"""What each cart holds, as a node of its own beside the cart's: built from the cart's state, read
through the component client, rather than from the event. It publishes to the same topic as the
cart graph, ``cart-graph``, under element ids no other consumer writes."""

from __future__ import annotations

from ankka import GraphConsumer
from ankka.graph import GraphEffect

from examples.shopping_cart.domain import Discarded, ShoppingCart, ShoppingCartEvent
from examples.shopping_cart.entity import ShoppingCartEntity


# docs:start graph-from-state
class CartContentsGraph(GraphConsumer[ShoppingCartEvent]):
    """An event says what changed — one item added — and an element is its whole state: how many
    lines the cart has now. So the element is not built from the event. The cart is read through
    the client and published as it is found, at the version of the event being handled.

    The cart read may already be ahead of that event. The delta then carries a later state at an
    earlier version, and the events still to come publish it again at their own: the graph is never
    behind for longer than the consumer is, and ends where the cart is."""

    component_id = "cart-contents-graph"
    source = ShoppingCartEntity
    produces_to = "cart-graph"
    message_codec = ShoppingCartEntity.event_codec

    async def on_message(self, event: ShoppingCartEvent) -> GraphEffect:  # type: ignore[override]
        if isinstance(event, Discarded):
            return self.effects.ignore()
        cart_id = self.metadata.subject or ""
        assert self.client is not None
        cart = await self.client.for_event_sourced_entity("shopping-cart", cart_id).call("get-cart").invoke(reply=ShoppingCart)
        return self.effects.publish(
            [
                self.graph.node(
                    f"cart-contents:{cart_id}",
                    labels=["CartContents"],
                    properties={"cartId": cart_id, "lines": len(cart.items), "quantity": cart.total_quantity},
                )
            ]
        )

    def on_delete(self) -> GraphEffect:
        return self.effects.publish([self.graph.tombstone_node(f"cart-contents:{self.metadata.subject}")])
# docs:end graph-from-state
