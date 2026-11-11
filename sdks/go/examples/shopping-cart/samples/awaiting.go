package samples

import (
	"context"
	"iter"
	"strings"
	"time"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// Quote is a quote being worked out.
type Quote struct {
	Item   string `json:"item"`
	Price  int    `json:"price"`
	Status string `json:"status"`
}

type quoteStep = ankka.StepEffect[Quote]

// QuoteWorkflow works out a quote: one step, which takes a moment.
var QuoteWorkflow = ankka.Workflow[Quote]("quote",
	func(string) Quote { return Quote{} },
	ankka.WorkflowCommand("start", func(_ context.Context, q Quote, item string) (ankka.WorkflowEffect[Quote, ankka.Done], error) {
		if q.Status != "" {
			return ankka.WorkflowEffect[Quote, ankka.Done]{}, ankka.Refuse(ankka.Conflict, "this quote has started")
		}
		return ankka.WorkflowUpdate[Quote, ankka.Done](Quote{Item: item, Status: "pricing"}).
			ThenTransitionTo("price", ankka.Unit{}).ThenReplyValue(ankka.Done{}), nil
	}),
	ankka.Step("price", func(_ context.Context, q Quote, _ ankka.Unit) (quoteStep, error) {
		time.Sleep(time.Second)
		if q.Item == "unobtainium" {
			return ankka.FailWorkflow[Quote](ankka.BadRequest, "nothing to price"), nil
		}
		q.Price, q.Status = len(q.Item)*10, "priced"
		return ankka.StepUpdate(q).ThenEnd(), nil
	}),
	ankka.StateCodec(ankka.CodecFor[Quote](ankka.Manifest("quote"))),
)

// Applicant is who is onboarded.
type Applicant struct {
	Name string `json:"name"`
}

// Kyc is a know-your-customer check's state.
type Kyc struct {
	Applicant Applicant `json:"applicant"`
	Decision  string    `json:"decision"`
}

type kycStep = ankka.StepEffect[Kyc]

// KycWorkflow decides on an applicant.
var KycWorkflow = ankka.Workflow[Kyc]("kyc",
	func(string) Kyc { return Kyc{} },
	ankka.WorkflowCommand("start", func(_ context.Context, _ Kyc, a Applicant) (ankka.WorkflowEffect[Kyc, ankka.Done], error) {
		return ankka.WorkflowUpdate[Kyc, ankka.Done](Kyc{Applicant: a}).
			ThenTransitionTo("decide", ankka.Unit{}).ThenReplyValue(ankka.Done{}), nil
	}),
	ankka.Step("decide", func(_ context.Context, k Kyc, _ ankka.Unit) (kycStep, error) {
		k.Decision = "approved"
		if strings.HasPrefix(k.Applicant.Name, "x") {
			k.Decision = "declined"
		}
		return ankka.StepUpdate(k).ThenEnd(), nil
	}),
	ankka.StateCodec(ankka.CodecFor[Kyc](ankka.Manifest("kyc"))),
)

// Onboarding is an onboarding's state.
type Onboarding struct {
	Applicant Applicant `json:"applicant"`
	Outcome   string    `json:"outcome"`
}

type onboardingStep = ankka.StepEffect[Onboarding]

// VerifyTimeout bounds the wait for the check; the step's own timeout must allow for it.
const VerifyTimeout = 20 * time.Second

func onboardingID(ctx context.Context) string {
	c, _ := ankka.CommandFrom(ctx)
	return c.EntityID
}

// docs:start step-awaits

// verifyStep starts the applicant's check and waits for it to end, as one call: the step is
// bounded by its own timeout, and fails as timed out if the check has not ended by then.
func verifyStep(ctx context.Context, o Onboarding, _ ankka.Unit) (onboardingStep, error) {
	kyc, err := ankka.SendAndAwait[Kyc](ctx, "kyc", "kyc-"+onboardingID(ctx), "start", o.Applicant, VerifyTimeout)
	if err != nil {
		return onboardingStep{}, err
	}
	if kyc.Decision == "approved" {
		return ankka.StepUpdate(o).ThenTransitionTo("welcome", ankka.Unit{}), nil
	}
	return ankka.StepUpdate(o).ThenTransitionTo("decline", ankka.Unit{}), nil
}

// docs:end step-awaits

// OnboardingWorkflow verifies an applicant through the check workflow, then welcomes or declines.
var OnboardingWorkflow = ankka.Workflow[Onboarding]("onboarding",
	func(string) Onboarding { return Onboarding{} },
	ankka.WorkflowCommand("start", func(_ context.Context, _ Onboarding, a Applicant) (ankka.WorkflowEffect[Onboarding, ankka.Done], error) {
		return ankka.WorkflowUpdate[Onboarding, ankka.Done](Onboarding{Applicant: a}).
			ThenTransitionTo("verify", ankka.Unit{}).ThenReplyValue(ankka.Done{}), nil
	}),
	ankka.Step("verify", verifyStep),
	ankka.Step("welcome", func(_ context.Context, o Onboarding, _ ankka.Unit) (onboardingStep, error) {
		o.Outcome = "welcomed"
		return ankka.StepUpdate(o).ThenEnd(), nil
	}),
	ankka.Step("decline", func(_ context.Context, o Onboarding, _ ankka.Unit) (onboardingStep, error) {
		o.Outcome = "declined"
		return ankka.StepUpdate(o).ThenEnd(), nil
	}),
	ankka.StateCodec(ankka.CodecFor[Onboarding](ankka.Manifest("onboarding"))),
	ankka.ForStep("verify", ankka.StepTimeout(VerifyTimeout+10*time.Second)),
)

// QuoteEndpoint starts quotes and waits for them.
var QuoteEndpoint = ankka.NewEndpoint("/quotes", ankka.AllowAll(),
	ankka.Post("/{id}", func(ctx context.Context, req *ankka.Request, item string) (ankka.Done, error) {
		return ankka.Invoke[ankka.Done](ctx, "quote", req.Param("id"), "start", item)
	}),
	// docs:start await-later
	// Answers with a quote started earlier, by anyone: at once if it has ended, when it ends if
	// not. A quote that failed is 424, naming the step and the reason.
	ankka.Get("/{id}/wait", func(ctx context.Context, req *ankka.Request) (Quote, error) {
		quote, err := ankka.AwaitEnd[Quote](ctx, "quote", req.Param("id"), 10*time.Second)
		if r, ok := ankka.AsRefusal(err); ok && r.Code == ankka.WorkflowFailed {
			return Quote{}, &ankka.HttpProblem{Status: 424, Message: r.Details["step"] + ": " + r.Details["reason"]}
		}
		return quote, err
	}),
	// docs:end await-later
	// docs:start lifecycle
	// The engine's view of a quote: running, paused, completed or failed, and why it failed. The
	// quote's own state says what it decided; this read runs no handler of it.
	ankka.Get("/{id}/lifecycle", func(ctx context.Context, req *ankka.Request) (string, error) {
		lifecycle, err := ankka.Lifecycle(ctx, "quote", req.Param("id"))
		if err != nil {
			return "", err
		}
		if lifecycle.Failure != nil {
			return string(lifecycle.Status) + ": " + *lifecycle.Failure, nil
		}
		return string(lifecycle.Status), nil
	}),
	// docs:end lifecycle
	// docs:start sse-await
	// A quote's end as server-sent events: the wait as one event, on one connection. A Go route
	// sends no heartbeat of its own while it waits, so a wait longer than a connection's idle
	// timeout is served as a plain wait made again by the caller.
	ankka.SSEEvents("/{id}/events", func(ctx context.Context, req *ankka.Request) iter.Seq2[ankka.SSEEvent, error] {
		return func(yield func(ankka.SSEEvent, error) bool) {
			quote, err := ankka.AwaitEnd[Quote](ctx, "quote", req.Param("id"), 10*time.Minute)
			switch r, refused := ankka.AsRefusal(err); {
			case err == nil:
				yield(ankka.SSEEvent{Name: "ended", Data: quote}, nil)
			case refused && r.Code == ankka.WorkflowFailed:
				yield(ankka.SSEEvent{Name: "failed", Text: r.Details["reason"]}, nil)
			case refused && r.Code == ankka.Timeout:
				yield(ankka.SSEEvent{Name: "timed-out"}, nil)
			default:
				yield(ankka.SSEEvent{}, err)
			}
		}
	}),
	// docs:end sse-await
)

// AwaitingService is the quotes, the onboarding and its check.
func AwaitingService() *ankka.Service {
	return ankka.NewService(QuoteWorkflow, QuoteEndpoint, KycWorkflow, OnboardingWorkflow)
}
