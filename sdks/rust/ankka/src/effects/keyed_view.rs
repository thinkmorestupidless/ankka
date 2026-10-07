//! Effects for keyed views: the rows one change writes and deletes, each named by its key.

/// One row a keyed view writes, or deletes, by the key it is kept under.
#[derive(Debug, Clone, PartialEq)]
pub enum RowChange<Row> {
    /// Write `row` under `key`.
    Upsert(String, Row),
    /// Delete the row under `key`.
    Delete(String),
}

impl<Row> RowChange<Row> {
    /// The key the change is to.
    pub fn key(&self) -> &str {
        match self {
            RowChange::Upsert(key, _) | RowChange::Delete(key) => key,
        }
    }
}

/// What a keyed view's handler says to do with one change: rows to write and rows to delete, in
/// order; a later change to a key wins. Empty is "nothing".
///
/// The runtime deletes no row a handler did not name, so a row is moved by deleting its old key and
/// writing its new one in the same effect: `KeyedViewEffect::delete_row(old).and(KeyedViewEffect::update_row(new, row))`.
#[derive(Debug, Clone, PartialEq)]
pub struct KeyedViewEffect<Row> {
    changes: Vec<RowChange<Row>>,
}

impl<Row> KeyedViewEffect<Row> {
    /// Write `row` under `key`.
    pub fn update_row(key: impl Into<String>, row: Row) -> KeyedViewEffect<Row> {
        KeyedViewEffect {
            changes: vec![RowChange::Upsert(key.into(), row)],
        }
    }

    /// Delete the row under `key`.
    pub fn delete_row(key: impl Into<String>) -> KeyedViewEffect<Row> {
        KeyedViewEffect {
            changes: vec![RowChange::Delete(key.into())],
        }
    }

    /// Write each row under its key.
    pub fn update_rows<K: Into<String>>(
        rows: impl IntoIterator<Item = (K, Row)>,
    ) -> KeyedViewEffect<Row> {
        KeyedViewEffect {
            changes: rows
                .into_iter()
                .map(|(key, row)| RowChange::Upsert(key.into(), row))
                .collect(),
        }
    }

    /// Delete the row under each key.
    pub fn delete_rows<K: Into<String>>(keys: impl IntoIterator<Item = K>) -> KeyedViewEffect<Row> {
        KeyedViewEffect {
            changes: keys
                .into_iter()
                .map(|key| RowChange::Delete(key.into()))
                .collect(),
        }
    }

    /// Nothing.
    pub fn ignore() -> KeyedViewEffect<Row> {
        KeyedViewEffect {
            changes: Vec::new(),
        }
    }

    /// This effect's changes, then `other`'s.
    pub fn and(mut self, other: KeyedViewEffect<Row>) -> KeyedViewEffect<Row> {
        self.changes.extend(other.changes);
        self
    }

    /// The changes, in order.
    pub fn changes(&self) -> &[RowChange<Row>] {
        &self.changes
    }

    /// Whether the effect changes nothing.
    pub fn is_empty(&self) -> bool {
        self.changes.is_empty()
    }

    pub(crate) fn into_changes(self) -> Vec<RowChange<Row>> {
        self.changes
    }
}
