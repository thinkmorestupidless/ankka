// The whole service through the real sidecar: Postgres and the sidecar image in Docker, this process
// serving the components to it, and an HTTP client for the routes. Seconds, not milliseconds.
//
// It runs under ANKKA_SLOW=1 and needs Docker. The sidecar is the one published with the SDK's
// version, pulled on first use; ANKKA_SIDECAR_IMAGE names another.
package main

import (
	"testing"
	"time"

	"github.com/thinkmorestupidless/ankka-go/testkit"
)

func TestAnItemSurvivesARestartAndIsListed(t *testing.T) {
	kit := testkit.StartT(t, service())
	ctx := t.Context()
	if r, err := kit.HTTP().Post(ctx, "/items/i1", AddItem{Name: "Widget", Count: 2}); err != nil || r.Status >= 300 {
		t.Fatalf("adding answered %v %v", r, err)
	}
	// A new sidecar, the same database: the state is durable, not cached.
	if err := kit.Restart(ctx); err != nil {
		t.Fatal(err)
	}
	got, err := testkit.GetJSON[Item](ctx, kit.HTTP(), "/items/i1")
	if err != nil || got != (Item{ID: "i1", Name: "Widget", Count: 2}) {
		t.Fatalf("the item is %+v (%v)", got, err)
	}
	// A view is eventually consistent: retry until the row is there, then check it.
	var rows []ItemRow
	for deadline := time.Now().Add(20 * time.Second); time.Now().Before(deadline); time.Sleep(200 * time.Millisecond) {
		if rows, err = testkit.GetJSON[[]ItemRow](ctx, kit.HTTP(), "/items/"); err == nil && len(rows) == 1 {
			break
		}
	}
	if len(rows) != 1 || rows[0] != (ItemRow{ID: "i1", Name: "Widget", Count: 2}) {
		t.Fatalf("the listing is %+v (%v)", rows, err)
	}
}
