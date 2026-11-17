import type { DesktopBridge, BridgeResult } from "./bridge.ts";
import type { Conversation, ConversationDetail, RunSnapshot, ServerInfo } from "../api/contracts.ts";

declare global { interface Window { alice: DesktopBridge } }

function element<T extends HTMLElement>(id: string): T {
  const found = document.getElementById(id);
  if (!found) throw new Error(`Missing UI element: ${id}`);
  return found as T;
}
const ui = {
  connectionForm: element<HTMLFormElement>("connection-form"),
  dialog: element<HTMLDialogElement>("connection-dialog"),
  workspace: element<HTMLElement>("workspace"), setup: element<HTMLElement>("setup-screen"),
  connectionError: element<HTMLElement>("connection-error"),
  editConnection: element<HTMLButtonElement>("edit-connection"), closeSettings: element<HTMLButtonElement>("close-settings"),
  url: element<HTMLInputElement>("backend-url"), token: element<HTMLInputElement>("access-token"),
  connect: element<HTMLButtonElement>("connect"),
  connectionStatus: element<HTMLSpanElement>("connection-status"),
  connectionDescription: element<HTMLParagraphElement>("connection-description"),
  provider: element<HTMLSelectElement>("provider-select"), model: element<HTMLSelectElement>("model-select"),
  newConversation: element<HTMLButtonElement>("new-conversation"), list: element<HTMLElement>("conversation-list"),
  messages: element<HTMLElement>("messages"), title: element<HTMLElement>("conversation-title"),
  form: element<HTMLFormElement>("message-form"), input: element<HTMLTextAreaElement>("message-input"),
  send: element<HTMLButtonElement>("send"), cancel: element<HTMLButtonElement>("cancel"),
  runStatus: element<HTMLElement>("run-status"), error: element<HTMLElement>("error"),
};
let connected = false;
let info: ServerInfo | undefined;
let conversations: Conversation[] = [];
let selected: string | undefined;
let detail: ConversationDetail | undefined;
let currentRun: RunSnapshot | undefined;
let sending = false;
let connecting = false;
let configured = false;
let entered = false;
let selectionGeneration = 0;
let modelGeneration = 0;
let backendGeneration = 0;
let pendingSubmit: { requestId: string; text: string; conversationId: string; providerId: string; model: string } | undefined;

// REST responses and SSE can arrive in a different order. Terminal snapshots are final.
function acceptRun(next: RunSnapshot | undefined) {
  if (!next) { currentRun = undefined; return; }
  if (currentRun?.id === next.id) {
    if (currentRun.status !== "running" && next.status === "running") return;
    if (next.updatedAt < currentRun.updatedAt) return;
  }
  currentRun = next;
}

class StaleBackendResponse extends Error {}

async function unwrap<T>(value: Promise<BridgeResult<T>>, generation = backendGeneration): Promise<T> {
  const result = await value;
  if (generation !== backendGeneration) throw new StaleBackendResponse();
  if (!result.ok) throw new Error(result.error);
  return result.value;
}
function showError(error?: unknown) {
  if (error instanceof StaleBackendResponse) return;
  ui.error.textContent = error instanceof Error ? error.message : typeof error === "string" ? error : "";
  ui.error.hidden = !ui.error.textContent;
}
function updateControls() {
  const active = currentRun?.status === "running";
  ui.send.disabled = !connected || !ui.model.value || !ui.input.value.trim() || active || sending;
  ui.cancel.hidden = !active;
  ui.cancel.disabled = !connected;
  ui.input.disabled = !connected;
  ui.provider.disabled = !connected || active || sending;
  ui.model.disabled = !connected || active || sending;
  ui.newConversation.disabled = !connected || sending;
  ui.connect.disabled = connecting;
  ui.url.disabled = connecting; ui.token.disabled = connecting;
  ui.closeSettings.hidden = !entered || !connected;
  ui.closeSettings.disabled = connecting;
  ui.connect.textContent = connecting ? "连接中…" : entered ? "保存并连接" : "连接并进入";
  for (const button of ui.list.querySelectorAll<HTMLButtonElement>("button")) button.disabled = sending;
}

