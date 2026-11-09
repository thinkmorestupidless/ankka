package samples

import (
	"context"
	"slices"
	"testing"

	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/cart"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/conformance"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

func TestTheSummariesCountLines(t *testing.T) {
	ctx := context.Background()
	if p := SummariesService().Problems(); len(p) > 0 {
		t.Fatal(p)
	}
	kit := testkit.View(CartSummaries)
	defer kit.Close()
	if _, err := kit.OnChange(ctx, "c1", cart.ItemAdded{Item: pen}); err != nil {
		t.Fatal(err)
	}
	if _, err := kit.OnChange(ctx, "c1", cart.ItemAdded{Item: ink}); err != nil {
		t.Fatal(err)
	}
	change, _ := kit.OnChange(ctx, "c1", cart.CheckedOut{})
	if change.Updated == nil || *change.Updated != (CartSummary{CartID: "c1", Items: 2, CheckedOut: true}) {
		t.Fatalf("the summary is %+v", change.Updated)
	}
	if change, _ := kit.OnChange(ctx, "c1", cart.Discarded{}); !change.Ignored {
		t.Fatalf("a discard was %+v", change)
	}
}

// docs:start view-test

func TestTheRowsFollowTheCart(t *testing.T) {
	ctx := context.Background()
	kit := testkit.View(cart.Rows)
	defer kit.Close()
	change, err := kit.OnChange(ctx, "c1", cart.ItemAdded{Item: cart.LineItem{ProductID: "p1", Name: "Pen", Quantity: 2}})
	if err != nil || change.Updated == nil {
		t.Fatalf("an item added wrote %+v, %v", change, err)
	}
	row, _ := kit.Row("c1")
	if row.CartID != "c1" || row.Quantities["p1"] != 2 || row.CheckedOut {
		t.Fatalf("the row is %+v", row)
	}
	if change, _ := kit.OnDelete(ctx, "c1"); !change.Deleted {
		t.Fatalf("a deletion was %+v", change)
	}
}

// docs:end view-test

// docs:start keyed-view-test

func TestTheRightsChangeNotesEveryRowHoldingIt(t *testing.T) {
	ctx := context.Background()
	kit := testkit.KeyedView(conformance.JoinedRows)
	defer kit.Close()
	for _, text := range []string{"r1|b", "r2|b", "r3|c"} {
		if _, _, err := kit.Change(ctx, conformance.JoinedLeft.ID(), "a", conformance.Noted{Text: text}); err != nil {
			t.Fatal(err)
		}
	}
	// The query is the database's to run; the test says what it answers.
	kit.Answering("of-right", func(values map[string]string) []conformance.JoinedRow {
		var rows []conformance.JoinedRow
		for _, r := range kit.Rows() {
			if r.Holding == values["holding"] {
				rows = append(rows, r)
			}
		}
		return rows
	})
	written, _, err := kit.Change(ctx, conformance.JoinedRight.ID(), "b", conformance.Noted{Text: "anything"})
	if err != nil || len(written) != 2 {
		t.Fatalf("the right wrote %v, %v", written, err)
	}
	for key, want := range map[string][]string{"r1": {"left", "right"}, "r2": {"left", "right"}, "r3": {"left"}} {
		if row, _ := kit.Row(key); !slices.Equal(row.Notes, want) {
			t.Fatalf("%s is noted %v", key, row.Notes)
		}
	}
}

// docs:end keyed-view-test
