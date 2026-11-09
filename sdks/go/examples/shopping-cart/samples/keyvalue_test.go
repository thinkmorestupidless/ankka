package samples

import (
	"context"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

// docs:start key-value-test

func TestRecordingACheckoutReplacesTheValue(t *testing.T) {
	ctx := context.Background()
	kit := testkit.KeyValue(CheckoutLog, testkit.EntityID("c1"))
	defer kit.Close()
	r := kit.Command(ctx, "record", int64(1_700_000_000_000))
	if r.Refusal() != nil || r.Reply() != (ankka.Done{}) {
		t.Fatalf("record answered %v, %v", r.Reply(), r.Refusal())
	}
	if s := kit.State(); s.At != 1_700_000_000_000 || !s.Notified || s.CartID != "c1" {
		t.Fatalf("the record is %+v", s)
	}
	want := CheckoutRecord{CartID: "c1", At: 1_700_000_000_000, Notified: true}
	if q := kit.Query(ctx, "get", ankka.Unit{}); q.Reply() != want {
		t.Fatalf("get answered %+v", q.Reply())
	}
}

// docs:end key-value-test

func TestTheLogIsReadThroughTheClient(t *testing.T) {
	ctx := context.Background()
	svc := CheckoutLogService()
	if p := svc.Problems(); len(p) > 0 {
		t.Fatal(p)
	}
	kit := testkit.Endpoint(CheckoutLogEndpoint, CheckoutLog)
	defer kit.Close()
	if r := kit.Get(ctx, "/checkout-log/c1"); r.Status != 200 || r.Text() != `{"cartId":"c1","at":0,"notified":false}` {
		t.Fatalf("reading answered %d %s", r.Status, r.Text())
	}
}