function renderConversations() {
  ui.list.replaceChildren();
  if (!conversations.length) {
    const empty = document.createElement("p"); empty.className = "list-empty"; empty.textContent = connected ? "开始第一段对话" : "连接后查看你的会话";
    ui.list.append(empty);
  }
  for (const conversation of conversations) {
    const button = document.createElement("button");
    button.className = `conversation-item${selected === conversation.id ? " selected" : ""}`;
    button.dataset.conversationId = conversation.id;
    button.dataset.testid = "conversation-item";
    const title = document.createElement("span"); title.textContent = conversation.title;
    const date = document.createElement("small"); date.textContent = new Date(conversation.updatedAt).toLocaleDateString("zh-CN", { month: "short", day: "numeric" });
    button.append(title, date);
    button.addEventListener("click", () => { void loadConversation(conversation.id).catch(showError); });
    ui.list.append(button);
  }
}

function message(role: "user" | "assistant", text: string, streaming = false) {
  const article = document.createElement("article"); article.className = `message ${role}`; article.dataset.role = role;
  const label = document.createElement("div"); label.className = "message-label"; label.textContent = role === "user" ? "你" : "Alice";
  const body = document.createElement("div"); body.className = "message-body"; body.textContent = text || (streaming ? "正在准备回答…" : "");
  article.append(label, body);
  return article;
}

const statusLabels: Record<RunSnapshot["status"], string> = {
  running: "正在处理", completed: "已完成", failed: "执行失败", cancelled: "已停止", limited: "已达到执行上限", interrupted: "后端重启，任务已中断",
};

function runDetails(run: RunSnapshot) {
  const container = document.createElement("div"); container.className = "run-details";
  if (run.tools.length) {
    const section = document.createElement("details"); section.open = run.status === "running"; section.className = "tool-section";
    const summary = document.createElement("summary"); summary.textContent = `工具活动 · ${run.tools.length}`; section.append(summary);
    for (const tool of run.tools) {
      const card = document.createElement("div"); card.className = `tool-card ${tool.status}`;
      const name = document.createElement("strong"); name.textContent = tool.name === "get_backend_time" ? "读取后端时间" : tool.name;
      const state = document.createElement("span"); state.textContent = tool.status === "running" ? "进行中" : tool.status === "failed" ? "失败" : "完成";
      const row = document.createElement("div"); row.className = "tool-heading"; row.append(name, state); card.append(row);
      if (tool.output) { const output = document.createElement("pre"); output.textContent = tool.output; card.append(output); }
      section.append(card);
    }
    container.append(section);
  }
  if (run.reasoning) {
    const thinking = document.createElement("details"); thinking.className = "reasoning";
    const summary = document.createElement("summary"); summary.textContent = "思考过程";
    const body = document.createElement("div"); body.textContent = run.reasoning;
    thinking.append(summary, body); container.append(thinking);
  }
  if (run.status !== "running" && run.status !== "completed") {
    const notice = document.createElement("p"); notice.className = "run-notice";
    notice.textContent = `${statusLabels[run.status]}${run.error?.message ? `：${run.error.message}` : ""}`;
    container.append(notice);
  }
  return container;
}

