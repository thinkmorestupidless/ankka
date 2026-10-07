//! Publishing a service's entities as a graph: a consumer that says which elements a change leaves
//! in which state, and has each published as a graph delta.
//!
//! A delta is one element's whole state — a node, an edge, or a tombstone for either — at a
//! version, under the contract `ankka.graph-delta.v1`. Its record key is its element key,
//! `node:<id>` or `edge:<id>`, and its version is the sequence number of the change being handled
//! unless the handler states one. A graph consumer writes no JSON, no key and no version:
//!
//! ```text
//! impl GraphConsumer for CartGraph {
//!     type Message = ShoppingCartEvent;
//!     const COMPONENT_ID: &'static str = "cart-graph";
//!     const TOPIC: &'static str = "cart-graph";
//!     fn source() -> Source { Source::of(ShoppingCart) }
//!     fn on_message(event: ShoppingCartEvent, ctx: &Context) -> GraphEffect {
//!         let id = ctx.entity_id();
//!         graph::publish([graph::node(format!("cart:{id}")).label("Cart").property("cartId", id)])
//!     }
//! }
//! ```
//!
//! The builders never fail, so they chain; an element is checked when the handler's answer is
//! dispatched. A fault there is a panic naming the element and what is wrong with it: the change is
//! not handled and comes again, and nothing of the answer is published.
//!
//! The rules a writer of deltas keeps are the author's, not the library's: an element is the whole
//! of its state, never a change to it; and an element has one writer — the entity it belongs to —
//! because only that entity's sequence numbers can order its deltas.

use crate::start_from::{self, StartFrom};
use std::collections::{BTreeMap, BTreeSet};
use std::fmt;
use std::marker::PhantomData;

use serde::Serialize;
use serde::de::DeserializeOwned;
use serde::ser::{SerializeSeq, Serializer};

use crate::codec::{Auto, ContentType, json};
use crate::components::consumer::to_proto;
use crate::components::view::{Source, change_context};
use crate::components::{ComponentOf, Registered, Shape, kinds};
use crate::context::{Context, Metadata};
use crate::effects::consumer::{ConsumerEffect, Outgoing};
use crate::proto::{self, Kind, Payload};

/// The contract's name: the manifest of every delta, and its `ce-type`.
pub const SCHEMA_NAME: &str = "ankka.graph-delta.v1";

/// The property names the reader of a delta keeps for itself.
const RESERVED: [&str; 3] = ["id", "_version", "_deleted"];

/// The record key of every delta for node `id`.
pub fn node_key(id: &str) -> String {
    format!("node:{id}")
}

/// The record key of every delta for edge `id`. Nodes and edges are separate id spaces, so a node
/// and an edge that share an id have different keys.
pub fn edge_key(id: &str) -> String {
    format!("edge:{id}")
}

// ── Values ───────────────────────────────────────────────────────────────────

/// A property value: a scalar, or a list of one kind of scalar.
///
/// A float whose value is whole is the integer of that value to whatever reads the graph, so the
/// two are equal here, and count as one kind in a list.
#[derive(Debug, Clone)]
pub enum Value {
    /// Text.
    Text(String),
    /// A boolean.
    Bool(bool),
    /// A whole number, 64 bits by type.
    Integer(i64),
    /// A number that is not whole. It must be finite.
    Float(f64),
    /// A non-empty list of one kind of scalar.
    List(Vec<Value>),
}

/// The integer a float is, if it is whole and fits 64 bits.
fn whole(value: f64) -> Option<i64> {
    // 2⁶³ is exactly representable and is one past the largest i64.
    let fits = (-9_223_372_036_854_775_808.0..9_223_372_036_854_775_808.0).contains(&value);
    (value.is_finite() && value.fract() == 0.0 && fits).then_some(value as i64)
}

impl PartialEq for Value {
    fn eq(&self, other: &Value) -> bool {
        match (self, other) {
            (Value::Text(a), Value::Text(b)) => a == b,
            (Value::Bool(a), Value::Bool(b)) => a == b,
            (Value::Integer(a), Value::Integer(b)) => a == b,
            (Value::Integer(a), Value::Float(b)) | (Value::Float(b), Value::Integer(a)) => {
                whole(*b) == Some(*a)
            }
            (Value::Float(a), Value::Float(b)) => a == b,
            (Value::List(a), Value::List(b)) => a == b,
            _ => false,
        }
    }
}

