package samples

import (
	"context"
	"encoding/json"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/cart"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

func TestTheClientAddressesRefusesAndFansOut(t *testing.T) {
	ctx := context.Background()
	kit := testkit.Endpoint(ClientEndpoint, cart.Entity)
	defer kit.Close()

	r := kit.Post(ctx, "/client/carts/c1", pen)
	var c cart.Cart
	if err := json.Unmarshal(r.Body, &c); err != nil || r.Status != 200 || len(c.Items) != 1 || c.Items[0] != pen {
		t.Fatalf("adding answered %d %s (%v)", r.Status, r.Text(), err)
	}
	kit.Post(ctx, "/client/carts/c2", ink)

	if r := kit.Post(ctx, "/client/carts/c1/try", cart.LineItem{ProductID: "p", Name: "P", Quantity: 0}); r.Text() != "refused: quantity must be greater than zero, was 0" {
		t.Fatalf("a refusal read as %d %q", r.Status, r.Text())
	}
	if r := kit.Post(ctx, "/client/carts/c1/try", ink); r.Text() != "added" {
		t.Fatalf("an accepted item read as %d %q", r.Status, r.Text())
	}

	r = kit.Post(ctx, "/client/totals", []string{"c1", "c2", "c3"})
	var totals CartTotals
	if err := json.Unmarshal(r.Body, &totals); err != nil || totals["c1"] != 3 || totals["c2"] != 1 || totals["c3"] != 0 {
		t.Fatalf("the totals are %d %s (%v)", r.Status, r.Text(), err)
	}
	if got := describeRefusal(&ankka.RefusalError{Code: ankka.Conflict, Message: "taken"}); got != "CONFLICT: taken" {
		t.Fatalf("a refusal is described as %q", got)
	}
}
