# Contract: the Python, TypeScript and Rust APIs

Each SDK declares a start position and a version in its own idiom, writes them into discovery as
[protocol.md](protocol.md) says, and checks rules T1 to T5 of
[topic-sources.md](topic-sources.md) itself, where its other declaration checks are, so that a
mistake is reported by the developer's own test run and names their class. The runtime checks
them again and is the authority.

A view, a consumer and a graph consumer take the same two declarations in every SDK.

## Python

```python
from ankka import StartFrom

class OrderSummaries(View[OrderChange, OrderSummary]):
    component_id = "summary"
    topic = "order-changes"
    start_from = StartFrom.EARLIEST        # the default for a view; may be omitted
    version = 2

class OrderNotifier(Consumer[OrderChange, None]):
    component_id = "notifier"
    topic = "order-changes"
    start_from = StartFrom.LATEST          # required: a consumer has no default

class Backfill(Consumer[OrderChange, None]):
    component_id = "backfill"
    topic = "order-changes"
    start_from = StartFrom.at(datetime(2026, 10, 1, 12, tzinfo=UTC))
```

- `start_from: ClassVar[StartFrom | None] = None`, `version: ClassVar[int | None] = None`.
- `StartFrom.EARLIEST`, `StartFrom.LATEST`, `StartFrom.at(when: datetime)`. A `datetime` with no
  timezone is refused: a start time read in the machine's local zone would differ between a laptop
  and a pod.
- Checked in `__init_subclass__`, raising `RegistrationError` naming the class, as a missing
  source does today.

## TypeScript

```ts
import { StartFrom } from "ankka"

class OrderSummaries extends View<OrderChange, OrderSummary> {
  static componentId = "summary"
  static topic = "order-changes"
  static version = 2
}

class OrderNotifier extends Consumer<OrderChange> {
  static componentId = "notifier"
  static topic = "order-changes"
  static startFrom = StartFrom.latest
}
```

- `readonly startFrom?: StartFrom`, `readonly version?: number` on `ViewClass`, `ConsumerClass`
  and the graph consumer's class type.
- `StartFrom.earliest`, `StartFrom.latest`, `StartFrom.at(when: Date)`. Plain frozen values, no
  `enum`: the SDK runs under Node's type stripping, which takes only erasable syntax.
- A version that is not a safe positive integer is a problem (T5), so `2.5` and `NaN` are refused
  before they are truncated into a `uint32`.
- Collected by `sourceOf` with the other source problems and thrown once as `RegistrationError`.

## Rust

```rust
impl View for OrderSummaries {
    const COMPONENT_ID: &'static str = "summary";
    fn source() -> Source { Source::topic("order-changes") }
    fn version() -> Option<u32> { Some(2) }
    // ...
}

impl Consumer for OrderNotifier {
    const COMPONENT_ID: &'static str = "notifier";
    fn source() -> Source { Source::topic("order-changes") }
    fn start_from() -> Option<StartFrom> { Some(StartFrom::Latest) }
    // ...
}
```

- Two provided methods on `View`, `Consumer` and `GraphConsumer`:
  `fn start_from() -> Option<StartFrom> { None }` and `fn version() -> Option<u32> { None }`.
  Provided, so every existing implementation compiles.
- `pub enum StartFrom { Earliest, Latest, AtMillis(i64) }`, with `StartFrom::at(SystemTime)`. A
  module has no clock, so a time is stated as a number.
- `Source` is unchanged. Its `Topic(String)` variant is public and matched on; a field added to it
  would break every match.
- Checked in each component's `problems()`, gathered by `Service::build`.

Rust has these because a module can already declare a topic source. Without a way to state a
start position, rule T1 would make a consumer over a topic impossible to register from Rust.

## An SDK ahead of its runtime

In all three, when the service declares a start position or a version on any component and the
sidecar's `SidecarInfo.protocol_version` is below 1.7, the SDK refuses to serve, naming the
component, the declaration, and both versions. A service that declares neither starts as before.

A module does receive `SidecarInfo`: `ankka1_discover` is handed it. So the Rust crate checks it as
the other SDKs do, and panics in `Service::discover`, naming what declares a start position or a
version, when the host's protocol is below 1.4. A module cannot report through `ReportError`, so the
panic is how the host learns why. (Found in implementation; planning had assumed a module could not
ask.)

## The unit test kits

Unchanged. Each hands a change to a handler with no sidecar and no broker. What a start position
and a version do is shown by the conformance cases, against the real sidecar.
