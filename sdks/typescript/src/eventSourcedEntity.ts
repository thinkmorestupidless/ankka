// An event sourced entity: state derived from a journal of events. The class declares its identity and
// codecs as statics and its handlers in a static table; the instance holds the state for one command.
//
//   export class ShoppingCartEntity extends EventSourcedEntity<ShoppingCart, ShoppingCartEvent> {
//     static readonly componentId = "shopping-cart"
//     static readonly state = jsonCodec(ShoppingCart, "shopping-cart")
//     static readonly events = jsonCodec(ShoppingCartEvent, "shopping-cart-event")
//     static readonly handlers = { addItem: command("add-item", LineItem, Done, (cart: ShoppingCartEntity, item) => cart.addItem(item)) }
//     emptyState() { ... }   applyEvent(state, event) { ... }   addItem(item) { return this.effects.persist(...).thenReply(() => done) }
//   }

import type { Shape } from "./codec.ts"
import type { CommandContext } from "./context.ts"
import type { HandlerTable } from "./handlers.ts"
import type { ComponentClient } from "./client.ts"
import { EventSourcedEffects } from "./effects/eventSourced.ts"

export abstract class EventSourcedEntity<S, E> {
  /** The effect builders: `this.effects.persist(...)`, `.reply(...)`, `.error(...)`. */
  readonly effects: EventSourcedEffects<S, E> = new EventSourcedEffects<S, E>()

  #state: S | undefined
  #entityId: string | undefined
  #context: CommandContext | undefined
  #client: ComponentClient | undefined
  #bound = false

  /** The state the entity has now, inside a handler. */
  get state(): S {
    if (!this.#bound) throw new Error("state is only available inside a command handler")
    return this.#state as S
  }

  /** The id of the instance this object stands for; available in `emptyState` and in handlers. */
  get entityId(): string {
    if (this.#entityId === undefined) throw new Error("entityId is only available once the entity is bound to an instance")
    return this.#entityId
  }

  /** The command's context: component id, sequence number, metadata, the clock. */
  get context(): CommandContext {
    if (!this.#context) throw new Error("context is only available inside a command handler")
    return this.#context
  }

  /** The component client, scoped to the command's trace. */
  get client(): ComponentClient {
    if (!this.#client) throw new Error("client is only available inside a command handler")
    return this.#client
  }

  /** @internal The kind, read off the prototype by the typed client and the timers. */
  get _kind(): "event-sourced" {
    return "event-sourced"
  }

  /** The state of an instance with no events yet. `this.entityId` is available here. */
  abstract emptyState(): S

  /** The fold: the state after `event`, given the state before it. Must not read `this.state`. */
  abstract applyEvent(state: S, event: E): S

  /** @internal Bound by the runtime and the testkit before `emptyState`. */
  _bindInstance(entityId: string): void {
    this.#entityId = entityId
  }

  /** @internal Bound by the runtime and the testkit for the duration of one handler call. */
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

/** The statics an event sourced entity class must declare; `register` constrains on this type. */
export interface EventSourcedEntityClass<S = unknown, E = unknown, C extends EventSourcedEntity<S, E> = EventSourcedEntity<S, E>> {
  new (): C
  readonly componentId: string
  readonly state: Shape<S>
  readonly events: Shape<E>
  readonly handlers: HandlerTable<C>
  /** Ask for a snapshot every N events; absent or 0: never. */
  readonly snapshotEvery?: number
}
