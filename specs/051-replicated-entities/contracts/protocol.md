# Contract: the sidecar protocol

Changes to `protocol/src/main/protobuf/ankka/protocol/v1/`. The minor version is the next free one
when this merges (research R13); every `<minor>` below is that number.

## discovery.proto

```proto
message EventSourcedDetail {
  int32 snapshot_every = 1;
  bool replicated = 2;                 // <minor>: the entity is replicated across installations
}
message ConsumerDetail {
  // existing fields …
  bool mirrored_changes = N;           // <minor>: a producing or graph consumer that reacts to mirrored changes too
}
```

- A runtime at a protocol minor below `<minor>` that reads `replicated = true` refuses the process at
  discovery, naming the version replication needs, as a workflow source is refused today
  (`sidecar/.../Discovery.scala:354`).
- An SDK that declares `replicated = true` against a runtime whose protocol is below `<minor>`
  refuses to start, naming the version.
- `replicated` on any detail but `EventSourcedDetail` cannot be expressed; a process that wants it is
  refused by its own SDK's types.

## event_sourced.proto

```proto
message EventSourcedIn {
  message Event {
    int64 sequence = 1;
    Payload payload = 2;
    string installation = 3;           // <minor>: where the event was written; empty for a record from before replication
  }
  message Command {
    // existing fields 1–5 …
    string installation = 6;           // <minor>: this installation; empty on a service with none named
    bool at_home = 7;                  // <minor>: whether this installation is the entity's home (true when none named)
  }
}
```

A process receives only commands its copy handles: a persisting command at a follower is forwarded
by the sidecar's host before the process is asked, so `at_home` is true for every persisting
command a process sees and may be false only for a read-only one. Platform records (kinds 3 to 7)
are never sent to the process as events; the sidecar's remote host folds them itself.

## consumer.proto

`ConsumerRequest.metadata` gains two entries beside `ce-subject` and `ankka.sequence`:

| Entry | Value |
|---|---|
| `ankka.installation` | where the change was written; absent for a record from before replication |
| `ankka.local` | `true` or `false`: whether the change was written at this installation |

A consumer declared without `mirrored_changes` that produces or publishes graph deltas is not sent a
non-local change at all (research R12), so it cannot publish twice by mistake.

## client.proto

No change. A process's component client calls the sidecar as today; forwarding to the home happens
inside the sidecar's runtime, invisibly, and a forwarded call carries the process's metadata.

## Fixtures

`protocol/fixtures/` is `core`'s `EncodingFixturesSuite`'s and gains nothing here. The journal and
snapshot bytes are pinned in `modules/testkit/src/test/resources/journal/` (research R4).
