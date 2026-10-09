/**
 * SC-002: every route of the control plane's API reference, bar the few that are not a person's to
 * call (`notThroughTheConsole`), was exercised through the console by the suite. Runs after the whole suite, reading the
 * routes each worker's fake control plane recorded; only a full run against the fakes can say, so it
 * checks only when `CONSOLE_E2E_PARITY=1`, which the workspace's `e2e` script sets.
 */
import { readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

/**
 * Routes that are not a person's to call through the console: the discovery it reads at startup,
 * and what a registered machine signs in with (feature 040) — the token route, the key set and the
 * discovery document a broker or a grantee's service reads — with the platform administrator's
 * rotation of the keys those tokens are signed with.
 */
const notThroughTheConsole = new Set([
  "GET /auth",
  "POST /oauth/token",
  "GET /.well-known/jwks.json",
  "GET /.well-known/openid-configuration",
  "POST /platform/machine-keys/rotate",
]);

export default async function parity() {
  if (process.env.CONSOLE_E2E_PARITY !== "1" || process.env.CONSOLE_E2E_TARGET === "compose") return;
  const here = fileURLToPath(new URL(".", import.meta.url));
  const reference = readFileSync(`${here}../../docs/reference/control-plane-api.md`, "utf8");
  const block = reference.slice(reference.indexOf("<!-- generated:start control-plane-routes -->"), reference.indexOf("<!-- generated:end control-plane-routes -->"));
  const routes = [...block.matchAll(/^\| `(\w+)` \| `([^`]+)` \|/gm)].map((m) => `${m[1]} ${m[2]}`).filter((r) => !notThroughTheConsole.has(r));
  const visited = new Set<string>();
  for (const f of readdirSync(`${here}test-results`).filter((f) => f.startsWith("visited-") && f.endsWith(".json"))) {
    for (const r of JSON.parse(readFileSync(`${here}test-results/${f}`, "utf8")) as string[]) visited.add(r);
  }
  const missing = routes.filter((r) => !visited.has(r));
  if (routes.length === 0) throw new Error("no routes found in docs/reference/control-plane-api.md's generated block");
  if (missing.length > 0) throw new Error(`the console's suite never exercised these control plane routes:\n  ${missing.join("\n  ")}`);
  process.stdout.write(`parity: all ${routes.length} control plane routes exercised through the console\n`);
}
