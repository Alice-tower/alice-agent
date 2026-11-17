import assert from "node:assert/strict";
import { test } from "node:test";
import { createPiRuntime, type RuntimeEvent, type RuntimeResult, type RuntimeTool } from "../../src/runtime/index.ts";
import { createBackendTimeTool } from "../../src/tools/index.ts";
import { ProviderError, type CompletionRequest, type CompletionResult, type LlmProvider, type ProviderEvent } from "../../src/provider/index.ts";

const input = { model: "test-model", messages: [{ role: "user" as const, content: "What time is it?" }] };
function result(content: CompletionResult["message"]["content"], finishReason: CompletionResult["finishReason"] = "stop"): CompletionResult {
  return { providerId: "fake", model: "test-model", message: { role: "assistant", content, origin: { providerId: "fake", model: "test-model" } }, finishReason };
}
function provider(generate: (request: CompletionRequest, signal?: AbortSignal) => AsyncIterable<ProviderEvent>): LlmProvider {
  return { id: "fake", protocol: "fake", listModels: async () => [{ id: "test-model", name: "Test" }], complete: async () => result([]), stream: (request, options) => generate(request, options?.signal) };
}
async function collect(events: AsyncIterable<RuntimeEvent>): Promise<{ events: RuntimeEvent[]; result: RuntimeResult }> {
  const values: RuntimeEvent[] = [];
  for await (const event of events) values.push(event);
  const done = values.at(-1);
  assert.equal(done?.type, "done");
  if (done?.type !== "done") throw new Error("Missing done event");
  return { events: values, result: done.result };
}
const call = { type: "tool-call" as const, id: "call-time", name: "get_backend_time", arguments: {} };

test("Pi Agent uses Provider, executes a read-only tool, and continues with complete history", async () => {
  const requests: CompletionRequest[] = [];
  const llm = provider(async function* (request) {
    requests.push(structuredClone(request));
    yield { type: "start" };
    if (requests.length === 1) yield { type: "done", result: result([call], "tool-calls") };
    else {
      yield { type: "reasoning-delta", index: 0, text: "reasoning" };
      yield { type: "text-delta", index: 1, text: "It is now." };
      yield { type: "done", result: result([{ type: "reasoning", text: "reasoning", signature: "reasoning_content" }, { type: "text", text: "It is now." }]) };
    }
  });
  const output = await collect(createPiRuntime().run(input, { provider: llm, tools: [createBackendTimeTool()], allowedTools: [call.name] }));
  assert.equal(output.result.status, "completed");
  assert.equal(output.result.turns, 2);
  assert.ok(output.events.some(event => event.type === "text-delta" && event.text === "It is now."));
  assert.ok(output.events.some(event => event.type === "reasoning-delta"));
  assert.ok(output.events.some(event => event.type === "tool-start"));
  const toolResult = requests[1]?.messages.find(message => message.role === "tool");
  assert.equal(toolResult?.role, "tool");
  if (toolResult?.role !== "tool") throw new Error("Missing tool result");
  assert.equal(JSON.parse(toolResult.content).timezone, Intl.DateTimeFormat().resolvedOptions().timeZone);
  assert.equal(requests[0]?.tools?.[0]?.name, call.name);
  const last = output.result.messages.at(-1);
  assert.equal(last?.role, "assistant");
  if (last?.role === "assistant") assert.equal(last.content[0]?.type === "reasoning" && last.content[0].signature, "reasoning_content");
});

test("execution allowlist is enforced independently of model-visible tools", async () => {
  let executed = false;
  const tool: RuntimeTool = { ...createBackendTimeTool(), execute: async () => { executed = true; return "secret"; } };
  const llm = provider(async function* () { yield { type: "done", result: result([call], "tool-calls") }; });
  const output = await collect(createPiRuntime().run(input, { provider: llm, tools: [tool], allowedTools: [] }));
  assert.equal(executed, false);
  assert.equal(output.result.status, "failed");
  assert.equal(output.result.error?.code, "permission-denied");
  assert.equal(output.result.messages.at(-1)?.role, "tool");
});

test("tool parameter validation runs before execute", async () => {
  let executed = false;
  const tool: RuntimeTool = { ...createBackendTimeTool(), execute: async () => { executed = true; return "unused"; } };
  const llm = provider(async function* () { yield { type: "done", result: result([{ ...call, arguments: { unknown: true } }], "tool-calls") }; });
  const output = await collect(createPiRuntime().run(input, { provider: llm, tools: [tool], allowedTools: [call.name] }));
  assert.equal(executed, false);
  assert.equal(output.result.status, "failed");
  assert.equal(output.result.error?.code, "tool-error");
});

test("tool failures are sanitized and do not re-enter the model loop", async () => {
  let requests = 0;
  const tool: RuntimeTool = { ...createBackendTimeTool(), execute: async () => { throw new Error("API_SECRET private error"); } };
  const llm = provider(async function* () { requests++; yield { type: "done", result: result([call], "tool-calls") }; });
  const output = await collect(createPiRuntime().run(input, { provider: llm, tools: [tool], allowedTools: [call.name] }));
  assert.equal(output.result.status, "failed");
  assert.equal(requests, 1);
  assert.doesNotMatch(JSON.stringify(output), /API_SECRET|private error/);
});

