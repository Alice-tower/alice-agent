import assert from "node:assert/strict";
import { test } from "node:test";
import { writeFile, readFile, stat } from "node:fs/promises";
import { resolve } from "node:path";
import { randomUUID } from "node:crypto";
import { LocalSecretStore } from "../../src/secrets/index.ts";

async function fixture() {
  const root = resolve("tmp/secrets-tests", randomUUID());
  const store = new LocalSecretStore(resolve(root, "secrets"));
  await store.initialize();
  return { root, store };
}

test("creates a stable access token and restricts local credential permissions", async () => {
  const { root, store } = await fixture();
  const token = await store.accessToken();
  assert.equal(token.length, 64);
  assert.ok(token === await store.accessToken());
  if (process.platform !== "win32") {
    assert.equal((await stat(resolve(root, "secrets"))).mode & 0o777, 0o700);
    assert.equal((await stat(store.path("access-token"))).mode & 0o777, 0o600);
  }
});

test("reuses an existing credential and never silently replaces an invalid token", async () => {
  const { store } = await fixture();
  const currentToken = randomUUID();
  await writeFile(store.path("access-token"), currentToken);
  assert.ok(await store.accessToken() === currentToken);
  await writeFile(store.path("access-token"), "invalid");
  await assert.rejects(() => store.accessToken(), /existing credentials were not replaced/);
  assert.ok(await readFile(store.path("access-token"), "utf8") === "invalid");
});

test("reads optional API keys and rejects traversal and multiline secrets without disclosing values", async () => {
  const { store } = await fixture();
  assert.equal(await store.read("llm-api-key"), undefined);
  const key = randomUUID();
  await writeFile(store.path("llm-api-key"), key + "\n");
  assert.ok(await store.read("llm-api-key") === key);
  assert.throws(() => store.path("../outside"), /Invalid credential name/);
  await writeFile(store.path("llm-api-key"), "private-value\nsecond-line");
  await assert.rejects(() => store.read("llm-api-key"), error => error instanceof Error && !error.message.includes("private-value"));
});
