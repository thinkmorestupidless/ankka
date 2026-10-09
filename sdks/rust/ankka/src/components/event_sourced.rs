//! Event sourced entities: state derived by folding persisted events.
//!
//! ```text
//! pub struct ShoppingCart;
//!
//! impl EventSourcedEntity for ShoppingCart {
//!     type State = Cart;
//!     type Event = CartEvent;
//!     const COMPONENT_ID: &'static str = "shopping-cart";
//!     const STATE_MANIFEST: Option<&'static str> = Some("shopping-cart");
//!     const EVENT_MANIFEST: Option<&'static str> = Some("shopping-cart-event");
//!
//!     fn empty_state(cart_id: &str) -> Cart { Cart::empty(cart_id) }
//!     fn apply(cart: Cart, event: &CartEvent) -> Cart { … }
//!     fn handlers() -> Handlers<Self> {
//!         Handlers::new()
//!             .command("add-item", Self::add_item)
//!             .query("get-cart", Self::get_cart)
//!     }
//! }
//! ```
//!
//! The wire name is declared separately from the function on purpose: it is a versioning boundary,
//! and renaming a function must not change the protocol under requests already in flight.

use std::marker::PhantomData;

use serde::Serialize;
use serde::de::DeserializeOwned;

use super::{ComponentOf, HeldState, Registered, Shape, kinds};
use crate::codec::{Auto, Codec, EncodingError, decode_payload, encode_payload};
use crate::context::{Context, Metadata};
use crate::effects::{
    Answer, CommandError, Effect, ErrorCode, ReadOnlyEffect, materialise_event_sourced,
};
use crate::proto::{self, Kind, Payload};

/// An event sourced entity. Implement it on a unit struct and register the struct's value.
pub trait EventSourcedEntity: Sized + 'static {
    /// The state its events fold into.
    type State: Serialize + DeserializeOwned + 'static;

    /// Its events: an enum declared `#[serde(tag = "type")]`, so each is stored as
    /// `{"type":"<Variant>",…}` as every other language stores it.
    type Event: Serialize + DeserializeOwned + 'static;

    /// The component's id: what the runtime shards it under and callers name it by.
    const COMPONENT_ID: &'static str;

    /// Whether its instances keep their state between calls; stateless unless said otherwise.
    const SHAPE: Shape = Shape::Stateless;

    /// The manifest its state is stored under; the state type's simple name unless set. Set it to
    /// what another language's service writes, to share a journal with it.
    const STATE_MANIFEST: Option<&'static str> = None;

    /// The manifest its events are stored under; the event type's simple name unless set.
    const EVENT_MANIFEST: Option<&'static str> = None;

    /// The state of instance `entity_id` with no events: fresh, deleted or expired. The id is
    /// there for a state that carries it, as a cart that knows its own id does.
    fn empty_state(entity_id: &str) -> Self::State;

    /// The state after one more event. Called on replay as well as after a command, so it decides
    /// nothing: every decision was the handler's.
    fn apply(state: Self::State, event: &Self::Event) -> Self::State;

    /// The handlers, by wire name.
    fn handlers() -> Handlers<Self>;

    /// Snapshot every this many events; never at 0, the default.
    fn snapshot_every() -> u32 {
        0
    }

    /// The codec the state is stored with.
    fn state_codec() -> Auto<Self::State> {
        Self::STATE_MANIFEST.map_or_else(Auto::new, Auto::named)
    }

    /// The codec the events are stored with.
    fn event_codec() -> Auto<Self::Event> {
        Self::EVENT_MANIFEST.map_or_else(Auto::new, Auto::named)
    }
}

/// A reply once it has been computed: the payload, or why the value could not be written.
pub type EncodedReply = Result<Payload, EncodingError>;

/// A handler with its input decoded and its reply encoded: what the table keeps.
pub(crate) type ErasedHandler<S, E> =
    Box<dyn Fn(&S, &Payload, &Context) -> Result<Effect<E, EncodedReply>, String>>;

/// One handler in the table.
pub(crate) struct HandlerEntry<S, E> {
    pub(crate) name: String,
    pub(crate) read_only: bool,
    pub(crate) run: ErasedHandler<S, E>,
}

/// An entity's handlers, by wire name. A command may persist; a query answers a
/// [`ReadOnlyEffect`], so one that tried to persist would not compile.
pub struct Handlers<C: EventSourcedEntity> {
    pub(crate) entries: Vec<HandlerEntry<C::State, C::Event>>,
    pub(crate) problems: Vec<String>,
    marker: PhantomData<fn() -> C>,
}

