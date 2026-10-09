// The JSON encoding, schema-directed, byte for byte what the Scala codecs write (protocol/ENCODING.md).
//
// Writing is the SDK's own: fields in declaration order, every field written, `null` for an absent
// option, doubles rendered as `Double.toString` does (`1.0`, `1.0E10`), a `long` as digits however
// large. `JSON.stringify` could do none of that. Reading is `JSON.parse` with the reviver's source-text
// access (Node 21+) so an integer past 2⁵³ becomes a `BigInt` instead of a rounded number, followed by a
// schema-directed decode that converts, checks and names the path of anything it refuses.

import { Duration, Instant, LocalDate, LocalDateTime } from "./time.ts"
import { done, resolve, describe, type Schema } from "./schema.ts"
import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto"
import { checkSubject, isLookupAllowed, keySource, PersonalFieldError, type Personal, type Present } from "./personal.ts"

export class EncodingError extends Error {
  constructor(message: string) {
    super(message)
    this.name = "EncodingError"
  }
}

export class DecodingError extends Error {
  /** Where in the document the refusal is, like `items[2].quantity`; empty at the top level. */
  readonly path: string
  constructor(message: string, path: string) {
    super(path ? `${path}: ${message}` : message)
    this.name = "DecodingError"
    this.path = path
  }
}

/** `Double.toString` as Scala prints it: plain between 1e-3 and 1e7 with at least one fractional digit, scientific outside. */
export function renderDouble(d: number): string {
  if (!Number.isFinite(d)) throw new EncodingError("NaN and infinities are not JSON")
  if (d === 0) return Object.is(d, -0) ? "-0.0" : "0.0"
  const magnitude = Math.abs(d)
  if (magnitude >= 1e-3 && magnitude < 1e7) {
    const plain = String(d)
    if (plain.includes("e")) return fromExponential(d) // not reachable in this range for finite doubles, kept for safety
    return plain.includes(".") ? plain : plain + ".0"
  }
  return fromExponential(d)
}

function fromExponential(d: number): string {
  const [mantissa, exponent] = d.toExponential().split("e") as [string, string]
  const m = mantissa.includes(".") ? mantissa : mantissa + ".0"
  return `${m}E${Number(exponent)}`
}

function base64Encode(bytes: Uint8Array): string {
  return Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength).toString("base64")
}

function base64Decode(text: string): Uint8Array {
  return new Uint8Array(Buffer.from(text, "base64"))
}

/** The JSON text for `value` under `schema`. */
export function writeJson(schema: Schema, value: unknown): string {
  const out: string[] = []
  write(schema, value, out, "")
  return out.join("")
}

