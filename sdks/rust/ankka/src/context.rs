//! What a handler can see about the call it is handling.

use crate::client::Client;
use crate::codec::time::Instant;
use crate::proto;

/// Key/value pairs that travel with a call. Opaque to a handler: the runtime puts the trace in
/// here, and a nested call carries it on, which is what makes it a child span. (It also states its
/// clock when it made the call, `ankka.now`, for a module built before the time could be asked
/// for; [`Context::now`] does not read it.) Keys compare without regard to case.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Metadata {
    entries: Vec<(String, String)>,
}

impl Metadata {
    /// No entries.
    pub fn new() -> Metadata {
        Metadata::default()
    }

    /// The first value under `key`.
    pub fn get(&self, key: &str) -> Option<&str> {
        self.entries
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(key))
            .map(|(_, v)| v.as_str())
    }

    /// Every value under `key`, in order.
    pub fn get_all(&self, key: &str) -> Vec<&str> {
        self.entries
            .iter()
            .filter(|(k, _)| k.eq_ignore_ascii_case(key))
            .map(|(_, v)| v.as_str())
            .collect()
    }

    /// These entries with `key` set to `value` alone.
    pub fn set(mut self, key: impl Into<String>, value: impl Into<String>) -> Metadata {
        let key = key.into();
        self.entries.retain(|(k, _)| !k.eq_ignore_ascii_case(&key));
        self.entries.push((key, value.into()));
        self
    }

    /// These entries with one more.
    pub fn add(mut self, key: impl Into<String>, value: impl Into<String>) -> Metadata {
        self.entries.push((key.into(), value.into()));
        self
    }

    /// Every entry, in order.
    pub fn entries(&self) -> &[(String, String)] {
        &self.entries
    }

    /// `ce-subject`: on a view's or consumer's change, the id of the entity that changed.
    pub fn subject(&self) -> Option<&str> {
        self.get("ce-subject")
    }

    /// `ankka.sequence`: the change's sequence number.
    pub fn sequence_number(&self) -> Option<i64> {
        self.get("ankka.sequence").and_then(|s| s.parse().ok())
    }

    /// `ankka.protocol`: the protocol version the runtime speaks, which it states on every
    /// consumer's request from 1.3 on. `None` from a runtime earlier than that.
    pub fn protocol(&self) -> Option<&str> {
        self.get("ankka.protocol")
    }

    /// The metadata as the protocol carries it.
    pub fn to_proto(&self) -> proto::Metadata {
        proto::Metadata {
            entries: self
                .entries
                .iter()
                .map(|(key, value)| proto::metadata::Entry {
                    key: key.clone(),
                    value: value.clone(),
                })
                .collect(),
        }
    }

    /// The protocol's metadata, or none.
    pub fn from_proto(metadata: Option<&proto::Metadata>) -> Metadata {
        Metadata {
            entries: metadata
                .map(|m| {
                    m.entries
                        .iter()
                        .map(|e| (e.key.clone(), e.value.clone()))
                        .collect()
                })
                .unwrap_or_default(),
        }
    }
}

/// What a handler sees: which instance, which component, the call's metadata, how many events the
/// instance has persisted, and a client for calling other components.
#[derive(Debug, Clone)]
pub struct Context {
    component_id: String,
    entity_id: String,
    sequence: i64,
    metadata: Metadata,
    secrets: bool,
    services: bool,
    rows_of: Option<String>,
    standing: Option<crate::standing::Standing>,
}

impl Context {
    /// A context for a call to `entity_id` of `component_id`. The runtime's calls build one; so do
    /// the testkits.
    pub fn new(
        component_id: impl Into<String>,
        entity_id: impl Into<String>,
        sequence: i64,
        metadata: Metadata,
    ) -> Context {
        Context {
            component_id: component_id.into(),
            entity_id: entity_id.into(),
            sequence,
            metadata,
            secrets: false,
            services: false,
            rows_of: None,
            standing: None,
        }
    }

    /// This context with a workflow's standing: what the runtime builds for a change from a
    /// workflow, and a test builds for one.
    pub fn with_standing(mut self, standing: Option<crate::standing::Standing>) -> Context {
        self.standing = standing;
        self
    }

    /// Of a change from a workflow: where it stood once the effect that recorded the state was
    /// applied. `None` for a change from an entity or a topic, and for anything but a change.
    pub fn standing(&self) -> Option<&crate::standing::Standing> {
        self.standing.as_ref()
    }

    /// This context with the rows of keyed view `view`: what the runtime builds for a keyed view's
    /// handler, and a test builds for one.
    pub fn with_rows(mut self, view: impl Into<String>) -> Context {
        self.rows_of = Some(view.into());
        self
    }

