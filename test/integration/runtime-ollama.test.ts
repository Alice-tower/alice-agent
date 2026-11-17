import assert from "node:assert/strict";
import { test } from "node:test";
import { ProviderRegistry } from "../../src/provider/index.ts";
import { createPiRuntime, type RuntimeEvent } from "../../src/runtime/index.ts";
import { createBackendTimeTool } from "../../src/tools/index.ts";

test("Pi Runtime calls Ollama, executes backend time tool and completes", { skip: process.env.ALICE_TEST_OLLAMA !== "1", timeout: 240_000 }, async t => {
  const provider = new ProviderRegistry().add({
    id: "ollama", protocol: "openai-chat",
    baseUrl: process.env.OLLAMA_BASE_URL ?? "http://127.0.0.1:11434/v1",
    ...(process.env.OLLAMA_API_KEY ? { apiKey: process.env.OLLAMA_API_KEY } : {}),
    timeoutMs: 180_000,
  });
  const model = process.env.OLLAMA_MODEL ?? (await provider.listModels())[0]?.id;
  assert.ok(model, "Ollama must provide a tool-capable model");
  const events: RuntimeEvent[] = [];
  for await (const event of createPiRuntime().run({
    model, maxTurns: 4, maxOutputTokens: 2048, timeoutMs: 180_000,
    messages: [
      { role: "system", content: "You are testing an assistant's tool integration. To answer questions about current time, you must first call get_backend_time. After its result, give one short sentence. /no_think" },
      { role: "user", content: "Call get_backend_time, then tell me the backend timezone and how many seconds remain until midnight. /no_think" },
    ],
  }, { provider, tools: [createBackendTimeTool()], allowedTools: ["get_backend_time"] })) events.push(event);
  const done = events.at(-1);
  assert.equal(done?.type, "done");
  if (done?.type !== "done") throw new Error("Missing final runtime result");
  assert.equal(done.result.status, "completed", JSON.stringify(done.result.error));
  assert.ok(events.some(event => event.type === "tool-start" && event.name === "get_backend_time"));
  const toolEnd = events.find(event => event.type === "tool-end");
  assert.ok(toolEnd?.type === "tool-end" && !toolEnd.isError);
  assert.equal(typeof JSON.parse(toolEnd.content).secondsUntilMidnight, "number");
  assert.ok(done.result.turns >= 2);
  assert.ok(done.result.messages.some(message => message.role === "tool"));
  const last = done.result.messages.at(-1);
  assert.ok(last?.role === "assistant" && last.content.some(part => part.type === "text" && part.text.trim()));
  t.diagnostic(`Runtime tool round trip completed with ${model}.`);
});
