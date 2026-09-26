// Handler declarations: the entries of a component's static `handlers`, `steps`, `actions`, `tools`
// and `guardrails` tables. Each carries the wire name, the input and reply schemas, and the function
// that runs it. The wire name is declared here and nowhere else; renaming the method it calls changes
// nothing on the wire. These are plain values, so the registry can read them without reflection.

import type { Shape } from "./codec.ts"
import type { EffectLike, ReadOnlyLike } from "./effects/common.ts"

export type HandlerKind = "command" | "query" | "stream" | "step" | "action"

type MaybePromise<T> = T | Promise<T>

/** One entry of a handler table. `C` is the component class, `I` the input, `R` the reply. */
export interface HandlerRef<C = unknown, I = unknown, R = unknown, E extends EffectLike = EffectLike> {
  readonly kind: HandlerKind
  /** The wire name. */
  readonly name: string
  readonly input: Shape<I> | undefined
  readonly reply: Shape<R> | undefined
  readonly readOnly: boolean
  readonly streaming: boolean
  readonly run: (self: C, input: I) => MaybePromise<E>
}

function ref<C, I, R, E extends EffectLike>(
  kind: HandlerKind,
  name: string,
  input: Shape<I> | undefined,
  reply: Shape<R> | undefined,
  run: (self: C, input: I) => MaybePromise<E>,
  flags: { readOnly: boolean; streaming: boolean },
): HandlerRef<C, I, R, E> {
  if (typeof name !== "string" || name.trim() === "") throw new TypeError(`a ${kind} needs a wire name`)
  if (typeof run !== "function") throw new TypeError(`${kind} ${name}: the handler is not a function`)
  return Object.freeze({ kind, name, input, reply, run, ...flags })
}

/**
 * A command handler: may persist, update, transition or refuse. `command("add-item", LineItem, Done, (e, item) => e.addItem(item))`,
 * or without an input: `command("checkout", Done, (e) => e.checkout())`.
 */
export function command<C, I, R, E extends EffectLike<R>>(
  name: string,
  input: Shape<I>,
  reply: Shape<R>,
  run: (self: C, input: I) => MaybePromise<E>,
): HandlerRef<C, I, R, E>
export function command<C, R, E extends EffectLike<R>>(name: string, reply: Shape<R>, run: (self: C) => MaybePromise<E>): HandlerRef<C, undefined, R, E>
export function command(name: string, ...rest: unknown[]): HandlerRef<any, any, any, any> {
  if (rest.length === 3) {
    const [input, reply, run] = rest as [Shape<unknown>, Shape<unknown>, HandlerRef["run"]]
    return ref("command", name, input, reply, run, { readOnly: false, streaming: false })
  }
  const [reply, run] = rest as [Shape<unknown>, HandlerRef["run"]]
  return ref("command", name, undefined, reply, run, { readOnly: false, streaming: false })
}

/**
 * A query handler: its function must return a read-only effect, so a query that persists does not
 * compile. Registration checks the effect's `kind` again at runtime, and the sidecar refuses events
 * from a read-only handler regardless.
 */
export function query<C, I, R, E extends ReadOnlyLike<R>>(
  name: string,
  input: Shape<I>,
  reply: Shape<R>,
  run: (self: C, input: I) => MaybePromise<E>,
): HandlerRef<C, I, R, E>
export function query<C, R, E extends ReadOnlyLike<R>>(name: string, reply: Shape<R>, run: (self: C) => MaybePromise<E>): HandlerRef<C, undefined, R, E>
export function query(name: string, ...rest: unknown[]): HandlerRef<any, any, any, any> {
  if (rest.length === 3) {
    const [input, reply, run] = rest as [Shape<unknown>, Shape<unknown>, HandlerRef["run"]]
    return ref("query", name, input, reply, run, { readOnly: true, streaming: false })
  }
  const [reply, run] = rest as [Shape<unknown>, HandlerRef["run"]]
  return ref("query", name, undefined, reply, run, { readOnly: true, streaming: false })
}

