import { stream as piStream } from "@earendil-works/pi-ai/api/openai-completions";
import { normalizeContext } from "@earendil-works/pi-ai/utils/transcript";
import type { Model, OpenAICompletionsOptions } from "@earendil-works/pi-ai";
import { z } from "zod";
import type { CompletionRequest, CompletionResult, LlmProvider, ModelInfo, ProviderAdapter, ProviderConfig, ProviderEvent, RequestOptions, TokenUsage } from "./contracts.ts";
import { httpError, ProviderError } from "./errors.ts";
import { fromPiResult, fromPiToolCall, toPiMessages } from "./pi-messages.ts";
import { operation, parseConfig, parseRequest } from "./validation.ts";

const settingsSchema = z.strictObject({
  maxTokensField: z.enum(["max_tokens", "max_completion_tokens"]).default("max_tokens"),
  streamUsage: z.boolean().default(true),
});
const modelsSchema = z.object({ data: z.array(z.object({ id: z.string().min(1) })) });
const count = z.number().int().nonnegative();
const usageSchema = z.object({
  prompt_tokens: count, completion_tokens: count, total_tokens: count.optional(),
  cached_tokens: count.optional(), prompt_cache_hit_tokens: count.optional(),
  prompt_tokens_details: z.object({ cached_tokens: count.optional() }).nullish(),
  completion_tokens_details: z.object({ reasoning_tokens: count.optional() }).nullish(),
});
const chunkSchema = z.object({
  id: z.string().optional(), model: z.string().optional(),
  choices: z.array(z.object({
    index: count,
    finish_reason: z.string().nullable().optional(),
    delta: z.object({
      role: z.string().optional(), content: z.string().nullish(),
      reasoning_content: z.string().nullish(), reasoning: z.string().nullish(), reasoning_text: z.string().nullish(),
      tool_calls: z.array(z.object({
        index: count, id: z.string().optional(), type: z.literal("function").optional(),
        function: z.object({ name: z.string().optional(), arguments: z.string().optional() }).optional(),
      })).optional(),
    }),
  })),
  usage: usageSchema.nullish(),
});

/** OpenAI-compatible Chat Completions adapter. Pi is an internal implementation detail. */
class OpenAIChatProvider implements LlmProvider {
  readonly id: string;
  readonly protocol = "openai-chat";
  readonly #config: ProviderConfig;
  readonly #settings: z.infer<typeof settingsSchema>;

  constructor(input: ProviderConfig) {
    const config = parseConfig(input);
    const settings = settingsSchema.safeParse(config.settings ?? {});
    if (config.protocol !== this.protocol || !settings.success) {
      throw new ProviderError("configuration", config.id, "Invalid OpenAI Chat Completions settings.");
    }
    this.id = config.id;
    this.#config = { ...config, baseUrl: config.baseUrl.replace(/\/+$/, "") };
    this.#settings = settings.data;
  }

