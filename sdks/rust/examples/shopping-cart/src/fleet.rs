//! The consumers page's mirrored-change example: a consumer over a replicated entity that is given
//! changes written at other installations too, and reads where each was written. Not in the
//! module's service.

use ankka::effects::consumer;
use ankka::prelude::*;

use crate::conformance::{ReplicatedVehicle, StepAdded};

/// What leaves the service for each step: the vehicle, the step, and where the change was written.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct Build {
    pub vehicle: String,
    pub step: String,
    pub installation: String,
    pub local: bool,
}

// docs:start mirrored-change
/// Publishes each step to "vehicle-builds" at every installation that holds the vehicle: declared
/// to be given mirrored changes, it reads where each change was written and decides.
pub struct BuildsConsumer;

impl Consumer for BuildsConsumer {
    type Message = StepAdded;
    const COMPONENT_ID: &'static str = "builds";

    fn source() -> Source {
        Source::of(ReplicatedVehicle)
    }

    fn produces_to() -> Option<&'static str> {
        Some("vehicle-builds")
    }

    fn mirrored_changes() -> bool {
        true
    }

    fn on_message(message: StepAdded, ctx: &Context) -> ConsumerEffect {
        consumer::produce(Build {
            vehicle: ctx.metadata().subject().unwrap_or_default().to_string(),
            step: message.step,
            // The installation that wrote the change, and whether that was this one.
            installation: ctx.written_at().unwrap_or_default().to_string(),
            local: ctx.local_origin(),
        })
    }
}
// docs:end mirrored-change
