import assert from "node:assert/strict";
import { test } from "node:test";
import { mkdir, readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { resolve } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { _electron } from "playwright";
import { ProviderRegistry, ProviderError, type LlmProvider, type CompletionRequest, type RequestOptions, type CompletionResult, type ProviderEvent } from "../../src/provider/index.ts";
import { createPiRuntime } from "../../src/runtime/index.ts";
import { createBackendTimeTool } from "../../src/tools/index.ts";
import { SqliteChatStore } from "../../src/storage/index.ts";
import { RunService } from "../../src/runs/index.ts";
import { createBackendServer } from "../../src/backend/index.ts";

// Explicit desktop test: launches a real Electron window against a real API and Pi loop.
// The model is simulated so verification has no external costs or model randomness.
test("Electron connects, chats through a Pi tool loop, cancels, and restores history", { timeout: 90_000 }, async t => {
  const temp = resolve("tmp/e2e", randomUUID());
  await mkdir(temp, { recursive: true });
  const registry = new ProviderRegistry([]);
  let calls = 0;
  const provider: LlmProvider = {
    id: "default", protocol: "simulated",
    listModels: async () => [{ id: "test-model", name: "Test model" }],
    complete: async function (request, options) {
      for await (const event of this.stream(request, options)) if (event.type === "done") return event.result;
      throw new Error("Missing result");
    },
    stream: async function* (request: CompletionRequest, options?: RequestOptions): AsyncGenerator<ProviderEvent> {
      calls++;
      yield { type: "start" };
      const last = request.messages.at(-1);
      const slow = last?.role === "user" && typeof last.content === "string" && last.content.includes("慢");
      if (slow) {
        for (let index = 0; index < 200; index++) {
          await delay(30);
          if (options?.signal?.aborted) throw new ProviderError("cancelled", "default", "Cancelled");
          yield { type: "text-delta", index: 0, text: "正在回答…" };
        }
      }
      const result: CompletionResult = {
        providerId: "default", model: request.model, finishReason: last?.role === "tool" ? "stop" : "tool-calls",
        message: { role: "assistant", content: last?.role === "tool" ? [{ type: "text", text: "后端时间已读取，今天剩余时间已计算。" }] : [{ type: "tool-call", id: `time-${calls}`, name: "get_backend_time", arguments: {} }] },
      };
      if (last?.role === "tool") yield { type: "text-delta", index: 0, text: "后端时间已读取，今天剩余时间已计算。" };
      yield { type: "done", result };
    },
  };
  registry.registerAdapter({ protocol: "simulated", create: () => provider });
  registry.add({ id: "default", protocol: "simulated", baseUrl: "http://localhost" });
  const store = new SqliteChatStore(resolve(temp, "test.sqlite"));
  const tools = [createBackendTimeTool()];
  const service = new RunService({ store, providers: registry, runtime: createPiRuntime(), tools });
  const token = randomUUID() + randomUUID();
  const server = createBackendServer({ token, service, providers: registry, toolNames: tools.map(tool => tool.name) });
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  assert.ok(address && typeof address === "object");
  t.after(async () => {
    await service.stop(); server.closeAllConnections();
    await new Promise<void>(resolve => server.close(() => resolve()));
    store.close();
  });
  const env: Record<string, string> = Object.fromEntries(Object.entries(process.env).filter((entry): entry is [string, string] => entry[1] !== undefined));
  env.ALICE_E2E_URL = `http://127.0.0.1:${address.port}`;
  env.ALICE_DESKTOP_DATA_DIR = resolve(temp, "desktop");
  delete env.ELECTRON_RUN_AS_NODE;
  let application = await _electron.launch({ args: [resolve("dist/desktop/main.cjs")], env, timeout: 30_000 });
  t.after(async () => { await application.close(); });
  let page = await application.firstWindow();
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  const connect = async () => {
    await page.getByTestId("access-token").fill(token);
    await page.getByTestId("connect").click();
    await page.getByTestId("connection-status").filter({ hasText: "已连接" }).waitFor();
    await page.getByTestId("model-select").locator("option").first().waitFor({ state: "attached" });
  };
  await page.getByTestId("connection-dialog").waitFor({ state: "visible" });
  assert.ok(!await page.getByTestId("workspace").isVisible());
  await page.keyboard.press("Escape");
  assert.ok(await page.getByTestId("connection-dialog").isVisible());
  await page.getByTestId("access-token").fill("invalid-token");
  await page.getByTestId("connect").click();
  await page.getByTestId("connection-error").waitFor();
  assert.ok(!await page.getByTestId("workspace").isVisible());
  await mkdir(resolve("tmp/screenshots"), { recursive: true });
  await page.screenshot({ path: resolve("tmp/screenshots/alice-setup.png") });
  await connect();
  assert.ok(!await page.getByTestId("connection-dialog").isVisible());
  const saved = await readFile(resolve(temp, "desktop/connection.json"), "utf8");
  assert.ok(!saved.includes(token));
  assert.deepEqual(Object.keys(JSON.parse(saved)), ["baseUrl", "encryptedToken"]);
  assert.equal(await page.getByTestId("access-token").inputValue(), "");
  await page.getByTestId("message-input").fill("读取后端当前时间，计算今天还剩多久");
  await page.getByTestId("send").click();
  await page.getByTestId("run-status").filter({ hasText: "已完成" }).waitFor();
  assert.match(await page.getByTestId("messages").innerText(), /后端时间已读取/);
  assert.match(await page.getByTestId("messages").innerText(), /工具活动/);
  await mkdir(resolve("tmp/screenshots"), { recursive: true });
  await page.screenshot({ path: resolve("tmp/screenshots/alice-desktop.png") });
  // Restart the whole client: connection restores, business data comes from the backend.
  await page.evaluate(() => localStorage.setItem("temporary-probe", "not-persistent"));
  await application.close();
  application = await _electron.launch({ args: [resolve("dist/desktop/main.cjs")], env, timeout: 30_000 });
  page = await application.firstWindow();
  await page.getByTestId("workspace").waitFor({ state: "visible" });
  assert.ok(!await page.getByTestId("connection-dialog").isVisible());
  assert.equal(await page.evaluate(() => localStorage.getItem("temporary-probe")), null);
  await page.getByTestId("messages").getByText("后端时间已读取，今天剩余时间已计算。", { exact: true }).waitFor();
  await page.getByTestId("message-input").fill("请慢慢回答");
  await page.getByTestId("send").click();
  await page.getByTestId("cancel").waitFor({ state: "visible" });
  await page.getByTestId("cancel").click();
  await page.getByTestId("run-status").filter({ hasText: "已停止" }).waitFor();
  await page.getByTestId("message-input").fill("再次读取时间");
  assert.ok(await page.getByTestId("send").isEnabled());
  const detail = service.conversation(service.listConversations()[0]!.id);
  assert.ok(detail.runs.some(run => run.status === "completed" && run.tools.length === 1));
  assert.ok(detail.runs.some(run => run.status === "cancelled"));
  assert.deepEqual(errors, []);
  const isolation = await page.evaluate(() => ({ node: typeof (globalThis as Record<string, unknown>).require, bridge: typeof window.alice }));
  assert.deepEqual(isolation, { node: "undefined", bridge: "object" });
  // Saved settings cannot bypass connectivity verification on a later startup.
  await application.close();
  server.closeAllConnections();
  await new Promise<void>(resolve => server.close(() => resolve()));
  application = await _electron.launch({ args: [resolve("dist/desktop/main.cjs")], env, timeout: 30_000 });
  page = await application.firstWindow();
  await page.getByTestId("connection-error").waitFor();
  assert.ok(await page.getByTestId("connection-dialog").isVisible());
  assert.ok(!await page.getByTestId("workspace").isVisible());
});
