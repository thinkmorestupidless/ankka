//! The cart's domain, field for field the Scala and Python samples' — which is what lets the three
//! share a journal. Plain Rust with no ankka types: these rules are tested with nothing running.

use ankka::prelude::*;

// docs:start domain
/// One product in a cart.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct LineItem {
    #[serde(rename = "productId")]
    pub product_id: String,
    pub name: String,
    pub quantity: i32,
}

/// A cart: what its events fold to.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct ShoppingCart {
    #[serde(rename = "cartId")]
    pub cart_id: String,
    pub items: Vec<LineItem>,
    #[serde(rename = "checkedOut")]
    pub checked_out: bool,
}

impl ShoppingCart {
    /// A cart with nothing in it.
    pub fn empty(cart_id: &str) -> ShoppingCart {
        ShoppingCart {
            cart_id: cart_id.to_string(),
            items: Vec::new(),
            checked_out: false,
        }
    }

    /// Adds a line, folding the quantity into an existing line for the same product.
    pub fn add_item(mut self, item: LineItem) -> ShoppingCart {
        let merged = match self.items.iter().find(|i| i.product_id == item.product_id) {
            Some(existing) => LineItem {
                quantity: existing.quantity + item.quantity,
                ..item
            },
            None => item,
        };
        self.items.retain(|i| i.product_id != merged.product_id);
        self.items.push(merged);
        // Sorted, as the other carts sort: two carts holding the same products are equal
        // whatever the order they were added in.
        self.items.sort_by(|a, b| a.product_id.cmp(&b.product_id));
        self
    }

    pub fn remove_item(mut self, product_id: &str) -> ShoppingCart {
        self.items.retain(|i| i.product_id != product_id);
        self
    }

    pub fn on_checked_out(self) -> ShoppingCart {
        ShoppingCart {
            checked_out: true,
            ..self
        }
    }

    pub fn contains(&self, product_id: &str) -> bool {
        self.items.iter().any(|i| i.product_id == product_id)
    }

    pub fn total_quantity(&self) -> i32 {
        self.items.iter().map(|i| i.quantity).sum()
    }

    pub fn is_empty(&self) -> bool {
        self.items.is_empty()
    }
}
// docs:end domain

// docs:start events
/// Everything that can happen to a cart, stored as `{"type":"ItemAdded",…}` as every language
/// stores it.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
#[serde(tag = "type")]
pub enum ShoppingCartEvent {
    ItemAdded {
        item: LineItem,
    },
    ItemRemoved {
        #[serde(rename = "productId")]
        product_id: String,
    },
    CheckedOut,
}
// docs:end events

#[cfg(test)]
mod tests {
    use super::*;

    fn pen() -> LineItem {
        LineItem {
            product_id: "p1".into(),
            name: "Pen".into(),
            quantity: 2,
        }
    }

    /// The spelling the Scala cart's journal holds: what lets the two share it.
    #[test]
    fn events_and_state_are_stored_as_the_other_carts_store_them() {
        assert_eq!(
            json(&ShoppingCartEvent::ItemAdded { item: pen() }),
            r#"{"type":"ItemAdded","item":{"productId":"p1","name":"Pen","quantity":2}}"#
        );
        assert_eq!(
            json(&ShoppingCartEvent::ItemRemoved {
                product_id: "p1".into()
            }),
            r#"{"type":"ItemRemoved","productId":"p1"}"#
        );
        assert_eq!(
            json(&ShoppingCartEvent::CheckedOut),
            r#"{"type":"CheckedOut"}"#
        );
        assert_eq!(
            json(&ShoppingCart::empty("c1").add_item(pen())),
            r#"{"cartId":"c1","items":[{"productId":"p1","name":"Pen","quantity":2}],"checkedOut":false}"#
        );
    }

    #[test]
    fn adding_a_product_again_folds_its_quantity_and_keeps_the_lines_sorted() {
        let cart = ShoppingCart::empty("c1")
            .add_item(LineItem {
                product_id: "p2".into(),
                ..pen()
            })
            .add_item(pen())
            .add_item(pen());
        let lines: Vec<(&str, i32)> = cart
            .items
            .iter()
            .map(|i| (i.product_id.as_str(), i.quantity))
            .collect();
        assert_eq!(lines, vec![("p1", 4), ("p2", 2)]);
        assert_eq!(cart.total_quantity(), 6);
    }

    fn json<T: Serialize + 'static>(value: &T) -> String {
        let payload = ankka::codec::encode_payload(value).expect("encodes");
        String::from_utf8(payload.data).expect("utf-8")
    }
}
