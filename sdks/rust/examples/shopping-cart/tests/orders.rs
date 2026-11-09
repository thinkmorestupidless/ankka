//! The order and its timed action with nothing running.

use ankka::prelude::*;
use ankka::testkit::{KeyValueEntityTestKit, TimedActionTestKit};
use shopping_cart::orders::{OrderEntity, OrderTimers};

#[test]
fn cancelling_a_pending_order_cancels_it_and_a_settled_one_is_left_alone() {
    let mut kit = KeyValueEntityTestKit::<OrderEntity>::new("o-1");
    kit.command("place", "a pen".to_string());
    assert_eq!(
        kit.command("cancel", ()).answer.reply::<String>(),
        Ok("cancelled".to_string())
    );
    let mut confirmed = KeyValueEntityTestKit::<OrderEntity>::new("o-2");
    confirmed.command("place", "ink".to_string());
    confirmed.command("confirm", ());
    assert_eq!(
        confirmed.command("cancel", ()).answer.reply::<String>(),
        Ok("already confirmed".to_string())
    );
    assert_eq!(confirmed.state().status, "confirmed");
}

// docs:start timed-action-test
#[test]
fn the_expiry_fires_the_cancel_and_reports_what_the_entity_refused() {
    // The unit kit answers event sourced entities only; the order is a key value entity, so the
    // action's call is refused and the action fails, as it would with no runtime to call.
    let kit = TimedActionTestKit::<OrderTimers>::new()
        .with_service(shopping_cart::orders::build())
        .with_metadata("ankka.timer", "expire-o-7");
    let fired = kit.fire("expire-order", "o-7".to_string());
    assert_eq!(fired.map_err(|e| e.code), Err(ErrorCode::Unavailable));
}
// docs:end timed-action-test
