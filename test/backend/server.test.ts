import assert from "node:assert/strict";
import { test, type TestContext } from "node:test";
import { randomUUID } from "node:crypto";
import { mkdir, mkdtemp, rm } from "node:fs/promises";
import { resolve } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { createBackendServer } from "../../src/backend/server.ts";
import { RunService } from "../../src/runs/index.ts";
import { SqliteChatStore } from "../../src/storage/index.ts";
import { ProviderRegistry, type LlmProvider, type ProviderMessage } from "../../src/provider/index.ts";
import type { AgentRuntime, RuntimeRequest, RuntimeServices } from "../../src/runtime/index.ts";
import { ConversationSchema, RunSchema, type RunSnapshot } from "../../src/api/contracts.ts";

const token = "test-backend-token-0123456789";
const input = () => ({ requestId: randomUUID(), text: "你好", providerId: "mock", model: "test-model" });

test("stream message creation time stays fixed across bursts and consecutive requests", async t => {
  let round = 0;
  const startTimes: number[] = [];
  const runtime: AgentRuntime = { run: async function* (request) {
    const index = ++round;
    const createdAt = Date.now(); startTimes.push(createdAt);
    yield { type: "message-start", turn: 1, createdAt };
    if (index === 1) for (let i = 0; i < 500; i++) yield { type: "text-delta", turn: 1, index: 0, text: "x" };
    const message = { role: "assistant" as const, content: [{ type: "text" as const, text: `answer-${index}` }] };
    yield { type: "assistant-message", turn: 1, message };
    yield { type: "done", result: { status: "completed", turns: 1, messages: [...request.messages, message] } };
  } };
  const api = await fixture(t, runtime);
  const conversation = await api.create();
  const first = await api.submit(conversation.id, { ...input(), text: "question-1" });
  await api.wait(first.id, "completed");
  const second = await api.submit(conversation.id, { ...input(), text: "question-2" });
  await api.wait(second.id, "completed");
  const detail = api.service.conversation(conversation.id);
  assert.deepEqual(detail.messages.map(message => message.text), ["question-1", "answer-1", "question-2", "answer-2"]);
  assert.equal(detail.messages[1]?.createdAt, startTimes[0]);
  assert.equal(detail.messages[3]?.createdAt, startTimes[1]);
  const run = api.service.run(first.id);
  assert.equal(run.messageCreatedAt, startTimes[0]);
  assert.ok(run.firstOutputAt !== undefined && run.firstOutputAt >= run.messageCreatedAt!);
  assert.ok(run.finishedAt !== undefined && run.finishedAt >= run.firstOutputAt!);
});

function mockProviders() {
  const provider: LlmProvider = {
    id: "mock", protocol: "mock",
    listModels: async () => [{ id: "test-model", name: "Test model" }],
    complete: async () => { throw new Error("Fake runtime does not call model"); },
    stream: async function* () { throw new Error("Fake runtime does not call model"); },
  };
  const registry = new ProviderRegistry([{ protocol: "mock", create: () => provider }]);
  registry.add({ id: "mock", protocol: "mock", baseUrl: "http://127.0.0.1/mock" });
  return registry;
}

function controlledRuntime() {
  const gate = Promise.withResolvers<void>();
  const requests: RuntimeRequest[] = [];
  let cancellations = 0;
  const runtime: AgentRuntime = {
    async *run(request, services) {
      requests.push(structuredClone(request));
      yield { type: "text-delta", text: "回复", index: 0, turn: 1 };
      await new Promise<void>(resolve => {
        const done = () => { services.signal?.removeEventListener("abort", aborted); resolve(); };
        const aborted = () => { cancellations++; done(); };
        if (services.signal?.aborted) aborted();
        else { services.signal?.addEventListener("abort", aborted, { once: true }); void gate.promise.then(done); }
      });
      const message = { role: "assistant" as const, content: [{ type: "text" as const, text: "回复" }] };
      yield { type: "assistant-message", message, turn: 1 };
      yield { type: "done", result: { status: services.signal?.aborted ? "cancelled" : "completed", turns: 1, messages: [...request.messages, message] } };
    },
  };
  return { runtime, gate, requests, cancellations: () => cancellations };
}

async function fixture(t: TestContext, runtime: AgentRuntime, store = new SqliteChatStore(":memory:")) {
  const providers = mockProviders();
  const service = new RunService({ store, providers, runtime, tools: [] });
  const server = createBackendServer({ token, service, providers, toolNames: [] });
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("Missing address");
  const base = `http://127.0.0.1:${address.port}/api/v1`;
  t.after(async () => {
    await service.stop();
    server.closeAllConnections();
    await new Promise<void>(resolve => server.close(() => resolve()));
    store.close();
  });
  const request = (path: string, body?: unknown, key = token) => fetch(base + path, {
    method: body === undefined ? "GET" : "POST",
    headers: { Authorization: `Bearer ${key}`, ...(body !== undefined ? { "Content-Type": "application/json" } : {}) },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  });
  const create = async () => ConversationSchema.parse(await (await request("/conversations", {})).json());
  const submit = async (conversationId: string, body = input()) => RunSchema.parse(await (await request(`/conversations/${conversationId}/messages`, body)).json());
  const wait = async (id: string, status: string) => {
    for (let attempt = 0; attempt < 100; attempt++) {
      const result = RunSchema.parse(await (await request(`/runs/${id}`)).json());
      if (result.status === status) return result;
      await delay(10);
    }
    throw new Error(`Run did not reach ${status}`);
  };
  return { request, create, submit, wait, service, store };
}

