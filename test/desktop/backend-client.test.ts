import assert from "node:assert/strict";
import { test } from "node:test";
import { BackendClient, ClientError, validateConnection } from "../../src/desktop/backend-client.ts";
import type { RunSnapshot } from "../../src/api/contracts.ts";

const connection = { baseUrl: "http://127.0.0.1:4317", token: "private-token" };
const run: RunSnapshot = {
  id: "00000000-0000-4000-8000-000000000001", conversationId: "00000000-0000-4000-8000-000000000002",
  requestId: "00000000-0000-4000-8000-000000000003", providerId: "ollama", model: "model",
  status: "completed", text: "你好", reasoning: "", turns: 1, tools: [], createdAt: 1, updatedAt: 2,
};

test("accepts local HTTP and remote HTTPS while rejecting ambiguous or insecure connection URLs", () => {
  assert.equal(validateConnection(connection).baseUrl, connection.baseUrl);
  assert.equal(validateConnection({ ...connection, baseUrl: "https://example.test/" }).baseUrl, "https://example.test");
  for (const baseUrl of ["http://192.168.1.10:4317", "file:///tmp/backend", "https://user:secret@example.test", "https://example.test/path", "https://example.test/?token=secret", "https://example.test/#fragment"]) {
    assert.throws(() => validateConnection({ ...connection, baseUrl }), ClientError);
  }
  assert.equal(validateConnection({ ...connection, baseUrl: "http://192.168.1.10:4317" }, true).baseUrl, "http://192.168.1.10:4317");
  assert.throws(() => validateConnection({ ...connection, token: "bad\ntoken" }), ClientError);
});

test("authenticates requests and validates the backend protocol before exposing data", async t => {
  const original = globalThis.fetch;
  t.after(() => { globalThis.fetch = original; });
  let requested = "";
  globalThis.fetch = async (url, init) => {
    requested = String(url);
    assert.equal(new Headers(init?.headers).get("authorization"), "Bearer private-token");
    assert.equal(init?.redirect, "error");
    return Response.json({ version: 1, providers: [{ id: "ollama", protocol: "openai-chat" }], tools: [] });
  };
  const client = new BackendClient(connection);
  assert.equal((await client.info()).providers[0]?.id, "ollama");
  assert.equal(requested, "http://127.0.0.1:4317/api/v1/info");
  assert.doesNotMatch(JSON.stringify(client), /private-token/);
  globalThis.fetch = async () => Response.json({ version: 99, providers: [], tools: [] });
  await assert.rejects(() => client.info(), error => error instanceof ClientError && error.kind === "protocol");
});

test("does not forward provider error bodies or credentials to the renderer", async t => {
  const original = globalThis.fetch;
  t.after(() => { globalThis.fetch = original; });
  globalThis.fetch = async () => Response.json({ error: { code: "unauthorized", message: "private-token secret input" } }, { status: 401 });
  const client = new BackendClient(connection);
  await assert.rejects(() => client.info(), error => {
    assert.ok(error instanceof ClientError);
    assert.equal(error.kind, "authentication");
    assert.doesNotMatch(error.message, /private-token|secret input/);
    return true;
  });
});

test("decodes SSE snapshots across UTF-8 and CRLF chunk boundaries", async t => {
  const original = globalThis.fetch;
  t.after(() => { globalThis.fetch = original; });
  const body = new TextEncoder().encode(`: heartbeat\r\n\r\nevent: run\r\ndata: ${JSON.stringify(run)}\r\n\r\n`);
  let cancelled = false;
  globalThis.fetch = async () => new Response(new ReadableStream<Uint8Array>({
    start(controller) { for (const byte of body) controller.enqueue(Uint8Array.of(byte)); },
    cancel() { cancelled = true; },
  }), { headers: { "content-type": "text/event-stream" } });
  const snapshots: RunSnapshot[] = [];
  await new BackendClient(connection).follow(run.id, new AbortController().signal, snapshot => snapshots.push(snapshot));
  assert.deepEqual(snapshots, [run]);
  assert.equal(cancelled, true);
});

test("rejects an unrelated task snapshot rather than mixing conversations", async t => {
  const original = globalThis.fetch;
  t.after(() => { globalThis.fetch = original; });
  globalThis.fetch = async () => new Response(`event: run\ndata: ${JSON.stringify({ ...run, id: "other" })}\n\n`, { headers: { "content-type": "text/event-stream" } });
  await assert.rejects(() => new BackendClient(connection).follow(run.id, new AbortController().signal, () => assert.fail("No invalid snapshot should be delivered")), error => error instanceof ClientError && error.kind === "protocol");
});

test("treats a stream ending before task completion as reconnectable", async t => {
  const original = globalThis.fetch;
  t.after(() => { globalThis.fetch = original; });
  globalThis.fetch = async () => new Response(`event: run\ndata: ${JSON.stringify({ ...run, status: "running" })}\n\n`, { headers: { "content-type": "text/event-stream" } });
  await assert.rejects(() => new BackendClient(connection).follow(run.id, new AbortController().signal, () => undefined), error => error instanceof ClientError && error.kind === "connection");
});
