// Publishing a graph: a consumer that says which elements a change leaves in which state, and has each
// published as a graph delta — one element's whole state at a version, or a tombstone marking it
// deleted — under the contract `ankka.graph-delta.v1`. The record's key is the element's
// (`node:<id>` or `edge:<id>`), never the author's to set, and its version is the change's sequence
// number unless one is stated. A graph database kept in step with the topic applies a delta when its
// version is newer than what it holds, so a change handled twice does no harm.
//
//   export class CartGraph extends GraphConsumer<ShoppingCartEvent> {
//     static readonly componentId = "cart-graph"
//     static readonly source = ShoppingCartEntity
//     static readonly message = ShoppingCartEntity.events
//     static readonly producesTo = "cart-graph"
//     onMessage(event) {
//       return this.effects.publish([this.graph.node(`cart:${this.subject}`, { labels: ["Cart"] })])
//     }
//   }

import type { StartFrom } from "./startFrom.ts"
import { JSON_CONTENT, type Codec, type Shape } from "./codec.ts"
import type { EffectLike, Metadata } from "./effects/common.ts"
import { secretsFor, type ComponentClient, type ComponentRef, type Secrets } from "./client.ts"
import { sequenceNumberOf } from "./consumer.ts"
import { renderDouble, reviver } from "./json.ts"
import { servicesFor, type Services } from "./services.ts"

type MaybePromise<T> = T | Promise<T>

/** The contract's name: the manifest and the `ce-type` of every delta. */
export const GRAPH_DELTA_SCHEMA = "ankka.graph-delta.v1"

/** The record key of every delta for the node `id`. */
export function nodeKey(id: string): string {
  return `node:${id}`
}

/** The record key of every delta for the edge `id`. Nodes and edges are separate id spaces. */
export function edgeKey(id: string): string {
  return `edge:${id}`
}

/** A property's scalar: an integral `number` must be a safe integer; a larger integer is a `bigint`. */
export type Scalar = string | boolean | number | bigint
/** A scalar, or a non-empty list of scalars of one kind. */
export type PropertyValue = Scalar | readonly Scalar[]
export type Properties = Readonly<Record<string, PropertyValue>>

/** A version: a whole number of at least 1, as a safe-integer `number` or a `bigint` within 64 bits. */
export type Version = number | bigint

/** An element as the author describes it. Without a `version` it takes its change's sequence number. */
export type Element =
  | { readonly kind: "node"; readonly id: string; readonly version?: Version; readonly labels: readonly string[]; readonly properties: Properties }
  | { readonly kind: "edge"; readonly id: string; readonly version?: Version; readonly type: string; readonly from: string; readonly to: string; readonly properties: Properties }
  | { readonly kind: "tombstone"; readonly element: "node"; readonly id: string; readonly version?: Version }
  | { readonly kind: "tombstone"; readonly element: "edge"; readonly id: string; readonly version?: Version; readonly type: string; readonly from: string; readonly to: string }

/** An element at a version: what is written and read. */
export type Delta = Element & { readonly version: Version }

/** Why an element or a result was refused; the names the shared fixture `refused.json` uses. */
export type GraphFault = "id" | "endpoints" | "identifier" | "reserved" | "property-value" | "integer-range" | "version" | "duplicate" | "no-sequence"

/** Thrown where an element is built, or when a result is returned, for something the merge sink would refuse. */
export class GraphError extends Error {
  readonly why: GraphFault
  constructor(why: GraphFault, message: string) {
    super(message)
    this.name = "GraphError"
    this.why = why
  }
}

const IDENTIFIER = /^[A-Za-z_][A-Za-z0-9_]*$/
const RESERVED: ReadonlySet<string> = new Set(["id", "_version", "_deleted"])
const INT64_MIN = -(2n ** 63n)
const INT64_MAX = 2n ** 63n - 1n

type ScalarKind = "string" | "boolean" | "integer" | "float"

function show(value: unknown): string {
  if (typeof value === "bigint") return `${value}n`
  if (typeof value === "number") return String(value)
  try {
    return JSON.stringify(value) ?? String(value)
  } catch {
    return String(value)
  }
}

