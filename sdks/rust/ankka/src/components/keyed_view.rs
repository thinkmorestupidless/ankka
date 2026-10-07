//! Keyed views: one or more entity sources, a handler per source, and effects that name every row
//! they write or delete by key. The runtime keeps the rows and handles one change at a time, so
//! what a handler reads of its own rows is what it writes over.
//!
//! ```text
//! impl KeyedView for Shipments {
//!     type Row = ShipmentRow;
//!     const COMPONENT_ID: &'static str = "shipments";
//!     fn sources() -> Sources<Self> {
//!         Sources::new()
//!             .on::<ShipmentEvent>(Source::of(Shipment), Self::on_shipment)
//!             .on::<CustomerEvent>(Source::of(Customer), Self::on_customer)
//!     }
//! }
//! ```
//!
//! A handler is a function of the change and its context: a module's fresh instance remembers
//! nothing, so everything arrives as an argument. It reads the view's own rows through
//! [`Context::rows`].

use std::marker::PhantomData;

use serde::Serialize;
use serde::de::DeserializeOwned;

use super::view::{DeclaredQuery, Source, change_context};
use super::{ComponentOf, Registered, Shape, kinds};
use crate::codec::Auto;
use crate::context::Context;
use crate::effects::keyed_view::{KeyedViewEffect, RowChange};
use crate::proto::{self, Kind};
use crate::start_from;

type Change<Row> = Box<dyn Fn(&[u8], &Context) -> Result<KeyedViewEffect<Row>, String>>;
type Deleted<Row> = Box<dyn Fn(&Context) -> KeyedViewEffect<Row>>;

/// One source of a keyed view, with what the view does with each of its changes and with its
/// entity's deletion.
struct SourceOf<Row> {
    source: Source,
    change: Change<Row>,
    deleted: Option<Deleted<Row>>,
}

/// A keyed view's sources: declared with [`Sources::on`], each with its handler.
pub struct Sources<C: KeyedView> {
    entries: Vec<SourceOf<C::Row>>,
    problems: Vec<String>,
}

impl<C: KeyedView> Default for Sources<C> {
    fn default() -> Sources<C> {
        Sources::new()
    }
}

impl<C: KeyedView> Sources<C> {
    /// No sources.
    pub fn new() -> Sources<C> {
        Sources {
            entries: Vec::new(),
            problems: Vec::new(),
        }
    }

    /// Reads `source`, an entity's events or states, decoding each as `E` and handing it to
    /// `handler`. When the entity is deleted the view does nothing unless [`Sources::on_deleted`]
    /// says otherwise.
    pub fn on<E: DeserializeOwned + 'static>(
        mut self,
        source: Source,
        handler: fn(E, &Context) -> KeyedViewEffect<C::Row>,
    ) -> Sources<C> {
        self.entries.push(SourceOf {
            source,
            change: Box::new(move |bytes, ctx| {
                let change = Auto::<E>::new()
                    .decode_value(bytes)
                    .map_err(|e| format!("a change does not decode: {e}"))?;
                Ok(handler(change, ctx))
            }),
            deleted: None,
        });
        self
    }

    /// What the view does when the entity `source` reads is deleted.
    pub fn on_deleted(
        mut self,
        source: Source,
        handler: fn(&Context) -> KeyedViewEffect<C::Row>,
    ) -> Sources<C> {
        match self.entries.iter_mut().find(|e| e.source == source) {
            Some(entry) => entry.deleted = Some(Box::new(handler)),
            None => self.problems.push(format!(
                "view '{}' says what to do when {source:?} is deleted, and does not read it",
                C::COMPONENT_ID
            )),
        }
        self
    }
}

/// A keyed view. Implement it on a unit struct and register the struct's value.
pub trait KeyedView: Sized + 'static {
    /// One row; a handler names the key it is kept under.
    type Row: Serialize + DeserializeOwned + 'static;

    /// The component's id.
    const COMPONENT_ID: &'static str;

    /// The manifest the rows are stored under; the row type's simple name unless set.
    const ROW_MANIFEST: Option<&'static str> = None;

    /// What the view reads, and what it does with each change.
    fn sources() -> Sources<Self>;

    /// The queries the view can be asked by name, its own handlers among others: see
    /// [`DeclaredQuery`]. None unless declared.
    fn declared() -> Vec<DeclaredQuery> {
        Vec::new()
    }

    /// Raised to have the view emptied and every source read again from its beginning. `None` is
    /// version 1.
    fn version() -> Option<u32> {
        None
    }

    /// The codec the rows are stored with.
    fn row_codec() -> Auto<Self::Row> {
        Self::ROW_MANIFEST.map_or_else(Auto::new, Auto::named)
    }
}

