import { runAgentLoop } from "@earendil-works/pi-agent-core";
import type { AgentEvent, AgentTool } from "@earendil-works/pi-agent-core";
import type { Model } from "@earendil-works/pi-ai";
import { z } from "zod";
import type { JsonObject, ProviderMessage } from "../provider/index.ts";
import type { AgentRuntime, RuntimeEvent, RuntimeRequest, RuntimeResult, RuntimeServices } from "./contracts.ts";
import { abortable, Channel } from "./channel.ts";
import { fromPi, toPi } from "./messages.ts";
import { providerBridge } from "./provider-bridge.ts";

const limits = z.object({ model: z.string().min(1), messages: z.array(z.unknown()).min(1), maxTurns: z.number().int().min(1).max(100).default(8), maxOutputTokens: z.number().int().positive().optional(), timeoutMs: z.number().int().min(1).max(2_147_483_647).default(120_000) });
const toolNames = z.array(z.string().regex(/^[a-zA-Z0-9_-]{1,64}$/));
const jsonObject = z.record(z.string(), z.json());

function closeToolHistory(messages: ProviderMessage[]): ProviderMessage[] {
  const pending = new Map<string, string>();
  for (const message of messages) {
    if (message.role === "assistant") for (const block of message.content) if (block.type === "tool-call") pending.set(block.id, block.name);
    if (message.role === "tool") pending.delete(message.toolCallId);
  }
  return [...messages, ...[...pending].map(([toolCallId, name]) => ({ role: "tool" as const, toolCallId, name, content: "任务已中断；工具未完成或结果未确认，请勿假设已执行。", isError: true }))];
}