function spaceOf(element: { kind: string; element?: string }): "node" | "edge" {
  return element.kind === "edge" || (element.kind === "tombstone" && element.element === "edge") ? "edge" : "node"
}

function describe(element: { kind: string; element?: string; id?: unknown }): string {
  const what = element.kind === "tombstone" ? `tombstone of ${spaceOf(element)}` : element.kind
  return `${what} ${show(element.id)}`
}

/** The element key: `node:<id>` or `edge:<id>`; a tombstone has the key of the element it marks. */
export function elementKey(element: Element): string {
  return spaceOf(element) === "edge" ? edgeKey(element.id) : nodeKey(element.id)
}

/** The kind of one scalar. A float with a whole value is an integer, as the sink reads it. */
function scalarKind(who: string, name: string, value: unknown): ScalarKind {
  switch (typeof value) {
    case "string":
      return "string"
    case "boolean":
      return "boolean"
    case "bigint":
      if (value < INT64_MIN || value > INT64_MAX) throw new GraphError("integer-range", `${who}: property ${show(name)} is ${value}, which does not fit 64 bits`)
      return "integer"
    case "number":
      if (!Number.isFinite(value)) throw new GraphError("property-value", `${who}: property ${show(name)} is ${value}, which is not a finite number`)
      if (!Number.isInteger(value)) return "float"
      if (Number.isSafeInteger(value)) return "integer"
      if (value < Number(INT64_MIN) || value >= 2 ** 63) throw new GraphError("integer-range", `${who}: property ${show(name)} is ${value}, a whole number that does not fit 64 bits`)
      throw new GraphError("integer-range", `${who}: property ${show(name)} is ${value}, a whole number past 2^53 that a number cannot hold exactly; pass it as a bigint`)
    default:
      throw new GraphError("property-value", `${who}: property ${show(name)} is ${show(value)}; a property is a string, a number, a boolean, or a non-empty list of one of those`)
  }
}

function checkProperties(who: string, properties: unknown): Properties {
  if (properties === undefined) return Object.freeze({})
  if (typeof properties !== "object" || properties === null || Array.isArray(properties)) {
    throw new GraphError("property-value", `${who}: properties must be an object of property values, not ${show(properties)}`)
  }
  const out: Record<string, PropertyValue> = {}
  for (const [name, value] of Object.entries(properties as Record<string, unknown>)) {
    if (RESERVED.has(name)) throw new GraphError("reserved", `${who}: property ${show(name)} is reserved`)
    if (Array.isArray(value)) {
      if (value.length === 0) throw new GraphError("property-value", `${who}: property ${show(name)} is an empty list; leave the property out instead`)
      const kinds = new Set(value.map((v) => scalarKind(who, name, v)))
      if (kinds.size > 1) throw new GraphError("property-value", `${who}: property ${show(name)} is a list of more than one kind (${[...kinds].join(", ")}); a whole-number float counts as an integer`)
      out[name] = Object.freeze([...(value as Scalar[])])
    } else {
      scalarKind(who, name, value)
      out[name] = value as Scalar
    }
  }
  return Object.freeze(out)
}

function checkVersion(who: string, version: unknown): Version {
  if (typeof version === "bigint") {
    if (version < 1n || version > INT64_MAX) throw new GraphError("version", `${who}: version must be a whole number of at least 1 within 64 bits, not ${version}`)
    return version <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(version) : version
  }
  if (typeof version !== "number" || !Number.isSafeInteger(version) || version < 1) {
    throw new GraphError("version", `${who}: version must be a whole number of at least 1 (a safe integer, or a bigint within 64 bits), not ${show(version)}`)
  }
  return version
}

function checkId(who: string, id: unknown): string {
  if (typeof id !== "string" || id === "") throw new GraphError("id", `${who}: id must be a non-empty string`)
  return id
}

