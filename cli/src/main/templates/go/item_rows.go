package main

import (
	"context"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// ItemRow is one item as the view keeps it.
type ItemRow struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Count int    `json:"count"`
}

// ItemRowsID is the view's component id.
const ItemRowsID = "item-rows"

// ItemRows is a view: fed the entity's events in order, keeping one row per item, the read side an
// entity cannot answer on its own. ItemRows live in Postgres, beside the journal, and belong to the
// sidecar.
var ItemRows = ankka.View[ItemRow](ItemRowsID,
	ankka.OnChange(ankka.Changes(ItemEntity), func(ctx context.Context, e Event, row *ItemRow) (ankka.ViewEffect[ItemRow], error) {
		current := ItemRow{}
		if row != nil {
			current = *row
		} else if c, ok := ankka.ChangeFrom(ctx); ok {
			current.ID = c.Subject
		}
		switch e := e.(type) {
		case ItemAdded:
			current.Name = e.Name
			current.Count += e.Count
		case ItemRemoved:
			current.Count -= e.Count
		}
		return ankka.UpdateRow(current), nil
	}),
	ankka.RowCodec(ankka.CodecFor[ItemRow](ankka.Manifest("item-row"))),
)
