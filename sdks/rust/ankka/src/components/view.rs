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
use crate::effects::view::ViewEffect;
use crate::proto::{self, Kind};

/// Where a view's or consumer's changes come from: a component of this service, or a topic.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Source {
    /// A component's events or state changes.
    Component(Kind, &'static str),
    /// A topic, from the runtime's broker.
    Topic(String),
}

impl Source {
    /// The changes of `component`, named by its value: `Source::of(ShoppingCart)`.
    pub fn of<C: ComponentOf<M>, M>(component: C) -> Source {
        let _ = component;
        Source::Component(C::kind(), C::component_id())
    }

    /// The messages on `topic`.
    pub fn topic(topic: impl Into<String>) -> Source {
        Source::Topic(topic.into())
    }

    pub(crate) fn to_proto(&self) -> proto::Source {
        let source = match self {
            Source::Component(kind, id) => {
                proto::source::Source::Component(proto::source::ComponentRef {
                    kind: *kind as i32,
                    id: id.to_string(),
                })
            }
            Source::Topic(topic) => proto::source::Source::Topic(topic.clone()),
        };
        proto::Source {
            source: Some(source),
            start_from: None,
        }
    }
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

    /// Raised to read the topic again from the start position, under a group of its own. A higher
    /// one has the view emptied and built again.
    /// `None` is version 1. Only for a topic source.
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
                Effect::UpdateRow(codec.to_payload(&row).unwrap_or_else(|e| {
                    panic!("view '{}': a row does not encode: {e}", C::COMPONENT_ID)
                }))
            }
            ViewEffect::DeleteRow => Effect::DeleteRow(proto::Empty {}),
            ViewEffect::Ignore => Effect::Ignore(proto::Empty {}),
        };
        Some(proto::ViewEffect {
            effect: Some(effect),
        })
    }
}
