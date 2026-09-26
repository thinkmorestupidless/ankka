// A view: a queryable projection of a source's changes. The sidecar runs the projection and stores the
// rows; this process only says what each event does to a row.
//
//   export class CartRows extends View<ShoppingCartEvent, CartRow> {
//     static readonly componentId = "cart-rows"
//     static readonly source = ShoppingCartEntity
//     static readonly events = ShoppingCartEntity.events
//     static readonly row = jsonCodec(CartRow, "cart-row")
//     onChange(event) { ... return this.effects.updateRow(...) }
//   }

import type { Shape } from "./codec.ts"
import type { Metadata } from "./effects/common.ts"
import type { ComponentClient } from "./client.ts"
import type { ComponentRef } from "./client.ts"
import { ViewEffects, type ViewEffect } from "./effects/stateless.ts"

type MaybePromise<T> = T | Promise<T>

export abstract class View<E, Row> {
  readonly effects: ViewEffects<Row> = new ViewEffects<Row>()

  #row: Row | null = null
  #metadata: Metadata = {}
  #client: ComponentClient | undefined
  #bound = false

  /** The current row, or `null` when there is none yet. */
  get row(): Row | null {
    if (!this.#bound) throw new Error("row is only available inside onChange or onDelete")
    return this.#row
  }

  /** The change's metadata: `ce-subject` is the source instance's id, `ankka.sequence` its sequence number. */
  get metadata(): Metadata {
    return this.#metadata
  }

  /** The id of the source instance the change came from. */
  get subject(): string {
    return this.#metadata["ce-subject"] ?? ""
  }

  get client(): ComponentClient {
    if (!this.#client) throw new Error("client is only available inside onChange or onDelete")
    return this.#client
  }

  /** @internal */
  get _kind(): "view" {
    return "view"
  }

  /** What `event` does to the row. */
  abstract onChange(event: E): MaybePromise<ViewEffect<Row>>

  /** The source was deleted. The row goes with it unless this says otherwise — an order history keeps a checked-out cart's row. */
  onDelete(): MaybePromise<ViewEffect<Row>> {
    return this.effects.deleteRow()
  }

  /** @internal */
  _bind(row: Row | null, metadata: Metadata, client: ComponentClient): void {
    this.#row = row
    this.#metadata = metadata
    this.#client = client
    this.#bound = true
  }
}

export interface ViewClass<E = unknown, Row = unknown, C extends View<E, Row> = View<E, Row>> {
  new (): C
  readonly componentId: string
  /** The component whose changes feed the view, or `topic`. */
  readonly source?: ComponentRef
  readonly topic?: string
  readonly events: Shape<E>
  readonly row: Shape<Row>
  /** The query names the view answers; `get` and `all` by default. */
  readonly queries?: readonly string[]
}
