package ankka_test

import (
	"context"
	"sync"
	"testing"
	"time"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

// reminderRuns counts kRetryReminder's runs; the first fails.
var reminderRuns struct {
	sync.Mutex
	inputs []string
}

var kRetryReminder = ankka.TimedAction("k-retry-reminder",
	ankka.Action("remind", func(_ context.Context, note string) (ankka.ActionEffect, error) {
		reminderRuns.Lock()
		defer reminderRuns.Unlock()
		reminderRuns.inputs = append(reminderRuns.inputs, note)
		if len(reminderRuns.inputs) == 1 {
			return ankka.ActionFail(ankka.Unavailable, "the mail server is down"), nil
		}
		return ankka.ActionDone(), nil
	}),
)

// features/polyglot/components.feature, "a timer set by a handler runs its timed action, and again
// after a failure", the Go row: the action fails its first run, the sweeper runs it again, and once
// it succeeds it runs no more.
func TestATimerRunsItsActionAgainAfterAFailure(t *testing.T) {
	kit := testkit.StartT(t, ankka.NewService(kRetryReminder))
	ctx := kit.Context(t.Context())
	if err := ankka.Schedule(ctx, "remind-c1", time.Second,
		ankka.TimerTarget{ComponentID: "k-retry-reminder", Handler: "remind"}, "call c1"); err != nil {
		t.Fatal(err)
	}
	runs := func() []string {
		reminderRuns.Lock()
		defer reminderRuns.Unlock()
		return append([]string(nil), reminderRuns.inputs...)
	}
	deadline := time.Now().Add(60 * time.Second)
	for len(runs()) < 2 && time.Now().Before(deadline) {
		time.Sleep(250 * time.Millisecond)
	}
	if got := runs(); len(got) < 2 || got[0] != "call c1" || got[1] != "call c1" {
		t.Fatalf("the timed action ran %v, wanting a failed run and a second one", got)
	}
	// Succeeded: the timer is gone, so the action is not run a third time.
	time.Sleep(5 * time.Second)
	if got := runs(); len(got) != 2 {
		t.Fatalf("after it succeeded the timed action ran again: %v", got)
	}
}
