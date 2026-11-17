import { randomUUID } from "node:crypto";
import type { RunSnapshot, SubmitMessage } from "../api/contracts.ts";
import { ProviderError, type ProviderRegistry, type ProviderMessage } from "../provider/index.ts";
import type { AgentRuntime, RuntimeTool } from "../runtime/index.ts";
import type { ChatStore } from "../storage/index.ts";

export class ApplicationError extends Error {
  readonly status: number;
  readonly code: string;
  constructor(status: number, code: string, message: string) { super(message); this.status = status; this.code = code; }
}

export class RunService {
  readonly #active = new Map<string, { controller: AbortController; settled: Promise<void> }>();
  readonly #listeners = new Map<string, Set<(run: RunSnapshot) => void>>();
  readonly #store: ChatStore;
  readonly #providers: ProviderRegistry;
  readonly #runtime: AgentRuntime;
  readonly #tools: RuntimeTool[];
  readonly #limits: { maxTurns: number; timeoutMs: number; maxOutputTokens: number };

  constructor(options: { store: ChatStore; providers: ProviderRegistry; runtime: AgentRuntime; tools: RuntimeTool[]; limits?: { maxTurns: number; timeoutMs: number; maxOutputTokens: number } }) {
    this.#store = options.store; this.#providers = options.providers; this.#runtime = options.runtime; this.#tools = options.tools;
    this.#limits = options.limits ?? { maxTurns: 8, timeoutMs: 180_000, maxOutputTokens: 2048 };
    this.#store.interruptRunning();
  }

