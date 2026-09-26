// Codecs: a manifest, a content type, and the bytes for a value (protocol/ENCODING.md). The default
// codec for a schema is derived from it — a record or sum type is JSON under the schema's name; a
// top-level scalar is `text/plain` under its primitive manifest; `Done`, `unit`, bytes and a top-level
// option are the binary payloads. A developer may hand over any object satisfying `Codec<T>` where a
// schema is expected; then portability is their contract.

import { Duration } from "./time.ts"
import { done, defaultManifest, describe, resolve, type Done, type Schema } from "./schema.ts"
import { readJson, renderDouble, writeJson, DecodingError } from "./json.ts"

export type ContentType = "application/json" | "text/plain" | "application/octet-stream"

export const JSON_CONTENT: ContentType = "application/json"
export const TEXT_CONTENT: ContentType = "text/plain"
export const BINARY_CONTENT: ContentType = "application/octet-stream"

export interface Codec<T> {
  readonly manifest: string
  readonly contentType: ContentType
  encode(value: T): Uint8Array
  decode(bytes: Uint8Array): T
}

/** Where a schema is expected, a codec is accepted too. */
export type Shape<T> = Schema<T> | Codec<T>

export function isCodec<T>(shape: Shape<T>): shape is Codec<T> {
  return typeof (shape as Codec<T>).encode === "function" && typeof (shape as Codec<T>).decode === "function"
}

/** The codec for a shape: the codec itself, or the schema's default. */
export function codecFor<T>(shape: Shape<T>): Codec<T> {
  return isCodec(shape) ? shape : defaultCodecFor(shape)
}

const utf8 = new TextEncoder()
const utf8Decoder = new TextDecoder("utf-8", { fatal: true })

function text(bytes: Uint8Array): string {
  return utf8Decoder.decode(bytes)
}

function frozen<T>(codec: Codec<T>): Codec<T> {
  return Object.freeze(codec)
}

/** The default JSON codec for a schema, under `manifest` (the schema's name when omitted). */
export function jsonCodec<T>(schema: Schema<T>, manifest: string = defaultManifest(schema)): Codec<T> {
  if (typeof manifest !== "string" || manifest.trim() === "") throw new TypeError(`jsonCodec(${describe(schema)}): a manifest is required`)
  return frozen({
    manifest,
    contentType: JSON_CONTENT,
    encode: (value: T) => utf8.encode(writeJson(schema, value)),
    decode: (bytes: Uint8Array) => readJson(schema, text(bytes)),
  })
}

function textCodec<T>(manifest: string, parse: (s: string) => T, render: (v: T) => string): Codec<T> {
  return frozen({
    manifest,
    contentType: TEXT_CONTENT,
    encode: (value: T) => utf8.encode(render(value)),
    decode: (bytes: Uint8Array) => parse(text(bytes)),
  })
}

function parseInteger(manifest: string, min: number, max: number): (s: string) => number {
  return (s) => {
    if (!/^-?\d+$/.test(s.trim())) throw new DecodingError(`not a whole number for ${manifest}: ${JSON.stringify(s)}`, "")
    const n = Number(s)
    if (!Number.isSafeInteger(n) || n < min || n > max) throw new DecodingError(`${s} does not fit ${manifest}`, "")
    return n
  }
}

function renderInteger(manifest: string): (v: number) => string {
  return (v) => {
    if (!Number.isSafeInteger(v)) throw new TypeError(`${manifest}: expected a whole number, got ${String(v)}`)
    return String(v)
  }
}

function parseDouble(s: string): number {
  const t = s.trim()
  if (t === "NaN") return NaN
  if (t === "Infinity") return Infinity
  if (t === "-Infinity") return -Infinity
  const n = Number(t)
  if (t === "" || Number.isNaN(n)) throw new DecodingError(`not a number: ${JSON.stringify(s)}`, "")
  return n
}

function renderDoubleText(d: number): string {
  if (Number.isNaN(d)) return "NaN"
  if (d === Infinity) return "Infinity"
  if (d === -Infinity) return "-Infinity"
  return renderDouble(d)
}

