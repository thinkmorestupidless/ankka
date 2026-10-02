"""The cart as a graph: a node for each cart, a node for its checkout and the edge between them,
published as graph deltas for a graph database to follow. This consumer publishes to a topic, so
the sidecar needs a broker (``ANKKA_KAFKA_BOOTSTRAP_SERVERS``), and the topic, ``cart-graph``, has
to be compacted to hold the graph: the pipeline that reads it declares it and creates it so."""

from __future__ import annotations

from ankka import GraphConsumer
from ankka.graph import Element, GraphEffect

from examples.shopping_cart.domain import CheckedOut, ItemAdded, ItemRemoved, ShoppingCartEvent
from examples.shopping_cart.entity import ShoppingCartEntity


# docs:start graph-consumer
class CartGraph(GraphConsumer[ShoppingCartEvent]):
    """Each element is the element's whole state after the event, at the event's sequence number:
    no key, no version and no JSON is written here."""

    component_id = "cart-graph"
    source = ShoppingCartEntity
    produces_to = "cart-graph"
    message_codec = ShoppingCartEntity.event_codec

    def on_message(self, event: ShoppingCartEvent) -> GraphEffect:
        cart_id = self.metadata.subject or ""
        match event:
            case ItemAdded() | ItemRemoved():
                return self.effects.publish([self._cart(cart_id, checked_out=False)])
            case CheckedOut():
                return self.effects.publish(
                    [
                        self._cart(cart_id, checked_out=True),
                        self.graph.node(f"checkout:{cart_id}", labels=["Checkout"], properties={"cartId": cart_id}),
                        self.graph.edge(f"checked-out:{cart_id}", type="CHECKED_OUT", from_id=f"cart:{cart_id}", to_id=f"checkout:{cart_id}"),
                    ]
                )
            case _:
                # Discarded: the deletion that follows it is what marks the cart gone.
                return self.effects.ignore()

    def on_delete(self) -> GraphEffect:
        return self.effects.publish([self.graph.tombstone_node(f"cart:{self.metadata.subject}")])

    def _cart(self, cart_id: str, *, checked_out: bool) -> Element:
        return self.graph.node(f"cart:{cart_id}", labels=["Cart"], properties={"cartId": cart_id, "checkedOut": checked_out})
# docs:end graph-consumer
