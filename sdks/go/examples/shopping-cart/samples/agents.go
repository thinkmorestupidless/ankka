// Package samples holds the examples the documentation shows in Go beside the other languages:
// each is compiled and run by a test here, and included by its region.
package samples

import (
	"context"
	"errors"
	"fmt"
	"iter"
	"strings"
	"time"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// ── an agent that reads context through the client ──

// Preferences are what the preferences entity holds for a traveller.
type Preferences struct {
	Summary string `json:"summary"`
}

type preferencesEffect[R any] = ankka.KeyValueEffect[Preferences, R]

// PreferencesEntity keeps a traveller's preferences.
var PreferencesEntity = ankka.KeyValue[Preferences]("preferences",
	func(string) Preferences { return Preferences{} },
	ankka.Update("set",
		func(_ context.Context, _ Preferences, summary string) (preferencesEffect[string], error) {
			return ankka.UpdateState[Preferences, string](Preferences{Summary: summary}).
				ThenReplyValue("done"), nil
		}),
	ankka.Query("get",
		func(_ context.Context, p Preferences, _ ankka.Unit) (ankka.ReadOnlyEffect[Preferences], error) {
			return ankka.Reply(p), nil
		}),
)

// docs:start activity

// ActivityRequest is who is asking and where they are going.
type ActivityRequest struct {
	UserID      string `json:"userId"`
	Destination string `json:"destination"`
}

// ActivityAgent suggests activities, reading the traveller's preferences itself.
var ActivityAgent = ankka.Agent("activity-agent",
	ankka.Ask("consult", func(ctx context.Context, req ActivityRequest) (ankka.AgentEffect, error) {
		// The agent reads what it needs through the client; no constructor plumbing needed.
		prefs, err := ankka.Invoke[Preferences](ctx, "preferences", req.UserID, "get", ankka.Unit{})
		if err != nil {
			return ankka.AgentEffect{}, err
		}
		return ankka.SystemMessage(
			"You are a concise activity specialist. Suggest two activities, in one sentence.").
			UserMessage("What should I do in " + req.Destination + "?").
			// Preferences are context, not something the user said, so memory records the
			// question, not the whole assembled prompt.
			WithContext("Traveller preferences: " + prefs.Summary).
			ThenReply(), nil
	}),
)

// docs:end activity

// ── the weather agent: structured replies, tools, guardrails ──

const weatherSystemMessage = "You are a weather assistant."

// docs:start forecast

// Forecast is the reply, decoded from the model's JSON. Its fields are its codec: no separate
// declaration is needed, and the caller decodes it with Invoke[Forecast].
type Forecast struct {
	Location       string `json:"location"`
	Summary        string `json:"summary"`
	DegreesCelsius int    `json:"degreesCelsius"`
}

// docs:end forecast

// docs:start tools

// WeatherArguments are the get_weather tool's; `desc` is what the model reads.
type WeatherArguments struct {
	Location string  `json:"location" desc:"A location or city name."`
	Date     *string `json:"date" desc:"Forecast date, in yyyy-MM-dd format."`
}

var getWeather = ankka.Tool("get_weather", "Returns the weather forecast for a given city.",
	func(_ context.Context, in WeatherArguments) (string, error) {
		if in.Location == "Nowhere" {
			return "", errors.New("unknown location")
		}
		return in.Location + ": 18C, sunny", nil
	})

var currentDate = ankka.Tool("current_date", "Returns today's date in yyyy-MM-dd format.",
	func(_ context.Context, _ struct{}) (string, error) {
		return "2026-09-06", nil
	})

// docs:end tools

// docs:start guardrail

var noKeys = ankka.Guardrail("no-keys",
	func(_ context.Context, stage ankka.GuardrailStage, text string) (string, error) {
		if stage == ankka.GuardrailOutput && strings.Contains(text, "sk-") {
			return "the reply contains what looks like a key", nil
		}
		return "", nil
	})

// docs:end guardrail

// maxInputLength rejects a message longer than 40 characters before any model is called.
var maxInputLength = ankka.Guardrail("max-input-length",
	func(_ context.Context, stage ankka.GuardrailStage, text string) (string, error) {
		if stage == ankka.GuardrailInput && len(text) > 40 {
			return "the message is longer than 40 characters", nil
		}
		return "", nil
	})

// WeatherAgent answers weather questions, with and without tools, in text and as a Forecast.
var WeatherAgent = ankka.Agent("weather-agent",
	ankka.Tools(getWeather, currentDate),
	ankka.Guardrails(maxInputLength, noKeys),
	ankka.Ask("ask", func(_ context.Context, question string) (ankka.AgentEffect, error) {
		return ankka.SystemMessage(weatherSystemMessage).UserMessage(question).
			Tools("get_weather", "current_date").ThenReply(), nil
	}),
	// docs:start structured
	// Same interaction, but the reply is parsed into a Forecast.
	ankka.Ask("ask-structured", func(_ context.Context, question string) (ankka.AgentEffect, error) {
		return ankka.SystemMessage(weatherSystemMessage + " Reply with JSON.").UserMessage(question).
			ThenReplyJSON("a forecast: location, summary, degreesCelsius"), nil
	}),
	// docs:end structured
	// docs:start guarded
	// Guarded, to exercise rejection before a model is called.
	ankka.Ask("guarded", func(_ context.Context, question string) (ankka.AgentEffect, error) {
		return ankka.SystemMessage(weatherSystemMessage).UserMessage(question).
			Guardrails("max-input-length", "no-keys").ThenReply(), nil
	}),
	// docs:end guarded
)

// ── the approval agent: tools that wait for a person ──

// RefundArguments are the issue_refund tool's.
type RefundArguments struct {
	Order  string `json:"order" desc:"The order to refund."`
	Amount int    `json:"amount" desc:"The amount to refund."`
}

// CustomerArguments are the close_account tool's.
type CustomerArguments struct {
	Customer string `json:"customer" desc:"The customer whose account to close."`
}

// docs:start requires-approval

var issueRefund = ankka.ApprovedTool("issue_refund",
	"Refunds an order. A supervisor approves every refund before it is made.", 0,
	func(_ context.Context, in RefundArguments) (string, error) {
		return fmt.Sprintf("refunded %d on %s", in.Amount, in.Order), nil
	})

// docs:end requires-approval

// docs:start requires-approval-within

var closeAccount = ankka.ApprovedTool("close_account", "Closes a customer's account.",
	2*time.Second,
	func(_ context.Context, in CustomerArguments) (string, error) {
		return "closed " + in.Customer, nil
	})

// docs:end requires-approval-within

// ApprovalAgent is a support agent whose refunds and closures wait for a person.
var ApprovalAgent = ankka.Agent("approval-agent",
	ankka.Tools(issueRefund, closeAccount),
	ankka.Ask("ask", func(_ context.Context, question string) (ankka.AgentEffect, error) {
		return ankka.SystemMessage("You handle support requests.").UserMessage(question).
			Tools("issue_refund", "close_account").ThenReply(), nil
	}),
	ankka.AskStream("chat", func(_ context.Context, question string) (ankka.AgentEffect, error) {
		return ankka.SystemMessage("You handle support requests.").UserMessage(question).
			Tools("issue_refund", "close_account").ThenReply(), nil
	}),
)

// docs:start ask-and-decide

// askAndDecide asks the agent in session s-42 and, when a tool waits for a person, decides for it.
func askAndDecide(ctx context.Context) (string, error) {
	answer, err := ankka.Invoke[string](ctx, "approval-agent", "s-42", "ask", "refund order o-7")
	var waiting *ankka.ApprovalAwaitedError
	if !errors.As(err, &waiting) {
		return answer, err
	}
	// In a real service the requests are shown to a person, and the decision arrives later,
	// from whatever route they make it through; that route's ACL is who may decide.
	decision := ankka.Decision{Approved: true, By: "dana"}
	answer, err = ankka.DecideApproval[string](ctx, "approval-agent", "s-42", "ask",
		waiting.Requests[0].ID, decision)
	if errors.As(err, &waiting) {
		return fmt.Sprintf("still waiting on %d", len(waiting.Requests)), nil
	}
	return answer, err
}

// docs:end ask-and-decide

// SupportEndpoint streams the approval agent's replies.
var SupportEndpoint = ankka.NewEndpoint("/support", ankka.AllowAll(),
	// docs:start sse-approval
	// The reply's text as it comes; a turn that ends waiting for a person ends with an event of its
	// own name, carrying the requests, so a browser knows the stream waits rather than finished.
	ankka.SSEEvents("/{session}", func(ctx context.Context, req *ankka.Request) iter.Seq2[ankka.SSEEvent, error] {
		return func(yield func(ankka.SSEEvent, error) bool) {
			session := req.Param("session")
			stream := ankka.InvokeStream(ctx, "approval-agent", session, "chat", "refund order o-7")
			for text, err := range stream {
				var waiting *ankka.ApprovalAwaitedError
				switch {
				case errors.As(err, &waiting):
					yield(ankka.SSEEvent{Name: "approval", Data: waiting.Requests}, nil)
					return
				case err != nil:
					yield(ankka.SSEEvent{}, err)
					return
				case !yield(ankka.SSEEvent{Text: text}, nil):
					return
				}
			}
		}
	}),
	// docs:end sse-approval
	ankka.Post("/ask-and-decide",
		func(ctx context.Context, _ *ankka.Request, _ ankka.Unit) (string, error) {
			return askAndDecide(ctx)
		}),
)

// ── a tool that calls another service ──

// BalanceArguments are the read_balance tool's.
type BalanceArguments struct {
	Customer string `json:"customer" desc:"The customer whose balance to read."`
}

// docs:start service-call-tool

// WalletAgent is a support agent whose tool reads a balance from the wallet service, as its own
// service: the call presents this service's certificate, so wallet's ACL can admit it by name.
var WalletAgent = ankka.Agent("wallet-agent",
	ankka.Tools(ankka.Tool("read_balance", "Reads a customer's wallet balance.",
		func(ctx context.Context, in BalanceArguments) (string, error) {
			res, err := ankka.CallService(ctx, "wallet", ankka.ServiceRequest{
				Method: "GET", Path: "/wallet/balances/" + in.Customer})
			if err != nil {
				return "", err
			}
			if res.Status != 200 {
				return "", fmt.Errorf("wallet answered %d", res.Status)
			}
			return string(res.Body), nil
		})),
	ankka.Ask("ask", func(_ context.Context, question string) (ankka.AgentEffect, error) {
		return ankka.SystemMessage("You answer questions about wallets.").UserMessage(question).
			Tools("read_balance").ThenReply(), nil
	}),
)

// docs:end service-call-tool

// ── naming a provider ──

// docs:start naming-a-provider

// Triage is answered by the provider the sidecar configured under the name openai, whatever the
// service's default is.
var Triage = ankka.Agent("triage",
	ankka.Ask("route", func(_ context.Context, question string) (ankka.AgentEffect, error) {
		return ankka.AgentModel("openai").
			SystemMessage("Say which team should answer.").
			UserMessage(question).
			ThenReply(), nil
	}),
)

// docs:end naming-a-provider
