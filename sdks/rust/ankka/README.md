# ankka

Build [ankka](https://github.com/thinkmorestupidless/ankka) services in Rust.

A Rust service is built to a WebAssembly module, and the ankka runtime loads that module into its own
process. Your functions decide what each command does. The runtime owns everything stateful and
distributed: the journal, sharding, snapshots, projections, timers, HTTP, cluster formation and the agent
loop. Event sourced and key value entities, workflows, views, consumers, timed actions, agents, autonomous
agents and HTTP endpoints are all declared and handled in the module.

A module has no network, no file system and no clock of its own. It reaches the rest of the service
through the runtime, so it holds no database credential and no model key.

## Installing

Every ankka release publishes this crate at the same version, so pin the version of the platform you
deploy to:

```toml
[lib]
crate-type = ["cdylib", "rlib"]    # the module, and the library the tests link

[dependencies]
ankka = "X.Y.Z"
serde = { version = "1", features = ["derive"] }

[dev-dependencies]
ankka = { version = "X.Y.Z", features = ["testkit"] }   # the integration testkit, which needs Docker

[profile.release]
panic = "abort"                    # a panic is a trap the runtime reports
```

`ankka init --language rust` makes a project with all of this set up.

## An entity

```rust
use ankka::prelude::*;
use serde::{Deserialize, Serialize};

#[derive(Clone, Serialize, Deserialize)]
pub struct Count {
    pub value: i64,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum CounterEvent {
    Increased { by: i64 },
}

pub struct Counter;

impl Counter {
    fn increase(_: &Count, by: i64, _: &Context) -> Effect<CounterEvent, Done> {
        if by <= 0 {
            return effects::error(ErrorCode::BadRequest, "increase by at least one").into();
        }
        effects::persist(CounterEvent::Increased { by }).then_reply_value(Done)
    }

    fn get(count: &Count, _: (), _: &Context) -> ReadOnlyEffect<i64> {
        effects::reply(count.value)
    }
}

impl EventSourcedEntity for Counter {
    type State = Count;
    type Event = CounterEvent;
    const COMPONENT_ID: &'static str = "counter";

    fn empty_state(_: &str) -> Count {
        Count { value: 0 }
    }

    fn apply(count: Count, event: &CounterEvent) -> Count {
        match event {
            CounterEvent::Increased { by } => Count { value: count.value + by },
        }
    }

    fn handlers() -> Handlers<Counter> {
        Handlers::new()
            .command("increase", Counter::increase)
            .query("get", Counter::get)
    }
}

pub fn build() -> Service {
    Service::new("counter").register(Counter)
}
ankka::service!(build);
```

A handler returns an effect, which describes what should happen; the runtime carries it out. A query
returns a `ReadOnlyEffect`, so a query that tries to persist does not compile. `"increase"` and `"get"`
are the wire names. They are declared separately from the Rust function names so that renaming a
function never changes the protocol.

## Building and testing

```bash
rustup target add wasm32-unknown-unknown
cargo build --release --target wasm32-unknown-unknown    # the module
cargo test                                               # natively, with no runtime
```

The same code builds natively without the module's exports, which is how its unit tests run. The unit
testkits drive one component in memory and still round-trip every value through the codec:

```rust
use ankka::testkit::EventSourcedTestKit;

#[test]
fn increases() {
    let mut counter = EventSourcedTestKit::<Counter>::new("c-1");
    counter.command("increase", 3i64).reply::<ankka::Done>().unwrap();
    assert_eq!(counter.state().value, 3);
    assert!(counter.command("increase", 0i64).error().is_some());
}
```

With the `testkit` feature, `AnkkaTestKit` starts the module in the real runtime image against a
throwaway Postgres, in Docker.

## Documentation

- [Your first service in Rust](https://docs.ankka.cloud/get-started/first-service-rust/)
- [Rust SDK reference](https://docs.ankka.cloud/reference/rust-sdk/): every component kind's trait,
  effects and testkit
- [Testing](https://docs.ankka.cloud/build/testing/)
- [Services in other languages](https://docs.ankka.cloud/concepts/polyglot/): how a module is hosted
- [WebAssembly ABI](https://docs.ankka.cloud/reference/wasm-abi/): what this crate speaks to the
  runtime, for anyone writing a guest library in another language

## License

Apache-2.0
