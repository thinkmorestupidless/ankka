//! Key value entities: a state replaced whole by each command that changes it.
//!
//! ```ignore
//! pub struct Profile;
//!
//! impl KeyValueEntity for Profile {
//!     type State = ProfileState;
//!     const COMPONENT_ID: &'static str = "profile";
//!
//!     fn empty_state(_: &str) -> ProfileState { ProfileState::default() }
//!     fn handlers() -> KeyValueHandlers<Self> {
//!         KeyValueHandlers::new()
//!             .command("set", Self::set)
//!             .query("get", Self::get)
//!     }
//! }
//! ```

use std::marker::PhantomData;

use serde::Serialize;
use serde::de::DeserializeOwned;

use super::event_sourced::EncodedReply;
use super::{ComponentOf, HeldState, Registered, Shape, Stateful, failure, kinds, name_problem};
use crate::codec::{Auto, decode_payload, encode_payload};
use crate::context::{Context, Metadata};
use crate::effects::key_value::{KeyValueEffect, materialise_key_value};
use crate::effects::{Answer, CommandError, ErrorCode, ReadOnlyEffect};
use crate::proto::{self, Kind, Payload};

/// A key value entity. Implement it on a unit struct and register the struct's value.
pub trait KeyValueEntity: Sized + 'static {
    /// Its state.
    type State: Serialize + DeserializeOwned + 'static;

    /// The component's id.
    const COMPONENT_ID: &'static str;

    /// Whether its instances keep their state between calls; stateless unless said otherwise.
    const SHAPE: Shape = Shape::Stateless;

    /// The manifest its state is stored under; the state type's simple name unless set.
    const STATE_MANIFEST: Option<&'static str> = None;

    /// The state of instance `entity_id` before any command: fresh, deleted or expired.
    fn empty_state(entity_id: &str) -> Self::State;

    /// The handlers, by wire name.
    fn handlers() -> KeyValueHandlers<Self>;

    /// The codec the state is stored with.
    fn state_codec() -> Auto<Self::State> {
        Self::STATE_MANIFEST.map_or_else(Auto::new, Auto::named)
    }
}

type Run<S> =
    Box<dyn Fn(&S, &Payload, &Context) -> Result<KeyValueEffect<S, EncodedReply>, String>>;

pub(crate) struct Entry<S> {
    pub(crate) name: String,
    pub(crate) read_only: bool,
    pub(crate) run: Run<S>,
}

/// A key value entity's handlers, by wire name. A query answers a [`ReadOnlyEffect`].
pub struct KeyValueHandlers<C: KeyValueEntity> {
    pub(crate) entries: Vec<Entry<C::State>>,
    pub(crate) problems: Vec<String>,
    marker: PhantomData<fn() -> C>,
}

impl<C: KeyValueEntity> Default for KeyValueHandlers<C> {
    fn default() -> KeyValueHandlers<C> {
        KeyValueHandlers::new()
    }
}

impl<C: KeyValueEntity> KeyValueHandlers<C> {
    /// No handlers yet.
    pub fn new() -> KeyValueHandlers<C> {
        KeyValueHandlers {
            entries: Vec::new(),
            problems: Vec::new(),
            marker: PhantomData,
        }
    }

    /// A command handler under wire name `name`: it may change the state.
    pub fn command<In, R, F>(self, name: &str, handler: F) -> KeyValueHandlers<C>
    where
        In: DeserializeOwned + 'static,
        R: Serialize + 'static,
        F: Fn(&C::State, In, &Context) -> KeyValueEffect<C::State, R> + 'static,
    {
        self.add(name, false, handler)
    }

    /// A read-only handler under wire name `name`: it answers from the state and changes nothing.
    pub fn query<In, R, F>(self, name: &str, handler: F) -> KeyValueHandlers<C>
    where
        In: DeserializeOwned + 'static,
        R: Serialize + 'static,
        F: Fn(&C::State, In, &Context) -> ReadOnlyEffect<R> + 'static,
    {
        self.add(name, true, move |s: &C::State, i: In, c: &Context| {
            handler(s, i, c).into()
        })
    }