test("API requires bearer authentication, reports version and validates requests", async t => {
  const controlled = controlledRuntime();
  const api = await fixture(t, controlled.runtime);
  assert.equal((await api.request("/info", undefined, "wrong-token")).status, 401);
  const info = await (await api.request("/info")).json();
  assert.equal(info.version, 1);
  assert.deepEqual(info.providers, [{ id: "mock", protocol: "mock" }]);
  assert.doesNotMatch(JSON.stringify(info), /test-backend-token|baseUrl|apiKey/);
  assert.equal((await api.request("/conversations", { extra: true })).status, 400);
  assert.equal((await api.request("/does-not-exist")).status, 404);
  assert.equal((await api.request("/conversations/not-real")).status, 404);
  assert.equal((await api.request("/providers/mock/models")).status, 200);
  assert.equal((await api.request("/providers/unknown/models")).status, 404);
  const conversation = await api.create();
  assert.equal((await api.request(`/conversations/${conversation.id}/messages`, { ...input(), text: "" })).status, 400);
  assert.equal(controlled.requests.length, 0);
});

test("oversized request bodies return a bounded API error without starting a task", async t => {
  const controlled = controlledRuntime();
  const api = await fixture(t, controlled.runtime);
  const conversation = await api.create();
  const response = await api.request(`/conversations/${conversation.id}/messages`, { ...input(), text: "a".repeat(140_000) });
  assert.equal(response.status, 413);
  assert.equal(controlled.requests.length, 0);
});

test("submitting retries is idempotent, changed payload conflicts, and same conversation is serialized", async t => {
  const controlled = controlledRuntime();
  const api = await fixture(t, controlled.runtime);
  const conversation = await api.create();
  const body = input();
  const first = await api.submit(conversation.id, body);
  const retry = await api.submit(conversation.id, body);
  assert.equal(retry.id, first.id);
  assert.equal(controlled.requests.length, 1);
  assert.equal((await api.request(`/conversations/${conversation.id}/messages`, { ...body, text: "changed" })).status, 409);
  assert.equal((await api.request(`/conversations/${conversation.id}/messages`, input())).status, 409);
  controlled.gate.resolve();
  await api.wait(first.id, "completed");
  const detail = api.service.conversation(conversation.id);
  assert.equal(detail.messages.filter(message => message.role === "user").length, 1);
  assert.equal(detail.messages.filter(message => message.role === "assistant").length, 1);
  const second = await api.submit(conversation.id);
  await api.wait(second.id, "completed");
  assert.equal(controlled.requests.length, 2);
  assert.ok(controlled.requests[1]?.messages.some(message => message.role === "assistant"));
});

test("disconnecting SSE does not cancel a run, and reconnect after completion returns the final snapshot", async t => {
  const controlled = controlledRuntime();
  const api = await fixture(t, controlled.runtime);
  const conversation = await api.create();
  const run = await api.submit(conversation.id);
  const response = await api.request(`/runs/${run.id}/events`);
  assert.match(response.headers.get("content-type") ?? "", /text\/event-stream/);
  const reader = response.body!.getReader();
  const first = await reader.read();
  assert.match(new TextDecoder().decode(first.value), /event: run/);
  await reader.cancel();
  assert.equal(controlled.cancellations(), 0);
  controlled.gate.resolve();
  const final = await api.wait(run.id, "completed");
  assert.equal(final.text, "回复");
  const reconnect = await api.request(`/runs/${run.id}/events`);
  const snapshot = await reconnect.text();
  assert.match(snapshot, /"status":"completed"/);
  assert.equal(controlled.cancellations(), 0);
});

test("large SSE snapshots finish without losing the terminal result under backpressure", async t => {
  const output = "长文本".repeat(90_000);
  const runtime: AgentRuntime = { async *run(request) {
    yield { type: "text-delta", text: output, index: 0, turn: 1 };
    yield { type: "done", result: { status: "completed", turns: 1, messages: request.messages } };
  } };
  const api = await fixture(t, runtime);
  const conversation = await api.create();
  const run = await api.submit(conversation.id);
  await api.wait(run.id, "completed");
  const response = await api.request(`/runs/${run.id}/events`);
  const raw = await response.text();
  const data = raw.split("\n").find(line => line.startsWith("data: "));
  assert.ok(data);
  const received = RunSchema.parse(JSON.parse(data.slice(6)));
  assert.equal(received.status, "completed");
  assert.equal(received.text, output);
});