export function createPiRuntime(): AgentRuntime {
  return {
    async *run(request: RuntimeRequest, services: RuntimeServices): AsyncGenerator<RuntimeEvent> {
      const parsed = limits.safeParse(request);
      if (!parsed.success || !toolNames.safeParse(services.allowedTools).success || new Set(services.tools.map(tool => tool.name)).size !== services.tools.length) {
        yield { type: "done", result: { status: "failed", messages: [], turns: 0, error: { code: "invalid-request", message: "无效的任务参数或工具配置。" } } };
        return;
      }
      const channel = new Channel<RuntimeEvent>();
      const controller = new AbortController();
      const allowed = new Set(services.allowedTools);
      const history: ProviderMessage[] = structuredClone(request.messages);
      let turns = 0;
      let timedOut = false;
      let limitReached = false;
      let error: RuntimeResult["error"];
      const cancel = () => controller.abort();
      services.signal?.addEventListener("abort", cancel, { once: true });
      if (services.signal?.aborted) cancel();
      const timer = setTimeout(() => { timedOut = true; controller.abort(); }, parsed.data.timeoutMs);
      timer.unref();
      const model: Model<"alice-provider"> = { id: request.model, name: request.model, api: "alice-provider", provider: services.provider.id, baseUrl: "", reasoning: false, input: ["text", "image"], contextWindow: Number.MAX_SAFE_INTEGER, maxTokens: Number.MAX_SAFE_INTEGER, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } };
      const toolError = () => { error ??= { code: "tool-error", message: "工具执行失败。" }; };
      const tools: AgentTool<JsonObject>[] = services.tools.map(tool => ({
        name: tool.name, label: tool.name, description: tool.description, parameters: tool.parameters,
        execute: async (_id, args) => {
          if (!allowed.has(tool.name)) {
            error = { code: "permission-denied", message: "工具未获本次任务授权。" };
            return { content: [{ type: "text", text: error.message }], details: {}, isError: true, terminate: true };
          }
          try {
            const validated = jsonObject.parse(args);
            const content = await abortable(tool.execute(validated, controller.signal), controller.signal);
            if (typeof content !== "string") throw new Error("Invalid tool result");
            return { content: [{ type: "text", text: content }], details: {} };
          } catch {
            toolError();
            return { content: [{ type: "text", text: "工具执行失败。" }], details: {}, isError: true, terminate: true };
          }
        },
      }));
      const emit = (event: AgentEvent): void => {
        if (event.type === "turn_start") turns++;
        if (event.type === "message_start" && event.message.role === "assistant") {
          channel.push({ type: "message-start", turn: turns, createdAt: event.message.timestamp });
        }
        if (event.type === "message_update") {
          const update = event.assistantMessageEvent;
          if (update.type === "text_delta" || update.type === "thinking_delta") channel.push({ type: update.type === "text_delta" ? "text-delta" : "reasoning-delta", text: update.delta, index: update.contentIndex, turn: turns });
        }
        if (event.type === "message_end") {
          const message = event.message;
          if (message.role === "assistant" && (message.stopReason === "stop" || message.stopReason === "toolUse")) {
            const converted = fromPi(message);
            if (converted.role === "assistant") {
              history.push(converted);
              channel.push({ type: "assistant-message", message: converted, turn: turns });
            }
          } else if (message.role === "toolResult") {
            const converted = fromPi(message);
            if (converted.role === "tool" && message.isError) converted.content = "工具执行失败或未执行。";
            history.push(converted);
          }
        }
        if (event.type === "tool_execution_start") channel.push({ type: "tool-start", toolCallId: event.toolCallId, name: event.toolName, arguments: jsonObject.parse(event.args) });
        if (event.type === "tool_execution_end") {
          const result: unknown = event.result;
          const content = event.isError ? "工具执行失败或未执行。" : z.object({ content: z.array(z.object({ type: z.string(), text: z.string().optional() })) }).parse(result).content.map(part => part.text ?? "").join("\n");
          if (event.isError) toolError();
          channel.push({ type: "tool-end", toolCallId: event.toolCallId, name: event.toolName, content, isError: event.isError });
        }
      };
      const producer = (async () => {
        try {
          if (controller.signal.aborted) return;
          await runAgentLoop([], { messages: toPi(request.messages, model), tools }, {
            model, convertToLlm: messages => messages, toolExecution: "sequential",
            beforeToolCall: async ({ toolCall }) => {
              if (error) return { block: true, reason: "本次任务已失败，后续工具未执行。", terminate: true };
              if (!allowed.has(toolCall.name)) {
                error = { code: "permission-denied", message: "工具未获本次任务授权。" };
                return { block: true, reason: error.message, terminate: true };
              }
              return undefined;
            },
            finishTurn: ({ message, toolResults }) => {
              if (message.stopReason === "length") { limitReached = true; return { action: "end" }; }
              if (toolResults.some(result => result.isError) || error || controller.signal.aborted) return { action: "end" };
              if (turns >= parsed.data.maxTurns && message.content.some(part => part.type === "toolCall")) { limitReached = true; return { action: "end" }; }
              return undefined;
            },
          }, emit, controller.signal, providerBridge(request, services, controller.signal, (code, message) => { error = { code, message }; }));
        } catch {
          error ??= { code: "runtime-error", message: "任务执行失败。" };
        } finally {
          clearTimeout(timer);
          services.signal?.removeEventListener("abort", cancel);
          const status: RuntimeResult["status"] = timedOut ? "limited" : controller.signal.aborted ? "cancelled" : error ? "failed" : limitReached ? "limited" : "completed";
          if (timedOut) error = { code: "timeout", message: "任务已达到时间上限。" };
          else if (controller.signal.aborted) error = { code: "cancelled", message: "任务已取消。" };
          else if (limitReached) error = { code: "limit", message: "任务已达到轮数或输出上限。" };
          channel.push({ type: "done", result: { status, messages: closeToolHistory(history), turns, ...(error ? { error } : {}) } });
          channel.end();
        }
      })();
      try { for await (const event of channel) yield event; }
      finally { controller.abort(); await producer; }
    },
  };
}