    fn add<In, R>(
        mut self,
        name: &str,
        read_only: bool,
        handler: impl Fn(&C::State, In, &Context) -> KeyValueEffect<C::State, R> + 'static,
    ) -> KeyValueHandlers<C>
    where
        In: DeserializeOwned + 'static,
        R: Serialize + 'static,
    {
        let taken = self.entries.iter().any(|e| e.name == name);
        if let Some(problem) = name_problem("key value entity", C::COMPONENT_ID, name, taken) {
            self.problems.push(problem);
            if taken {
                return self;
            }
        }
        let run: Run<C::State> = Box::new(move |state, payload, ctx| {
            let input: In = decode_payload(payload).map_err(|e| {
                format!(
                    "the input to '{}' is not a {}: {e}",
                    ctx.component_id(),
                    std::any::type_name::<In>()
                )
            })?;
            Ok(handler(state, input, ctx).map_reply(|reply| encode_payload(&reply)))
        });
        self.entries.push(Entry {
            name: name.to_string(),
            read_only,
            run,
        });
        self
    }

    pub(crate) fn find(&self, name: &str) -> Option<&Entry<C::State>> {
        self.entries.iter().find(|e| e.name == name)
    }
}

pub(crate) struct Registration<C: KeyValueEntity> {
    shape: Shape,
    handlers: KeyValueHandlers<C>,
    codec: Auto<C::State>,
}

impl<C: KeyValueEntity> ComponentOf<kinds::KeyValue> for C {
    fn kind() -> Kind {
        Kind::KeyValueEntity
    }

    fn component_id() -> &'static str {
        C::COMPONENT_ID
    }

    fn registration() -> Box<dyn Registered> {
        Box::new(Registration::<C> {
            shape: C::SHAPE,
            handlers: C::handlers(),
            codec: C::state_codec(),
        })
    }
}

impl<C: KeyValueEntity> Registered for Registration<C> {
    fn id(&self) -> &str {
        C::COMPONENT_ID
    }

    fn kind(&self) -> Kind {
        Kind::KeyValueEntity
    }

    fn shape(&self) -> Shape {
        self.shape
    }

    fn set_shape(&mut self, shape: Shape) {
        self.shape = shape;
    }

    fn to_component(&self) -> proto::Component {
        let mut handlers: Vec<proto::Handler> = self
            .handlers
            .entries
            .iter()
            .map(|e| proto::Handler {
                name: e.name.clone(),
                read_only: e.read_only,
                streaming: false,
            })
            .collect();
        handlers.sort_by(|a, b| a.name.cmp(&b.name));
        proto::Component {
            kind: Kind::KeyValueEntity as i32,
            id: C::COMPONENT_ID.to_string(),
            handlers,
            detail: Some(proto::component::Detail::KeyValue(proto::KeyValueDetail {})),
        }
    }

    fn problems(&self) -> Vec<String> {
        let mut problems = self.handlers.problems.clone();
        if C::COMPONENT_ID.is_empty() {
            problems.push("a key value entity has an empty component id".to_string());
        }
        problems
    }

    fn handle(&self, request: proto::HandleRequest, held: &mut HeldState) -> proto::HandleReply {
        let entity_id = request.entity_id.clone();
        let stateful = Stateful {
            shape: self.shape,
            codec: &self.codec,
            entity_id: &entity_id,
        };
        let reply = self.handle_command(&stateful, request, held);
        if reply.failure.is_some() {
            stateful.restore(held);
        }
        reply
    }
}

