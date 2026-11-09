package samples

import (
	"testing"
	"time"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

// The secret store is the sidecar's, so a component that keeps or reads a secret is proved through
// the integration kit, which puts a generated key on the runtime it starts.
func TestACredentialIsKeptAndReadByTheStep(t *testing.T) {
	if p := ChargeService().Problems(); len(p) > 0 {
		t.Fatal(p)
	}
	kit := testkit.StartT(t, ChargeService())
	ctx := t.Context()
	h := kit.HTTP()

	r, err := h.Post(ctx, "/credentials/", KeepSecret{Name: "provider/stripe", Value: "sk-test"})
	if err != nil || r.Status != 204 {
		t.Fatalf("keeping answered %v, %v", r, err)
	}
	r, err = h.Get(ctx, "/credentials/?name=provider/stripe")
	if err != nil || r.Text() != "sk-test" {
		t.Fatalf("reading answered %v, %v", r, err)
	}
	r, err = h.Get(ctx, "/credentials/?name=provider/none")
	if err != nil || r.Status != 404 {
		t.Fatalf("reading nothing answered %v, %v", r, err)
	}

	end, err := ankka.SendAndAwait[ChargeState](kit.Context(ctx), "charge", "ch-1", "start",
		Charge{Provider: "stripe", Amount: 12}, 30*time.Second)
	if err != nil || end != (ChargeState{Provider: "stripe", CredentialLength: 7, Status: "charged"}) {
		t.Fatalf("the charge ended %+v, %v\n%s", end, err, kit.SidecarLogs(ctx))
	}

	r, err = h.Delete(ctx, "/credentials/?name=provider/stripe")
	if err != nil || r.Status != 204 {
		t.Fatalf("deleting answered %v, %v", r, err)
	}
	if _, err := ankka.SendAndAwait[ChargeState](kit.Context(ctx), "charge", "ch-2", "start",
		Charge{Provider: "stripe", Amount: 12}, 30*time.Second); err == nil {
		t.Fatal("a charge with no credential completed")
	}
}
