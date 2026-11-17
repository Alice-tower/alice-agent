import assert from "node:assert/strict";
import { test } from "node:test";
import { ProviderRegistry, ProviderError, type CompletionRequest, type LlmProvider, type ProviderEvent } from "../../src/provider/index.ts";
import { chunk, fixture, sse } from "./fixture.ts";

const request: CompletionRequest = { model: "test-model", messages: [{ role: "user", content: "Hello" }] };
const errorCode = (code: string) => (error: unknown) => error instanceof ProviderError && error.code === code;
const tool = { name: "get_weather", description: "Read weather", parameters: { type: "object", properties: { city: { type: "string" } }, required: ["city"] } };

test("lists models at a custom base path, normalizes duplicates and keeps credentials out of registry views", async t => {
  const server = await fixture(t, (_, response) => { response.end(JSON.stringify({ data: [{ id: "model-a" }, { id: "model-a" }, { id: "model-b" }] })); });
  const registry = new ProviderRegistry();
  const provider = registry.add({ id: "proxy", protocol: "openai-chat", baseUrl: server.baseUrl.replace("/v1", "/proxy/v1/"), apiKey: "test-secret" });
  assert.deepEqual(await provider.listModels(), [{ id: "model-a", name: "model-a" }, { id: "model-b", name: "model-b" }]);
  assert.equal(server.requests[0]?.path, "/proxy/v1/models");
  assert.equal(server.requests[0]?.headers.authorization, "Bearer test-secret");
  assert.equal(registry.get("proxy"), provider);
  assert.deepEqual(registry.list(), [{ id: "proxy", protocol: "openai-chat" }]);
  assert.doesNotMatch(JSON.stringify(provider), /test-secret|baseUrl/);
});

test("streams text and reasoning with fragmented UTF-8, reports actual usage and converts request settings", async t => {
  const server = await fixture(t, (_, response) => sse(response, [
    chunk({ role: "assistant", reasoning_content: "想一下" }), chunk({ content: "你好" }), chunk({ content: "！" }, "stop"),
    { choices: [], usage: { prompt_tokens: 12, completion_tokens: 4, total_tokens: 16, prompt_tokens_details: { cached_tokens: 5 }, completion_tokens_details: { reasoning_tokens: 2 } } },
  ]));
  const provider = new ProviderRegistry().add({ id: "local", protocol: "openai-chat", baseUrl: server.baseUrl });
  const events: ProviderEvent[] = [];
  for await (const event of provider.stream({ ...request, maxOutputTokens: 80, temperature: 0.2 })) events.push(event);
  assert.equal(events[0]?.type, "start");
  assert.equal(events.filter(event => event.type === "text-delta").map(event => event.text).join(""), "你好！");
  assert.equal(events.filter(event => event.type === "reasoning-delta").map(event => event.text).join(""), "想一下");
  const last = events.at(-1);
  assert.equal(last?.type, "done");
  if (last?.type !== "done") throw new Error("Missing done event");
  assert.deepEqual(last.result.usage, { inputTokens: 12, outputTokens: 4, totalTokens: 16, cachedInputTokens: 5, reasoningTokens: 2 });
  assert.equal(last.result.finishReason, "stop");
  assert.equal(server.requests[0]?.body.max_tokens, 80);
  assert.equal(server.requests[0]?.body.temperature, 0.2);
  assert.equal(server.requests[0]?.headers.authorization, undefined);
  assert.deepEqual(server.requests[0]?.body.stream_options, { include_usage: true });
});

test("complete consumes the same stream and does not invent missing usage", async t => {
  const server = await fixture(t, (_, response) => sse(response, [chunk({ content: "OK" }, "stop")]));
  const provider = new ProviderRegistry().add({ id: "local", protocol: "openai-chat", baseUrl: server.baseUrl, settings: { streamUsage: false, maxTokensField: "max_completion_tokens" } });
  const result = await provider.complete({ ...request, maxOutputTokens: 20 });
  assert.equal(result.usage, undefined);
  assert.deepEqual(result.message.content, [{ type: "text", text: "OK" }]);
  assert.equal(server.requests[0]?.body.max_completion_tokens, 20);
  assert.equal(server.requests[0]?.body.stream_options, undefined);
});

