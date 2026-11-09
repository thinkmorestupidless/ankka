//! The replicated vehicle and the mirrored-change consumer with nothing running.

use ankka::prelude::*;
use ankka::testkit::{ConsumerTestKit, EventSourcedTestKit, ReplicationMode};
use shopping_cart::conformance::{ReplicatedVehicle, StepAdded, VehicleRecord};
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

// docs:start follower-kit
#[test]
fn a_follower_answers_queries_from_its_mirrored_history_and_refuses_to_write() {
    let mut kit =
        EventSourcedTestKit::<ReplicatedVehicle>::new("v1").in_mode(ReplicationMode::Follower {
            home: "leipzig".into(),
            installation: "bratislava".into(),
        });
    // History the copy mirrored from its home, written there.
    kit.replay(
        vec![
            StepAdded {
                step: "frame".into(),
            },
            StepAdded {
                step: "paint".into(),
            },
        ],
        Some("leipzig"),
    );
    let vehicle = kit.command("get", ()).reply::<VehicleRecord>().unwrap();
    assert_eq!(vehicle.steps, vec!["frame", "paint"]);
    assert_eq!(vehicle.written_at, vec!["leipzig", "leipzig"]);
    assert_eq!(vehicle.local, vec![false, false]);
    // A command that may persist is refused: the home is the only copy that writes.
    let refused = kit.command("add-step", "wheels".to_string());
    assert_eq!(refused.error().map(|e| e.code), Some(ErrorCode::NotHome));
    assert!(refused.error().unwrap().message.contains("leipzig"));
}
// docs:end follower-kit