impl From<&str> for Value {
    fn from(value: &str) -> Value {
        Value::Text(value.to_string())
    }
}

impl From<String> for Value {
    fn from(value: String) -> Value {
        Value::Text(value)
    }
}

impl From<&String> for Value {
    fn from(value: &String) -> Value {
        Value::Text(value.clone())
    }
}

impl From<bool> for Value {
    fn from(value: bool) -> Value {
        Value::Bool(value)
    }
}

impl From<i64> for Value {
    fn from(value: i64) -> Value {
        Value::Integer(value)
    }
}

impl From<i32> for Value {
    fn from(value: i32) -> Value {
        Value::Integer(i64::from(value))
    }
}

impl From<f64> for Value {
    fn from(value: f64) -> Value {
        Value::Float(value)
    }
}

impl<T: Into<Value>> From<Vec<T>> for Value {
    fn from(values: Vec<T>) -> Value {
        Value::List(values.into_iter().map(Into::into).collect())
    }
}

impl Serialize for Value {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        match self {
            Value::Text(text) => serializer.serialize_str(text),
            Value::Bool(flag) => serializer.serialize_bool(*flag),
            Value::Integer(n) => serializer.serialize_i64(*n),
            Value::Float(n) => serializer.serialize_f64(*n),
            Value::List(values) => {
                let mut seq = serializer.serialize_seq(Some(values.len()))?;
                for value in values {
                    seq.serialize_element(value)?;
                }
                seq.end()
            }
        }
    }
}

// ── Refusals ─────────────────────────────────────────────────────────────────

/// Why an element, or a handler's whole answer, is refused.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Why {
    /// An id that is empty.
    Id,
    /// An edge whose type, or the id it runs from or to, is empty.
    Endpoints,
    /// A label, or an edge's type, that is not `[A-Za-z_][A-Za-z0-9_]*`.
    Identifier,
    /// A property named `id`, `_version` or `_deleted`.
    Reserved,
    /// A property value that is not finite, an empty list, a list of more than one kind, or a
    /// list in a list.
    PropertyValue,
    /// A whole number that does not fit 64 bits.
    IntegerRange,
    /// A stated version below 1.
    Version,
    /// The same kind and id twice in one answer.
    Duplicate,
    /// No stated version, and a change with no sequence number to take one from.
    NoSequence,
    /// A label on something that is not a node, or a property on a tombstone.
    Misplaced,
}

impl Why {
    /// The reason's name, as the shared fixture of refused elements spells it.
    pub fn as_str(&self) -> &'static str {
        match self {
            Why::Id => "id",
            Why::Endpoints => "endpoints",
            Why::Identifier => "identifier",
            Why::Reserved => "reserved",
            Why::PropertyValue => "property-value",
            Why::IntegerRange => "integer-range",
            Why::Version => "version",
            Why::Duplicate => "duplicate",
            Why::NoSequence => "no-sequence",
            Why::Misplaced => "misplaced",
        }
    }
}

/// An element, or an answer, that would not be accepted by what reads the graph — or that this
/// library will not write. It names the element and the fault.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Refused {
    /// The reason.
    pub why: Why,
    /// The element and what is wrong with it.
    pub message: String,
}

impl fmt::Display for Refused {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for Refused {}

// ── Elements ─────────────────────────────────────────────────────────────────

/// What an element is.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ElementKind {
    /// A node, in its whole state.
    Node,
    /// An edge, in its whole state.
    Edge,
    /// A node, marked deleted.
    NodeTombstone,
    /// An edge, marked deleted.
    EdgeTombstone,
}

impl ElementKind {
    /// Which of the two id spaces the element is in: `node` or `edge`.
    pub fn space(&self) -> &'static str {
        match self {
            ElementKind::Node | ElementKind::NodeTombstone => "node",
            ElementKind::Edge | ElementKind::EdgeTombstone => "edge",
        }
    }

    fn is_tombstone(&self) -> bool {
        matches!(
            self,
            ElementKind::NodeTombstone | ElementKind::EdgeTombstone
        )
    }

    fn is_edge(&self) -> bool {
        self.space() == "edge"
    }
}

/// A node, an edge, or a tombstone for either: as a handler describes it, and as a reader gives it
/// back. A handler's has no version unless it states one with [`at`](Element::at); a read one has
/// the version it was published at.
#[derive(Debug, Clone, PartialEq)]
pub struct Element {
    kind: ElementKind,
    id: String,
    version: Option<i64>,
    labels: Vec<String>,
    edge_type: String,
    from: String,
    to: String,
    properties: BTreeMap<String, Value>,
}

