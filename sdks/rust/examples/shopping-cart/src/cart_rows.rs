//! A queryable projection of every cart: the entity answers by cart id, this answers the rest —
//! which carts contain a product, which have been checked out.

use std::collections::BTreeMap;

use ankka::effects::view;
use ankka::prelude::*;

use crate::domain::ShoppingCartEvent;
use crate::entity::ShoppingCart;

/// One row per cart: how many of each product it holds, and whether it was checked out.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CartRow {
    #[serde(rename = "cartId")]
    pub cart_id: String,
    pub quantities: BTreeMap<String, i32>,
    #[serde(rename = "checkedOut")]
    pub checked_out: bool,
}

impl CartRow {
    fn new(cart_id: &str) -> CartRow {
        CartRow {
            cart_id: cart_id.to_string(),
            quantities: BTreeMap::new(),
            checked_out: false,
        }
    }
}

// docs:start view
pub struct CartRows;

impl View for CartRows {
    type Row = CartRow;
    type Event = ShoppingCartEvent;
    const COMPONENT_ID: &'static str = "cart-rows";
    const ROW_MANIFEST: Option<&'static str> = Some("cart-row");

    fn source() -> Source {
        Source::of(ShoppingCart)
    }

    fn on_event(
        row: Option<CartRow>,
        event: ShoppingCartEvent,
        ctx: &Context,
    ) -> ViewEffect<CartRow> {
        let cart_id = ctx.metadata().subject().unwrap_or_default();
        let mut row = row.unwrap_or_else(|| CartRow::new(cart_id));
        match event {
            ShoppingCartEvent::ItemAdded { item } => {
                *row.quantities.entry(item.product_id).or_default() += item.quantity;
            }
            ShoppingCartEvent::ItemRemoved { product_id } => {
                row.quantities.remove(&product_id);
            }
            ShoppingCartEvent::CheckedOut => row.checked_out = true,
        }
        view::update_row(row)
    }

    /// Checkout deletes the cart, but a checked-out cart is exactly what an order history needs:
    /// the row outlives the entity that produced it.
    fn on_deleted(row: Option<CartRow>, _: &Context) -> ViewEffect<CartRow> {
        match row {
            Some(row) => view::update_row(CartRow {
                checked_out: true,
                ..row
            }),
            None => view::ignore(),
        }
    }

    fn queries() -> Vec<&'static str> {
        vec!["by-id", "all"]
    }
}
// docs:end view
