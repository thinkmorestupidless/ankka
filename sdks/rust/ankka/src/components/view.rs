//! Views: a queryable row per source entity, kept up to date from the source's changes by the
//! runtime, which stores the rows and answers the queries.
//!
//! ```text
//! impl View for CartRows {
//!     type Row = CartRow;
//!     type Event = ShoppingCartEvent;
//!     const COMPONENT_ID: &'static str = "cart-rows";
//!     const ROW_MANIFEST: Option<&'static str> = Some("cart-row");
//!     fn source() -> Source { Source::of(ShoppingCart) }
//!     fn on_event(row: Option<CartRow>, event: ShoppingCartEvent, ctx: &Context) -> ViewEffect<CartRow> { … }
//!     fn queries() -> Vec<&'static str> { vec!["by-id", "all"] }
//! }
//! ```

use crate::start_from::{self, StartFrom};
use std::marker::PhantomData;

use serde::Serialize;
use serde::de::DeserializeOwned;

use super::{ComponentOf, Registered, Shape, kinds};
use crate::codec::Auto;
use crate::context::{Context, Metadata};
use crate::contract::Contract;
use crate::effects::view::ViewEffect;
use crate::proto::{self, Kind};

/// Where a view's or consumer's changes come from: a component of this service, or a topic.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Source {
    /// A component's events or state changes.
    Component(Kind, &'static str),
    /// A topic, from the runtime's broker, with what the project must know about reading it.
    Topic(TopicSource),
}

/// A topic a view or consumer reads, and what the project must know about it: the contract the
/// component expects the topic to carry (checked at start against the project's declaration),
/// the declared broker the topic is on (the installation's when `None`), and whether the
/// partitions an instance holds are handled at once, each in order.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TopicSource {
    /// The topic, by its declared name.
    pub topic: String,
    /// The contract the component expects the topic to carry.
    pub contract: Option<Contract>,
    /// The declared broker the topic is on.
    pub broker: Option<String>,
    /// Whether the partitions an instance holds are handled at once.
    pub parallel: bool,
}

impl Source {
    /// The changes of `component`, named by its value: `Source::of(ShoppingCart)`.
    pub fn of<C: ComponentOf<M>, M>(component: C) -> Source {
        let _ = component;
        Source::Component(C::kind(), C::component_id())
    }

    /// The messages on `topic`.
    pub fn topic(topic: impl Into<String>) -> Source {
        Source::Topic(TopicSource {
            topic: topic.into(),
            contract: None,
            broker: None,
            parallel: false,
        })
    }

    /// The contract the component expects the topic to carry. Applies to a topic; a component
    /// source is left as it is.
    pub fn contract(self, contract: Contract) -> Source {
        self.with_topic(|t| t.contract = Some(contract))
    }

    /// The declared broker the topic is on. Applies to a topic; a component source is left as it is.
    pub fn broker(self, broker: impl Into<String>) -> Source {
        let broker = broker.into();
        self.with_topic(|t| t.broker = Some(broker))
    }

    /// Handle the partitions an instance holds at once, each in order. Applies to a topic; a
    /// component source is left as it is.
    pub fn parallel(self) -> Source {
        self.with_topic(|t| t.parallel = true)
    }

    fn with_topic(self, change: impl FnOnce(&mut TopicSource)) -> Source {
        match self {
            Source::Topic(mut t) => {
                change(&mut t);
                Source::Topic(t)
            }
            other => other,
        }
    }

    pub(crate) fn to_proto(&self) -> proto::Source {
        match self {
            Source::Component(kind, id) => proto::Source {
                source: Some(proto::source::Source::Component(
                    proto::source::ComponentRef {
                        kind: *kind as i32,
                        id: id.to_string(),
                    },
                )),
                start_from: None,
                contract: None,
                broker: None,
                parallel: None,
            },
            Source::Topic(t) => proto::Source {
                source: Some(proto::source::Source::Topic(t.topic.clone())),
                start_from: None,
                contract: t.contract.as_ref().map(Contract::to_proto),
                broker: t.broker.clone(),
                parallel: t.parallel.then_some(true),
            },
        }
    }
}

/// A question a view can be asked by name: one SQL statement over the view's own table, whose
/// values are the `:name`s it holds. The runtime checks the statement when the service starts, and a
/// statement that is not one read of the view's own table stops the service, naming the view and the
/// query. A value is bound and never becomes part of the statement.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeclaredQuery {
    /// The query's wire name, `[a-z0-9-]+`.
    pub name: String,
    /// The statement, naming the view's table as [`table_of`] gives it.
    pub statement: String,
}

/// Declares query `name` as `statement`, for a view's [`View::declared`].
pub fn query(name: impl Into<String>, statement: impl Into<String>) -> DeclaredQuery {
    DeclaredQuery {
        name: name.into(),
        statement: statement.into(),
    }
}

/// The table holding view `component_id`'s rows, for a declared query's statement to name: `ankka_view_`
/// and the id with every character that is not a letter or digit as `_`.
pub fn table_of(component_id: &str) -> String {
    let folded: String = component_id
        .chars()
        .map(|c| if c.is_alphanumeric() { c } else { '_' })
        .collect();
    format!("ankka_view_{folded}")
}

