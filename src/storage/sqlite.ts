import { DatabaseSync } from "node:sqlite";
import { randomUUID } from "node:crypto";
import { ConversationSchema, RunSchema, SubmitMessageSchema, ChatMessageSchema, type Conversation, type RunSnapshot, type SubmitMessage } from "../api/contracts.ts";
import type { ProviderMessage } from "../provider/index.ts";
import type { ChatStore } from "./contracts.ts";

export class SqliteChatStore implements ChatStore {
  readonly #db: DatabaseSync;

  constructor(path: string) {
    this.#db = new DatabaseSync(path);
    // One backend owns this database; a second process must fail before recovering runs.
    try {
      this.#db.exec("PRAGMA foreign_keys=ON; PRAGMA busy_timeout=1000; PRAGMA locking_mode=EXCLUSIVE; PRAGMA journal_mode=WAL;");
    } catch (error) { this.#db.close(); throw error; }
    const version = this.#db.prepare("PRAGMA user_version").get()?.user_version;
    if (version !== 0 && version !== 1) {
      this.#db.close();
      throw new Error("Unsupported database version; refusing to modify user data.");
    }
    this.#db.exec(`
      CREATE TABLE IF NOT EXISTS conversations (
        id TEXT PRIMARY KEY, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
        history_json TEXT NOT NULL DEFAULT '[]'
      );
      CREATE TABLE IF NOT EXISTS runs (
        id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL REFERENCES conversations(id),
        request_id TEXT NOT NULL, input_json TEXT NOT NULL, snapshot_json TEXT NOT NULL,
        UNIQUE(conversation_id, request_id)
      );
      CREATE TABLE IF NOT EXISTS messages (
        id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL REFERENCES conversations(id),
        run_id TEXT NOT NULL REFERENCES runs(id), role TEXT NOT NULL, text TEXT NOT NULL, created_at INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS messages_conversation ON messages(conversation_id, created_at);
      CREATE UNIQUE INDEX IF NOT EXISTS messages_run_role ON messages(run_id, role);
      PRAGMA user_version=1;
    `);
  }

  #transaction(action: () => void): void {
    this.#db.exec("BEGIN IMMEDIATE");
    try { action(); this.#db.exec("COMMIT"); }
    catch (error) { this.#db.exec("ROLLBACK"); throw error; }
  }

  createConversation(): Conversation {
    const now = Date.now();
    const conversation = { id: randomUUID(), title: "新对话", createdAt: now, updatedAt: now };
    this.#db.prepare("INSERT INTO conversations(id,title,created_at,updated_at) VALUES(?,?,?,?)")
      .run(conversation.id, conversation.title, now, now);
    return conversation;
  }

  listConversations(): Conversation[] {
    return this.#db.prepare("SELECT id,title,created_at AS createdAt,updated_at AS updatedAt FROM conversations ORDER BY updated_at DESC").all().map(row => ConversationSchema.parse(row));
  }

  conversation(id: string) {
    const row = this.#db.prepare("SELECT id,title,created_at AS createdAt,updated_at AS updatedAt FROM conversations WHERE id=?").get(id);
    if (!row) return undefined;
    const messages = this.#db.prepare("SELECT id,role,text,created_at AS createdAt,run_id AS runId FROM messages WHERE conversation_id=? ORDER BY created_at,rowid").all(id).map(value => ChatMessageSchema.parse(value));
    const runs = this.#db.prepare("SELECT snapshot_json FROM runs WHERE conversation_id=? ORDER BY rowid").all(id).map(value => RunSchema.parse(JSON.parse(String(value.snapshot_json))));
    return { conversation: ConversationSchema.parse(row), messages, runs };
  }

  history(id: string): ProviderMessage[] {
    const row = this.#db.prepare("SELECT history_json FROM conversations WHERE id=?").get(id);
    // Written only from validated runtime results; provider validates again before sending.
    return row ? JSON.parse(String(row.history_json)) as ProviderMessage[] : [];
  }

  run(id: string): RunSnapshot | undefined {
    const row = this.#db.prepare("SELECT snapshot_json FROM runs WHERE id=?").get(id);
    return row ? RunSchema.parse(JSON.parse(String(row.snapshot_json))) : undefined;
  }

  findRequest(conversationId: string, requestId: string) {
    const row = this.#db.prepare("SELECT snapshot_json,input_json FROM runs WHERE conversation_id=? AND request_id=?").get(conversationId, requestId);
    return row ? { run: RunSchema.parse(JSON.parse(String(row.snapshot_json))), input: SubmitMessageSchema.parse(JSON.parse(String(row.input_json))) } : undefined;
  }

  startRun(run: RunSnapshot, input: SubmitMessage): void {
    this.#transaction(() => {
      this.#db.prepare("INSERT INTO runs(id,conversation_id,request_id,input_json,snapshot_json) VALUES(?,?,?,?,?)")
        .run(run.id, run.conversationId, run.requestId, JSON.stringify(input), JSON.stringify(run));
      this.#db.prepare("INSERT INTO messages(id,conversation_id,run_id,role,text,created_at) VALUES(?,?,?,?,?,?)")
        .run(randomUUID(), run.conversationId, run.id, "user", input.text, run.createdAt);
      this.#db.prepare("UPDATE conversations SET title=CASE WHEN title='新对话' THEN ? ELSE title END,updated_at=? WHERE id=?")
        .run(input.text.slice(0, 40), run.updatedAt, run.conversationId);
    });
  }

  updateRun(run: RunSnapshot): void {
    this.#db.prepare("UPDATE runs SET snapshot_json=? WHERE id=?").run(JSON.stringify(run), run.id);
    this.#db.prepare("UPDATE messages SET text=? WHERE run_id=? AND role='assistant'").run(run.text, run.id);
  }

  startAssistantMessage(run: RunSnapshot, createdAt: number): void {
    this.#db.prepare("INSERT INTO messages(id,conversation_id,run_id,role,text,created_at) VALUES(?,?,?,?,?,?) ON CONFLICT(run_id,role) DO NOTHING")
      .run(randomUUID(), run.conversationId, run.id, "assistant", run.text, createdAt);
  }

  finishRun(run: RunSnapshot, history?: ProviderMessage[]): void {
    this.#transaction(() => {
      this.updateRun(run);
      this.startAssistantMessage(run, run.messageCreatedAt ?? run.finishedAt ?? Date.now());
      this.#db.prepare("UPDATE conversations SET updated_at=? WHERE id=?").run(run.finishedAt ?? Date.now(), run.conversationId);
      if (history) this.#db.prepare("UPDATE conversations SET history_json=? WHERE id=?").run(JSON.stringify(history), run.conversationId);
    });
  }

  interruptRunning(): void {
    for (const row of this.#db.prepare("SELECT snapshot_json FROM runs").all()) {
      const run = RunSchema.parse(JSON.parse(String(row.snapshot_json)));
      if (run.status !== "running") continue;
      run.status = "interrupted";
      run.finishedAt = Date.now();
      run.updatedAt = Math.max(run.finishedAt, run.updatedAt + 1);
      run.error = { code: "interrupted", message: "后端上次停止时任务仍在执行，已标记中断；未自动重放工具。" };
      for (const tool of run.tools) if (tool.status === "running") { tool.status = "failed"; tool.finishedAt = run.finishedAt; }
      this.finishRun(run);
    }
  }

  close(): void { this.#db.close(); }
}
