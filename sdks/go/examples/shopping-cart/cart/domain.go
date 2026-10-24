// Package cart is the shopping cart, field for field the Scala sample's: that is what lets a Scala,
// a Python, a TypeScript and a Go cart share one journal.
package cart

import (
	"slices"
	"strings"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// docs:start domain

// LineItem is one product in a cart.
type LineItem struct {
	ProductID string `json:"productId"`
	Name      string `json:"name"`
	Quantity  int    `json:"quantity"`
}

// Cart is a cart's state.
type Cart struct {
	CartID     string     `json:"cartId"`
	Items      []LineItem `json:"items"`
	CheckedOut bool       `json:"checkedOut"`
}

// docs:start events

// Event is what a cart persists.
type Event interface{ cartEvent() }

// ItemAdded is an item put in the cart.
type ItemAdded struct {
	Item LineItem `json:"item"`
}

// ItemRemoved is a product taken out of the cart.
type ItemRemoved struct {
	ProductID string `json:"productId"`
}

// CheckedOut is the cart ordered.
type CheckedOut struct{}

// Discarded is the cart thrown away.
type Discarded struct{}

func (ItemAdded) cartEvent()   {}
func (ItemRemoved) cartEvent() {}
func (CheckedOut) cartEvent()  {}
func (Discarded) cartEvent()   {}

// Events are the cases of Event, under the Scala sample's discriminators.
var Events = ankka.Cases[Event](
	ankka.Case[Event, ItemAdded]("ItemAdded"),
	ankka.Case[Event, ItemRemoved]("ItemRemoved"),
	ankka.Case[Event, CheckedOut]("CheckedOut"),
	ankka.Case[Event, Discarded]("Discarded"),
)

// docs:end events
// docs:end domain

// Empty is the cart with nothing in it.
func Empty(cartID string) Cart { return Cart{CartID: cartID, Items: []LineItem{}} }

// AddItem adds an item, merging quantities for a product already present; items stay sorted by
// product, as the Scala cart keeps them.
func AddItem(c Cart, item LineItem) Cart {
	items := make([]LineItem, 0, len(c.Items)+1)
	for _, i := range c.Items {
		if i.ProductID == item.ProductID {
			item.Quantity += i.Quantity
			continue
		}
		items = append(items, i)
	}
	items = append(items, item)
	slices.SortFunc(items, func(a, b LineItem) int { return strings.Compare(a.ProductID, b.ProductID) })
	c.Items = items
	return c
}

// RemoveItem takes a product out.
func RemoveItem(c Cart, productID string) Cart {
	items := make([]LineItem, 0, len(c.Items))
	for _, i := range c.Items {
		if i.ProductID != productID {
			items = append(items, i)
		}
	}
	c.Items = items
	return c
}

// Contains reports whether the cart holds the product.
func Contains(c Cart, productID string) bool {
	return slices.ContainsFunc(c.Items, func(i LineItem) bool { return i.ProductID == productID })
}

// TotalQuantity is how many items the cart holds.
func TotalQuantity(c Cart) int {
	n := 0
	for _, i := range c.Items {
		n += i.Quantity
	}
	return n
}

// Apply folds one event into the cart.
func Apply(c Cart, e Event) Cart {
	switch e := e.(type) {
	case ItemAdded:
		return AddItem(c, e.Item)
	case ItemRemoved:
		return RemoveItem(c, e.ProductID)
	case CheckedOut:
		c.CheckedOut = true
	}
	// Discarded: the cart is deleted straight after; the event is there for what reads the journal.
	return c
}
