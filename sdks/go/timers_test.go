package ankka

import (
	"strings"
	"testing"
	"time"
)

// A recurring timer's period is refused in the runtime's words, naming the timer, below a
// millisecond and above a century, and admitted at both ends.
func TestARecurringPeriodIsTheRuntimesRule(t *testing.T) {
	for _, bad := range []time.Duration{0, -time.Second, time.Microsecond, (maxPeriodDays + 1) * 24 * time.Hour} {
		p := periodProblem("recur-rr1", bad)
		if !strings.Contains(p, "timer 'recur-rr1' has a period of") || !strings.Contains(p, "from 1 millisecond to 36500 days") {
			t.Errorf("a period of %s: %q", bad, p)
		}
	}
	for _, good := range []time.Duration{time.Millisecond, time.Second, maxPeriodDays * 24 * time.Hour} {
		if p := periodProblem("recur-ok", good); p != "" {
			t.Errorf("a period of %s was refused: %q", good, p)
		}
	}
}
