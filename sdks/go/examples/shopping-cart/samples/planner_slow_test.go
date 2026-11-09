package samples

import (
	"encoding/json"
	"fmt"
	"slices"
	"testing"
	"time"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

func mustJSON(v any) string {
	data, err := json.Marshal(v)
	if err != nil {
		panic(err)
	}
	return string(data)
}

// docs:start script

// scriptPlan scripts the sidecar's model: the selector's structured reply as the first turn, then
// one standing rule per specialist and one for the summary, each matched on what its agent asks.
// Rules rather than turns, because the specialists are consulted at once, in no fixed order.
func scriptPlan(specialists []string, summary string) string {
	selection := fmt.Sprintf(`{"specialists":%s,"reason":"chosen for the test"}`, mustJSON(specialists))
	// The first plan's selection is the one ordered turn; every later plan's is a rule.
	turns := []map[string]any{{"text": selection}, {"when": "Plan a trip", "text": selection}}
	for _, specialist := range specialists {
		switch specialist {
		case SpecialistActivity:
			turns = append(turns, map[string]any{"when": "What should I do", "text": "Try the tram and a pastel de nata."})
		case SpecialistBudget:
			turns = append(turns, map[string]any{"when": "cost per day", "text": "Budget about 90 euros a day."})
		default:
			panic("unscripted specialist '" + specialist + "'")
		}
	}
	return mustJSON(append(turns, map[string]any{"when": "Write a brief", "text": summary}))
}

// docs:end script

func TestTheSelectorDecidesWhichSpecialistsRun(t *testing.T) {
	chosen := []string{SpecialistActivity, SpecialistBudget}
	kit := testkit.StartT(t, PlannerService(),
		testkit.Env(map[string]string{"ANKKA_MODEL_SCRIPT": scriptPlan(chosen, "A mild, affordable trip.")}))
	ctx := kit.Context(t.Context())

	// docs:start assert
	if _, err := ankka.Invoke[ankka.Done](ctx, "planner", "p-select", "start",
		StartPlan{UserID: "u-1", Destination: "Lisbon"}); err != nil {
		t.Fatal(err)
	}
	plan, err := ankka.AwaitEnd[PlanState](ctx, "planner", "p-select", 60*time.Second)
	if err != nil {
		t.Fatalf("the plan did not complete: %v\n%s", err, kit.SidecarLogs(ctx))
	}
	if plan.Selection == nil || !slices.Equal(plan.Selection.Specialists, chosen) {
		t.Fatalf("the selection is %+v", plan.Selection)
	}
	var consulted []string
	for _, c := range plan.Contributions {
		consulted = append(consulted, c.Specialist)
	}
	if !slices.Equal(consulted, chosen) {
		t.Fatalf("the contributions are %+v", plan.Contributions)
	}
	// The weather specialist was never consulted, so it contributed nothing.
	if _, ok := plan.ContributionFrom(SpecialistWeather); ok {
		t.Fatal("the weather specialist contributed")
	}
	if plan.Summary == nil || *plan.Summary != "A mild, affordable trip." {
		t.Fatalf("the summary is %v", plan.Summary)
	}
	// docs:end assert

	// docs:start drain
	// Let the second plan finish before the test ends. A workflow left mid-flight keeps consuming
	// the shared scripted model, which would starve the next test.
	if _, err := ankka.Invoke[ankka.Done](ctx, "planner", "p-twice", "start",
		StartPlan{UserID: "u-1", Destination: "Lisbon"}); err != nil {
		t.Fatal(err)
	}
	if _, err := ankka.AwaitEnd[PlanState](ctx, "planner", "p-twice", 60*time.Second); err != nil {
		t.Fatal(err)
	}
	// docs:end drain
}
