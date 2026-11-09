//! The topic view and the relay with nothing running: the kit hands the view a message as the
//! topic would, and reads what the relay would publish.

use ankka::testkit::{ConsumerTestKit, ViewTestKit};
use shopping_cart::stock::{FanLine, StockEvent, StockLevels, WalletRelay};

fn moved(product_id: &str, delta: i32) -> StockEvent {
    StockEvent {
        product_id: product_id.into(),
        delta,
    }
}

// docs:start topic-view-test
#[test]
fn a_products_level_is_the_sum_of_the_topics_messages_about_it() {
    let mut kit = ViewTestKit::<StockLevels>::new();
    // The key is the message's subject, which the broker's record key carries.
    kit.on_event("p1", moved("p1", 5));
    kit.on_event("p1", moved("p1", -2));
    kit.on_event("p2", moved("p2", 1));
    assert_eq!(kit.row("p1").map(|r| r.level), Some(3));
    assert_eq!(kit.row("p2").map(|r| r.level), Some(1));
    assert!(kit.row("p3").is_none());
}
// docs:end topic-view-test

#[test]
fn the_relay_publishes_one_line_per_message_under_its_contract() {
    let kit = ConsumerTestKit::<WalletRelay>::new();
    let published = ConsumerTestKit::<WalletRelay>::messages(&kit.on_message("p1", moved("p1", 5)));
    assert_eq!(published.len(), 1);
    assert_eq!(
        published[0].read::<FanLine>(),
        FanLine {
            product_id: "p1".into(),
            delta: 5,
        }
    );
    // The contract is declared with the publication, for the runtime to check what is published.
    let spec = shopping_cart::stock::build()
        .discover(&Default::default())
        .spec
        .unwrap();
    let relay = spec
        .components
        .iter()
        .find(|c| c.id == "wallet")
        .expect("the relay is declared");
    let ankka::proto::component::Detail::Consumer(detail) = relay.detail.as_ref().unwrap() else {
        panic!("not a consumer")
    };
    let publication = detail.produces.as_ref().expect("a publication");
    assert_eq!(publication.topic, "transactions");
    assert_eq!(
        publication.contract.as_ref().map(|c| c.name.as_str()),
        Some("transaction.v1")
    );
}
