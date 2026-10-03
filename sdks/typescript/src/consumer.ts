// A consumer: reacts to a source's changes, optionally producing onward to a topic.

import type { Shape } from "./codec.ts"
import type { Metadata } from "./effects/common.ts"
import type { ComponentClient, ComponentRef } from "./client.ts"
import { ConsumerEffects, type ConsumerEffect } from "./effects/stateless.ts"

type MaybePromise<T> = T | Promise<T>

/** The metadata entry a runtime sets on a consumer's request to say what it speaks: its protocol version. */
export const PROTOCOL_KEY = "ankka.protocol"

/** Thrown when a consumer answers with several messages, or a record key, to a runtime that would drop them. */
export class ProtocolVersionError extends Error {
  constructor(declared: string | undefined) {
    super(`this runtime speaks protocol ${declared ?? "1.2 or earlier"}; several messages or a record key need 1.3`)
    this.name = "ProtocolVersionError"
  }
}

/**
 * Refuses to answer with several messages unless the request says the runtime accepts them. A runtime
 * before protocol 1.3 reads the reply as no effect at all and records the change as handled with
 * nothing published, so the request is failed instead: the change is delivered again, visibly.
 */
export function requireSeveralMessages(metadata: Metadata): void {
  const declared = metadata[PROTOCOL_KEY]
  const match = declared === undefined ? null : /^(\d+)\.(\d+)$/.exec(declared.trim())
  const accepted = match !== null && (Number(match[1]) > 1 || (Number(match[1]) === 1 && Number(match[2]) >= 3))
  if (!accepted) throw new ProtocolVersionError(declared)
}

/** The change's sequence number from its metadata: an event's, a key value state's revision; absent when there is none. */
export function sequenceNumberOf(metadata: Metadata): bigint | undefined {
  const raw = metadata["ankka.sequence"]
  return raw !== undefined && /^-?\d+$/.test(raw) ? BigInt(raw) : undefined
}

export abstract class Consumer<M, Out = never> {
  readonly effects: ConsumerEffects<Out> = new ConsumerEffects<Out>()

  #metadata: Metadata = {}
  #client: ComponentClient | undefined

  /** The message's metadata: `ce-subject` is the source instance's id, `ankka.sequence` its sequence number. */
  get metadata(): Metadata {
    return this.#metadata
  }

  get subject(): string {
    return this.#metadata["ce-subject"] ?? ""
  }

  /** The change's sequence number: an event's, or a key value state's revision. `0n` for a topic's message. */
  get sequenceNumber(): bigint | undefined {
    return sequenceNumberOf(this.#metadata)
  }

  get client(): ComponentClient {
    if (!this.#client) throw new Error("client is only available inside onMessage or onDelete")
    return this.#client
  }

  /** @internal */
  get _kind(): "consumer" {
    return "consumer"
  }

  abstract onMessage(message: M): MaybePromise<ConsumerEffect<Out>>

  /** The source was deleted. Ignored unless this says otherwise. */
  onDelete(): MaybePromise<ConsumerEffect<Out>> {
    return this.effects.ignore()
  }

  /** @internal */
  _bind(metadata: Metadata, client: ComponentClient): void {
    this.#metadata = metadata
    this.#client = client
  }
}

export interface ConsumerClass<M = unknown, Out = unknown, C extends Consumer<M, Out> = Consumer<M, Out>> {
  new (): C
  readonly componentId: string
  readonly source?: ComponentRef
  readonly topic?: string
  readonly message: Shape<M>
  /** The shape of what `produce` sends onward; required with `producesTo`. */
  readonly out?: Shape<Out>
  readonly producesTo?: string
}
