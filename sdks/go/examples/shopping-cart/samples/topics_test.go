package samples

import (
	"context"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

func TestTheStockLevelsFollowTheTopic(t *testing.T) {
	ctx := context.Background()
	if p := ankka.NewService(StockLevels).Problems(); len(p) > 0 {
		t.Fatal(p)
	}
	kit := testkit.View(StockLevels)
	defer kit.Close()
	if _, err := kit.OnChange(ctx, "p1", StockEvent{ProductID: "p1", Delta: 5}); err != nil {
		t.Fatal(err)
	}
	change, err := kit.OnChange(ctx, "p1", StockEvent{ProductID: "p1", Delta: -2})
	if err != nil || change.Updated == nil || *change.Updated != (StockRow{ProductID: "p1", Level: 3}) {
		t.Fatalf("the level is %+v, %v", change.Updated, err)
	}
	if row, ok := kit.Row("p1"); !ok || row.Level != 3 {
		t.Fatalf("the row is %+v", row)
	}
}
