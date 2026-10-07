# Contract: recurring timers in Python, TypeScript and Rust

One behaviour, three spellings. Decisions are in [research.md](../research.md) (R7, R8). The
rules a service can rely on are in [scala-api.md](scala-api.md) and the wire is in
[protocol.md](protocol.md).

## Shared rules

- The recurring schedule takes a timer id, a delay, a period, a timed action's handler and its
  input. Both the delay and the period are given.
- A period of zero or less, or of more than 36,500 days, is refused before anything is sent, with
  the runtime's message, naming the timer. The runtime refuses it too. A delay of zero or less is
  due at once.
- Setting the same recurring timer again, with the same handler and period, keeps its next due.
  A service may set its recurring timers every time it starts.
- `cancel` is unchanged and cancels either kind. No SDK gains `exists`.
- The due time a handler is run for is metadata `ankka.due`, and each SDK offers one accessor that
  parses it. On a runtime older than protocol 1.12 there is none and the accessor answers the
  language's "nothing".
- A runtime older than protocol 1.12 asked for a recurring timer is reported as that, naming both
  versions.
- Each unit test kit can set the metadata a timed action is run with.

## Python

```python
class Timers:
    async def schedule(self, timer_id, delay, component_id, name, input=None, *, codec=None) -> None: ...   # unchanged
    async def schedule_recurring(
        self,
        timer_id: str,
        delay: timedelta,
        period: timedelta,
        component_id: str,
        name: str,
        input: Any = None,
        *,
        codec: Codec[Any] | None = None,
    ) -> None: ...
    async def cancel(self, timer_id: str) -> None: ...   # unchanged

class TimedAction:
    @property
    def due_time(self) -> datetime | None: ...   # timezone-aware, UTC
```

- A refusal raises `ankka.client.CommandError` with `code == "BAD_REQUEST"`.
- `TimedActionTestKit.of(cls).call(name, input=None, *, metadata=None)`: `metadata` is a mapping
  of keys to strings, so a test sets `ankka.due`, `ankka.attempts` and `ankka.timer`.

## TypeScript

```ts
export type TimedActionTarget =
  | { component: ComponentRef; handler: HandlerRef<any, any> }
  | { componentId: string; name: string; input?: Shape<any> }

export class Timers {
  schedule(timerId: string, delay: Duration, target: TimerTarget, input?: unknown): Promise<void>   // unchanged
  scheduleRecurring(
    timerId: string,
    delay: Duration,
    period: Duration,
    target: TimedActionTarget,
    input?: unknown,
  ): Promise<void>
  cancel(timerId: string): Promise<void>   // unchanged
}

export abstract class TimedAction {
  get dueTime(): Date | undefined
}
```

- A refusal rejects with `CommandError` whose `code` is `"BAD_REQUEST"`.
- `TimedActionTestKit.of(cls).invoke(action, input?, metadata?)` already takes metadata; a test
  passes `"ankka.due"`.

## Rust

```rust
impl Client {
    pub fn schedule_recurring<C, P>(
        &self,
        timer_id: &str,
        delay: Duration,
        period: Duration,
        component: C,          // a timed action
        name: &str,
        payload: P,
    ) -> Result<(), CommandError>;

    pub fn schedule_recurring_by_name<P>(
        &self,
        timer_id: &str,
        delay: Duration,
        period: Duration,
        component_id: &str,
        name: &str,
        payload: P,
    ) -> Result<(), CommandError>;
}

impl Context {
    /// The due time this timer is run for (`ankka.due`); `None` outside a timed action.
    pub fn due(&self) -> Option<Instant>;
}
```

- A refusal is `Err(CommandError)` with `ErrorCode::BadRequest`: the reply carries it, so the
  module does not trap. `schedule` and `cancel` are unchanged.
- Natively, with no runtime, `schedule_recurring` goes to the installed `NativeHost` as `schedule`
  does, and panics with the same words when there is none.
- `TimedActionTestKit::<C>::new().with_metadata(key, value).fire(name, input)`.
- A module that never calls `schedule_recurring` does not import it.

## What is not changed

`ScheduleRequest`'s `kind` and `entity_id`, which every SDK can send and the runtime ignores;
TypeScript's test kit default of `ankka.attempts`; accessors for a timer's id and attempt count.
Each is its own change, and every existing timer test passes as it is.
