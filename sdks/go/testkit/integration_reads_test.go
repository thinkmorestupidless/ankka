package testkit_test

// The integration kit makes both reads against the running service (protocol 1.24): the Go rows of "the
// test kit reads a workflow's standing from the running service" (features/polyglot/standing.feature) and
// "the test kit reads how a copy stands from the running service" (features/replication/status.feature).
// The sidecar runs as the installation "kit" beside one, "elsewhere", it never reaches, so a replicated
// entity has a home. Needs Docker and the sidecar image from this branch (ANKKA_SIDECAR_IMAGE).

import (
	"context"
	"errors"
	"slices"
	"testing"
	"time"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

type order struct {
	Mode string `json:"mode"`
	Done bool   `json:"done"`
}

// A workflow whose one step ends it, or fails for "doomed": retried once, then the workflow fails.
var orders = ankka.Workflow("order", func(string) order { return order{} },
	ankka.WorkflowCommand("start", func(_ context.Context, o order, mode string) (ankka.WorkflowEffect[order, string], error) {
		o.Mode = mode
		return ankka.WorkflowUpdate[order, string](o).ThenTransitionTo("charge", nil).ThenReplyValue("started"), nil
	}),
	ankka.Step("charge", func(_ context.Context, o order, _ ankka.Unit) (ankka.StepEffect[order], error) {
		if o.Mode == "doomed" {
			return ankka.StepEffect[order]{}, errors.New("the card was refused")
		}
		o.Done = true
		return ankka.StepUpdate(o).ThenEnd(), nil
	}),
	ankka.ForStep("charge", ankka.StepRecovery(ankka.Recovery{MaxRetries: 1})),
)

type trip struct {
	Stops []string `json:"stops"`
}

type tripEvent interface{ tripEvent() }

type stopAdded struct {
	Stop string `json:"stop"`
}

func (stopAdded) tripEvent() {}

var tripEvents = ankka.Cases[tripEvent](ankka.Case[tripEvent, stopAdded]("StopAdded"))

func tripEntity(id string, parts ...ankka.Part) *ankka.EventSourcedEntity[trip, tripEvent] {
	all := append([]ankka.Part{
		ankka.Command("add-stop", func(_ context.Context, _ trip, stop string) (ankka.Effect[trip, tripEvent, string], error) {
			return ankka.Persist[trip, tripEvent, string](stopAdded{Stop: stop}).ThenReplyValue(stop), nil
		}),
	}, parts...)
	return ankka.EventSourced[trip, tripEvent](id, tripEvents, func(string) trip { return trip{} },
		func(t trip, e tripEvent) trip {
			if s, ok := e.(stopAdded); ok {
				t.Stops = append(t.Stops, s.Stop)
			}
			return t
		}, all...)
}

func TestTheIntegrationKitReadsAStandingAndACopyFromTheRunningService(t *testing.T) {
	service := ankka.NewService(orders, tripEntity("trip", ankka.Replicated()), tripEntity("log"))
	k := testkit.StartT(t, service, testkit.Env(map[string]string{
		"ANKKA_INSTALLATION":                   "kit",
		"ANKKA_INSTALLATION_ELSEWHERE_DB_HOST": "elsewhere.invalid",
	}))
	ctx := k.Context(context.Background())

	// A workflow that ended, one that failed, and one never started.
	if _, err := ankka.SendAndAwait[order](ctx, "order", "o1", "start", "ok", 20*time.Second); err != nil {
		t.Fatalf("the order did not end: %v", err)
	}
	if l, err := ankka.Lifecycle(ctx, "order", "o1"); err != nil || l.Status != ankka.Completed || !l.IsTerminal() {
		t.Fatalf("the ended order read %+v, %v", l, err)
	}
	if _, err := ankka.SendAndAwait[order](ctx, "order", "o2", "start", "doomed", 20*time.Second); err == nil {
		t.Fatal("the doomed order ended")
	}
	failed, err := ankka.Lifecycle(ctx, "order", "o2")
	if err != nil || !failed.IsFailed() || failed.Failure == nil || failed.Retries["charge"] != 1 {
		t.Fatalf("the failed order read %+v, %v", failed, err)
	}
	if l, err := ankka.Lifecycle(ctx, "order", "o3"); err != nil || l.Status != ankka.NotStarted {
		t.Fatalf("the order never started read %+v, %v", l, err)
	}

	// A copy of the replicated trip: homed here, and its position counts its event and the platform's
	// records of its home and its replication set.
	if _, err := ankka.Invoke[string](ctx, "trip", "t1", "add-stop", "leeds"); err != nil {
		t.Fatalf("the stop was not added: %v", err)
	}
	copy, err := ankka.Replication(ctx, "trip", "t1")
	if err != nil || copy.Home == nil || *copy.Home != "kit" || !slices.Equal(copy.ReplicationSet, []string{"kit"}) || copy.Position != 3 || copy.InConflict() || copy.PendingHandover != nil {
		t.Fatalf("the copy read %+v, %v", copy, err)
	}

	// An entity that is not replicated is refused, as a Scala handler is.
	if _, err := ankka.Invoke[string](ctx, "log", "l1", "add-stop", "york"); err != nil {
		t.Fatalf("the log was not written: %v", err)
	}
	_, err = ankka.Replication(ctx, "log", "l1")
	var refused *ankka.RefusalError
	if !errors.As(err, &refused) || refused.Code != ankka.NotFound {
		t.Fatalf("an entity that is not replicated read as %v", err)
	}
}
