# Contract: the Rust surface

Crate `ankka`, for services hosted as WebAssembly modules. The rules behind the graph types are in
[graph-builder.md](graph-builder.md); the wire and the guard on the runtime's version are in
[protocol.md](protocol.md). The export `ankka1_consumer` keeps its signature.

## Several messages — `ankka::effects::consumer`

```rust
/// One message of several.
#[derive(Debug, Clone)]
pub struct Outgoing { /* payload, metadata, key */ }

impl Outgoing {
    pub fn key(self, key: impl Into<String>) -> Self;      // an empty key panics at dispatch
    pub fn metadata(self, metadata: Metadata) -> Self;
}

#[derive(Debug, Clone)]
pub enum ConsumerEffect {
    Done,
    Ignore,
    Produce(Result<Payload, EncodingError>, Metadata),     // unchanged
    ProduceAll(Vec<Outgoing>),                             // new
}

pub fn produce<T: Serialize + 'static>(message: T) -> ConsumerEffect;                        // unchanged
pub fn produce_with<T: Serialize + 'static>(message: T, metadata: Metadata) -> ConsumerEffect; // new
pub fn message<T: Serialize + 'static>(message: T) -> Outgoing;                              // new
pub fn produce_all(messages: impl IntoIterator<Item = Outgoing>) -> ConsumerEffect;          // empty: as done
```

`Consumer` is unchanged. At dispatch a `ProduceAll` becomes `produce_all` after the guard: with
`ankka.protocol` absent or below 1.3 in the request, the call panics with the contract's message,
and the host fails the change.

`ConsumerEffect` gains a variant: an exhaustive `match` on it in a service stops compiling. The
release notes say so.

## Publishing a graph — `ankka::graph`

```rust
pub const SCHEMA_NAME: &str = "ankka.graph-delta.v1";
pub fn node_key(id: &str) -> String;
pub fn edge_key(id: &str) -> String;

/// A property value: a scalar or a list of one kind of scalar.
#[derive(Debug, Clone, PartialEq)]
pub enum Value { Text(String), Bool(bool), Integer(i64), Float(f64), List(Vec<Value>) }
// From<&str>, From<String>, From<bool>, From<i64>, From<i32>, From<f64>, From<Vec<T: Into<Value>>>

#[derive(Debug, Clone, PartialEq)]
pub struct Element { /* kind, id, version: Option<i64>, labels, type, from, to, properties */ }

impl Element {
    pub fn label(self, label: &str) -> Self;                       // nodes
    pub fn property(self, name: &str, value: impl Into<Value>) -> Self;
    pub fn at(self, version: i64) -> Self;
    pub fn key(&self) -> String;
}

pub fn node(id: impl Into<String>) -> Element;
pub fn edge(id: impl Into<String>, r#type: &str, from: impl Into<String>, to: impl Into<String>) -> Element;
pub fn tombstone_node(id: impl Into<String>) -> Element;
pub fn tombstone_edge(id: impl Into<String>, r#type: &str, from: impl Into<String>, to: impl Into<String>) -> Element;

#[derive(Debug, Clone)]
pub enum GraphEffect { Publish(Vec<Element>), Done, Ignore }
pub fn publish(elements: impl IntoIterator<Item = Element>) -> GraphEffect;   // empty: as done
pub fn done() -> GraphEffect;
pub fn ignore() -> GraphEffect;

pub trait GraphConsumer: Sized + 'static {
    type Message: DeserializeOwned + 'static;
    const COMPONENT_ID: &'static str;
    const TOPIC: &'static str;
    fn source() -> Source;
    fn on_message(message: Self::Message, ctx: &Context) -> GraphEffect;
    fn on_deleted(ctx: &Context) -> GraphEffect { let _ = ctx; GraphEffect::Ignore }
}

pub fn read(value: &[u8], key: Option<&str>) -> Result<Element, String>;
```

```rust
pub struct CartGraph;

impl GraphConsumer for CartGraph {
    type Message = CartEvent;
    const COMPONENT_ID: &'static str = "cart-graph";
    const TOPIC: &'static str = "cart-graph";

    fn source() -> Source { Source::of(ShoppingCartEntity) }

    fn on_message(event: CartEvent, ctx: &Context) -> GraphEffect {
        let id = ctx.entity_id();
        match event {
            CartEvent::ItemAdded { .. } | CartEvent::ItemRemoved { .. } => graph::publish([cart(id, false)]),
            CartEvent::CheckedOut => graph::publish([
                cart(id, true),
                graph::node(format!("checkout:{id}")).label("Checkout").property("cartId", id),
                graph::edge(format!("checked-out:{id}"), "CHECKED_OUT", format!("cart:{id}"), format!("checkout:{id}")),
            ]),
            CartEvent::Discarded => graph::ignore(),
        }
    }

    fn on_deleted(ctx: &Context) -> GraphEffect {
        graph::publish([graph::tombstone_node(format!("cart:{}", ctx.entity_id()))])
    }
}
```

Registered with `.register(CartGraph)`. `GraphConsumer` has its own kind marker,
`kinds::GraphConsumer`, so its blanket `ComponentOf` implementation does not overlap the one for
`Consumer`; what is discovered is `Kind::Consumer` with `produces_to`. A type cannot implement
both traits and register twice under one id: registration refuses the duplicate id, as today.

An element is validated when the effect is dispatched, not when the builder methods are called,
so the builders stay infallible and chainable; a fault panics with a message naming the element
and the fault, and the host fails the change. `Value::Integer` is 64 bits by type;
`Value::Float` must be finite.

## Testing — `ankka::testkit`

```rust
impl<C: Consumer> ConsumerTestKit<C> {
    pub fn on_message(&self, subject: &str, message: C::Message) -> ConsumerEffect;      // unchanged
    pub fn at(self, sequence: i64) -> Self;                                              // new: `ankka.sequence`
    pub fn messages(effect: &ConsumerEffect) -> Vec<Published>;                          // new
}
pub struct Published { pub payload: Payload, pub key: Option<String>, pub metadata: Metadata }

pub struct GraphConsumerTestKit<G: GraphConsumer> { /* … */ }
impl<G: GraphConsumer> GraphConsumerTestKit<G> {
    pub fn new() -> Self;
    pub fn with_service(service: Service) -> Self;
    pub fn on_message(&self, subject: &str, sequence: i64, message: G::Message) -> Vec<Element>;
    pub fn on_deleted(&self, subject: &str, sequence: i64) -> Vec<Element>;
}
```

The kits set `ankka.protocol` to the crate's own version.
