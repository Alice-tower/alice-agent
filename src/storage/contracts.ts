import type { ChatMessage, Conversation, ConversationDetail, RunSnapshot, SubmitMessage } from "../api/contracts.ts";
import type { ProviderMessage } from "../provider/index.ts";

export interface ChatStore {
  createConversation(): Conversation;
  listConversations(): Conversation[];
  conversation(id: string): ConversationDetail | undefined;
  history(id: string): ProviderMessage[];
  run(id: string): RunSnapshot | undefined;
  findRequest(conversationId: string, requestId: string): { run: RunSnapshot; input: SubmitMessage } | undefined;
  startRun(run: RunSnapshot, input: SubmitMessage): void;
  startAssistantMessage(run: RunSnapshot, createdAt: number): void;
  updateRun(run: RunSnapshot): void;
  finishRun(run: RunSnapshot, history?: ProviderMessage[]): void;
  interruptRunning(): void;
  close(): void;
}

export type { ChatMessage };
