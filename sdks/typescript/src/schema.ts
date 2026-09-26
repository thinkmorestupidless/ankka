// Shape declarations. TypeScript erases its types, so every value that crosses the protocol is
// declared once as a `Schema`, and two things are derived from it: the static type (`Infer`) and the
// codec (`codec.ts`). A schema does what a validation library would not for this encoding: it says
// whether a number is an `int` or a `double`, carries a `long` as `bigint`, and names records so the
// default manifest exists. Nothing here performs I/O or touches JSON; that is `json.ts`.

import type { Duration, Instant, LocalDate, LocalDateTime } from "./time.ts"

/** The one value of type `Done`: a handler that has nothing to say. */
export const done = Object.freeze({ done: true } as const)
export type Done = typeof done

export type ScalarKind =
  | "string"
  | "int"
  | "long"
  | "double"
  | "boolean"
  | "instant"
  | "duration"
  | "localDate"
  | "localDateTime"
  | "bytes"
  | "unit"
  | "done"

/** The structure of a schema, without its type parameter. `resolve` narrows away `lazy`. */
export type Shape =
  | { readonly kind: ScalarKind }
  | { readonly kind: "option"; readonly inner: Schema }
  | { readonly kind: "list"; readonly inner: Schema }
  | { readonly kind: "stringMap"; readonly inner: Schema }
  | { readonly kind: "record"; readonly name: string; readonly fields: Readonly<Record<string, Schema>> }
  | { readonly kind: "sumType"; readonly name: string; readonly cases: Readonly<Record<string, Readonly<Record<string, Schema>>>> }
  | { readonly kind: "enumeration"; readonly name: string; readonly values: readonly string[] }
  | { readonly kind: "lazy"; readonly thunk: () => Schema }

interface Typed<T> {
  /** Phantom: carries the TypeScript type. Never set. */
  readonly _type?: T
}

/** A shape declaration carrying the TypeScript type it describes. */
export type Schema<T = unknown> = Typed<T> & Shape

/** The TypeScript type a schema describes: `type Cart = Infer<typeof Cart>`. */
export type Infer<S> = S extends Typed<infer T> ? T : never

type Simplify<T> = { [K in keyof T]: T[K] } & {}
type InferFields<F extends Readonly<Record<string, Schema>>> = Simplify<{ [K in keyof F]: Infer<F[K]> }>
type InferCases<C extends Readonly<Record<string, Readonly<Record<string, Schema>>>>> = {
  [K in keyof C & string]: Simplify<{ type: K } & InferFields<C[K]>>
}[keyof C & string]

export class SchemaError extends Error {
  constructor(message: string) {
    super(message)
    this.name = "SchemaError"
  }
}

function scalar<T>(kind: ScalarKind): Schema<T> {
  return Object.freeze({ kind }) as Schema<T>
}

function checkName(what: string, name: string): void {
  if (typeof name !== "string" || name.trim() === "") throw new SchemaError(`a ${what} needs a name`)
}

/** The schema builders. `import { s } from "ankka"` and declare shapes with `s.record(...)`. */
export const s = Object.freeze({
  /** A JSON string. At top level: the `string` text payload. */
  string: scalar<string>("string"),
  /** A whole number within ±2⁵³, as a JavaScript `number`. At top level: the `int` text payload. */
  int: scalar<number>("int"),
  /** A 64-bit whole number, as a `bigint`, lossless past 2⁵³. At top level: the `long` text payload. */
  long: scalar<bigint>("long"),
  /** A double-precision number, rendered as the Scala codecs render it (`1.0`, `1.0E10`). */
  double: scalar<number>("double"),
  boolean: scalar<boolean>("boolean"),
  /** ISO-8601 in UTC with 0, 3, 6 or 9 fractional digits. */
  instant: scalar<Instant>("instant"),
  /** ISO-8601, `PT1.5S`. */
  duration: scalar<Duration>("duration"),
  localDate: scalar<LocalDate>("localDate"),
  localDateTime: scalar<LocalDateTime>("localDateTime"),
  /** Base64 inside JSON; at top level the raw `bytes` payload. */
  bytes: scalar<Uint8Array>("bytes"),
  /** No value: the `unit` payload of zero bytes. */
  unit: scalar<undefined>("unit"),

  /** `T | null`: written as `null` when absent; an absent field reads as `null`. */
  option<T>(inner: Schema<T>): Schema<T | null> {
    return Object.freeze({ kind: "option", inner }) as Schema<T | null>
  },
  list<T>(inner: Schema<T>): Schema<T[]> {
    return Object.freeze({ kind: "list", inner }) as Schema<T[]>
  },
  stringMap<T>(inner: Schema<T>): Schema<Record<string, T>> {
    return Object.freeze({ kind: "stringMap", inner }) as Schema<Record<string, T>>
  },

  /** A record: a JSON object with every field written, in declaration order. `name` is the default manifest. */
  record<F extends Readonly<Record<string, Schema>>>(name: string, fields: F): Schema<InferFields<F>> {
    checkName("record", name)
    return Object.freeze({ kind: "record", name, fields: Object.freeze({ ...fields }) }) as Schema<InferFields<F>>
  },

  /**
   * A sum type: each case is an object carrying `"type": "<CaseName>"` and the case's fields, which is
   * also how a TypeScript discriminated union reads. `s.sumType("Event", { Added: { item: Item }, Done: {} })`
   * gives `{ type: "Added"; item: Item } | { type: "Done" }`.
   */
  sumType<C extends Readonly<Record<string, Readonly<Record<string, Schema>>>>>(name: string, cases: C): Schema<InferCases<C>> {
    checkName("sum type", name)
    const names = Object.keys(cases)
    if (names.length === 0) throw new SchemaError(`sum type ${name} has no cases`)
    for (const c of names) {
      if (c === "type") throw new SchemaError(`sum type ${name}: a case cannot be named "type", the discriminator`)
      if ("type" in cases[c]!) throw new SchemaError(`sum type ${name}, case ${c}: a field cannot be named "type", the discriminator`)
    }
    const frozen: Record<string, Readonly<Record<string, Schema>>> = {}
    for (const c of names) frozen[c] = Object.freeze({ ...cases[c]! })
    return Object.freeze({ kind: "sumType", name, cases: Object.freeze(frozen) }) as Schema<InferCases<C>>
  },

  /** A fieldless enumeration used as a field: `{"type":"Ready"}` on the wire, `"Ready"` in TypeScript. */
  enumeration<const V extends readonly [string, ...string[]]>(name: string, ...values: V): Schema<V[number]> {
    checkName("enumeration", name)
    if (new Set(values).size !== values.length) throw new SchemaError(`enumeration ${name} repeats a value`)
    return Object.freeze({ kind: "enumeration", name, values: Object.freeze([...values]) }) as Schema<V[number]>
  },

  /** A reference to a schema declared later, for recursive shapes: `s.list(s.lazy(() => Tree))`. */
  lazy<T>(thunk: () => Schema<T>): Schema<T> {
    return Object.freeze({ kind: "lazy", thunk }) as Schema<T>
  },
})

