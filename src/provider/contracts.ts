export type JsonValue = null | boolean | number | string | JsonValue[] | JsonObject;
export type JsonObject = { [key: string]: JsonValue };

export interface TextPart {
  type: "text";
  text: string;
  /** Opaque replay data; callers must preserve it without interpreting it. */
  signature?: string;
}

export interface ReasoningPart {
  type: "reasoning";
  text: string;
  signature?: string;
}

export interface ToolCallPart {
  type: "tool-call";
  id: string;
  name: string;
  arguments: JsonObject;
}

export interface ImagePart {
  type: "image";
  mimeType: string;
  data: string;
}

export interface AssistantMessage {
  role: "assistant";
  content: (TextPart | ReasoningPart | ToolCallPart)[];
  /** Origin matters when replaying signed reasoning across providers. */
  origin?: { providerId: string; model: string };
}

export type ProviderMessage =
  | { role: "system"; content: string }
  | { role: "user"; content: string | (TextPart | ImagePart)[] }
  | AssistantMessage
  | { role: "tool"; toolCallId: string; name: string; content: string; isError?: boolean };

export interface ToolDefinition {
  name: string;
  description: string;
  parameters: JsonObject;
}

export interface CompletionRequest {
  model: string;
  messages: ProviderMessage[];
  tools?: ToolDefinition[];
  toolChoice?: "auto" | "none" | "required" | { name: string };
  maxOutputTokens?: number;
  temperature?: number;
}

export interface RequestOptions {
  signal?: AbortSignal;
  /** Total operation deadline, including streaming, in milliseconds. */
  timeoutMs?: number;
}

export interface TokenUsage {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  cachedInputTokens?: number;
  reasoningTokens?: number;
}

export interface CompletionResult {
  providerId: string;
  model: string;
  responseId?: string;
  message: AssistantMessage;
  finishReason: "stop" | "length" | "tool-calls";
  /** Absent if the server did not report usage; never an invented zero. */
  usage?: TokenUsage;
}

export type ProviderEvent =
  | { type: "start" }
  | { type: "text-delta"; index: number; text: string }
  | { type: "reasoning-delta"; index: number; text: string }
  | { type: "tool-call-delta"; index: number; argumentsDelta: string }
  | { type: "tool-call"; index: number; toolCall: ToolCallPart }
  | { type: "done"; result: CompletionResult };

export interface ModelInfo {
  id: string;
  name: string;
}

/** Backend-only connection configuration. Never return credentials to the GUI. */
export interface ProviderConfig {
  id: string;
  protocol: string;
  baseUrl: string;
  apiKey?: string;
  timeoutMs?: number;
  /** Validated by the protocol adapter, rather than interpreted by the registry. */
  settings?: JsonObject;
}

export interface LlmProvider {
  readonly id: string;
  readonly protocol: string;
  listModels(options?: RequestOptions): Promise<ModelInfo[]>;
  complete(request: CompletionRequest, options?: RequestOptions): Promise<CompletionResult>;
  /** Single-consumer stream. Failures throw ProviderError; no done event follows. */
  stream(request: CompletionRequest, options?: RequestOptions): AsyncIterable<ProviderEvent>;
}

export interface ProviderAdapter {
  readonly protocol: string;
  create(config: ProviderConfig): LlmProvider;
}
