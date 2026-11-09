package samples

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"strings"
	"sync"
	"time"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// The specialists the planner knows, which are the roles their agents write under.
const (
	SpecialistWeather  = "weather"
	SpecialistActivity = "activity"
	SpecialistBudget   = "budget"
)

// AllSpecialists is every specialist a selection may name.
var AllSpecialists = []string{SpecialistWeather, SpecialistActivity, SpecialistBudget}

// AgentSelection is the selector's structured reply.
type AgentSelection struct {
	Specialists []string `json:"specialists"`
	Reason      string   `json:"reason"`
}

// Contribution is one specialist's answer.
type Contribution struct {
	Specialist string `json:"specialist"`
	Answer     string `json:"answer"`
}

// PlanState is what the plan has decided so far.
type PlanState struct {
	UserID        string          `json:"userId"`
	Destination   string          `json:"destination"`
	Status        string          `json:"status"`
	Selection     *AgentSelection `json:"selection"`
	Contributions []Contribution  `json:"contributions"`
	Summary       *string         `json:"summary"`
}

// ContributionFrom is what a specialist answered, if it was consulted.
func (p PlanState) ContributionFrom(specialist string) (string, bool) {
	for _, c := range p.Contributions {
		if c.Specialist == specialist {
			return c.Answer, true
		}
	}
	return "", false
}

// StartPlan starts a plan for a traveller.
type StartPlan struct {
	UserID      string `json:"userId"`
	Destination string `json:"destination"`
}

// docs:start selector

// SelectorAgent routes a request to specialists. Its own deliberation is not part of the
// conversation the specialists and the summariser share, so it keeps no session memory.
var SelectorAgent = ankka.Agent("selector-agent",
	ankka.Ask("select", func(_ context.Context, request string) (ankka.AgentEffect, error) {
		if strings.TrimSpace(request) == "" {
			return ankka.AgentEffect{}, ankka.Refuse(ankka.BadRequest, "nothing to plan")
		}
		return ankka.SystemMessage("You route planning requests to specialists.\n" +
			"Available specialists: " + strings.Join(AllSpecialists, ", ") + ".\n" +
			`Reply with JSON: {"specialists":["..."],"reason":"..."}.` + "\n" +
			"Choose only the specialists the request genuinely needs.").
			UserMessage(request).
			Memory(false).
			ThenReplyJSON("the specialists to consult, and why"), nil
	}),
)

// docs:end selector

// BudgetAgent estimates what a trip costs.
var BudgetAgent = ankka.Agent("budget-agent",
	ankka.Role(SpecialistBudget),
	ankka.Ask("consult", func(_ context.Context, destination string) (ankka.AgentEffect, error) {
		return ankka.SystemMessage("You are a concise budget specialist. One sentence.").
			UserMessage("What does a trip to " + destination + " cost per day?").
			ThenReply(), nil
	}),
)

// docs:start summary

// SummaryAgent combines what the specialists said, reading them back from the shared session:
// every message the specialists wrote carries its agent's role.
var SummaryAgent = ankka.Agent("summary-agent",
	ankka.Ask("summarise", func(_ context.Context, destination string) (ankka.AgentEffect, error) {
		return ankka.SystemMessage(
			"You write short trip briefs. Combine what the specialists said into two sentences.").
			UserMessage("Write a brief for a trip to " + destination + ".").
			ThenReply(), nil
	}),
)

// docs:end summary

type planStep = ankka.StepEffect[PlanState]

// docs:start session

// session is the session every agent in a plan is addressed with: the workflow's own id, so the
// specialists accumulate one conversation and the summariser reads it back.
func session(ctx context.Context) string {
	c, _ := ankka.CommandFrom(ctx)
	return c.EntityID
}

// docs:end session

func startPlan(_ context.Context, s PlanState, start StartPlan) (ankka.WorkflowEffect[PlanState, ankka.Done], error) {
	if strings.TrimSpace(start.Destination) == "" {
		return ankka.WorkflowEffect[PlanState, ankka.Done]{}, ankka.Refuse(ankka.BadRequest, "a destination is required")
	}
	if s.Status != "" {
		return ankka.WorkflowEffect[PlanState, ankka.Done]{}, ankka.Refuse(ankka.Conflict, "this plan has already started")
	}
	started := PlanState{UserID: start.UserID, Destination: start.Destination, Status: "selecting"}
	return ankka.WorkflowUpdate[PlanState, ankka.Done](started).
		ThenTransitionTo("select-specialists", start.Destination).
		ThenReplyValue(ankka.Done{}), nil
}

// docs:start select