function checkEndpoints(who: string, e: { type?: unknown; from?: unknown; to?: unknown }): { type: string; from: string; to: string } {
  for (const field of ["type", "from", "to"] as const) {
    if (typeof e[field] !== "string" || e[field] === "") throw new GraphError("endpoints", `${who}: an edge needs a type, a from and a to; ${field} is missing or empty`)
  }
  if (!IDENTIFIER.test(e.type as string)) throw new GraphError("identifier", `${who}: type ${show(e.type)} is not an identifier ([A-Za-z_][A-Za-z0-9_]*)`)
  return { type: e.type as string, from: e.from as string, to: e.to as string }
}

function checkLabels(who: string, labels: unknown): readonly string[] {
  if (labels === undefined) return Object.freeze([])
  if (!Array.isArray(labels)) throw new GraphError("identifier", `${who}: labels must be a list of identifiers, not ${show(labels)}`)
  for (const label of labels) {
    if (typeof label !== "string" || !IDENTIFIER.test(label)) throw new GraphError("identifier", `${who}: label ${show(label)} is not an identifier ([A-Za-z_][A-Za-z0-9_]*)`)
  }
  return Object.freeze([...(labels as string[])])
}

/** The element, checked against everything the merge sink would refuse of it, as a frozen value. */
function checked(element: Element): Element {
  const raw = element as { kind?: unknown; element?: unknown; id?: unknown; version?: unknown; labels?: unknown; properties?: unknown; type?: unknown; from?: unknown; to?: unknown }
  if (typeof raw !== "object" || raw === null) throw new TypeError(`not a graph element: ${show(raw)}`)
  if (raw.kind !== "node" && raw.kind !== "edge" && raw.kind !== "tombstone") throw new TypeError(`not a graph element: kind is ${show(raw.kind)}`)
  if (raw.kind === "tombstone" && raw.element !== "node" && raw.element !== "edge") throw new TypeError(`not a graph element: a tombstone marks a node or an edge, not ${show(raw.element)}`)
  const who = describe(raw as { kind: string; element?: string; id?: unknown })
  const id = checkId(who, raw.id)
  const version = raw.version === undefined ? {} : { version: checkVersion(who, raw.version) }
  if (raw.kind === "node") {
    return Object.freeze({ kind: "node", id, ...version, labels: checkLabels(who, raw.labels), properties: checkProperties(who, raw.properties) })
  }
  if (raw.kind === "edge") {
    return Object.freeze({ kind: "edge", id, ...version, ...checkEndpoints(who, raw), properties: checkProperties(who, raw.properties) })
  }
  if (raw.element === "edge") return Object.freeze({ kind: "tombstone", element: "edge", id, ...version, ...checkEndpoints(who, raw) })
  return Object.freeze({ kind: "tombstone", element: "node", id, ...version })
}

/** `this.graph` in a graph consumer: builds elements, refusing where they are built what the sink would refuse. */
export class Graph {
  node(id: string, options: { readonly labels?: readonly string[]; readonly properties?: Properties; readonly version?: Version } = {}): Element {
    return checked({ kind: "node", id, labels: options.labels as readonly string[], properties: options.properties as Properties, ...(options.version !== undefined ? { version: options.version } : {}) })
  }

  edge(id: string, options: { readonly type: string; readonly from: string; readonly to: string; readonly properties?: Properties; readonly version?: Version }): Element {
    return checked({ kind: "edge", id, type: options?.type, from: options?.from, to: options?.to, properties: options?.properties as Properties, ...(options?.version !== undefined ? { version: options.version } : {}) })
  }

  /** Marks a node deleted. The node stays in the graph, marked, so a straggling older delta cannot bring it back. */
  tombstoneNode(id: string, options: { readonly version?: Version } = {}): Element {
    return checked({ kind: "tombstone", element: "node", id, ...(options.version !== undefined ? { version: options.version } : {}) })
  }

  /** Marks an edge deleted; it names the edge's type and endpoints so the edge is found without a scan. */
  tombstoneEdge(id: string, options: { readonly type: string; readonly from: string; readonly to: string; readonly version?: Version }): Element {
    return checked({ kind: "tombstone", element: "edge", id, type: options?.type, from: options?.from, to: options?.to, ...(options?.version !== undefined ? { version: options.version } : {}) })
  }
}

