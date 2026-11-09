package cart

import (
	"context"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// ComponentID is the cart's component id, the Scala sample's.
const ComponentID = "shopping-cart"

type effect[R any] = ankka.Effect[Cart, Event, R]

// docs:start entity
// docs:start handlers

// Entity is the shopping cart: the same wire names and manifests as the Scala sample's. The
// string each handler is registered under is its wire name, separate from the Go function's.
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
	// docs:start codecs
	// A JSON codec derived from the type, under the manifest the journal records it by.
	ankka.StateCodec(ankka.CodecFor[Cart](ankka.Manifest("shopping-cart"))),
	ankka.EventCodec(ankka.CodecFor[Event](ankka.Manifest("shopping-cart-event"))),
	// docs:end codecs
	ankka.SnapshotEvery(3),
)

// docs:end handlers

var checkedOut = ankka.Refuse(ankka.Conflict, "cart is already checked out")

func done(Cart) ankka.Done { return ankka.Done{} }

// docs:start add-item

// addItem describes what should happen: a refusal is a returned error and nothing is persisted; an
// accepted item is an event the runtime persists before it replies.
func addItem(_ context.Context, c Cart, item LineItem) (effect[ankka.Done], error) {
	if c.CheckedOut {
		return effect[ankka.Done]{}, checkedOut
	}
	if item.Quantity <= 0 {
		return effect[ankka.Done]{}, ankka.Refusef(ankka.BadRequest, "quantity must be greater than zero, was %d", item.Quantity)
	}
	return ankka.Persist[Cart, Event, ankka.Done](ItemAdded{Item: item}).ThenReply(done), nil
}

// docs:end add-item

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

// docs:start discard

// discard records the discard and then deletes the cart, so the same id starts again empty. The
// event is persisted before the deletion takes effect, so a consumer or view downstream still
// observes that this cart was discarded rather than merely vanishing. A checked-out cart is a
// record of an order and is not discarded.
func discard(_ context.Context, c Cart, _ ankka.Unit) (effect[ankka.Done], error) {
	if c.CheckedOut {
		return effect[ankka.Done]{}, checkedOut
	}
	return ankka.Persist[Cart, Event, ankka.Done](Discarded{}).DeleteEntity().ThenReply(done), nil
}

// docs:end discard
// docs:end entity
