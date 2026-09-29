//! The cart's tests at both levels: the entity and its API natively, with nothing running, and —
//! with `--features slow` — the module in the real runtime against Postgres, restarted, and sharing
//! its journal with the Scala cart.

use ankka::prelude::*;
use ankka::testkit::{
    EndpointTestKit, EventSourcedTestKit, StepNext, ViewTestKit, WorkflowTestKit,
};
use shopping_cart::cart_rows::CartRows;
use shopping_cart::checkout_workflow::CheckoutWorkflow;
use shopping_cart::domain::{LineItem, ShoppingCart as Cart, ShoppingCartEvent};
use shopping_cart::endpoint::CartApi;
use shopping_cart::entity::ShoppingCart;

fn pen(quantity: i32) -> LineItem {
    LineItem {
        product_id: "p1".into(),
        name: "Pen".into(),
        quantity,
    }
}

fn ink() -> LineItem {
    LineItem {
        product_id: "p2".into(),
        name: "Ink".into(),
        quantity: 1,
    }
}

// docs:start view-test
#[test]
fn the_row_counts_each_product_and_outlives_the_cart() {
    let mut kit = ViewTestKit::<CartRows>::new();
    kit.on_event("cart-1", ShoppingCartEvent::ItemAdded { item: pen(2) });
    kit.on_event("cart-1", ShoppingCartEvent::ItemAdded { item: pen(3) });
    assert_eq!(kit.row("cart-1").unwrap().quantities["p1"], 5);
    // Checkout deletes the cart; the row stays, checked out.
    kit.on_deleted("cart-1");
    assert!(kit.row("cart-1").unwrap().checked_out);
}
// docs:end view-test

// docs:start workflow-test
#[test]
fn a_checkout_reserves_what_the_cart_holds_then_charges_it() {
    let mut kit =
        WorkflowTestKit::<CheckoutWorkflow>::new("cart-9").with_service(shopping_cart::build());
    assert_eq!(
        kit.command("start", "ok".to_string()).reply::<Done>(),
        Ok(Done)
    );
    assert_eq!(kit.run_step(), StepNext::TransitionTo("charge".into()));
    // The step asked the cart what it holds — the kit answers from an in-memory cart, empty here.
    assert_eq!(kit.state().reserved, 0);
    assert_eq!(kit.run_to_end(), StepNext::End);
    assert_eq!(kit.state().status, "charged");
}
// docs:end workflow-test

// docs:start unit-test
#[test]
fn adding_an_item_persists_it_and_the_cart_holds_it() {
    let mut kit = EventSourcedTestKit::<ShoppingCart>::new("cart-1");
    let outcome = kit.command("add-item", pen(2));
    assert_eq!(
        outcome.events,
        vec![ShoppingCartEvent::ItemAdded { item: pen(2) }]
    );
    assert_eq!(outcome.reply::<Done>(), Ok(Done));
    assert_eq!(kit.state().items, vec![pen(2)]);

    // The same product again folds into one line.
    kit.command("add-item", pen(1));
    assert_eq!(kit.state().items, vec![pen(3)]);
}

#[test]
fn a_refused_command_persists_nothing() {
    let mut kit = EventSourcedTestKit::<ShoppingCart>::new("cart-1");
    let refused = kit.command("add-item", pen(0));
    assert!(refused.events.is_empty());
    assert_eq!(refused.error().map(|e| e.code), Some(ErrorCode::BadRequest));
    assert!(kit.state().items.is_empty());
}
// docs:end unit-test

#[test]
fn removing_and_checking_out() {
    let mut kit = EventSourcedTestKit::<ShoppingCart>::new("cart-2");
    kit.command("add-item", pen(1));
    kit.command("add-item", ink());
    kit.command("remove-item", "p1".to_string());
    assert_eq!(kit.state().items, vec![ink()]);
    assert_eq!(
        kit.command("remove-item", "p1".to_string())
            .error()
            .map(|e| e.code),
        Some(ErrorCode::NotFound)
    );

    let checkout = kit.command("checkout", ());
    assert_eq!(checkout.events, vec![ShoppingCartEvent::CheckedOut]);
    assert!(checkout.reply::<Cart>().unwrap().checked_out);
    // Checked out and deleted: the id is a fresh cart again.
    assert_eq!(kit.state(), &Cart::empty("cart-2"));
}

#[test]
fn an_empty_cart_cannot_be_checked_out() {
    let mut kit = EventSourcedTestKit::<ShoppingCart>::new("cart-3");
    assert_eq!(
        kit.command("checkout", ()).error().map(|e| e.code),
        Some(ErrorCode::BadRequest)
    );
}

