//! Consumers: react to every change of a source, at least once and in order per entity — act
//! through the client, or publish onward to a topic.
//!
//! A consumer may be sent the same message again, and must tolerate that; a consumer that panics
//! is sent it again.

use std::marker::PhantomData;

use serde::de::DeserializeOwned;

use super::view::{Source, change_context};
use super::{ComponentOf, Registered, Shape, kinds};
use crate::codec::Auto;
use crate::context::Context;
use crate::effects::consumer::ConsumerEffect;
use crate::proto::{self, Kind};

/// A consumer. Implement it on a unit struct and register the struct's value.
pub trait Consumer: Sized + 'static {
    /// What the source changes with: its events, its state, or a topic's messages.
    type Message: DeserializeOwned + 'static;

    /// The component's id.
    const COMPONENT_ID: &'static str;

    /// Where the messages come from.
    fn source() -> Source;

    /// The topic `effects::consumer::produce` publishes to, if the consumer publishes. The runtime
    /// needs a broker for one (`ANKKA_KAFKA_BOOTSTRAP_SERVERS`).
    fn produces_to() -> Option<&'static str> {
        None
    }

    /// One message. The source entity's id is `ctx.metadata().subject()`.
    fn on_message(message: Self::Message, ctx: &Context) -> ConsumerEffect;

    /// The source entity was deleted: ignored unless said otherwise.
    fn on_deleted(ctx: &Context) -> ConsumerEffect {
        let _ = ctx;
        ConsumerEffect::Ignore
    }
}

pub(crate) struct Registration<C: Consumer> {
    marker: PhantomData<fn() -> C>,
}

impl<C: Consumer> ComponentOf<kinds::Consumer> for C {
    fn kind() -> Kind {
        Kind::Consumer
    }

    fn component_id() -> &'static str {
        C::COMPONENT_ID
    }

    fn registration() -> Box<dyn Registered> {
        Box::new(Registration::<C> {
            marker: PhantomData,
        })
    }
}

impl<C: Consumer> Registered for Registration<C> {
    fn id(&self) -> &str {
        C::COMPONENT_ID
    }

    fn kind(&self) -> Kind {
        Kind::Consumer
    }

    fn shape(&self) -> Shape {
        Shape::Stateless
    }

    fn to_component(&self) -> proto::Component {
        proto::Component {
            kind: Kind::Consumer as i32,
            id: C::COMPONENT_ID.to_string(),
            handlers: Vec::new(),
            detail: Some(proto::component::Detail::Consumer(proto::ConsumerDetail {
                source: Some(C::source().to_proto()),
                produces_to: C::produces_to().map(str::to_string),
            })),
        }
    }

    fn problems(&self) -> Vec<String> {
        let mut problems = Vec::new();
        if C::COMPONENT_ID.is_empty() {
            problems.push("a consumer has an empty component id".to_string());
        }
        problems
    }

    fn consumer(&self, request: proto::ConsumerRequest) -> Option<proto::ConsumerEffect> {
        let ctx = change_context(C::COMPONENT_ID, request.metadata.as_ref());
        let effect = match request.message.filter(|_| !request.deleted) {
            Some(message) => {
                let message: C::Message = Auto::<C::Message>::new()
                    .decode_value(&message.data)
                    .unwrap_or_else(|e| {
                        panic!(
                            "consumer '{}': a message does not decode: {e}",
                            C::COMPONENT_ID
                        )
                    });
                C::on_message(message, &ctx)
            }
            None => C::on_deleted(&ctx),
        };
        use proto::consumer_effect::{Effect, Produce};
        let effect = match effect {
            ConsumerEffect::Done => Effect::Done(proto::Empty {}),
            ConsumerEffect::Ignore => Effect::Ignore(proto::Empty {}),
            ConsumerEffect::Produce(payload, metadata) => Effect::Produce(Produce {
                payload: Some(payload.unwrap_or_else(|e| {
                    panic!(
                        "consumer '{}': what it produces does not encode: {e}",
                        C::COMPONENT_ID
                    )
                })),
                metadata: Some(metadata.to_proto()),
            }),
        };
        Some(proto::ConsumerEffect {
            effect: Some(effect),
        })
    }
}
