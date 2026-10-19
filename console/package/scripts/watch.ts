// The package while it is being worked on: the theme written once, then the stylesheet and the
// TypeScript recompiled as they change. Change tokens.ts and restart this.
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { copyFonts, copyMark, tailwind, writeTheme } from "./build.ts";

const root = fileURLToPath(new URL("..", import.meta.url));
writeTheme();
copyFonts();
copyMark();
spawn("npx", ["tsc", "-p", "tsconfig.build.json", "--watch", "--preserveWatchOutput"], { cwd: root, stdio: "inherit" });
tailwind(true);
