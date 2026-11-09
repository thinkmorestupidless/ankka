package samples

import (
	"context"
	"encoding/json"
	"fmt"
	"iter"
	"strings"
	"time"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/cart"
)

// ── the cart answerer: a task type and the autonomous agent that works it ──

// docs:start task-type

// Answer is an answer, and what the agent looked at to reach it. Its fields are the schema the
// model is shown, as a tool's input fields are.
type Answer struct {
	Answer  string   `json:"answer"`
	Sources []string `json:"sources"`
}

// AnswerTask is the one kind of work the answerer accepts. "answer" is the wire name, written into
// every task of this type: renaming the Go value changes nothing stored.
var AnswerTask = ankka.NewTaskType[Answer]("answer",
	"Answer a question about a shopping cart, citing what you looked at",
	ankka.Rule("cites-sources", func(_ context.Context, a Answer) (ankka.Verdict, error) {
		if len(a.Sources) == 0 {
			return ankka.Rejected("say which tools you used in sources"), nil
		}
		return ankka.Accepted(), nil
	}),
)

// docs:end task-type

// docs:start agent

// CartArguments name the cart a tool reads.
type CartArguments struct {
	CartID string `json:"cartId" desc:"The id of the cart."`
}

// cartOf reads the cart through the client, as the tools do. A tool may run more than once for
// one request of the model (after a crash the last recorded request's tools run again), and these
// only read, so that costs nothing.
func cartOf(ctx context.Context, cartID string) (cart.Cart, error) {
	return ankka.Invoke[cart.Cart](ctx, cart.ComponentID, cartID, "get-cart", ankka.Unit{})
}

// CartAnswerer answers questions about carts on its own: it is handed a task and works it,
// iteration by iteration, until it completes it with an Answer that cites its sources.
var CartAnswerer = ankka.Autonomous("cart-answerer", "Answers questions about shopping carts",
	ankka.Instructions("Look carts up rather than guessing. Be brief."),
	ankka.Tools(
		ankka.Tool("cart_contents", "Lists what is in a cart.",
			func(ctx context.Context, in CartArguments) (string, error) {
				c, err := cartOf(ctx, in.CartID)
				if err != nil {
					return "", err
				}
				if len(c.Items) == 0 {
					return "cart " + in.CartID + " is empty", nil
				}
				var lines []string
				for _, i := range c.Items {
					lines = append(lines, fmt.Sprintf("%d x %s", i.Quantity, i.Name))
				}
				return strings.Join(lines, ", "), nil
			}),
		ankka.Tool("cart_total", "Counts the items in a cart.",
			func(ctx context.Context, in CartArguments) (string, error) {
				c, err := cartOf(ctx, in.CartID)
				if err != nil {
					return "", err
				}
				total := 0
				for _, i := range c.Items {
					total += i.Quantity
				}
				return fmt.Sprintf("cart %s holds %d items", in.CartID, total), nil
			}),
	),
	ankka.Guardrails(maxInputLength2000),
	// The task type it accepts, and the most model calls it may spend on one task.
	ankka.Accepts(AnswerTask, 5),
)

// docs:end agent

var maxInputLength2000 = ankka.Guardrail("max-input-length",
	func(_ context.Context, stage ankka.GuardrailStage, text string) (string, error) {
		if stage == ankka.GuardrailInput && len(text) > 2000 {
			return "the instructions are longer than 2000 characters", nil
		}
		return "", nil
	})

// ── the operator: an autonomous agent whose tools wait for a person ──

// ServiceArguments name a service.
type ServiceArguments struct {
	Service string `json:"service" desc:"the service to act on"`
}

// NodeArguments name a node.
type NodeArguments struct {
	Node string `json:"node" desc:"the node to drain"`
}

// OperationTask is the operator's work: a text result.
var OperationTask = ankka.NewTaskType[string]("operation", "Carry out an operation on the cluster")

// docs:start autonomous-requires-approval

