//! What a handler can see about the call it is handling.

use crate::client::Client;
use crate::codec::time::Instant;
use crate::proto;

/// Key/value pairs that travel with a call. Opaque to a handler: the runtime puts its clock
/// (`ankka.now`) and the trace in here, and a nested call carries them on, which is what makes it a
/// child span. Keys compare without regard to case.
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
        }
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

    /// The runtime's clock when it made the call (`ankka.now`). A module has no clock of its own:
    /// this is the one to read. Natively, with no runtime to set it, the machine's clock.
    pub fn now(&self) -> Instant {
        if let Some(millis) = self
            .metadata
            .get("ankka.now")
            .and_then(|m| m.parse::<i64>().ok())
        {
            return Instant::from_epoch_millis(millis);
        }
        #[cfg(not(target_arch = "wasm32"))]
        {
            Instant::from(std::time::SystemTime::now())
        }
        #[cfg(target_arch = "wasm32")]
        {
            panic!("the runtime sets ankka.now on every call; this call has none")
        }
    }

    /// A client for calling other components, carrying this call's metadata on, so a nested call
    /// joins the same trace.
    pub fn client(&self) -> Client {
        Client::with_metadata(self.metadata.clone())
    }
}
