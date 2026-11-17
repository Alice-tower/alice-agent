import { readFile, writeFile, mkdir, rename, unlink } from "node:fs/promises";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { z } from "zod";
import type { ConnectionInput } from "./bridge.ts";

export interface SettingsCipher {
  encrypt(value: string): Promise<Buffer>;
  decrypt(value: Buffer): Promise<string>;
}

/** The only application-owned persistent frontend data: its backend connection. */
export class ConnectionSettings {
  readonly #directory: string;
  readonly #cipher: SettingsCipher;
  constructor(directory: string, cipher: SettingsCipher) { this.#directory = directory; this.#cipher = cipher; }

  async read(): Promise<ConnectionInput | undefined> {
    let text: string;
    try { text = await readFile(join(this.#directory, "connection.json"), "utf8"); }
    catch (error) {
      if (error instanceof Error && "code" in error && error.code === "ENOENT") return undefined;
      throw new Error("无法读取后端连接设置。");
    }
    try {
      const saved = z.strictObject({ baseUrl: z.url(), encryptedToken: z.string().min(1) }).parse(JSON.parse(text));
      return { baseUrl: saved.baseUrl, token: await this.#cipher.decrypt(Buffer.from(saved.encryptedToken, "base64")) };
    } catch { throw new Error("无法解锁后端连接设置，请重新填写。"); }
  }

  async save(connection: ConnectionInput): Promise<void> {
    const temporary = join(this.#directory, `.connection-${randomUUID()}.tmp`);
    try {
      const encrypted = await this.#cipher.encrypt(connection.token);
      await mkdir(this.#directory, { recursive: true, mode: 0o700 });
      await writeFile(temporary, JSON.stringify({ baseUrl: connection.baseUrl, encryptedToken: encrypted.toString("base64") }), { mode: 0o600, flag: "wx" });
      await rename(temporary, join(this.#directory, "connection.json"));
    } catch {
      await unlink(temporary).catch(() => undefined);
      throw new Error("无法安全保存后端连接设置，请检查系统凭据服务与目录权限。");
    }
  }
}
