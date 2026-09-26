// A consumer: reacts to a source's changes, optionally producing onward to a topic.

import type { Shape } from "./codec.ts"
import type { Metadata } from "./effects/common.ts"
import type { ComponentClient, ComponentRef } from "./client.ts"
import { ConsumerEffects, type ConsumerEffect } from "./effects/stateless.ts"

type MaybePromise<T> = T | Promise<T>

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