function write(schema: Schema, value: unknown, out: string[], path: string): void {
  const r = resolve(schema)
  switch (r.kind) {
    case "string":
      if (typeof value !== "string") throw new EncodingError(`${at(path)}expected a string, got ${kindOf(value)}`)
      out.push(JSON.stringify(value))
      return
    case "int":
      if (typeof value === "bigint" && value >= BigInt(Number.MIN_SAFE_INTEGER) && value <= BigInt(Number.MAX_SAFE_INTEGER)) {
        out.push(value.toString())
        return
      }
      if (typeof value !== "number" || !Number.isSafeInteger(value)) {
        throw new EncodingError(`${at(path)}expected a whole number within ±2^53 for int, got ${kindOf(value)}`)
      }
      out.push(String(value))
      return
    case "long":
      if (typeof value === "bigint") {
        out.push(value.toString())
        return
      }
      if (typeof value === "number" && Number.isSafeInteger(value)) {
        out.push(String(value))
        return
      }
      throw new EncodingError(`${at(path)}expected a bigint (or a safe integer) for long, got ${kindOf(value)}`)
    case "double":
      if (typeof value === "bigint") {
        out.push(renderDouble(Number(value)))
        return
      }
      if (typeof value !== "number") throw new EncodingError(`${at(path)}expected a number for double, got ${kindOf(value)}`)
      out.push(renderDouble(value))
      return
    case "boolean":
      if (typeof value !== "boolean") throw new EncodingError(`${at(path)}expected a boolean, got ${kindOf(value)}`)
      out.push(value ? "true" : "false")
      return
    case "instant": {
      const instant = value instanceof Instant ? value : value instanceof Date ? Instant.fromDate(value) : undefined
      if (!instant) throw new EncodingError(`${at(path)}expected an Instant (or a Date), got ${kindOf(value)}`)
      out.push(JSON.stringify(instant.toString()))
      return
    }
    case "duration":
      if (!(value instanceof Duration)) throw new EncodingError(`${at(path)}expected a Duration, got ${kindOf(value)}`)
      out.push(JSON.stringify(value.toString()))
      return
    case "localDate":
      if (!(value instanceof LocalDate)) throw new EncodingError(`${at(path)}expected a LocalDate, got ${kindOf(value)}`)
      out.push(JSON.stringify(value.toString()))
      return
    case "localDateTime":
      if (!(value instanceof LocalDateTime)) throw new EncodingError(`${at(path)}expected a LocalDateTime, got ${kindOf(value)}`)
      out.push(JSON.stringify(value.toString()))
      return
    case "bytes":
      if (!(value instanceof Uint8Array)) throw new EncodingError(`${at(path)}expected a Uint8Array, got ${kindOf(value)}`)
      out.push(JSON.stringify(base64Encode(value)))
      return
    case "unit":
    case "done":
      out.push("null")
      return
    case "option":
      if (value === null || value === undefined) out.push("null")
      else write(r.inner, value, out, path)
      return
    case "list": {
      if (!Array.isArray(value)) throw new EncodingError(`${at(path)}expected an array, got ${kindOf(value)}`)
      out.push("[")
      value.forEach((item, i) => {
        if (i > 0) out.push(",")
        write(r.inner, item, out, `${path}[${i}]`)
      })
      out.push("]")
      return
    }
    case "stringMap": {
      if (!isPlainObject(value)) throw new EncodingError(`${at(path)}expected an object for a string-keyed map, got ${kindOf(value)}`)
      out.push("{")
      let first = true
      for (const [k, v] of Object.entries(value)) {
        if (!first) out.push(",")
        first = false
        out.push(JSON.stringify(k), ":")
        write(r.inner, v, out, path ? `${path}.${k}` : k)
      }
      out.push("}")
      return
    }
    case "record": {
      if (!isPlainObject(value)) throw new EncodingError(`${at(path)}expected an object for ${describe(r)}, got ${kindOf(value)}`)
      out.push("{")
      writeFields(r.fields, value, out, path, false)
      out.push("}")
      return
    }
    case "sumType": {
      if (!isPlainObject(value) || typeof value["type"] !== "string") {
        throw new EncodingError(`${at(path)}expected an object with a "type" for ${describe(r)}, got ${kindOf(value)}`)
      }
      const caseName = value["type"]
      const fields = r.cases[caseName]
      if (!fields) throw new EncodingError(`${at(path)}${describe(r)} has no case ${JSON.stringify(caseName)}`)
      out.push("{", JSON.stringify("type"), ":", JSON.stringify(caseName))
      writeFields(fields, value, out, path, true)
      out.push("}")
      return
    }
    case "enumeration": {
      if (typeof value !== "string" || !r.values.includes(value)) {
        throw new EncodingError(`${at(path)}expected one of ${r.values.map((v) => JSON.stringify(v)).join(", ")} for ${describe(r)}, got ${kindOf(value)}`)
      }
      out.push(`{"type":${JSON.stringify(value)}}`)
      return
    }
    case "personal":
      out.push(writePersonal(r.inner, value, path))
      return
  }
}

// ── Personal fields (protocol 1.15; protocol/fixtures/personal) ─────────────

function aad(subject: string, project: string): Uint8Array {
  return new TextEncoder().encode(`${subject}\u0000${project}`)
}

function writePersonal(inner: Schema, value: unknown, path: string): string {
  if (!isPlainObject(value) || (value["kind"] !== "present" && value["kind"] !== "erased") || typeof value["subject"] !== "string") {
    throw new EncodingError(`${at(path)}expected a personal value (present(...) or erased(...)), got ${kindOf(value)}`)
  }
  const keys = keySource()
  const p = value as unknown as Personal<unknown>
  if (p.kind === "erased") {
    const project = p.project ?? keys.ownProject ?? keys.key(undefined, p.subject, false).project
    return JSON.stringify({ subject: p.subject, project })
  }
  const answer = keys.key(p.project, p.subject, p.project === undefined || p.project === keys.ownProject)
  const project = answer.project || p.project || keys.ownProject || ""
  switch (answer.kind) {
    case "key": {
      const plaintext = new TextEncoder().encode(writeJson(inner, p.value))
      const nonce = randomBytes(12)
      const cipher = createCipheriv("aes-256-gcm", answer.key, nonce)
      cipher.setAAD(aad(p.subject, project))
      const sealed = Buffer.concat([Buffer.from([1]), nonce, cipher.update(plaintext), cipher.final(), cipher.getAuthTag()])
      const envelope: Record<string, string> = { subject: p.subject, project, data: sealed.toString("base64") }
      if (p.lookup && isLookupAllowed()) envelope["lookup"] = keys.lookupToken(plaintext)
      p.stored = true
      return JSON.stringify(envelope)
    }
    case "destroyed":
      // Carried, not new: a state holding what was stored before the erasure.
      if (p.stored) return JSON.stringify({ subject: p.subject, project })
      throw new PersonalFieldError(`data subject ${p.subject} is erased in project ${project}: no personal field can be written for it`, "BAD_REQUEST")
    case "refused":
      // No key and no tombstone, asked without creating one: a subject never written here, as erased.
      if (answer.reason === "unknown") {
        throw new PersonalFieldError(`data subject ${p.subject} is erased in project ${project}: no personal field can be written for it`, "BAD_REQUEST")
      }
      throw new PersonalFieldError(`the keyring refused data subject ${p.subject} of project ${project}: ${answer.reason}`, "FORBIDDEN")
    case "unavailable":
      throw new PersonalFieldError(`no keyring is available: ${answer.reason}`, "UNAVAILABLE")
  }
}

