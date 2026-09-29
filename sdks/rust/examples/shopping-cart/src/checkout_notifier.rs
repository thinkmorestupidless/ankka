//! Turns an internal event into an action elsewhere: the cart's own events are an implementation
//! detail, and this consumer decides which are worth acting on — here, recording a checkout in the
//! checkout log through the client.

use ankka::effects::consumer;
use ankka::prelude::*;

use crate::checkout_log::CheckoutLog;
use crate::domain::ShoppingCartEvent;
use crate::entity::ShoppingCart;

// docs:start consumer
pub struct CheckoutNotifier;

impl Consumer for CheckoutNotifier {
    type Message = ShoppingCartEvent;
    const COMPONENT_ID: &'static str = "checkout-notifier";

    fn source() -> Source {
        Source::of(ShoppingCart)
    }

    fn on_message(event: ShoppingCartEvent, ctx: &Context) -> ConsumerEffect {
        let ShoppingCartEvent::CheckedOut = event else {
            return consumer::ignore();
        };
        let cart_id = ctx.metadata().subject().unwrap_or_default();
        let at = ctx.now().epoch_millis();
        // A panic sends the message again, which is what a consumer that could not act wants.
        let recorded: Result<Done, CommandError> =
            ctx.client().invoke(CheckoutLog, cart_id, "record", at);
        recorded.expect("the checkout log answers");
        consumer::done()
    }
}
// docs:end consumer
