//! What each cart holds, as a node of its own beside the cart's: built from the cart's state, read
//! through the client, rather than from the event. It publishes to the same topic as the cart
//! graph, `cart-graph`, under element ids no other consumer writes.

use ankka::prelude::*;

use crate::domain::{ShoppingCart as Cart, ShoppingCartEvent};
use crate::entity::ShoppingCart;

// docs:start graph-from-state
/// An event says what changed — one item added — and an element is its whole state: how many
/// lines the cart has now. So the element is not built from the event. The cart is read through
/// the client and published as it is found, at the version of the event being handled.
///
/// The cart read may already be ahead of that event. The delta then carries a later state at an
/// earlier version, and the events still to come publish it again at their own: the graph is
/// never behind for longer than the consumer is, and ends where the cart is.
pub struct CartContentsGraph;

impl GraphConsumer for CartContentsGraph {
    type Message = ShoppingCartEvent;
    const COMPONENT_ID: &'static str = "cart-contents-graph";
    const TOPIC: &'static str = "cart-graph";

    fn source() -> Source {
        Source::of(ShoppingCart)
    }

    fn on_message(event: ShoppingCartEvent, ctx: &Context) -> GraphEffect {
        if let ShoppingCartEvent::Discarded = event {
            return graph::ignore();
        }
        let id = ctx.entity_id();
        // A panic sends the event again, which is what a consumer that could not read wants.
        let read: Result<Cart, CommandError> =
            ctx.client().invoke(ShoppingCart, id, "get-cart", ());
        let cart = read.expect("the cart answers");
        graph::publish([graph::node(format!("cart-contents:{id}"))
            .label("CartContents")
            .property("cartId", id)
            .property("lines", cart.items.len() as i64)
            .property("quantity", cart.total_quantity())])
    }

    fn on_deleted(ctx: &Context) -> GraphEffect {
        graph::publish([graph::tombstone_node(format!(
            "cart-contents:{}",
            ctx.entity_id()
        ))])
    }
}
// docs:end graph-from-state
