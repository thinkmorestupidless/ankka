# Contract: the TypeScript surface

Package `ankka`. The rules behind the graph types are in [graph-builder.md](graph-builder.md); the
wire and the guard on the runtime's version are in [protocol.md](protocol.md).

## Several messages

```ts
export interface OutgoingMessage<Out> {
  readonly payload: Out
  readonly key?: string            // "" throws
  readonly metadata?: Metadata
}

export type ConsumerEffect<Out> =
  | { readonly kind: "produce"; readonly payload: Out; readonly metadata: Metadata }      // unchanged
  | { readonly kind: "produceAll"; readonly messages: readonly OutgoingMessage<Out>[] }   // new
  | { readonly kind: "done" }
  | { readonly kind: "ignore" }

class ConsumerEffects<Out> {
  produce(payload: Out, metadata?: Metadata): ConsumerEffect<Out>             // unchanged
  produceAll(messages: readonly OutgoingMessage<Out>[]): ConsumerEffect<Out>  // empty: as done
  done(): ConsumerEffect<Out>
  ignore(): ConsumerEffect<Out>
}

abstract class Consumer<M, Out = never> {
  get sequenceNumber(): bigint | undefined     // new: `ankka.sequence`
  // subject, metadata, client, effects, onMessage, onDelete: unchanged
}
```

`handleConsumer` replies `produceAll` after the guard: with `ankka.protocol` absent or below 1.3
it throws, and the request fails with `Code.Internal` and the message of the contract.

## Publishing a graph

```ts
export const GRAPH_DELTA_SCHEMA = "ankka.graph-delta.v1"
export function nodeKey(id: string): string
export function edgeKey(id: string): string

export type Scalar = string | boolean | number | bigint
export type PropertyValue = Scalar | readonly Scalar[]
export type Properties = Readonly<Record<string, PropertyValue>>

export type Element =
  | { readonly kind: "node"; readonly id: string; readonly version?: number | bigint
      readonly labels: readonly string[]; readonly properties: Properties }
  | { readonly kind: "edge"; readonly id: string; readonly version?: number | bigint
      readonly type: string; readonly from: string; readonly to: string; readonly properties: Properties }
  | { readonly kind: "tombstone"; readonly element: "node"; readonly id: string; readonly version?: number | bigint }
  | { readonly kind: "tombstone"; readonly element: "edge"; readonly id: string; readonly version?: number | bigint
      readonly type: string; readonly from: string; readonly to: string }

class Graph {                                   // `this.graph` in a GraphConsumer
  node(id: string, options?: { labels?: readonly string[]; properties?: Properties; version?: number | bigint }): Element
  edge(id: string, options: { type: string; from: string; to: string; properties?: Properties; version?: number | bigint }): Element
  tombstoneNode(id: string, options?: { version?: number | bigint }): Element
  tombstoneEdge(id: string, options: { type: string; from: string; to: string; version?: number | bigint }): Element
}

export type GraphEffect =
  | { readonly kind: "publish"; readonly elements: readonly Element[] }
  | { readonly kind: "done" } | { readonly kind: "ignore" }

class GraphEffects {
  publish(elements: readonly Element[]): GraphEffect      // empty: as done
  done(): GraphEffect
  ignore(): GraphEffect
}

export abstract class GraphConsumer<M> {
  readonly graph: Graph
  readonly effects: GraphEffects
  get subject(): string
  get sequenceNumber(): bigint | undefined
  get metadata(): Metadata
  get client(): ComponentClient
  abstract onMessage(message: M): MaybePromise<GraphEffect>
  onDelete(): MaybePromise<GraphEffect>         // default: ignore
}
// statics: componentId, source? | topic?, message: Shape<M>, producesTo: string

export function readDelta(value: Uint8Array, key?: string): Element    // throws, naming the fault
```

```ts
export class CartGraph extends GraphConsumer<CartEvent> {
  static readonly componentId = "cart-graph"
  static readonly source = ShoppingCartEntity
  static readonly message = jsonCodec(CartEvent)
  static readonly producesTo = "cart-graph"

  onMessage(event: CartEvent) {
    const id = this.subject
    switch (event.type) {
      case "ItemAdded":
      case "ItemRemoved":
        return this.effects.publish([this.cart(id, false)])
      case "CheckedOut":
        return this.effects.publish([
          this.cart(id, true),
          this.graph.node(`checkout:${id}`, { labels: ["Checkout"], properties: { cartId: id } }),
          this.graph.edge(`checked-out:${id}`, { type: "CHECKED_OUT", from: `cart:${id}`, to: `checkout:${id}` }),
        ])
      default:
        return this.effects.ignore()
    }
  }

  onDelete() {
    return this.effects.publish([this.graph.tombstoneNode(`cart:${this.subject}`)])
  }

  private cart(id: string, checkedOut: boolean) {
    return this.graph.node(`cart:${id}`, { labels: ["Cart"], properties: { cartId: id, checkedOut } })
  }
}
```

Registered with `.register(CartGraph)`; discovered as a consumer with `producesTo`. Numbers: an
integral `number` must be a safe integer and is written as an integer; a non-integral `number` is
a float; a `bigint` must fit 64 bits. A version is a safe-integer `number` or a `bigint`, at
least 1. The reader returns integers beyond 2⁵³ as `bigint`. The delta's codec is a hand-written
`Codec`, since a delta's properties have no fixed shape.

## Testing — `ankka/testkit`

```ts
class ConsumerTestKit<M, Out> {
  onMessage(message: M, subject?: string, metadata?: Metadata): Promise<ConsumerEffect<Out>>   // unchanged
  onDelete(subject?: string, metadata?: Metadata): Promise<ConsumerEffect<Out>>
  produced: { payload: Out; metadata: Metadata; key?: string }[]      // `key` is new; one entry per message
}

class GraphConsumerTestKit<M> {
  static of<M>(cls: GraphConsumerClass<M>, client?: ComponentClient): GraphConsumerTestKit<M>
  onMessage(message: M, options?: { subject?: string; sequence?: number | bigint }): Promise<Element[]>
  onDelete(options?: { subject?: string; sequence?: number | bigint }): Promise<Element[]>
}
```

The kits set `ankka.protocol` to the SDK's own version.

## As built

Where the SDK settled what this contract left open, or went further:

- A refusal is `GraphError`, with `why`, the reason's name as `refused.json` has it. An integral
  `number` between 2⁵³ and 2⁶³ is refused as `integer-range`, saying to pass a `bigint`.
- `graphDeltaCodec` and `elementKey` are exported; `readDelta` returns a `Delta`, an element with
  its version. With no `key` argument it makes no key check.
- An element is validated where `Graph` builds it and again when a result is resolved, because
  `Element` is a public union an author can write by hand.
- A graph consumer that declares a static `out` is refused at registration.
- An empty list is answered as `done`; the guard fails the request with `Code.Internal` and the
  contract's message.
- `GraphConsumerTestKit`'s options also take `metadata`.
