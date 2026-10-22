# Contract: sidecar protocol 1.22

Additive. A process declaring 1.21 or earlier is served as before; the new fields are empty on the
wire when there is nothing to say, so an SDK that ignores them reads 1.22 as 1.21.

## `ankka.protocol.v1.EventSourcedIn.Command`

| Field | Number | Since | Meaning |
|---|---|---|---|
| `attribution` | 8 | 1.22 | the subject of the principal who made the original call; empty when none |
| `forwarded_from` | 9 | 1.22 | the installation this command was forwarded from; empty when it was not |

## `ankka.protocol.v1.EventSourcedIn.Event`

| Field | Number | Since | Meaning |
|---|---|---|---|
| `attribution` | 4 | 1.22 | as recorded with the event |
| `forwarded_from` | 5 | 1.22 | as recorded with the event |

## Module mode: `ankka.protocol.v1.FoldRequest` (`wasm.proto`)

A module is not sent `Event` on replay: the runtime calls `ankka1_fold` with a `FoldRequest` per
replayed event. So a module's replayed event is told the same two facts there, mirroring `Event` 4
and 5 (coordinator's decision of 2026-10-22, T033):

| Field | Number | Since | Meaning |
|---|---|---|---|
| `attribution` | 8 | 1.22 | as recorded with the event; empty when none |
| `forwarded_from` | 9 | 1.22 | as recorded with the event; empty when none |

The runtime carries both from the journal record through `HeldState` into the fold; the Rust
crate's `fold_event` reads them into the `EventContext`. `protocol/WASM-ABI.md` describes them.

## Version

`WireProtocol.Version = "1.22"`. `Discovery`'s changelog gains 1.21 (storage client, spec 055)
and 1.22 (attribution and the forwarding installation, spec 057). The constants in
`sdks/python/src/ankka/service.py`, `sdks/typescript/src/spec.ts` and
`sdks/rust/ankka/src/service.rs` move with it, with the `.proto` copies and fixtures.

## SDK surface

| Language | Command context | Event context |
|---|---|---|
| Python | `context.attribution: str | None`, `context.forwarded_from: str | None` | the same names |
| TypeScript | `context.attribution?: string`, `context.forwardedFrom?: string` | the same |
| Rust | `context.attribution() -> Option<&str>`, `context.forwarded_from() -> Option<&str>` | the same |

## Conformance

Each language's conformance adds one case: a command carrying `attribution = "ada"` and
`forwarded_from = "bratislava"` whose handler persists an event, and the event's record read back
with both. The Scala reference (`ConformanceReference`) defines it. The values are illustrative:
the conformance suite sends `forwarded-from = "b"`, which is one of the names the suite's
installations use (accepted 2026-10-22). For a module target the case keeps its half after a
restart, which reads the record back through `FoldRequest`.
