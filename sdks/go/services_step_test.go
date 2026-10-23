package ankka_test

import (
	"context"
	"errors"
	"fmt"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

// docs:start step-calls-service
type payout struct {
	Amount    int    `json:"amount"`
	Reference string `json:"reference"`
}

// A step asks the PSP gateway service to start a payout, and goes on with the answer.
var payoutFlow = ankka.Workflow("payout", func(string) payout { return payout{} },
	ankka.WorkflowCommand("start", func(_ context.Context, p payout, amount int) (ankka.WorkflowEffect[payout, string], error) {
		p.Amount = amount
		return ankka.WorkflowUpdate[payout, string](p).ThenTransitionTo("initiate-payout", nil).ThenReplyValue("started"), nil
	}),
	ankka.Step("initiate-payout", func(ctx context.Context, p payout, _ ankka.Unit) (ankka.StepEffect[payout], error) {
		res, err := ankka.CallService(ctx, "psp-gateway", ankka.ServiceRequest{
			Method: "GET", Path: fmt.Sprintf("/payouts?amount=%d", p.Amount)})
		if err != nil {
			return ankka.StepEffect[payout]{}, err // the engine retries a step that failed
		}
		p.Reference = string(res.Body)
		return ankka.StepUpdate(p).ThenEnd(), nil
	}),
)

func TestAWorkflowsStepCallsAnotherServiceAndGoesOnWithTheAnswer(t *testing.T) {
	var asked []string
	k := testkit.Workflow(payoutFlow, testkit.AnswerService("psp-gateway",
		func(r ankka.ServiceRequest) (*ankka.ServiceResponse, error) {
			asked = append(asked, r.Path)
			return &ankka.ServiceResponse{Status: 200, ContentType: "text/plain", Body: []byte("started " + r.Path)}, nil
		}))
	defer k.Close()
	ctx := context.Background()
	k.Command(ctx, "start", 25)
	if p, err := k.RunStep(ctx); err != nil || !p.Ended || k.State().Reference != "started /payouts?amount=25" {
		t.Fatalf("the workflow stood %+v, %v, at %+v", p, err, k.State())
	}
	if len(asked) != 1 || asked[0] != "/payouts?amount=25" {
		t.Fatalf("the gateway was asked %v", asked)
	}
}

// docs:end step-calls-service

func TestAUnitKitRefusesAServiceItWasNotToldToAnswer(t *testing.T) {
	k := testkit.Workflow(payoutFlow)
	defer k.Close()
	ctx := context.Background()
	k.Command(ctx, "start", 25)
	_, err := k.RunStep(ctx)
	var se *ankka.ServiceError
	if !errors.As(err, &se) || se.Reason != ankka.ServiceUnresolvable || se.Service != "psp-gateway" {
		t.Fatalf("a call to a service the kit does not answer failed with %v", err)
	}
	gone := testkit.Workflow(payoutFlow, testkit.AnswerService("psp-gateway",
		func(ankka.ServiceRequest) (*ankka.ServiceResponse, error) {
			return nil, &ankka.ServiceError{Reason: ankka.ServiceUnanswered, Detail: "connection refused"}
		}))
	defer gone.Close()
	gone.Command(ctx, "start", 25)
	if _, err := gone.RunStep(ctx); !errors.As(err, &se) || se.Reason != ankka.ServiceUnanswered || se.Detail != "connection refused" {
		t.Fatalf("an unanswered call failed with %v", err)
	}
}
