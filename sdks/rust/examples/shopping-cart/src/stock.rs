//! The topics page's examples: a view over a topic, and a consumer that reads one topic and
//! publishes to another, stating the contract the topic carries. A component that reads a topic
//! needs a broker, so neither is in the module's service.

use ankka::effects::{consumer, view};
use ankka::prelude::*;

// docs:start topic-view
/// What the topic carries, as its JSON: a product's stock moved by `delta`.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct StockEvent {
    #[serde(rename = "productId")]
    pub product_id: String,
    pub delta: i32,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct StockRow {
    #[serde(rename = "productId")]
    pub product_id: String,
    pub level: i32,
}

pub struct StockLevels;

impl View for StockLevels {
    type Row = StockRow;
    type Event = StockEvent;
    const COMPONENT_ID: &'static str = "stock-levels";
    const ROW_MANIFEST: Option<&'static str> = Some("stock-row");

    fn source() -> Source {
        Source::topic("stock-events")
    }

    // docs:start version
    /// Raising the version rebuilds the view, as far back as the broker retains.
    fn version() -> Option<u32> {
        Some(2)
    }
    // docs:end version

    fn on_event(row: Option<StockRow>, event: StockEvent, ctx: &Context) -> ViewEffect<StockRow> {
        let current = row.unwrap_or_else(|| StockRow {
            product_id: ctx.metadata().subject().unwrap_or_default().to_string(),
            level: 0,
        });
        view::update_row(StockRow {
            level: current.level + event.delta,
            ..current
        })
    }
}
// docs:end topic-view

/// What the relay publishes.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct FanLine {
    #[serde(rename = "productId")]
    pub product_id: String,
    pub delta: i32,
}

/// The schema document fetched from the project with `ankka projects topics schema get`.
const TRANSACTION_SCHEMA: &str = concat!(
    r#"{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","#,
    r#""required":["productId","delta"],"#,
    r#""properties":{"productId":{"type":"string"},"delta":{"type":"integer"}}}"#
);

// docs:start contract
/// Reads "events" and publishes to "transactions", stating the contract the topic carries.
pub struct WalletRelay;

impl WalletRelay {
    fn transactions() -> Contract {
        Contract::from_bytes(TRANSACTION_SCHEMA.as_bytes(), "transaction.v1")
            .expect("the transaction schema is JSON")
    }
}

impl Consumer for WalletRelay {
    type Message = StockEvent;
    const COMPONENT_ID: &'static str = "wallet";

    fn source() -> Source {
        Source::topic("events")
    }

    fn start_from() -> Option<StartFrom> {
        Some(StartFrom::Earliest)
    }

    fn produces() -> Option<Publication> {
        Some(Publication::to("transactions").contract(WalletRelay::transactions()))
    }

    fn on_message(event: StockEvent, _: &Context) -> ConsumerEffect {
        consumer::produce(FanLine {
            product_id: event.product_id,
            delta: event.delta,
        })
    }
}
// docs:end contract

/// The view and the relay, as a service the tests run against.
pub fn build() -> Service {
    Service::new("ankka-rust")
        .register(StockLevels)
        .register(WalletRelay)
}
