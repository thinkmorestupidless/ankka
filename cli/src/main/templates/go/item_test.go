// The entity and the view with no sidecar, no database and no network: milliseconds.
//
// Inputs, events, state and replies still round-trip through the codecs, so a shape the codec cannot
// express fails here rather than on first deployment.
package main

import (
	"context"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

func TestTheServiceHasNoProblems(t *testing.T) {
	if p := service().Problems(); len(p) > 0 {
		t.Fatalf("problems: %v", p)
	}
}

func TestAddingPersistsAnEventAndUpdatesTheState(t *testing.T) {
	ctx := context.Background()
	kit := testkit.EventSourced(ItemEntity, testkit.EntityID("i1"))
	added := kit.Command(ctx, "add-item", AddItem{Name: "Widget", Count: 2})
	if added.Refusal() != nil || len(added.Events()) != 1 || added.Events()[0] != (ItemAdded{Name: "Widget", Count: 2}) {
		t.Fatalf("add-item answered %+v", added)
	}
	kit.Command(ctx, "add-item", AddItem{Name: "Widget", Count: 3})
	if got := kit.Query(ctx, "get-item", ankka.Unit{}).Reply(); got != (Item{ID: "i1", Name: "Widget", Count: 5}) {
		t.Fatalf("get-item answered %+v", got)
	}
}

func TestRemovingMoreThanThereIsIsRefusedAndPersistsNothing(t *testing.T) {
	ctx := context.Background()
	kit := testkit.EventSourced(ItemEntity, testkit.EntityID("i1"))
	kit.Command(ctx, "add-item", AddItem{Name: "Widget", Count: 2})
	refused := kit.Command(ctx, "remove-item", RemoveItem{Count: 3})
	if refused.Refusal() == nil || refused.Refusal().Code != ankka.Conflict || len(refused.Events()) != 0 {
		t.Fatalf("removing too many answered %+v", refused)
	}
	removed := kit.Command(ctx, "remove-item", RemoveItem{Count: 2})
	if len(removed.Events()) != 1 || removed.Events()[0] != (ItemRemoved{Count: 2}) {
		t.Fatalf("remove-item answered %+v", removed)
	}
}

func TestACountBelowOneIsABadRequest(t *testing.T) {
	kit := testkit.EventSourced(ItemEntity, testkit.EntityID("i1"))
	refused := kit.Command(context.Background(), "add-item", AddItem{Name: "Widget", Count: 0})
	if refused.Refusal() == nil || refused.Refusal().Code != ankka.BadRequest {
		t.Fatalf("a count of zero answered %+v", refused)
	}
}

func TestTheViewKeepsOneRowPerItem(t *testing.T) {
	ctx := context.Background()
	kit := testkit.View(ItemRows)
	if _, err := kit.OnChange(ctx, "i1", ItemAdded{Name: "Widget", Count: 2}); err != nil {
		t.Fatal(err)
	}
	if _, err := kit.OnChange(ctx, "i1", ItemRemoved{Count: 1}); err != nil {
		t.Fatal(err)
	}
	if row, ok := kit.Row("i1"); !ok || row != (ItemRow{ID: "i1", Name: "Widget", Count: 1}) {
		t.Fatalf("the row is %+v (%v)", row, ok)
	}
}
