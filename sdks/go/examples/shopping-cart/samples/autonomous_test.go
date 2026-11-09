package samples

import (
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/cart"
)

// The Go kit scripts no autonomous agent's model: the agents are proved against a sidecar in the
// slow suite, and declared here.
func TestTheAutonomousAgentsAreDeclared(t *testing.T) {
	svc := ankka.NewService(cart.Entity, CartAnswerer, Operator, QuestionsEndpoint)
	if p := svc.Problems(); len(p) > 0 {
		t.Fatal(p)
	}
	var kinds []string
	for _, c := range svc.Spec().GetComponents() {
		kinds = append(kinds, c.GetId()+":"+c.GetKind().String())
	}
	want := map[string]bool{"cart-answerer:AUTONOMOUS_AGENT": true, "operator:AUTONOMOUS_AGENT": true}
	for _, k := range kinds {
		delete(want, k)
	}
	if len(want) > 0 {
		t.Fatalf("declared %v", kinds)
	}
}
