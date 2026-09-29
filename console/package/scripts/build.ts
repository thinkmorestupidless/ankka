// Builds dist/: the TypeScript compiler's output plus the stylesheet, which tsc does not copy.
import { execFileSync } from "node:child_process";
import { copyFileSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("..", import.meta.url));
rmSync(`${root}/dist`, { recursive: true, force: true });
execFileSync("npx", ["tsc", "-p", "tsconfig.build.json"], { cwd: root, stdio: "inherit" });
copyFileSync(`${root}/src/styles.css`, `${root}/dist/styles.css`);
