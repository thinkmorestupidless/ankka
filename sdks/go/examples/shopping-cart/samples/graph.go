package samples

import (
	"context"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/cart"
)

// docs:start graph-from-state

// CartContentsGraph publishes what each cart holds, as a node of its own beside the cart's.
//
// An event says what changed (one item added) and an element is its whole state: how many lines
// the cart has now. So this consumer does not build the element from the event. It reads the cart
// through the client and publishes what it finds, at the version of the event it is handling.
//
// The cart it reads may already be ahead of that event. The delta then carries a later state at an
// earlier version, and the events still to come publish it again at their own: the graph is never
// behind for longer than the consumer is, and ends where the cart is.
var CartContentsGraph = ankka.GraphConsumer[cart.Event]("cart-contents-graph", ankka.Changes(cart.Entity),
	"cart-graph",
	func(ctx context.Context, e cart.Event) (ankka.GraphEffect, error) {
		if _, ok := e.(cart.Discarded); ok {
			return ankka.GraphIgnore(), nil
		}
		change, _ := ankka.ChangeFrom(ctx)
		c, err := ankka.Invoke[cart.Cart](ctx, cart.ComponentID, change.Subject, "get-cart", ankka.Unit{})
		if err != nil {
			return ankka.GraphEffect{}, err
		}
		return ankka.Publish(ankka.Node("cart-contents:"+change.Subject, ankka.Labels("CartContents"),
			ankka.WithProperties(ankka.Properties{
				"cartId": change.Subject, "lines": len(c.Items), "quantity": cart.TotalQuantity(c),
			}))), nil
	},
	ankka.WhenDeleted(func(ctx context.Context) (ankka.GraphEffect, error) {
		change, _ := ankka.ChangeFrom(ctx)
		return ankka.Publish(ankka.NodeTombstone("cart-contents:" + change.Subject)), nil
	}),
)

// docs:end graph-from-state