// selectSpecialists asks the selector which specialists this request needs.
func selectSpecialists(ctx context.Context, s PlanState, destination string) (planStep, error) {
	selection, err := ankka.Invoke[AgentSelection](ctx, "selector-agent", session(ctx), "select",
		"Plan a trip to "+destination)
	if err != nil {
		return planStep{}, err
	}
	// Ignore anything the model invented that is not a real specialist.
	var known []string
	for _, name := range selection.Specialists {
		if slices.Contains(AllSpecialists, name) {
			known = append(known, name)
		}
	}
	if len(known) == 0 {
		// A selector that names nothing usable should not stall the plan.
		known = []string{SpecialistActivity}
	}
	selection.Specialists = known
	s.Status, s.Selection = "consulting", &selection
	return ankka.StepUpdate(s).ThenTransitionTo("consult-specialists", ankka.Unit{}), nil
}

// docs:end select

// docs:start parallel

// consultSpecialists consults every chosen specialist at once: a goroutine per call rather than
// one call after another, because the specialists are independent, so the step takes as long as
// the slowest model call instead of the sum of them.
func consultSpecialists(ctx context.Context, s PlanState, _ ankka.Unit) (planStep, error) {
	chosen := s.Selection.Specialists
	contributions := make([]Contribution, len(chosen))
	failures := make([]error, len(chosen))
	var pending sync.WaitGroup
	for i, specialist := range chosen {
		pending.Add(1)
		go func() {
			defer pending.Done()
			answer, err := consult(ctx, s, specialist)
			contributions[i], failures[i] = Contribution{Specialist: specialist, Answer: answer}, err
		}()
	}
	pending.Wait()
	if err := errors.Join(failures...); err != nil {
		return planStep{}, err
	}
	s.Contributions = contributions
	return ankka.StepUpdate(s).ThenTransitionTo("summarise", ankka.Unit{}), nil
}

// consult asks one specialist, in the plan's session.
func consult(ctx context.Context, s PlanState, specialist string) (string, error) {
	switch specialist {
	case SpecialistWeather:
		return ankka.Invoke[string](ctx, "weather-agent", session(ctx), "consult", s.Destination)
	case SpecialistActivity:
		return ankka.Invoke[string](ctx, "activity-agent", session(ctx), "consult",
			ActivityRequest{UserID: s.UserID, Destination: s.Destination})
	case SpecialistBudget:
		return ankka.Invoke[string](ctx, "budget-agent", session(ctx), "consult", s.Destination)
	}
	return fmt.Sprintf("no specialist named '%s'", specialist), nil
}

// docs:end parallel

// docs:start summarise

// summarise combines the contributions, reading them back from the shared session.
func summarise(ctx context.Context, s PlanState, _ ankka.Unit) (planStep, error) {
	brief, err := ankka.Invoke[string](ctx, "summary-agent", session(ctx), "summarise", s.Destination)
	if err != nil {
		return planStep{}, err
	}
	s.Status, s.Summary = "completed", &brief
	return ankka.StepUpdate(s).ThenEnd(), nil
}

// docs:end summarise

// docs:start planner

// retryOnce runs a step again once before the workflow fails: a model call that failed is worth
// one more try.
var retryOnce = ankka.StepRecovery(ankka.Recovery{MaxRetries: 1})

// PlannerWorkflow orchestrates the specialists into one plan: dynamic, since the first step asks
// which specialists are needed; parallel, since the second consults them all at once; durable,
// since each step's result is journalled before the next begins.
var PlannerWorkflow = ankka.Workflow[PlanState]("planner",
	func(string) PlanState { return PlanState{} },
	ankka.WorkflowCommand("start", startPlan),
	ankka.Query("plan", func(_ context.Context, s PlanState, _ ankka.Unit) (ankka.ReadOnlyEffect[PlanState], error) {
		return ankka.Reply(s), nil
	}),
	ankka.Step("select-specialists", selectSpecialists),
	ankka.Step("consult-specialists", consultSpecialists),
	ankka.Step("summarise", summarise),
	ankka.StateCodec(ankka.CodecFor[PlanState](ankka.Manifest("plan-state"))),
	// docs:start planner-settings
	ankka.WorkflowTimeout(5*time.Minute),
	// Model calls are slow, and a tool loop can legitimately take a while.
	ankka.StepTimeout(90*time.Second),
	ankka.ForStep("select-specialists", retryOnce),
	ankka.ForStep("consult-specialists", retryOnce),
	ankka.ForStep("summarise", retryOnce),
	// docs:end planner-settings
)

// docs:end planner

// PlannerService is the planner and every agent it consults.
func PlannerService() *ankka.Service {
	return ankka.NewService(PreferencesEntity, PlannerWorkflow, SelectorAgent, WeatherAgent,
		ActivityAgent, BudgetAgent, SummaryAgent)
}
