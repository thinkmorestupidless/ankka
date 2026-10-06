// A keyed view: several entities' changes, each through a handler of its own, written to whichever rows
// a change is about, by key. The sidecar runs one projection per source and handles one change at a
// time across all of them; this process only says which rows each change writes.
//
//   export class Shipments extends KeyedView<ShipmentRow> {
//     static readonly componentId = "shipments"
//     static readonly row = ShipmentRowShape
//     static readonly sources = [
//       on(ShipmentEntity, ShipmentEvents, (v: Shipments, e) => v.onShipment(e)),
//       on(CustomerEntity, CustomerEvents, (v: Shipments, e) => v.onCustomer(e)),
//     ]
//     static readonly declared = [declaredQuery("of-customer", `SELECT payload FROM ${tableOf("shipments")} WHERE ...`)]
//     async onCustomer(event: CustomerEvent) {
//       const theirs = await this.rows.ask("of-customer", { customer: this.subject })
//       return this.effects.updateRows(theirs.map((r) => [r.shipmentId, { ...r, name: event.name }]))
//     }
//   }

import type { Shape } from "./codec.ts"
import type { Metadata } from "./effects/common.ts"
import type { ComponentClient, ComponentRef } from "./client.ts"
import { KeyedViewEffects, type KeyedViewEffect } from "./effects/keyed.ts"
import type { DeclaredQuery } from "./view.ts"

type MaybePromise<T> = T | Promise<T>

/** A keyed view's own rows, for the length of one change. It reaches no other view. */
export interface ViewRows<Row> {
  /** The row under `key`, or `null`. */
  get(key: string): Promise<Row | null>
  /** The rows of one of this view's declared queries, asked with its values. */
  ask(name: string, values?: Readonly<Record<string, string>>): Promise<Row[]>
}

/** One source of a keyed view, and what the view does with each of its changes and with its entity's deletion. */
export interface KeyedSource<V = any, E = any> {
  readonly source: ComponentRef
  readonly events: Shape<E>
  readonly onChange: (view: V, event: E) => MaybePromise<KeyedViewEffect<any>>
  readonly deleted?: (view: V) => MaybePromise<KeyedViewEffect<any>>
}

/**
 * Declares a source of a keyed view: the entity whose changes it reads, the shape of those changes, and
 * the handler for each. When the entity is deleted the view does nothing unless `deleted` names rows.
 */
export function on<V, E>(
  source: ComponentRef,
  events: Shape<E>,
  onChange: (view: V, event: E) => MaybePromise<KeyedViewEffect<any>>,
  options: { readonly deleted?: (view: V) => MaybePromise<KeyedViewEffect<any>> } = {},
): KeyedSource<V, E> {
  return Object.freeze({ source, events, onChange, ...(options.deleted ? { deleted: options.deleted } : {}) })
}

export abstract class KeyedView<Row> {
  readonly effects: KeyedViewEffects<Row> = new KeyedViewEffects<Row>()

  #metadata: Metadata = {}
  #client: ComponentClient | undefined
  #rows: ViewRows<Row> | undefined

  /** The change's metadata: `ce-subject` is the source entity's id, `ankka.sequence` its sequence number. */
  get metadata(): Metadata {
    return this.#metadata
  }

  /** The id of the entity the change came from. */
  get subject(): string {
    return this.#metadata["ce-subject"] ?? ""
  }

  /** This view's own rows, by key or by one of its declared queries. */
  get rows(): ViewRows<Row> {
    if (!this.#rows) throw new Error("rows are only available inside a keyed view's handler")
    return this.#rows
  }

  get client(): ComponentClient {
    if (!this.#client) throw new Error("client is only available inside a keyed view's handler")
    return this.#client
  }

  /** @internal */
  get _kind(): "view" {
    return "view"
  }

  /** @internal */
  _bind(metadata: Metadata, client: ComponentClient | undefined, rows: ViewRows<Row>): void {
    this.#metadata = metadata
    this.#client = client
    this.#rows = rows
  }
}

export interface KeyedViewClass<Row = unknown, C extends KeyedView<Row> = KeyedView<Row>> {
  new (): C
  readonly componentId: string
  readonly row: Shape<Row>
  /** One per entity the view reads, declared with `on(...)`. */
  readonly sources: readonly KeyedSource<C, any>[]
  /** Raised to have the view emptied and every source read again from its beginning. Absent is 1. */
  readonly version?: number
  /** The queries the view can be asked by name, by its own handlers among others. */
  readonly declared?: readonly DeclaredQuery[]
}

/** The rows a keyed view reads through the component client, on its own id. */
export function clientRows<Row>(client: ComponentClient, viewId: string, row: Shape<Row>): ViewRows<Row> {
  return {
    get: (key) => client.views.get(viewId, key, row),
    ask: (name, values = {}) => client.views.ask(viewId, name, values, row),
  }
}
