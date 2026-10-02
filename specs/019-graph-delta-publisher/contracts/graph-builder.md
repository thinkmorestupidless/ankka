# Contract: the graph delta builder

One statement of the rules. The four SDKs' builders are four spellings of it:
[Scala](scala-api.md), [Python](python-api.md), [TypeScript](typescript-api.md),
[Rust](rust-api.md). The delta contract itself, `ankka.graph-delta.v1`, is ankka-flow's and is not
restated beyond what a builder needs.

## Elements

| Element | Given by the author | Written as |
|---|---|---|
| node | id; labels (may be none); properties (may be none); version (optional) | `{"kind":"node","id":…,"version":…,"labels":[…],"properties":{…}}` |
| edge | id; type; the id it runs from; the id it runs to; properties; version (optional) | `{"kind":"edge","id":…,"version":…,"type":…,"from":…,"to":…,"properties":{…}}` |
| node tombstone | id; version (optional) | `{"kind":"tombstone","element":"node","id":…,"version":…}` |
| edge tombstone | id; type; from; to; version (optional) | `{"kind":"tombstone","element":"edge","id":…,"version":…,"type":…,"from":…,"to":…}` |

`labels` and `properties` are always written, empty when there are none. Field order and number
formatting are each language's; two deltas are equal when they read back equal.

## The record

| Part | Value |
|---|---|
| key | `node:<id>` or `edge:<id>`, UTF-8. A tombstone has the key of the element it marks. Never the author's to set. |
| value | the delta, JSON, content type `application/json`, manifest `ankka.graph-delta.v1` |
| `ce-type` | `ankka.graph-delta.v1` |
| `ce-subject` | the source entity's id |

## Version

1. The version the author stated, when there is one.
2. Otherwise the sequence number of the change being handled.
3. A version below 1 is refused. When it came from rule 2 the message says the source has no
   sequence number and a version must be stated.

All elements of one result that state no version share the change's.

## Refused: faults of one element

Raised where the element is built in Scala, Python and TypeScript. In Rust the builder methods are
infallible and chainable, and the same faults are raised when the result is dispatched. Either
way it is before anything is published.

| Fault | `why` in `refused.json` |
|---|---|
| id empty, or not a string | `id` |
| an edge's type, from or to missing or empty | `endpoints` |
| a label, or an edge's type, that is not `[A-Za-z_][A-Za-z0-9_]*` | `identifier` |
| a property named `id`, `_version` or `_deleted` | `reserved` |
| a property name that is not a string | (tested natively, where a language allows one) |
| a property value that is null, an object, an empty list, a list of more than one kind, or a list containing a list | `property-value` |
| an integer, or a float with a whole value, that does not fit 64 bits, signed | `integer-range` |
| a float that is not finite | (tested natively; JSON cannot carry it) |
| a stated version that is not a whole number of at least 1 within 64 bits | `version` |

Kinds of scalar: string, boolean, integer, float. A float whose value is whole counts as an
integer for the one-kind-per-list rule. In TypeScript an integral `number` must be a safe integer;
larger integers are `bigint`.

## Refused: faults of the result

| Fault | `why` |
|---|---|
| the same kind and id twice in one result | `duplicate` |
| no stated version and the change's sequence number is below 1 | `no-sequence` |

A refusal is an error raised in the handler. The change is not handled and is delivered again; no
part of the result is published.

## Reading

Each SDK reads a record's value, and optionally its key, back into an element: for tests, and for
a consumer of a delta topic. The reader applies the contract's own validation and, given a key,
the rule that it must be the delta's element key. It accepts a version of 0, which the contract
allows and the builder does not write.

## Fixtures

`protocol/fixtures/graph-deltas/`, copied into every SDK with the rest of `protocol/fixtures`:

| File | Rows | Every SDK |
|---|---|---|
| `keys.json` (ankka-flow's) | `{delta, key}` | builds the element from `delta`'s fields, publishes it through its test kit, and gets `key` and a value that reads back equal to `delta` |
| `deltas.json` (ankka-flow's) | `{delta, key}`, every property kind | the same |
| `refused.json` | `{name, element, why}`, and for `duplicate` a list of elements | is refused, for the reason `why` names |
| `SOURCE.md` | which files are copies, from which ankka-flow tag | — |

## The cart graph

The graph consumer every SDK's shopping cart example carries, and conformance's `cart-graph`:

| Change to cart `<id>` | Elements published, at the change's sequence number |
|---|---|
| `ItemAdded`, `ItemRemoved` | node `cart:<id>`, labels `[Cart]`, properties `{cartId: <id>, checkedOut: false}` |
| `CheckedOut` | node `cart:<id>`, `[Cart]`, `{cartId: <id>, checkedOut: true}`; node `checkout:<id>`, `[Checkout]`, `{cartId: <id>}`; edge `checked-out:<id>`, type `CHECKED_OUT`, from `cart:<id>` to `checkout:<id>`, no properties |
| `Discarded` | nothing: ignore |
| deletion | tombstone for node `cart:<id>` |

The scripted history for `consumer.graph-deltas`: add an item (1), add another (2), remove one (3),
check out (4). Expected records, in order per key:

| Key | Versions |
|---|---|
| `node:cart:<id>` | 1, 2, 3, 4 (`checkedOut` true only at 4) |
| `node:checkout:<id>` | 4 |
| `edge:checked-out:<id>` | 4 |