  async listModels(options?: RequestOptions): Promise<ModelInfo[]> {
    const life = operation(this.id, this.#config.timeoutMs ?? 120_000, options);
    try {
      life.check();
      const response = await fetch(`${this.#config.baseUrl}/models`, {
        headers: this.#config.apiKey ? { Authorization: `Bearer ${this.#config.apiKey}` } : {},
        signal: life.signal, redirect: "error",
      });
      if (!response.ok) {
        await response.body?.cancel();
        throw httpError(this.id, response.status);
      }
      const parsed = modelsSchema.safeParse(await response.json());
      if (!parsed.success) throw new ProviderError("protocol", this.id, "Provider returned an invalid model list.");
      life.check();
      return [...new Set(parsed.data.data.map(model => model.id))].map(id => ({ id, name: id }));
    } catch (error) {
      life.check();
      if (error instanceof ProviderError) throw error;
      if (error instanceof SyntaxError) throw new ProviderError("protocol", this.id, "Provider returned invalid JSON.");
      throw new ProviderError("network", this.id, "Could not retrieve the provider model list.");
    } finally {
      life.dispose();
    }
  }

  async complete(request: CompletionRequest, options?: RequestOptions): Promise<CompletionResult> {
    for await (const event of this.stream(request, options)) {
      if (event.type === "done") return event.result;
    }
    throw new ProviderError("protocol", this.id, "Provider stream ended without a result.");
  }

  async *stream(input: CompletionRequest, options?: RequestOptions): AsyncGenerator<ProviderEvent> {
    const request = parseRequest(input, this.id);
    const life = operation(this.id, this.#config.timeoutMs ?? 120_000, options);
    let transportError: ProviderError | undefined;
    let receivedResponse = false;
    let usage: TokenUsage | undefined;
    const toolNames = new Set(request.tools?.map(tool => tool.name));
    const toolIds = new Set<string>();
    try {
      life.check();
      const model: Model<"openai-completions"> = {
        id: request.model, name: request.model, api: "openai-completions", provider: this.id,
        baseUrl: this.#config.baseUrl, input: ["text", "image"], reasoning: false,
        // Required Pi metadata only; not advertised as actual model limits or prices.
        contextWindow: Number.MAX_SAFE_INTEGER, maxTokens: Number.MAX_SAFE_INTEGER,
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
        compat: {
          maxTokensField: this.#settings.maxTokensField,
          supportsUsageInStreaming: this.#settings.streamUsage,
          supportsDeveloperRole: false,
          supportsStore: false, supportsReasoningEffort: false, supportsStrictMode: false,
        },
      };
      const streamOptions: OpenAICompletionsOptions = {
        apiKey: this.#config.apiKey ?? "local-no-key",
        signal: life.signal, maxRetries: 0, cacheRetention: "none",
        maxTokens: request.maxOutputTokens, temperature: request.temperature,
        toolChoice: typeof request.toolChoice === "object"
          ? { type: "function", function: { name: request.toolChoice.name } } : request.toolChoice,
        identity: { userAgent: { custom: "alice-agent/0.1" }, attribution: null },
        fetch: async (url, init) => {
          try {
            // No credential-bearing redirects; keyless servers receive no invented bearer token.
            const headers = new Headers(init?.headers);
            if (!this.#config.apiKey) headers.delete("authorization");
            const response = await fetch(url, { ...init, headers, redirect: "error" });
            receivedResponse = true;
            if (!response.ok) transportError = httpError(this.id, response.status);
            else if (!response.headers.get("content-type")?.toLowerCase().includes("text/event-stream")) {
              transportError = new ProviderError("protocol", this.id, "Provider did not return an SSE stream.");
              await response.body?.cancel();
              throw transportError;
            }
            return response;
          } catch (error) {
            transportError ??= new ProviderError("network", this.id, "Could not connect to the provider.");
            throw error;
          }
        },
        onProviderStreamEvent: (raw) => {
          const parsed = chunkSchema.safeParse(raw);
          if (!parsed.success || parsed.data.choices.length > 1 || parsed.data.choices.some(choice => choice.index !== 0)) {
            throw new ProviderError("protocol", this.id, "Provider returned an invalid completion chunk.");
          }
          const reported = parsed.data.usage;
          if (reported) {
            const cached = reported.prompt_tokens_details?.cached_tokens ?? reported.prompt_cache_hit_tokens ?? reported.cached_tokens;
            const reasoning = reported.completion_tokens_details?.reasoning_tokens;
            usage = {
              inputTokens: reported.prompt_tokens,
              outputTokens: reported.completion_tokens,
              totalTokens: reported.total_tokens ?? reported.prompt_tokens + reported.completion_tokens,
              ...(cached !== undefined ? { cachedInputTokens: cached } : {}),
              ...(reasoning !== undefined ? { reasoningTokens: reasoning } : {}),
            };
          }
        },
      };
      const context = normalizeContext({
        messages: toPiMessages(request.messages, model),
        tools: request.tools?.map(tool => ({ name: tool.name, description: tool.description, parameters: tool.parameters })),
      });
      const stream = piStream(model, context, streamOptions);
      for await (const event of stream) {
        life.check();
        switch (event.type) {
          case "start": yield { type: "start" }; break;
          case "text_delta": yield { type: "text-delta", index: event.contentIndex, text: event.delta }; break;
          case "thinking_delta": yield { type: "reasoning-delta", index: event.contentIndex, text: event.delta }; break;
          case "toolcall_delta": yield { type: "tool-call-delta", index: event.contentIndex, argumentsDelta: event.delta }; break;
          case "toolcall_end": {
            const toolCall = fromPiToolCall(event.toolCall, this.id);
            if (!toolNames.has(toolCall.name) || toolIds.has(toolCall.id)
              || request.toolChoice === "none"
              || (typeof request.toolChoice === "object" && toolCall.name !== request.toolChoice.name)) {
              throw new ProviderError("protocol", this.id, "Provider returned duplicate or undeclared tool calls.");
            }
            toolIds.add(toolCall.id);
            yield { type: "tool-call", index: event.contentIndex, toolCall };
            break;
          }
          case "done":
            yield { type: "done", result: fromPiResult(event.message, request, this.id, usage) };
            return;
          case "error":
            throw transportError ?? new ProviderError(receivedResponse ? "protocol" : "network", this.id, "Provider completion failed.");
        }
      }
      throw new ProviderError("protocol", this.id, "Provider stream ended without a result.");
    } catch (error) {
      life.check();
      if (error instanceof ProviderError) throw error;
      throw transportError ?? new ProviderError("protocol", this.id, "Provider completion failed.");
    } finally {
      life.dispose();
    }
  }
}

export const openAIChatAdapter: ProviderAdapter = {
  protocol: "openai-chat",
  create: config => new OpenAIChatProvider(config),
};