/// The node `id`, with no labels and no properties yet.
pub fn node(id: impl Into<String>) -> Element {
    Element::new(
        ElementKind::Node,
        id.into(),
        "",
        String::new(),
        String::new(),
    )
}

/// The edge `id`, of this type, running from the node `from` to the node `to`.
pub fn edge(
    id: impl Into<String>,
    r#type: &str,
    from: impl Into<String>,
    to: impl Into<String>,
) -> Element {
    Element::new(ElementKind::Edge, id.into(), r#type, from.into(), to.into())
}

/// The node `id`, marked deleted. A tombstone marks; it does not remove: the element stays in the
/// graph, deleted, so an older delta arriving late cannot bring it back.
pub fn tombstone_node(id: impl Into<String>) -> Element {
    Element::new(
        ElementKind::NodeTombstone,
        id.into(),
        "",
        String::new(),
        String::new(),
    )
}

/// The edge `id`, marked deleted. It names the edge's type and endpoints, so a reader finds the
/// edge without searching for it.
pub fn tombstone_edge(
    id: impl Into<String>,
    r#type: &str,
    from: impl Into<String>,
    to: impl Into<String>,
) -> Element {
    Element::new(
        ElementKind::EdgeTombstone,
        id.into(),
        r#type,
        from.into(),
        to.into(),
    )
}

fn identifier(text: &str) -> bool {
    let mut chars = text.chars();
    chars
        .next()
        .is_some_and(|c| c.is_ascii_alphabetic() || c == '_')
        && chars.all(|c| c.is_ascii_alphanumeric() || c == '_')
}

/// The kind a scalar counts as, or why it is no scalar a delta can carry.
fn scalar_kind(value: &Value) -> Result<&'static str, (Why, String)> {
    match value {
        Value::Text(_) => Ok("string"),
        Value::Bool(_) => Ok("boolean"),
        Value::Integer(_) => Ok("integer"),
        Value::Float(n) if !n.is_finite() => Err((
            Why::PropertyValue,
            format!("is {n}, which is not a number a delta can carry"),
        )),
        Value::Float(n) if n.fract() == 0.0 => match whole(*n) {
            Some(_) => Ok("integer"),
            None => Err((
                Why::IntegerRange,
                format!("is the whole number {n:e}, which does not fit 64 bits"),
            )),
        },
        Value::Float(_) => Ok("float"),
        Value::List(_) => Err((Why::PropertyValue, "has a list inside a list".to_string())),
    }
}

impl Element {
    fn new(kind: ElementKind, id: String, edge_type: &str, from: String, to: String) -> Element {
        Element {
            kind,
            id,
            version: None,
            labels: Vec::new(),
            edge_type: edge_type.to_string(),
            from,
            to,
            properties: BTreeMap::new(),
        }
    }

    /// The node with one more label.
    pub fn label(mut self, label: &str) -> Element {
        self.labels.push(label.to_string());
        self
    }

    /// The node or edge with `name` set to `value`. An element is the whole of its state: a
    /// property left out is a property the element does not have.
    pub fn property(mut self, name: &str, value: impl Into<Value>) -> Element {
        self.properties.insert(name.to_string(), value.into());
        self
    }

    /// The element at a version of the handler's choosing, in place of the sequence number of the
    /// change being handled. It must rise with the history of the entity that owns the element.
    pub fn at(mut self, version: i64) -> Element {
        self.version = Some(version);
        self
    }

    /// What the element is.
    pub fn kind(&self) -> ElementKind {
        self.kind
    }

    /// Its id.
    pub fn id(&self) -> &str {
        &self.id
    }

    /// The version it is at: stated by the handler, or the one a read delta was published at.
    pub fn version(&self) -> Option<i64> {
        self.version
    }

    /// A node's labels.
    pub fn labels(&self) -> &[String] {
        &self.labels
    }

    /// An edge's type; empty for a node.
    pub fn edge_type(&self) -> &str {
        &self.edge_type
    }

    /// The id of the node an edge runs from; empty for a node.
    pub fn from(&self) -> &str {
        &self.from
    }

    /// The id of the node an edge runs to; empty for a node.
    pub fn to(&self) -> &str {
        &self.to
    }

