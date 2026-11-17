import { build } from "esbuild";
import { mkdir, copyFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";

const root = fileURLToPath(new URL("../", import.meta.url));
const outdir = resolve(root, "dist/desktop");
await mkdir(outdir, { recursive: true });
await Promise.all([
  build({ entryPoints: [resolve(root, "src/desktop/main.ts")], outfile: resolve(outdir, "main.cjs"), bundle: true, platform: "node", format: "cjs", target: "node22", external: ["electron"] }),
  build({ entryPoints: [resolve(root, "src/desktop/preload.ts")], outfile: resolve(outdir, "preload.cjs"), bundle: true, platform: "node", format: "cjs", target: "node22", external: ["electron"] }),
  build({ entryPoints: [resolve(root, "src/desktop/renderer.ts")], outfile: resolve(outdir, "renderer.js"), bundle: true, platform: "browser", target: "chrome140" }),
  ...["index.html", "style.css"].map(name => copyFile(resolve(root, "src/desktop", name), resolve(outdir, name))),
]);
console.log("Electron desktop built.");