impl<C: EventSourcedEntity> Default for Handlers<C> {
    fn default() -> Handlers<C> {
        Handlers::new()
    }
}

impl<C: EventSourcedEntity> Handlers<C> {
    /// No handlers yet.
    pub fn new() -> Handlers<C> {
        Handlers {
            entries: Vec::new(),
            problems: Vec::new(),
            marker: PhantomData,
        }
    }

    /// A command handler under wire name `name`: it may persist events.
    pub fn command<In, R, F>(self, name: &str, handler: F) -> Handlers<C>
    where
        In: DeserializeOwned + 'static,
        R: Serialize + 'static,
        F: Fn(&C::State, In, &Context) -> Effect<C::Event, R> + 'static,
    {
        self.add(name, false, move |state, input, ctx| {
            handler(state, input, ctx)
        })
    }

    /// A read-only handler under wire name `name`: it answers from the state and persists nothing.
    pub fn query<In, R, F>(self, name: &str, handler: F) -> Handlers<C>
    where
        In: DeserializeOwned + 'static,
        R: Serialize + 'static,
        F: Fn(&C::State, In, &Context) -> ReadOnlyEffect<R> + 'static,
    {
        self.add(name, true, move |state, input, ctx| {
            handler(state, input, ctx).into()
        })
    }

    fn add<In, R>(
        mut self,
        name: &str,
        read_only: bool,
        handler: impl Fn(&C::State, In, &Context) -> Effect<C::Event, R> + 'static,
    ) -> Handlers<C>
    where
        In: DeserializeOwned + 'static,
        R: Serialize + 'static,
    {
        if name.is_empty() {
            self.problems.push(format!(
                "event sourced entity '{}' has a handler with no name",
                C::COMPONENT_ID
            ));
        } else if self.entries.iter().any(|e| e.name == name) {
            self.problems.push(format!(
                "event sourced entity '{}' declares handler '{name}' twice",
                C::COMPONENT_ID
            ));
            return self;
        }
        let run: ErasedHandler<C::State, C::Event> = Box::new(move |state, payload, ctx| {
            let input: In = decode_payload(payload).map_err(|e| {
                format!(
                    "the input to '{}' is not a {}: {e}",
                    ctx.component_id(),
                    std::any::type_name::<In>()
                )
            })?;
            Ok(handler(state, input, ctx).map_reply(|reply| encode_payload(&reply)))
        });
        self.entries.push(HandlerEntry {
            name: name.to_string(),
            read_only,
            run,
        });
        self
    }

    /// The handler under `name`.
    pub(crate) fn find(&self, name: &str) -> Option<&HandlerEntry<C::State, C::Event>> {
        self.entries.iter().find(|e| e.name == name)
    }

    /// The wire names and whether each is read-only, in declaration order.
    pub fn names(&self) -> Vec<(&str, bool)> {
        self.entries
            .iter()
            .map(|e| (e.name.as_str(), e.read_only))
            .collect()
    }
}

/// An event sourced entity as the registry holds it.
pub(crate) struct Registration<C: EventSourcedEntity> {
    pub(crate) handlers: Handlers<C>,
    pub(crate) shape: Shape,
}

impl<C: EventSourcedEntity> ComponentOf<kinds::EventSourced> for C {
    fn kind() -> Kind {
        Kind::EventSourcedEntity
    }

    fn component_id() -> &'static str {
        C::COMPONENT_ID
    }

    fn registration() -> Box<dyn Registered> {
        Box::new(Registration::<C> {
            handlers: C::handlers(),
            shape: C::SHAPE,
        })
    }
}

impl<C: EventSourcedEntity> Registered for Registration<C> {
    fn id(&self) -> &str {
        C::COMPONENT_ID
    }

