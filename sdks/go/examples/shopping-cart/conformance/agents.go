package conformance

import (
	"context"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"sync"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// ── reminder: a timed action ──

// Reminder records on the conformance entity it is given the id of.
var Reminder = ankka.TimedAction("reminder",
	ankka.Action("remind", func(ctx context.Context, id string) (ankka.ActionEffect, error) {
		if _, err := ankka.Invoke[string](ctx, "conformance", id, "record", "reminded"); err != nil {
			return ankka.ActionEffect{}, err
		}
		return ankka.ActionDone(), nil
	}),
	// Records the due time it was run for, as the runtime told it.
	ankka.Action("tick", func(ctx context.Context, id string) (ankka.ActionEffect, error) {
		due := "undefined"
		if t, ok := ankka.TimerFrom(ctx); ok && !t.Due.IsZero() {
			due = fmt.Sprint(t.Due.UnixMilli())
		}
		if _, err := ankka.Invoke[string](ctx, "conformance", id, "record", "due:"+due); err != nil {
			return ankka.ActionEffect{}, err
		}
		return ankka.ActionDone(), nil
	}),
)

// ── assistant: an agent whose tool acts through the client ──

// LookupArguments are the lookup tool's.
type LookupArguments struct {
	ID string `json:"id"`
}

// lookup records under the id and answers how many things are recorded there.
func lookup(mark, label string) func(context.Context, LookupArguments) (string, error) {
	return func(ctx context.Context, in LookupArguments) (string, error) {
		if in.ID == "" {
			return "", errors.New("an id is needed")
		}
		if _, err := ankka.Invoke[string](ctx, "conformance", in.ID, "record", mark); err != nil {
			return "", err
		}
		n, err := ankka.Invoke[int](ctx, "conformance", in.ID, "count", ankka.Unit{})
		if err != nil {
			return "", err
		}
		return fmt.Sprintf("%s for %s is %d", label, in.ID, n), nil
	}
}

var noSecrets = ankka.Guardrail("no-secrets", func(_ context.Context, stage ankka.GuardrailStage, text string) (string, error) {
	if strings.Contains(text, "sk-") {
		return string(stage) + " rejected by no-secrets", nil
	}
	return "", nil
})

func describe(_ context.Context, question string) (ankka.AgentEffect, error) {
	return ankka.SystemMessage("You are helpful.").UserMessage(question).Tools("lookup").Guardrails("no-secrets").ThenReply(), nil
}

// Assistant is an agent whose tool acts through the client.
var Assistant = ankka.Agent("assistant",
	ankka.Tools(ankka.Tool("lookup", "Looks up how many things were recorded under an id.", lookup("looked-up", "count"))),
	ankka.Guardrails(noSecrets),
	ankka.Ask("ask", describe),
	ankka.AskStream("stream", describe),
	// Answered by the model the sidecar has under the name second, not its default.
	ankka.Ask("ask-second", func(_ context.Context, question string) (ankka.AgentEffect, error) {
		return ankka.AgentModel("second").SystemMessage("You are helpful.").UserMessage(question).ThenReply(), nil
	}),
)

// ── approver: an agent whose tools wait for a person, with two MCP servers ──

// RefundArguments are the refund tool's.
type RefundArguments struct {
	ID string `json:"id"`
}

// PathArguments are the ask_scripted tool's.
type PathArguments struct {
	Path string `json:"path"`
}

var instructed = regexp.MustCompile(`(?i)ignore what you were told`)

// docs:start approver

// Approver is an agent whose refund waits for a person, and whose MCP server guarded does too.
var Approver = ankka.Agent("approver",
	ankka.Tools(
		ankka.ApprovedTool("refund", "Refunds what was recorded under an id. A person approves every refund.", 0,
			func(ctx context.Context, in RefundArguments) (string, error) {
				if _, err := ankka.Invoke[string](ctx, "conformance", in.ID, "record", "refunded"); err != nil {
					return "", err
				}
				return "refunded " + in.ID, nil
			}),
		// A tool calls another service as this service: the called service's ACL can admit it by name.
		ankka.Tool("ask_scripted", "Asks the scripted service for what is at a path.", func(ctx context.Context, in PathArguments) (string, error) {
			res, err := ankka.CallService(ctx, "scripted", ankka.ServiceRequest{Method: "GET", Path: in.Path})
			if err != nil {
				return "", err
			}
			if res.Status < 200 || res.Status > 299 {
				return "", fmt.Errorf("the scripted service answered %d: %s", res.Status, res.Body)
			}
			return string(res.Body), nil
		}),
	),
	// Both found at ANKKA_MCP_<NAME>_URL; every tool of guarded waits for a person.
	ankka.McpServers(ankka.McpServer("tickets"), ankka.McpServer("guarded", ankka.McpApproval(0))),
	ankka.Guardrails(ankka.ResultGuardrail("no-instructions", func(_ context.Context, _, text string) (string, error) {
		if instructed.MatchString(text) {
			return "the result tries to instruct whoever reads it", nil
		}
		return "", nil
	})),
	ankka.Ask("ask", func(_ context.Context, question string) (ankka.AgentEffect, error) {
		return ankka.SystemMessage("You approve refunds.").UserMessage(question).Tools("refund", "ask_scripted").ThenReply(), nil
	}),
)

// docs:end approver

// ── answerer: an autonomous agent, whose tool acts through the client ──

// Answer is a task's result.
type Answer struct {
	Answer  string   `json:"answer"`
	Sources []string `json:"sources"`
}

var flakySeen sync.Map

// AnswerTask is the one task type the answerer accepts.
var AnswerTask = ankka.NewTaskType[Answer]("answer", "Answer a question, citing what you looked up",
	ankka.Rule("cites-sources", func(_ context.Context, a Answer) (ankka.Verdict, error) {
		if len(a.Sources) > 0 {
			return ankka.Accepted(), nil
		}
		return ankka.Rejected("sources must not be empty"), nil
	}),
	// Fails the first time it sees "flaky-once": a rule that fails once, then decides.
	ankka.Rule("steady", func(_ context.Context, a Answer) (ankka.Verdict, error) {
		if a.Answer == "flaky-once" {
			if _, seen := flakySeen.LoadOrStore(a.Answer, true); !seen {
				return ankka.Verdict{}, errors.New("the rule threw")
			}
		}
		return ankka.Accepted(), nil
	}),
)

// Answerer is the autonomous agent.
var Answerer = ankka.Autonomous("answerer", "Answers questions",
	ankka.Tools(
		ankka.Tool("lookup", "Looks up how many things were recorded under an id.", lookup("looked-up", "count")),
		// Waits for a person before it runs; what it records is counted as a run.
		ankka.ApprovedTool("sensitive_lookup", "Looks up what was recorded under an id. A person approves every one.", 0,
			lookup("sensitive", "sensitive count")),
	),
	ankka.Guardrails(noSecrets),
	ankka.Accepts(AnswerTask, 4),
)
