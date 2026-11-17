import { app, BrowserWindow, ipcMain, safeStorage, session } from "electron";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { z } from "zod";
import { BackendClient, ClientError, validateConnection } from "./backend-client.ts";
import type { BridgeResult, ConnectionInput, ConnectionState } from "./bridge.ts";
import { SubmitMessageSchema } from "../api/contracts.ts";
import { ConnectionSettings } from "./settings.ts";

let window: BrowserWindow | undefined;
let client: BackendClient | undefined;
let watcher: AbortController | undefined;
let defaultUrl = process.env.ALICE_E2E_URL ?? "http://127.0.0.1:4317";
let savedConnection: ConnectionInput | undefined;
let settingsError: string | undefined;
let settings: ConnectionSettings;
const idSchema = z.uuid();
const providerSchema = z.string().min(1).max(256);
const localEntry = pathToFileURL(join(__dirname, "index.html")).href;
const allowInsecureLan = process.env.ALICE_ALLOW_INSECURE_LAN === "1";
app.setName("Alice");
if (process.env.ALICE_DESKTOP_DATA_DIR) app.setPath("userData", process.env.ALICE_DESKTOP_DATA_DIR);

function requireClient(): BackendClient {
  if (!client) throw new ClientError("请先连接后端。");
  return client;
}
function state(value: ConnectionState) { window?.webContents.send("desktop:connection", value); }
function stopWatching() { watcher?.abort(); watcher = undefined; }

function handle<T>(name: string, action: (...args: unknown[]) => T | Promise<T>) {
  ipcMain.handle(`desktop:${name}`, async (event, ...args: unknown[]): Promise<BridgeResult<T>> => {
    if (!window || event.sender !== window.webContents || event.senderFrame?.url !== localEntry) {
      return { ok: false, error: "不允许来自此窗口的请求。" };
    }
    try { return { ok: true, value: await action(...args) }; }
    catch (error) { return { ok: false, error: error instanceof ClientError ? error.message : "无法完成操作，请检查连接和输入。" }; }
  });
}

async function watch(id: string) {
  stopWatching();
  const active = new AbortController();
  watcher = active;
  const current = requireClient();
  let attempt = 0;
  while (!active.signal.aborted && client === current) {
    try {
      await current.follow(id, active.signal, run => {
        state({ state: "connected" });
        window?.webContents.send("desktop:run", run);
      });
      return;
    } catch (error) {
      if (active.signal.aborted) return;
      if (error instanceof ClientError && error.kind !== "connection") {
        state({ state: "disconnected", message: error.message });
        return;
      }
      state({ state: "reconnecting", message: "网络连接中断，正在恢复任务状态…" });
      await new Promise<void>(resolve => {
        const done = () => { clearTimeout(timer); active.signal.removeEventListener("abort", done); resolve(); };
        const timer = setTimeout(done, Math.min(1000 * 2 ** attempt++, 10_000));
        active.signal.addEventListener("abort", done, { once: true });
      });
    }
  }
}

handle("settings", () => ({ baseUrl: defaultUrl, configured: !!savedConnection, ...(settingsError ? { error: settingsError } : {}) }));
async function connect(connection: ConnectionInput, save: boolean) {
  const next = new BackendClient(connection, allowInsecureLan);
  const info = await next.info();
  if (save) {
    try { await settings.save(connection); }
    catch (error) { throw new ClientError(error instanceof Error ? error.message : "无法保存后端连接。"); }
    savedConnection = connection;
    settingsError = undefined;
  }
  stopWatching();
  client = next;
  defaultUrl = connection.baseUrl;
  state({ state: "connected" });
  return info;
}
handle("connect", async (input) => {
  const candidate = z.strictObject({ baseUrl: z.string(), token: z.string() }).parse(input);
  if (!candidate.token && savedConnection && candidate.baseUrl.replace(/\/+$/, "") === savedConnection.baseUrl) candidate.token = savedConnection.token;
  return connect(validateConnection(candidate, allowInsecureLan), true);
});
handle("restore", () => savedConnection ? connect(savedConnection, false) : undefined);
handle("models", input => requireClient().models(providerSchema.parse(input)));
handle("conversations", () => requireClient().conversations());
handle("createConversation", () => requireClient().createConversation());
handle("conversation", input => requireClient().conversation(idSchema.parse(input)));
handle("submit", (id, input) => requireClient().submit(idSchema.parse(id), SubmitMessageSchema.parse(input)));
handle("cancel", input => requireClient().cancel(idSchema.parse(input)));
handle("watch", input => {
  const id = idSchema.parse(input);
  requireClient();
  void watch(id).catch(() => state({ state: "disconnected", message: "无法订阅任务状态，请重新连接。" }));
});

async function createWindow() {
  settings = new ConnectionSettings(app.getPath("userData"), {
    encrypt: async value => {
      if (!await safeStorage.isAsyncEncryptionAvailable()) throw new Error("Secure storage unavailable");
      return safeStorage.encryptStringAsync(value);
    },
    decrypt: async value => (await safeStorage.decryptStringAsync(value)).result,
  });
  try {
    const saved = await settings.read();
    savedConnection = saved ? validateConnection(saved, allowInsecureLan) : undefined;
    if (savedConnection) defaultUrl = savedConnection.baseUrl;
  } catch (error) { savedConnection = undefined; settingsError = error instanceof Error ? error.message : "无法读取连接设置。"; }
  const uiSession = session.fromPartition("alice-ui", { cache: false });
  window = new BrowserWindow({
    width: 1180, height: 820, minWidth: 840, minHeight: 620,
    title: "Alice", backgroundColor: "#f7f7f4",
    webPreferences: { preload: join(__dirname, "preload.cjs"), contextIsolation: true, sandbox: true, nodeIntegration: false, session: uiSession },
  });
  window.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
  window.webContents.on("will-navigate", event => event.preventDefault());
  window.webContents.session.setPermissionRequestHandler((_webContents, _permission, callback) => callback(false));
  window.on("closed", () => { stopWatching(); window = undefined; });
  await window.loadFile(join(__dirname, "index.html"));
}

void app.whenReady().then(createWindow);
app.on("activate", () => { if (!window) void createWindow(); });
app.on("window-all-closed", () => { if (process.platform !== "darwin") app.quit(); });
app.on("before-quit", () => { stopWatching(); client = undefined; });