    fn kind(&self) -> Kind {
        Kind::EventSourcedEntity
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
            kind: Kind::EventSourcedEntity as i32,
            id: C::COMPONENT_ID.to_string(),
            handlers,
            detail: Some(proto::component::Detail::EventSourced(
                proto::EventSourcedDetail {
                    snapshot_every: C::snapshot_every() as i32,
                },
            )),
        }
    }

    fn problems(&self) -> Vec<String> {
        let mut problems = self.handlers.problems.clone();
        if C::COMPONENT_ID.is_empty() {
            problems.push("an event sourced entity has an empty component id".to_string());
        }
        problems
    }

    fn handle(&self, request: proto::HandleRequest, held: &mut HeldState) -> proto::HandleReply {
        let entity_id = request.entity_id.clone();
        let reply = self.handle_command(request, held);
        if reply.failure.is_some() {
            restore::<C>(self.shape, &entity_id, held);
        }
        reply
    }

    fn fold(&self, request: proto::FoldRequest, held: &mut HeldState) -> proto::FoldReply {
        let entity_id = request.entity_id.clone();
        let reply = self.fold_event(request, held);
        if reply.failure.is_some() {
            restore::<C>(self.shape, &entity_id, held);
        }
        reply
    }
}

impl<C: EventSourcedEntity> Registration<C> {
    fn handle_command(
        &self,
        request: proto::HandleRequest,
        held: &mut HeldState,
    ) -> proto::HandleReply {
        let entity_id = request.entity_id.clone();
        let Some(proto::handle_request::Command::EventSourced(command)) = request.command else {
            let message = format!(
                "'{}' is an event sourced entity and takes only its commands",
                C::COMPONENT_ID
            );
            return fault(request.state, 0, ErrorCode::BadRequest, message);
        };
        let state = match current_state::<C>(self.shape, request.state.as_ref(), &entity_id, held) {
            Ok(state) => state,
            Err(message) => return fault(request.state, command.id, ErrorCode::Internal, message),
        };
        let metadata = Metadata::from_proto(command.metadata.as_ref());
        let sequence = metadata.sequence_number().unwrap_or(0);
        let ctx = Context::new(C::COMPONENT_ID, entity_id.clone(), sequence, metadata);
        let Some(entry) = self.handlers.find(&command.name) else {
            let refusal = CommandError::new(
                ErrorCode::NotFound,
                format!("no handler '{}' on '{}'", command.name, C::COMPONENT_ID),
            );
            let outcome = proto::Outcome {
                outcome: Some(proto::outcome::Outcome::Error(refusal.to_proto())),
            };
            let reply = proto::event_sourced_out::Reply {
                command_id: command.id,
                outcome: Some(outcome),
                ..Default::default()
            };
            return reply_with::<C>(self.shape, reply, state, &entity_id, held);
        };
        let effect = match (entry.run)(&state, &command.payload.unwrap_or_default(), &ctx) {
            Ok(effect) => effect,
            Err(message) => return fault(request.state, command.id, ErrorCode::Internal, message),
        };
        let m = materialise_event_sourced(state, C::apply, effect);
        let event_codec = C::event_codec();
        let events: Result<Vec<Payload>, EncodingError> =
            m.events.iter().map(|e| event_codec.payload(e)).collect();
        let events = match events {
            Ok(events) => events,
            Err(e) => {
                return fault(
                    request.state,
                    command.id,
                    ErrorCode::Internal,
                    format!("an event does not encode: {e}"),
                );
            }
        };
        let outcome = match m.outcome {
            Answer::Reply(Ok(payload), metadata) => {
                proto::outcome::Outcome::Reply(proto::outcome::Reply {
                    payload: Some(payload),
                    metadata: Some(metadata.to_proto()),
                })
            }
            Answer::Reply(Err(e), _) => {
                let message = format!("computing the reply failed: {e}");
                return fault(request.state, command.id, ErrorCode::Internal, message);
            }
            Answer::NoReply => proto::outcome::Outcome::NoReply(proto::outcome::NoReply {}),
            Answer::Error(refusal) => proto::outcome::Outcome::Error(refusal.to_proto()),
        };
        let refused = matches!(outcome, proto::outcome::Outcome::Error(_));
        let snapshot = if command.snapshot_requested && !refused {
            match C::state_codec().to_payload(&m.new_state) {
                Ok(snapshot) => Some(snapshot),
                Err(e) => {
                    return fault(
                        request.state,
                        command.id,
                        ErrorCode::Internal,
                        format!("the state does not encode: {e}"),
                    );
                }
            }
        } else {
            None
        };
        let reply = proto::event_sourced_out::Reply {
            command_id: command.id,
            events,
            retention: m.retention.map(|r| r.to_proto()),
            outcome: Some(proto::Outcome {
                outcome: Some(outcome),
            }),
            snapshot,
        };
        reply_with::<C>(self.shape, reply, m.new_state, &entity_id, held)
    }

