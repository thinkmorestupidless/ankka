//! Consumers: react to every change of a source, at least once and in order per entity — act
//! through the client, or publish onward to a topic.
//!
//! A consumer may be sent the same message again, and must tolerate that; a consumer that panics
//! is sent it again.

use crate::contract::Contract;
use crate::start_from::{self, StartFrom};
use std::marker::PhantomData;

use serde::de::DeserializeOwned;

use super::view::{Source, change_context};
use super::{ComponentOf, Registered, Shape, kinds};
use crate::codec::Auto;
use crate::context::Context;
use crate::context::Metadata;
use crate::effects::consumer::{ConsumerEffect, Outgoing};
use crate::proto::{self, Kind};

/// A consumer. Implement it on a unit struct and register the struct's value.
pub trait Consumer: Sized + 'static {
    /// What the source changes with: its events, its state, or a topic's messages.
    type Message: DeserializeOwned + 'static;

    /// The component's id.
    const COMPONENT_ID: &'static str;

    /// Where the messages come from.
    fn source() -> Source;

    /// Where a topic source starts, the first time its consumer group reads the topic. A consumer that
    /// reads a topic must say: there is no default.
    fn start_from() -> Option<StartFrom> {
        None
    }

    /// Raised to read the topic again from the start position, under a group of its own. A new one
    /// reads under a group of its own.
    /// `None` is version 1. Only for a topic source.
    fn version() -> Option<u32> {
        None
    }

    /// The topic `effects::consumer::produce` and `produce_all` publish to, if the consumer
    /// publishes. The runtime needs a broker for one (`ANKKA_KAFKA_BOOTSTRAP_SERVERS`).
    fn produces_to() -> Option<&'static str> {
        None
    }

    /// The publication, with the contract the consumer states for the topic and the declared
    /// broker it is on. By default the topic `produces_to` names, alone; a consumer that states a
    /// contract or a broker overrides this and leaves `produces_to` alone.
    fn produces() -> Option<Publication> {
        Self::produces_to().map(Publication::to)
    }

    /// One message. The source entity's id is `ctx.metadata().subject()`.
    fn on_message(message: Self::Message, ctx: &Context) -> ConsumerEffect;

    /// The source entity was deleted: ignored unless said otherwise.
    fn on_deleted(ctx: &Context) -> ConsumerEffect {
        let _ = ctx;
        ConsumerEffect::Ignore
    }
}

/// A topic a consumer publishes to, with the contract it states for it and the declared broker
/// the topic is on: `Publication::to("orders").contract(orders).broker("legacy")`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Publication {
    /// The topic, by its declared name.
    pub topic: String,
    /// The contract the consumer states for the topic.
    pub contract: Option<Contract>,
    /// The declared broker the topic is on.
    pub broker: Option<String>,
}

impl Publication {
    /// To `topic`, with no contract, on the installation's broker.
    pub fn to(topic: impl Into<String>) -> Publication {
        Publication {
            topic: topic.into(),
            contract: None,
            broker: None,
        }
    }

    /// The contract the consumer states for the topic.
    pub fn contract(mut self, contract: Contract) -> Publication {
        self.contract = Some(contract);
        self
    }

    /// The declared broker the topic is on.
    pub fn broker(mut self, broker: impl Into<String>) -> Publication {
        self.broker = Some(broker.into());
        self
    }

