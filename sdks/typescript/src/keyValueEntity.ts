// A key value entity: one durable state per instance, replaced whole by `updateState`.

import type { Shape } from "./codec.ts"
import type { CommandContext } from "./context.ts"
import type { HandlerTable } from "./handlers.ts"
import type { ComponentClient } from "./client.ts"
import { KeyValueEffects } from "./effects/keyValue.ts"

export abstract class KeyValueEntity<S> {
  readonly effects: KeyValueEffects<S> = new KeyValueEffects<S>()

  #state: S | undefined
  #entityId: string | undefined
  #context: CommandContext | undefined
  #client: ComponentClient | undefined
  #bound = false

  get state(): S {
    if (!this.#bound) throw new Error("state is only available inside a command handler")
    return this.#state as S
  }

  get entityId(): string {
    if (this.#entityId === undefined) throw new Error("entityId is only available once the entity is bound to an instance")
    return this.#entityId
  }

  get context(): CommandContext {
    if (!this.#context) throw new Error("context is only available inside a command handler")
    return this.#context
  }

  get client(): ComponentClient {
    if (!this.#client) throw new Error("client is only available inside a command handler")
    return this.#client
  }

  /** @internal */
  get _kind(): "key-value" {
    return "key-value"
  }

  /** The state of an instance that has never been written. `this.entityId` is available here. */
  abstract emptyState(): S

  /** @internal */
  _bindInstance(entityId: string): void {
    this.#entityId = entityId
  }

  /** @internal */
  _bindCommand(state: S, context: CommandContext, client: ComponentClient): void {
    this.#state = state
    this.#context = context
    this.#client = client
    this.#bound = true
  }

  /** @internal */
  _unbindCommand(): void {
    this.#state = undefined
    this.#context = undefined
    this.#client = undefined
    this.#bound = false
  }
}

export interface KeyValueEntityClass<S = unknown, C extends KeyValueEntity<S> = KeyValueEntity<S>> {
  new (): C
  readonly componentId: string
  readonly state: Shape<S>
  readonly handlers: HandlerTable<C>
}
