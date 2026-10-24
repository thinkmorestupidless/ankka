package testkit

import (
	"context"
	"strings"
	"testing"
	"time"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

var orders = ankka.NewEndpoint("/orders", ankka.Authenticate(ankka.Issuer("customers")),
	ankka.Get("/mine", func(ctx context.Context, req *ankka.Request) (string, error) { return "yours", nil }),
)

// features/service-identity/languages.feature, "a service with an authenticated route and no
// issuer listed does not start", the Go row: the route names its issuer, the platform's program is
// given no ANKKA_AUTH_ISSUERS, and it refuses the service, naming the route and the variable.
func TestAnAuthenticatedRouteWithNoIssuerListedDoesNotStart(t *testing.T) {
	Slow(t)
	run := runID()
	_, err := Start(t.Context(), ankka.NewService(orders), withRun(run), ReadyWithin(40*time.Second))
	if err == nil {
		t.Fatal("a service with an authenticated route and no issuer listed started")
	}
	for _, want := range []string{"/orders", "ANKKA_AUTH_ISSUERS"} {
		if !strings.Contains(err.Error(), want) {
			t.Fatalf("the refusal does not name %s:\n%v", want, err)
		}
	}
	if left := runContainers(t, t.Context(), run); len(left) != 0 {
		t.Fatalf("the refused start left containers behind: %v", left)
	}
}
