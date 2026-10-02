# Contract: the Python surface

Package `ankka`. The rules behind the graph types are in [graph-builder.md](graph-builder.md); the
wire and the guard on the runtime's version are in [protocol.md](protocol.md).

## Several messages — `ankka.effects.consumer`

```python
@dataclass(frozen=True)
class Message(Generic[Out]):
    payload: Out
    key: str | None = None            # "" raises ValueError
    metadata: Metadata = Metadata()

@dataclass(frozen=True)
class ProduceAll(Generic[Out]):
    messages: tuple[Message[Out], ...]

ConsumerEffect = Produce[Any] | ProduceAll[Any] | Done | Ignore

class ConsumerEffects(Generic[Out]):
    def produce(self, payload: Out, metadata: Metadata | None = None) -> Produce[Out]: ...   # unchanged
    def message(self, payload: Out, *, key: str | None = None,
                metadata: Metadata | None = None) -> Message[Out]: ...
    def produce_all(self, messages: Iterable[Message[Out]]) -> ProduceAll[Out]: ...          # empty: as done
```

`Consumer` is unchanged apart from accepting `ProduceAll` from `on_message` and `on_delete`.
`self.metadata.sequence_number` is the change's sequence, as today; `self.metadata.protocol` is
new and is the runtime's protocol version, or `None`.

`ConsumerServicer.Handle` replies `produce_all` for a `ProduceAll`, after the guard: with
`ankka.protocol` absent or below 1.3 it raises, and the request fails.

## Publishing a graph — `ankka.graph`

```python
SCHEMA_NAME = "ankka.graph-delta.v1"

def node_key(id: str) -> str: ...
def edge_key(id: str) -> str: ...

Scalar = str | bool | int | float
PropertyValue = Scalar | Sequence[Scalar]

@dataclass(frozen=True)
class Element:
    kind: Literal["node", "edge", "tombstone"]
    element: Literal["node", "edge"]
    id: str
    version: int | None                 # None until the result is returned
    labels: tuple[str, ...] = ()
    type: str | None = None
    from_id: str | None = None
    to_id: str | None = None
    properties: Mapping[str, Any] = field(default_factory=dict)
    @property
    def key(self) -> str: ...

class Graph:                            # `self.graph` in a GraphConsumer
    def node(self, id: str, *, labels: Sequence[str] = (),
             properties: Mapping[str, PropertyValue] | None = None,
             version: int | None = None) -> Element: ...
    def edge(self, id: str, *, type: str, from_id: str, to_id: str,
             properties: Mapping[str, PropertyValue] | None = None,
             version: int | None = None) -> Element: ...
    def tombstone_node(self, id: str, *, version: int | None = None) -> Element: ...
    def tombstone_edge(self, id: str, *, type: str, from_id: str, to_id: str,
                       version: int | None = None) -> Element: ...

class GraphEffects:                     # `self.effects` in a GraphConsumer
    def publish(self, elements: Iterable[Element]) -> Publish: ...    # empty: as done
    def done(self) -> Done: ...
    def ignore(self) -> Ignore: ...

class GraphConsumer(Generic[Src]):
    component_id: ClassVar[str]
    source: ClassVar[Any] = None
    topic: ClassVar[str | None] = None          # a topic to read, as on Consumer
    produces_to: ClassVar[str]                  # required
    message_codec: ClassVar[Codec[Any]]
    def on_message(self, message: Src) -> GraphEffect: ...
    def on_delete(self) -> GraphEffect: ...     # default: ignore

def read(value: bytes, key: str | None = None) -> Element: ...        # ValueError naming the fault
```

```python
class CartGraph(GraphConsumer[CartEvent]):
    component_id = "cart-graph"
    source = ShoppingCartEntity
    produces_to = "cart-graph"
    message_codec = CART_EVENTS

    def on_message(self, event: CartEvent) -> GraphEffect:
        cart_id = self.metadata.subject or ""
        match event:
            case ItemAdded() | ItemRemoved():
                return self.effects.publish([self._cart(cart_id, checked_out=False)])
            case CheckedOut():
                return self.effects.publish([
                    self._cart(cart_id, checked_out=True),
                    self.graph.node(f"checkout:{cart_id}", labels=["Checkout"], properties={"cartId": cart_id}),
                    self.graph.edge(f"checked-out:{cart_id}", type="CHECKED_OUT",
                                    from_id=f"cart:{cart_id}", to_id=f"checkout:{cart_id}"),
                ])
            case _:
                return self.effects.ignore()

    def on_delete(self) -> GraphEffect:
        return self.effects.publish([self.graph.tombstone_node(f"cart:{self.metadata.subject}")])
```

A `GraphConsumer` is discovered as a consumer with `produces_to`. It has no `out_codec` to
declare and no `produce`. `self.client` and `self.metadata` are the consumer's. Refusals are
`ValueError`. `bool` is not an integer for a version or a property kind.

## Testing — `ankka.testkit`

```python
class ConsumerTestKit(Generic[Src, Out]):
    def on_message(self, message: Src, subject: str = "test", *,
                   sequence: int | None = None) -> ConsumerEffect: ...
    def on_delete(self, subject: str = "test", *, sequence: int | None = None) -> ConsumerEffect: ...
    produced: list[Any]                 # payloads, as today: one per message
    messages: list[Produced]            # new: payload, key, metadata

@dataclass(frozen=True)
class Produced(Generic[Out]):
    payload: Out
    key: str | None
    metadata: Metadata

class GraphConsumerTestKit(Generic[Src]):
    @classmethod
    def of(cls, consumer: type[GraphConsumer[Src]], client: ComponentClient | None = None) -> Self: ...
    def on_message(self, message: Src, subject: str = "test", *, sequence: int = 1) -> list[Element]: ...
    def on_delete(self, subject: str = "test", *, sequence: int = 1) -> list[Element]: ...
```

The kits set `ankka.protocol` to the SDK's own version, so the guard passes; a test of the guard
builds the request without it.

## As built

Where the SDK settled what this contract left open, or went further:

- A refusal is `graph.RefusedElement`, a `ValueError` with `.why`, the reason's name as
  `refused.json` has it. A non-string property name is refused as `property-name`.
- `graph.CODEC` is the delta's codec (manifest `ankka.graph-delta.v1`), for a consumer that reads
  a delta topic; `graph.resolve` settles a result's versions and duplicates.
- `GraphConsumer` is not a subclass of `Consumer`. It carries an `out_codec` the SDK sets, so one
  servicer and one registry serve both; declaring an `out_codec` on one is a `RegistrationError`.
- `read` returns properties as the sink stores them: a whole-valued number is an `int`.
- An empty `ProduceAll` is answered as `done`, and one un-keyed message as `produce`, on any
  runtime; the guard fires for two or more messages or any keyed one, and fails the call with
  `FAILED_PRECONDITION` and the contract's message.
- `ConsumerTestKit.of` takes no client; `GraphConsumerTestKit.of` does.
- `GraphConsumer` and `graph` are exported from `ankka`.
