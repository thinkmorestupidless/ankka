use ankka::effects::consumer;
use ankka::prelude::*;

#[derive(Serialize, Deserialize)]
struct Changed {
    n: i32,
}

struct Graph;

impl GraphConsumer for Graph {
    type Message = Changed;
    const COMPONENT_ID: &'static str = "graph";
    const TOPIC: &'static str = "graph";

    fn source() -> Source {
        Source::topic("changes")
    }

    // A graph consumer answers with elements. A message of any other kind, or one under a key of
    // the handler's choosing, is not an answer it can give: refused at compile time.
    fn on_message(changed: Changed, _: &Context) -> GraphEffect {
        consumer::produce_all([consumer::message(changed.n).key("mine")])
    }
}

fn main() {}