/** The schema of `done`: the empty `done` payload. */
export const Done: Schema<Done> = scalar<Done>("done")

type Resolved<T> = Typed<T> & Exclude<Shape, { kind: "lazy" }>

/** Follows `lazy` references to the schema they name. */
export function resolve<T>(schema: Schema<T>): Resolved<T> {
  let current: Schema<T> = schema
  for (let i = 0; i < 64 && current.kind === "lazy"; i++) current = current.thunk() as Schema<T>
  if (current.kind === "lazy") throw new SchemaError("a lazy schema never resolves")
  return current as Resolved<T>
}

/** A short description for error messages: `record Cart`, `list<int>`, `string`. */
export function describe(schema: Schema): string {
  const r = resolve(schema)
  switch (r.kind) {
    case "record":
      return `record ${r.name}`
    case "sumType":
      return `sum type ${r.name}`
    case "enumeration":
      return `enumeration ${r.name}`
    case "option":
      return `option<${describe(r.inner)}>`
    case "list":
      return `list<${describe(r.inner)}>`
    case "stringMap":
      return `map<string, ${describe(r.inner)}>`
    default:
      return r.kind
  }
}

/** The default manifest for a schema: a record's, sum type's or enumeration's name; a scalar's text manifest. */
export function defaultManifest(schema: Schema): string {
  const r = resolve(schema)
  switch (r.kind) {
    case "record":
    case "sumType":
    case "enumeration":
      return r.name
    case "option":
      return `option[${defaultManifest(r.inner)}]`
    case "list":
      return `list[${defaultManifest(r.inner)}]`
    case "stringMap":
      return `map[${defaultManifest(r.inner)}]`
    default:
      return r.kind
  }
}

/**
 * JSON Schema (draft 2020-12 vocabulary) for a shape, as an agent tool's input needs it. Objects refuse
 * unknown properties; integers are `integer`; instants are `string` with `format: date-time`.
 */
export function toJsonSchema(schema: Schema): Record<string, unknown> {
  const r = resolve(schema)
  switch (r.kind) {
    case "string":
      return { type: "string" }
    case "int":
    case "long":
      return { type: "integer" }
    case "double":
      return { type: "number" }
    case "boolean":
      return { type: "boolean" }
    case "instant":
      return { type: "string", format: "date-time" }
    case "duration":
      return { type: "string", format: "duration" }
    case "localDate":
      return { type: "string", format: "date" }
    case "localDateTime":
      return { type: "string", format: "date-time" }
    case "bytes":
      return { type: "string", contentEncoding: "base64" }
    case "unit":
    case "done":
      return { type: "null" }
    case "option":
      return { anyOf: [toJsonSchema(r.inner), { type: "null" }] }
    case "list":
      return { type: "array", items: toJsonSchema(r.inner) }
    case "stringMap":
      return { type: "object", additionalProperties: toJsonSchema(r.inner) }
    case "record":
      return objectSchema(r.fields)
    case "sumType":
      return {
        oneOf: Object.entries(r.cases).map(([name, fields]) => {
          const o = objectSchema(fields)
          return {
            ...o,
            properties: { type: { const: name }, ...(o.properties as object) },
            required: ["type", ...(o.required as string[])],
          }
        }),
      }
    case "enumeration":
      return { type: "object", properties: { type: { enum: [...r.values] } }, required: ["type"], additionalProperties: false }
  }
}

function objectSchema(fields: Readonly<Record<string, Schema>>): Record<string, unknown> {
  const properties: Record<string, unknown> = {}
  const required: string[] = []
  for (const [name, field] of Object.entries(fields)) {
    properties[name] = toJsonSchema(field)
    if (resolve(field).kind !== "option") required.push(name)
  }
  return { type: "object", properties, required, additionalProperties: false }
}
