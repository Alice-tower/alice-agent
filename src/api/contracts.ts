import { z } from "zod";

export const API_VERSION = 1;
export const ErrorResponseSchema = z.object({ error: z.object({ code: z.string(), message: z.string() }) });
export const ServerInfoSchema = z.object({
  version: z.literal(API_VERSION),
  providers: z.array(z.object({ id: z.string(), protocol: z.string() })),
  tools: z.array(z.string()),
});
export const ModelsSchema = z.array(z.object({ id: z.string(), name: z.string() }));
export const ConversationSchema = z.object({ id: z.string(), title: z.string(), createdAt: z.number(), updatedAt: z.number() });
export const ConversationsSchema = z.array(ConversationSchema);
export const RunStatusSchema = z.enum(["running", "completed", "failed", "cancelled", "limited", "interrupted"]);
export const RunSchema = z.object({
  id: z.string(), conversationId: z.string(), requestId: z.string(), providerId: z.string(), model: z.string(),
  status: RunStatusSchema, text: z.string(), reasoning: z.string(), turns: z.number(),
  tools: z.array(z.object({
    callId: z.string(), name: z.string(), arguments: z.string(),
    status: z.enum(["running", "completed", "failed"]), output: z.string().optional(),
    startedAt: z.number().optional(), finishedAt: z.number().optional(),
  })),
  error: z.object({ code: z.string(), message: z.string() }).optional(),
  // updatedAt is a monotonic snapshot ordering marker; use the phase timestamps for elapsed time.
  createdAt: z.number(), updatedAt: z.number(),
  messageCreatedAt: z.number().optional(), firstOutputAt: z.number().optional(), finishedAt: z.number().optional(),
});
export const ChatMessageSchema = z.object({
  id: z.string(), role: z.enum(["user", "assistant"]), text: z.string(), createdAt: z.number(), runId: z.string(),
});
export const ConversationDetailSchema = z.object({
  conversation: ConversationSchema, messages: z.array(ChatMessageSchema), runs: z.array(RunSchema),
});
export const SubmitMessageSchema = z.strictObject({
  requestId: z.uuid(), text: z.string().trim().min(1).max(32_000),
  providerId: z.string().min(1), model: z.string().min(1).max(256),
});
export type ServerInfo = z.infer<typeof ServerInfoSchema>;
export type Conversation = z.infer<typeof ConversationSchema>;
export type ConversationDetail = z.infer<typeof ConversationDetailSchema>;
export type ChatMessage = z.infer<typeof ChatMessageSchema>;
export type RunSnapshot = z.infer<typeof RunSchema>;
export type SubmitMessage = z.infer<typeof SubmitMessageSchema>;
