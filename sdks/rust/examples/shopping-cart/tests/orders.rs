//! The order and its timed action with nothing running.

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
fn the_expiry_fires_the_cancel_and_the_timer_is_done() {
    // The kit answers the action's call from the order held in memory: the entity answers the
    // cancel, and either answer ends the timer.
    let kit = TimedActionTestKit::<OrderTimers>::new()
        .with_service(shopping_cart::orders::build())
        .with_metadata("ankka.timer", "expire-o-7");
    assert_eq!(
        kit.fire("expire-order", "o-7".to_string())
            .map_err(|e| e.code),
        Ok(())
    );
}
// docs:end timed-action-test
