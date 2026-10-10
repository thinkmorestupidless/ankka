# Contract: protocol 1.15 and the SDKs

## `client.proto`

```proto
service Client {
  …
  // 1.15: a query answered as a stream of rows, or a watch of a declared query or one row.
  rpc QueryStream (QueryRequest) returns (stream RowFrame);
  rpc Watch       (WatchRequest) returns (stream RowFrame);
}

// 1.15. One element of a row stream or a watch. A stream of rows carries `row` frames and then
// completes, or `failed`; a watch carries the rows now, `caught_up` once, then `row` and `removed`
// as the view writes, and `ended` or `failed` before it completes.
message RowFrame {
  oneof frame {
    Row     row       = 1;
    Removed removed   = 2;
    Empty   caught_up = 3;
    Ended   ended     = 4;
    Error   failed    = 5;
  }
}
message Row     { string key = 1; Payload payload = 2; }   // payload: the stored row, application/json
message Removed { string key = 1; }
message Ended   { string reason = 1; }   // "rebuilt" | "instance-stopping" | "listener-lost" | "unread"

message WatchRequest {
  string view_id = 1;
  Metadata metadata = 2;                   // the handler's, as QueryRequest carries it
  oneof target {
    Named  named = 3;                      // a declared query, declared watched, with its values
    string key   = 4;                      // one row by its row key
  }
  optional uint32 unread_bound = 5;        // absent: the instance's ankka.view.unread-bound
  Overflow overflow = 6;                   // DROP_HEAD when absent
}
message Named { string name = 1; map<string, string> values = 2; }
enum Overflow { DROP_HEAD = 0; DROP_TAIL = 1; DROP_NEW = 2; DROP_ALL = 3; FAIL = 4; }
```

`QueryRequest` is unchanged: `QueryStream` honours `name` (`all` or a declared query), `values`, and
`limit` when present (absent: no limit, unlike `Query`'s thousand). `get`/`by-id`/`by-key` through
`QueryStream` answer at most one row and are allowed.

## `discovery.proto`

```proto
message DeclaredQuery {
  …
  bool watched = N;   // 1.15: may be watched through Client.Watch; the sidecar applies the watched rules at start
}
```

## Versions

`1.15` in: `modules/runtime/.../remote/Conversation.scala` (`WireProtocol.Version`),
`sidecar/.../Discovery.scala` (the comment gains 1.14 and 1.15), `controlplane-api/.../Compatibility.scala`
(`ProtocolVersion(1, 15)` and its history), `protocol/README.md`, `sdks/python/src/ankka/service.py`,
`sdks/typescript/src/spec.ts`, `sdks/rust/.../README.md`. Feature 048 also moves the protocol; the second
to merge takes `1.16`.

## Refusals

| Side | Condition | Behaviour |
|---|---|---|
| sidecar at discovery | a `Spec` declaring protocol < 1.15 with a `watched` query | `"view '…': query '…' is watched, which needs protocol 1.15; the SDK speaks 1.14"` — the `SocketsSince` pattern |
| sidecar at discovery | a watched statement breaking a watched rule | the `QueryCheck` text, as for any declared query |
| sidecar per call | `Watch` of a query not declared watched | `RowFrame.failed` with `BAD_REQUEST`, "declare it watched" |
| sidecar per call | the watch bound | `RowFrame.failed` with `UNAVAILABLE` naming the bound |
| SDK at discovery | sidecar states a version < 1.15 and a view declares a watched query | the SDK aborts discovery `FAILED_PRECONDITION`: `"view '…' declares the watched query '…', which the sidecar does not know: it speaks protocol 1.14, and this SDK 1.15. Run a sidecar speaking 1.15 or later."` |
| SDK per call | `UNIMPLEMENTED` from `QueryStream` or `Watch` | raised as `"the runtime beside this process does not offer view streams, which need protocol 1.15 (this SDK speaks 1.15)"` |
| module at discovery | a `watched` query | `"view '…': query '…' is watched, and a module reads a view whole; declare it without watched"` |

## Flow control

`ClientService` serves both rpcs with the ready-aware loop (`pull` from a `Sink.queue` with an input
buffer of one; wait on `isReady`; `onNext`), so a process reading slowly slows the row stream, and a
watch's unread bound is applied in the runtime before the frames reach gRPC.

## Python

```python
async for row in self.client.views.stream("orders", "all-orders", OrderRow): ...
async for row in self.client.views.stream("orders", "by-customer", OrderRow, values={"customer": "alice"}): ...
async for event in self.client.views.watch("carts", "open-carts", CartRow, unread=256, overflow=Overflow.DROP_HEAD):
    match event:
        case Row(key=k, row=r): ...
        case Removed(key=k): ...
        case CaughtUp(): ...
async for event in self.client.views.watch_row("carts", "c1", CartRow): ...
```

`WatchEnded(reason)` is raised when the watch ends with a reason; `CommandError` for a refusal. An
`@sse` route serves a watch by yielding `SseEvent("row", json)`, `("removed", …)`, `("caught-up", "{}")`
and `("ended", …)` — `ankka.sse_events(watch)` does the mapping. A declared query is
`query("open-carts", statement, watched=True)`.

## TypeScript

```ts
for await (const row of client.views.stream<OrderRow>("orders", "all-orders", OrderRow)) { ... }
for await (const e of client.views.watch<CartRow>("carts", "open-carts", {}, CartRow, { unread: 256, overflow: "dropHead" })) {
  if (e.kind === "row") ... else if (e.kind === "removed") ... else /* caughtUp */ ...
}
for await (const e of client.views.watchRow<CartRow>("carts", "c1", CartRow)) { ... }
```

`WatchEnded` carries `reason`; `sseEvents(watch)` maps a watch to SSE events for an `sse` route. A
declared query is `{ name: "open-carts", statement, watched: true }`.

## Rust

No streaming API: the crate's `Client` is unchanged, `query`/`ask` answer whole. A module's descriptor
cannot declare a watched query (the crate offers no such field), and a module that declares one by hand
is refused at start.

## Conformance

New cases in `ConformanceSuite`, with routes in every reference (`ConformanceReference.scala`,
`conformance.py`, `conformance.ts`, the Rust reference for everything but the stream routes):

| Case | Drives |
|---|---|
| `view.stream-all` | `GET /conformance/views/stream` → 5 000 rows as SSE, past the whole limit |
| `view.stream-named` | the declared query as a stream, in order |
| `view.watch-named` | the rows now, `caught-up`, then a row that comes to match |
| `view.watch-row` | one row, versions in order, removal |
| `view.watched-refused-by-old-runtime` | the reference started against a fake sidecar stating 1.14 refuses discovery |
| `view.watched-refused-in-module` | `WasmHostSuite`: a module declaring a watched query is refused naming "reads a view whole" |

Streaming cases call `onlyWhereStreaming()`; a module is skipped there and tested in the last case.
