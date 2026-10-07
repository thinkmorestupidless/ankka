# Contract: the wire at protocol 1.13

Additive only, so a minor by `protocol/README.md`'s rule: a 1.7 SDK runs on a 1.13 runtime
unchanged. Written once, in the first slice, with every field below, so that no runtime exists
that speaks 1.13 and does nothing with part of it.

## `discovery.proto`

```proto
message ViewDetail {
  Source source = 1;                 // a plain view's one source
  string row_manifest = 2;
  repeated string queries = 3;       // unchanged, and still unread
  // 1.7: with a topic source. 1.13: also with entity sources. A higher one rebuilds the view. Absent: 1
  optional uint32 version = 4;
  // 1.13: a keyed view's sources, each a component. Present: `source` is absent, the view's
  // changes carry `source_id`, and its effects are `rows`.
  repeated Source sources = 5;
  // 1.13: the queries this view can be asked by name.
  repeated DeclaredQuery declared_queries = 6;
}

// 1.13: a statement over the view's own table; its values are the `:name`s it holds.
message DeclaredQuery { string name = 1; string statement = 2; }
```

## `view.proto`

```proto
message ViewRequest {
  string component_id = 1;
  Payload event = 2;
  Metadata metadata = 3;             // ce-subject, ankka.sequence
  optional Payload row = 4;          // a plain view's current row; never sent to a keyed view
  bool deleted = 5;
  optional string source_id = 6;     // 1.13: a keyed view: the component this change is from
}

message ViewEffect {
  oneof effect {
    Payload update_row = 1;
    Empty delete_row = 2;
    Empty ignore = 3;
    RowChanges rows = 4;             // 1.13: a keyed view's answer; empty is nothing
  }
}
message RowChanges { repeated RowChange changes = 1; }
message RowChange {
  string key = 1;
  oneof change { Payload upsert = 2; Empty delete = 3; }
}
```

## `client.proto`

```proto
message QueryRequest {
  string view_id = 1;
  string name = 2;                   // "get", "all", or since 1.13 a declared query's name
  Payload payload = 3;               // the key, for "get"
  Metadata metadata = 4;
  map<string, string> values = 5;    // 1.13: a declared query's values
  optional uint32 limit = 6;         // 1.13: absent: 1000
}
```

`QueryReply` is unchanged: rows as a JSON array, or an error.

## The WebAssembly ABI

No export or import is added. `ankka1_view` takes the same `ViewRequest` and returns the same
`ViewEffect`, with the new field and case; the `query` import takes the same `QueryRequest`.
`WASM-ABI.md` gains the sentence that a keyed view's changes carry `source_id` and are answered
with `rows`, and that a guest declaring a keyed view, a declared query or a version on an entity
view refuses a host below 1.13.

## What discovery refuses (the sidecar, and the module host through the same function)

| | Refused | Problem |
|---|---|---|
| D1 | a view with both `source` and `sources` | names the view |
| D2 | a view with neither | names the view |
| D3 | a `sources` entry that is a topic, or that carries a start position | K2/K3's words |
| D4 | everything K1–K5 and Q1–Q9 refuse | theirs |
| D5 | a version below 1; a version on a consumer that reads an entity | T5, T4 |

## What an SDK refuses

| | When | Refused |
|---|---|---|
| S1 | discovery, the runtime below 1.13 | a keyed view, a declared query, or a version on a view that reads entities — naming it and the two versions |
| S2 | registration | what K1–K4 and Q9 refuse, where the SDK can see it without a parser; the rest is the runtime's to refuse at discovery |

An SDK does not parse statements. The check is the runtime's, in one implementation.

## Compatibility

| SDK | Runtime | |
|---|---|---|
| 1.7 | 1.13 | hosted as before: `source`, no declared queries, `update_row`/`delete_row`/`ignore` |
| 1.13, using nothing new | 1.7 | hosted as before |
| 1.13, declaring anything new | 1.7 | S1: the service does not start, and says why |

A `rows` effect is given only for a keyed view, and only a runtime at 1.13 or later can have
registered one, so no request needs to state the runtime's version for the reply to be safe.

## Where the version is written

`protocol/README.md`; `WireProtocol.Version` (`runtime/remote/Conversation.scala`);
`Protocol.version` (`controlplane-api/…/Compatibility.scala`) with `HostingSuite`; the changelog in
`sidecar/…/Discovery.scala`; `PROTOCOL_VERSION` in each SDK; and the three copies of `protocol/`
under `sdks/`, which CI diffs against the original.