function readPersonal(inner: Schema, raw: unknown, path: string): Personal<unknown> {
  if (!isPlainObject(raw)) throw new DecodingError(`expected a personal envelope, got ${kindOf(raw)}`, path)
  for (const k of Object.keys(raw)) {
    if (!["subject", "project", "data", "lookup"].includes(k)) throw new DecodingError(`unknown key '${k}' in a personal envelope`, path)
  }
  const subject = raw["subject"]
  const project = raw["project"]
  if (typeof subject !== "string") throw new DecodingError("a personal envelope needs a valid subject", path)
  try {
    checkSubject(subject)
  } catch {
    throw new DecodingError("a personal envelope needs a valid subject", path)
  }
  if (typeof project !== "string" || project === "") throw new DecodingError("a personal envelope needs its project", path)
  const data = raw["data"]
  if (data === undefined || data === null) return { kind: "erased", subject, project }
  const answer = keySource().key(project, subject, false)
  if (answer.kind === "unavailable") throw new PersonalFieldError(`no keyring is available: ${answer.reason}`, "UNAVAILABLE")
  if (answer.kind !== "key") return { kind: "erased", subject, project }
  if (typeof data !== "string" || !/^[A-Za-z0-9+/]*={0,2}$/.test(data)) throw new DecodingError("personal envelope corrupt: data is not base64", path)
  const stored = Buffer.from(data, "base64")
  const corrupt = (): DecodingError => new DecodingError(`personal envelope corrupt: it does not open as ${subject} of ${project}`, path)
  if (stored.length < 1 + 12 + 16 || stored[0] !== 1) throw corrupt()
  let plaintext: Buffer
  try {
    const decipher = createDecipheriv("aes-256-gcm", answer.key, stored.subarray(1, 13))
    decipher.setAAD(aad(subject, project))
    decipher.setAuthTag(stored.subarray(stored.length - 16))
    plaintext = Buffer.concat([decipher.update(stored.subarray(13, stored.length - 16)), decipher.final()])
  } catch {
    throw corrupt()
  }
  const read: Present<unknown> = { kind: "present", subject, value: readJson(inner, plaintext.toString("utf8")), lookup: "lookup" in raw, project }
  read.stored = true
  return read
}

function writeFields(fields: Readonly<Record<string, Schema>>, value: Record<string, unknown>, out: string[], path: string, continuing: boolean): void {
  let first = !continuing
  for (const [name, field] of Object.entries(fields)) {
    if (!first) out.push(",")
    first = false
    out.push(JSON.stringify(name), ":")
    const v = value[name]
    if (v === undefined && resolve(field).kind !== "option") {
      throw new EncodingError(`${at(path ? `${path}.${name}` : name)}missing required field`)
    }
    write(field, v, out, path ? `${path}.${name}` : name)
  }
}

/** Decodes `text` under `schema`. Lenient on unknown fields and field order; strict on what is required. */
export function readJson<T>(schema: Schema<T>, text: string): T {
  let raw: unknown
  try {
    raw = JSON.parse(text, reviver)
  } catch (e) {
    throw new DecodingError(`not JSON: ${(e as Error).message}`, "")
  }
  return decode(schema, raw, "") as T
}

/** Applies the schema to a value `JSON.parse` produced (with the same reviver), for callers that hold parsed JSON. */
export function decodeJsonValue<T>(schema: Schema<T>, raw: unknown): T {
  return decode(schema, raw, "") as T
}

/** The reviver: an integer past 2⁵³ arrives as a `BigInt` made from its source text, everything else as usual. */
export function reviver(this: unknown, _key: string, value: unknown, context?: { source?: string }): unknown {
  if (typeof value === "number" && Number.isInteger(value) && !Number.isSafeInteger(value)) {
    const source = context?.source
    if (source && /^-?\d+$/.test(source)) return BigInt(source)
  }
  return value
}