    /// Its properties, by name.
    pub fn properties(&self) -> &BTreeMap<String, Value> {
        &self.properties
    }

    /// The property `name`, if it has one.
    pub fn get(&self, name: &str) -> Option<&Value> {
        self.properties.get(name)
    }

    /// The record key of every delta for this element: `node:<id>` or `edge:<id>`. A tombstone has
    /// the key of the element it marks.
    pub fn key(&self) -> String {
        format!("{}:{}", self.kind.space(), self.id)
    }

    /// `node 'cart:1'`, `tombstone of edge 'e'`: the element, for a message.
    fn named(&self) -> String {
        let what = match self.kind {
            ElementKind::Node => "node",
            ElementKind::Edge => "edge",
            ElementKind::NodeTombstone => "tombstone of node",
            ElementKind::EdgeTombstone => "tombstone of edge",
        };
        format!("{what} '{}'", self.id)
    }

    fn refused(&self, why: Why, fault: impl fmt::Display) -> Refused {
        Refused {
            why,
            message: format!("{}: {fault}", self.named()),
        }
    }

    /// What is wrong with the element on its own, if anything: what the reader of its delta would
    /// refuse, and a stated version below 1.
    pub fn check(&self) -> Result<(), Refused> {
        if self.id.is_empty() {
            return Err(self.refused(Why::Id, "an id must not be empty"));
        }
        if self.kind.is_edge() {
            for (what, value) in [
                ("type", &self.edge_type),
                ("from", &self.from),
                ("to", &self.to),
            ] {
                if value.is_empty() {
                    let fault =
                        format!("an edge needs a type, a from and a to; its {what} is empty");
                    return Err(self.refused(Why::Endpoints, fault));
                }
            }
            if !identifier(&self.edge_type) {
                let fault = format!(
                    "type '{}' is not an identifier ([A-Za-z_][A-Za-z0-9_]*)",
                    self.edge_type
                );
                return Err(self.refused(Why::Identifier, fault));
            }
        }
        if !self.labels.is_empty() && self.kind != ElementKind::Node {
            return Err(self.refused(Why::Misplaced, "only a node has labels"));
        }
        if let Some(label) = self.labels.iter().find(|l| !identifier(l)) {
            let fault = format!("label '{label}' is not an identifier ([A-Za-z_][A-Za-z0-9_]*)");
            return Err(self.refused(Why::Identifier, fault));
        }
        if !self.properties.is_empty() && self.kind.is_tombstone() {
            return Err(self.refused(Why::Misplaced, "a tombstone has no properties"));
        }
        for (name, value) in &self.properties {
            if RESERVED.contains(&name.as_str()) {
                let fault = format!("property '{name}' is reserved");
                return Err(self.refused(Why::Reserved, fault));
            }
            let fault = |(why, fault): (Why, String)| {
                self.refused(why, format!("property '{name}' {fault}"))
            };
            match value {
                Value::List(values) if values.is_empty() => {
                    return Err(fault((Why::PropertyValue, "is an empty list".to_string())));
                }
                Value::List(values) => {
                    let mut kinds = BTreeSet::new();
                    for value in values {
                        kinds.insert(scalar_kind(value).map_err(fault)?);
                    }
                    if kinds.len() > 1 {
                        let kinds: Vec<&str> = kinds.into_iter().collect();
                        let mixed = format!(
                            "is a list of more than one kind ({}); a whole-number float counts \
                             as an integer",
                            kinds.join(", ")
                        );
                        return Err(fault((Why::PropertyValue, mixed)));
                    }
                }
                scalar => {
                    scalar_kind(scalar).map_err(fault)?;
                }
            }
        }
        if let Some(version) = self.version
            && version < 1
        {
            let fault = format!("version {version} is not a whole number of at least 1");
            return Err(self.refused(Why::Version, fault));
        }
        Ok(())
    }

    /// The delta as the contract writes it. The element is checked and at a version.
    fn encoded(&self) -> Vec<u8> {
        let tombstone = self.kind.is_tombstone();
        let edge = self.kind.is_edge();
        let wire = Wire {
            kind: match self.kind {
                ElementKind::Node => "node",
                ElementKind::Edge => "edge",
                ElementKind::NodeTombstone | ElementKind::EdgeTombstone => "tombstone",
            },
            element: tombstone.then(|| self.kind.space()),
            id: &self.id,
            version: self.version.unwrap_or_default(),
            labels: (self.kind == ElementKind::Node).then_some(&self.labels),
            edge_type: edge.then_some(&self.edge_type),
            from: edge.then_some(&self.from),
            to: edge.then_some(&self.to),
            properties: (!tombstone).then_some(&self.properties),
        };
        json::to_vec(&wire).unwrap_or_else(|e| panic!("{} does not encode: {e}", self.named()))
    }

