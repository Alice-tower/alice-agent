// Build local Pi sources; supply generated model data from the matching package when missing.
import { cp, mkdir, readFile, access } from "node:fs/promises";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import { x } from "tar";

const exec = promisify(execFile);
const root = fileURLToPath(new URL("../", import.meta.url));
const pi = resolve(root, "vendor/pi");
const ai = resolve(pi, "packages/ai");
const tsc = resolve(root, "node_modules/typescript/bin/tsc");
const runNode = (args, cwd = root) => exec(process.execPath, args, { cwd, maxBuffer: 4 * 1024 * 1024 });

const data = resolve(ai, "src/providers/data");
try {
  await access(resolve(data, ".manifest.json"));
} catch {
  const { version } = JSON.parse(await readFile(resolve(ai, "package.json"), "utf8"));
  const staging = resolve(root, "tmp/pi-model-data", version);
  await mkdir(staging, { recursive: true });
  console.log(`Restoring Pi ${version} model data from its published package...`);
  // npm_execpath is supplied by npm on all supported development platforms.
  const npm = process.env.npm_execpath;
  if (!npm) throw new Error("Run this script with npm run build:pi.");
  const { stdout } = await runNode([
    npm, "pack", `@earendil-works/pi-ai@${version}`, "--ignore-scripts", "--json",
    "--pack-destination", staging, "--cache", resolve(root, "tmp/npm-cache"),
  ]);
  const [{ filename }] = JSON.parse(stdout);
  await x({
    file: resolve(staging, filename),
    cwd: staging,
    filter: (path) => path.startsWith("package/dist/providers/data/"),
  });
  await cp(resolve(staging, "package/dist/providers/data"), data, { recursive: true });
}

// The upstream validator checks catalog identity and each data file's manifest hash.
await runNode([resolve(ai, "scripts/check-model-data.ts")]);
for (const name of ["telemetry", "schema", "ai", "agent"]) {
  console.log(`Building local Pi ${name}...`);
  try {
    await runNode([tsc, "-p", resolve(pi, "packages", name, "tsconfig.build.json")]);
  } catch (error) {
    throw new Error(error.stdout || error.stderr || "Pi build failed", { cause: error });
  }
}
await cp(data, resolve(ai, "dist/providers/data"), { recursive: true });
