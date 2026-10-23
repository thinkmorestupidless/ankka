package cart

import (
	"context"
	"maps"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// docs:start view

// Row is one cart as the view keeps it: what it holds by product, and whether it was checked out.
type Row struct {
	CartID     string         `json:"cartId"`
	Quantities map[string]int `json:"quantities"`
	CheckedOut bool           `json:"checkedOut"`
}

// RowsID is the view's id.
const RowsID = "cart-rows"

// Rows is a queryable projection of every cart: the entity answers by cart id, this answers the
// rest.
var Rows = ankka.View[Row](RowsID,
	ankka.OnChange(ankka.Changes(Entity), func(ctx context.Context, e Event, row *Row) (ankka.ViewEffect[Row], error) {
		current := Row{Quantities: map[string]int{}}
		if row != nil {
			current = *row
			current.Quantities = maps.Clone(row.Quantities)
			if current.Quantities == nil {
				current.Quantities = map[string]int{}
			}
		}
		if c, ok := ankka.ChangeFrom(ctx); ok && current.CartID == "" {
			current.CartID = c.Subject
		}
		switch e := e.(type) {
		case ItemAdded:
			current.Quantities[e.Item.ProductID] += e.Item.Quantity
		case ItemRemoved:
			delete(current.Quantities, e.ProductID)
		case CheckedOut:
			current.CheckedOut = true
		default:
			// Discarded: the deletion that follows removes the row.
			return ankka.IgnoreChange[Row](), nil
		}
		return ankka.UpdateRow(current), nil
	}),
	ankka.RowCodec(ankka.CodecFor[Row](ankka.Manifest("cart-row"))),
)

// docs:end view
