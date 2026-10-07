//! Calling other components: an entity's command, a workflow's, an agent's; a view's query; a
//! timer. Every call blocks the calling handler until the runtime answers it, which in a module is
//! free — the runtime parks the thread it runs the module on, and nothing else waits.
//!
//! A component is named by its value, so its kind and id come from its declaration:
//!
//! ```ignore
//! let cart: Cart = ctx.client().invoke(ShoppingCart, "cart-1", "get-cart", ())?;
//! ```
//!
//! Autonomous agents and their tasks have calls of their own, in [`autonomous`]:
//! `ctx.client().tasks()`, `.task(id)` and `.autonomous_agent(Answerer)`.

pub mod autonomous;

use prost::Message;
use serde::Serialize;
use serde::de::DeserializeOwned;

use crate::abi::imports::{Import, call, call_schedule_recurring};
use crate::codec::time::Duration;
use crate::codec::{EncodingError, decode_payload, encode_payload};
use crate::components::{ComponentOf, kinds};
use crate::context::Metadata;
use crate::effects::{CommandError, ErrorCode};
use crate::proto::{self, Kind};

pub use autonomous::{
    AgentState, Assignment, Attachment, AutonomousAgentCalls, InstanceCalls, NewTask, TaskCalls,
    TaskSnapshot, Tasks,
};

/// A client for calling other components. A handler gets one from its `Context`, carrying the
/// call's metadata on.
#[derive(Debug, Clone, Default)]
pub struct Client {
    metadata: Metadata,
}

fn encoding(e: EncodingError) -> CommandError {
    CommandError::new(ErrorCode::BadRequest, e.0)
}

/// The longest period a recurring timer may have, in days: a century.
const MAX_PERIOD_DAYS: i64 = 36_500;

/// Why `period` cannot be a recurring timer's period, in the runtime's words.
fn period_problem(timer_id: &str, period: Duration) -> Option<String> {
    let longest = Duration::of_hours(MAX_PERIOD_DAYS * 24);
    if period < Duration::of_millis(1) || period > longest {
        Some(format!(
            "timer '{timer_id}' has a period of {period}; a period is from 1 millisecond to \
             {MAX_PERIOD_DAYS} days"
        ))
    } else {
        None
    }
}

fn answer<T: Message + Default>(import: Import, request: impl Message) -> T {
    let reply = call(import, &request.encode_to_vec());
    T::decode(reply.as_slice())
        .unwrap_or_else(|e| panic!("the runtime's answer to {import:?} does not decode: {e}"))
}

impl Client {
    /// A client that carries `metadata` on every call.
    pub fn with_metadata(metadata: Metadata) -> Client {
        Client { metadata }
    }

    /// Calls handler `name` of `entity_id` of `component`, answering its reply as `R`.
    pub fn invoke<C, M, R, P>(
        &self,
        component: C,
        entity_id: &str,
        name: &str,
        payload: P,
    ) -> Result<R, CommandError>
    where
        C: ComponentOf<M>,
        R: DeserializeOwned + 'static,
        P: Serialize + 'static,
    {
        let _ = component;
        self.invoke_by_name(C::kind(), C::component_id(), entity_id, name, payload)
    }

    /// Sends handler `name` of `entity_id` of `component` its payload and carries on, without
    /// waiting for an answer: the way to call a handler that never replies. Nothing it answers,
    /// a refusal included, comes back; the `Err` is only for a payload that cannot be encoded.
    pub fn send<C, M, P>(
        &self,
        component: C,
        entity_id: &str,
        name: &str,
        payload: P,
    ) -> Result<(), CommandError>
    where
        C: ComponentOf<M>,
        P: Serialize + 'static,
    {
        let _ = component;
        let request = self.request(C::kind(), C::component_id(), entity_id, name, &payload)?;
        let _ = call(Import::Send, &request.encode_to_vec());
        Ok(())
    }

    /// Calls a component named by its kind and id, for one this service does not declare.
    pub fn invoke_by_name<R, P>(
        &self,
        kind: Kind,
        component_id: &str,
        entity_id: &str,
        name: &str,
        payload: P,
    ) -> Result<R, CommandError>
    where
        R: DeserializeOwned + 'static,
        P: Serialize + 'static,
    {
        let request = self.request(kind, component_id, entity_id, name, &payload)?;
        let reply: proto::InvokeReply = answer(Import::Invoke, request);
        match reply.result {
            Some(proto::invoke_reply::Result::Reply(reply)) => {
                let payload = reply.payload.unwrap_or_default();
                decode_payload(&payload).map_err(|e| CommandError::new(ErrorCode::Internal, e.0))
            }
            Some(proto::invoke_reply::Result::Error(error)) => {
                Err(CommandError::from_proto(&error))
            }
            Some(proto::invoke_reply::Result::Approval(_)) => Err(CommandError::new(
                ErrorCode::Conflict,
                "the agent's turn is awaiting an approval decision, which a module cannot make",
            )),
            None => Err(CommandError::new(
                ErrorCode::Internal,
                "the runtime answered an invoke with nothing",
            )),
        }
    }