pub(crate) struct Registration<C: KeyedView> {
    marker: PhantomData<fn() -> C>,
}

impl<C: KeyedView> ComponentOf<kinds::KeyedView> for C {
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

fn component_of(source: &Source) -> Option<&'static str> {
    match source {
        Source::Component(_, id) => Some(id),
        Source::Topic(_) => None,
    }
}

impl<C: KeyedView> Registered for Registration<C> {
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
        let sources = C::sources();
        proto::Component {
            kind: Kind::View as i32,
            id: C::COMPONENT_ID.to_string(),
            handlers: Vec::new(),
            detail: Some(proto::component::Detail::View(proto::ViewDetail {
                source: None,
                row_manifest: C::row_codec().form().manifest(),
                queries: Vec::new(),
                version: C::version(),
                sources: sources
                    .entries
                    .iter()
                    .map(|e| start_from::source_proto(&e.source, None))
                    .collect(),
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
        let view = C::COMPONENT_ID;
        let sources = C::sources();
        let mut problems = sources.problems;
        if view.is_empty() {
            problems.push("a keyed view has an empty component id".to_string());
        }
        if sources.entries.is_empty() {
            problems.push(format!(
                "view '{view}' declares no source; a keyed view reads one or more"
            ));
        }
        let mut seen = Vec::new();
        for entry in &sources.entries {
            match component_of(&entry.source) {
                Some(id) if seen.contains(&id) => problems.push(format!(
                    "view '{view}' reads '{id}' twice; each source is read once"
                )),
                Some(id) => seen.push(id),
                None => problems.push(format!(
                    "view '{view}' reads a topic; a keyed view reads entities, and a topic and an \
                     entity may not be sources of one view"
                )),
            }
        }
        if C::version() == Some(0) {
            problems.push(format!(
                "view '{view}' declares version 0; a version is a whole number of 1 or more"
            ));
        }
        problems
    }

    fn view(&self, request: proto::ViewRequest) -> Option<proto::ViewEffect> {
        let view = C::COMPONENT_ID;
        let source_id = request.source_id.clone().unwrap_or_else(|| {
            panic!("keyed view '{view}' was sent a change that names no source")
        });
        let sources = C::sources();
        let entry = sources
            .entries
            .iter()
            .find(|e| component_of(&e.source) == Some(source_id.as_str()))
            .unwrap_or_else(|| {
                panic!(
                    "keyed view '{view}' was sent a change of '{source_id}', which it does not read"
                )
            });
        let ctx = change_context(view, request.metadata.as_ref()).with_rows(view);
        let effect = match request.event.filter(|_| !request.deleted) {
            Some(event) => (entry.change)(&event.data, &ctx)
                .unwrap_or_else(|e| panic!("keyed view '{view}', source '{source_id}': {e}")),
            None => entry
                .deleted
                .as_ref()
                .map_or_else(KeyedViewEffect::ignore, |deleted| deleted(&ctx)),
        };
        let codec = C::row_codec();
        let changes = effect
            .into_changes()
            .into_iter()
            .map(|change| match change {
                RowChange::Upsert(key, row) => proto::RowChange {
                    change: Some(proto::row_change::Change::Upsert(
                        codec.to_payload(&row).unwrap_or_else(|e| {
                            panic!("keyed view '{view}': row '{key}' does not encode: {e}")
                        }),
                    )),
                    key,
                },
                RowChange::Delete(key) => proto::RowChange {
                    key,
                    change: Some(proto::row_change::Change::Delete(proto::Empty {})),
                },
            })
            .collect();
        Some(proto::ViewEffect {
            effect: Some(proto::view_effect::Effect::Rows(proto::RowChanges {
                changes,
            })),
        })
    }
}