    /// The message that publishes the element's delta: under its element key, with the contract's
    /// name as its manifest and its `ce-type`.
    fn outgoing(&self) -> Outgoing {
        let payload = Payload {
            content_type: ContentType::Json.as_str().to_string(),
            manifest: SCHEMA_NAME.to_string(),
            data: self.encoded(),
        };
        Outgoing::of(Ok(payload))
            .key(self.key())
            .metadata(Metadata::new().set("ce-type", SCHEMA_NAME))
    }
}

/// A delta's fields, in the order they are written. `labels` and `properties` are always written
/// for the kinds that have them, empty when there are none.
#[derive(Serialize)]
struct Wire<'a> {
    kind: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    element: Option<&'a str>,
    id: &'a str,
    version: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    labels: Option<&'a Vec<String>>,
    #[serde(rename = "type", skip_serializing_if = "Option::is_none")]
    edge_type: Option<&'a String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    from: Option<&'a String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    to: Option<&'a String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    properties: Option<&'a BTreeMap<String, Value>>,
}

/// A handler's elements as they are published for a change at `sequence`: each checked, each at
/// the version it states or else at `sequence`, and no element there twice.
///
/// It is what dispatch does before anything is published, and what a test of a mapping can call
/// to see why an answer would be refused.
pub fn resolve(elements: Vec<Element>, sequence: i64) -> Result<Vec<Element>, Refused> {
    let mut seen = BTreeSet::new();
    let mut resolved = Vec::with_capacity(elements.len());
    for mut element in elements {
        element.check()?;
        if element.version.is_none() {
            if sequence < 1 {
                return Err(element.refused(
                    Why::NoSequence,
                    "the change being handled has no sequence number to take a version from; \
                     state a version with .at(…)",
                ));
            }
            element.version = Some(sequence);
        }
        if !seen.insert((element.kind.space(), element.id.clone())) {
            return Err(element.refused(
                Why::Duplicate,
                "the element is in this answer twice; an answer holds each element once",
            ));
        }
        resolved.push(element);
    }
    Ok(resolved)
}

// ── Reading ──────────────────────────────────────────────────────────────────

/// The element a delta is: a record's value read back, for a test or for a consumer of a delta
/// topic. With `key`, the record's key must be the delta's element key.
///
/// The contract's own validation applies, so a version of 0 reads, though this library writes
/// none below 1.
pub fn read(value: &[u8], key: Option<&str>) -> Result<Element, String> {
    use serde_json::Value as Json;
    let Ok(Json::Object(fields)) = serde_json::from_slice::<Json>(value) else {
        return Err("not a JSON object".to_string());
    };
    let text = |name: &str| {
        fields
            .get(name)
            .and_then(Json::as_str)
            .filter(|s| !s.is_empty())
    };
    let Some(kind) = fields.get("kind") else {
        return Err("kind missing".to_string());
    };
    let kind = kind.as_str().unwrap_or_default();
    let kind = match kind {
        "node" => ElementKind::Node,
        "edge" => ElementKind::Edge,
        "tombstone" => match fields.get("element").and_then(Json::as_str) {
            Some("node") => ElementKind::NodeTombstone,
            Some("edge") => ElementKind::EdgeTombstone,
            _ => return Err("tombstone needs element 'node' or 'edge'".to_string()),
        },
        other => return Err(format!("unknown kind '{other}'")),
    };
    let Some(id) = text("id") else {
        return Err("id missing or empty".to_string());
    };
    let version = fields
        .get("version")
        .and_then(Json::as_number)
        .and_then(read_integer)
        .filter(|v| *v >= 0)
        .ok_or("version is not a non-negative integer")?;
    let mut element = Element::new(kind, id.to_string(), "", String::new(), String::new());
    element.version = Some(version);
    if kind.is_edge() {
        let (Some(edge_type), Some(from), Some(to)) = (text("type"), text("from"), text("to"))
        else {
            return Err(if kind.is_tombstone() {
                "tombstone of an edge needs type, from and to".to_string()
            } else {
                "edge needs type, from and to".to_string()
            });
        };
        element.edge_type = edge_type.to_string();
        element.from = from.to_string();
        element.to = to.to_string();
    }
    if kind == ElementKind::Node {
        match fields.get("labels") {
            None => {}
            Some(Json::Array(labels)) => {
                for label in labels {
                    match label.as_str().filter(|l| identifier(l)) {
                        Some(label) => element.labels.push(label.to_string()),
                        None => return Err("labels must be an array of identifiers".to_string()),
                    }
                }
            }
            Some(_) => return Err("labels must be an array of identifiers".to_string()),
        }
    }
    if !kind.is_tombstone() {
        match fields.get("properties") {
            None => {}
            Some(Json::Object(properties)) => {
                for (name, value) in properties {
                    if RESERVED.contains(&name.as_str()) {
                        return Err(format!("property '{name}' is reserved"));
                    }
                    let value = read_property(value).ok_or_else(|| {
                        format!("property '{name}' is not a scalar or array of scalars")
                    })?;
                    element.properties.insert(name.clone(), value);
                }
            }
            Some(_) => return Err("properties must be an object".to_string()),
        }
    }
    if let Some(key) = key {
        let expected = element.key();
        if key != expected {
            return Err(format!(
                "key '{key}' is not this delta's element key '{expected}'"
            ));
        }
    }
    Ok(element)
}

