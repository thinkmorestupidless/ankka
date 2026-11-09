package samples

import (
	"context"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

func TestTheSelectorRoutesWithoutRememberingTheRouting(t *testing.T) {
	ctx := context.Background()
	if p := PlannerService().Problems(); len(p) > 0 {
		t.Fatal(p)
	}
	model := testkit.NewScriptedModel().ExpectText(`{"specialists":["weather"],"reason":"rain"}`)
	kit := testkit.Agent(SelectorAgent, "p-1", model)
	defer kit.Close()
	answer, err := kit.Ask(ctx, "select", "Plan a trip to Lisbon")
	if err != nil || answer != `{"specialists":["weather"],"reason":"rain"}` {
		t.Fatalf("the selector answered %q, %v", answer, err)
	}
	if _, err := kit.Ask(ctx, "select", "  "); err == nil {
		t.Fatal("nothing to plan was routed")
	}
}

func TestTheSummariserAndTheBudgetSpecialistAnswer(t *testing.T) {
	ctx := context.Background()
	budget := testkit.Agent(BudgetAgent, "p-1", testkit.NewScriptedModel().ExpectText("About 90 euros a day."))
	defer budget.Close()
	if answer, _ := budget.Ask(ctx, "consult", "Lisbon"); answer != "About 90 euros a day." {
		t.Fatalf("the budget specialist answered %q", answer)
	}
	model := testkit.NewScriptedModel().ExpectText("A mild, affordable trip.")
	summary := testkit.Agent(SummaryAgent, "p-1", model)
	defer summary.Close()
	if answer, _ := summary.Ask(ctx, "summarise", "Lisbon"); answer != "A mild, affordable trip." {
		t.Fatalf("the summariser answered %q", answer)
	}
	if asked := model.Calls[0].Messages; asked[len(asked)-1].Text != "Write a brief for a trip to Lisbon." {
		t.Fatalf("the summariser asked %+v", asked)
	}
}

func TestAPlanStartsBySelecting(t *testing.T) {
	ctx := context.Background()
	kit := testkit.Workflow(PlannerWorkflow)
	defer kit.Close()
	if r := kit.Command(ctx, "start", StartPlan{UserID: "u-1"}); r.Refusal() == nil || r.Refusal().Code != ankka.BadRequest {
		t.Fatalf("a plan with no destination answered %v", r.Refusal())
	}
	r := kit.Command(ctx, "start", StartPlan{UserID: "u-1", Destination: "Lisbon"})
	if r.Refusal() != nil || r.Transition() == nil || r.Transition().Step != "select-specialists" {
		t.Fatalf("start answered %v, %+v", r.Refusal(), r.Transition())
	}
	if s := kit.State(); s.Status != "selecting" || s.Destination != "Lisbon" {
		t.Fatalf("the plan is %+v", s)
	}
	if r := kit.Command(ctx, "start", StartPlan{UserID: "u-1", Destination: "Porto"}); r.Refusal() == nil || r.Refusal().Code != ankka.Conflict {
		t.Fatalf("starting twice answered %v", r.Refusal())
	}
	if _, ok := kit.State().ContributionFrom(SpecialistWeather); ok {
		t.Fatal("a plan not yet consulted has a contribution")
	}
}
