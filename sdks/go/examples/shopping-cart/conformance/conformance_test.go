package conformance

import (
	"slices"
	"testing"
)

// The ids every reference declares, by exact comparison (discovery.lists-every-component).
var componentIDs = []string{
	"shopping-cart", "conformance", "profile", "checkout", "checkout-rows", "checkout-ends", "cart-rows",
	"checkout-recorder", "checkout-fanout", "topic-rows", "topic-relay", "contract-relay", "partner-relay",
	"tree-node", "tree-rows", "replicated-vehicle", "vehicle-feed", "joined-left", "joined-right", "joined-rows",
	"cart-graph", "profile-graph", "reminder", "assistant", "answerer", "approver",
}

func TestTheReferenceHasNoProblems(t *testing.T) {
	if p := Service().Problems(); len(p) > 0 {
		t.Fatalf("the reference has problems: %v", p)
	}
}

func TestTheReferenceDeclaresEveryComponentTheSuiteExpects(t *testing.T) {
	var got []string
	for _, c := range Service().Spec().GetComponents() {
		got = append(got, c.GetId())
	}
	want := slices.Clone(componentIDs)
	slices.Sort(got)
	slices.Sort(want)
	if !slices.Equal(got, want) {
		t.Fatalf("declared %v, the suite expects %v", got, want)
	}
}

func TestTheReferenceDeclaresTheRoutesTheSuiteNames(t *testing.T) {
	routes := map[string]bool{}
	for _, e := range Service().Spec().GetEndpoints() {
		for _, r := range e.GetRoutes() {
			routes[r.GetMethod()+" "+e.GetPrefix()+r.GetTemplate()] = true
		}
	}
	for _, r := range []string{
		"POST /carts/{cartId}/items", "GET /carts/awkward", "GET /conformance/echo", "GET /private/",
		"GET /callers/whoami", "GET /callers/granted", "POST /autonomous/tasks/{taskType}", "GET /autonomous/tasks/{id}",
	} {
		if !routes[r] {
			t.Errorf("%s is not declared", r)
		}
	}
}