/// A JSON number as the whole number it is, if it is one that fits 64 bits.
fn read_integer(number: &serde_json::Number) -> Option<i64> {
    number.as_i64().or_else(|| {
        // An integer past i64 reads as a u64 and is out of range; anything else is a float.
        if number.is_u64() {
            None
        } else {
            number.as_f64().and_then(whole)
        }
    })
}

fn read_scalar(value: &serde_json::Value) -> Option<Value> {
    use serde_json::Value as Json;
    match value {
        Json::String(text) => Some(Value::Text(text.clone())),
        Json::Bool(flag) => Some(Value::Bool(*flag)),
        Json::Number(number) => match read_integer(number) {
            Some(n) => Some(Value::Integer(n)),
            None if number.is_u64() => None,
            None => number
                .as_f64()
                .filter(|n| n.is_finite() && n.fract() != 0.0)
                .map(Value::Float),
        },
        _ => None,
    }
}

fn read_property(value: &serde_json::Value) -> Option<Value> {
    match value {
        serde_json::Value::Array(values) if values.is_empty() => None,
        serde_json::Value::Array(values) => {
            let values: Vec<Value> = values.iter().map(read_scalar).collect::<Option<_>>()?;
            let kinds: BTreeSet<&str> = values
                .iter()
                .map(|v| scalar_kind(v).unwrap_or("unreadable"))
                .collect();
            (kinds.len() == 1).then_some(Value::List(values))
        }
        scalar => read_scalar(scalar),
    }
}

// ── The effect and the consumer ──────────────────────────────────────────────

/// What a graph consumer decided about one change.
#[derive(Debug, Clone)]
pub enum GraphEffect {
    /// Publish these elements, each as a delta under its element key. None at all is
    /// [`Done`](GraphEffect::Done).
    Publish(Vec<Element>),
    /// Handled; nothing in the graph changes.
    Done,
    /// Not of interest.
    Ignore,
}

/// Publish these elements for this change: each as a delta at the change's sequence number, unless
/// it states a version. The change is handled when the broker has accepted every one.
pub fn publish(elements: impl IntoIterator<Item = Element>) -> GraphEffect {
    GraphEffect::Publish(elements.into_iter().collect())
}

/// The change was handled, and nothing in the graph changes.
pub fn done() -> GraphEffect {
    GraphEffect::Done
}

/// The change is not of interest.
pub fn ignore() -> GraphEffect {
    GraphEffect::Ignore
}