/** The `text/plain` codecs for top-level primitives. */
export const textCodecs = Object.freeze({
  string: textCodec<string>("string", (s) => s, (v) => v),
  int: textCodec<number>("int", parseInteger("int", -2147483648, 2147483647), renderInteger("int")),
  short: textCodec<number>("short", parseInteger("short", -32768, 32767), renderInteger("short")),
  byte: textCodec<number>("byte", parseInteger("byte", -128, 127), renderInteger("byte")),
  long: textCodec<bigint>(
    "long",
    (s) => {
      if (!/^-?\d+$/.test(s.trim())) throw new DecodingError(`not a whole number for long: ${JSON.stringify(s)}`, "")
      return BigInt(s.trim())
    },
    (v) => {
      if (typeof v === "bigint") return v.toString()
      if (typeof v === "number" && Number.isSafeInteger(v)) return String(v)
      throw new TypeError(`long: expected a bigint, got ${String(v)}`)
    },
  ),
  double: textCodec<number>("double", parseDouble, renderDoubleText),
  float: textCodec<number>("float", parseDouble, renderDoubleText),
  boolean: textCodec<boolean>(
    "boolean",
    (s) => {
      const t = s.trim()
      if (t === "true") return true
      if (t === "false") return false
      throw new DecodingError(`not a boolean: ${JSON.stringify(s)}`, "")
    },
    (v) => (v ? "true" : "false"),
  ),
  durationMillis: textCodec<Duration>(
    "duration-millis",
    (s) => {
      if (!/^-?\d+$/.test(s.trim())) throw new DecodingError(`not a millisecond count: ${JSON.stringify(s)}`, "")
      return Duration.ofMillis(Number(s.trim()))
    },
    (v) => String(v.toMillis()),
  ),
})

const EMPTY = new Uint8Array(0)

/** The binary codecs: `done`, `unit`, raw bytes, and a top-level option over another codec. */
export const binaryCodecs = Object.freeze({
  done: frozen<Done>({
    manifest: "done",
    contentType: BINARY_CONTENT,
    encode: () => EMPTY,
    decode: () => done,
  }),
  unit: frozen<undefined>({
    manifest: "unit",
    contentType: BINARY_CONTENT,
    encode: () => EMPTY,
    decode: () => undefined,
  }),
  bytes: frozen<Uint8Array>({
    manifest: "bytes",
    contentType: BINARY_CONTENT,
    encode: (v) => v,
    decode: (b) => b,
  }),
  option<T>(inner: Codec<T>): Codec<T | null> {
    return frozen({
      manifest: `option[${inner.manifest}]`,
      contentType: BINARY_CONTENT,
      encode: (value: T | null) => {
        if (value === null || value === undefined) return EMPTY
        const encoded = inner.encode(value)
        const out = new Uint8Array(encoded.length + 1)
        out[0] = 1
        out.set(encoded, 1)
        return out
      },
      decode: (bytes: Uint8Array) => {
        if (bytes.length === 0) return null
        if (bytes[0] !== 1) throw new DecodingError(`option[${inner.manifest}]: expected a 0x01 marker, got ${bytes[0]}`, "")
        return inner.decode(bytes.subarray(1))
      },
    })
  },
})

/** The default codec for a schema: text for a top-level scalar, binary for done/unit/bytes/option, JSON otherwise. */
export function defaultCodecFor<T>(schema: Schema<T>): Codec<T> {
  const r = resolve(schema)
  switch (r.kind) {
    case "string":
      return textCodecs.string as Codec<T>
    case "int":
      return textCodecs.int as Codec<T>
    case "long":
      return textCodecs.long as Codec<T>
    case "double":
      return textCodecs.double as Codec<T>
    case "boolean":
      return textCodecs.boolean as Codec<T>
    case "duration":
      return textCodecs.durationMillis as Codec<T>
    case "bytes":
      return binaryCodecs.bytes as Codec<T>
    case "done":
      return binaryCodecs.done as Codec<T>
    case "unit":
      return binaryCodecs.unit as Codec<T>
    case "option":
      return binaryCodecs.option(defaultCodecFor(r.inner)) as Codec<T>
    default:
      return jsonCodec(schema)
  }
}

const primitives: Readonly<Record<string, Codec<unknown>>> = Object.freeze({
  string: textCodecs.string,
  int: textCodecs.int,
  long: textCodecs.long,
  short: textCodecs.short,
  byte: textCodecs.byte,
  double: textCodecs.double,
  float: textCodecs.float,
  boolean: textCodecs.boolean,
  "duration-millis": textCodecs.durationMillis,
  done: binaryCodecs.done,
  unit: binaryCodecs.unit,
  bytes: binaryCodecs.bytes,
} as Record<string, Codec<unknown>>)

/** The primitive codec a manifest names, including `option[<inner>]`, or `undefined` for a domain manifest. */
export function codecForManifest(manifest: string): Codec<unknown> | undefined {
  const direct = primitives[manifest]
  if (direct) return direct
  const m = /^option\[(.+)\]$/.exec(manifest)
  if (m) {
    const inner = codecForManifest(m[1]!)
    return inner ? binaryCodecs.option(inner) : undefined
  }
  return undefined
}
