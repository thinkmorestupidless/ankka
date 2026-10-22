// AWS Signature Version 4, as S3 and every store that speaks it reads it: a request signed in its
// headers, and a URL signed in its query string (feature 055). The runtime's `SigV4.scala` made the
// same choices, and the same vectors hold both: paths are encoded once, segment by segment, as S3
// expects; a put's payload is `UNSIGNED-PAYLOAD`, so a stream is sent once and never hashed.
//
// The signer works on a request's parts — its method, its endpoint, its path and query *before*
// encoding, the headers it signs and the hash of its payload — so the header form and the query form
// share one canonical request, and AWS's published examples test it with no client at all.

import { createHash, createHmac } from "node:crypto"

/** A key pair. Never printed whole: `toString` hides the secret. */
export class Credentials {
  readonly accessKey: string
  readonly #secretKey: string

  constructor(accessKey: string, secretKey: string) {
    this.accessKey = accessKey
    this.#secretKey = secretKey
    Object.freeze(this)
  }

  /** @internal */
  get _secretKey(): string {
    return this.#secretKey
  }

  toString(): string {
    return `Credentials(${this.accessKey}, ***)`
  }

  toJSON(): string {
    return this.toString()
  }
}

export const ALGORITHM = "AWS4-HMAC-SHA256"
/** The payload hash a put sends so its body is streamed once and never hashed. */
export const UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

const utf8 = new TextEncoder()

export function sha256Hex(data: Uint8Array | string): string {
  return createHash("sha256").update(typeof data === "string" ? utf8.encode(data) : data).digest("hex")
}

export function hmac(key: Uint8Array | string, data: string): Buffer {
  return createHmac("sha256", key).update(data, "utf8").digest()
}

/** The hash of an empty payload. */
export const EMPTY_PAYLOAD = sha256Hex(new Uint8Array())

/** RFC 3986 encoding as SigV4 states it: unreserved characters kept, everything else `%XX` of its UTF-8 bytes. */
export function encode(text: string, encodeSlash: boolean): string {
  let out = ""
  for (const b of utf8.encode(text)) {
    const c = String.fromCharCode(b)
    if ((b >= 0x41 && b <= 0x5a) || (b >= 0x61 && b <= 0x7a) || (b >= 0x30 && b <= 0x39) || c === "-" || c === "_" || c === "." || c === "~" || (c === "/" && !encodeSlash)) {
      out += c
    } else {
      out += "%" + b.toString(16).toUpperCase().padStart(2, "0")
    }
  }
  return out
}

/** One request: the endpoint's scheme and authority, a decoded path and decoded query pairs. */
export class SignedRequest {
  readonly method: string
  readonly endpoint: URL
  readonly path: string
  readonly query: readonly (readonly [string, string])[]

  constructor(method: string, endpoint: URL | string, path: string, query: readonly (readonly [string, string])[] = []) {
    this.method = method
    this.endpoint = typeof endpoint === "string" ? new URL(endpoint) : endpoint
    this.path = path
    this.query = query
    Object.freeze(this)
  }

  /** The `host` header's value: the host, with the port when the endpoint names one. */
  get host(): string {
    return this.endpoint.port === "" ? this.endpoint.hostname : `${this.endpoint.hostname}:${this.endpoint.port}`
  }

  get canonicalPath(): string {
    const raw = this.endpoint.pathname === "/" ? "" : this.endpoint.pathname
    const base = raw.endsWith("/") ? raw.slice(0, -1) : raw
    const p = this.path.startsWith("/") ? this.path : "/" + this.path
    return base + encode(p, false)
  }

