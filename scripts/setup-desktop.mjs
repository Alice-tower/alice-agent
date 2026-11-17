import { spawn } from "node:child_process";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { mkdir } from "node:fs/promises";

const root = fileURLToPath(new URL("../", import.meta.url));
const temp = resolve(root, "tmp/electron-download");
await mkdir(temp, { recursive: true });
const child = spawn(process.execPath, [resolve(root, "node_modules/electron/install.js")], {
  cwd: root,
  env: { ...process.env, ...(process.env.HTTPS_PROXY || process.env.HTTP_PROXY ? { ELECTRON_GET_USE_PROXY: "1" } : {}), electron_config_cache: resolve(root, "tmp/electron-cache"), TMPDIR: temp, TEMP: temp, TMP: temp },
  stdio: "inherit",
});
child.on("error", error => { console.error(error.message); process.exitCode = 1; });
child.on("exit", code => { process.exitCode = code ?? 1; });