    /// The rows of the keyed view whose handler this is, for the length of the change: by key, or
    /// by one of the view's declared queries.
    ///
    /// # Panics
    ///
    /// Outside a keyed view's handler: no other handler is handling a change to a view's rows.
    pub fn rows(&self) -> ViewRows {
        let view = self
            .rows_of
            .clone()
            .unwrap_or_else(|| panic!("ctx.rows() is a keyed view's, in one of its handlers"));
        ViewRows {
            view,
            client: self.client(),
        }
    }

    /// This context with the secret store: what the runtime builds for an endpoint, a workflow
    /// step, a consumer, a timed action and an agent, and a test builds for one of those.
    pub fn with_secrets(mut self) -> Context {
        self.secrets = true;
        self
    }

    /// This context with the clients for other services: what the runtime builds for the same
    /// handlers that have the secret store, and a test builds for one of those.
    pub fn with_services(mut self) -> Context {
        self.services = true;
        self
    }

    /// The clients for other services, or `None` in an entity, a view and a workflow's command
    /// handler. A call to another service waits for as long as that service takes, and a command
    /// that waited would hold every other command to the same entity behind it. The runtime
    /// enforces the same rule itself, by ending a call that tries; this is the earlier answer.
    pub fn services(&self) -> Option<crate::services::Services> {
        self.services
            .then(|| crate::services::Services::with_metadata(self.metadata.clone()))
    }

    /// The service's secret store, or `None` in an entity, a view and a workflow's command handler.
    /// A read there would put a database call on a single-writer path, and a value read there is
    /// one line away from an event or a state.
    pub fn secrets(&self) -> Option<crate::secrets::Secrets> {
        self.secrets.then(crate::secrets::Secrets::new)
    }

    /// The component handling the call.
    pub fn component_id(&self) -> &str {
        &self.component_id
    }

    /// The instance handling the call: an entity's or workflow's id. Empty for a stateless kind.
    pub fn entity_id(&self) -> &str {
        &self.entity_id
    }

    /// The task an autonomous agent's tool, guardrail or rule is running for; `None` anywhere else.
    /// It is the agent's session, `task:<id>`, without its prefix.
    pub fn task_id(&self) -> Option<&str> {
        self.entity_id.strip_prefix("task:")
    }

    /// How many events the instance had persisted before this call.
    pub fn sequence(&self) -> i64 {
        self.sequence
    }

    /// The call's metadata.
    pub fn metadata(&self) -> &Metadata {
        &self.metadata
    }

    /// The runtime's clock, read now. A module has no clock of its own: this is the one to read,
    /// from any handler. It is the time it is called, so two reads in one handler may differ, and
    /// a handler that needs one time reads it once. An event's time belongs in the event: when the
    /// event is read again, this is still the present. Natively, with no runtime, the machine's
    /// clock, or the time a test fixed with the testkit's `with_clock`.
    pub fn now(&self) -> Instant {
        Instant::from_epoch_millis(crate::abi::imports::now())
    }

    /// Fills `buf` with random bytes from the runtime's secure source, from any handler. An id
    /// made from them in a command belongs in the event the command persists: read again, the
    /// command is not run again, and a second fill would be other bytes. Natively, the system's
    /// source, or the bytes a test fixed with the testkit's `with_random`.
    pub fn random(&self, buf: &mut [u8]) {
        crate::abi::imports::random(buf)
    }

    /// The due time this timer is run for (`ankka.due`): the same on every retry of one due time,
    /// and for a recurring timer a whole number of periods from the last. `None` outside a timed
    /// action, and on a runtime older than protocol 1.12.
    pub fn due(&self) -> Option<Instant> {
        self.metadata
            .get("ankka.due")
            .and_then(|m| m.parse::<i64>().ok())
            .map(Instant::from_epoch_millis)
    }

    /// A client for calling other components, carrying this call's metadata on, so a nested call
    /// joins the same trace.
    pub fn client(&self) -> Client {
        Client::with_metadata(self.metadata.clone())
    }
}

/// A keyed view's own rows, read while its handler handles one change. The runtime holds the
/// view's lock throughout, so what is read is what the handler's rows replace.
///
/// A read that fails fails the change, which is handled again, so these panic rather than answer an
/// error a handler could only pass on.
#[derive(Debug, Clone)]
pub struct ViewRows {
    view: String,
    client: Client,
}

impl ViewRows {
    /// The row under `key`, or `None`.
    pub fn get<R: serde::de::DeserializeOwned + 'static>(&self, key: &str) -> Option<R> {
        let rows: Vec<R> = self
            .client
            .query_by_name(&self.view, "get", key.to_string())
            .unwrap_or_else(|e| panic!("view '{}' could not read row '{key}': {e}", self.view));
        rows.into_iter().next()
    }

    /// The rows of the view's declared query `name`, asked with `values`.
    pub fn ask<R: serde::de::DeserializeOwned + 'static>(
        &self,
        name: &str,
        values: &[(&str, &str)],
    ) -> Vec<R> {
        self.client
            .ask_by_name(&self.view, name, values, None)
            .unwrap_or_else(|e| panic!("view '{}' could not ask '{name}': {e}", self.view))
    }
}
