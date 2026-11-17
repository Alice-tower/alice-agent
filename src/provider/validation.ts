import { z } from "zod";
import type { CompletionRequest, JsonValue, ProviderConfig, RequestOptions } from "./contracts.ts";
import { ProviderError } from "./errors.ts";

export const json: z.ZodType<JsonValue> = z.lazy(() => z.union([
  z.null(), z.boolean(), z.number().finite(), z.string(), z.array(json), z.record(z.string(), json),
]));
export const jsonObject = z.record(z.string(), json);
const identifier = z.string().trim().min(1).max(256);
const toolName = z.string().regex(/^[a-zA-Z0-9_-]{1,64}$/);
const text = z.strictObject({ type: z.literal("text"), text: z.string(), signature: z.string().optional() });
const reasoning = z.strictObject({ type: z.literal("reasoning"), text: z.string(), signature: z.string().optional() });
const toolCall = z.strictObject({ type: z.literal("tool-call"), id: identifier, name: toolName, arguments: jsonObject });
const image = z.strictObject({ type: z.literal("image"), mimeType: z.string().regex(/^image\/[a-zA-Z0-9.+-]+$/), data: z.string().min(1) });
const message = z.discriminatedUnion("role", [
  z.strictObject({ role: z.literal("system"), content: z.string() }),
  z.strictObject({ role: z.literal("user"), content: z.union([z.string(), z.array(z.union([text, image])).min(1)]) }),
  z.strictObject({ role: z.literal("assistant"), content: z.array(z.union([text, reasoning, toolCall])), origin: z.strictObject({ providerId: identifier, model: identifier }).optional() }),
  z.strictObject({ role: z.literal("tool"), toolCallId: identifier, name: toolName, content: z.string(), isError: z.boolean().optional() }),
]);
const tool = z.strictObject({ name: toolName, description: z.string(), parameters: jsonObject.refine(value => value.type === "object", "Tool schema must have type object") });
const timeout = z.number().int().min(1).max(2_147_483_647);
const configSchema = z.strictObject({
  id: identifier,
  protocol: identifier,
  baseUrl: z.url().refine(value => {
    const url = new URL(value);
    return ["http:", "https:"].includes(url.protocol) && !url.username && !url.password && !url.search && !url.hash;
  }),
  apiKey: z.string().min(1).refine(value => !/[\r\n]/.test(value)).optional(),
  timeoutMs: timeout.optional(),
  settings: jsonObject.optional(),
});
const requestSchema = z.strictObject({
  model: identifier,
  messages: z.array(message).min(1),
  tools: z.array(tool).optional(),
  toolChoice: z.union([z.enum(["auto", "none", "required"]), z.strictObject({ name: toolName })]).optional(),
  maxOutputTokens: z.number().int().positive().optional(),
  temperature: z.number().min(0).max(2).optional(),
});

export function parseConfig(input: ProviderConfig): ProviderConfig {
  const parsed = configSchema.safeParse(input);
  if (!parsed.success) throw new ProviderError("configuration", "configuration", "Invalid provider configuration.");
  return parsed.data;
}

export function parseRequest(input: CompletionRequest, providerId: string): CompletionRequest {
  const parsed = requestSchema.safeParse(input);
  if (!parsed.success) throw new ProviderError("invalid-request", providerId, "Invalid completion request.");
  const request = parsed.data;
  const names = new Set(request.tools?.map(tool => tool.name));
  if (names.size !== (request.tools?.length ?? 0)
    || (request.toolChoice === "required" && names.size === 0)
    || (typeof request.toolChoice === "object" && !names.has(request.toolChoice.name))) {
    throw new ProviderError("invalid-request", providerId, "Tool selection must refer to unique declared tools.");
  }
  const pending = new Map<string, string>();
  const usedIds = new Set<string>();
  for (const message of request.messages) {
    if (message.role === "tool") {
      if (pending.get(message.toolCallId) !== message.name) {
        throw new ProviderError("invalid-request", providerId, "Tool result does not match a pending tool call.");
      }
      pending.delete(message.toolCallId);
    } else {
      if (pending.size > 0) throw new ProviderError("invalid-request", providerId, "Pending tool calls require results before the next message.");
      if (message.role === "assistant") {
        for (const part of message.content) {
          if (part.type !== "tool-call") continue;
          if (usedIds.has(part.id)) throw new ProviderError("invalid-request", providerId, "Duplicate tool call ID.");
          usedIds.add(part.id);
          pending.set(part.id, part.name);
        }
      }
    }
  }
  if (pending.size > 0) throw new ProviderError("invalid-request", providerId, "Tool call results are missing.");
  return request;
}

export function operation(providerId: string, defaultTimeout: number, options: RequestOptions = {}) {
  const parsed = timeout.safeParse(options.timeoutMs ?? defaultTimeout);
  if (!parsed.success) throw new ProviderError("invalid-request", providerId, "Invalid operation timeout.");
  const controller = new AbortController();
  let timedOut = false;
  const cancel = () => controller.abort();
  options.signal?.addEventListener("abort", cancel, { once: true });
  if (options.signal?.aborted) cancel();
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, parsed.data);
  timer.unref();
  return {
    signal: controller.signal,
    check() {
      if (controller.signal.aborted) {
        throw new ProviderError(timedOut ? "timeout" : "cancelled", providerId, timedOut ? "Provider operation timed out." : "Provider operation cancelled.");
      }
    },
    dispose() {
      clearTimeout(timer);
      options.signal?.removeEventListener("abort", cancel);
      controller.abort();
    },
  };
}