test("observer failures cannot turn a successful task into a failed one", async t => {
  const controlled = controlledRuntime();
  const api = await fixture(t, controlled.runtime);
  const conversation = await api.create();
  const run = await api.submit(conversation.id);
  api.service.subscribe(run.id, () => { throw new Error("broken observer"); });
  controlled.gate.resolve();
  await api.wait(run.id, "completed");
  assert.equal(api.service.conversation(conversation.id).messages.filter(message => message.role === "assistant").length, 1);
});

test("explicit cancellation reaches a terminal state and does not poison subsequent turns", async t => {
  const controlled = controlledRuntime();
  const api = await fixture(t, controlled.runtime);
  const conversation = await api.create();
  const run = await api.submit(conversation.id);
  assert.equal((await api.request(`/runs/${run.id}/cancel`, {})).status, 200);
  await api.wait(run.id, "cancelled");
  assert.equal(controlled.cancellations(), 1);
  controlled.gate.resolve();
  const next = await api.submit(conversation.id);
  await api.wait(next.id, "completed");
  assert.ok(!controlled.requests[1]?.messages.some(message => message.role === "assistant"));
});

test("runtime failure is persisted with a safe error and never saved as continuation history", async t => {
  const runtime: AgentRuntime = { async *run() { yield { type: "text-delta", text: "partial", index: 0, turn: 1 }; throw new Error("secret-token-and-private-prompt"); } };
  const api = await fixture(t, runtime);
  const conversation = await api.create();
  const run = await api.submit(conversation.id);
  const failed = await api.wait(run.id, "failed");
  assert.equal(failed.text, "partial");
  assert.doesNotMatch(JSON.stringify(failed), /secret-token-and-private-prompt/);
  assert.deepEqual(api.store.history(conversation.id), []);
  assert.equal(api.service.conversation(conversation.id).messages.length, 2);
});

test("final assistant messages reconcile the displayed result even when no deltas were emitted", async t => {
  const runtime: AgentRuntime = { async *run(request) {
    const message = { role: "assistant" as const, content: [{ type: "text" as const, text: "Final answer" }] };
    yield { type: "assistant-message", message, turn: 1 };
    yield { type: "done", result: { status: "completed", turns: 1, messages: [...request.messages, message] } };
  } };
  const api = await fixture(t, runtime);
  const conversation = await api.create();
  const run = await api.submit(conversation.id);
  const final = await api.wait(run.id, "completed");
  assert.equal(final.text, "Final answer");
  assert.equal(api.service.conversation(conversation.id).messages.at(-1)?.text, "Final answer");
});

test("SQLite restart preserves conversations and marks abandoned runs interrupted without replaying tools", async t => {
  await mkdir(resolve("tmp"), { recursive: true });
  const directory = await mkdtemp(resolve("tmp/backend-test-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const path = resolve(directory, "test.sqlite");
  const store = new SqliteChatStore(path);
  const conversation = store.createConversation();
  const submitted = input();
  const run: RunSnapshot = {
    id: randomUUID(), conversationId: conversation.id, requestId: submitted.requestId, providerId: "mock", model: "test-model",
    status: "running", text: "before crash", reasoning: "", turns: 1,
    tools: [{ callId: "call-a", name: "read_clock", arguments: "{}", status: "running" }], createdAt: Date.now(), updatedAt: Date.now(),
  };
  store.startRun(run, submitted);
  store.close();
  const controlled = controlledRuntime();
  const api = await fixture(t, controlled.runtime, new SqliteChatStore(path));
  const recovered = api.service.run(run.id);
  assert.equal(recovered.status, "interrupted");
  assert.equal(recovered.tools[0]?.status, "failed");
  assert.equal(controlled.requests.length, 0);
  assert.equal(api.service.conversation(conversation.id).messages.length, 2);
  assert.deepEqual(api.store.history(conversation.id), []);
});

test("a second SQLite owner is rejected without interrupting the live owner's task", async t => {
  await mkdir(resolve("tmp"), { recursive: true });
  const directory = await mkdtemp(resolve("tmp/backend-owner-test-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const path = resolve(directory, "test.sqlite");
  const controlled = controlledRuntime();
  const api = await fixture(t, controlled.runtime, new SqliteChatStore(path));
  const conversation = await api.create();
  const run = await api.submit(conversation.id);
  assert.throws(() => {
    const second = new SqliteChatStore(path);
    try { second.interruptRunning(); } finally { second.close(); }
  }, /locked|busy/i);
  assert.equal(api.service.run(run.id).status, "running");
  assert.equal(controlled.cancellations(), 0);
  controlled.gate.resolve();
  await api.wait(run.id, "completed");
});