impl<C: KeyValueEntity> Registration<C> {
    fn handle_command(
        &self,
        stateful: &Stateful<'_, C::State>,
        request: proto::HandleRequest,
        held: &mut HeldState,
    ) -> proto::HandleReply {
        let sent = request.state.clone();
        let fault = |id: i64, message: String| match crate::personal::take_refusal() {
            // A personal field that could not be written refuses the command, as for an event
            // sourced entity; the state stays what it was (1.15).
            Some(refusal) => proto::HandleReply {
                reply: Some(proto::handle_reply::Reply::KeyValue(
                    proto::key_value_out::Reply {
                        command_id: id,
                        outcome: Some(proto::Outcome {
                            outcome: Some(proto::outcome::Outcome::Error(refusal.to_proto())),
                        }),
                        ..Default::default()
                    },
                )),
                state: sent.clone(),
                failure: None,
            },
            None => proto::HandleReply {
                reply: None,
                state: sent.clone(),
                failure: Some(failure(id, ErrorCode::Internal, message)),
            },
        };
        let Some(proto::handle_request::Command::KeyValue(command)) = request.command else {
            return fault(
                0,
                format!(
                    "'{}' is a key value entity and takes only its commands",
                    C::COMPONENT_ID
                ),
            );
        };
        let state =
            match stateful.current(sent.as_ref(), held, || C::empty_state(stateful.entity_id)) {
                Ok(state) => state,
                Err(message) => return fault(command.id, message),
            };
        let metadata = Metadata::from_proto(command.metadata.as_ref());
        let sequence = metadata.sequence_number().unwrap_or(0);
        let ctx = Context::new(C::COMPONENT_ID, stateful.entity_id, sequence, metadata);
        let Some(entry) = self.handlers.find(&command.name) else {
            let refusal = CommandError::new(
                ErrorCode::NotFound,
                format!("no handler '{}' on '{}'", command.name, C::COMPONENT_ID),
            );
            return self.answer(
                stateful,
                held,
                command.id,
                state,
                false,
                None,
                proto::outcome::Outcome::Error(refusal.to_proto()),
                fault,
            );
        };
        let effect = match (entry.run)(&state, &command.payload.unwrap_or_default(), &ctx) {
            Ok(effect) => effect,
            Err(message) => return fault(command.id, message),
        };
        let m = materialise_key_value(state, effect);
        let outcome = match m.outcome {
            Answer::Reply(Ok(payload), metadata) => {
                proto::outcome::Outcome::Reply(proto::outcome::Reply {
                    payload: Some(payload),
                    metadata: Some(metadata.to_proto()),
                })
            }
            Answer::Reply(Err(e), _) => {
                return fault(command.id, format!("computing the reply failed: {e}"));
            }
            Answer::NoReply => proto::outcome::Outcome::NoReply(proto::outcome::NoReply {}),
            Answer::Error(refusal) => proto::outcome::Outcome::Error(refusal.to_proto()),
        };
        self.answer(
            stateful,
            held,
            command.id,
            m.state,
            m.written,
            m.retention,
            outcome,
            fault,
        )
    }

    #[allow(clippy::too_many_arguments)]
    fn answer(
        &self,
        stateful: &Stateful<'_, C::State>,
        held: &mut HeldState,
        command_id: i64,
        state: C::State,
        written: bool,
        retention: Option<crate::effects::Retention>,
        outcome: proto::outcome::Outcome,
        fault: impl Fn(i64, String) -> proto::HandleReply,
    ) -> proto::HandleReply {
        let deleted = retention == Some(crate::effects::Retention::DeleteNow);
        // A deleted entity's next command finds it fresh, so the state after is the empty one.
        let state = if deleted {
            C::empty_state(stateful.entity_id)
        } else {
            state
        };
        let encoded = match stateful.keep(state, held) {
            Ok(encoded) => encoded,
            Err(message) => return fault(command_id, message),
        };
        let reply = proto::key_value_out::Reply {
            command_id,
            new_state: written.then(|| encoded.clone()),
            retention: retention.map(|r| r.to_proto()),
            outcome: Some(proto::Outcome {
                outcome: Some(outcome),
            }),
        };
        proto::HandleReply {
            reply: Some(proto::handle_reply::Reply::KeyValue(reply)),
            state: if deleted { None } else { Some(encoded) },
            failure: None,
        }
    }
}