/// A consumer that publishes a graph, and nothing else: its handlers answer with elements, and
/// each is published to [`TOPIC`](GraphConsumer::TOPIC) as a delta. There is no key for it to set
/// and no other message for it to produce. Implement it on a unit struct and register the
/// struct's value; the runtime sees an ordinary consumer.
///
/// It needs a runtime that speaks protocol 1.3, and a broker (`ANKKA_KAFKA_BOOTSTRAP_SERVERS`).
/// The topic must be compacted to hold the graph; the library neither creates nor checks it.
pub trait GraphConsumer: Sized + 'static {
    /// What the source changes with: its events, its state, or a topic's messages.
    type Message: DeserializeOwned + 'static;

    /// The component's id.
    const COMPONENT_ID: &'static str;

    /// The topic the deltas are published to.
    const TOPIC: &'static str;

    /// Where the changes come from.
    fn source() -> Source;

    /// Where a topic source starts, the first time its consumer group reads the topic. A graph
    /// consumer that reads a topic must say: there is no default.
    fn start_from() -> Option<StartFrom> {
        None
    }

    /// A new one reads the topic again from the start position, under a group of its own. `None`
    /// is version 1. Only for a topic source.
    fn version() -> Option<u32> {
        None
    }

    /// The elements one change leaves, each in its whole state. The source entity's id is
    /// `ctx.entity_id()`, and the change's sequence number, which is every element's version
    /// unless it states one, is `ctx.sequence()`. A topic's messages have no sequence number: an
    /// element built from one states its version.
    fn on_message(message: Self::Message, ctx: &Context) -> GraphEffect;

    /// The source entity was deleted: ignored unless said otherwise. The deletion has a sequence
    /// number of its own, above every change before it, so a tombstone published here outranks
    /// them.
    fn on_deleted(ctx: &Context) -> GraphEffect {
        let _ = ctx;
        GraphEffect::Ignore
    }
}

pub(crate) struct Registration<G: GraphConsumer> {
    marker: PhantomData<fn() -> G>,
}

impl<G: GraphConsumer> ComponentOf<kinds::GraphConsumer> for G {
    fn kind() -> Kind {
        Kind::Consumer
    }

    fn component_id() -> &'static str {
        G::COMPONENT_ID
    }

    fn registration() -> Box<dyn Registered> {
        Box::new(Registration::<G> {
            marker: PhantomData,
        })
    }
}

impl<G: GraphConsumer> Registered for Registration<G> {
    fn id(&self) -> &str {
        G::COMPONENT_ID
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
            id: G::COMPONENT_ID.to_string(),
            handlers: Vec::new(),
            detail: Some(proto::component::Detail::Consumer(proto::ConsumerDetail {
                source: Some(start_from::source_proto(&G::source(), G::start_from())),
                produces_to: Some(G::TOPIC.to_string()),
                version: G::version(),
                // The topic alone: a delta topic's contract is the graph delta format, not a
                // schema a project declares.
                produces: Some(proto::Publication {
                    topic: G::TOPIC.to_string(),
                    contract: None,
                    broker: None,
                }),
            })),
        }
    }

    fn problems(&self) -> Vec<String> {
        let mut problems = Vec::new();
        if G::COMPONENT_ID.is_empty() {
            problems.push("a graph consumer has an empty component id".to_string());
        }
        if G::TOPIC.is_empty() {
            problems.push(format!(
                "graph consumer '{}' names no topic to publish its deltas to",
                G::COMPONENT_ID
            ));
        }
        problems.extend(start_from::problems(
            &format!("graph consumer '{}'", G::COMPONENT_ID),
            &G::source(),
            G::start_from(),
            G::version(),
            true,
        ));
        problems
    }

    fn consumer(&self, request: proto::ConsumerRequest) -> Option<proto::ConsumerEffect> {
        let ctx = change_context(G::COMPONENT_ID, request.metadata.as_ref());
        let effect = match request.message.filter(|_| !request.deleted) {
            Some(message) => {
                let message: G::Message = Auto::<G::Message>::new()
                    .decode_value(&message.data)
                    .unwrap_or_else(|e| {
                        panic!(
                            "graph consumer '{}': a message does not decode: {e}",
                            G::COMPONENT_ID
                        )
                    });
                G::on_message(message, &ctx)
            }
            None => G::on_deleted(&ctx),
        };
        let effect = match effect {
            GraphEffect::Done => ConsumerEffect::Done,
            GraphEffect::Ignore => ConsumerEffect::Ignore,
            GraphEffect::Publish(elements) => {
                let resolved = resolve(elements, ctx.sequence())
                    .unwrap_or_else(|r| panic!("graph consumer '{}': {r}", G::COMPONENT_ID));
                ConsumerEffect::ProduceAll(resolved.iter().map(Element::outgoing).collect())
            }
        };
        Some(to_proto(G::COMPONENT_ID, effect, ctx.metadata()))
    }
}
