package cart

import (
	"context"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// ComponentID is the cart's component id, the Scala sample's.
const ComponentID = "shopping-cart"

type effect[R any] = ankka.Effect[Cart, Event, R]

// docs:start entity

// Entity is the shopping cart: the same wire names and manifests as the Scala sample's.
var Entity = ankka.EventSourced[Cart, Event](ComponentID, Events, Empty, Apply,
	ankka.Command("add-item", addItem),
	ankka.Command("remove-item", removeItem),
	ankka.Command("checkout", checkout),
	ankka.Command("discard", discard),
	ankka.Query("get-cart", func(_ context.Context, c Cart, _ ankka.Unit) (ankka.ReadOnlyEffect[Cart], error) {
		return ankka.Reply(c), nil
	}),
	ankka.Query("total-quantity", func(_ context.Context, c Cart, _ ankka.Unit) (ankka.ReadOnlyEffect[int], error) {
		return ankka.Reply(TotalQuantity(c)), nil
	}),
	ankka.StateCodec(ankka.CodecFor[Cart](ankka.Manifest("shopping-cart"))),
	ankka.EventCodec(ankka.CodecFor[Event](ankka.Manifest("shopping-cart-event"))),
	ankka.SnapshotEvery(3),
)

var checkedOut = ankka.Refuse(ankka.Conflict, "cart is already checked out")

func done(Cart) ankka.Done { return ankka.Done{} }

func addItem(_ context.Context, c Cart, item LineItem) (effect[ankka.Done], error) {
	if c.CheckedOut {
		return effect[ankka.Done]{}, checkedOut
	}
	if item.Quantity <= 0 {
		return effect[ankka.Done]{}, ankka.Refusef(ankka.BadRequest, "quantity must be greater than zero, was %d", item.Quantity)
	}
	return ankka.Persist[Cart, Event, ankka.Done](ItemAdded{Item: item}).ThenReply(done), nil
}

func removeItem(_ context.Context, c Cart, productID string) (effect[ankka.Done], error) {
	if c.CheckedOut {
		return effect[ankka.Done]{}, checkedOut
	}
	if !Contains(c, productID) {
		return effect[ankka.Done]{}, ankka.Refusef(ankka.NotFound, "cart does not contain '%s'", productID)
	}
	return ankka.Persist[Cart, Event, ankka.Done](ItemRemoved{ProductID: productID}).ThenReply(done), nil
}

// checkout keeps the cart, the record of what was ordered, and refuses every later change to it.
func checkout(_ context.Context, c Cart, _ ankka.Unit) (effect[Cart], error) {
	if c.CheckedOut {
		return effect[Cart]{}, checkedOut
	}
	if len(c.Items) == 0 {
		return effect[Cart]{}, ankka.Refuse(ankka.BadRequest, "cannot check out an empty cart")
	}
	return ankka.Persist[Cart, Event, Cart](CheckedOut{}).ThenReplyState(), nil
}

// discard persists the event, then deletes the cart, so a consumer downstream sees the discard
// rather than a cart that vanished; the same id then starts again empty.
func discard(_ context.Context, c Cart, _ ankka.Unit) (effect[ankka.Done], error) {
	if c.CheckedOut {
		return effect[ankka.Done]{}, checkedOut
	}
	return ankka.Persist[Cart, Event, ankka.Done](Discarded{}).DeleteEntity().ThenReply(done), nil
}

// docs:end entity
