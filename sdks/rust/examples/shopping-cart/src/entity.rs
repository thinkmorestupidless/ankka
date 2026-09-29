//! The cart as an event sourced entity: the Scala and Python carts' handlers, wire names and
//! manifests, so a journal written by any of them replays here.

use ankka::prelude::*;

use crate::domain::{LineItem, ShoppingCart as Cart, ShoppingCartEvent};

// docs:start entity
pub struct ShoppingCart;

impl ShoppingCart {
    fn add_item(cart: &Cart, item: LineItem, _: &Context) -> Effect<ShoppingCartEvent, Done> {
        if cart.checked_out {
            return effects::error(ErrorCode::Conflict, "cart is already checked out").into();
        }
        if item.quantity <= 0 {
            let message = format!("quantity must be greater than zero, was {}", item.quantity);
            return effects::error(ErrorCode::BadRequest, message).into();
        }
        effects::persist(ShoppingCartEvent::ItemAdded { item }).then_reply_value(Done)
    }

    fn remove_item(
        cart: &Cart,
        product_id: String,
        _: &Context,
    ) -> Effect<ShoppingCartEvent, Done> {
        if cart.checked_out {
            return effects::error(ErrorCode::Conflict, "cart is already checked out").into();
        }
        if !cart.contains(&product_id) {
            let message = format!("cart does not contain '{product_id}'");
            return effects::error(ErrorCode::NotFound, message).into();
        }
        effects::persist(ShoppingCartEvent::ItemRemoved { product_id }).then_reply_value(Done)
    }

    fn checkout(cart: &Cart, _: (), _: &Context) -> Effect<ShoppingCartEvent, Cart> {
        if cart.checked_out {
            return effects::error(ErrorCode::Conflict, "cart is already checked out").into();
        }
        if cart.is_empty() {
            return effects::error(ErrorCode::BadRequest, "cannot check out an empty cart").into();
        }
        // The event is persisted, then the cart deleted, so a consumer downstream still sees the
        // checkout rather than a cart that vanished.
        effects::persist(ShoppingCartEvent::CheckedOut)
            .delete_entity()
            .then_reply(|cart: &Cart| cart.clone())
    }

    fn get_cart(cart: &Cart, _: (), _: &Context) -> ReadOnlyEffect<Cart> {
        effects::reply(cart.clone())
    }

    fn total_quantity(cart: &Cart, _: (), _: &Context) -> ReadOnlyEffect<i32> {
        effects::reply(cart.total_quantity())
    }
}

impl EventSourcedEntity for ShoppingCart {
    type State = Cart;
    type Event = ShoppingCartEvent;
    const COMPONENT_ID: &'static str = "shopping-cart";
    // The manifests the Scala cart stores under: what makes the journal shared.
    const STATE_MANIFEST: Option<&'static str> = Some("shopping-cart");
    const EVENT_MANIFEST: Option<&'static str> = Some("shopping-cart-event");

    fn empty_state(cart_id: &str) -> Cart {
        Cart::empty(cart_id)
    }

    fn apply(cart: Cart, event: &ShoppingCartEvent) -> Cart {
        match event {
            ShoppingCartEvent::ItemAdded { item } => cart.add_item(item.clone()),
            ShoppingCartEvent::ItemRemoved { product_id } => cart.remove_item(product_id),
            ShoppingCartEvent::CheckedOut => cart.on_checked_out(),
        }
    }

    fn handlers() -> Handlers<ShoppingCart> {
        Handlers::new()
            .command("add-item", ShoppingCart::add_item)
            .command("remove-item", ShoppingCart::remove_item)
            .command("checkout", ShoppingCart::checkout)
            .query("get-cart", ShoppingCart::get_cart)
            .query("total-quantity", ShoppingCart::total_quantity)
    }

    fn snapshot_every() -> u32 {
        100
    }
}
// docs:end entity