test("does not invent cache or reasoning counts when a server only reports basic token usage", async t => {
  const server = await fixture(t, (_, response) => sse(response, [
    chunk({ content: "OK" }, "stop"),
    { choices: [], usage: { prompt_tokens: 8, completion_tokens: 2 } },
  ]));
  const provider = new ProviderRegistry().add({ id: "local", protocol: "openai-chat", baseUrl: server.baseUrl });
  assert.deepEqual((await provider.complete(request)).usage, { inputTokens: 8, outputTokens: 2, totalTokens: 10 });
});

test("assembles interleaved tool calls and replays assistant/tool history without executing tools", async t => {
  let round = 0;
  const server = await fixture(t, (_, response) => {
    if (round++ === 0) sse(response, [
      chunk({ tool_calls: [
        { index: 0, id: "call-a", type: "function", function: { name: "get_weather", arguments: '{"city":' } },
        { index: 1, id: "call-b", type: "function", function: { name: "get_weather", arguments: '{"city":"上' } },
      ] }),
      chunk({ tool_calls: [{ index: 1, function: { arguments: '海"}' } }, { index: 0, function: { arguments: '"北京"}' } }] }, "tool_calls"),
    ]);
    else sse(response, [chunk({ content: "Both cities are sunny." }, "stop")]);
  });
  const provider = new ProviderRegistry().add({ id: "local", protocol: "openai-chat", baseUrl: server.baseUrl });
  const first = await provider.complete({ ...request, tools: [tool], toolChoice: { name: tool.name } });
  assert.equal(first.finishReason, "tool-calls");
  assert.deepEqual(first.message.content, [
    { type: "tool-call", id: "call-a", name: "get_weather", arguments: { city: "北京" } },
    { type: "tool-call", id: "call-b", name: "get_weather", arguments: { city: "上海" } },
  ]);
  const second = await provider.complete({ ...request, tools: [tool], messages: [
    ...request.messages, first.message,
    { role: "tool", toolCallId: "call-a", name: "get_weather", content: "sunny" },
    { role: "tool", toolCallId: "call-b", name: "get_weather", content: "sunny" },
  ] });
  assert.equal(second.finishReason, "stop");
  assert.deepEqual(server.requests[0]?.body.tool_choice, { type: "function", function: { name: tool.name } });
  const history = server.requests[1]?.body.messages as Record<string, unknown>[];
  assert.equal(history.filter(message => message.role === "tool").length, 2);
  assert.equal(history.find(message => message.role === "tool")?.tool_call_id, "call-a");
});

test("retains length as a distinct finish reason", async t => {
  const server = await fixture(t, (_, response) => sse(response, [chunk({ content: "partial" }, "length")]));
  const provider = new ProviderRegistry().add({ id: "local", protocol: "openai-chat", baseUrl: server.baseUrl });
  assert.equal((await provider.complete(request)).finishReason, "length");
});

for (const status of [401, 403, 404, 429, 500]) {
  test(`normalizes HTTP ${status} errors without leaking response bodies or retrying`, async t => {
    const server = await fixture(t, (_, response) => {
      response.writeHead(status, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: { message: "test-secret private-prompt" } }));
    });
    const provider = new ProviderRegistry().add({ id: "local", protocol: "openai-chat", baseUrl: server.baseUrl, apiKey: "test-secret" });
    for (const invoke of [() => provider.complete(request), () => provider.listModels()]) {
      await assert.rejects(invoke, error => {
        assert.ok(error instanceof ProviderError);
        assert.equal(error.status, status);
        assert.equal(error.retryable, status === 429 || status === 500);
        assert.doesNotMatch(String(error), /test-secret|private-prompt/);
        return true;
      });
    }
    assert.equal(server.requests.length, 2);
  });
}

for (const invalid of [
  [chunk({ content: 42 }, "stop")],
  [chunk({ content: "unfinished" })],
  [{ choices: "invalid" }],
]) {
  test("rejects malformed or interrupted SSE without a successful result", async t => {
    const server = await fixture(t, (_, response) => sse(response, invalid));
    const provider = new ProviderRegistry().add({ id: "local", protocol: "openai-chat", baseUrl: server.baseUrl });
    await assert.rejects(() => provider.complete(request), errorCode("protocol"));
  });
}

test("rejects successful HTTP responses that are not SSE", async t => {
  const server = await fixture(t, (_, response) => { response.end('{"content":"not a stream"}'); });
  const provider = new ProviderRegistry().add({ id: "local", protocol: "openai-chat", baseUrl: server.baseUrl });
  await assert.rejects(() => provider.complete(request), errorCode("protocol"));
});