/** An agent handler whose reply streams as tokens: `stream("stream", s.string, (a, q) => a.ask(q))`. */
export function stream<C, I, E extends EffectLike>(name: string, input: Shape<I>, run: (self: C, input: I) => MaybePromise<E>): HandlerRef<C, I, string, E>
export function stream<C, E extends EffectLike>(name: string, run: (self: C) => MaybePromise<E>): HandlerRef<C, undefined, string, E>
export function stream(name: string, ...rest: unknown[]): HandlerRef<any, any, any, any> {
  if (rest.length === 2) {
    const [input, run] = rest as [Shape<unknown>, HandlerRef["run"]]
    return ref("stream", name, input, undefined, run, { readOnly: false, streaming: true })
  }
  const [run] = rest as [HandlerRef["run"]]
  return ref("stream", name, undefined, undefined, run, { readOnly: false, streaming: true })
}

/** A workflow step: `step("charge", s.int, (w, quantity) => w.charge(quantity))` or `step("reserve", (w) => w.reserve())`. */
export function step<C, I, E extends EffectLike>(name: string, input: Shape<I>, run: (self: C, input: I) => MaybePromise<E>): HandlerRef<C, I, undefined, E>
export function step<C, E extends EffectLike>(name: string, run: (self: C) => MaybePromise<E>): HandlerRef<C, undefined, undefined, E>
export function step(name: string, ...rest: unknown[]): HandlerRef<any, any, any, any> {
  if (rest.length === 2) {
    const [input, run] = rest as [Shape<unknown>, HandlerRef["run"]]
    return ref("step", name, input, undefined, run, { readOnly: false, streaming: false })
  }
  const [run] = rest as [HandlerRef["run"]]
  return ref("step", name, undefined, undefined, run, { readOnly: false, streaming: false })
}

/** A timed action: `action("remind", s.string, (t, id) => t.remind(id))`. */
export function action<C, I, E extends EffectLike>(name: string, input: Shape<I>, run: (self: C, input: I) => MaybePromise<E>): HandlerRef<C, I, undefined, E>
export function action<C, E extends EffectLike>(name: string, run: (self: C) => MaybePromise<E>): HandlerRef<C, undefined, undefined, E>
export function action(name: string, ...rest: unknown[]): HandlerRef<any, any, any, any> {
  if (rest.length === 2) {
    const [input, run] = rest as [Shape<unknown>, HandlerRef["run"]]
    return ref("action", name, input, undefined, run, { readOnly: false, streaming: false })
  }
  const [run] = rest as [HandlerRef["run"]]
  return ref("action", name, undefined, undefined, run, { readOnly: false, streaming: false })
}

/** An agent tool: the model sees `description` and the JSON Schema of `input`; the process runs `run`. */
export interface ToolRef<C = unknown, I = unknown> {
  readonly name: string
  readonly description: string
  readonly input: Shape<I>
  readonly run: (self: C, input: I) => MaybePromise<unknown>
}

export function tool<C, I>(name: string, description: string, input: Shape<I>, run: (self: C, input: I) => MaybePromise<unknown>): ToolRef<C, I> {
  if (typeof name !== "string" || name.trim() === "") throw new TypeError("a tool needs a name")
  if (typeof description !== "string" || description.trim() === "") {
    throw new TypeError(`tool ${name}: a tool needs a description, or the model cannot choose it`)
  }
  return Object.freeze({ name, description, input, run })
}

export type GuardrailStage = "input" | "output"

/** An agent guardrail: returns a reason to block, or `null` to let the text through. */
export interface GuardrailRef {
  readonly name: string
  readonly check: (stage: GuardrailStage, text: string) => MaybePromise<string | null>
}

export function guardrail(name: string, check: (stage: GuardrailStage, text: string) => MaybePromise<string | null>): GuardrailRef {
  if (typeof name !== "string" || name.trim() === "") throw new TypeError("a guardrail needs a name")
  return Object.freeze({ name, check })
}

/** The handler table type a component declares: property names are the developer's, `name` is the wire's. */
export type HandlerTable<C> = Readonly<Record<string, HandlerRef<C, any, any, any>>>
