package samples

import (
	"net/http"
	"strings"
	"testing"
	"time"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

// Waiting for a workflow's end is the sidecar's to answer, so the waits are proved through it.
func TestAWaitIsAnsweredWhenTheWorkflowEnds(t *testing.T) {
	kit := testkit.StartT(t, AwaitingService())
	ctx := t.Context()
	h := kit.HTTP()

	if r, err := h.Post(ctx, "/quotes/q-1", "pen"); err != nil || r.Status != 204 {
		t.Fatalf("starting answered %v, %v", r, err)
	}
	quote, err := testkit.GetJSON[Quote](ctx, h, "/quotes/q-1/wait")
	if err != nil || quote != (Quote{Item: "pen", Price: 30, Status: "priced"}) {
		t.Fatalf("the wait answered %+v, %v\n%s", quote, err, kit.SidecarLogs(ctx))
	}
	// Ended already: answered at once, from what was recorded.
	if again, err := testkit.GetJSON[Quote](ctx, h, "/quotes/q-1/wait"); err != nil || again != quote {
		t.Fatalf("the second wait answered %+v, %v", again, err)
	}
	if r, err := h.Post(ctx, "/quotes/q-2", "unobtainium"); err != nil || r.Status != 204 {
		t.Fatalf("starting answered %v, %v", r, err)
	}
	if r, err := h.Get(ctx, "/quotes/q-2/wait"); err != nil || r.Status != 424 || !strings.Contains(r.Text(), "nothing to price") {
		t.Fatalf("a failed quote's wait answered %v, %v", r, err)
	}
	if r, err := h.Do(ctx, http.MethodGet, "/quotes/q-1/events", nil, nil); err != nil || !strings.Contains(r.Text(), "event:ended") {
		t.Fatalf("the events answered %v, %v", r, err)
	}

	cctx := kit.Context(ctx)
	if _, err := ankka.Invoke[ankka.Done](cctx, "onboarding", "o-1", "start", Applicant{Name: "ada"}); err != nil {
		t.Fatal(err)
	}
	onboarded, err := ankka.AwaitEnd[Onboarding](cctx, "onboarding", "o-1", 30*time.Second)
	if err != nil || onboarded.Outcome != "welcomed" {
		t.Fatalf("the onboarding ended %+v, %v\n%s", onboarded, err, kit.SidecarLogs(ctx))
	}
	if kyc, err := ankka.Invoke[Kyc](cctx, "kyc", "kyc-o-1", "state", ankka.Unit{}); err == nil {
		t.Fatalf("the check answered an undeclared query: %+v", kyc)
	}
}
