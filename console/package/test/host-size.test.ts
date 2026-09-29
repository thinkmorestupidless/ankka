/**
 * SC-009: the installation's console is the package plus a thin host. Everything a second host
 * would otherwise copy belongs in the package, so the host's own source stays small.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const hostDir = fileURLToPath(new URL("../../host/", import.meta.url));

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return name === "+types" ? [] : sources(path);
    return /\.(ts|tsx)$/.test(name) ? [path] : [];
  });
}

const counted = (file: string) =>
  readFileSync(file, "utf8")
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l !== "" && !l.startsWith("//") && !l.startsWith("*") && !l.startsWith("/*")).length;

test("the host is under 500 lines of its own source", () => {
  const files = [...sources(join(hostDir, "app")), join(hostDir, "server.ts")];
  const lines = files.reduce((n, f) => n + counted(f), 0);
  process.stdout.write(`host: ${lines} lines in ${files.length} files\n`);
  assert.ok(lines < 500, `the host has ${lines} lines of its own`);
});
