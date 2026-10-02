# Data Model: Graph Delta Publisher

Nothing here is a table. These are the values that cross a boundary: handler to runtime, runtime
to broker, entity to store.

## Change

What a consumer's handler is handed.

| Field | From an event sourced entity | From a key value entity | From a topic |
|---|---|---|---|
| message | the event | the state | the message |
| subject | the entity's id | the entity's id | `ce-subject`, else the record key |
| sequence number | the event's | the state's revision | 0 |
| deletion | a journalled record, at its own sequence number | a state marked deleted, at its own revision | never |

Invariant (FR-020): for one subject from an entity, sequence numbers never decrease, across
deletion and re-creation.

## Outgoing message

| Field | Rule |
|---|---|
| payload | encoded by the consumer's output serializer |
| metadata | CloudEvents attributes and other entries; `ce-subject` defaults to the change's subject |
| key | optional, non-empty; absent means the subject |

The record published: key = key, else subject; value = the encoded payload; headers = the metadata
with the platform's defaults.

## Result of a change

One of: a single message (as today); a list of outgoing messages, possibly empty; done; ignore.
A list is published in order. The change is handled when every message is accepted. At most 4 MiB
encoded.

## Element

What an author describes.

| Kind | Fields | Identity |
|---|---|---|
| node | id, labels, properties, version? | (`node`, id) |
| edge | id, type, from, to, properties, version? | (`edge`, id) |
| node tombstone | id, version? | (`node`, id) |
| edge tombstone | id, type, from, to, version? | (`edge`, id) |

Validation: [contracts/graph-builder.md](contracts/graph-builder.md). An element with no version
takes its change's sequence number when the result is returned.

## Delta

An element at a version: what is written and read. Always valid. Its record key is `node:<id>` or
`edge:<id>`. Its encoded form is `ankka.graph-delta.v1`, which ankka-flow defines.

## Element's history

For one identity, the deltas published over time. Versions rise with the owning entity's sequence.

```text
            node/edge delta (v)            tombstone (v' > v)           node/edge delta (v'' > v')
 absent ───────────────────────▶ live ───────────────────────▶ deleted ───────────────────────────▶ live
                                  │ ▲
                                  └─┘ delta (higher v)
```

A redelivered change republishes a delta at a version already published; a reader that keeps the
highest version is unmoved by it.

Ownership: every identity is written by exactly one source instance. Not enforced.

## Stored state of a key value entity

`StateRecord(manifest, payload, deleted, expiryMillis)` — unchanged in form.

| State | deleted | payload | revision |
|---|---|---|---|
| never written | no row | | |
| live | false | the state | n |
| deleted | true | the empty state (in process); absent (remote) | n + 1 |
| written again | false | the state | n + 2 |

## Fixture rows

| File | Row |
|---|---|
| `keys.json` | `{"delta": <a delta as the contract writes it>, "key": "<element key>"}` |
| `deltas.json` | the same, with `"reads": {<property>: <kind>}` — the kind the sink reads each property as: `string`, `integer`, `float`, `boolean`, or `list:<kind>` |
| `refused.json` | `{"name": "<what the row shows>", "element": <as `delta`, possibly invalid, `version` optional>, "why": "<reason>"}`; for `duplicate`, `"elements": [ … ]` in place of `element`; `"sequence": 0` where the change has no sequence number (1 otherwise) |

`why` is one of `id`, `endpoints`, `identifier`, `reserved`, `property-value`, `integer-range`,
`version`, `duplicate`, `no-sequence`. A property name that is not a string cannot be written in
JSON and is tested natively where a language allows one. A row a language's types cannot express
counts as refused there.