    pub(crate) fn to_proto(&self) -> proto::Publication {
        proto::Publication {
            topic: self.topic.clone(),
            contract: self.contract.as_ref().map(Contract::to_proto),
            broker: self.broker.clone(),
        }
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
                source: Some(start_from::source_proto(&C::source(), C::start_from())),
                // The topic in both, for a runtime before 1.14, which reads `produces_to` alone.
                produces_to: C::produces().map(|p| p.topic),
                version: C::version(),
                produces: C::produces().map(|p| p.to_proto()),
            })),
        }
    }

    fn problems(&self) -> Vec<String> {
        let mut problems = Vec::new();
        if C::COMPONENT_ID.is_empty() {
            problems.push("a consumer has an empty component id".to_string());
        }
        problems.extend(start_from::problems(
            &format!("consumer '{}'", C::COMPONENT_ID),
            &C::source(),
            C::start_from(),
            C::version(),
            true,
        ));
        if let (Some(to), Some(publication)) = (C::produces_to(), C::produces())
            && to != publication.topic
        {
            problems.push(format!(
                "consumer '{}' names '{to}' in produces_to and '{}' in produces; a consumer \
                 publishes to one topic",
                C::COMPONENT_ID,
                publication.topic
            ));
        }
        problems
    }

    fn consumer(&self, request: proto::ConsumerRequest) -> Option<proto::ConsumerEffect> {
        let ctx = change_context(C::COMPONENT_ID, request.metadata.as_ref())
            .with_secrets()
            .with_services();
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
        Some(to_proto(C::COMPONENT_ID, effect, ctx.metadata()))
    }
}

/// The first protocol version whose runtime publishes several messages for one change.
const SEVERAL_SINCE: (u32, u32) = (1, 3);

/// `major.minor` as numbers, if that is what `version` is.
fn parsed(version: &str) -> Option<(u32, u32)> {
    let (major, minor) = version.split_once('.')?;
    Some((major.parse().ok()?, minor.parse().ok()?))
}

/// Refuses to answer with several messages, or a record key, to a runtime that has not said it
/// accepts them: an earlier one reads the reply as no effect at all, records the change as handled
/// and publishes nothing. Failing the change instead keeps the messages and says what is wrong.
fn guard(request: &Metadata) {
    let speaks = request.protocol();
    if speaks.and_then(parsed).is_some_and(|v| v >= SEVERAL_SINCE) {
        return;
    }
    panic!(
        "this runtime speaks protocol {}; several messages or a record key need {}.{}",
        speaks.unwrap_or("1.2 or earlier"),
        SEVERAL_SINCE.0,
        SEVERAL_SINCE.1
    )
}

/// What a consumer decided, as the protocol carries it. `request` is the metadata of the request
/// being answered, which says what the runtime speaks.
///
/// No messages at all are `done`, and one message that names no key is `produce` — both mean
/// exactly what the several-message form would, and a runtime of any version takes them.
pub(crate) fn to_proto(
    component_id: &str,
    effect: ConsumerEffect,
    request: &Metadata,
) -> proto::ConsumerEffect {
    use proto::consumer_effect::{Effect, Message, Produce, ProduceAll};
    let encoded = |payload: Result<proto::Payload, crate::codec::EncodingError>| {
        payload.unwrap_or_else(|e| {
            panic!("consumer '{component_id}': what it produces does not encode: {e}")
        })
    };
    let effect = match effect {
        ConsumerEffect::Done => Effect::Done(proto::Empty {}),
        ConsumerEffect::Ignore => Effect::Ignore(proto::Empty {}),
        ConsumerEffect::Produce(payload, metadata) => Effect::Produce(Produce {
            payload: Some(encoded(payload)),
            metadata: Some(metadata.to_proto()),
        }),
        ConsumerEffect::ProduceAll(messages) => {
            let mut messages: Vec<_> = messages.into_iter().map(Outgoing::into_parts).collect();
            if messages
                .iter()
                .any(|(_, key, _)| key.as_deref() == Some(""))
            {
                panic!(
                    "consumer '{component_id}': a record key must not be empty; leave it out to \
                     key a message by its subject"
                )
            }
            match messages.len() {
                0 => Effect::Done(proto::Empty {}),
                1 if messages[0].1.is_none() => {
                    let (payload, _, metadata) = messages.remove(0);
                    Effect::Produce(Produce {
                        payload: Some(encoded(payload)),
                        metadata: Some(metadata.to_proto()),
                    })
                }
                _ => {
                    guard(request);
                    Effect::ProduceAll(ProduceAll {
                        messages: messages
                            .into_iter()
                            .map(|(payload, key, metadata)| Message {
                                payload: Some(encoded(payload)),
                                metadata: Some(metadata.to_proto()),
                                key,
                            })
                            .collect(),
                    })
                }
            }
        }
    };
    proto::ConsumerEffect {
        effect: Some(effect),
    }
}
