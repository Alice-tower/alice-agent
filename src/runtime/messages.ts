import type { AssistantMessage as PiAssistantMessage, Message, Model, Tool } from "@earendil-works/pi-ai";
import { getSystemMessageText } from "@earendil-works/pi-ai";
import { z } from "zod";
import type { AssistantMessage, ProviderMessage, TokenUsage } from "../provider/index.ts";

const jsonObject = z.record(z.string(), z.json());

export const emptyUsage = () => ({ input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } });

export function piAssistant(message: AssistantMessage, model: Model<string>, finishReason: "stop" | "length" | "tool-calls", usage?: TokenUsage): PiAssistantMessage {
  return {
    role: "assistant", api: "alice-provider", provider: message.origin?.providerId ?? model.provider,
    model: message.origin?.model ?? model.id, timestamp: Date.now(),
    content: message.content.map(part => {
      if (part.type === "text") return { type: "text", text: part.text, textSignature: part.signature };
      if (part.type === "reasoning") return { type: "thinking", thinking: part.text, thinkingSignature: part.signature };
      return { type: "toolCall", id: part.id, name: part.name, arguments: part.arguments };
    }),
    usage: usage ? { ...emptyUsage(), input: usage.inputTokens, output: usage.outputTokens, totalTokens: usage.totalTokens, reasoning: usage.reasoningTokens } : emptyUsage(),
    stopReason: finishReason === "tool-calls" ? "toolUse" : finishReason,
  };
}

export function toPi(messages: ProviderMessage[], model: Model<string>): Message[] {
  return messages.map((message): Message => {
    if (message.role === "system") return { role: "system", content: message.content, timestamp: 0 };
    if (message.role === "user") return { role: "user", content: message.content, timestamp: 0 };
    if (message.role === "assistant") return piAssistant(message, model, message.content.some(part => part.type === "tool-call") ? "tool-calls" : "stop");
    return { role: "toolResult", toolCallId: message.toolCallId, toolName: message.name, content: [{ type: "text", text: message.content }], isError: message.isError ?? false, timestamp: 0 };
  });
}

export function fromPi(message: Message): ProviderMessage {
  if (message.role === "system") return { role: "system", content: getSystemMessageText(message) };
  if (message.role === "user") return { role: "user", content: message.content };
  if (message.role === "toolResult") return { role: "tool", toolCallId: message.toolCallId, name: message.toolName, content: message.content.filter(part => part.type === "text").map(part => part.text).join("\n"), isError: message.isError };
  return {
    role: "assistant", origin: { providerId: message.provider, model: message.model },
    content: message.content.map(part => {
      if (part.type === "text") return { type: "text", text: part.text, ...(part.textSignature ? { signature: part.textSignature } : {}) };
      if (part.type === "thinking") return { type: "reasoning", text: part.thinking, ...(part.thinkingSignature ? { signature: part.thinkingSignature } : {}) };
      return { type: "tool-call", id: part.id, name: part.name, arguments: jsonObject.parse(part.arguments) };
    }),
  };
}

export function declaredTools(tools: Tool[]): { name: string; description: string; parameters: import("../provider/index.ts").JsonObject }[] {
  return tools.map(tool => ({ name: tool.name, description: tool.description, parameters: jsonObject.parse(tool.parameters) }));
}