// Operator runs the cluster; a restart waits for a person, a drain waits two seconds at most.
var Operator = ankka.Autonomous("operator", "Operates the cluster",
	ankka.Tools(
		ankka.ApprovedTool("restart_service",
			"Restarts a service. An operator approves every restart.", 0,
			func(_ context.Context, in ServiceArguments) (string, error) {
				return "restarted " + in.Service, nil
			}),
		ankka.ApprovedTool("drain_node", "Drains a node of its work.", 2*time.Second,
			func(_ context.Context, in NodeArguments) (string, error) {
				return "drained " + in.Node, nil
			}),
		ankka.Tool("read_metrics", "Reads a service's metrics.",
			func(_ context.Context, in ServiceArguments) (string, error) {
				return in.Service + " is healthy", nil
			}),
	),
	ankka.Accepts(OperationTask, 5),
)

// docs:end autonomous-requires-approval

// decideForOperator sends a person's decision to the operator instance ops-1.
func decideForOperator(ctx context.Context, approvalID string) error {
	// docs:start decide
	return ankka.Instance("operator", "ops-1").
		Decide(ctx, approvalID, ankka.Decision{Approved: true, By: "dana"})
	// docs:end decide
}

// ── the questions endpoint: running a task and reading its result ──

// Asked is where to look for an answer.
type Asked struct {
	TaskID     string `json:"taskId"`
	InstanceID string `json:"instanceId"`
}

// Question is where a task has got to, with the answer once there is one.
type Question struct {
	TaskID     string  `json:"taskId"`
	Status     string  `json:"status"`
	Answer     *Answer `json:"answer"`
	Reason     string  `json:"reason"`
	Iterations int     `json:"iterations"`
}

// QuestionsEndpoint takes questions about carts, answered by an autonomous agent while the caller
// gets on with things.
var QuestionsEndpoint = ankka.NewEndpoint("/questions", ankka.AllowAll(),
	// docs:start run-task
	// Starts the work and answers at once, with where to look for the answer.
	ankka.Post("/ask", func(ctx context.Context, _ *ankka.Request, question string) (Asked, error) {
		taskID, err := ankka.RunSingleTask(ctx, "cart-answerer", AnswerTask, question,
			ankka.TaskOptions{})
		if err != nil {
			return Asked{}, err
		}
		task, err := ankka.GetTask(ctx, AnswerTask, taskID)
		if err != nil {
			return Asked{}, err
		}
		asked := Asked{TaskID: taskID}
		if task.Assignee != nil {
			asked.InstanceID = task.Assignee.InstanceID
		}
		return asked, nil
	}),
	// docs:end run-task
	// docs:start read-task
	// Where the task has got to, with the answer once there is one.
	ankka.Get("/{taskId}", func(ctx context.Context, req *ankka.Request) (Question, error) {
		task, err := ankka.GetTask(ctx, AnswerTask, req.Param("taskId"))
		if err != nil {
			return Question{}, err
		}
		return Question{TaskID: task.ID, Status: string(task.Status), Answer: task.Result,
			Reason: task.Reason, Iterations: task.Iterations}, nil
	}),
	// docs:end read-task
	// docs:start assign-tasks
	// Two questions for one instance, the second waiting on the first; they are worked in order.
	ankka.Post("/review", func(ctx context.Context, _ *ankka.Request, _ ankka.Unit) (ankka.Assigned, error) {
		first, err := ankka.CreateTask(ctx, AnswerTask, "What is in cart c-1?",
			ankka.TaskOptions{})
		if err != nil {
			return ankka.Assigned{}, err
		}
		second, err := ankka.CreateTask(ctx, AnswerTask, "And in c-2?",
			ankka.TaskOptions{DependsOn: []string{first}})
		if err != nil {
			return ankka.Assigned{}, err
		}
		return ankka.Instance("cart-answerer", "reviewer-1").Assign(ctx, first, second)
	}),
	// docs:end assign-tasks
	// docs:start notifications
	// What an answerer instance does, as it happens, as server-sent events. Each event's data is
	// the notification's JSON, as a JSON string, as every event's data is: a reader parses the
	// field, then the notification. Watching keeps the instance in memory while the stream is open.
	ankka.SSE("/answerer/{instanceId}/notifications",
		func(ctx context.Context, req *ankka.Request) iter.Seq2[string, error] {
			return func(yield func(string, error) bool) {
				instance := ankka.Instance("cart-answerer", req.Param("instanceId"))
				for n, err := range instance.Notifications(ctx) {
					if err != nil {
						yield("", err)
						return
					}
					text, _ := json.Marshal(n)
					if !yield(string(text), nil) {
						return
					}
				}
			}
		}),
	// docs:end notifications
)
