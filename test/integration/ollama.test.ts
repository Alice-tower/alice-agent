import assert from "node:assert/strict";
import { test } from "node:test";
import { ProviderRegistry } from "../../src/provider/index.ts";

// Explicit opt-in: this test uses real local compute and is excluded from npm test.
test("Ollama OpenAI-compatible model discovery, streaming and tool round trip", { skip: process.env.ALICE_TEST_OLLAMA !== "1", timeout: 300_000 }, async t => {
  const provider = new ProviderRegistry().add({
    id: "ollama", protocol: "openai-chat",
    baseUrl: process.env.OLLAMA_BASE_URL ?? "http://127.0.0.1:11434/v1",
    ...(process.env.OLLAMA_API_KEY ? { apiKey: process.env.OLLAMA_API_KEY } : {}),
    timeoutMs: 180_000,
  });
  const models = await provider.listModels({ timeoutMs: 10_000 });
  const model = process.env.OLLAMA_MODEL ?? models[0]?.id;
  assert.ok(model, "Ollama must expose at least one model");
  assert.ok(models.some(item => item.id === model));
  t.diagnostic(`Testing model: ${model}`);
  let text = "";
  let finished = false;
  for await (const event of provider.stream({ model, messages: [{ role: "user", content: "Reply with only the word READY. /no_think" }], maxOutputTokens: 128, temperature: 0 })) {
    if (event.type === "text-delta") text += event.text;
    if (event.type === "done") { finished = true; assert.equal(event.result.finishReason, "stop"); }
  }
  assert.ok(finished);
  assert.match(text, /READY/i);

  const messages = [{ role: "user" as const, content: "Use the get_test_value tool to obtain the value, then report that value. Do not guess. /no_think" }];
  const tools = [{ name: "get_test_value", description: "Returns the test value; call with an empty object.", parameters: { type: "object", properties: {}, additionalProperties: false } }];
  const first = await provider.complete({ model, messages, tools, toolChoice: { name: "get_test_value" }, maxOutputTokens: 256, temperature: 0 });
  assert.equal(first.finishReason, "tool-calls");
  const calls = first.message.content.filter(part => part.type === "tool-call");
  assert.ok(calls.length > 0);
  assert.ok(calls.every(call => call.name === "get_test_value"));
  const final = await provider.complete({
    model, tools, toolChoice: "none", maxOutputTokens: 1024, temperature: 0,
    messages: [
      ...messages, first.message,
      ...calls.map(call => ({ role: "tool" as const, toolCallId: call.id, name: call.name, content: "ALICE-TEST-42" })),
      { role: "user", content: "Report only the exact value returned by the tool. /no_think" },
    ],
  });
  assert.equal(final.finishReason, "stop");
  const answer = final.message.content.filter(part => part.type === "text").map(part => part.text).join("");
  assert.match(answer, /ALICE-TEST-42/);
  t.diagnostic("Streaming and tool round trip completed.");
});