  listConversations() { return this.#store.listConversations(); }
  createConversation() { return this.#store.createConversation(); }
  conversation(id: string) {
    const conversation = this.#store.conversation(id);
    if (!conversation) throw new ApplicationError(404, "not-found", "对话不存在。");
    return conversation;
  }
  run(id: string) {
    const run = this.#store.run(id);
    if (!run) throw new ApplicationError(404, "not-found", "执行任务不存在。");
    return run;
  }

  submit(conversationId: string, input: SubmitMessage): RunSnapshot {
    const detail = this.conversation(conversationId);
    const existing = this.#store.findRequest(conversationId, input.requestId);
    if (existing) {
      if (JSON.stringify(existing.input) !== JSON.stringify(input)) throw new ApplicationError(409, "request-conflict", "请求标识已用于其他内容。");
      return existing.run;
    }
    if (detail.runs.some(run => run.status === "running")) throw new ApplicationError(409, "conversation-busy", "当前对话仍在执行，请等待或取消后再发送。");
    let provider;
    try { provider = this.#providers.get(input.providerId); }
    catch { throw new ApplicationError(400, "provider", "模型服务未配置。"); }
    const now = Date.now();
    const run: RunSnapshot = {
      id: randomUUID(), conversationId, requestId: input.requestId, providerId: input.providerId, model: input.model,
      status: "running", text: "", reasoning: "", turns: 0, tools: [], createdAt: now, updatedAt: now,
    };
    const history = this.#store.history(conversationId);
    const messages: ProviderMessage[] = [
      ...(history.length ? history : [{ role: "system" as const, content: "你是用户的私人助理。请用中文简洁回答；需要当前时间时调用 get_backend_time，所有工具操作均发生在后端机器。" }]),
      { role: "user", content: input.text },
    ];
    this.#store.startRun(run, input);
    const controller = new AbortController();
    const settled = Promise.resolve().then(async () => {
      const textByTurn = new Map<number, string>();
      const reasoningByTurn = new Map<number, string>();
      const startMessage = (createdAt: number) => {
        if (run.messageCreatedAt !== undefined) return;
        run.messageCreatedAt = createdAt;
        this.#store.startAssistantMessage(run, createdAt);
      };
      const refreshText = () => {
        run.text = [...textByTurn].sort(([a], [b]) => a - b).map(([, value]) => value).filter(Boolean).join("\n\n");
        run.reasoning = [...reasoningByTurn].sort(([a], [b]) => a - b).map(([, value]) => value).filter(Boolean).join("\n\n");
      };
      try {
        for await (const event of this.#runtime.run({ messages, model: input.model, ...this.#limits }, {
          provider, tools: this.#tools, allowedTools: this.#tools.map(tool => tool.name), signal: controller.signal,
        })) {
          if (event.type === "message-start") startMessage(event.createdAt);
          else if (event.type === "text-delta") {
            startMessage(Date.now());
            run.firstOutputAt ??= Date.now();
            textByTurn.set(event.turn, (textByTurn.get(event.turn) ?? "") + event.text);
            refreshText();
          } else if (event.type === "reasoning-delta") {
            startMessage(Date.now());
            run.firstOutputAt ??= Date.now();
            reasoningByTurn.set(event.turn, (reasoningByTurn.get(event.turn) ?? "") + event.text);
            refreshText();
          } else if (event.type === "assistant-message") {
            startMessage(Date.now());
            if (event.message.content.some(part => (part.type === "text" || part.type === "reasoning") && part.text.length > 0)) run.firstOutputAt ??= Date.now();
            textByTurn.set(event.turn, event.message.content.filter(part => part.type === "text").map(part => part.text).join(""));
            reasoningByTurn.set(event.turn, event.message.content.filter(part => part.type === "reasoning").map(part => part.text).join(""));
            refreshText();
            run.turns = event.turn;
          }
          else if (event.type === "tool-start") run.tools.push({ callId: event.toolCallId, name: event.name, arguments: JSON.stringify(event.arguments), status: "running", startedAt: Date.now() });
          else if (event.type === "tool-end") {
            const tool = run.tools.find(tool => tool.callId === event.toolCallId);
            if (tool) { tool.status = event.isError ? "failed" : "completed"; tool.output = event.content; tool.finishedAt = Date.now(); }
          } else if (event.type === "done") {
            run.status = event.result.status;
            run.finishedAt = Date.now();
            run.turns = event.result.turns;
            if (event.result.error) run.error = event.result.error;
            for (const tool of run.tools) if (tool.status === "running") { tool.status = "failed"; tool.finishedAt = run.finishedAt; }
            run.updatedAt = Math.max(Date.now(), run.updatedAt + 1);
            this.#store.finishRun(run, run.status === "completed" ? event.result.messages : undefined);
            this.#publish(run);
            return;
          }
          run.updatedAt = Math.max(Date.now(), run.updatedAt + 1);
          this.#store.updateRun(run);
          this.#publish(run);
        }
        throw new Error("Runtime ended without a result");
      } catch (error) {
        run.status = controller.signal.aborted ? "cancelled" : "failed";
        run.finishedAt = Date.now();
        run.error = { code: error instanceof ProviderError ? error.code : "execution", message: "执行未能完成，请检查模型连接或重新发送。" };
        for (const tool of run.tools) if (tool.status === "running") { tool.status = "failed"; tool.finishedAt = run.finishedAt; }
        run.updatedAt = Math.max(Date.now(), run.updatedAt + 1);
        this.#store.finishRun(run);
        this.#publish(run);
      }
    }).finally(() => { this.#active.delete(run.id); });
    this.#active.set(run.id, { controller, settled });
    return structuredClone(run);
  }

  cancel(id: string): RunSnapshot {
    const run = this.run(id);
    this.#active.get(id)?.controller.abort();
    return run;
  }

  subscribe(id: string, listener: (run: RunSnapshot) => void): () => void {
    this.run(id);
    const listeners = this.#listeners.get(id) ?? new Set();
    listeners.add(listener); this.#listeners.set(id, listeners);
    return () => { listeners.delete(listener); if (!listeners.size) this.#listeners.delete(id); };
  }

  #publish(run: RunSnapshot): void {
    for (const listener of this.#listeners.get(run.id) ?? []) {
      try { listener(structuredClone(run)); }
      catch {
        this.#listeners.get(run.id)?.delete(listener);
        console.error("Run subscriber disconnected after an observer error.");
      }
    }
  }

  async stop(): Promise<void> {
    const active = [...this.#active.values()];
    for (const run of active) run.controller.abort();
    await Promise.allSettled(active.map(run => run.settled));
  }
}
