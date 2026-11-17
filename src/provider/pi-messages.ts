import type { AssistantMessage as PiAssistantMessage, Message, Model, ToolCall } from "@earendil-works/pi-ai";
import type { AssistantMessage, CompletionRequest, CompletionResult, ProviderMessage, TokenUsage, ToolCallPart } from "./contracts.ts";
import { ProviderError } from "./errors.ts";
import { jsonObject } from "./validation.ts";

const emptyUsage = () => ({
  input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
});

export function toPiMessages(messages: ProviderMessage[], model: Model<"openai-completions">): Message[] {
  return messages.map((message): Message => {
    switch (message.role) {
      case "system": return { role: "system", content: message.content, timestamp: 0 };
      case "user": return {
        role: "user", timestamp: 0,
        content: typeof message.content === "string" ? message.content : message.content.map(part =>
          part.type === "image" ? part : { type: "text", text: part.text }),
      };
      case "tool": return {
        role: "toolResult", toolCallId: message.toolCallId, toolName: message.name,
        content: [{ type: "text", text: message.content }], isError: message.isError ?? false, timestamp: 0,
      };
      case "assistant": return {
        role: "assistant", api: "openai-completions", timestamp: 0,
        provider: message.origin?.providerId ?? model.provider,
        model: message.origin?.model ?? model.id,
        content: message.content.map(part => {
          if (part.type === "text") return { type: "text", text: part.text, textSignature: part.signature };
          if (part.type === "reasoning") return { type: "thinking", thinking: part.text, thinkingSignature: part.signature };
          return { type: "toolCall", id: part.id, name: part.name, arguments: part.arguments };
        }),
        stopReason: message.content.some(part => part.type === "tool-call") ? "toolUse" : "stop",
        usage: emptyUsage(),
      };
    }
  });
}

export function fromPiToolCall(call: ToolCall, providerId: string): ToolCallPart {
  const args = jsonObject.safeParse(call.arguments);
  if (call.argumentsError || !call.id || !/^[a-zA-Z0-9_-]{1,64}$/.test(call.name) || !args.success) {
    throw new ProviderError("protocol", providerId, "Provider returned an invalid tool call or invalid JSON arguments.");
  }
  return { type: "tool-call", id: call.id, name: call.name, arguments: args.data };
}

export function fromPiResult(message: PiAssistantMessage, request: CompletionRequest, providerId: string, usage?: TokenUsage): CompletionResult {
  if (message.stopReason !== "stop" && message.stopReason !== "length" && message.stopReason !== "toolUse") {
    throw new ProviderError("protocol", providerId, "Provider returned an unsupported completion state.");
  }
  const content: AssistantMessage["content"] = message.content.map(part => {
    if (part.type === "text") return { type: "text", text: part.text, ...(part.textSignature ? { signature: part.textSignature } : {}) };
    if (part.type === "thinking") return { type: "reasoning", text: part.thinking, ...(part.thinkingSignature ? { signature: part.thinkingSignature } : {}) };
    return fromPiToolCall(part, providerId);
  });
  const calls = content.filter(part => part.type === "tool-call");
  const toolNames = new Set(request.tools?.map(tool => tool.name));
  if (new Set(calls.map(call => call.id)).size !== calls.length || calls.some(call => !toolNames.has(call.name))) {
    throw new ProviderError("protocol", providerId, "Provider returned duplicate or undeclared tool calls.");
  }
  if ((message.stopReason === "toolUse") !== (calls.length > 0)) {
    throw new ProviderError("protocol", providerId, "Provider finish reason does not match its tool calls.");
  }
  const selection = request.toolChoice;
  if (calls.length > 0 && (selection === "none"
    || (typeof selection === "object" && calls.some(call => call.name !== selection.name)))) {
    throw new ProviderError("protocol", providerId, "Provider returned a tool call outside the requested tool selection.");
  }
  const model = message.responseModel ?? request.model;
  return {
    providerId, model,
    ...(message.responseId ? { responseId: message.responseId } : {}),
    message: { role: "assistant", content, origin: { providerId, model: request.model } },
    finishReason: message.stopReason === "toolUse" ? "tool-calls" : message.stopReason,
    ...(usage ? { usage } : {}),
  };
}