test("a failed tool blocks every remaining call in the same batch", async () => {
  const executed: string[] = [];
  const first: RuntimeTool = { ...createBackendTimeTool(), name: "first", execute: async () => { executed.push("first"); throw new Error("failure"); } };
  const second: RuntimeTool = { ...createBackendTimeTool(), name: "second", execute: async () => { executed.push("second"); return "must not execute"; } };
  const llm = provider(async function* () { yield { type: "done", result: result([
    { ...call, id: "one", name: "first" }, { ...call, id: "two", name: "second" },
  ], "tool-calls") }; });
  const output = await collect(createPiRuntime().run(input, { provider: llm, tools: [first, second], allowedTools: ["first", "second"] }));
  assert.deepEqual(executed, ["first"]);
  assert.equal(output.result.status, "failed");
  const results = output.result.messages.filter(message => message.role === "tool");
  assert.equal(results.length, 2);
  assert.ok(results.every(message => message.isError));
});

test("maxTurns bounds model requests, retaining closed tool history", async () => {
  let requests = 0;
  const llm = provider(async function* () { requests++; yield { type: "done", result: result([{ ...call, id: `call-${requests}` }], "tool-calls") }; });
  const output = await collect(createPiRuntime().run({ ...input, maxTurns: 2 }, { provider: llm, tools: [createBackendTimeTool()], allowedTools: [call.name] }));
  assert.equal(output.result.status, "limited");
  assert.equal(requests, 2);
  assert.equal(output.result.turns, 2);
  assert.equal(output.result.messages.filter(message => message.role === "tool").length, 2);
});

test("output length is a limit, not task success", async () => {
  const llm = provider(async function* () { yield { type: "text-delta", index: 0, text: "partial" }; yield { type: "done", result: result([{ type: "text", text: "partial" }], "length") }; });
  const output = await collect(createPiRuntime().run(input, { provider: llm, tools: [], allowedTools: [] }));
  assert.equal(output.result.status, "limited");
  assert.deepEqual(output.result.messages, input.messages);
});

test("Provider failures retain stable safe error codes", async () => {
  const llm = provider(async function* () { throw new ProviderError("authentication", "fake", "Authentication failed"); });
  const output = await collect(createPiRuntime().run(input, { provider: llm, tools: [], allowedTools: [] }));
  assert.equal(output.result.status, "failed");
  assert.equal(output.result.error?.code, "authentication");
});

test("total timeout settles even if a Provider ignores AbortSignal", async () => {
  const keepAlive = setTimeout(() => {}, 1000);
  try {
    const llm = provider(async function* () { await new Promise<void>(() => {}); yield { type: "start" }; });
    const output = await collect(createPiRuntime().run({ ...input, timeoutMs: 30 }, { provider: llm, tools: [], allowedTools: [] }));
    assert.equal(output.result.status, "limited");
    assert.equal(output.result.error?.code, "timeout");
  } finally { clearTimeout(keepAlive); }
});

test("an already-cancelled run never starts a model request", async () => {
  let called = false;
  const llm = provider(async function* () { called = true; yield { type: "done", result: result([]) }; });
  const output = await collect(createPiRuntime().run(input, { provider: llm, tools: [], allowedTools: [], signal: AbortSignal.abort() }));
  assert.equal(called, false);
  assert.equal(output.result.status, "cancelled");
  assert.equal(output.result.error?.code, "cancelled");
  assert.equal(output.result.turns, 0);
  assert.deepEqual(output.result.messages, input.messages);
});

test("cancellation closes pending tool history without waiting for an uncooperative tool", async () => {
  const controller = new AbortController();
  const tool: RuntimeTool = { ...createBackendTimeTool(), execute: async () => { controller.abort(); await new Promise<void>(() => {}); return "unused"; } };
  const llm = provider(async function* () { yield { type: "done", result: result([call], "tool-calls") }; });
  const output = await collect(createPiRuntime().run(input, { provider: llm, tools: [tool], allowedTools: [call.name], signal: controller.signal }));
  assert.equal(output.result.status, "cancelled");
  assert.equal(output.result.messages.at(-1)?.role, "tool");
});

test("per-turn context hook receives project message contracts", async () => {
  let prepared = 0;
  const llm = provider(async function* (request) {
    assert.equal(request.messages[0]?.role, "system");
    yield { type: "done", result: result([{ type: "text", text: "OK" }]) };
  });
  const output = await collect(createPiRuntime().run(input, {
    provider: llm, tools: [], allowedTools: [],
    prepareContext: async messages => { prepared++; return [{ role: "system", content: "rule" }, ...messages]; },
  }));
  assert.equal(prepared, 1);
  assert.equal(output.result.status, "completed");
});
