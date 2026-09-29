//! Effects for consumers: what one message comes to.

use serde::Serialize;

use crate::codec::{EncodingError, encode_payload};
use crate::context::Metadata;
use crate::proto::Payload;

/// What a consumer decided about one message.
#[derive(Debug, Clone)]
pub enum ConsumerEffect {
    /// Handled.
    Done,
    /// Not of interest.
    Ignore,
    /// Publish this onward, to the topic the consumer declares.
    Produce(Result<Payload, EncodingError>, Metadata),
}

/// The message was handled.
pub fn done() -> ConsumerEffect {
    ConsumerEffect::Done
}

/// The message is not of interest.
pub fn ignore() -> ConsumerEffect {
    ConsumerEffect::Ignore
}

/// Publish `message` to the consumer's topic.
pub fn produce<T: Serialize + 'static>(message: T) -> ConsumerEffect {
    ConsumerEffect::Produce(encode_payload(&message), Metadata::default())
}
