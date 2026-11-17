import { mkdir, readFile, writeFile, chmod } from "node:fs/promises";
import { resolve } from "node:path";
import { randomBytes } from "node:crypto";

function isMissing(error: unknown): boolean {
  return error instanceof Error && "code" in error && error.code === "ENOENT";
}

function validate(value: string, minimum = 1): string {
  const secret = value.trim();
  if (secret.length < minimum || secret.length > 65_536 || /[\r\n\0]/.test(secret)) {
    throw new Error("Credential is empty or invalid; existing credentials were not replaced.");
  }
  return secret;
}

/** Backend-only local credentials. Names are flat, explicit filenames; values are never logged. */
export class LocalSecretStore {
  readonly #directory: string;

  constructor(directory: string) { this.#directory = resolve(directory); }

  path(name: string): string {
    if (!/^[a-z0-9][a-z0-9._-]{0,127}$/.test(name) || name === "." || name === "..") throw new Error("Invalid credential name.");
    return resolve(this.#directory, name);
  }

  async initialize(): Promise<void> {
    await mkdir(this.#directory, { recursive: true, mode: 0o700 });
    await chmod(this.#directory, 0o700);
  }

  async read(name: string): Promise<string | undefined> {
    const path = this.path(name);
    try {
      const value = validate(await readFile(path, "utf8"));
      await chmod(path, 0o600);
      return value;
    } catch (error) {
      if (isMissing(error)) return undefined;
      throw error;
    }
  }

  async accessToken(): Promise<string> {
    const current = await this.read("access-token");
    if (current !== undefined) return validate(current, 24);
    const token = randomBytes(32).toString("hex");
    try {
      await writeFile(this.path("access-token"), token + "\n", { mode: 0o600, flag: "wx" });
    } catch (error) {
      if (!(error instanceof Error && "code" in error && error.code === "EEXIST")) throw error;
      const existing = await this.read("access-token");
      if (!existing) throw new Error("Credential creation failed.");
      return validate(existing, 24);
    }
    return token;
  }
}
