# Data Model: WebAssembly Request and Clock

Nothing is stored. No table, journal record, snapshot or `.proto` message is added or changed.
What the feature adds are values the host holds for the length of one call, and the surface of
the Rust crate. Decisions are in [research.md](research.md).

## The call site (`sidecar/wasm`, new)

What the host is running in a module, for as long as one export call lasts.

| Field | Type | Meaning |
|---|---|---|
| `function` | `String` | the ABI function called, with its prefix: `ankka1_handle` (`export` is a keyword) |
| `purpose` | `Purpose` | what whoever made the call knows: `componentId` (empty for `discover`), `handler` (the command, step, tool, guardrail, task type or route, where there is one) and `metadata`, what the host sent with the call |
| `instance` | `GuestInstance` | the instance running it, read for whether it has been abandoned |

`GuestInstance.call(function, request, purpose)` builds it. There is no form of `call` without a
purpose.

- `CallSite.current: Option[CallSite]` is the calling thread's, set by `GuestInstance.call` around
  the export and cleared after it, whether it returned or threw.
- `CallSite.Permitted: Set[String]` is the exports `request` proceeds from: `run_step`,
  `consumer`, `timed_action`, `plan`, `invoke_tool`, `check_guardrail`, `check_task_result`,
  `http`, each with the prefix. It is a subset of `ModuleLoader.Exports`, which a test holds.
- `site.mayRequest: Either[String, Unit]` is the whole rule: `Left` with the message of
  [contracts/wasm-imports.md](contracts/wasm-imports.md) when the export is not permitted or the
  instance is broken.

No call site is the same as an export that is not permitted.

## `ImportRefused` (`sidecar/wasm`, new)

The exception a refused `request` throws from the host function: the import's name and the
message. It is not a `GuestFault`; it becomes one in `GuestInstance.call`, as any failure of a
call does.

## The imports' own state (`HostImports`)

| Member | Was | Becomes |
|---|---|---|
| `clock: () => Long` | `WasmConversation`'s `now` parameter | `HostImports`'s, default `System.currentTimeMillis`; `WasmConversation` stamps `ankka.now` from it |
| `serviceTimeout: FiniteDuration` | none | how long `request` awaits the client: the client's own timeout, its connect timeout and a second |
| `calls: Option[ServiceCalls]` | none | what `request` calls: `ClientLogic`, through the one method of a trait it extends, set by `bind`; a suite sets a stand-in |
| `secure: SecureRandom` | none | one, shared; never seeded |
| `values` | eleven functions | fourteen |

`ModuleLoader.Imports` gains `request`, `now`, `random`.

## Messages

`request` takes `ServiceRequest` and answers `ServiceReply`, both 025's, unchanged
(`client.proto`). The host overwrites `ServiceRequest.metadata` with the call site's. `now` and
`random` carry no message.

## The Rust crate

Detail in [contracts/rust-api.md](contracts/rust-api.md).

| Type | Fields | Notes |
|---|---|---|
| `Services` | the context's metadata | from `Context::services()`; `None` in an entity, a view and a workflow's command |
| `ServiceClient` | service, optional project, metadata | `target()` is `project/name`, or `name` alone |
| `ServiceResponse` | `status: u16`, `content_type: String`, `body: Vec<u8>`, `headers: Vec<(String, String)>` | `text()` reads the body as UTF-8 |
| `ServiceError` | `Unresolvable { service, reason }`, `IdentityMismatch { service, detail }`, `Unanswered { service, reason }`, `CallFailed { service, status, body }`, `Refused(CommandError)` | the first three from `ServiceReply.failure`, the fourth from a typed helper's status outside 2xx, the last from `ServiceReply.error` |
| `Context` | gains `services: bool` beside `secrets: bool` | set by the same component kinds |

## Validation

| Rule | Where | Answer |
|---|---|---|
| `request` from an export not in `Permitted`, or with no call site | host, before parsing | trap |
| `request` from an abandoned call | host, before parsing | trap |
| the service's name, the project, the path, the method, a body over 4,000,000 bytes | 025's `ClientLogic.request` | `ServiceReply.error(BAD_REQUEST)` |
| `random` with a length below 0 or above 65,536, or a buffer outside memory | host | trap |
| a module naming an import the runtime does not offer | `ModuleLoader`, at load | refused, naming it; unchanged |
| a body over 4,000,000 bytes | the crate, before the import | `ServiceError::Refused`, naming the limit |

## State transitions

One, and it is the existing one: an instance on which an import trapped is broken, is replaced by
its pool when the call returns, and the component's held state is untouched.