function renderMessages() {
  const nearBottom = ui.messages.scrollHeight - ui.messages.scrollTop - ui.messages.clientHeight < 100;
  ui.messages.replaceChildren();
  ui.title.textContent = detail?.conversation.title ?? "新的灵感，从这里开始";
  if (!detail?.messages.length && !currentRun) {
    const welcome = document.createElement("div"); welcome.className = "welcome";
    const symbol = document.createElement("div"); symbol.className = "welcome-symbol"; symbol.textContent = "✳";
    const heading = document.createElement("h1"); heading.textContent = "有什么想一起完成的？";
    const body = document.createElement("p"); body.textContent = connected ? "开始一段对话，Alice 会在你的后端上思考与执行。" : "连接你的后端，让 Alice 开始工作。";
    const example = document.createElement("button"); example.className = "example"; example.textContent = "读取后端当前时间，计算今天还剩多久";
    example.disabled = !connected;
    example.addEventListener("click", () => { ui.input.value = example.textContent ?? ""; ui.input.focus(); updateControls(); });
    welcome.append(symbol, heading, body, example); ui.messages.append(welcome);
  }
  const renderedRuns = new Set<string>();
  for (const item of detail?.messages ?? []) {
    const run = currentRun?.id === item.runId ? currentRun : detail?.runs.find(run => run.id === item.runId);
    ui.messages.append(message(item.role, item.role === "assistant" && run ? run.text : item.text));
    if (item.role === "assistant" && run) { ui.messages.append(runDetails(run)); renderedRuns.add(run.id); }
  }
  if (currentRun && !renderedRuns.has(currentRun.id)) {
    ui.messages.append(message("assistant", currentRun.text, currentRun.status === "running"));
    ui.messages.append(runDetails(currentRun));
  }
  ui.runStatus.textContent = currentRun ? statusLabels[currentRun.status] : "";
  ui.runStatus.className = currentRun?.status === "running" ? "run-status active" : "run-status";
  if (nearBottom || currentRun?.status === "running") ui.messages.scrollTop = ui.messages.scrollHeight;
  updateControls();
}

async function refreshConversations() {
  conversations = await unwrap(window.alice.conversations());
  renderConversations();
}

async function loadConversation(id: string) {
  const generation = ++selectionGeneration;
  const next = await unwrap(window.alice.conversation(id));
  if (generation !== selectionGeneration) return;
  selected = id; detail = next;
  const nextRun = next.runs.findLast(run => run.status === "running") ?? next.runs.at(-1);
  if (currentRun?.conversationId !== id) currentRun = undefined;
  acceptRun(nextRun);
  pendingSubmit = undefined;
  showError(); renderConversations(); renderMessages();
  if (currentRun?.status === "running") await unwrap(window.alice.watch(currentRun.id));
}

async function loadModels() {
  const generation = ++modelGeneration;
  ui.model.replaceChildren(); updateControls();
  if (!ui.provider.value) return;
  const models = await unwrap(window.alice.models(ui.provider.value));
  if (generation !== modelGeneration) return;
  for (const model of models) { const option = document.createElement("option"); option.value = model.id; option.textContent = model.name; ui.model.append(option); }
  if (!models.length) showError("后端暂无可用模型，请先在模型服务中准备模型。");
  updateControls();
}

function showConnectionError(error?: unknown) {
  ui.connectionError.textContent = error instanceof Error ? error.message : typeof error === "string" ? error : "";
  ui.connectionError.hidden = !ui.connectionError.textContent;
}
function openSettings() {
  ui.token.value = "";
  ui.token.placeholder = configured ? "留空使用此后端已保存的令牌" : "输入后端访问令牌";
  if (!ui.dialog.open) ui.dialog.showModal();
  updateControls();
}
async function enterWorkspace(nextInfo: ServerInfo) {
  info = nextInfo; connected = true; configured = true;
  // A backend switch must not carry the previous backend's transient business state.
  selectionGeneration++; modelGeneration++;
  selected = undefined; detail = undefined; currentRun = undefined; conversations = []; pendingSubmit = undefined; ui.input.value = "";
  ui.token.value = "";
  ui.provider.replaceChildren(); ui.model.replaceChildren(); renderConversations(); renderMessages();
  for (const provider of info.providers) {
    const option = document.createElement("option"); option.value = provider.id; option.textContent = provider.id; ui.provider.append(option);
  }
  entered = true; ui.workspace.hidden = false; ui.setup.hidden = true;
  if (ui.dialog.open) ui.dialog.close();
  await refreshConversations();
  if (conversations[0]) await loadConversation(conversations[0].id);
  try { await loadModels(); } catch (error) { showError(error); }
  ui.input.focus();
}
async function connect(event?: SubmitEvent) {
  event?.preventDefault(); connecting = true; updateControls(); showError();
  backendGeneration++;
  showConnectionError();
  try {
    await enterWorkspace(await unwrap(window.alice.connect({ baseUrl: ui.url.value.trim(), token: ui.token.value })));
  } catch (error) { openSettings(); showConnectionError(error); }
  finally { connecting = false; updateControls(); }
}

