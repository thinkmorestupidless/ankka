package samples

import (
	"encoding/json"
	"testing"
	"time"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/cart"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

var pen = cart.LineItem{ProductID: "pen", Name: "Pen", Quantity: 2}
var ink = cart.LineItem{ProductID: "ink", Name: "Ink", Quantity: 1}

// docs:start scripted-autonomous

// The sidecar's model, scripted: a tool call, an answer that cites nothing, which the rule in this
// process sends back, then the answer the task completes with.
const answerScript = `[
  {"tool": "cart_total", "arguments": {"cartId": "c-1"}},
  {"tool": "complete_task", "arguments": {"answer": "3 items", "sources": []}},
  {"tool": "complete_task", "arguments": {"answer": "Cart c-1 holds 3 items.", "sources": ["cart_total"]}}
]`

func TestATaskIsWorkedThroughTheSidecar(t *testing.T) {
	kit := testkit.StartT(t, ankka.NewService(cart.Entity, cart.API, CartAnswerer, Operator, QuestionsEndpoint),
		testkit.Env(map[string]string{"ANKKA_MODEL_SCRIPT": scripts(answerScript, operatorScript)}))
	ctx, h := t.Context(), kit.HTTP()
	for _, item := range []cart.LineItem{pen, ink} {
		if r, err := h.Post(ctx, "/carts/c-1/items", item); err != nil || r.Status != 204 {
			t.Fatalf("adding an item: %v, %v", r, err)
		}
	}

	asked, err := h.Post(ctx, "/questions/ask", "How many items are in cart c-1?")
	if err != nil || asked.Status != 200 {
		t.Fatalf("asking answered %v, %v", asked, err)
	}
	var where Asked
	if err := json.Unmarshal(asked.Body, &where); err != nil {
		t.Fatal(err)
	}

	// The loop runs in the sidecar; the tool runs here and reads the cart; the typed result is
	// read back through the task's record.
	done, err := ankka.WaitForTask(kit.Context(ctx), AnswerTask, where.TaskID, time.Minute)
	if err != nil || done.Status != ankka.TaskCompleted {
		t.Fatalf("the task ended %+v, %v\n%s", done, err, kit.SidecarLogs(ctx))
	}
	if done.Result.Answer != "Cart c-1 holds 3 items." || done.Iterations != 3 {
		t.Fatalf("the task's result is %+v after %d iterations", *done.Result, done.Iterations)
	}
	read, err := testkit.GetJSON[Question](ctx, h, "/questions/"+where.TaskID)
	if err != nil || read.Status != "completed" || read.Answer.Sources[0] != "cart_total" {
		t.Fatalf("reading the task answered %+v, %v", read, err)
	}

	// docs:end scripted-autonomous
	operatorWaitsForAPerson(t, kit)
}

// The operator's model: a restart, which waits for a person, then the task completed.
const operatorScript = `[
  {"tool": "restart_service", "arguments": {"service": "checkout"}},
  {"tool": "complete_task", "arguments": {"result": "checkout restarted"}}
]`

// scripts is the JSON arrays joined into one script, consumed in order.
func scripts(parts ...string) string {
	var all []json.RawMessage
	for _, p := range parts {
		var entries []json.RawMessage
		if err := json.Unmarshal([]byte(p), &entries); err != nil {
			panic(err)
		}
		all = append(all, entries...)
	}
	out, _ := json.Marshal(all)
	return string(out)
}

func operatorWaitsForAPerson(t *testing.T, kit *testkit.Kit) {
	t.Helper()
	ctx := kit.Context(t.Context())
	taskID, err := ankka.CreateTask(ctx, OperationTask, "Restart the checkout service.", ankka.TaskOptions{})
	if err != nil {
		t.Fatal(err)
	}
	ops := ankka.Instance("operator", "ops-1")
	if assigned, err := ops.Assign(ctx, taskID); err != nil || len(assigned.Accepted) != 1 {
		t.Fatalf("assigning answered %+v, %v", assigned, err)
	}
	var awaiting []ankka.ApprovalRequest
	deadline := time.Now().Add(30 * time.Second)
	for len(awaiting) == 0 && time.Now().Before(deadline) {
		time.Sleep(200 * time.Millisecond)
		state, err := ops.State(ctx)
		if err != nil {
			t.Fatal(err)
		}
		awaiting = state.Awaiting
	}
	if len(awaiting) != 1 || awaiting[0].Tool != "restart_service" {
		t.Fatalf("the instance awaits %+v\n%s", awaiting, kit.SidecarLogs(ctx))
	}
	if err := decideForOperator(ctx, awaiting[0].ID); err != nil {
		t.Fatal(err)
	}
	done, err := ankka.WaitForTask(ctx, OperationTask, taskID, time.Minute)
	if err != nil || done.Status != ankka.TaskCompleted || *done.Result != "checkout restarted" {
		t.Fatalf("the operation ended %+v, %v\n%s", done, err, kit.SidecarLogs(ctx))
	}
}
