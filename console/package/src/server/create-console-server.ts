/**
 * The console's process: the app on one port and readiness on another.
 *
 * In a cluster (`ANKKA_CONSOLE_TLS_DIR` set) the app port is TLS with the certificate cert-manager
 * issued for the console, re-read when it is renewed, and it serves only a client presenting a
 * certificate from the same authority that names the gateway, `ankka://gateway`: nothing reaches the
 * console except through the installation's front door. Readiness is plain HTTP on its own port,
 * because the kubelet holds no certificate, and says yes only once the certificate is loaded, the
 * app port is bound and the control plane has answered. Locally it is plain HTTP on one port.
 */
import { createServer as createHttpServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { createServer as createHttpsServer } from "node:https";
import { createReadStream, statSync } from "node:fs";
import { extname, normalize, resolve, sep } from "node:path";
import type { TLSSocket } from "node:tls";
import { createSecureContext } from "node:tls";
import { fileURLToPath } from "node:url";
import { createRequestListener } from "@react-router/node";
import type { ServerBuild } from "react-router";
import { ControlPlaneClient } from "../client/control-plane.ts";
import { closeAllStreams } from "../stream/registry.ts";
import { RotatingFiles, tlsFetch } from "./transport.ts";

export const GatewayUri = "ankka://gateway";

export interface ConsoleServerOptions {
  build?: ServerBuild | (() => Promise<ServerBuild>);
  /** In place of `build`: any request listener. The server's own tests use it. */
  requestListener?: (req: IncomingMessage, res: ServerResponse) => void;
  /** The client build: `build/client/` beside the server build. */
  clientDir: string | URL;
  env?: Record<string, string | undefined>;
  /** How long to wait for the control plane at startup before refusing to start. */
  startupTimeoutMs?: number;
}

export interface ConsoleServer {
  app: Server;
  probe?: Server;
  url: string;
  ready(): boolean;
  close(): Promise<void>;
}

const types: Record<string, string> = {
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
  ".json": "application/json",
  ".woff2": "font/woff2",
  ".txt": "text/plain; charset=utf-8",
};

/** Serves a file from the client build, or answers `false` for the app to handle the request. */
function staticFile(root: string, req: IncomingMessage, res: ServerResponse): boolean {
  if (req.method !== "GET" && req.method !== "HEAD") return false;
  const path = decodeURIComponent(new URL(req.url ?? "/", "http://x").pathname);
  if (path === "/" || path.endsWith("/")) return false;
  const file = resolve(root, "." + normalize(path));
  if (!file.startsWith(root + sep)) return false;
  let size: number;
  try {
    const stat = statSync(file);
    if (!stat.isFile()) return false;
    size = stat.size;
  } catch {
    return false;
  }
  res.writeHead(200, {
    "content-type": types[extname(file)] ?? "application/octet-stream",
    "content-length": size,
    // Fingerprinted by the build: they never change under the same name.
    "cache-control": path.startsWith("/assets/") ? "public, max-age=31536000, immutable" : "public, max-age=300",
  });
  if (req.method === "HEAD") res.end();
  else createReadStream(file).pipe(res);
  return true;
}

function peerNamesGateway(req: IncomingMessage): boolean {
  const socket = req.socket as TLSSocket;
  if (typeof socket.getPeerCertificate !== "function") return false;
  const names = socket.getPeerCertificate()?.subjectaltname ?? "";
  return names.split(",").map((n) => n.trim()).includes(`URI:${GatewayUri}`);
}

async function waitForControlPlane(url: string, fetchImpl: ReturnType<typeof tlsFetch>, timeoutMs: number): Promise<void> {
  const client = new ControlPlaneClient({ baseUrl: url, bearer: async () => null, transport: fetchImpl });
  const deadline = Date.now() + timeoutMs;
  let last: unknown;
  while (Date.now() < deadline) {
    try {
      await client.authDiscovery();
      return;
    } catch (e) {
      last = e;
      await new Promise((r) => setTimeout(r, 1000));
    }
  }
  throw new Error(`the control plane did not answer GET ${url}/auth: ${last instanceof Error ? last.message : String(last)}`);
}

export async function createConsoleServer(options: ConsoleServerOptions): Promise<ConsoleServer> {
  const env = options.env ?? process.env;
  const tlsDir = env.ANKKA_CONSOLE_TLS_DIR?.trim();
  const port = Number(env.ANKKA_CONSOLE_PORT ?? (tlsDir ? 9000 : 3000));
  const probePort = env.ANKKA_CONSOLE_PROBE_PORT ? Number(env.ANKKA_CONSOLE_PROBE_PORT) : undefined;
  const host = env.ANKKA_CONSOLE_HOST ?? (tlsDir ? "0.0.0.0" : "127.0.0.1");
  const clientRoot = resolve(typeof options.clientDir === "string" ? options.clientDir : fileURLToPath(options.clientDir));
  const cpUrl = env.ANKKA_CONSOLE_CONTROL_PLANE_URL ?? "http://localhost:9000";
  const files = tlsDir ? { ca: `${tlsDir}/ca.crt`, cert: `${tlsDir}/tls.crt`, key: `${tlsDir}/tls.key` } : undefined;
  if (tlsDir && !env.ANKKA_CONSOLE_AUTHORITY?.trim() && !options.requestListener) {
    throw new Error("ANKKA_CONSOLE_AUTHORITY is not set: in a cluster the console must be told its own address");
  }

  let bound = false;
  let controlPlaneAnswered = false;
  let closing = false;

  if (!options.build && !options.requestListener) throw new Error("createConsoleServer needs the host's server build");
  const listener =
    options.requestListener ??
    createRequestListener({ build: options.build!, mode: env.NODE_ENV === "development" ? "development" : "production" });
  const handle = (req: IncomingMessage, res: ServerResponse) => {
    if (tlsDir && !peerNamesGateway(req)) {
      res.writeHead(403, { "content-type": "text/plain" }).end("only the installation's gateway may call the console\n");
      return;
    }
    if (staticFile(clientRoot, req, res)) return;
    void listener(req, res);
  };

  let app: Server;
  let rotation: NodeJS.Timeout | undefined;
  if (files) {
    const rotating = new RotatingFiles(files, 30_000);
    const contextOf = () => {
      const m = rotating.current();
      return { version: m.version, context: createSecureContext({ key: m.key, cert: m.cert, ca: m.ca }) };
    };
    let current = contextOf();
    const server = createHttpsServer({
      key: rotating.current().key,
      cert: rotating.current().cert,
      ca: rotating.current().ca,
      requestCert: true,
      rejectUnauthorized: true,
      minVersion: "TLSv1.2",
    }, handle);
    rotation = setInterval(() => {
      const next = contextOf();
      if (next.version !== current.version) {
        current = next;
        const m = rotating.current();
        server.setSecureContext({ key: m.key, cert: m.cert, ca: m.ca, requestCert: true, rejectUnauthorized: true } as never);
      }
    }, 30_000);
    rotation.unref();
    app = server;
  } else {
    app = createHttpServer(handle);
  }
  // Streams send keepalives well inside this; a request that says nothing for longer is gone.
  app.keepAliveTimeout = 65_000;
  app.headersTimeout = 66_000;

  await waitForControlPlane(cpUrl, tlsFetch(files), options.startupTimeoutMs ?? 60_000);
  controlPlaneAnswered = true;

  await new Promise<void>((resolveListen, reject) => {
    app.once("error", reject);
    app.listen(port, host, () => {
      bound = true;
      resolveListen();
    });
  });

  const ready = () => bound && controlPlaneAnswered && !closing;

  let probe: Server | undefined;
  if (probePort !== undefined) {
    probe = createHttpServer((req, res) => {
      if (req.url === "/ready" || req.url === "/healthz") {
        const ok = req.url === "/healthz" ? !closing : ready();
        res.writeHead(ok ? 200 : 503, { "content-type": "text/plain" }).end(ok ? "ok\n" : "not ready\n");
      } else {
        res.writeHead(404).end();
      }
    });
    await new Promise<void>((r) => probe!.listen(probePort, host, r));
  }

  const address = app.address();
  const actualPort = typeof address === "object" && address ? address.port : port;
  const url = `${tlsDir ? "https" : "http"}://${host === "0.0.0.0" ? "localhost" : host}:${actualPort}`;

  const close = async () => {
    if (closing) return;
    closing = true;
    if (rotation) clearInterval(rotation);
    // Streams are told first, so browsers reconnect to an instance that is staying.
    closeAllStreams();
    const closed = new Promise<void>((r) => app.close(() => r()));
    app.closeIdleConnections();
    const deadline = new Promise<void>((r) => setTimeout(() => (app.closeAllConnections(), r()), 10_000).unref());
    await Promise.race([closed, deadline]);
    if (probe) await new Promise<void>((r) => probe!.close(() => r()));
  };

  return { app, probe, url, ready, close };
}

/** Runs the server until SIGTERM or SIGINT, then drains it and exits. */
export async function runConsoleServer(options: ConsoleServerOptions): Promise<void> {
  const server = await createConsoleServer(options);
  process.stdout.write(JSON.stringify({ at: new Date().toISOString(), event: "listening", url: server.url }) + "\n");
  const stop = async (signal: string) => {
    process.stdout.write(JSON.stringify({ at: new Date().toISOString(), event: "stopping", signal }) + "\n");
    await server.close();
    process.exit(0);
  };
  process.once("SIGTERM", () => void stop("SIGTERM"));
  process.once("SIGINT", () => void stop("SIGINT"));
}
