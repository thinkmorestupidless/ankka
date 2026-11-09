//! The cart as an event sourced entity: the Scala and Python carts' handlers, wire names and
//! manifests, so a journal written by any of them replays here.

use ankka::prelude::*;

use crate::domain::{LineItem, ShoppingCart as Cart, ShoppingCartEvent};

// docs:start entity
pub struct ShoppingCart;

impl ShoppingCart {
    // docs:start add-item
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
    // docs:end add-item

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
        // As the Scala cart: a checked-out cart is kept, the record of what was ordered, and every
        // later change to it is refused.
        effects::persist(ShoppingCartEvent::CheckedOut).then_reply(|cart: &Cart| cart.clone())
    }

    // docs:start discard
    /// Records the discard and then deletes the cart, so the same id starts again empty. A
    /// checked-out cart is a record of an order and is not discarded.
    fn discard(cart: &Cart, _: (), _: &Context) -> Effect<ShoppingCartEvent, Done> {
        if cart.checked_out {
            return effects::error(ErrorCode::Conflict, "cart is already checked out").into();
        }
        // The event is persisted, then the cart deleted, so a consumer downstream still sees the
        // discard rather than a cart that vanished; the same id then starts again empty.
        effects::persist(ShoppingCartEvent::Discarded)
            .delete_entity()
            .then_reply_value(Done)
    }
    // docs:end discard

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
            // The cart is deleted straight after; the event is there for what reads the journal.
            ShoppingCartEvent::Discarded => cart,
        }
    }

    // docs:start handlers
    /// Every handler under its wire name: the name a caller and a stored timer use, which does
    /// not change when the Rust function is renamed.
    fn handlers() -> Handlers<ShoppingCart> {
        Handlers::new()
            .command("add-item", ShoppingCart::add_item)
            .command("remove-item", ShoppingCart::remove_item)
            .command("checkout", ShoppingCart::checkout)
            .command("discard", ShoppingCart::discard)
            .query("get-cart", ShoppingCart::get_cart)
            .query("total-quantity", ShoppingCart::total_quantity)
    }
    // docs:end handlers

    fn snapshot_every() -> u32 {
        100
    }
}
// docs:end entity