export type GraphEffect =
  | ({ readonly kind: "publish"; readonly elements: readonly Element[] } & EffectLike<never>)
  | ({ readonly kind: "done" } & EffectLike<never>)
  | ({ readonly kind: "ignore" } & EffectLike<never>)

/** `this.effects` in a graph consumer: there is nothing here that publishes anything but elements. */
export class GraphEffects {
  /** Publish these elements, each as a delta under its own key. An empty list publishes nothing. */
  publish(elements: readonly Element[]): GraphEffect {
    if (!Array.isArray(elements)) throw new TypeError("publish takes a list of elements")
    return Object.freeze({ kind: "publish", elements: Object.freeze(elements.map(checked)) })
  }
  done(): GraphEffect {
    return Object.freeze({ kind: "done" })
  }
  ignore(): GraphEffect {
    return Object.freeze({ kind: "ignore" })
  }
}

// ── writing ──

function writeScalar(value: Scalar): string {
  switch (typeof value) {
    case "string":
      return JSON.stringify(value)
    case "boolean":
      return value ? "true" : "false"
    case "bigint":
      return value.toString()
    default:
      return Number.isInteger(value) ? String(value) : renderDouble(value)
  }
}

function writeProperties(properties: Properties): string {
  const fields = Object.entries(properties).map(([name, value]) => `${JSON.stringify(name)}:${Array.isArray(value) ? `[${(value as readonly Scalar[]).map(writeScalar).join(",")}]` : writeScalar(value as Scalar)}`)
  return `{${fields.join(",")}}`
}

/** The delta as the contract writes it. `labels` and `properties` are always written. */
function writeDelta(delta: Delta): string {
  const id = JSON.stringify(delta.id)
  const version = delta.version.toString()
  switch (delta.kind) {
    case "node":
      return `{"kind":"node","id":${id},"version":${version},"labels":[${delta.labels.map((l) => JSON.stringify(l)).join(",")}],"properties":${writeProperties(delta.properties)}}`
    case "edge":
      return `{"kind":"edge","id":${id},"version":${version},"type":${JSON.stringify(delta.type)},"from":${JSON.stringify(delta.from)},"to":${JSON.stringify(delta.to)},"properties":${writeProperties(delta.properties)}}`
    default:
      return delta.element === "edge"
        ? `{"kind":"tombstone","element":"edge","id":${id},"version":${version},"type":${JSON.stringify(delta.type)},"from":${JSON.stringify(delta.from)},"to":${JSON.stringify(delta.to)}}`
        : `{"kind":"tombstone","element":"node","id":${id},"version":${version}}`
  }
}

// ── reading ──

const utf8 = new TextEncoder()
const utf8Decoder = new TextDecoder("utf-8", { fatal: true })

function readScalar(name: string, raw: unknown): { kind: ScalarKind; value: Scalar } {
  const refuse = (): never => {
    throw new Error(`property '${name}' is not a scalar or array of scalars`)
  }
  switch (typeof raw) {
    case "string":
      return { kind: "string", value: raw }
    case "boolean":
      return { kind: "boolean", value: raw }
    case "bigint":
      return raw < INT64_MIN || raw > INT64_MAX ? refuse() : { kind: "integer", value: raw }
    case "number":
      if (!Number.isInteger(raw)) return { kind: "float", value: raw }
      if (Number.isSafeInteger(raw)) return { kind: "integer", value: raw }
      // A whole number written with a fraction or an exponent: an integer if it fits 64 bits.
      return raw < Number(INT64_MIN) || raw >= 2 ** 63 ? refuse() : { kind: "integer", value: BigInt(raw) }
    default:
      return refuse()
  }
}

