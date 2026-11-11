package samples

import (
	"context"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

func TestTheQuoteIsPricedByItsStep(t *testing.T) {
	ctx := context.Background()
	if p := AwaitingService().Problems(); len(p) > 0 {
		t.Fatal(p)
	}
	kit := testkit.Workflow(QuoteWorkflow)
	defer kit.Close()
	if r := kit.Command(ctx, "start", "pen"); r.Refusal() != nil || r.Transition().Step != "price" {
		t.Fatalf("start answered %v, %+v", r.Refusal(), r.Transition())
	}
	progress, err := kit.RunStep(ctx)
	if err != nil || !progress.Ended || kit.State().Price != 30 {
		t.Fatalf("pricing ended %+v, %v, %+v", progress, err, kit.State())
	}
	failing := testkit.Workflow(QuoteWorkflow)
	defer failing.Close()
	failing.Command(ctx, "start", "unobtainium")
	if progress, _ := failing.RunStep(ctx); progress.Failed == nil {
		t.Fatalf("an unpriceable quote ended %+v", progress)
	}
}

func TestTheCheckDecides(t *testing.T) {
	ctx := context.Background()
	kit := testkit.Workflow(KycWorkflow)
	defer kit.Close()
	kit.Command(ctx, "start", Applicant{Name: "xavier"})
	if progress, _ := kit.RunStep(ctx); !progress.Ended || kit.State().Decision != "declined" {
		t.Fatalf("the check ended %+v with %+v", progress, kit.State())
	}
}

// docs:start lifecycle-test
func TestTheLifecycleRouteSaysWhyAQuoteFailed(t *testing.T) {
	ctx := context.Background()
	failure := "nothing to price"
	kit := testkit.EndpointWith(QuoteEndpoint,
		testkit.AnswerLifecycle("quote", "q-1", ankka.WorkflowLifecycle{Status: ankka.Failed, Failure: &failure}),
		testkit.AnswerLifecycle("quote", "q-2", ankka.WorkflowLifecycle{Status: ankka.Running}))
	defer kit.Close()
	if r := kit.Get(ctx, "/quotes/q-1/lifecycle"); r.Text() != "Failed: nothing to price" {
		t.Fatalf("a failed quote answered %d %q", r.Status, r.Text())
	}
	if r := kit.Get(ctx, "/quotes/q-2/lifecycle"); r.Text() != "Running" {
		t.Fatalf("a running quote answered %d %q", r.Status, r.Text())
	}
}

// docs:end lifecycle-test