/// A view. Implement it on a unit struct and register the struct's value.
pub trait View: Sized + 'static {
    /// One row, per source entity.
    type Row: Serialize + DeserializeOwned + 'static;

    /// What the source changes with: its events, or its state for a key value source.
    type Event: DeserializeOwned + 'static;

    /// The component's id.
    const COMPONENT_ID: &'static str;

    /// The manifest the rows are stored under; the row type's simple name unless set.
    const ROW_MANIFEST: Option<&'static str> = None;

    /// Where the changes come from.
    fn source() -> Source;

    /// Where a topic source starts, the first time its consumer group reads the topic. A view that says
    /// nothing starts at the earliest message the broker holds.
    fn start_from() -> Option<StartFrom> {
        None
    }

    /// Raised to have the view emptied and built again: a topic read again from the start
    /// position, under a group of its own; an entity read again from its first event or state.
    /// `None` is version 1.
    fn version() -> Option<u32> {
        None
    }

    /// The row after one change to the source: `row` is the current one, if there is one. The
    /// changed entity's id is `ctx.metadata().subject()`.
    fn on_event(row: Option<Self::Row>, event: Self::Event, ctx: &Context)
    -> ViewEffect<Self::Row>;

    /// The row once the source entity is deleted: dropped unless said otherwise.
    fn on_deleted(row: Option<Self::Row>, ctx: &Context) -> ViewEffect<Self::Row> {
        let _ = (row, ctx);
        ViewEffect::DeleteRow
    }

    /// The queries the view answers, by name.
    fn queries() -> Vec<&'static str> {
        Vec::new()
    }

    /// The queries the view can be asked by name, each a statement over its own table: see
    /// [`DeclaredQuery`]. None unless declared.
    fn declared() -> Vec<DeclaredQuery> {
        Vec::new()
    }

    /// The codec the rows are stored with.
    fn row_codec() -> Auto<Self::Row> {
        Self::ROW_MANIFEST.map_or_else(Auto::new, Auto::named)
    }
}

pub(crate) struct Registration<C: View> {
    marker: PhantomData<fn() -> C>,
}

impl<C: View> ComponentOf<kinds::View> for C {
    fn kind() -> Kind {
        Kind::View
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

/// What a change's metadata says about it, as a handler's context.
pub(crate) fn change_context(component_id: &str, metadata: Option<&proto::Metadata>) -> Context {
    let metadata = Metadata::from_proto(metadata);
    let subject = metadata.subject().unwrap_or_default().to_string();
    let sequence = metadata.sequence_number().unwrap_or(0);
    Context::new(component_id, subject, sequence, metadata)
}

impl<C: View> Registered for Registration<C> {
    fn id(&self) -> &str {
        C::COMPONENT_ID
    }

    fn kind(&self) -> Kind {
        Kind::View
    }

    fn shape(&self) -> Shape {
        Shape::Stateless
    }

    fn to_component(&self) -> proto::Component {
        proto::Component {
            kind: Kind::View as i32,
            id: C::COMPONENT_ID.to_string(),
            handlers: Vec::new(),
            detail: Some(proto::component::Detail::View(proto::ViewDetail {
                source: Some(start_from::source_proto(&C::source(), C::start_from())),
                row_manifest: C::row_codec().form().manifest(),
                queries: C::queries().into_iter().map(str::to_string).collect(),
                version: C::version(),
                sources: Vec::new(),
                declared_queries: C::declared()
                    .into_iter()
                    .map(|q| proto::DeclaredQuery {
                        name: q.name,
                        statement: q.statement,
                    })
                    .collect(),
            })),
        }
    }

    fn problems(&self) -> Vec<String> {
        let mut problems = Vec::new();
        if C::COMPONENT_ID.is_empty() {
            problems.push("a view has an empty component id".to_string());
        }
        problems.extend(start_from::problems(
            &format!("view '{}'", C::COMPONENT_ID),
            &C::source(),
            C::start_from(),
            C::version(),
            false,
        ));
        problems
    }

    fn view(&self, request: proto::ViewRequest) -> Option<proto::ViewEffect> {
        let ctx = change_context(C::COMPONENT_ID, request.metadata.as_ref());
        let codec = C::row_codec();
        let row = request
            .row
            .as_ref()
            .filter(|r| !r.data.is_empty())
            .map(|row| {
                codec.decode_value(&row.data).unwrap_or_else(|e| {
                    panic!(
                        "view '{}': a stored row does not decode: {e}",
                        C::COMPONENT_ID
                    )
                })
            });
        let effect = match request.event.filter(|_| !request.deleted) {
            Some(event) => {
                let event: C::Event = Auto::<C::Event>::new()
                    .decode_value(&event.data)
                    .unwrap_or_else(|e| {
                        panic!("view '{}': a change does not decode: {e}", C::COMPONENT_ID)
                    });
                C::on_event(row, event, &ctx)
            }
            None => C::on_deleted(row, &ctx),
        };
        use proto::view_effect::Effect;
        let effect = match effect {
            ViewEffect::UpdateRow(row) => {
                // A view's row is the one place a personal field's lookup token is written.
                Effect::UpdateRow(
                    crate::personal::allowing_lookup(|| codec.to_payload(&row)).unwrap_or_else(
                        |e| panic!("view '{}': a row does not encode: {e}", C::COMPONENT_ID),
                    ),
                )
            }
            ViewEffect::DeleteRow => Effect::DeleteRow(proto::Empty {}),
            ViewEffect::Ignore => Effect::Ignore(proto::Empty {}),
        };
        Some(proto::ViewEffect {
            effect: Some(effect),
        })
    }
}
