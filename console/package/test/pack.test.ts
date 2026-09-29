/**
 * The package as npm would receive it: built, packed, installed into an empty directory with only
 * its declared dependencies, and every entry point imported. Slow, so it runs with `CONSOLE_PACK=1`,
 * which the CI job sets.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const packageDir = fileURLToPath(new URL("..", import.meta.url));

test("pack: the published tarball installs alone and every entry point imports", { skip: process.env.CONSOLE_PACK !== "1" }, () => {
  const out = mkdtempSync(join(tmpdir(), "ankka-console-pack-"));
  try {
    execFileSync(process.execPath, ["scripts/build.ts"], { cwd: packageDir, stdio: "pipe" });
    execFileSync("npm", ["pack", "--pack-destination", out], { cwd: packageDir, stdio: "pipe" });
    const tarball = readdirSync(out).find((f) => f.endsWith(".tgz"))!;
    const app = join(out, "app");
    execFileSync("mkdir", ["-p", app]);
    writeFileSync(join(app, "package.json"), JSON.stringify({ name: "smoke", private: true, type: "module" }));
    execFileSync("npm", ["install", "--no-audit", "--no-fund", join(out, tarball), "react-router@^8.4.0", "@react-router/node@^8.4.0", "react@^19", "react-dom@^19"], {
      cwd: app,
      stdio: "pipe",
    });
    const script = [
      'const main = await import("ankka-console");',
      'const server = await import("ankka-console/server");',
      'const client = await import("ankka-console/client");',
      'const testing = await import("ankka-console/testing");',
      'if (typeof main.ConsoleProvider !== "function") throw new Error("ConsoleProvider");',
      'if (typeof server.consoleRoutes !== "function" || server.consoleRoutes().length < 10) throw new Error("consoleRoutes");',
      'if (!server.consoleRoutes()[0].file.endsWith(".js")) throw new Error("routes must point at built files");',
      'if (typeof client.ControlPlaneClient !== "function") throw new Error("ControlPlaneClient");',
      'if (typeof testing.fakeControlPlane !== "function") throw new Error("fakeControlPlane");',
      'console.log("ok");',
    ].join("\n");
    const result = execFileSync(process.execPath, ["--input-type=module", "-e", script], { cwd: app, encoding: "utf8" });
    assert.match(result, /ok/);
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
});
