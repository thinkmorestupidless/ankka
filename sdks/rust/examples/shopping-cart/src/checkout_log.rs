//! Where the notifier records checkouts: a key value entity per cart holding when it happened.

use ankka::prelude::*;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CheckoutRecord {
    #[serde(rename = "cartId")]
    pub cart_id: String,
    pub at: i64,
    pub notified: bool,
}

// docs:start key-value
pub struct CheckoutLog;

impl CheckoutLog {
    fn record(_: &CheckoutRecord, at: i64, ctx: &Context) -> KeyValueEffect<CheckoutRecord, Done> {
        let record = CheckoutRecord {
            cart_id: ctx.entity_id().to_string(),
            at,
            notified: true,
        };
        effects::update_state(record).then_reply_value(Done)
    }

    fn get(record: &CheckoutRecord, _: (), _: &Context) -> ReadOnlyEffect<CheckoutRecord> {
        effects::reply(record.clone())
    }
}

impl KeyValueEntity for CheckoutLog {
    type State = CheckoutRecord;
    const COMPONENT_ID: &'static str = "checkout-log";
    const STATE_MANIFEST: Option<&'static str> = Some("checkout-record");

    fn empty_state(cart_id: &str) -> CheckoutRecord {
        CheckoutRecord {
            cart_id: cart_id.to_string(),
            at: 0,
            notified: false,
        }
    }

    fn handlers() -> KeyValueHandlers<CheckoutLog> {
        KeyValueHandlers::new()
            .command("record", CheckoutLog::record)
            .query("get", CheckoutLog::get)
    }
}
// docs:end key-value
