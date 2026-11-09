package samples

import (
	"testing"
	"time"

	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/cart"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

// docs:start view-integration

// A view's rows are written by the sidecar's projection, so reading one is proved through the
// integration kit: the whole service, a real sidecar and a throwaway database.
func TestTheSummariesAreReadThroughTheSidecar(t *testing.T) {
	kit := testkit.StartT(t, SummariesService())
	ctx := t.Context()
	h := kit.HTTP()

	for _, item := range []cart.LineItem{pen, ink} {
		if r, err := h.Post(ctx, "/carts/v1/items", item); err != nil || r.Status != 204 {
			t.Fatalf("adding answered %v, %v", r, err)
		}
	}
	if r, err := h.Post(ctx, "/carts/v2/items", pen); err != nil || r.Status != 204 {
		t.Fatalf("adding answered %v, %v", r, err)
	}
	if r, err := h.Post(ctx, "/carts/v2/checkout", nil); err != nil || r.Status != 200 {
		t.Fatalf("checking out answered %v, %v", r, err)
	}

	// A projection runs behind the write: read until the row holds what was written, never once.
	deadline := time.Now().Add(20 * time.Second)
	for {
		row, err := testkit.GetJSON[CartSummary](ctx, h, "/summaries/carts/v1")
		if err == nil && row.Items == 2 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("v1 reads %+v, %v\n%s", row, err, kit.SidecarLogs(ctx))
		}
		time.Sleep(200 * time.Millisecond)
	}
	open, err := testkit.GetJSON[[]CartSummary](ctx, h, "/summaries/open")
	if err != nil || len(open) != 1 || open[0].CartID != "v1" {
		t.Fatalf("the open carts are %+v, %v", open, err)
	}
	byID, err := testkit.GetJSON[[]CartSummary](ctx, h, "/summaries/by-cart-id/v2")
	if err != nil || len(byID) != 1 || !byID[0].CheckedOut {
		t.Fatalf("v2 by id is %+v, %v", byID, err)
	}
	all, err := testkit.GetJSON[[]CartSummary](ctx, h, "/summaries/carts")
	if err != nil || len(all) != 2 {
		t.Fatalf("every row is %+v, %v", all, err)
	}
}

// docs:end view-integration
