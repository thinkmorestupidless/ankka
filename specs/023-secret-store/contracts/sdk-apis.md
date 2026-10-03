# Contract: the secret store in Python, TypeScript and Rust

One behaviour, three spellings. Decisions are in [research.md](../research.md) (R9, R10). The
rules, the error codes and the wire are in [scala-api.md](scala-api.md) and
[protocol.md](protocol.md); each SDK's `CommandError` carries the code the runtime answered.

## Shared rules

- `put(name, value)` keeps; `get(name)` answers the value or the language's "nothing"; `delete(name)`
  removes. No method answers an empty string.
- The store is offered to endpoints, workflow **steps**, consumers (graph consumers included),
  timed actions, agents and autonomous agents. It is not offered to an event sourced entity, a key
  value entity, a view or a workflow's command handler.
- The process never holds the secret key. A module asking `config` for it is answered absent.
- A runtime older than protocol 1.6 is reported as that, naming both versions.

## Python

```python
class Secrets:
    async def put(self, name: str, value: str) -> None: ...
    async def get(self, name: str) -> str | None: ...
    async def delete(self, name: str) -> None: ...
```

- Not a member of `ComponentClient`, which entities are handed. Consumers, timed actions, agents,
  autonomous agents and endpoints reach it as `self.secrets`; a workflow step through its step
  context. An entity's `CommandContext` and a `View` have no `secrets` attribute.
- Unit tests: the testkit's in-memory `Secrets`, applying the rules of
  `proto/fixtures/secrets/rules.json`.
- Integration tests: `AnkkaTestKit.start` puts a generated `ANKKA_SECRET_KEY` on the sidecar
  unless `env` names one; `env={"ANKKA_SECRET_KEY": ""}` starts it with none.

## TypeScript

```ts
export class Secrets {
  put(name: string, value: string): Promise<void>;
  get(name: string): Promise<string | undefined>;
  delete(name: string): Promise<void>;
}
```

- `get secrets(): Secrets` on the consumer, timed action, agent, autonomous agent, endpoint and
  graph consumer classes, and on a workflow within a step. The event sourced entity, key value
  entity and view classes have no such property: `entity.secrets` is a type error, held by a
  `// @ts-expect-error` test.
- `noSecrets()` beside `noClient()`: the unit-test store whose every call throws; and an
  in-memory `Secrets` in the testkit.
- Integration tests: `AnkkaTestKitOptions.env` as for Python.

## Rust

```rust
impl Context {
    /// The secret store, or `None` in an entity, a view and a workflow's command handler.
    pub fn secrets(&self) -> Option<Secrets>;
}

impl Secrets {
    pub fn put(&self, name: &str, value: &str) -> Result<(), Error>;
    pub fn get(&self, name: &str) -> Result<Option<String>, Error>;
    pub fn delete(&self, name: &str) -> Result<(), Error>;
}
```

- Every handler takes one `&Context`, so the kind of component decides what `secrets()` answers,
  set where each kind builds its context.
- `Import` gains `GetSecret`, `PutSecret` and `DeleteSecret`. The native host used by unit tests
  answers them from an in-memory map applying the rules.
- Integration tests: `AnkkaTestKit::start` sets a generated `ANKKA_SECRET_KEY`; `start_with` can
  replace or empty it.

## What a test must show, per SDK

- The four `languages.feature` outlines, through the SDK's conformance run.
- That an entity and a view are given no store: a type-level check where the language has one,
  and a run-time check that the attribute, property or `Option` is absent.
- That the unit-test double and the runtime agree on every row of the rules fixture.
- That a store call against a runtime without the calls is reported as a version mismatch, not a
  hang and not "absent".
