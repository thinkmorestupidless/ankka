//! The shopping cart every ankka SDK carries, as a WebAssembly module: an event sourced cart, a view
//! of every cart, a checkout workflow, a consumer and the log it writes, an agent, and the HTTP API
//! in front of them. `cargo build --release` in this directory builds the module; with the
//! `conformance` feature, the module is the conformance reference instead ([`conformance`]).

pub mod assistant;
pub mod cart_rows;
pub mod checkout_log;
pub mod checkout_notifier;
pub mod checkout_workflow;
pub mod conformance;
pub mod domain;
pub mod endpoint;
pub mod entity;

use ankka::Service;

// docs:start service
/// Everything the module hosts, registered by value; `service!` emits the exports once.
pub fn build() -> Service {
    Service::new("ankka-rust")
        .register(entity::ShoppingCart)
        .register(cart_rows::CartRows)
        .register(checkout_workflow::CheckoutWorkflow)
        .register(checkout_notifier::CheckoutNotifier)
        .register(checkout_log::CheckoutLog)
        .register(assistant::CartAssistant)
        .endpoint(endpoint::CartApi)
}

#[cfg(not(feature = "conformance"))]
ankka::service!(build);
// docs:end service

#[cfg(feature = "conformance")]
ankka::service!(conformance::build);

#[cfg(test)]
mod tests {
    use ankka::proto::SidecarInfo;

    #[test]
    fn the_service_builds_and_declares_the_cart_and_its_routes() {
        let service = super::build().build().expect("no problems");
        let spec = service.discover(&SidecarInfo::default()).spec.unwrap();
        let ids: Vec<&str> = spec.components.iter().map(|c| c.id.as_str()).collect();
        assert_eq!(
            ids,
            vec![
                "assistant",
                "cart-rows",
                "checkout",
                "checkout-log",
                "checkout-notifier",
                "shopping-cart"
            ]
        );
        let routes: Vec<String> = spec.endpoints[0]
            .routes
            .iter()
            .map(|r| r.id.clone())
            .collect();
        assert!(
            routes.contains(&"GET /{cartId}/rows".to_string()),
            "{routes:?}"
        );
        assert!(
            routes.contains(&"POST /{cartId}/items".to_string()),
            "{routes:?}"
        );
    }

    #[test]
    fn the_conformance_reference_declares_what_the_suite_expects() {
        let service = super::conformance::build().build().expect("no problems");
        let spec = service.discover(&SidecarInfo::default());
        let ids: Vec<String> = spec
            .spec
            .unwrap()
            .components
            .into_iter()
            .map(|c| c.id)
            .collect();
        assert_eq!(
            ids,
            vec![
                "answerer",
                "assistant",
                "cart-rows",
                "checkout",
                "checkout-recorder",
                "conformance",
                "profile",
                "reminder",
                "shopping-cart"
            ]
        );
        // No config import natively: the shape defaults to stateless.
        assert!(spec.stateful.is_empty());
    }
}
