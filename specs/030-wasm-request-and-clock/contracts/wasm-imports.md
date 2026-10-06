# Contract: the `ankka1` import module, with three more

What a guest library sees. Decisions are in [research.md](../research.md) (R1 to R9, R12). The
protocol version this feature lands at is 1.10: 025 landed at 1.8 and 028 at 1.9.

## The imports

Added to the table of `protocol/WASM-ABI.md`:

| import | in | out | semantics |
|---|---|---|---|
| `request(ptr, len) -> i64` | `ServiceRequest` | `ServiceReply` | `Client.Request`: a call to another service, made by the runtime as this service, since 1.10. Permitted only from the exports listed under *Where `request` may be called*; from any other it traps. Blocks the calling instance until the service answers or the runtime's wait for it ends |
| `now() -> i64` | | | the runtime's clock as milliseconds since the Unix epoch, when it is asked, since 1.10. From any export |
| `random(ptr, len)` | | | fills the `len` bytes at `ptr`, which the guest owns, from the runtime's secure source, since 1.10. `len` is at most 65,536. From any export |

`request` follows the memory convention of every bytes import: the request is read from the
guest's buffer, and the reply is written into a buffer the host allocates through `ankka1_alloc`
and the guest then owns. `now` touches no memory. `random` writes into the guest's own buffer and
calls no export.

A module that calls none of the three imports none of them and runs on a runtime older than 1.10.
A module that imports one is refused at load by a runtime older than 1.10, naming the import.

## Where `request` may be called

| Export | A `request` made while the host is running it |
|---|---|
| `ankka1_run_step` | proceeds |
| `ankka1_consumer` | proceeds |
| `ankka1_timed_action` | proceeds |
| `ankka1_plan` | proceeds |
| `ankka1_invoke_tool` | proceeds |
| `ankka1_check_guardrail` | proceeds |
| `ankka1_check_task_result` | proceeds |
| `ankka1_http` | proceeds |
| `ankka1_handle` | traps: a command of an entity or of a workflow |
| `ankka1_fold` | traps: an event applied to an entity's state |
| `ankka1_view` | traps: a view's handler |
| `ankka1_close`, `ankka1_discover`, `ankka1_alloc`, `ankka1_free` | traps |

The rule is the host's, decided from the export it called. It does not read the request and it
does not depend on what the guest library checks. An export added to the ABI later traps until it
is added to the permitted list.

A `request` also traps when the call it is made from has already been abandoned: the runtime
answered the call's own caller with a fault because the module ran past its deadline, and what
the module does after that makes no further call to another service.

## The trap

The import does not return. The instance is discarded and replaced, the state the runtime holds
for the component is untouched, and the caller is answered as for any trap: `Internal`, with

```text
the module failed in ankka1_handle: request may not be called from the command cart/add-item: a module calls another service from a workflow step, a consumer, a timed action, an agent, a tool, a guardrail, a result check or a route
```

| Export | The middle of the message |
|---|---|
| `handle` | `from the command <component>/<handler>` |
| `fold` | `while <component> reads an event` |
| `view` | `from the view <component>` |
| any other | `from <export>` |
| an abandoned call | `from a call the runtime has abandoned (<export>, <component>)` |

Nothing is sent, and the request's bytes are not parsed, before the trap.

## What `request` answers

`ServiceReply`, exactly as 025 defines it for a process: `response` whenever the service answered,
whatever its status; `failure` with `UNRESOLVABLE`, `IDENTITY_MISMATCH` or `UNANSWERED`; `error`
when the runtime refused the request (a name, a path, a body over 4,000,000 bytes), and
`error(UNAVAILABLE)` before the service has started. A redirect is the reply; it is not followed.

- **Metadata.** The runtime sends the metadata it gave the export being run, and ignores
  `ServiceRequest.metadata`. The call is counted from that handler and traced under its span.
- **Headers and bounds.** 025's, unchanged: the platform's own headers are replaced, and a body
  each way is at most 4,000,000 bytes.
- **Waiting.** No timeout is carried. The runtime waits the service's
  `ankka.service-client.timeout`, thirty seconds unless set, and answers `UNANSWERED` when it
  passes. An export's own deadline is unchanged, so a call that outlasts it is abandoned as any
  over-long call is.

## `now` and `random`

- `now` is the time when it is asked. Two reads in one call may differ. Under replay it is still
  the present: an event's time is read from the event.
- `random` is never seeded and has no setting. Bytes used to make an id in a command belong in the
  event the command records, or a replay makes a different id.
- Neither waits, and neither reads the call site.

## `ankka.now`

The host goes on setting `ankka.now` on every request that carries `Metadata`, through 1.10 and
the minor after it, so a module built with a guest library from before 1.10 reads the time as it
did. That now includes the four requests that carried none: a step, a tool call, a guardrail check
and a result check.

## Version

- The protocol version is `1.10`. No message is added or changed by this feature.
- `WASM-ABI.md`'s own rule holds: an added import is a minor change, tolerated by absence.
- `ModuleLoader.Imports` and `HostImports.values` name the same fourteen functions.

## What a test must show

Each scenario of `features/wasm/` is a case named for it. Where a weaker check would pass:

- **Nothing was sent.** Read from the scripted service's record of requests, which must be empty,
  not from the command having failed.
- **The state is kept.** Assert what the state holds after the next command, not that the command
  succeeded: a fresh state also succeeds.
- **Every export is in the table.** The cases iterate `ModuleLoader.Exports`; an export with no
  expectation fails the suite.
- **The rule is the runtime's.** The guests of `WasmImportsSuite` are written by hand and link no
  library.
- **The time is the runtime's.** With the clock fixed to a value no machine's clock has, the guest
  reads that value.
- **The bytes were written.** The guest pre-fills its two buffers with different constants; the
  case asserts neither constant survives and the two differ.
