//! An order and the timed action that expires it: the timers page's example. Not in the module's
//! service; `build` assembles the pair for the tests.

use ankka::prelude::*;

/// An order: what was ordered, and where it is.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct Order {
    pub item: String,
    pub status: String,
}

pub struct OrderEntity;

impl OrderEntity {
    fn place(order: &Order, item: String, ctx: &Context) -> KeyValueEffect<Order, Done> {
        if order.status != "none" {
            let message = format!("order '{}' was already placed", ctx.entity_id());
            return effects::error(ErrorCode::Conflict, message).into();
        }
        effects::update_state(Order {
            item,
            status: "pending".to_string(),
        })
        .then_reply_value(Done)
    }

    fn confirm(order: &Order, _: (), _: &Context) -> KeyValueEffect<Order, Done> {
        if order.status != "pending" {
            let message = format!("cannot confirm an order that is '{}'", order.status);
            return effects::error(ErrorCode::Conflict, message).into();
        }
        effects::update_state(Order {
            status: "confirmed".to_string(),
            ..order.clone()
        })
        .then_reply_value(Done)
    }

    /// Cancels a pending order, and reports success for one already settled.
    ///
    /// The idempotence matters: this is what a timer calls, and a timer that "fails" because the
    /// order was confirmed a moment earlier would retry forever.
    fn cancel(order: &Order, _: (), _: &Context) -> KeyValueEffect<Order, String> {
        if order.status == "pending" {
            return effects::update_state(Order {
                status: "cancelled".to_string(),
                ..order.clone()
            })
            .then_reply_value("cancelled".to_string());
        }
        effects::reply(format!("already {}", order.status)).into()
    }

    fn status(order: &Order, _: (), _: &Context) -> ReadOnlyEffect<String> {
        effects::reply(order.status.clone())
    }
}

impl KeyValueEntity for OrderEntity {
    type State = Order;
    const COMPONENT_ID: &'static str = "order";
    const STATE_MANIFEST: Option<&'static str> = Some("order");

    fn empty_state(_: &str) -> Order {
        Order {
            item: String::new(),
            status: "none".to_string(),
        }
    }

    fn handlers() -> KeyValueHandlers<OrderEntity> {
        KeyValueHandlers::new()
            .command("place", OrderEntity::place)
            .command("confirm", OrderEntity::confirm)
            .command("cancel", OrderEntity::cancel)
            .query("status", OrderEntity::status)
    }
}

/// Expires orders that were never confirmed.
///
/// Note what it does *not* do: fail when the order has already been confirmed. A timed action that
/// errors gets rescheduled, so "there was nothing to do" has to be reported as success.
pub struct OrderTimers;

impl OrderTimers {
    // docs:start expire-order
    fn expire_order(order_id: String, ctx: &Context) -> Result<(), CommandError> {
        // The entity answers "cancelled" or "already confirmed"; either way the timer is done.
        let _outcome: String = ctx.client().invoke(OrderEntity, &order_id, "cancel", ())?;
        Ok(())
    }
    // docs:end expire-order
}

// docs:start order-timers
impl TimedAction for OrderTimers {
    const COMPONENT_ID: &'static str = "order-timers";

    fn actions() -> Actions<OrderTimers> {
        Actions::new().action("expire-order", OrderTimers::expire_order)
    }
}
// docs:end order-timers

/// Schedules the expiry when an order is placed: the timer's id names the order, so placing it
/// again moves the same timer rather than adding a second.
pub fn schedule_expiry(ctx: &Context, order_id: &str, after: Duration) -> Result<(), CommandError> {
    ctx.client().schedule(
        &format!("expire-{order_id}"),
        after,
        OrderTimers,
        None,
        "expire-order",
        order_id.to_string(),
    )
}

/// The order and its timers, as a service the tests run against.
pub fn build() -> Service {
    Service::new("ankka-rust")
        .register(OrderEntity)
        .register(OrderTimers)
}
