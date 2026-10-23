package cart_test

import (
	"testing"

	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/cart"
)

func TestTheCartHasNoProblems(t *testing.T) {
	if p := cart.Service().Problems(); len(p) > 0 {
		t.Fatalf("problems: %v", p)
	}
}
