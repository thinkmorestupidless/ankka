use ankka::prelude::*;

#[derive(Serialize, Deserialize)]
struct Count {
    n: i32,
}

#[derive(Serialize, Deserialize)]
#[serde(tag = "type")]
enum Counted {
    Once {},
}

struct Counter;

impl Counter {
    // A query answering an Effect, which could persist: refused at compile time.
    fn get(_: &Count, _: (), _: &Context) -> Effect<Counted, Done> {
        effects::persist(Counted::Once {}).then_reply_value(Done)
    }
}

impl EventSourcedEntity for Counter {
    type State = Count;
    type Event = Counted;
    const COMPONENT_ID: &'static str = "counter";

    fn empty_state(_: &str) -> Count {
        Count { n: 0 }
    }

    fn apply(state: Count, _: &Counted) -> Count {
        state
    }

    fn handlers() -> Handlers<Counter> {
        Handlers::new().query("get", Counter::get)
    }
}

fn main() {}
