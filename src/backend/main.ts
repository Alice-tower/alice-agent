import { mkdir, chmod } from "node:fs/promises";
import { resolve } from "node:path";
import { z } from "zod";
import { ProviderRegistry } from "../provider/index.ts";
import { createPiRuntime } from "../runtime/index.ts";
import { createBackendTimeTool } from "../tools/index.ts";
import { SqliteChatStore } from "../storage/index.ts";
import { RunService } from "../runs/index.ts";
import { createBackendServer } from "./server.ts";
import { LocalSecretStore } from "../secrets/index.ts";

const config = z.object({
  host: z.string().min(1), port: z.coerce.number().int().min(0).max(65535),
  dataDir: z.string().min(1), baseUrl: z.url(), apiKey: z.string().optional(),
}).parse({
  host: process.env.ALICE_HOST ?? "127.0.0.1", port: process.env.ALICE_PORT ?? 4317,
  dataDir: process.env.ALICE_DATA_DIR ?? "data", baseUrl: process.env.ALICE_LLM_BASE_URL ?? "http://127.0.0.1:11434/v1",
  apiKey: process.env.ALICE_LLM_API_KEY,
});
if (!["127.0.0.1", "::1", "localhost"].includes(config.host) && process.env.ALICE_ALLOW_INSECURE_LAN !== "1") {
  throw new Error("LAN HTTP requires ALICE_ALLOW_INSECURE_LAN=1 for trusted-network development, or deploy behind a TLS reverse proxy.");
}
const dataDir = resolve(config.dataDir);
await mkdir(dataDir, { recursive: true, mode: 0o700 });
await chmod(dataDir, 0o700);
const secrets = new LocalSecretStore(process.env.ALICE_SECRETS_DIR ?? "secrets");
await secrets.initialize();
const tokenPath = secrets.path("access-token");
const token = process.env.ALICE_ACCESS_TOKEN ?? await secrets.accessToken();
const apiKey = config.apiKey ?? await secrets.read("llm-api-key");
const providers = new ProviderRegistry();
providers.add({ id: "default", protocol: "openai-chat", baseUrl: config.baseUrl, ...(apiKey ? { apiKey } : {}), timeoutMs: 180_000 });
const tools = [createBackendTimeTool()];
const store = new SqliteChatStore(resolve(dataDir, "alice.sqlite"));
const service = new RunService({ store, providers, runtime: createPiRuntime(), tools });
const server = createBackendServer({ token, service, providers, toolNames: tools.map(tool => tool.name) });
server.on("error", error => { console.error("Backend could not listen:", error.message); process.exitCode = 1; store.close(); });
server.listen(config.port, config.host, () => {
  const address = server.address();
  console.log(`Alice backend listening on ${config.host}:${typeof address === "object" && address ? address.port : config.port}`);
  console.log(process.env.ALICE_ACCESS_TOKEN ? "Access token supplied by environment." : `Access token file: ${tokenPath}`);
});
let stopping = false;
const stop = () => {
  if (stopping) return;
  stopping = true;
  void service.stop().then(() => {
    server.closeAllConnections();
    server.close(() => { store.close(); });
  });
};
process.on("SIGINT", stop);
process.on("SIGTERM", stop);
