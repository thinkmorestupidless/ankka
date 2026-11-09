//! The mirrored-change consumer with nothing running.

use ankka::testkit::ConsumerTestKit;
use shopping_cart::conformance::StepAdded;
use shopping_cart::fleet::{Build, BuildsConsumer};

#[test]
fn each_step_is_published_with_where_it_was_written() {
    let kit = ConsumerTestKit::<BuildsConsumer>::new();
    let effect = kit.on_message(
        "v1",
        StepAdded {
            step: "paint".into(),
        },
    );
    let published = ConsumerTestKit::<BuildsConsumer>::messages(&effect);
    assert_eq!(published.len(), 1);
    // The kit hands a change written here, with no installation named, as a service outside a
    // cluster is given one.
    assert_eq!(
        published[0].read::<Build>(),
        Build {
            vehicle: "v1".into(),
            step: "paint".into(),
            installation: String::new(),
            local: true,
        }
    );
}

#[test]
fn the_consumer_declares_that_it_takes_mirrored_changes() {
    let spec = ankka::Service::new("test")
        .register(BuildsConsumer)
        .discover(&Default::default())
        .spec
        .unwrap();
    let ankka::proto::component::Detail::Consumer(detail) =
        spec.components[0].detail.as_ref().unwrap()
    else {
        panic!("not a consumer")
    };
    assert!(detail.mirrored_changes);
}
