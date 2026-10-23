package main

import (
	"context"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

// docs:start unit-test
var pen = LineItem{ProductID: "p1", Name: "Pen", Quantity: 2}

func TestAddingAnItemPersistsOneEvent(t *testing.T) {
	kit := testkit.EventSourced(ShoppingCartEntity, testkit.EntityID("c1"))
	result := kit.Command(context.Background(), "add-item", pen)
	if len(result.Events()) != 1 || result.Events()[0] != (ItemAdded{Item: pen}) {
		t.Fatalf("add-item persisted %v", result.Events())
	}
	cart := kit.Query(context.Background(), "get-cart", ankka.Unit{}).Reply().(ShoppingCart)
	if len(cart.Items) != 1 || cart.Items[0] != pen {
		t.Fatalf("the cart holds %v", cart.Items)
	}
}

func TestARefusedCommandPersistsNothing(t *testing.T) {
	kit := testkit.EventSourced(ShoppingCartEntity, testkit.EntityID("c1"))
	result := kit.Command(context.Background(), "add-item", LineItem{ProductID: "p1", Name: "Pen"})
	if result.Refusal() == nil || result.Refusal().Code != ankka.BadRequest || len(result.Events()) != 0 {
		t.Fatalf("a quantity of zero answered %+v", result)
	}
}

// docs:end unit-test

// docs:start integration-test
func TestACartSurvivesARestart(t *testing.T) {
	kit := testkit.StartT(t, service()) // skipped unless ANKKA_SLOW=1
	ctx := t.Context()
	if _, err := kit.HTTP().Post(ctx, "/carts/c1/items", pen); err != nil {
		t.Fatal(err)
	}
	if err := kit.Restart(ctx); err != nil { // a new sidecar, the same database
		t.Fatal(err)
	}
	cart, err := testkit.GetJSON[ShoppingCart](ctx, kit.HTTP(), "/carts/c1")
	if err != nil || len(cart.Items) != 1 || cart.Items[0].Name != "Pen" {
		t.Fatalf("after a restart the cart is %+v (%v)", cart, err)
	}
}

// docs:end integration-test
