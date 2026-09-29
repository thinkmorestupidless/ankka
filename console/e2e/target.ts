/**
 * What the browser suite runs against.
 *
 * `fake` (the default): the fake issuer, the fake control plane and the built host, started in this
 * worker on ephemeral ports, with a round-robin proxy in front of two host instances so a test can
 * restart one under a signed-in session. Tests steer the fakes directly.
 *
 * `compose`: the compose stack's Keycloak on :8081, a control plane on :9000 and the console on
 * :3000, all started by the developer (`just test-console-compose`). Tests that need to steer a fake
 * skip themselves there.
 */
import { spawn, type ChildProcess } from "node:child_process";
import { createServer, request as httpRequest, type Server } from "node:http";
import { createServer as createNetServer, type AddressInfo } from "node:net";
import { fileURLToPath } from "node:url";
import { fakeControlPlane, fakeIssuer, type FakeControlPlane, type FakeIssuer } from "ankka-console/testing";

export interface Target {
  kind: "fake" | "compose";
  /** The console as the browser reaches it. */
  url: string;
  issuer?: FakeIssuer;
  controlPlane?: FakeControlPlane;
  /** For the compose target: the control plane's address, for checks outside the browser. */
  controlPlaneUrl: string;
  /** The account a test user signs in as: on compose, every one of them is the development user. */
  userFor(user: string): string;
  passwordFor(user: string): string;
  restartInstance(index: number): Promise<void>;
  /** Lines each host instance wrote to stdout, parsed: the request log. */
  requestLog: Record<string, unknown>[];
  close(): Promise<void>;
}

const hostDir = fileURLToPath(new URL("../host/", import.meta.url));

async function freePort(): Promise<number> {
  const s = createNetServer();
  await new Promise<void>((r) => s.listen(0, "127.0.0.1", r));
  const port = (s.address() as AddressInfo).port;
  await new Promise<void>((r) => s.close(() => r()));
  return port;
}

async function waitFor(url: string, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(url);
      await res.body?.cancel();
      if (res.status < 500) return;
    } catch {
      // not listening yet
    }
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error(`nothing answered ${url} within ${timeoutMs}ms`);
}

export async function startTarget(): Promise<Target> {
  if (process.env.CONSOLE_E2E_TARGET === "compose") return composeTarget();
  return fakeTarget();
}

function composeTarget(): Target {
  return {
    kind: "compose",
    url: process.env.CONSOLE_E2E_URL ?? "http://localhost:3000",
    controlPlaneUrl: process.env.CONSOLE_E2E_CONTROL_PLANE_URL ?? "http://localhost:9000",
    userFor: () => "dev",
    passwordFor: () => "dev",
    restartInstance: async () => {
      throw new Error("the compose target has one console, run by the developer");
    },
    requestLog: [],
    close: async () => undefined,
  };
}

async function fakeTarget(): Promise<Target> {
  // Access tokens of 36 seconds, cached for 6 after the console's 30-second skew: short enough that a
  // test sees a session ended at the identity provider within seconds, and that refreshes happen
  // throughout the suite rather than never.
  const issuer = await fakeIssuer({ clientId: "ankka-console", clientSecret: "dev", accessTokenSeconds: 36 });
  const controlPlane = await fakeControlPlane({
    verify: (t) => issuer.verifyAccessToken(t) as never,
    issuer: issuer.issuer,
    signupUrl: "https://ankka.example/sign-up",
  });
  const proxyPort = await freePort();
  const publicUrl = `http://127.0.0.1:${proxyPort}`;
  const requestLog: Record<string, unknown>[] = [];

  const ports = [await freePort(), await freePort()];
  const children: (ChildProcess | undefined)[] = [];
  const start = async (i: number) => {
    const child = spawn(process.execPath, ["server.ts"], {
      cwd: hostDir,
      env: {
        ...process.env,
        NODE_ENV: "production",
        ANKKA_CONSOLE_PORT: String(ports[i]),
        ANKKA_CONSOLE_AUTHORITY: `127.0.0.1:${proxyPort}`,
        ANKKA_CONSOLE_CONTROL_PLANE_URL: controlPlane.url,
        ANKKA_CONSOLE_CLIENT_SECRET: "dev",
        ANKKA_CONSOLE_SESSION_SECRET: "e2e-session-secret",
        ANKKA_CONSOLE_ALLOW_INSECURE_ISSUER: "true",
      },
      stdio: ["ignore", "pipe", "inherit"],
    });
    let buffered = "";
    child.stdout!.on("data", (chunk: Buffer) => {
      buffered += chunk.toString("utf8");
      let nl: number;
      while ((nl = buffered.indexOf("\n")) >= 0) {
        const line = buffered.slice(0, nl);
        buffered = buffered.slice(nl + 1);
        try {
          requestLog.push(JSON.parse(line) as Record<string, unknown>);
        } catch {
          // not a log line
        }
      }
    });
    children[i] = child;
    await waitFor(`http://127.0.0.1:${ports[i]}/auth/sign-out`);
  };
  await Promise.all([start(0), start(1)]);

  // Round robin across whichever instances answer: a refused connection moves on to the next, which
  // is what the gateway does with an instance that has left the Service's endpoints.
  let next = 0;
  const proxy: Server = createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on("data", (c: Buffer) => chunks.push(c));
    req.on("end", () => {
      const body = Buffer.concat(chunks);
      const attempt = (tries: number) => {
        const port = ports[next++ % ports.length];
        const upstream = httpRequest({ host: "127.0.0.1", port, method: req.method, path: req.url, headers: req.headers }, (up) => {
          res.writeHead(up.statusCode ?? 502, up.headers);
          up.pipe(res);
        });
        upstream.on("error", () => (tries > 0 ? attempt(tries - 1) : res.writeHead(502).end()));
        upstream.end(body);
      };
      attempt(ports.length);
    });
  });
  await new Promise<void>((r) => proxy.listen(proxyPort, "127.0.0.1", r));

  const stop = (i: number) =>
    new Promise<void>((resolve) => {
      const child = children[i];
      if (!child || child.exitCode !== null) return resolve();
      child.once("exit", () => resolve());
      child.kill("SIGTERM");
    });

  return {
    kind: "fake",
    url: publicUrl,
    issuer,
    controlPlane,
    controlPlaneUrl: controlPlane.url,
    userFor: (user) => user,
    passwordFor: (user) => user,
    async restartInstance(i) {
      await stop(i);
      await start(i);
    },
    requestLog,
    async close() {
      await Promise.all([stop(0), stop(1)]);
      proxy.closeAllConnections();
      await new Promise<void>((r) => proxy.close(() => r()));
      await controlPlane.close();
      await issuer.close();
    },
  };
}