function readProperties(raw: unknown): Properties {
  if (raw === undefined) return Object.freeze({})
  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) throw new Error("properties must be an object")
  const out: Record<string, PropertyValue> = {}
  for (const [name, value] of Object.entries(raw as Record<string, unknown>)) {
    if (RESERVED.has(name)) throw new Error(`property '${name}' is reserved`)
    if (Array.isArray(value)) {
      const read = value.map((v) => readScalar(name, v))
      if (read.length === 0 || new Set(read.map((r) => r.kind)).size > 1) throw new Error(`property '${name}' is not a scalar or array of scalars`)
      out[name] = Object.freeze(read.map((r) => r.value))
    } else {
      out[name] = readScalar(name, value).value
    }
  }
  return Object.freeze(out)
}

function readVersion(raw: unknown): Version {
  if (typeof raw === "bigint" && raw >= 0n && raw <= INT64_MAX) return raw
  if (typeof raw === "number" && Number.isInteger(raw) && raw >= 0) {
    if (Number.isSafeInteger(raw)) return raw
    if (raw < 2 ** 63) return BigInt(raw)
  }
  throw new Error("version is not a non-negative integer")
}

function readEndpoints(o: Record<string, unknown>, problem: string): { type: string; from: string; to: string } {
  const text = (v: unknown): v is string => typeof v === "string" && v !== ""
  if (!text(o.type) || !IDENTIFIER.test(o.type) || !text(o.from) || !text(o.to)) throw new Error(problem)
  return { type: o.type, from: o.from, to: o.to }
}

/**
 * Reads a delta's value back into an element, for tests and for a consumer of a delta topic. Given a
 * `key` it also holds the contract's rule that the key is the delta's element key. It accepts a
 * version of 0, which the contract allows and the builder does not write. An integer past 2⁵³ comes
 * back as a `bigint`.
 */
export function readDelta(value: Uint8Array, key?: string): Delta {
  let raw: unknown
  try {
    raw = JSON.parse(utf8Decoder.decode(value), reviver)
  } catch {
    throw new Error("not a JSON object")
  }
  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) throw new Error("not a JSON object")
  const o = raw as Record<string, unknown>
  if (o.kind === undefined) throw new Error("kind missing")
  if (o.kind !== "node" && o.kind !== "edge" && o.kind !== "tombstone") throw new Error(`unknown kind '${String(o.kind)}'`)
  if (typeof o.id !== "string" || o.id === "") throw new Error("id missing or empty")
  const id = o.id
  const version = readVersion(o.version)
  let delta: Delta
  if (o.kind === "node") {
    const labels = o.labels ?? []
    if (!Array.isArray(labels) || labels.some((l) => typeof l !== "string" || !IDENTIFIER.test(l))) throw new Error("labels must be an array of identifiers")
    delta = Object.freeze({ kind: "node", id, version, labels: Object.freeze([...(labels as string[])]), properties: readProperties(o.properties) })
  } else if (o.kind === "edge") {
    delta = Object.freeze({ kind: "edge", id, version, ...readEndpoints(o, "edge needs type, from and to"), properties: readProperties(o.properties) })
  } else if (o.element === "node") {
    delta = Object.freeze({ kind: "tombstone", element: "node", id, version })
  } else if (o.element === "edge") {
    delta = Object.freeze({ kind: "tombstone", element: "edge", id, version, ...readEndpoints(o, "tombstone of an edge needs type, from and to") })
  } else {
    throw new Error("tombstone needs element 'node' or 'edge'")
  }
  if (key !== undefined && key !== elementKey(delta)) throw new Error(`key '${key}' is not this delta's element key '${elementKey(delta)}'`)
  return delta
}

/** The codec of a delta: JSON under the manifest `ankka.graph-delta.v1`. Hand-written, since a delta's properties have no fixed shape. */
export const graphDeltaCodec: Codec<Delta> = Object.freeze({
  manifest: GRAPH_DELTA_SCHEMA,
  contentType: JSON_CONTENT,
  encode: (delta: Delta) => utf8.encode(writeDelta(checked(delta) as Delta)),
  decode: (bytes: Uint8Array) => readDelta(bytes),
})

// ── a result, resolved ──

/** One record a graph consumer's result comes to: the element key, the delta's bytes, and the delta. */
export interface DeltaRecord {
  readonly key: string
  readonly value: Uint8Array
  readonly delta: Delta
}

