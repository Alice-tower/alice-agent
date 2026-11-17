import assert from "node:assert/strict";
import { test } from "node:test";
import { mkdir, readFile, readdir } from "node:fs/promises";
import { resolve } from "node:path";
import { randomUUID } from "node:crypto";
import { ConnectionSettings, type SettingsCipher } from "../../src/desktop/settings.ts";

const cipher: SettingsCipher = {
  encrypt: async text => Buffer.from(text.split("").reverse().join("")),
  decrypt: async value => value.toString().split("").reverse().join(""),
};

test("persists only backend settings and delegates credential protection to the platform cipher", async () => {
  const directory = resolve("tmp/settings-tests", randomUUID());
  const settings = new ConnectionSettings(directory, cipher);
  assert.equal(await settings.read(), undefined);
  const input = { baseUrl: "http://127.0.0.1:4317", token: randomUUID() };
  await settings.save(input);
  assert.deepEqual(await new ConnectionSettings(directory, cipher).read(), input);
  const raw = await readFile(resolve(directory, "connection.json"), "utf8");
  assert.ok(!raw.includes(input.token));
  assert.deepEqual(Object.keys(JSON.parse(raw)), ["baseUrl", "encryptedToken"]);
  assert.deepEqual(await readdir(directory), ["connection.json"]);
});

test("a failed encryption never overwrites saved connection settings", async () => {
  const directory = resolve("tmp/settings-tests", randomUUID());
  const settings = new ConnectionSettings(directory, cipher);
  const input = { baseUrl: "http://127.0.0.1:4317", token: randomUUID() };
  await settings.save(input);
  const unavailable = new ConnectionSettings(directory, { ...cipher, encrypt: async () => { throw new Error("private error"); } });
  await assert.rejects(() => unavailable.save({ ...input, token: randomUUID() }), /无法安全保存/);
  assert.deepEqual(await settings.read(), input);
  assert.deepEqual(await readdir(directory), ["connection.json"]);
});

test("unreadable encrypted credentials require reconfiguration", async () => {
  const directory = resolve("tmp/settings-tests", randomUUID());
  await mkdir(directory, { recursive: true });
  const settings = new ConnectionSettings(directory, cipher);
  await settings.save({ baseUrl: "http://127.0.0.1:4317", token: randomUUID() });
  const unavailable = new ConnectionSettings(directory, { ...cipher, decrypt: async () => { throw new Error("private error"); } });
  await assert.rejects(() => unavailable.read(), /无法解锁后端连接设置/);
});
