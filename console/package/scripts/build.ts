// Builds dist/: the theme generated from the tokens, the stylesheet compiled by Tailwind, the
// TypeScript compiler's output, and the face the stylesheet names. No host needs Tailwind: what it
// imports is the compiled CSS.
import { execFileSync } from "node:child_process";
import { copyFileSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { tokens, toCss } from "../src/ui/tokens.ts";

const root = fileURLToPath(new URL("..", import.meta.url));
const require = createRequire(import.meta.url);

/** The theme, from the tokens: the stylesheet imports it. */
export function writeTheme(): void {
  writeFileSync(join(root, "src/theme.generated.css"), toCss(tokens));
}

/** The stylesheet, compiled; `watch` keeps the compiler running. */
export function tailwind(watch = false): void {
  const cli = join(dirname(require.resolve("@tailwindcss/cli/package.json")), "dist/index.mjs");
  const args = [cli, "-i", "src/styles.css", "-o", "dist/styles.css", watch ? "--watch" : "--minify"];
  execFileSync(process.execPath, args, { cwd: root, stdio: "inherit" });
}

/** The two subsets of the face, beside the stylesheet that names them by a relative url(). */
export function copyFonts(): void {
  const files = join(dirname(require.resolve("@fontsource-variable/inter/package.json")), "files");
  mkdirSync(join(root, "dist/fonts"), { recursive: true });
  for (const f of ["inter-latin-wght-normal.woff2", "inter-latin-ext-wght-normal.woff2"]) copyFileSync(join(files, f), join(root, "dist/fonts", f));
}

if (import.meta.url === `file://${process.argv[1]}`) {
  rmSync(join(root, "dist"), { recursive: true, force: true });
  writeTheme();
  execFileSync("npx", ["tsc", "-p", "tsconfig.build.json"], { cwd: root, stdio: "inherit" });
  tailwind();
  copyFileSync(join(root, "src/theme.generated.css"), join(root, "dist/theme.css"));
  copyFonts();
}