    /// Calls a streaming handler, answering its tokens once the stream has ended: a module is
    /// handed a streaming reply whole.
    pub fn invoke_stream<C, M, P>(
        &self,
        component: C,
        entity_id: &str,
        name: &str,
        payload: P,
    ) -> Result<Vec<String>, CommandError>
    where
        C: ComponentOf<M>,
        P: Serialize + 'static,
    {
        let _ = component;
        let request = self.request(C::kind(), C::component_id(), entity_id, name, &payload)?;
        let reply: proto::StreamTokens = answer(Import::InvokeStream, request);
        let mut tokens = Vec::new();
        for token in reply.tokens {
            match token.token {
                Some(proto::stream_token::Token::Text(text)) => tokens.push(text),
                Some(proto::stream_token::Token::Completed(_)) | None => {}
                Some(proto::stream_token::Token::Failed(error)) => {
                    return Err(CommandError::from_proto(&error));
                }
                Some(proto::stream_token::Token::Approval(_)) => {
                    return Err(CommandError::new(
                        ErrorCode::Conflict,
                        "the agent's turn is awaiting an approval decision, which a module cannot make",
                    ));
                }
            }
        }
        Ok(tokens)
    }

    /// Asks view `view` its query `name`, answering the rows as `R`.
    pub fn query<V, M, R, P>(&self, view: V, name: &str, payload: P) -> Result<R, CommandError>
    where
        V: ComponentOf<M>,
        R: DeserializeOwned + 'static,
        P: Serialize + 'static,
    {
        let _ = view;
        self.query_by_name(V::component_id(), name, payload)
    }

    /// Asks a view named by its id.
    pub fn query_by_name<R, P>(
        &self,
        view_id: &str,
        name: &str,
        payload: P,
    ) -> Result<R, CommandError>
    where
        R: DeserializeOwned + 'static,
        P: Serialize + 'static,
    {
        let request = proto::QueryRequest {
            view_id: view_id.to_string(),
            name: name.to_string(),
            payload: Some(encode_payload(&payload).map_err(encoding)?),
            metadata: Some(self.metadata.to_proto()),
            values: Default::default(),
            limit: None,
        };
        Self::rows(answer(Import::Query, request))
    }

    /// Asks view `view` its declared query `name` with `values`, by name, answering the rows as `R`:
    /// at most the runtime's default number of them.
    pub fn ask<V, M, R>(
        &self,
        view: V,
        name: &str,
        values: &[(&str, &str)],
    ) -> Result<R, CommandError>
    where
        V: ComponentOf<M>,
        R: DeserializeOwned + 'static,
    {
        let _ = view;
        self.ask_by_name(V::component_id(), name, values, None)
    }

    /// Asks a view named by its id its declared query `name`, reading at most `limit` rows when one
    /// is given.
    pub fn ask_by_name<R>(
        &self,
        view_id: &str,
        name: &str,
        values: &[(&str, &str)],
        limit: Option<u32>,
    ) -> Result<R, CommandError>
    where
        R: DeserializeOwned + 'static,
    {
        let request = proto::QueryRequest {
            view_id: view_id.to_string(),
            name: name.to_string(),
            payload: None,
            metadata: Some(self.metadata.to_proto()),
            values: values
                .iter()
                .map(|(k, v)| (k.to_string(), v.to_string()))
                .collect(),
            limit,
        };
        Self::rows(answer(Import::Query, request))
    }

