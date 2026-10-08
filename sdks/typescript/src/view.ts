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

import type { StartFrom } from "./startFrom.ts"
import type { Contract } from "./contract.ts"
import type { Shape } from "./codec.ts"
import type { Metadata } from "./effects/common.ts"
import type { ComponentClient } from "./client.ts"
import type { ComponentRef } from "./client.ts"
import { ViewEffects, type ViewEffect } from "./effects/stateless.ts"

type MaybePromise<T> = T | Promise<T>

/**
 * A question a view can be asked by name: one SQL statement over the view's own table, whose values
 * are the `:name`s it holds. The platform checks the statement when the service starts — one that is
 * not a single read of the view's own table stops the service, naming the view and the query — and
 * binds each value as a parameter, never as part of the text. The answer is rows of the view's row
 * shape, read from the statement's `payload` column.
 */
export interface DeclaredQuery {
  readonly name: string
  readonly statement: string
}

/** Declares a query for a view's `static declared`. The SDK sends the statement as written. */
export function declaredQuery(name: string, statement: string): DeclaredQuery {
  return Object.freeze({ name, statement })
}

/** The table a view's rows are kept in, for a declared query's statement to name. */
export function tableOf(componentId: string): string {
  return "ankka_view_" + componentId.replace(/[^A-Za-z0-9]/g, "_")
}

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
  /** Where a topic source starts the first time its group reads the topic. */
  readonly startFrom?: StartFrom
  /**
   * Raised to have the view emptied and read again: a topic from its start position, under a group of
   * its own, as far back as the broker retains; an entity from its first event or state. Absent is 1.
   */
  readonly version?: number
  /** The contract the topic read is expected to carry, checked at start against the project's declaration. */
  readonly contract?: Contract
  /** The declared broker the topic read is on; absent is the installation's. */
  readonly broker?: string
  /** Whether the partitions an instance holds are handled at once, each in order. */
  readonly parallel?: boolean
  readonly events: Shape<E>
  readonly row: Shape<Row>
  /** The query names the view answers; `get` and `all` by default. */
  readonly queries?: readonly string[]
  /** The queries the view can be asked by name, each a statement over its own table (protocol 1.13). */
  readonly declared?: readonly DeclaredQuery[]
}
