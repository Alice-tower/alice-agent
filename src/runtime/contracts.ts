import type { AssistantMessage, JsonObject, LlmProvider, ProviderMessage, ToolDefinition } from "../provider/index.ts";

export interface RuntimeTool extends ToolDefinition {
  execute(args: JsonObject, signal: AbortSignal): Promise<string>;
}

export interface RuntimeRequest {
  messages: ProviderMessage[];
  model: string;
  maxTurns?: number;
  maxOutputTokens?: number;
  timeoutMs?: number;
}

export interface RuntimeServices {
  provider: LlmProvider;
  tools: RuntimeTool[];
  /** Checked again immediately before execution, independently of advertised tools. */
  allowedTools: string[];
  signal?: AbortSignal;
  prepareContext?: (messages: ProviderMessage[], signal: AbortSignal) => Promise<ProviderMessage[]>;
}

export interface RuntimeResult {
  status: "completed" | "failed" | "cancelled" | "limited";
  /** Full continuation history, including tool results; never contains partial tool arguments. */
  messages: ProviderMessage[];
  turns: number;
  error?: { code: string; message: string };
}

export type RuntimeEvent =
  | { type: "message-start"; turn: number; createdAt: number }
  | { type: "text-delta" | "reasoning-delta"; text: string; index: number; turn: number }
  | { type: "assistant-message"; message: AssistantMessage; turn: number }
  | { type: "tool-start"; toolCallId: string; name: string; arguments: JsonObject }
  | { type: "tool-end"; toolCallId: string; name: string; content: string; isError: boolean }
  | { type: "done"; result: RuntimeResult };

export interface AgentRuntime {
  run(request: RuntimeRequest, services: RuntimeServices): AsyncIterable<RuntimeEvent>;
}
