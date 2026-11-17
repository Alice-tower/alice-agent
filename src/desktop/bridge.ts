import type { Conversation, ConversationDetail, RunSnapshot, ServerInfo, SubmitMessage } from "../api/contracts.ts";

export interface ConnectionInput { baseUrl: string; token: string }
export interface ConnectionState { state: "connected" | "disconnected" | "reconnecting"; message?: string }
export type BridgeResult<T> = { ok: true; value: T } | { ok: false; error: string };

/** Only these capabilities are available to the isolated renderer. */
export interface DesktopBridge {
  settings(): Promise<BridgeResult<{ baseUrl: string; configured: boolean; error?: string }>>;
  restore(): Promise<BridgeResult<ServerInfo | undefined>>;
  connect(input: ConnectionInput): Promise<BridgeResult<ServerInfo>>;
  models(providerId: string): Promise<BridgeResult<{ id: string; name: string }[]>>;
  conversations(): Promise<BridgeResult<Conversation[]>>;
  createConversation(): Promise<BridgeResult<Conversation>>;
  conversation(id: string): Promise<BridgeResult<ConversationDetail>>;
  submit(id: string, message: SubmitMessage): Promise<BridgeResult<RunSnapshot>>;
  cancel(id: string): Promise<BridgeResult<RunSnapshot>>;
  watch(id: string): Promise<BridgeResult<void>>;
  onRun(handler: (run: RunSnapshot) => void): () => void;
  onConnection(handler: (state: ConnectionState) => void): () => void;
}
