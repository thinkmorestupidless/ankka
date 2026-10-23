package cart_test

import (
	"context"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/cart"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

func TestTheCartHasNoProblems(t *testing.T) {
	if p := cart.Service().Problems(); len(p) > 0 {
		t.Fatalf("problems: %v", p)
	}
}

var (
	pen = cart.LineItem{ProductID: "pen", Name: "Pen", Quantity: 2}
	ink = cart.LineItem{ProductID: "ink", Name: "Ink", Quantity: 1}
)

// docs:start unit-test

func TestAddingAndRemovingItems(t *testing.T) {
	ctx := context.Background()
	kit := testkit.EventSourced(cart.Entity, testkit.EntityID("c1"))
	if r := kit.Command(ctx, "add-item", pen); r.Refusal() != nil || len(r.Events()) != 1 || r.Events()[0] != (cart.ItemAdded{Item: pen}) {
		t.Fatalf("add-item answered %+v", r)
	}
	kit.Command(ctx, "add-item", ink)
	if q := kit.Query(ctx, "total-quantity", ankka.Unit{}); q.Reply() != 3 {
		t.Fatalf("the total is %v", q.Reply())
	}
	// Items are kept sorted by product, as the Scala sample keeps them.
	if got := kit.State().Items; len(got) != 2 || got[0] != ink || got[1] != pen {
		t.Fatalf("the items are %+v", got)
	}
	if r := kit.Command(ctx, "remove-item", "pen"); r.Refusal() != nil || r.Events()[0] != (cart.ItemRemoved{ProductID: "pen"}) {
		t.Fatalf("remove-item answered %+v", r)
	}
	if got := kit.State(); got.CartID != "c1" || len(got.Items) != 1 || got.Items[0] != ink {
		t.Fatalf("after removing, the cart is %+v", got)
	}
}

// docs:end unit-test

func TestRefusalsChangeNothing(t *testing.T) {
	ctx := context.Background()
	kit := testkit.EventSourced(cart.Entity)
	for _, c := range []struct {
		wire  string
		input any
		code  ankka.ErrorCode
	}{
		{"add-item", cart.LineItem{ProductID: "pen", Name: "Pen", Quantity: 0}, ankka.BadRequest},
		{"remove-item", "nothing", ankka.NotFound},
		{"checkout", ankka.Unit{}, ankka.BadRequest},
	} {
		r := kit.Command(ctx, c.wire, c.input)
		if r.Refusal() == nil || r.Refusal().Code != c.code || len(r.Events()) != 0 {
			t.Errorf("%s answered %+v, want a %v refusal", c.wire, r, c.code)
		}
	}
	if len(kit.State().Items) != 0 {
		t.Fatalf("a refusal changed the cart: %+v", kit.State())
	}
}

func TestACheckedOutCartRefusesEveryChange(t *testing.T) {
	ctx := context.Background()
	kit := testkit.EventSourced(cart.Entity, testkit.EntityID("c2"))
	kit.Command(ctx, "add-item", pen)
	r := kit.Command(ctx, "checkout", ankka.Unit{})
	if got, ok := r.Reply().(cart.Cart); !ok || !got.CheckedOut || got.CartID != "c2" {
		t.Fatalf("checkout answered %+v", r.Reply())
	}
	for _, c := range []struct {
		wire  string
		input any
	}{{"add-item", ink}, {"remove-item", "pen"}, {"checkout", ankka.Unit{}}, {"discard", ankka.Unit{}}} {
		if r := kit.Command(ctx, c.wire, c.input); r.Refusal() == nil || r.Refusal().Code != ankka.Conflict {
			t.Errorf("%s on a checked out cart answered %+v", c.wire, r)
		}
	}
}

func TestDiscardStartsTheCartAgain(t *testing.T) {
	ctx := context.Background()
	kit := testkit.EventSourced(cart.Entity, testkit.EntityID("c3"))
	kit.Command(ctx, "add-item", pen)
	if r := kit.Command(ctx, "discard", ankka.Unit{}); !r.Deleted() || r.Events()[0] != (cart.Discarded{}) {
		t.Fatalf("discard answered %+v", r)
	}
	if got := kit.State(); got.CartID != "c3" || len(got.Items) != 0 {
		t.Fatalf("after a discard the cart is %+v", got)
	}
}

func TestTheFold(t *testing.T) {
	c := cart.Empty("f")
	for _, e := range []cart.Event{cart.ItemAdded{Item: pen}, cart.ItemAdded{Item: ink}, cart.ItemAdded{Item: pen}, cart.ItemRemoved{ProductID: "ink"}, cart.CheckedOut{}} {
		c = cart.Apply(c, e)
	}
	if !c.CheckedOut || len(c.Items) != 1 || c.Items[0].Quantity != 4 {
		t.Fatalf("the fold answered %+v", c)
	}
}

// docs:start endpoint-test

func TestTheRoutesCallTheCart(t *testing.T) {
	ctx := context.Background()
	kit := testkit.Endpoint(cart.API, cart.Entity)
	defer kit.Close()
	if r := kit.Post(ctx, "/carts/e1/items", pen); r.Status != 204 {
		t.Fatalf("POST items answered %d %s %v", r.Status, r.Text(), r.Failure)
	}
	if r := kit.Get(ctx, "/carts/e1/total"); r.Text() != "2" {
		t.Fatalf("GET total answered %d %q", r.Status, r.Text())
	}
	if r := kit.Delete(ctx, "/carts/e1/items/nothing"); r.Status != 404 {
		t.Fatalf("removing what is not there answered %d", r.Status)
	}
	if r := kit.Post(ctx, "/carts/e1/checkout", ankka.Unit{}); r.Status != 200 {
		t.Fatalf("checkout answered %d %s", r.Status, r.Text())
	}
	if r := kit.Post(ctx, "/carts/e1/items", ink); r.Status != 409 {
		t.Fatalf("adding to a checked out cart answered %d", r.Status)
	}
}

// docs:end endpoint-test