for (const argumentsText of ['{"city":', '[]', 'not-json']) {
  test("rejects invalid final tool arguments rather than repairing them", async t => {
    const server = await fixture(t, (_, response) => sse(response, [chunk({ tool_calls: [{ index: 0, id: "call-a", type: "function", function: { name: tool.name, arguments: argumentsText } }] }, "tool_calls")]));
    const provider = new ProviderRegistry().add({ id: "local", protocol: "openai-chat", baseUrl: server.baseUrl });
    await assert.rejects(() => provider.complete({ ...request, tools: [tool] }), errorCode("protocol"));
  });
}

test("rejects undeclared tool calls", async t => {
  const server = await fixture(t, (_, response) => sse(response, [chunk({ tool_calls: [{ index: 0, id: "call-a", type: "function", function: { name: "unknown", arguments: "{}" } }] }, "tool_calls")]));
  const provider = new ProviderRegistry().add({ id: "local", protocol: "openai-chat", baseUrl: server.baseUrl });
  const events: ProviderEvent[] = [];
  await assert.rejects(async () => {
    for await (const event of provider.stream(request)) events.push(event);
  }, errorCode("protocol"));
  assert.ok(!events.some(event => event.type === "tool-call" || event.type === "done"));
});

test("rejects tool calls that violate the caller's tool selection", async t => {
  const server = await fixture(t, (_, response) => sse(response, [chunk({ tool_calls: [{ index: 0, id: "call-a", type: "function", function: { name: tool.name, arguments: "{}" } }] }, "tool_calls")]));
  const provider = new ProviderRegistry().add({ id: "local", protocol: "openai-chat", baseUrl: server.baseUrl });
  await assert.rejects(() => provider.complete({ ...request, tools: [tool], toolChoice: "none" }), errorCode("protocol"));
  await assert.rejects(() => provider.complete({ ...request, tools: [tool, { ...tool, name: "other" }], toolChoice: { name: "other" } }), errorCode("protocol"));
});

test("keeps reasoning replay metadata and serializes image inputs at the adapter boundary", async t => {
  const server = await fixture(t, (_, response) => sse(response, [chunk({ reasoning_content: "private reasoning" }), chunk({ content: "OK" }, "stop")]));
  const provider = new ProviderRegistry().add({ id: "local", protocol: "openai-chat", baseUrl: server.baseUrl });
  const first = await provider.complete(request);
  const reasoning = first.message.content.find(part => part.type === "reasoning");
  assert.equal(reasoning?.signature, "reasoning_content");
  await provider.complete({ ...request, messages: [
    { role: "system", content: "Follow instructions" }, ...request.messages, first.message,
    { role: "user", content: [{ type: "text", text: "Describe" }, { type: "image", mimeType: "image/png", data: "aGVsbG8=" }] },
  ] });
  const history = server.requests[1]?.body.messages as Record<string, unknown>[];
  assert.equal(history.find(message => message.role === "assistant")?.reasoning_content, "private reasoning");
  assert.deepEqual(history.at(-1)?.content, [{ type: "text", text: "Describe" }, { type: "image_url", image_url: { url: "data:image/png;base64,aGVsbG8=" } }]);
});

test("normalizes transport disconnection as a retryable network error", async t => {
  const server = await fixture(t, (_, response) => { response.destroy(); });
  const provider = new ProviderRegistry().add({ id: "local", protocol: "openai-chat", baseUrl: server.baseUrl });
  await assert.rejects(() => provider.complete(request), error => error instanceof ProviderError && error.code === "network" && error.retryable);
  await assert.rejects(() => provider.listModels(), errorCode("network"));
});

test("checks invalid input and tool history before making network requests", async t => {
  const server = await fixture(t, (_, response) => sse(response, [chunk({ content: "OK" }, "stop")]));
  const provider = new ProviderRegistry().add({ id: "local", protocol: "openai-chat", baseUrl: server.baseUrl });
  for (const input of [
    { ...request, model: "" }, { ...request, temperature: NaN }, { ...request, maxOutputTokens: 0 },
    { ...request, toolChoice: "required" as const },
    { ...request, tools: [tool, tool] },
    { ...request, messages: [{ role: "tool" as const, toolCallId: "unknown", name: tool.name, content: "result" }] },
    { ...request, messages: [{ role: "assistant" as const, content: [{ type: "tool-call" as const, id: "a", name: tool.name, arguments: {} }] }] },
  ]) await assert.rejects(() => provider.complete(input), errorCode("invalid-request"));
  assert.equal(server.requests.length, 0);
});

