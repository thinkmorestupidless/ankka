package samples

import (
	"context"
	"reflect"
	"testing"

	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/cart"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/conformance"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

func TestTheContentsNodeIsReadFromTheCart(t *testing.T) {
	ctx := context.Background()
	// The kit hosts the cart the consumer reads; nothing has been added to it, so it is empty.
	kit := testkit.GraphConsumer(CartContentsGraph, testkit.Calling(cart.Entity))
	defer kit.Close()
	deltas, err := kit.OnMessage(ctx, "c1", cart.ItemAdded{Item: pen}, testkit.Sequence(1))
	if err != nil || len(deltas) != 1 {
		t.Fatalf("published %v, %v", deltas, err)
	}
	if d := deltas[0]; d.Key() != "node:cart-contents:c1" || d.Version != 1 || d.Properties["lines"] != int64(0) {
		t.Fatalf("the node is %+v", d)
	}
	if deltas, _ := kit.OnMessage(ctx, "c1", cart.Discarded{}, testkit.Sequence(2)); len(deltas) != 0 {
		t.Fatalf("a discard published %v", deltas)
	}
	if deltas, _ := kit.OnDelete(ctx, "c1", testkit.Sequence(3)); len(deltas) != 1 || deltas[0].Kind != "tombstone" {
		t.Fatalf("a deletion published %v", deltas)
	}
}

// docs:start graph-test

func TestTheCartGraphFollowsTheCart(t *testing.T) {
	ctx := context.Background()
	kit := testkit.GraphConsumer(conformance.CartGraph)
	defer kit.Close()

	// An item added publishes the cart's node, at the event's sequence number.
	deltas, err := kit.OnMessage(ctx, "c1", cart.ItemAdded{Item: pen}, testkit.Sequence(1))
	if err != nil || len(deltas) != 1 || deltas[0].Key() != "node:cart:c1" {
		t.Fatalf("an item added published %v, %v", deltas, err)
	}
	node := deltas[0]
	if node.Version != 1 || !reflect.DeepEqual(node.Labels, []string{"Cart"}) ||
		!reflect.DeepEqual(node.Properties, map[string]any{"cartId": "c1", "checkedOut": false}) {
		t.Fatalf("the cart's node is %+v", node)
	}

	// A checkout publishes the cart as checked out, the checkout, and the edge between them.
	deltas, _ = kit.OnMessage(ctx, "c1", cart.CheckedOut{}, testkit.Sequence(4))
	keys := []string{}
	for _, d := range deltas {
		keys = append(keys, d.Key())
		if d.Version != 4 {
			t.Fatalf("%s is at version %d", d.Key(), d.Version)
		}
	}
	if !reflect.DeepEqual(keys, []string{"node:cart:c1", "node:checkout:c1", "edge:checked-out:c1"}) {
		t.Fatalf("a checkout published %v", keys)
	}
	if edge := deltas[2]; edge.Type != "CHECKED_OUT" || edge.From != "cart:c1" || edge.To != "checkout:c1" {
		t.Fatalf("the edge is %+v", edge)
	}

	// A discarded cart's deletion publishes its tombstone.
	if deltas, _ := kit.OnMessage(ctx, "c1", cart.Discarded{}, testkit.Sequence(2)); len(deltas) != 0 {
		t.Fatalf("a discard published %v", deltas)
	}
	deltas, _ = kit.OnDelete(ctx, "c1", testkit.Sequence(3))
	if len(deltas) != 1 || deltas[0].Kind != "tombstone" || deltas[0].Element != "node" ||
		deltas[0].Key() != "node:cart:c1" || deltas[0].Version != 3 {
		t.Fatalf("the deletion published %+v", deltas)
	}
}

// docs:end graph-test