    fn rows<R: DeserializeOwned + 'static>(reply: proto::QueryReply) -> Result<R, CommandError> {
        match reply.result {
            Some(proto::query_reply::Result::Rows(rows)) => {
                decode_payload(&rows).map_err(|e| CommandError::new(ErrorCode::Internal, e.0))
            }
            Some(proto::query_reply::Result::Error(error)) => Err(CommandError::from_proto(&error)),
            None => Err(CommandError::new(
                ErrorCode::Internal,
                "the runtime answered a query with nothing",
            )),
        }
    }

    /// Schedules a call to handler `name` of `component` — of `entity_id` for a kind that has
    /// instances — after `delay`, under `timer_id`. Scheduling the same id again replaces the timer.
    #[allow(clippy::too_many_arguments)]
    pub fn schedule<C, M, P>(
        &self,
        timer_id: &str,
        delay: Duration,
        component: C,
        entity_id: Option<&str>,
        name: &str,
        payload: P,
    ) -> Result<(), CommandError>
    where
        C: ComponentOf<M>,
        P: Serialize + 'static,
    {
        let _ = component;
        self.schedule_by_name(
            timer_id,
            delay,
            C::kind(),
            C::component_id(),
            entity_id,
            name,
            payload,
        )
    }

    /// Schedules a call to a component named by its kind and id.
    #[allow(clippy::too_many_arguments)]
    pub fn schedule_by_name<P: Serialize + 'static>(
        &self,
        timer_id: &str,
        delay: Duration,
        kind: Kind,
        component_id: &str,
        entity_id: Option<&str>,
        name: &str,
        payload: P,
    ) -> Result<(), CommandError> {
        let request = proto::ScheduleRequest {
            timer_id: timer_id.to_string(),
            delay_millis: delay.to_millis(),
            kind: kind as i32,
            component_id: component_id.to_string(),
            entity_id: entity_id.map(str::to_string),
            name: name.to_string(),
            payload: Some(encode_payload(&payload).map_err(encoding)?),
        };
        let _: proto::Empty = answer(Import::Schedule, request);
        Ok(())
    }

    /// Schedules handler `name` of the timed action `component` to run after `delay` and then
    /// every `period` until it is cancelled, under `timer_id`. Setting the same recurring timer
    /// again, with the same handler and period, keeps its next due, so a service may set its
    /// recurring timers every time it starts; anything else under the id replaces it. A period
    /// from 1 millisecond to 36,500 days is accepted; any other is refused with `BadRequest`
    /// before anything is sent. A delay of zero or less is due at once.
    #[allow(clippy::too_many_arguments)]
    pub fn schedule_recurring<C, P>(
        &self,
        timer_id: &str,
        delay: Duration,
        period: Duration,
        component: C,
        name: &str,
        payload: P,
    ) -> Result<(), CommandError>
    where
        C: ComponentOf<kinds::TimedAction>,
        P: Serialize + 'static,
    {
        let _ = component;
        self.schedule_recurring_by_name(timer_id, delay, period, C::component_id(), name, payload)
    }

    /// Schedules a recurring call to a timed action named by its id.
    pub fn schedule_recurring_by_name<P: Serialize + 'static>(
        &self,
        timer_id: &str,
        delay: Duration,
        period: Duration,
        component_id: &str,
        name: &str,
        payload: P,
    ) -> Result<(), CommandError> {
        if let Some(problem) = period_problem(timer_id, period) {
            return Err(CommandError::new(ErrorCode::BadRequest, problem));
        }
        let request = proto::ScheduleRecurringRequest {
            timer_id: timer_id.to_string(),
            delay_millis: delay.to_millis(),
            period_millis: period.to_millis(),
            component_id: component_id.to_string(),
            name: name.to_string(),
            payload: Some(encode_payload(&payload).map_err(encoding)?),
        };
        let reply = call_schedule_recurring(&request.encode_to_vec());
        let reply = proto::ScheduleRecurringReply::decode(reply.as_slice()).unwrap_or_else(|e| {
            panic!("the runtime's answer to ScheduleRecurring does not decode: {e}")
        });
        match reply.error {
            Some(error) => Err(CommandError::from_proto(&error)),
            None => Ok(()),
        }
    }

    /// Cancels the timer `timer_id`, if it has not fired.
    pub fn cancel(&self, timer_id: &str) -> Result<(), CommandError> {
        let _: proto::Empty = answer(
            Import::Cancel,
            proto::CancelRequest {
                timer_id: timer_id.to_string(),
            },
        );
        Ok(())
    }

    fn request<P: Serialize + 'static>(
        &self,
        kind: Kind,
        component_id: &str,
        entity_id: &str,
        name: &str,
        payload: &P,
    ) -> Result<proto::InvokeRequest, CommandError> {
        Ok(proto::InvokeRequest {
            kind: kind as i32,
            component_id: component_id.to_string(),
            entity_id: entity_id.to_string(),
            name: name.to_string(),
            payload: Some(encode_payload(payload).map_err(encoding)?),
            metadata: Some(self.metadata.to_proto()),
        })
    }
}
