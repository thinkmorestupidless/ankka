package ankka_test

import (
	"context"
	"slices"
	"sync"
	"testing"
	"time"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

// orderSeen is what kOrderRecorder was given for the cart "ord", in the order it was given it.
var orderSeen struct {
	sync.Mutex
	products  []string
	sequences []int64
}

var kOrderRecorder = ankka.Consumer("k-order-recorder", ankka.Changes(kEntity), func(ctx context.Context, e kEvent) (ankka.ConsumerEffect, error) {
	c, _ := ankka.ChangeFrom(ctx)
	if a, ok := e.(kAdded); ok && c.Subject == "ord" {
		orderSeen.Lock()
		orderSeen.products = append(orderSeen.products, a.Item.ProductID)
		orderSeen.sequences = append(orderSeen.sequences, c.Sequence)
		orderSeen.Unlock()
	}
	return ankka.ConsumerDone(), nil
})

func seenSoFar() ([]string, []int64) {
	orderSeen.Lock()
	defer orderSeen.Unlock()
	return slices.Clone(orderSeen.products), slices.Clone(orderSeen.sequences)
}

// waitSeen waits until the consumer has been given n events of "ord".
func waitSeen(t *testing.T, n int) {
	t.Helper()
	deadline := time.Now().Add(30 * time.Second)
	for time.Now().Before(deadline) {
		if p, _ := seenSoFar(); len(p) >= n {
			return
		}
		time.Sleep(200 * time.Millisecond)
	}
	p, _ := seenSoFar()
	t.Fatalf("the consumer was given %v, waiting for %d events", p, n)
}

// features/polyglot/components.feature, "a consumer is given each event at least once and in the
// order its entity recorded them", the Go row: three events in order, a restart of the service,
// and nothing it had handled given again.
func TestAConsumerIsGivenEachEventInOrderAndNothingAgainAfterARestart(t *testing.T) {
	kit := testkit.StartT(t, ankka.NewService(kEntity, kOrderRecorder))
	ctx := kit.Context(t.Context())
	for _, p := range []string{"o1", "o2", "o3"} {
		if _, err := ankka.Invoke[int](ctx, "k-cart", "ord", "add", kItem{p, 1}); err != nil {
			t.Fatal(err)
		}
	}
	waitSeen(t, 3)
	if p, s := seenSoFar(); !slices.Equal(p, []string{"o1", "o2", "o3"}) || !slices.Equal(s, []int64{1, 2, 3}) {
		t.Fatalf("the consumer was given %v at sequences %v", p, s)
	}
	// Long enough for the projection to store its offset after the third event.
	time.Sleep(3 * time.Second)
	if err := kit.Restart(t.Context()); err != nil {
		t.Fatal(err)
	}
	ctx = kit.Context(t.Context())
	if _, err := ankka.Invoke[int](ctx, "k-cart", "ord", "add", kItem{"o4", 1}); err != nil {
		t.Fatal(err)
	}
	// The fourth event is given after the restart; an event given again would come before it.
	waitSeen(t, 4)
	time.Sleep(2 * time.Second)
	if p, s := seenSoFar(); !slices.Equal(p, []string{"o1", "o2", "o3", "o4"}) || !slices.Equal(s, []int64{1, 2, 3, 4}) {
		t.Fatalf("after the restart the consumer had been given %v at sequences %v", p, s)
	}
}
