// The package while it is being worked on: the theme written once, then the stylesheet and the
// TypeScript recompiled as they change. Change tokens.ts and restart this.
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { copyFonts, tailwind, writeTheme } from "./build.ts";

const root = fileURLToPath(new URL("..", import.meta.url));
writeTheme();
copyFonts();
spawn("npx", ["tsc", "-p", "tsconfig.build.json", "--watch", "--preserveWatchOutput"], { cwd: root, stdio: "inherit" });
tailwind(true);