async function createConversation() {
  const created = await unwrap(window.alice.createConversation());
  await refreshConversations(); await loadConversation(created.id); ui.input.focus();
}

async function send(event: SubmitEvent) {
  event.preventDefault();
  const text = ui.input.value.trim();
  if (!connected || !text || !ui.model.value || sending || currentRun?.status === "running") return;
  sending = true; showError(); updateControls();
  try {
    if (!selected) await createConversation();
    const conversationId = selected;
    if (!conversationId) throw new Error("请先建立会话。");
    if (!pendingSubmit || pendingSubmit.text !== text || pendingSubmit.conversationId !== conversationId
      || pendingSubmit.providerId !== ui.provider.value || pendingSubmit.model !== ui.model.value) {
      pendingSubmit = { requestId: crypto.randomUUID(), text, conversationId, providerId: ui.provider.value, model: ui.model.value };
    }
    const { requestId, providerId, model } = pendingSubmit;
    const run = await unwrap(window.alice.submit(conversationId, { requestId, text, providerId, model }));
    pendingSubmit = undefined; ui.input.value = "";
    await loadConversation(conversationId);
    acceptRun(run);
    renderMessages();
    if (run.status === "running") await unwrap(window.alice.watch(run.id));
    await refreshConversations();
  } catch (error) { showError(error); }
  finally { sending = false; updateControls(); }
}

ui.connectionForm.addEventListener("submit", event => { void connect(event); });
ui.editConnection.addEventListener("click", () => { showConnectionError(); openSettings(); });
ui.closeSettings.addEventListener("click", () => { if (entered && connected) ui.dialog.close(); });
ui.dialog.addEventListener("cancel", event => { if (!entered || !connected || connecting) event.preventDefault(); });
ui.newConversation.addEventListener("click", () => { void createConversation().catch(showError); });
ui.provider.addEventListener("change", () => { void loadModels().catch(showError); });
ui.model.addEventListener("change", updateControls);
ui.input.addEventListener("input", updateControls);
ui.input.addEventListener("keydown", event => { if (event.key === "Enter" && !event.shiftKey && !event.isComposing) { event.preventDefault(); if (!ui.send.disabled) ui.form.requestSubmit(); } });
ui.form.addEventListener("submit", event => { void send(event); });
ui.cancel.addEventListener("click", () => {
  if (!currentRun) return;
  const id = currentRun.id;
  void unwrap(window.alice.cancel(id)).then(run => {
    if (run.conversationId !== selected || (currentRun && currentRun.id !== id)) return;
    acceptRun(run); renderMessages();
  }).catch(showError);
});
window.alice.onConnection(connection => {
  connected = connection.state !== "disconnected";
  ui.connectionStatus.textContent = connection.state === "connected" ? "已连接" : connection.state === "reconnecting" ? "重连中" : "未连接";
  ui.connectionStatus.dataset.state = connection.state;
  ui.connectionDescription.textContent = connection.message ?? (connection.state === "connected" ? "执行与数据保留在后端" : "连接你自己的 Agent 后端");
  updateControls();
  if (connection.state === "disconnected") { openSettings(); if (connection.message) showConnectionError(connection.message); }
});
window.alice.onRun(run => {
  if (run.conversationId !== selected) return;
  acceptRun(run); renderMessages();
  if (run.status !== "running") {
    const id = selected;
    void unwrap(window.alice.conversation(id)).then(next => {
      if (id !== selected) return;
      detail = next; renderMessages();
      return refreshConversations();
    }).catch(showError);
  }
});

async function initialize() {
  try {
    const settings = await unwrap(window.alice.settings());
    ui.url.value = settings.baseUrl; configured = settings.configured;
    if (settings.configured) {
      backendGeneration++;
      connecting = true; updateControls();
      const restored = await unwrap(window.alice.restore());
      if (restored) { await enterWorkspace(restored); return; }
    }
    openSettings(); if (settings.error) showConnectionError(settings.error);
  } catch (error) { openSettings(); showConnectionError(error); }
  finally { connecting = false; updateControls(); }
}
void initialize();
renderConversations(); renderMessages(); updateControls();
