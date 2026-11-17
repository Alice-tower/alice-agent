import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { timingSafeEqual } from "node:crypto";
import { z } from "zod";
import { API_VERSION, SubmitMessageSchema, type RunSnapshot } from "../api/contracts.ts";
import { ProviderError, type ProviderRegistry } from "../provider/index.ts";
import { ApplicationError, type RunService } from "../runs/index.ts";

function authenticate(request: IncomingMessage, token: string): boolean {
  const value = request.headers.authorization;
  const expected = Buffer.from(`Bearer ${token}`);
  const received = Buffer.from(value ?? "");
  return expected.length === received.length && timingSafeEqual(expected, received);
}

async function body(request: IncomingMessage): Promise<unknown> {
  if (!request.headers["content-type"]?.toLowerCase().startsWith("application/json")) throw new ApplicationError(415, "content-type", "请求必须使用 JSON。");
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 128 * 1024) throw new ApplicationError(413, "body-too-large", "请求内容过长。");
    chunks.push(Buffer.from(chunk));
  }
  try { return JSON.parse(Buffer.concat(chunks).toString()); }
  catch { throw new ApplicationError(400, "json", "JSON 格式不正确。"); }
}

export function createBackendServer(options: { token: string; service: RunService; providers: ProviderRegistry; toolNames: string[] }) {
  if (options.token.length < 24) throw new Error("Backend token must contain at least 24 characters");
  const { service, providers } = options;
  const server = createServer((request, response) => {
    const json = (status: number, value: unknown) => {
      response.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff" });
      response.end(JSON.stringify(value));
    };
    void (async () => {
      if (!authenticate(request, options.token)) throw new ApplicationError(401, "authentication", "访问凭据无效。");
      const url = new URL(request.url ?? "/", "http://backend.local");
      const parts = url.pathname.split("/").filter(Boolean).map(part => decodeURIComponent(part));
      if (parts[0] !== "api" || parts[1] !== "v1") throw new ApplicationError(404, "not-found", "接口不存在或版本不受支持。");
      const method = request.method;
      if (method === "GET" && parts.length === 3 && parts[2] === "info") {
        return json(200, { version: API_VERSION, providers: providers.list(), tools: options.toolNames });
      }
      if (method === "GET" && parts.length === 5 && parts[2] === "providers" && parts[4] === "models") {
        let provider;
        try { provider = providers.get(parts[3]!); } catch { throw new ApplicationError(404, "provider", "模型服务不存在。"); }
        const controller = new AbortController();
        const cancel = () => controller.abort();
        response.once("close", cancel);
        try { return json(200, await provider.listModels({ timeoutMs: 15_000, signal: controller.signal })); }
        finally { response.off("close", cancel); }
      }
      if (parts[2] === "conversations") {
        if (parts.length === 3 && method === "GET") return json(200, service.listConversations());
        if (parts.length === 3 && method === "POST") { z.strictObject({}).parse(await body(request)); return json(201, service.createConversation()); }
        if (parts.length === 4 && method === "GET") return json(200, service.conversation(parts[3]!));
        if (parts.length === 5 && parts[4] === "messages" && method === "POST") return json(202, service.submit(parts[3]!, SubmitMessageSchema.parse(await body(request))));
      }
      if (parts[2] === "runs" && parts[3]) {
        const id = parts[3];
        if (parts.length === 4 && method === "GET") return json(200, service.run(id));
        if (parts.length === 5 && parts[4] === "cancel" && method === "POST") { z.strictObject({}).parse(await body(request)); return json(200, service.cancel(id)); }
        if (parts.length === 5 && parts[4] === "events" && method === "GET") {
          service.run(id);
          response.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-store", connection: "keep-alive", "x-accel-buffering": "no" });
          response.flushHeaders();
          let unsubscribe = () => {};
          let heartbeat: ReturnType<typeof setInterval> | undefined;
          let blocked = false;
          let latest: RunSnapshot | undefined;
          const cleanup = () => { unsubscribe(); if (heartbeat) clearInterval(heartbeat); };
          const send = (run: RunSnapshot) => {
            if (response.destroyed || response.writableEnded) return;
            if (blocked) { latest = run; return; }
            blocked = !response.write(`id: ${run.updatedAt}\nevent: run\ndata: ${JSON.stringify(run)}\n\n`);
            if (run.status !== "running") { cleanup(); response.end(); }
          };
          response.on("drain", () => {
            blocked = false;
            if (latest) { const run = latest; latest = undefined; send(run); }
          });
          unsubscribe = service.subscribe(id, send);
          response.once("close", cleanup);
          response.once("error", cleanup);
          heartbeat = setInterval(() => { if (!blocked) blocked = !response.write(": keepalive\n\n"); }, 15_000);
          heartbeat.unref();
          send(service.run(id));
          return;
        }
      }
      throw new ApplicationError(404, "not-found", "接口不存在。");
    })().catch(error => {
      if (response.headersSent) { response.destroy(); return; }
      if (error instanceof ApplicationError) return json(error.status, { error: { code: error.code, message: error.message } });
      if (error instanceof z.ZodError || error instanceof URIError) return json(400, { error: { code: "invalid-request", message: "请求参数不正确。" } });
      if (error instanceof ProviderError) return json(502, { error: { code: error.code, message: error.message } });
      json(500, { error: { code: "internal", message: "后端暂时无法完成请求。" } });
    });
  });
  server.requestTimeout = 30_000;
  server.headersTimeout = 10_000;
  return server;
}
