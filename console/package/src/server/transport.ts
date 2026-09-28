/**
 * A `fetch` for the console's outbound calls, over `node:https` so it can trust one authority and
 * present one certificate — the control plane requires the console's own certificate in a cluster,
 * and the identity provider's in-cluster address is verified against the installation's service
 * authority. The certificate files are re-read when cert-manager rewrites them.
 */
import { request as httpRequest } from "node:http";
import { request as httpsRequest, type RequestOptions } from "node:https";
import { readFileSync, statSync } from "node:fs";
import { Readable } from "node:stream";

export interface TlsFiles {
  /** The authority to trust; none means the system's roots. */
  ca?: string;
  /** The certificate and key to present as a client. */
  cert?: string;
  key?: string;
}

/** Reads a set of PEM files, and reads them again when any has changed since, at most once every `pollMs`. */
export class RotatingFiles {
  readonly #files: TlsFiles;
  readonly #pollMs: number;
  #checkedAt = 0;
  #stamp = "";
  #loaded: { ca?: Buffer; cert?: Buffer; key?: Buffer } = {};
  #version = 0;

  constructor(files: TlsFiles, pollMs = 30_000) {
    this.#files = files;
    this.#pollMs = pollMs;
  }

  /** The current contents, and a number that changes whenever they do. */
  current(): { ca?: Buffer; cert?: Buffer; key?: Buffer; version: number } {
    const now = Date.now();
    if (now - this.#checkedAt >= this.#pollMs || this.#version === 0) {
      this.#checkedAt = now;
      const paths = [this.#files.ca, this.#files.cert, this.#files.key].filter((p): p is string => !!p);
      const stamp = paths.map((p) => `${p}:${statSync(p).mtimeMs}`).join("|");
      if (stamp !== this.#stamp) {
        this.#loaded = {
          ca: this.#files.ca ? readFileSync(this.#files.ca) : undefined,
          cert: this.#files.cert ? readFileSync(this.#files.cert) : undefined,
          key: this.#files.key ? readFileSync(this.#files.key) : undefined,
        };
        this.#stamp = stamp;
        this.#version += 1;
      }
    }
    return { ...this.#loaded, version: this.#version };
  }
}

export type FetchLike = (url: string | URL, init?: RequestInit) => Promise<Response>;

/**
 * `fetch`, over TLS material that is re-read on rotation. With no files it is the platform's own
 * `fetch`. Bodies are buffered on the way out; every call this is used for is small JSON or a form.
 */
export function tlsFetch(files: TlsFiles | undefined, pollMs?: number): FetchLike {
  if (!files || (!files.ca && !files.cert)) return (url, init) => fetch(url, init);
  const rotating = new RotatingFiles(files, pollMs);
  return async (input, init = {}) => {
    const url = new URL(String(input));
    const material = rotating.current();
    const headers = new Headers(init.headers);
    let body: Buffer | undefined;
    if (init.body !== undefined && init.body !== null) {
      body = Buffer.from(await new Response(init.body as BodyInit).arrayBuffer());
      headers.set("content-length", String(body.length));
    }
    const options: RequestOptions = {
      method: init.method ?? "GET",
      host: url.hostname,
      port: url.port,
      path: url.pathname + url.search,
      headers: Object.fromEntries(headers.entries()),
      ca: material.ca,
      cert: material.cert,
      key: material.key,
      servername: url.hostname,
      signal: init.signal ?? undefined,
    };
    const send = url.protocol === "https:" ? httpsRequest : httpRequest;
    return new Promise<Response>((resolve, reject) => {
      const req = send(options, (res) => {
        const out = new Headers();
        for (const [k, v] of Object.entries(res.headers)) {
          if (Array.isArray(v)) v.forEach((x) => out.append(k, x));
          else if (v !== undefined) out.set(k, v);
        }
        const status = res.statusCode ?? 502;
        const nullBody = status === 204 || status === 304 || options.method === "HEAD";
        resolve(
          new Response(nullBody ? null : (Readable.toWeb(res) as ReadableStream), {
            status,
            statusText: res.statusMessage,
            headers: out,
          }),
        );
      });
      req.on("error", reject);
      if (body) req.end(body);
      else req.end();
    });
  };
}
