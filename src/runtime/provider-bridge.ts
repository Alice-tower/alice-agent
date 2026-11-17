import { createAssistantMessageEventStream, getCurrentTools } from "@earendil-works/pi-ai";
import type { AssistantMessage as PiAssistantMessage } from "@earendil-works/pi-ai";
import type { StreamFn } from "@earendil-works/pi-agent-core";
import { ProviderError } from "../provider/index.ts";
import type { RuntimeRequest, RuntimeServices } from "./contracts.ts";
import { abortable } from "./channel.ts";
import { declaredTools, emptyUsage, fromPi, piAssistant } from "./messages.ts";

export function providerBridge(request: RuntimeRequest, services: RuntimeServices, signal: AbortSignal, failed: (code: string, message: string) => void): StreamFn {
  return (model, context) => {
    const stream = createAssistantMessageEventStream();
    const partial: PiAssistantMessage = { role: "assistant", api: "alice-provider", provider: services.provider.id, model: request.model, content: [], usage: emptyUsage(), stopReason: "pending", timestamp: Date.now() };
    void (async () => {
      let iterator: AsyncIterator<import("../provider/index.ts").ProviderEvent> | undefined;
      try {
        const messages = context.messages.map(fromPi).filter(message => message.role !== "system" || message.content !== "");
        const input = services.prepareContext ? await abortable(services.prepareContext(messages, signal), signal) : messages;
        iterator = services.provider.stream({
          model: request.model, messages: input,
          tools: declaredTools(getCurrentTools(context.messages)),
          maxOutputTokens: request.maxOutputTokens,
        }, { signal, timeoutMs: request.timeoutMs ?? 120_000 })[Symbol.asyncIterator]();
        let started = false;
        while (true) {
          const next = await abortable(iterator.next(), signal);
          if (next.done) break;
          const event = next.value;
          if (!started) { started = true; stream.push({ type: "start", partial }); }
          if (event.type === "text-delta" || event.type === "reasoning-delta") {
            const existing = partial.content[event.index];
            if (event.type === "text-delta") {
              if (!existing) partial.content[event.index] = { type: "text", text: "" };
              const block = partial.content[event.index];
              if (block?.type !== "text") throw new Error("Invalid content block");
              block.text += event.text;
              stream.push({ type: "text_delta", contentIndex: event.index, delta: event.text, partial });
            } else {
              if (!existing) partial.content[event.index] = { type: "thinking", thinking: "" };
              const block = partial.content[event.index];
              if (block?.type !== "thinking") throw new Error("Invalid content block");
              block.thinking += event.text;
              stream.push({ type: "thinking_delta", contentIndex: event.index, delta: event.text, partial });
            }
          } else if (event.type === "done") {
            const message = piAssistant(event.result.message, model, event.result.finishReason, event.result.usage);
            stream.push({ type: "done", reason: message.stopReason === "toolUse" ? "toolUse" : event.result.finishReason === "length" ? "length" : "stop", message });
            stream.end();
            return;
          }
        }
        throw new Error("Provider ended without result");
      } catch (error) {
        const code = signal.aborted ? "cancelled" : error instanceof ProviderError ? error.code : "provider-error";
        const message = error instanceof ProviderError ? error.message : signal.aborted ? "任务已取消。" : "模型调用失败。";
        failed(code, message);
        partial.stopReason = signal.aborted ? "aborted" : "error";
        partial.errorMessage = message;
        stream.push({ type: "error", reason: partial.stopReason, error: partial });
        stream.end();
      } finally {
        if (iterator?.return) void Promise.resolve(iterator.return()).catch(() => {});
      }
    })();
    return stream;
  };
}
