# Contract: the wire, the runtime's rules and conformance

Protocol **1.3**. Everything here is additive to 1.2.

## `consumer.proto`

```protobuf
message ConsumerEffect {
  oneof effect {
    Produce produce = 1;
    Empty done = 2;
    Empty ignore = 3;
    ProduceAll produce_all = 4;          // 1.3
  }
  message Produce { Payload payload = 1; Metadata metadata = 2; }   // unchanged
  message ProduceAll { repeated Message messages = 1; }             // 1.3
  message Message {                                                 // 1.3
    Payload payload = 1;
    Metadata metadata = 2;
    optional string key = 3;             // the record key; absent: the message's subject
  }
}
```

`ConsumerRequest`, the `Consumer` service, discovery and the WebAssembly export `ankka1_consumer`
are unchanged. `Protocol.version`, `protocol/README.md` and each SDK's `PROTOCOL_VERSION` read
`1.3`.

## What the runtime does with a reply

| Reply | Published | The change is handled when |
|---|---|---|
| `produce` | one record: key = the message's subject; as in 1.2, by the same code | the broker accepts it |
| `produce_all` with n ≥ 1 messages | n records to the consumer's topic, in the order given | the broker has accepted all n |
| `produce_all` with no messages | nothing | at once, as `done` |
| `done`, `ignore` | nothing | at once |

For each message of a `produce_all`:

1. **Subject**: `ce-subject` from the message's metadata; when absent, the source's id — the rule
   `produce` has.
2. **Key**: `key` when present; otherwise the subject. An empty `key` is refused: the change fails.
3. **Headers**: the CloudEvents attributes and the other metadata entries, exactly as for
   `produce`. A new `ce-id` for each message. `ankka.manifest` and `ankka.content-type` from the
   payload, as for `produce`.
4. **Value**: the payload's bytes.

If any publication fails, the reply's future fails, the change is not recorded as handled, and it
is delivered again; every message is then published again. Messages already accepted are not
withdrawn.

A consumer that declares no topic and replies `produce_all` with messages fails the change, as one
that replies `produce` does. A consumer with a topic and no broker is refused at startup, as today.

## Limits

A reply larger than 4 MiB encoded fails the change with an error naming the consumer, the subject
and the limit. Behind a sidecar the transport enforces it; for a module and for an in-process
consumer the runtime does, before publishing anything.

## Request metadata

| Entry | Value | Since |
|---|---|---|
| `ce-subject` | the source's id | 1.0 |
| `ankka.sequence` | the event's sequence number; the state's revision; `0` for a topic | 1.0 |
| `ankka.protocol` | the runtime's protocol version, `1.3` | 1.3 |

**An SDK's obligation**: before replying `produce_all`, read `ankka.protocol` from the request. If
it is absent or below `1.3`, do not reply `produce_all`; fail the request with

```text
this runtime speaks protocol <version, or "1.2 or earlier">; several messages or a record key need 1.3
```

A handler that returns exactly one message with no key replies `produce`, whatever the runtime,
and one that returns no messages replies `done`: neither is several messages nor a key, so
neither has a reason to fail on an earlier runtime. `produce_all` is sent for two or more
messages, or for any message that names a key — which is every graph delta. The runtime accepts
an empty `produce_all` all the same, as `done`.

## Key value sources

A request for a key value entity's change carries the revision as `ankka.sequence`. A deletion
arrives as `deleted = true` with no message, at the revision after the last update. After a
deletion the id is usable: the next state arrives at a higher revision.

## Compatibility

| Service built with | Runtime | Result |
|---|---|---|
| SDK at 1.2 | 1.3 | unchanged; `ankka.protocol` is an entry it does not read |
| SDK at 1.3, returning single un-keyed messages | 1.2 | unchanged |
| SDK at 1.3, returning several messages or a key | 1.2 | the change fails with the message above; nothing is lost |
| descriptor declaring `1.3` | platform at 1.2 | refused at deploy, as any later minor is |

## Conformance cases

Run against the Scala reference, the Python and TypeScript processes, and the Rust module in both
shapes. The targets are given an in-memory broker that can be told to fail the next publication to
a topic.

Each reference service adds:

| Component | Kind | Source | Topic | Behaviour |
|---|---|---|---|---|
| `checkout-fanout` | consumer | the cart's events | `conformance-fanout` | `ItemAdded`: an empty list. `ItemRemoved`: a single `produce` of `{n: 0}`. `CheckedOut`: three messages `{n: 1}`, `{n: 2}`, `{n: 3}`; the second with key `second:<cartId>`; the third with metadata `x-n: 3`. `Discarded`: ignore. The messages are JSON under the manifest `fanned`. |
| `cart-graph` | graph consumer | the cart's events | `conformance-graph` | the table in [graph-builder.md](graph-builder.md#the-cart-graph) |
| `profile-graph` | graph consumer | the key value entity `profile` | `conformance-profile-graph` | each state: node `profile:<id>`, labels `[Profile]`, properties `{name: <the state's name>}`. Deletion: a tombstone for node `profile:<id>`. |

| Case | Asserts |
|---|---|
| `consumer.produce-all-in-order` | three records for a checkout, payloads in order |
| `consumer.produce-all-keys` | record keys `<cartId>`, `second:<cartId>`, `<cartId>`; `ce-subject` is `<cartId>` on all three; `x-n` on the third only. (That each record is its own CloudEvent, with a `ce-id` of its own, is the Kafka publisher's and is held by `KafkaSuite`; the in-memory broker adds no headers.) |
| `consumer.produce-all-empty` | an `ItemAdded` publishes nothing and a later checkout is still published: the change was handled |
| `consumer.produce-all-redelivers` | with the second publication made to fail once, all three payloads are eventually in the topic, and the first is there at least twice |
| `consumer.single-produce-unchanged` | `checkout-fanout`'s single `produce` for an item removed: record key and subject both the cart's id |
| `consumer.graph-deltas` | for the scripted history, the records read back equal the expected `(key, delta)` list, with versions equal to the sequence numbers and `ce-type` `ankka.graph-delta.v1` |
| `consumer.graph-delete-and-recreate` | discard, then add: a tombstone above every earlier version, then a node above the tombstone |
| `consumer.graph-replay-is-equal` | the same change handled twice publishes equal `(key, delta)` |
| `consumer.kv-sequence` | `profile-graph`'s deltas carry revisions 1, 2, … and never 0 |
| `kv.delete-is-a-change` | deleting the key value entity publishes `profile-graph`'s tombstone at the next revision. (That a view's row goes with it is held by `KeyValueDeletionSuite` and `RemoteProjectionSuite`; no reference has a view over the key value entity.) |
| `kv.delete-then-write` | a write after a deletion succeeds, reads back, and publishes at a revision above the tombstone's |
