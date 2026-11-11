package testkit_test

// A unit kit's answers for a workflow's standing and how a copy stands (protocol 1.24): the Go rows of
// "a component test reads a stubbed standing" (features/polyglot/standing.feature) and "a component test
// reads a stubbed copy" (features/replication/status.feature).

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

type watch struct {
	Plan   string `json:"plan"`
	Copy   string `json:"copy"`
	Reason string `json:"reason"`
}

func str(p *string) string {
	if p == nil {
		return "-"
	}
	return *p
}

// A step reads the standing of the plan it watches and how the vehicle's copy stands, and keeps both.
var watcher = ankka.Workflow("watcher", func(string) watch { return watch{} },
	ankka.WorkflowCommand("start", func(_ context.Context, w watch, _ ankka.Unit) (ankka.WorkflowEffect[watch, string], error) {
		return ankka.WorkflowUpdate[watch, string](w).ThenTransitionTo("look", nil).ThenReplyValue("started"), nil
	}),
	ankka.Step("look", func(ctx context.Context, w watch, _ ankka.Unit) (ankka.StepEffect[watch], error) {
		plan, err := ankka.Lifecycle(ctx, "plan", "p1")
		if err != nil {
			return ankka.StepEffect[watch]{}, err
		}
		w.Plan = fmt.Sprintf("%s %s %s %v", plan.Status, str(plan.PendingStep), str(plan.Failure), plan.Retries)
		copy, err := ankka.Replication(ctx, "vehicle", "v1")
		if err != nil {
			return ankka.StepEffect[watch]{}, err
		}
		w.Copy = fmt.Sprintf("%s %s %s %s %d %v", str(copy.Home), strings.Join(copy.ReplicationSet, ","), str(copy.PendingHandover), str(copy.Conflict), copy.Position, copy.InConflict())
		return ankka.StepUpdate(w).ThenEnd(), nil
	}),
)

func ptr(s string) *string { return &s }

func TestAUnitKitAnswersAStubbedStandingAndAStubbedCopy(t *testing.T) {
	k := testkit.Workflow(watcher,
		testkit.AnswerLifecycle("plan", "p1", ankka.WorkflowLifecycle{Status: ankka.Failed, PendingStep: ptr("deposit"), Retries: map[string]int{"deposit": 2}, Failure: ptr("the bank was shut")}),
		testkit.AnswerReplication("vehicle", "v1", ankka.ReplicationStatus{Home: ptr("leipzig"), ReplicationSet: []string{"leipzig", "vienna"}, PendingHandover: ptr("vienna"), Conflict: ptr("graz"), Position: 4}))
	defer k.Close()
	ctx := context.Background()
	k.Command(ctx, "start", ankka.Unit{})
	if p, err := k.RunStep(ctx); err != nil || !p.Ended {
		t.Fatalf("the step stood %+v, %v", p, err)
	}
	if got := k.State().Plan; got != "Failed deposit the bank was shut map[deposit:2]" {
		t.Fatalf("the standing read was %q", got)
	}
	if got := k.State().Copy; got != "leipzig leipzig,vienna vienna graz 4 true" {
		t.Fatalf("the copy read was %q", got)
	}
}

func TestAUnitKitRefusesAReadItWasNotToldToAnswer(t *testing.T) {
	ctx := context.Background()
	noPlan := testkit.Workflow(watcher)
	defer noPlan.Close()
	noPlan.Command(ctx, "start", ankka.Unit{})
	_, err := noPlan.RunStep(ctx)
	if err == nil || !strings.Contains(err.Error(), `the standing of "p1" of "plan" was not stubbed`) || !strings.Contains(err.Error(), "AnswerLifecycle") {
		t.Fatalf("an unstubbed standing failed with %v", err)
	}
	noCopy := testkit.Workflow(watcher, testkit.AnswerLifecycle("plan", "p1", ankka.WorkflowLifecycle{Status: ankka.Running}))
	defer noCopy.Close()
	noCopy.Command(ctx, "start", ankka.Unit{})
	_, err = noCopy.RunStep(ctx)
	var refused *ankka.RefusalError
	if !errors.As(err, &refused) || !strings.Contains(err.Error(), `how "v1" of "vehicle" stands was not stubbed`) {
		t.Fatalf("an unstubbed copy failed with %v (%T)", err, err)
	}
}
