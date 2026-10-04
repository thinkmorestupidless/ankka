// The shopping cart's interface's server: one program that listens on PORT, in development and in
// production.
//
// It serves the app, and answers one route of its own, /summary?cart=<id>, by calling the service
// "cart" at the calling address the platform gives it (ANKKA_SERVICES_URL) for the cart's total.
// Requests under /api/cart never reach it: the platform's proxy passes those to the cart. Node runs
// this file directly.
import http from "node:http";
import { readFile, stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

export interface ServerOptions {
  /** Where to call other services: the platform's calling address. */
  servicesUrl: string;
  /** The built app, in production. */
  dist?: string;
  /** Vite's middleware, in development: it serves the app from its sources. */
  middleware?: (req: http.IncomingMessage, res: http.ServerResponse, next: () => void) => void;
}

const types: Record<string, string> = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript",
  ".css": "text/css",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
  ".json": "application/json",
  ".woff2": "font/woff2",
};

function send(res: http.ServerResponse, status: number, type: string, body: string): void {
  res.writeHead(status, { "Content-Type": type });
  res.end(body);
}

// docs:start calling-a-service
/** A cart's total, read the way any service is called: by name, at the calling address. */
async function summary(servicesUrl: string, cart: string, res: http.ServerResponse): Promise<void> {
  try {
    const answer = await fetch(`${servicesUrl}/cart/carts/${encodeURIComponent(cart)}/total`);
    send(res, 200, "application/json", JSON.stringify({ status: answer.status, body: await answer.text() }));
  } catch (error) {
    send(res, 502, "application/json", JSON.stringify({ error: `the cart did not answer: ${String(error)}` }));
  }
}
// docs:end calling-a-service

async function isFile(file: string): Promise<boolean> {
  try {
    return (await stat(file)).isFile();
  } catch {
    return false;
  }
}

/**
 * The built app. A path that names a file is that file; one that looks like a file and is not there
 * is 404; anything else is the app's own route, so it is the index page.
 */
async function serveBuilt(dist: string, pathname: string, res: http.ServerResponse): Promise<void> {
  const file = path.join(dist, decodeURIComponent(pathname));
  if (!file.startsWith(dist)) return send(res, 404, "text/plain", "not found");
  if (await isFile(file)) {
    const immutable = file.startsWith(path.join(dist, "assets") + path.sep);
    res.writeHead(200, {
      "Content-Type": types[path.extname(file)] ?? "application/octet-stream",
      "Cache-Control": immutable ? "public, max-age=31536000, immutable" : "no-cache",
    });
    res.end(await readFile(file));
    return;
  }
  if (path.extname(pathname) !== "") return send(res, 404, "text/plain", "not found");
  res.writeHead(200, { "Content-Type": types[".html"], "Cache-Control": "no-cache" });
  res.end(await readFile(path.join(dist, "index.html")));
}

export function createAppServer(options: ServerOptions): http.Server {
  return http.createServer((req, res) => {
    const url = new URL(req.url ?? "/", "http://app");
    if (req.method === "GET" && url.pathname === "/summary") {
      void summary(options.servicesUrl, url.searchParams.get("cart") ?? "demo", res);
    } else if (options.dist) {
      void serveBuilt(options.dist, url.pathname, res);
    } else if (options.middleware) {
      options.middleware(req, res, () => send(res, 404, "text/plain", "not found"));
    } else {
      send(res, 404, "text/plain", "not found");
    }
  });
}

async function main(): Promise<void> {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
  const port = Number(process.env.PORT ?? 3000);
  const servicesUrl = process.env.ANKKA_SERVICES_URL ?? "http://127.0.0.1:7630";
  const production = process.env.NODE_ENV === "production";
  let middleware: ServerOptions["middleware"];
  if (!production) {
    // Development only, so the production image needs no Vite.
    const { createServer } = await import("vite");
    const vite = await createServer({ root, server: { middlewareMode: true }, appType: "spa" });
    middleware = vite.middlewares;
  }
  const server = createAppServer({ servicesUrl, dist: production ? path.join(root, "dist") : undefined, middleware });
  server.listen(port, () => console.log(`listening on ${port}`));
  // The platform stops a pod with SIGTERM after it has left the service's address: finish what is in
  // flight, then go.
  process.on("SIGTERM", () => {
    server.close(() => process.exit(0));
    server.closeIdleConnections();
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