  get canonicalQuery(): string {
    return this.query
      .map(([k, v]) => [encode(k, true), encode(v, true)] as const)
      .sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : a[1] < b[1] ? -1 : a[1] > b[1] ? 1 : 0))
      .map(([k, v]) => `${k}=${v}`)
      .join("&")
  }

  /** The URL the request is sent to: the same encoding as the one signed. */
  get url(): string {
    const q = this.canonicalQuery
    return `${this.endpoint.protocol}//${this.host}${this.canonicalPath}${q === "" ? "" : "?" + q}`
  }

  withQuery(more: readonly (readonly [string, string])[]): SignedRequest {
    return new SignedRequest(this.method, this.endpoint, this.path, [...this.query, ...more])
  }
}

function pad(n: number, width = 2): string {
  return String(n).padStart(width, "0")
}

/** `20150830T123600Z`. */
export function amzDate(now: Date): string {
  return `${shortDate(now)}T${pad(now.getUTCHours())}${pad(now.getUTCMinutes())}${pad(now.getUTCSeconds())}Z`
}

function shortDate(now: Date): string {
  return `${pad(now.getUTCFullYear(), 4)}${pad(now.getUTCMonth() + 1)}${pad(now.getUTCDate())}`
}

function signingKey(secret: string, now: Date, region: string, service: string): Buffer {
  const date = hmac(utf8.encode("AWS4" + secret), shortDate(now))
  return hmac(hmac(hmac(date, region), service), "aws4_request")
}

function stringToSign(stamp: string, scope: string, canonicalRequest: string): string {
  return `${ALGORITHM}\n${stamp}\n${scope}\n${sha256Hex(canonicalRequest)}`
}

/**
 * The headers to send with `request` so the store believes it: `x-amz-date` and `Authorization`.
 * `headers` are the ones to sign beside `host` and `x-amz-date` — an S3 request includes
 * `x-amz-content-sha256` among them — and are sent as given.
 */
export function sign(
  request: SignedRequest,
  headers: Readonly<Record<string, string>>,
  payloadHash: string,
  credentials: Credentials,
  region: string,
  service: string,
  now: Date,
): { "x-amz-date": string; Authorization: string } {
  const stamp = amzDate(now)
  const signed = new Map<string, string>()
  for (const [k, v] of Object.entries(headers)) signed.set(k.toLowerCase(), v)
  signed.set("host", request.host)
  signed.set("x-amz-date", stamp)
  const sorted = [...signed.entries()].map(([k, v]) => [k, v.trim().replace(/ +/g, " ")] as const).sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0))
  const names = sorted.map(([k]) => k).join(";")
  const canonical = sorted.map(([k, v]) => `${k}:${v}\n`).join("")
  const scope = `${shortDate(now)}/${region}/${service}/aws4_request`
  const creq = [request.method, request.canonicalPath, request.canonicalQuery, canonical, names, payloadHash].join("\n")
  const signature = hmac(signingKey(credentials._secretKey, now, region, service), stringToSign(stamp, scope, creq)).toString("hex")
  return {
    "x-amz-date": stamp,
    Authorization: `${ALGORITHM} Credential=${credentials.accessKey}/${scope}, SignedHeaders=${names}, Signature=${signature}`,
  }
}

/** A URL anyone may use, until it expires, for `request`'s method on its path. Signs `host` alone. */
export function presign(request: SignedRequest, credentials: Credentials, region: string, service: string, expiresSeconds: number, now: Date): string {
  const stamp = amzDate(now)
  const scope = `${shortDate(now)}/${region}/${service}/aws4_request`
  const withAuth = request.withQuery([
    ["X-Amz-Algorithm", ALGORITHM],
    ["X-Amz-Credential", `${credentials.accessKey}/${scope}`],
    ["X-Amz-Date", stamp],
    ["X-Amz-Expires", String(expiresSeconds)],
    ["X-Amz-SignedHeaders", "host"],
  ])
  const creq = [request.method, withAuth.canonicalPath, withAuth.canonicalQuery, `host:${request.host}\n`, "host", UNSIGNED_PAYLOAD].join("\n")
  const signature = hmac(signingKey(credentials._secretKey, now, region, service), stringToSign(stamp, scope, creq)).toString("hex")
  return withAuth.withQuery([["X-Amz-Signature", signature]]).url
}
