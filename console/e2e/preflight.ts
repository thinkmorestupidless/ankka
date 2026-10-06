/**
 * Before the compose target runs anything: the three things it does not start must be answering. A
 * missing one used to surface as every test failing on its first navigation, which reads like dozens
 * of regressions; this names what is not running and how to start it, once.
 */
export default async function preflight() {
  if (process.env.CONSOLE_E2E_TARGET !== "compose") return;
  const issuer = process.env.CONSOLE_E2E_ISSUER ?? "http://localhost:8081/realms/ankka";
  const controlPlane = process.env.CONSOLE_E2E_CONTROL_PLANE_URL ?? "http://localhost:9000";
  const consoleUrl = process.env.CONSOLE_E2E_URL ?? "http://localhost:3000";
  const needs = [
    { what: "Keycloak", url: `${issuer}/.well-known/openid-configuration`, start: "docker compose up -d   (repository root)" },
    { what: "the control plane", url: `${controlPlane}/auth`, start: "ANKKA_AUTH_ISSUER=http://localhost:8081/realms/ankka sbt controlPlane/run" },
    { what: "the console", url: consoleUrl, start: "cd console && npm run dev" },
  ];
  const missing: string[] = [];
  for (const n of needs) {
    try {
      await fetch(n.url, { redirect: "manual", signal: AbortSignal.timeout(5_000) });
    } catch (e) {
      missing.push(`  ${n.what} is not answering at ${n.url} (${e instanceof Error ? e.message : String(e)})\n    start it: ${n.start}`);
    }
  }
  if (missing.length > 0) throw new Error(`the compose target needs a running stack:\n${missing.join("\n")}`);
}