test("distinguishes cancellation from deadlines before headers and during streaming", async t => {
  const server = await fixture(t, (_, response) => {
    if (server.requests.length > 1) {
      response.writeHead(200, { "content-type": "text/event-stream" });
      response.write(`data: ${JSON.stringify(chunk({ content: "begin" }))}\n\n`);
    }
  });
  const provider = new ProviderRegistry().add({ id: "local", protocol: "openai-chat", baseUrl: server.baseUrl });
  await assert.rejects(() => provider.complete(request, { timeoutMs: 80 }), errorCode("timeout"));
  await assert.rejects(() => provider.complete(request, { timeoutMs: 80 }), errorCode("timeout"));
  const controller = new AbortController();
  await assert.rejects(async () => {
    for await (const event of provider.stream(request, { signal: controller.signal })) {
      if (event.type === "text-delta") controller.abort();
    }
  }, errorCode("cancelled"));
  const before = server.requests.length;
  await assert.rejects(() => provider.complete(request, { signal: AbortSignal.abort() }), errorCode("cancelled"));
  assert.equal(server.requests.length, before);
  await assert.rejects(() => provider.listModels({ timeoutMs: 80 }), errorCode("timeout"));
});

test("stopping stream consumption cancels its upstream request", async t => {
  const closed = Promise.withResolvers<void>();
  const server = await fixture(t, (_, response) => {
    response.on("close", () => closed.resolve());
    response.writeHead(200, { "content-type": "text/event-stream" });
    response.write(`data: ${JSON.stringify(chunk({ content: "begin" }))}\n\n`);
  });
  const provider = new ProviderRegistry().add({ id: "local", protocol: "openai-chat", baseUrl: server.baseUrl });
  for await (const event of provider.stream(request)) if (event.type === "text-delta") break;
  await Promise.race([closed.promise, new Promise<never>((_, reject) => { const timer = setTimeout(() => reject(new Error("Upstream connection not closed")), 1000); timer.unref(); })]);
});

for (const body of ['not-json', '{"data":[{}]}']) {
  test("validates model discovery responses", async t => {
    const server = await fixture(t, (_, response) => { response.end(body); });
    const provider = new ProviderRegistry().add({ id: "local", protocol: "openai-chat", baseUrl: server.baseUrl });
    await assert.rejects(() => provider.listModels(), errorCode("protocol"));
  });
}

test("does not follow redirects with API credentials", async t => {
  const server = await fixture(t, (_, response) => { response.writeHead(302, { location: "/target" }); response.end(); });
  const provider = new ProviderRegistry().add({ id: "local", protocol: "openai-chat", baseUrl: server.baseUrl, apiKey: "secret" });
  await assert.rejects(() => provider.complete(request), errorCode("network"));
  assert.equal(server.requests.length, 1);
});

test("registers new protocols without changing the registry or public completion contract", () => {
  const registry = new ProviderRegistry();
  const custom: LlmProvider = {
    id: "custom", protocol: "test-protocol", listModels: async () => [],
    complete: async () => ({ providerId: "custom", model: "test", message: { role: "assistant", content: [] }, finishReason: "stop" }),
    stream: async function* () { yield { type: "start" }; },
  };
  registry.registerAdapter({ protocol: "test-protocol", create: () => custom });
  assert.equal(registry.add({ id: "custom", protocol: "test-protocol", baseUrl: "http://localhost/v1" }), custom);
  assert.throws(() => registry.add({ id: "custom", protocol: "test-protocol", baseUrl: "http://localhost/v1" }), errorCode("configuration"));
  assert.throws(() => registry.add({ id: "other", protocol: "unknown", baseUrl: "http://localhost/v1" }), errorCode("unsupported-protocol"));
  assert.throws(() => registry.get("missing"), errorCode("configuration"));
  assert.throws(() => registry.registerAdapter({ protocol: "test-protocol", create: () => custom }), errorCode("configuration"));
  for (const config of [
    { id: "bad", protocol: "openai-chat", baseUrl: "file:///tmp" },
    { id: "bad", protocol: "openai-chat", baseUrl: "http://secret:password@localhost/v1" },
    { id: "bad", protocol: "openai-chat", baseUrl: "http://localhost/v1", settings: { unknown: true } },
  ]) assert.throws(() => registry.add(config), errorCode("configuration"));
});