// docs:start endpoint-test
#[test]
fn the_api_answers_from_the_cart_behind_it() {
    let kit = EndpointTestKit::<CartApi>::with_service(shopping_cart::build());
    assert_eq!(kit.post("/carts/c1/items", &pen(2)).status, 204);
    assert_eq!(kit.post("/carts/c1/items", &ink()).status, 204);
    let cart: Cart = kit.get("/carts/c1").json().unwrap();
    assert_eq!(cart.items, vec![pen(2), ink()]);
    assert_eq!(kit.get("/carts/c1/total").text(), "3");
    // A refusal from the entity is its status.
    assert_eq!(kit.post("/carts/c1/items", &pen(0)).status, 400);
}
// docs:end endpoint-test

#[test]
fn a_literal_route_outranks_a_cart_id() {
    let kit = EndpointTestKit::<CartApi>::new();
    assert_eq!(kit.get("/carts/awkward").text(), "literal");
}

// ── Through the runtime ─────────────────────────────────────────────────────

#[cfg(feature = "slow")]
mod slow {
    use super::*;
    use ankka::testkit::{AnkkaTestKit, Module};

    // docs:start integration-test
    #[test]
    fn the_cart_through_the_runtime_survives_a_restart() {
        let mut rt = AnkkaTestKit::start(Module::build().unwrap()).unwrap();
        let http = rt.http().clone();
        assert_eq!(
            http.post("/carts/c1/items")
                .json(&pen(2))
                .send()
                .unwrap()
                .status,
            204
        );
        assert_eq!(
            http.post("/carts/c1/items")
                .json(&ink())
                .send()
                .unwrap()
                .status,
            204
        );
        assert_eq!(
            http.delete("/carts/c1/items/p2").send().unwrap().status,
            204
        );

        // A new runtime on the same database: the cart comes back from the journal.
        rt.restart().unwrap();
        let cart: Cart = rt.http().get("/carts/c1").send().unwrap().json().unwrap();
        assert_eq!(cart.items, vec![pen(2)]);

        let checkout = rt.http().post("/carts/c1/checkout").send().unwrap();
        assert_eq!(checkout.status, 200, "{}", checkout.text());
        let fresh: Cart = rt.http().get("/carts/c1").send().unwrap().json().unwrap();
        assert_eq!(fresh, Cart::empty("c1"));
    }
    // docs:end integration-test

    /// The Scala cart's image, which `sbt shoppingCart/Docker/publishLocal` builds.
    fn scala_cart() -> String {
        std::env::var("ANKKA_SCALA_CART_IMAGE")
            .unwrap_or_else(|_| "sample-shopping-cart:latest".to_string())
    }

    fn read(http: &ankka::testkit::Http, cart_id: &str) -> Cart {
        let r = http.get(&format!("/carts/{cart_id}")).send().unwrap();
        assert_eq!(r.status, 200, "{}", r.text());
        r.json().unwrap()
    }

    /// One journal, two languages: a cart the Scala service wrote is read by the Rust module, and
    /// one the module wrote is read by the Scala service. Never both at once: each is a cluster.
    #[test]
    fn journal_portable() {
        let mut rt = AnkkaTestKit::start(Module::build().unwrap()).unwrap();
        rt.stop_runtime();

        let scala = rt.start_beside(&scala_cart(), 9000, Vec::new()).unwrap();
        let written = scala
            .http()
            .post("/carts/from-scala/items")
            .json(&pen(2))
            .send()
            .unwrap();
        assert!(
            written.status < 300,
            "{} {}",
            written.status,
            written.text()
        );
        scala
            .http()
            .post("/carts/from-scala/items")
            .json(&ink())
            .send()
            .unwrap();
        let by_scala = read(scala.http(), "from-scala");
        drop(scala);

        rt.start_runtime().unwrap();
        assert_eq!(read(rt.http(), "from-scala"), by_scala);
        rt.http()
            .post("/carts/from-rust/items")
            .json(&ink())
            .send()
            .unwrap();
        rt.http()
            .post("/carts/from-rust/items")
            .json(&pen(1))
            .send()
            .unwrap();
        let by_rust = read(rt.http(), "from-rust");
        rt.stop_runtime();

        let scala = rt.start_beside(&scala_cart(), 9000, Vec::new()).unwrap();
        assert_eq!(read(scala.http(), "from-rust"), by_rust);
        assert_eq!(read(scala.http(), "from-scala"), by_scala);
    }
}
