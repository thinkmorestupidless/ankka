# Contract: the Python, TypeScript and Rust surfaces

Each SDK mirrors the Scala surface in its own idiom. The names below are the ones the conformance
suite and `features/replication/languages.feature` assert on.

## Python (`sdks/python/src/ankka/`)

```python
class Vehicle(EventSourcedEntity[VehicleState, VehicleEvent]):
    component_id = "vehicle"
    replicated = True                              # class attribute; default False

    def apply_event(self, event):                  # unchanged signature
        where = self.event_context.installation    # str | None
        local = self.event_context.is_local

    def add_step(self, step):
        self.command_context.installation          # str | None
        self.command_context.is_home               # bool
        return self.effects.persist(StepAdded(step)).hand_over_to("vienna").then_reply(...)
        # also .replicate_to(name), .stop_replicating_to(name)

class Builds(Consumer[...]):
    produce_to = "vehicle-builds"
    mirrored_changes = True                        # default False

# a change
ctx.installation, ctx.local_origin
```

`event_sourced_entity.py`, `effects/`, `context.py`, `consumer.py`, `server.py` (discovery sends
`replicated` and `mirrored_changes`; refuses a runtime below the minor). Errors arrive as the SDK's
`CommandError` with codes `NOT_HOME`, `CONFLICT`, `UNAVAILABLE`.

## TypeScript (`sdks/typescript/src/`)

```ts
export const Vehicle = eventSourcedEntity<VehicleState, VehicleEvent>({
  componentId: "vehicle",
  replicated: true,                                // default false
  emptyState: ...,
  applyEvent: (state, event, ctx) => { ctx.installation; ctx.isLocal; ... },
  commands: {
    "add-step": (entity, step) =>
      entity.effects.persist(stepAdded(step)).handOverTo("vienna").thenReply(() => done),
  },
})
// replicateTo(name), stopReplicatingTo(name); commandContext.installation, commandContext.isHome
export const Builds = consumer({ produceTo: "vehicle-builds", mirroredChanges: true, ... })
// change: ctx.installation, ctx.localOrigin
```

`eventSourcedEntity.ts`, `effects/`, `context.ts`, `consumer.ts`, `server/` discovery and the
version refusal. `applyEvent` gains an optional third argument so existing two-argument handlers
compile unchanged.

## Rust (`sdks/rust/ankka/src/`)

```rust
impl EventSourcedEntity for Vehicle {
    const COMPONENT_ID: &'static str = "vehicle";
    const REPLICATED: bool = true;                 // default false via the trait's default
    fn apply_event(&self, state: Self::State, event: Self::Event, ctx: &EventContext) -> Self::State { ctx.installation(); ctx.is_local(); ... }
}
// effects: persist(ev).hand_over_to("vienna"), .replicate_to(..), .stop_replicating_to(..)
// CommandContext: installation(), is_home()
impl Consumer for Builds { const MIRRORED_CHANGES: bool = true; ... }
```

A module runs behind the sidecar image (`.claude/rules/wasm.md`); the sidecar's host folds platform
records and forwards, so the module's ABI gains only what the event and command carry: the
`installation` string and the `at_home` flag on the host-to-module event and command records, and
the two declaration flags in the module's descriptor. `apply_event` gains the context parameter; the
`ankka-rust` skill shows the migration.

## Conformance

`sidecar/.../ConformanceSuite` gains `es.replicated-declares` (discovery carries the flag; a target
below the minor is refused naming it), `es.replicated-context` (the command handler at the home sees
`at_home = true`, the event handler sees `installation`) and `consumer.mirrored-declares`. The Scala
reference, the Python and TypeScript processes and the Rust module all run them, as every conformance
case does.
