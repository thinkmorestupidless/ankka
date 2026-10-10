//! A checkout as a durable multi-step process: reserve the stock, charge the customer, and check the
//! cart out — or compensate. The runtime journals every transition and runs the steps; a step that
//! panics is retried and failed over as `settings` declares.

use ankka::effects::workflow;
use ankka::prelude::*;

use crate::domain::ShoppingCart as Cart;
use crate::entity::ShoppingCart;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Checkout {
    #[serde(rename = "cartId")]
    pub cart_id: String,
    pub status: String,
    pub reserved: i32,
    pub mode: String,
}

// docs:start workflow
pub struct CheckoutWorkflow;

impl CheckoutWorkflow {
    /// `mode`: `ok`, `fail` (the charge is declined) or `pause` (a pause before it).
    fn start(checkout: &Checkout, mode: String, _: &Context) -> WorkflowEffect<Checkout, Done> {
        if checkout.status != "new" {
            let message = format!("checkout is already {}", checkout.status);
            return effects::error(ErrorCode::Conflict, message).into();
        }
        let mode = if mode.is_empty() {
            "ok".to_string()
        } else {
            mode
        };
        let reserving = Checkout {
            status: "reserving".into(),
            mode,
            ..checkout.clone()
        };
        workflow::update_state(reserving)
            .transition_to("reserve")
            .then_reply_value(Done)
    }

    fn status(checkout: &Checkout, _: (), _: &Context) -> ReadOnlyEffect<Checkout> {
        effects::reply(checkout.clone())
    }

    fn reserve(checkout: &Checkout, _: (), ctx: &Context) -> StepEffect<Checkout> {
        // A client call from a step: what the cart holds.
        let total: i32 = ctx
            .client()
            .invoke(ShoppingCart, &checkout.cart_id, "total-quantity", ())
            .expect("the cart answers");
        let next = if checkout.mode == "pause" {
            "wait"
        } else {
            "charge"
        };
        let reserved = Checkout {
            status: "reserved".into(),
            reserved: total,
            ..checkout.clone()
        };
        step_effects::update_state(reserved).then_transition_to(next)
    }

    fn wait(checkout: &Checkout, _: (), _: &Context) -> StepEffect<Checkout> {
        let waiting = Checkout {
            status: "waiting".into(),
            ..checkout.clone()
        };
        step_effects::update_state(waiting).then_pause_for(Duration::of_millis(1500), "charge")
    }

    fn charge(checkout: &Checkout, _: (), ctx: &Context) -> StepEffect<Checkout> {
        if checkout.mode == "fail" || checkout.mode == "doomed" {
            panic!("payment declined");
        }
        // Not idempotent — a retry after the cart was checked out is refused — which is why
        // `charge` is allowed one retry and then fails over, and why compensation exists.
        if checkout.reserved > 0 {
            let _: Cart = ctx
                .client()
                .invoke(ShoppingCart, &checkout.cart_id, "checkout", ())
                .expect("the cart checks out");
        }
        let charged = Checkout {
            status: "charged".into(),
            ..checkout.clone()
        };
        step_effects::update_state(charged).then_end()
    }

    fn compensate(checkout: &Checkout, _: (), _: &Context) -> StepEffect<Checkout> {
        if checkout.mode == "doomed" {
            panic!("compensation failed too");
        }
        let compensated = Checkout {
            status: "compensated".into(),
            reserved: 0,
            ..checkout.clone()
        };
        step_effects::update_state(compensated).then_end()
    }
}

impl Workflow for CheckoutWorkflow {
    type State = Checkout;
    const COMPONENT_ID: &'static str = "checkout";
    const STATE_MANIFEST: Option<&'static str> = Some("checkout");

    fn empty_state(cart_id: &str) -> Checkout {
        Checkout {
            cart_id: cart_id.into(),
            status: "new".into(),
            reserved: 0,
            mode: "ok".into(),
        }
    }

    fn handlers() -> WorkflowHandlers<CheckoutWorkflow> {
        WorkflowHandlers::new()
            .command("start", CheckoutWorkflow::start)
            .query("status", CheckoutWorkflow::status)
    }

    fn steps() -> Steps<CheckoutWorkflow> {
        Steps::new()
            .step("reserve", CheckoutWorkflow::reserve)
            .step("wait", CheckoutWorkflow::wait)
            .step("charge", CheckoutWorkflow::charge)
            .step("compensate", CheckoutWorkflow::compensate)
    }

    fn settings() -> WorkflowSettings {
        WorkflowSettings::new()
            .default_step_timeout(Duration::of_seconds(10))
            .step_recovery("charge", Recovery::retries(1).failover_to("compensate"))
    }
}
// docs:end workflow
