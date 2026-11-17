import assert from "node:assert/strict";
import { test } from "node:test";
import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdir } from "node:fs/promises";
import { resolve } from "node:path";
import { _electron } from "playwright";

test("Electron talks to an independent backend process and local Ollama", { skip: process.env.ALICE_TEST_OLLAMA !== "1", timeout: 240_000 }, async t => {
  const data = resolve("tmp/e2e-ollama", randomUUID());
  await mkdir(data, { recursive: true });
  const token = randomUUID() + randomUUID();
  const backend = spawn(process.execPath, [resolve("dist/backend/main.js")], {
    env: { ...process.env, ALICE_HOST: "127.0.0.1", ALICE_PORT: "0", ALICE_DATA_DIR: data, ALICE_SECRETS_DIR: resolve(data, "secrets"), ALICE_ACCESS_TOKEN: token,
      ALICE_LLM_BASE_URL: process.env.OLLAMA_BASE_URL ?? "http://127.0.0.1:11434/v1" },
    stdio: ["ignore", "pipe", "pipe"],
  });
  t.after(async () => {
    if (backend.exitCode !== null) return;
    const stopped = new Promise<void>(resolve => backend.once("exit", () => resolve()));
    backend.kill("SIGTERM"); await stopped;
  });
  const baseUrl = await new Promise<string>((resolve, reject) => {
    let output = "";
    const timer = setTimeout(() => reject(new Error("Backend startup timed out")), 15_000);
    backend.once("exit", () => { clearTimeout(timer); reject(new Error("Backend could not start")); });
    backend.stdout.on("data", (chunk: Buffer) => {
      output += chunk.toString();
      const match = /listening on 127\.0\.0\.1:(\d+)/.exec(output);
      if (match) { clearTimeout(timer); resolve(`http://127.0.0.1:${match[1]}`); }
    });
  });
  const env: Record<string, string> = Object.fromEntries(Object.entries(process.env).filter((entry): entry is [string, string] => entry[1] !== undefined));
  delete env.ELECTRON_RUN_AS_NODE;
  env.ALICE_E2E_URL = baseUrl;
  env.ALICE_DESKTOP_DATA_DIR = resolve(data, "desktop");
  const app = await _electron.launch({ args: [resolve("dist/desktop/main.cjs")], env });
  t.after(async () => { await app.close(); });
  const page = await app.firstWindow();
  await page.getByTestId("access-token").fill(token);
  await page.getByTestId("connect").click();
  await page.getByTestId("model-select").locator("option").first().waitFor({ state: "attached" });
  if (process.env.OLLAMA_MODEL) await page.getByTestId("model-select").selectOption(process.env.OLLAMA_MODEL);
  await page.getByTestId("message-input").fill("请务必先调用 get_backend_time 工具，再简短报告工具返回的当前本地时间。/no_think");
  await page.getByTestId("send").click();
  await page.getByTestId("run-status").filter({ hasText: /已完成|执行失败|执行上限/ }).waitFor({ timeout: 200_000 });
  assert.equal(await page.getByTestId("run-status").textContent(), "已完成");
  assert.match(await page.getByTestId("messages").innerText(), /工具活动/);
  const text = await page.locator('.message[data-role="assistant"] .message-body').last().innerText();
  assert.ok(text.trim());
  await mkdir(resolve("tmp/screenshots"), { recursive: true });
  await page.screenshot({ path: resolve("tmp/screenshots/alice-ollama.png") });
  t.diagnostic("Independent backend + Electron + Pi + Ollama + backend time tool completed.");
});
