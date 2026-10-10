# Contract: the Python, TypeScript and Rust surfaces

The names mirror the Scala ones; the errors mirror each SDK's `CommandError`.

## Python (`sdks/python/src/ankka/client.py`)

```python
class Calls:
    async def await_end(self, timeout: float, *, reply: type[T] | None = None, reply_codec: Codec[T] | None = None) -> T: ...
    def await_end_parts(self, timeout: float, *, reply: type[T] | None = None, reply_codec: Codec[T] | None = None) -> AsyncIterator[Heartbeat | Ended[T] | Failed | TimedOut]: ...

class Invocation:
    def then_await_end(self, timeout: float) -> AwaitingInvocation: ...

class AwaitingInvocation:
    async def invoke(self, input: Any = None, *, reply: type[T] | None = None, codec=None, reply_codec=None) -> T: ...
```

```python
quote = await client.for_workflow("quote", id).call("start").then_await_end(30.0).invoke(request, reply=Quote)
state = await client.for_workflow("quote", id).await_end(10.0, reply=Quote)

@sse("/reports/{id}")
async def report(self, id: str) -> AsyncIterator[str]:
    async for part in self.client.for_workflow("report", id).await_end_parts(600.0, reply=Report):
        yield part.to_json()     # {"heartbeat": true} | {"ended": {...}} | {"failed": {...}} | {"timedOut": true}
```

- `timeout` is seconds, required, `> 0` (a `ValueError` otherwise).
- `CommandError` (`client.py:33`) carries `Error(message, code, details)`; `effects/common.py`'s
  `Error` gains `details: Mapping[str, str] = {}` and `ErrorCode` gains `WORKFLOW_FAILED`
  (HTTP 424). `error.details.get("step")`, `error.details["reason"]`.
- `Heartbeat`, `Ended(state)`, `Failed(error)`, `TimedOut` are small frozen dataclasses with
  `to_json()`, so an `@sse` handler yields JSON strings, which is what `_handle_stream` sends.
- An `UNIMPLEMENTED` from the runtime raises
  `CommandError(Error("the runtime beside this process does not offer waiting for a workflow's end, which needs protocol 1.15 (this SDK speaks 1.15)", UNAVAILABLE))`,
  in the words `client.py:376-385` uses for recurring timers.
- `WorkflowTestKit` (unit) is unchanged: an await is an integration behaviour, tested through
  `ankka.testkit.integration.AnkkaTestKit` against the sidecar image.

## TypeScript (`sdks/typescript/src/client.ts`)

```ts
class Calls {
  awaitEnd<S>(timeoutMillis: number, reply?: Schema<S>): Promise<S>
  awaitEndParts<S>(timeoutMillis: number, reply?: Schema<S>): AsyncIterable<AwaitPart<S>>
}
class Invocation<I, O> {
  thenAwaitEnd<S>(timeoutMillis: number, reply?: Schema<S>): AwaitingInvocation<I, S>
}
type AwaitPart<S> = { kind: "heartbeat" } | { kind: "ended"; state: S } | { kind: "failed"; error: CommandError } | { kind: "timed-out" }
```

```ts
const quote = await client.of(QuoteWorkflow, id).call(QuoteWorkflow.handlers.start).thenAwaitEnd(30_000).invoke(request)
report: sse("/reports/{id}", (ep, req) => toJsonLines(ep.client.of(ReportWorkflow, req.params.id).awaitEndParts(600_000)))
```

- `timeoutMillis` is required and `> 0` (a `RangeError` otherwise). The typed form through `of`
  decodes with the workflow's declared state schema; the untyped form takes a `reply` schema.
- `CommandError` (`effects/common.ts:41`) gains `details: Record<string, string>`; `ErrorCode`
  (`kinds.ts:36`) gains `WorkflowFailed` with status 424.
- `UNIMPLEMENTED` → `tooOld("waiting for a workflow's end", "1.15")` (`client.ts:386-397`).
- Under Node type stripping: no `enum`, no parameter properties (`sdk-typescript.md`).

## Rust (`sdks/rust/ankka/src/client.rs`)

```rust
impl Client {
    pub fn await_end<W: Workflow>(&self, id: &str, timeout: Duration) -> Result<W::State, CommandError>;
    pub fn invoke_then_await_end<W: Workflow, I: Encode>(&self, id: &str, name: &str, input: &I, timeout: Duration) -> Result<W::State, CommandError>;
}
pub struct CommandError { pub code: ErrorCode, pub message: String, pub details: BTreeMap<String, String> }
```

- `ErrorCode::WorkflowFailed` (status 424, proto `WORKFLOW_FAILED` both ways).
- The import `await_end` is in its own `#[link]` block with its own dispatch function; a module
  that never calls `await_end` does not import it (`wasm-objdump -j Import` in the crate's test).
- A runtime that does not provide `await_end` refuses the module at load, naming the import, as
  `ModuleLoader` does for any missing import; the ABI page says "since protocol 1.15".
- No stream form: a module cannot stream.
- A call from a command handler holds that command's pooled instance for the wait and is subject
  to `CommandPool`'s deadline; the documentation says to await from a step, a route or a tool, not
  a command.

## Conformance (`specs/009-polyglot-runtimes/contracts/conformance.md`)

Every reference adds the route `POST /conformance/checkout-await/{id}` with body `ok` or `fail`:
it sends `start` to the `checkout` workflow and awaits its end within 30 seconds; `ok` answers the
final state as JSON with 200; `fail` answers 424 whose body names the step and the reason.

| Behaviour | Asserts |
|---|---|
| `wf.start-and-await` | `ok` → 200 and the state says every step ran, in one request, with no status query between start and answer (the recorder shows no `status` call) |
| `wf.await-failed` | `fail` → 424; the body's `details.step` names the failing step and `details.reason` is non-empty; a following `GET /conformance/checkout/{id}` reports the compensated status |

The Rust reference runs both cases in both shapes.
