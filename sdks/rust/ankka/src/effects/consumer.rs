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
    /// Publish these onward, in this order. The change is handled when the broker has accepted all
    /// of them; if one is refused the change comes again, and all are published again. None at all
    /// is [`Done`](ConsumerEffect::Done).
    ProduceAll(Vec<Outgoing>),
}

/// One message of several: what to publish, its headers, and optionally the record key it is
/// published under.
///
/// A message with no key is keyed by its subject — the source entity's id unless its metadata sets
/// `ce-subject` — which is what keeps everything about one entity on one partition, in order. A key
/// is for a message about something else: a line item, an element of a graph. Naming one does not
/// change the subject.
#[derive(Debug, Clone)]
pub struct Outgoing {
    payload: Result<Payload, EncodingError>,
    metadata: Metadata,
    key: Option<String>,
}

impl Outgoing {
    /// A message already encoded, under a codec of the service's choosing:
    /// `Outgoing::of(Auto::<Line>::named("line").to_payload(&line))`.
    pub fn of(payload: Result<Payload, EncodingError>) -> Outgoing {
        Outgoing {
            payload,
            metadata: Metadata::default(),
            key: None,
        }
    }

    /// Publish under `key` instead of the subject. An empty key is refused when the effect is
    /// dispatched: leave the key out to key by the subject.
    pub fn key(mut self, key: impl Into<String>) -> Outgoing {
        self.key = Some(key.into());
        self
    }

    /// Publish with these headers.
    pub fn metadata(mut self, metadata: Metadata) -> Outgoing {
        self.metadata = metadata;
        self
    }

    /// The message as it is published: its payload, the key it named and its headers.
    pub fn into_parts(self) -> (Result<Payload, EncodingError>, Option<String>, Metadata) {
        (self.payload, self.key, self.metadata)
    }
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

/// Publish `message` to the consumer's topic with these headers. Set `ce-subject` to key it, and
/// order it, by something other than the source entity's id.
pub fn produce_with<T: Serialize + 'static>(message: T, metadata: Metadata) -> ConsumerEffect {
    ConsumerEffect::Produce(encode_payload(&message), metadata)
}

/// One message of several, for [`produce_all`]: add a key with `.key(…)`, headers with
/// `.metadata(…)`.
pub fn message<T: Serialize + 'static>(message: T) -> Outgoing {
    Outgoing::of(encode_payload(&message))
}

/// Publish several messages for this change, in order. The change is handled when all of them are
/// accepted. An empty list publishes nothing and is handled at once.
///
/// It needs a runtime that speaks protocol 1.3: on an earlier one the change fails, saying so,
/// rather than have its messages dropped.
pub fn produce_all(messages: impl IntoIterator<Item = Outgoing>) -> ConsumerEffect {
    ConsumerEffect::ProduceAll(messages.into_iter().collect())
}
