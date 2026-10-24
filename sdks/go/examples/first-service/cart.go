// The shopping cart "Your first service in Go" builds: one event sourced entity.
package main

// docs:start entity
import (
	"context"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

type LineItem struct {
	ProductID string `json:"productId"`
	Name      string `json:"name"`
	Quantity  int    `json:"quantity"`
}

type ShoppingCart struct {
	CartID     string     `json:"cartId"`
	Items      []LineItem `json:"items"`
	CheckedOut bool       `json:"checkedOut"`
}

// An event is one of a closed set of types, each written under its discriminator.
type Event interface{ cartEvent() }

type ItemAdded struct {
	Item LineItem `json:"item"`
}

type CheckedOut struct{}

func (ItemAdded) cartEvent()  {}
func (CheckedOut) cartEvent() {}

var events = ankka.Cases[Event](
	ankka.Case[Event, ItemAdded]("ItemAdded"),
	ankka.Case[Event, CheckedOut]("CheckedOut"),
)

var ShoppingCartEntity = ankka.EventSourced[ShoppingCart, Event]("shopping-cart", events, empty, apply,
	ankka.Command("add-item", addItem),
	ankka.Command("checkout", checkout),
	ankka.Query("get-cart", func(_ context.Context, cart ShoppingCart, _ ankka.Unit) (ankka.ReadOnlyEffect[ShoppingCart], error) {
		return ankka.Reply(cart), nil
	}),
	ankka.StateCodec(ankka.CodecFor[ShoppingCart](ankka.Manifest("shopping-cart"))),
	ankka.EventCodec(ankka.CodecFor[Event](ankka.Manifest("shopping-cart-event"))),
)

func empty(cartID string) ShoppingCart { return ShoppingCart{CartID: cartID, Items: []LineItem{}} }

func apply(cart ShoppingCart, event Event) ShoppingCart {
	switch e := event.(type) {
	case ItemAdded:
		cart.Items = append(cart.Items, e.Item)
	case CheckedOut:
		cart.CheckedOut = true
	}
	return cart
}

type effect[R any] = ankka.Effect[ShoppingCart, Event, R]

func addItem(_ context.Context, cart ShoppingCart, item LineItem) (effect[ankka.Done], error) {
	if cart.CheckedOut {
		return effect[ankka.Done]{}, ankka.Refuse(ankka.Conflict, "cart is already checked out")
	}
	if item.Quantity <= 0 {
		return effect[ankka.Done]{}, ankka.Refusef(ankka.BadRequest, "quantity must be greater than zero, was %d", item.Quantity)
	}
	return ankka.Persist[ShoppingCart, Event, ankka.Done](ItemAdded{Item: item}).
		ThenReply(func(ShoppingCart) ankka.Done { return ankka.Done{} }), nil
}

func checkout(_ context.Context, cart ShoppingCart, _ ankka.Unit) (effect[ShoppingCart], error) {
	if cart.CheckedOut {
		return effect[ShoppingCart]{}, ankka.Refuse(ankka.Conflict, "cart is already checked out")
	}
	return ankka.Persist[ShoppingCart, Event, ShoppingCart](CheckedOut{}).ThenReplyState(), nil
}

// docs:end entity
