//! Effects for views: what one change to the source does to the view's row.

/// What a view decided about its row, for one change to its source.
#[derive(Debug, Clone, PartialEq)]
pub enum ViewEffect<Row> {
    /// Write this row.
    UpdateRow(Row),
    /// Delete the row.
    DeleteRow,
    /// Leave the row as it is.
    Ignore,
}

/// Write `row` as the view's row for the changed entity.
pub fn update_row<Row>(row: Row) -> ViewEffect<Row> {
    ViewEffect::UpdateRow(row)
}

/// Delete the changed entity's row.
pub fn delete_row<Row>() -> ViewEffect<Row> {
    ViewEffect::DeleteRow
}

/// Leave the row as it is.
pub fn ignore<Row>() -> ViewEffect<Row> {
    ViewEffect::Ignore
}
