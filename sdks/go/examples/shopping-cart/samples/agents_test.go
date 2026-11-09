package samples

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

func TestTheActivityAgentReadsPreferencesAsContext(t *testing.T) {
	model := testkit.NewScriptedModel().ExpectText("Walk the old town, then the harbour.")
	k := testkit.Agent(ActivityAgent, "s1", model, testkit.AgentCalling(PreferencesEntity))
	defer k.Close()
	answer, err := k.Ask(context.Background(), "consult", ActivityRequest{UserID: "u-1", Destination: "Porto"})
	if err != nil || answer != "Walk the old town, then the harbour." {
		t.Fatalf("answered %q, %v", answer, err)
	}
	seen := model.Calls[0].Messages[len(model.Calls[0].Messages)-1].Text
	if !strings.Contains(seen, "Traveller preferences:") || !strings.Contains(seen, "What should I do in Porto?") {
		t.Fatalf("the model saw %q", seen)
	}
}

func TestTheWeatherAgentAnswersAsAForecast(t *testing.T) {
	model := testkit.NewScriptedModel().ExpectText(`{"location":"Lisbon","summary":"sunny","degreesCelsius":18}`)
	k := testkit.Agent(WeatherAgent, "s1", model)
	defer k.Close()
	text, err := k.Ask(context.Background(), "ask-structured", "Lisbon tomorrow?")
	if err != nil {
		t.Fatal(err)
	}
	var f Forecast
	if err := json.Unmarshal([]byte(text), &f); err != nil || f != (Forecast{Location: "Lisbon", Summary: "sunny", DegreesCelsius: 18}) {
		t.Fatalf("the forecast is %+v (%v)", f, err)
	}
}

// docs:start agent-test

func TestTheWeatherAgentsToolsAnswerTheModel(t *testing.T) {
	// docs:start scripted-model
	// The model answers from its script, in order, and fails loudly when it runs out.
	model := testkit.NewScriptedModel().
		ExpectToolCall("current_date", map[string]any{}).
		ExpectToolCall("get_weather", map[string]any{"location": "Lisbon"}).
		ExpectToolCall("get_weather", map[string]any{"location": "Nowhere", "date": "2026-09-07"}).
		ExpectText("Lisbon is sunny; Nowhere is unknown.")
	// docs:end scripted-model
	k := testkit.Agent(WeatherAgent, "s1", model)
	defer k.Close()
	answer, err := k.Ask(context.Background(), "ask", "Lisbon or Nowhere?")
	if err != nil || answer != "Lisbon is sunny; Nowhere is unknown." {
		t.Fatalf("answered %q, %v", answer, err)
	}
	var results []string
	for _, m := range model.Calls[3].Messages {
		if m.Role == "tool" {
			results = append(results, m.Text)
		}
	}
	want := []string{"2026-09-06", "Lisbon: 18C, sunny"}
	if len(results) != 3 || results[0] != want[0] || results[1] != want[1] ||
		!strings.Contains(results[2], "unknown location") {
		t.Fatalf("the model was told %q", results)
	}
}

// docs:end agent-test

func TestTheGuardrailsRejectBeforeAndAfterTheModel(t *testing.T) {
	model := testkit.NewScriptedModel()
	k := testkit.Agent(WeatherAgent, "s1", model)
	defer k.Close()
	_, err := k.Ask(context.Background(), "guarded", strings.Repeat("rain? ", 10))
	var refusal *ankka.RefusalError
	if !errors.As(err, &refusal) || refusal.Code != ankka.Forbidden || len(model.Calls) != 0 {
		t.Fatalf("a long message was answered %v after %d model calls", err, len(model.Calls))
	}
	model.ExpectText("use sk-123")
	_, err = k.Ask(context.Background(), "guarded", "any keys?")
	if !errors.As(err, &refusal) || refusal.Code != ankka.Forbidden || !strings.Contains(refusal.Message, "no-keys") {
		t.Fatalf("a leaked key was answered %v", err)
	}
}

func TestARefundWaitsForAPerson(t *testing.T) {
	model := testkit.NewScriptedModel().
		ExpectToolCall("issue_refund", RefundArguments{Order: "o-7", Amount: 12}).
		ExpectText("Refunded 12 on o-7.")
	k := testkit.Agent(ApprovalAgent, "s-42", model)
	defer k.Close()
	_, err := k.Ask(context.Background(), "ask", "refund order o-7")
	var waiting *ankka.ApprovalAwaitedError
	if !errors.As(err, &waiting) || len(waiting.Requests) != 1 || waiting.Requests[0].Tool != "issue_refund" {
		t.Fatalf("the turn ended with %v", err)
	}
	answer, err := k.Decide(context.Background(), waiting.Requests[0].ID, true, "dana", "")
	if err != nil || answer != "Refunded 12 on o-7." {
		t.Fatalf("after the decision: %q, %v", answer, err)
	}
	if told := model.Calls[1].Messages[len(model.Calls[1].Messages)-1].Text; told != "refunded 12 on o-7" {
		t.Fatalf("the model was told %q", told)
	}
}

func TestClosingAnAccountWaitsWithATimeLimit(t *testing.T) {
	model := testkit.NewScriptedModel().
		ExpectToolCall("close_account", CustomerArguments{Customer: "c-9"}).
		ExpectText("Not closed.")
	k := testkit.Agent(ApprovalAgent, "s-43", model)
	defer k.Close()
	_, err := k.Ask(context.Background(), "ask", "close c-9")
	var waiting *ankka.ApprovalAwaitedError
	if !errors.As(err, &waiting) || waiting.Requests[0].Tool != "close_account" || waiting.Requests[0].ExpiresAt == nil {
		t.Fatalf("the turn ended with %v", err)
	}
	if _, err := k.Decide(context.Background(), waiting.Requests[0].ID, false, "dana", "not today"); err != nil {
		t.Fatal(err)
	}
	if told := model.Calls[1].Messages[len(model.Calls[1].Messages)-1].Text; !strings.Contains(told, "dana") || !strings.Contains(told, "not today") {
		t.Fatalf("the model was told %q", told)
	}
}

func TestTheWalletToolCallsTheWalletService(t *testing.T) {
	// The unit kit answers no other service, so the tool's error is what the model is told; the
	// call itself is proved against a sidecar in the slow suite.
	model := testkit.NewScriptedModel().
		ExpectToolCall("read_balance", BalanceArguments{Customer: "c-1"}).
		ExpectText("I could not read it.")
	k := testkit.Agent(WalletAgent, "s1", model)
	defer k.Close()
	if _, err := k.Ask(context.Background(), "ask", "what is c-1's balance?"); err != nil {
		t.Fatal(err)
	}
	if told := model.Calls[1].Messages[len(model.Calls[1].Messages)-1].Text; !strings.Contains(told, "wallet") {
		t.Fatalf("the model was told %q", told)
	}
}

func TestTriageNamesItsProvider(t *testing.T) {
	svc := ankka.NewService(Triage, WeatherAgent, ActivityAgent, ApprovalAgent, WalletAgent, PreferencesEntity, SupportEndpoint)
	if p := svc.Problems(); len(p) > 0 {
		t.Fatal(p)
	}
	model := testkit.NewScriptedModel().ExpectText("billing")
	k := testkit.Agent(Triage, "s1", model)
	defer k.Close()
	if answer, err := k.Ask(context.Background(), "route", "my invoice is wrong"); err != nil || answer != "billing" {
		t.Fatalf("answered %q, %v", answer, err)
	}
}