function decode(schema: Schema, raw: unknown, path: string): unknown {
  const r = resolve(schema)
  switch (r.kind) {
    case "string":
      if (typeof raw !== "string") throw new DecodingError(`expected a string, got ${kindOf(raw)}`, path)
      return raw
    case "int":
      if (typeof raw === "bigint") {
        if (raw < BigInt(Number.MIN_SAFE_INTEGER) || raw > BigInt(Number.MAX_SAFE_INTEGER)) {
          throw new DecodingError(`${raw} does not fit an int (declare the field as long)`, path)
        }
        return Number(raw)
      }
      if (typeof raw !== "number" || !Number.isInteger(raw)) throw new DecodingError(`expected a whole number, got ${kindOf(raw)}`, path)
      return raw
    case "long":
      if (typeof raw === "bigint") return raw
      if (typeof raw === "number" && Number.isInteger(raw)) return BigInt(raw)
      throw new DecodingError(`expected a whole number, got ${kindOf(raw)}`, path)
    case "double":
      if (typeof raw === "bigint") return Number(raw)
      if (typeof raw !== "number") throw new DecodingError(`expected a number, got ${kindOf(raw)}`, path)
      return raw
    case "boolean":
      if (typeof raw !== "boolean") throw new DecodingError(`expected a boolean, got ${kindOf(raw)}`, path)
      return raw
    case "instant":
      return parseWith(raw, path, "an ISO-8601 instant", Instant.parse)
    case "duration":
      return parseWith(raw, path, "an ISO-8601 duration", Duration.parse)
    case "localDate":
      return parseWith(raw, path, "an ISO-8601 date", LocalDate.parse)
    case "localDateTime":
      return parseWith(raw, path, "an ISO-8601 date-time", LocalDateTime.parse)
    case "bytes":
      if (typeof raw !== "string") throw new DecodingError(`expected base64 text, got ${kindOf(raw)}`, path)
      return base64Decode(raw)
    case "unit":
      return undefined
    case "done":
      return done
    case "option":
      return raw === null || raw === undefined ? null : decode(r.inner, raw, path)
    case "list":
      if (!Array.isArray(raw)) throw new DecodingError(`expected an array, got ${kindOf(raw)}`, path)
      return raw.map((item, i) => decode(r.inner, item, `${path}[${i}]`))
    case "stringMap": {
      if (!isPlainObject(raw)) throw new DecodingError(`expected an object, got ${kindOf(raw)}`, path)
      const out: Record<string, unknown> = {}
      for (const [k, v] of Object.entries(raw)) out[k] = decode(r.inner, v, path ? `${path}.${k}` : k)
      return out
    }
    case "record": {
      if (!isPlainObject(raw)) throw new DecodingError(`expected an object for ${describe(r)}, got ${kindOf(raw)}`, path)
      return decodeFields(r.fields, raw, path)
    }
    case "sumType": {
      if (!isPlainObject(raw)) throw new DecodingError(`expected an object for ${describe(r)}, got ${kindOf(raw)}`, path)
      const caseName = raw["type"]
      if (typeof caseName !== "string") throw new DecodingError(`${describe(r)} needs a "type"`, path)
      const fields = r.cases[caseName]
      if (!fields) throw new DecodingError(`${describe(r)} has no case ${JSON.stringify(caseName)}`, path)
      return { type: caseName, ...decodeFields(fields, raw, path) }
    }
    case "enumeration": {
      const name = typeof raw === "string" ? raw : isPlainObject(raw) ? raw["type"] : undefined
      if (typeof name !== "string" || !r.values.includes(name)) {
        throw new DecodingError(`expected one of ${r.values.map((v) => JSON.stringify(v)).join(", ")} for ${describe(r)}, got ${JSON.stringify(raw)}`, path)
      }
      return name
    }
    case "personal":
      return readPersonal(r.inner, raw, path)
  }
}

function decodeFields(fields: Readonly<Record<string, Schema>>, raw: Record<string, unknown>, path: string): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [name, field] of Object.entries(fields)) {
    const fieldPath = path ? `${path}.${name}` : name
    const v = raw[name]
    if (v === undefined) {
      if (resolve(field).kind === "option") out[name] = null
      else throw new DecodingError("missing required field", fieldPath)
    } else {
      out[name] = decode(field, v, fieldPath)
    }
  }
  return out
}

function parseWith<T>(raw: unknown, path: string, what: string, parse: (text: string) => T): T {
  if (typeof raw !== "string") throw new DecodingError(`expected ${what}, got ${kindOf(raw)}`, path)
  try {
    return parse(raw)
  } catch (e) {
    throw new DecodingError((e as Error).message, path)
  }
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

function kindOf(value: unknown): string {
  if (value === null) return "null"
  if (Array.isArray(value)) return "an array"
  if (value instanceof Uint8Array) return "bytes"
  if (typeof value === "object") return "an object"
  return typeof value === "bigint" ? "a bigint" : `${/^[aeiou]/.test(typeof value) ? "an" : "a"} ${typeof value}`
}

function at(path: string): string {
  return path ? `${path}: ` : ""
}
