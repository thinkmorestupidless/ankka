//! The carts as a graph: a node for each cart, a node for each checkout, and an edge from one to
//! the other, published as graph deltas for whatever keeps a graph database in step with a topic.
//!
//! The consumer says which elements a change leaves in which state and nothing else. Each is
//! published under the key of its element and at the version of the event that caused it, so a
//! change handled twice publishes the same deltas, and a reader that keeps the highest version is
//! unmoved by the repeat.

use ankka::graph::Element;
use ankka::prelude::*;

use crate::domain::ShoppingCartEvent;
use crate::entity::ShoppingCart;

// docs:start graph-consumer
pub struct CartGraph;

impl GraphConsumer for CartGraph {
    type Message = ShoppingCartEvent;
    const COMPONENT_ID: &'static str = "cart-graph";
    const TOPIC: &'static str = "cart-graph";

    fn source() -> Source {
        Source::of(ShoppingCart)
    }

    fn on_message(event: ShoppingCartEvent, ctx: &Context) -> GraphEffect {
        let id = ctx.entity_id();
        match event {
            ShoppingCartEvent::ItemAdded { .. } | ShoppingCartEvent::ItemRemoved { .. } => {
                graph::publish([cart(id, false)])
            }
            ShoppingCartEvent::CheckedOut => graph::publish([
                cart(id, true),
                graph::node(format!("checkout:{id}"))
                    .label("Checkout")
                    .property("cartId", id),
                graph::edge(
                    format!("checked-out:{id}"),
                    "CHECKED_OUT",
                    format!("cart:{id}"),
                    format!("checkout:{id}"),
                ),
            ]),
            // The deletion that follows is what marks the cart's node.
            ShoppingCartEvent::Discarded => graph::ignore(),
        }
    }

    /// A discarded cart is deleted, and its node is marked deleted: a tombstone, above every
    /// delta the cart published before it.
    fn on_deleted(ctx: &Context) -> GraphEffect {
        graph::publish([graph::tombstone_node(format!("cart:{}", ctx.entity_id()))])
    }
}

/// The cart's node, whole: an element is its state, not a change to it.
fn cart(id: &str, checked_out: bool) -> Element {
    graph::node(format!("cart:{id}"))
        .label("Cart")
        .property("cartId", id)
        .property("checkedOut", checked_out)
}
// docs:end graph-consumer
