//! The shopping cart every ankka SDK carries, as a WebAssembly module: an event sourced cart, a view
//! of every cart, a checkout workflow, a consumer and the log it writes, the carts as a graph, an
//! agent, and the HTTP API in front of them. `cargo build --release` in this directory builds the module; with the
//! `conformance` feature, the module is the conformance reference instead ([`conformance`]).

pub mod assistant;
pub mod cart_contents_graph;
pub mod cart_graph;
pub mod cart_rows;
pub mod checkout_log;
pub mod checkout_notifier;
pub mod checkout_workflow;
pub mod conformance;
pub mod domain;
pub mod endpoint;
pub mod entity;
pub mod service_calls;

use ankka::Service;

// docs:start service
/// Everything the module hosts, registered by value; `service!` emits the exports once.
pub fn build() -> Service {
    let service = Service::new("ankka-rust")
        .register(entity::ShoppingCart)
        .register(cart_rows::CartRows)
        .register(checkout_workflow::CheckoutWorkflow)
        .register(checkout_notifier::CheckoutNotifier)
        .register(checkout_log::CheckoutLog)
        .register(assistant::CartAssistant)
        .endpoint(endpoint::CartApi);
    // The carts' graph is published to a topic, and a component that publishes needs a broker:
    // it is registered when the service's descriptor names one.
    match ankka::config("ANKKA_KAFKA_BOOTSTRAP_SERVERS") {
        Some(_) => service
            .register(cart_graph::CartGraph)
            .register(cart_contents_graph::CartContentsGraph),
        None => service,
    }
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

    fn conformance_ids() -> Vec<String> {
        let service = super::conformance::build().build().expect("no problems");
        let spec = service.discover(&SidecarInfo::default());
        // No shape is named: the module is stateless.
        assert!(spec.stateful.is_empty());
        spec.spec
            .unwrap()
            .components
            .into_iter()
            .map(|c| c.id)
            .collect()
    }

    #[test]
    fn the_conformance_reference_declares_what_the_suite_expects() {
        // A broker named, as the conformance suite names one and a descriptor would.
        let ids = ankka::testkit::with_config(
            &[("ANKKA_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")],
            conformance_ids,
        );
        assert_eq!(
            ids,
            vec![
                "answerer",
                "assistant",
                "cart-graph",
                "cart-rows",
                "checkout",
                "checkout-fanout",
                "checkout-recorder",
                "conformance",
                "contract-relay",
                "joined-left",
                "joined-right",
                "joined-rows",
                "member",
                "member-rows",
                "profile",
                "profile-graph",
                "reminder",
                "shopping-cart",
                "topic-relay",
                "topic-rows",
                "tree-node",
                "tree-rows"
            ]
        );
    }

    #[test]
    fn with_no_broker_named_the_reference_registers_nothing_that_publishes() {
        // A runtime with no broker refuses a module with a publishing consumer, and the cluster
        // suite deploys this module where there is none.
        let ids = conformance_ids();
        assert!(
            !ids.iter()
                .any(|id| ["cart-graph", "checkout-fanout", "profile-graph"].contains(&id.as_str())),
            "{ids:?}"
        );
        assert_eq!(ids.len(), 16, "{ids:?}");
    }

    use crate::conformance::{
        CheckoutFanout, ConformanceCartGraph, Fanned, ProfileGraph, ProfileState,
    };
    use crate::domain::{LineItem, ShoppingCartEvent};
    use ankka::graph::ElementKind;
    use ankka::prelude::ConsumerEffect;
    use ankka::testkit::{ConsumerTestKit, GraphConsumerTestKit};

    // docs:start consumer-test
    /// The fan-out the suite's several-message cases read, as every reference publishes it.
    #[test]
    fn the_reference_fans_a_checkout_out_into_three_messages() {
        let kit = ConsumerTestKit::<CheckoutFanout>::new().at(2);
        let added = ShoppingCartEvent::ItemAdded {
            item: LineItem {
                product_id: "p1".into(),
                name: "Pen".into(),
                quantity: 1,
            },
        };
        assert!(matches!(kit.on_message("c1", added), ConsumerEffect::Done));

        let messages = ConsumerTestKit::<CheckoutFanout>::messages(
            &kit.on_message("c1", ShoppingCartEvent::CheckedOut),
        );
        let ns: Vec<i32> = messages.iter().map(|m| m.read::<Fanned>().n).collect();
        assert_eq!(ns, vec![1, 2, 3]);
        let keys: Vec<Option<&str>> = messages.iter().map(|m| m.key.as_deref()).collect();
        assert_eq!(keys, vec![None, Some("second:c1"), None]);
        assert_eq!(messages[2].metadata.get("x-n"), Some("3"));
        assert!(messages.iter().all(|m| m.payload.manifest == "fanned"));
        assert_eq!(messages[0].payload.data, br#"{"n":1}"#);

        // An item removed is one message, the old way, on a runtime of any version.
        let removed = ShoppingCartEvent::ItemRemoved {
            product_id: "p1".into(),
        };
        let earlier = ConsumerTestKit::<CheckoutFanout>::new().speaking(None);
        match earlier.on_message("c1", removed) {
            ConsumerEffect::Produce(Ok(payload), _) => {
                assert_eq!(payload.manifest, "fanned");
                assert_eq!(payload.data, br#"{"n":0}"#);
            }
            other => panic!("{other:?}"),
        }
        let discarded = kit.on_message("c1", ShoppingCartEvent::Discarded);
        assert!(matches!(discarded, ConsumerEffect::Ignore));
    }
    // docs:end consumer-test

    /// The scripted history of the suite's graph case: add, add, remove, check out.
    #[test]
    fn the_reference_publishes_the_cart_graph_and_the_profile_graph() {
        let kit = GraphConsumerTestKit::<ConformanceCartGraph>::new();
        let item = || LineItem {
            product_id: "p1".into(),
            name: "Pen".into(),
            quantity: 1,
        };
        let history = [
            ShoppingCartEvent::ItemAdded { item: item() },
            ShoppingCartEvent::ItemAdded { item: item() },
            ShoppingCartEvent::ItemRemoved {
                product_id: "p1".into(),
            },
            ShoppingCartEvent::CheckedOut,
        ];
        let mut published: Vec<(String, i64)> = Vec::new();
        for (index, event) in history.into_iter().enumerate() {
            for element in kit.on_message("g1", index as i64 + 1, event) {
                published.push((element.key(), element.version().unwrap()));
            }
        }
        let expected: Vec<(String, i64)> = [
            ("node:cart:g1", 1),
            ("node:cart:g1", 2),
            ("node:cart:g1", 3),
            ("node:cart:g1", 4),
            ("node:checkout:g1", 4),
            ("edge:checked-out:g1", 4),
        ]
        .into_iter()
        .map(|(key, version)| (key.to_string(), version))
        .collect();
        assert_eq!(published, expected);

        let profiles = GraphConsumerTestKit::<ProfileGraph>::new();
        let state = profiles.on_message("p1", 1, ProfileState { name: "Ada".into() });
        assert_eq!(state[0].key(), "node:profile:p1");
        assert_eq!(state[0].labels(), ["Profile".to_string()]);
        assert_eq!(
            state[0].get("name"),
            Some(&ankka::graph::Value::from("Ada"))
        );
        assert_eq!(state[0].version(), Some(1));
        let deleted = profiles.on_deleted("p1", 2);
        assert_eq!(deleted[0].kind(), ElementKind::NodeTombstone);
        assert_eq!(deleted[0].key(), "node:profile:p1");
        assert_eq!(deleted[0].version(), Some(2));
    }
}
