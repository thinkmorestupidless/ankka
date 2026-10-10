/**
 * SC-002: every route of the control plane's API reference, bar the discovery the console reads at
 * startup and the routes listed in `notThroughTheConsole` with their reasons, was exercised through the
 * console by the suite. Runs after the whole suite, reading the
 * routes each worker's fake control plane recorded; only a full run against the fakes can say, so it
 * checks only when `CONSOLE_E2E_PARITY=1`, which the workspace's `e2e` script sets.
 */
import { readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

/**
 * Routes the console does not offer, each with why. A route belongs here only when a person has no
 * reason to call it from the console, or when showing it there is an open decision, not an oversight.
 */
const notThroughTheConsole: Record<string, string> = {
  "POST /secret-reads": "a service writes the record of its own reads, by its certificate; never a person",
  "GET /projects/{projectId}/secret-reads": "the record of reads is listed with the CLI; showing it in the console is an open question",
};

export default async function parity() {
  if (process.env.CONSOLE_E2E_PARITY !== "1" || process.env.CONSOLE_E2E_TARGET === "compose") return;
  const here = fileURLToPath(new URL(".", import.meta.url));
  const reference = readFileSync(`${here}../../docs/reference/control-plane-api.md`, "utf8");
  const block = reference.slice(reference.indexOf("<!-- generated:start control-plane-routes -->"), reference.indexOf("<!-- generated:end control-plane-routes -->"));
  const routes = [...block.matchAll(/^\| `(\w+)` \| `([^`]+)` \|/gm)].map((m) => `${m[1]} ${m[2]}`).filter((r) => r !== "GET /auth" && !(r in notThroughTheConsole));
  const visited = new Set<string>();
  for (const f of readdirSync(`${here}test-results`).filter((f) => f.startsWith("visited-") && f.endsWith(".json"))) {
    for (const r of JSON.parse(readFileSync(`${here}test-results/${f}`, "utf8")) as string[]) visited.add(r);
  }
  const missing = routes.filter((r) => !visited.has(r));
  if (routes.length === 0) throw new Error("no routes found in docs/reference/control-plane-api.md's generated block");
  if (missing.length > 0) throw new Error(`the console's suite never exercised these control plane routes:\n  ${missing.join("\n  ")}`);
  process.stdout.write(`parity: all ${routes.length} control plane routes exercised through the console\n`);
}