/**
 * What a graph consumer's effect publishes for the change `metadata` describes: every element at its
 * stated version, else the change's sequence number, refusing a result that names an element twice or
 * leaves a version to a change that has no sequence number. The server and the test kit both read an
 * effect through this, so they cannot disagree about what a handler published.
 *
 * @internal
 */
export function deltaRecords(effect: GraphEffect, metadata: Metadata): readonly DeltaRecord[] {
  if (effect.kind !== "publish") return []
  const sequence = sequenceNumberOf(metadata)
  const seen = new Set<string>()
  return effect.elements.map((given) => {
    const element = checked(given)
    const key = elementKey(element)
    if (seen.has(key)) throw new GraphError("duplicate", `${describe(element)} is in this result twice (${key}); an element is published once for a change`)
    seen.add(key)
    let version = element.version
    if (version === undefined) {
      if (sequence === undefined || sequence < 1n) {
        throw new GraphError("no-sequence", `${describe(element)}: this source has no sequence number; state a version`)
      }
      version = checkVersion(describe(element), sequence)
    }
    const delta = Object.freeze({ ...element, version }) as Delta
    return Object.freeze({ key, value: utf8.encode(writeDelta(delta)), delta })
  })
}

// ── the consumer ──

/**
 * A consumer that publishes a graph. Its handlers return elements; each is published to `producesTo`
 * as a delta under its element key. It has no `produce` and no way to set a key, and it is discovered
 * and hosted as an ordinary consumer.
 */
export abstract class GraphConsumer<M> {
  readonly graph: Graph = new Graph()
  readonly effects: GraphEffects = new GraphEffects()

  #metadata: Metadata = {}
  #client: ComponentClient | undefined

  /** The change's metadata: `ce-subject` is the source instance's id, `ankka.sequence` its sequence number. */
  get metadata(): Metadata {
    return this.#metadata
  }

  get subject(): string {
    return this.#metadata["ce-subject"] ?? ""
  }

  /** The change's sequence number, which is the version of every element that states none. */
  get sequenceNumber(): bigint | undefined {
    return sequenceNumberOf(this.#metadata)
  }

  get client(): ComponentClient {
    if (!this.#client) throw new Error("client is only available inside onMessage or onDelete")
    return this.#client
  }

  #secrets: Secrets | undefined

  /** The service's secret store: values kept encrypted in the service's own database, never in a journal or a view. */
  get secrets(): Secrets {
    return this.#secrets ?? secretsFor(this.client)
  }

  /** A unit test's store in place of the runtime's: `component.secrets = new InMemorySecrets()`. */
  set secrets(store: Secrets) {
    this.#secrets = store
  }

  #services: Services | undefined

  /** Other services, called as this one, through the runtime: `this.services.service("orders")`. */
  get services(): Services {
    return this.#services ?? servicesFor(this.client)
  }

  /** A unit test's in place of the runtime's: `component.services = new ScriptedServices()`. */
  set services(services: Services) {
    this.#services = services
  }

  /** @internal */
  get _kind(): "consumer" {
    return "consumer"
  }

  abstract onMessage(message: M): MaybePromise<GraphEffect>

  /** The source was deleted. Ignored unless this says otherwise; publish tombstones here. */
  onDelete(): MaybePromise<GraphEffect> {
    return this.effects.ignore()
  }

  /** @internal */
  _bind(metadata: Metadata, client: ComponentClient): void {
    this.#metadata = metadata
    this.#client = client
  }
}

export interface GraphConsumerClass<M = unknown, C extends GraphConsumer<M> = GraphConsumer<M>> {
  new (): C
  readonly componentId: string
  readonly source?: ComponentRef
  readonly topic?: string
  /** Where a topic source starts the first time its group reads the topic. */
  readonly startFrom?: StartFrom
  /** Raised to read the topic again from the start position, under a group of its own. Absent is 1. */
  readonly version?: number
  readonly message: Shape<M>
  /** The delta topic. It must be compacted to hold the graph; the pipeline that reads it creates it so. */
  readonly producesTo: string
}
