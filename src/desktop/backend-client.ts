import { z } from "zod";
import {
  ConversationDetailSchema, ConversationSchema, ConversationsSchema, ErrorResponseSchema,
  ModelsSchema, RunSchema, ServerInfoSchema, SubmitMessageSchema,
} from "../api/contracts.ts";
import type { RunSnapshot, SubmitMessage } from "../api/contracts.ts";
import type { ConnectionInput } from "./bridge.ts";

const connectionSchema = z.strictObject({
  baseUrl: z.url(), token: z.string().min(1).max(8192).refine(value => !/[\r\n]/.test(value)),
});
export class ClientError extends Error {
  readonly kind: "connection" | "authentication" | "protocol" | "request";
  constructor(message: string, kind: "connection" | "authentication" | "protocol" | "request" = "request") {
    super(message);
    this.name = "ClientError";
    this.kind = kind;
  }
}

export function validateConnection(input: unknown, allowInsecureLan = false): ConnectionInput {
  const parsed = connectionSchema.safeParse(input);
  if (!parsed.success) throw new ClientError("请填写有效的后端地址和访问令牌。");
  const url = new URL(parsed.data.baseUrl);
  const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
  if (!(["https:"].includes(url.protocol) || (url.protocol === "http:" && (loopback || allowInsecureLan)))
    || url.username || url.password || url.search || url.hash || (url.pathname !== "/" && url.pathname !== "")) {
    throw new ClientError("请使用本机 HTTP 地址或 HTTPS 后端地址，不要包含额外路径和凭据。");
  }
  return { baseUrl: url.origin, token: parsed.data.token };
}

export class BackendClient {
  readonly #connection: ConnectionInput;

  constructor(input: ConnectionInput, allowInsecureLan = false) {
    this.#connection = validateConnection(input, allowInsecureLan);
  }

  async #request<T>(path: string, schema: z.ZodType<T>, method = "GET", body?: unknown): Promise<T> {
    try {
      const response = await fetch(`${this.#connection.baseUrl}/api/v1${path}`, {
        method,
        headers: { Authorization: `Bearer ${this.#connection.token}`, ...(body !== undefined ? { "Content-Type": "application/json" } : {}) },
        ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
        signal: AbortSignal.timeout(15_000), redirect: "error",
      });
      if (!response.ok) await this.#rejectResponse(response);
      const parsed = schema.safeParse(await response.json());
      if (!parsed.success) throw new ClientError("后端返回了不兼容的数据，请检查客户端与后端版本。", "protocol");
      return parsed.data;
    } catch (error) {
      if (error instanceof ClientError) throw error;
      throw new ClientError("无法连接后端，请检查地址、后端运行状态和网络。", "connection");
    }
  }

  async #rejectResponse(response: Response): Promise<never> {
    if (response.status === 401 || response.status === 403) {
      await response.body?.cancel();
      throw new ClientError("访问令牌无效，请重新连接。", "authentication");
    }
    const payload = ErrorResponseSchema.safeParse(await response.json().catch(() => null));
    const code = payload.success ? payload.data.error.code : "";
    if (response.status === 409) throw new ClientError("此会话已有任务正在执行，请等待完成或停止任务。");
    if (response.status === 404) throw new ClientError("内容已不存在，请刷新会话列表。");
    if (code === "invalid_request") throw new ClientError("请求内容不完整，请检查模型和消息。");
    throw new ClientError(`后端暂时无法完成请求（${response.status}）。`);
  }

  info() { return this.#request("/info", ServerInfoSchema); }
  models(providerId: string) { return this.#request(`/providers/${encodeURIComponent(providerId)}/models`, ModelsSchema); }
  conversations() { return this.#request("/conversations", ConversationsSchema); }
  createConversation() { return this.#request("/conversations", ConversationSchema, "POST", {}); }
  conversation(id: string) { return this.#request(`/conversations/${encodeURIComponent(id)}`, ConversationDetailSchema); }
  submit(id: string, input: SubmitMessage) {
    const parsed = SubmitMessageSchema.safeParse(input);
    if (!parsed.success) throw new ClientError("请先选择模型并输入消息。");
    return this.#request(`/conversations/${encodeURIComponent(id)}/messages`, RunSchema, "POST", parsed.data);
  }
  cancel(id: string) { return this.#request(`/runs/${encodeURIComponent(id)}/cancel`, RunSchema, "POST", {}); }

  async follow(id: string, signal: AbortSignal, update: (run: RunSnapshot) => void): Promise<void> {
    const connectionTimeout = new AbortController();
    const timer = setTimeout(() => connectionTimeout.abort(), 15_000);
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
    let idleTimer: ReturnType<typeof setTimeout> | undefined;
    let terminal = false;
    try {
      const response = await fetch(`${this.#connection.baseUrl}/api/v1/runs/${encodeURIComponent(id)}/events`, {
        headers: { Authorization: `Bearer ${this.#connection.token}`, Accept: "text/event-stream" },
        signal: AbortSignal.any([signal, connectionTimeout.signal]), redirect: "error",
      });
      clearTimeout(timer);
      if (!response.ok) await this.#rejectResponse(response);
      if (!response.headers.get("content-type")?.includes("text/event-stream") || !response.body) {
        throw new ClientError("后端事件流格式不兼容。", "protocol");
      }
      reader = response.body.getReader();
      const resetIdle = () => {
        clearTimeout(idleTimer);
        idleTimer = setTimeout(() => connectionTimeout.abort(), 45_000);
      };
      resetIdle();
      const decoder = new TextDecoder();
      let pending = "";
      while (!signal.aborted) {
        const next = await reader.read();
        resetIdle();
        pending = (pending + decoder.decode(next.value, { stream: !next.done })).replace(/\r\n/g, "\n");
        if (pending.length > 4_000_000) throw new ClientError("后端事件过大，已暂停连接。", "protocol");
        let boundary: number;
        while ((boundary = pending.indexOf("\n\n")) !== -1) {
          const frame = pending.slice(0, boundary);
          pending = pending.slice(boundary + 2);
          if (!frame.split("\n").some(line => line === "event: run" || line === "event:run")) continue;
          const data = frame.split("\n").filter(line => line.startsWith("data:")).map(line => line.slice(5).trimStart()).join("\n");
          let raw: unknown;
          try { raw = JSON.parse(data); } catch { throw new ClientError("后端事件格式无效。", "protocol"); }
          const parsed = RunSchema.safeParse(raw);
          if (!parsed.success || parsed.data.id !== id) throw new ClientError("后端任务数据不兼容。", "protocol");
          update(parsed.data);
          if (parsed.data.status !== "running") { terminal = true; return; }
        }
        if (next.done) break;
      }
      if (!signal.aborted && !terminal) throw new ClientError("连接已中断，正在恢复任务状态。", "connection");
    } catch (error) {
      if (signal.aborted) return;
      if (error instanceof ClientError) throw error;
      throw new ClientError("连接已中断，正在恢复任务状态。", "connection");
    } finally {
      clearTimeout(timer);
      clearTimeout(idleTimer);
      await reader?.cancel().catch(() => undefined);
    }
  }
}