    fn fold_event(&self, request: proto::FoldRequest, held: &mut HeldState) -> proto::FoldReply {
        let failed = |state: Option<Payload>, message: String| proto::FoldReply {
            state,
            failure: Some(proto::Failure {
                command_id: 0,
                error: Some(CommandError::new(ErrorCode::Internal, message).to_proto()),
            }),
        };
        let state = match current_state::<C>(
            self.shape,
            request.state.as_ref(),
            &request.entity_id,
            held,
        ) {
            Ok(state) => state,
            Err(message) => return failed(request.state, message),
        };
        let event: C::Event =
            match C::event_codec().decode_value(&request.event.clone().unwrap_or_default().data) {
                Ok(event) => event,
                Err(e) => return failed(request.state, format!("the event does not decode: {e}")),
            };
        let new_state = C::apply(state, &event);
        match C::state_codec().to_payload(&new_state) {
            Ok(payload) => {
                if self.shape == Shape::Stateful {
                    held.put(&request.entity_id, new_state);
                    held.put_encoded(&request.entity_id, payload.clone());
                }
                proto::FoldReply {
                    state: Some(payload),
                    failure: None,
                }
            }
            Err(e) => failed(request.state, format!("the state does not encode: {e}")),
        }
    }
}

/// The state a call starts from: the one the runtime sent, else — for a stateful entity — the one
/// kept since the runtime last sent it, else the empty state.
fn current_state<C: EventSourcedEntity>(
    shape: Shape,
    sent: Option<&Payload>,
    entity_id: &str,
    held: &mut HeldState,
) -> Result<C::State, String> {
    match sent {
        Some(payload) => C::state_codec()
            .decode_value(&payload.data)
            .map_err(|e| format!("the state does not decode: {e}")),
        None if shape == Shape::Stateful => Ok(held
            .take::<C::State>(entity_id)
            .unwrap_or_else(|| C::empty_state(entity_id))),
        None => Ok(C::empty_state(entity_id)),
    }
}

/// After a fault nothing applies, so a stateful entity goes on from the state it kept before the
/// call — which the call took to work on, and is decoded again from its encoded copy. The runtime
/// does not send the state again after a fault it was answered, so losing it here would start the
/// next command from the empty state.
fn restore<C: EventSourcedEntity>(shape: Shape, entity_id: &str, held: &mut HeldState) {
    if shape != Shape::Stateful || held.get::<C::State>(entity_id).is_some() {
        return;
    }
    if let Some(encoded) = held.encoded(entity_id)
        && let Ok(state) = C::state_codec().decode_value(&encoded.data)
    {
        held.put(entity_id, state);
    }
}

/// A reply carrying the state after it, which a stateful entity also keeps.
fn reply_with<C: EventSourcedEntity>(
    shape: Shape,
    reply: proto::event_sourced_out::Reply,
    state: C::State,
    entity_id: &str,
    held: &mut HeldState,
) -> proto::HandleReply {
    match C::state_codec().to_payload(&state) {
        Ok(encoded) => {
            if shape == Shape::Stateful {
                held.put(entity_id, state);
                held.put_encoded(entity_id, encoded.clone());
            }
            proto::HandleReply {
                reply: Some(proto::handle_reply::Reply::EventSourced(reply)),
                state: Some(encoded),
                failure: None,
            }
        }
        Err(e) => fault(
            None,
            reply.command_id,
            ErrorCode::Internal,
            format!("the state does not encode: {e}"),
        ),
    }
}

/// A fault: nothing in the reply applies, and the runtime keeps the state it holds.
fn fault(
    state: Option<Payload>,
    command_id: i64,
    code: ErrorCode,
    message: String,
) -> proto::HandleReply {
    // A personal field that could not be written — its subject erased, the keyring refusing or out
    // of reach — refuses the command rather than failing it, and nothing is journaled (1.15).
    if let Some(refusal) = crate::personal::take_refusal() {
        return proto::HandleReply {
            reply: Some(proto::handle_reply::Reply::EventSourced(
                proto::event_sourced_out::Reply {
                    command_id,
                    outcome: Some(proto::Outcome {
                        outcome: Some(proto::outcome::Outcome::Error(refusal.to_proto())),
                    }),
                    ..Default::default()
                },
            )),
            state,
            failure: None,
        };
    }
    proto::HandleReply {
        reply: None,
        state,
        failure: Some(proto::Failure {
            command_id,
            error: Some(CommandError::new(code, message).to_proto()),
        }),
    }
}
