package ankka_test

import (
	"context"
	"errors"
	"strconv"
	"strings"
	"testing"
	"time"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

// ---- key value entities ----

type kCount struct {
	N int `json:"n"`
}

var kCounter = ankka.KeyValue("k-counter", func(string) kCount { return kCount{} },
	ankka.Update("add", func(_ context.Context, s kCount, by int) (ankka.KeyValueEffect[kCount, kCount], error) {
		if by == 0 {
			return ankka.KeyValueEffect[kCount, kCount]{}, ankka.Refuse(ankka.BadRequest, "add something")
		}
		s.N += by
		return ankka.UpdateState[kCount, kCount](s).ExpireAfter(time.Hour).ThenReplyState(), nil
	}),
	ankka.Update("reset", func(context.Context, kCount, ankka.Unit) (ankka.KeyValueEffect[kCount, ankka.Done], error) {
		return ankka.DeleteState[kCount, ankka.Done]().ThenReplyValue(ankka.Done{}), nil
	}),
	ankka.Query("get", func(_ context.Context, s kCount, _ ankka.Unit) (ankka.ReadOnlyEffect[int], error) {
		return ankka.Reply(s.N), nil
	}),
)

func TestTheKeyValueKitCarriesTheStateForward(t *testing.T) {
	ctx := context.Background()
	k := testkit.KeyValue(kCounter, testkit.EntityID("c1"))
	defer k.Close()
	if r := k.Command(ctx, "add", 2); r.Failure() != nil || r.Reply().(kCount).N != 2 || r.ExpireAfter() != time.Hour {
		t.Fatalf("add answered %v, %v", r.Reply(), r.Failure())
	}
	k.Command(ctx, "add", 3)
	if r := k.Query(ctx, "get", ankka.Unit{}); r.Reply() != 5 || k.State().N != 5 {
		t.Errorf("get answered %v with state %v", r.Reply(), k.State())
	}
	if r := k.Command(ctx, "add", 0); r.Refusal() == nil || r.Refusal().Code != ankka.BadRequest || k.State().N != 5 {
		t.Errorf("a refused add was %v and left %v", r.Refusal(), k.State())
	}
	if r := k.Query(ctx, "add", 1); r.Failure() == nil {
		t.Error("a command run as a query did not fail")
	}
	if r := k.Command(ctx, "reset", ankka.Unit{}); !r.Deleted() || k.State().N != 0 {
		t.Errorf("reset deleted %v and left %v", r.Deleted(), k.State())
	}
}

// ---- workflows ----

type kOrder struct {
	Status string `json:"status"`
	Amount int    `json:"amount"`
}

var kOrderFlow = ankka.Workflow("k-order", func(string) kOrder { return kOrder{Status: "new"} },
	ankka.WorkflowCommand("start", func(_ context.Context, o kOrder, amount int) (ankka.WorkflowEffect[kOrder, string], error) {
		if o.Status != "new" {
			return ankka.WorkflowEffect[kOrder, string]{}, ankka.Refuse(ankka.Conflict, "already started")
		}
		o.Status, o.Amount = "started", amount
		return ankka.WorkflowUpdate[kOrder, string](o).ThenTransitionTo("reserve", nil).ThenReplyValue("started"), nil
	}),
	ankka.Step("reserve", func(_ context.Context, o kOrder, _ ankka.Unit) (ankka.StepEffect[kOrder], error) {
		o.Status = "reserved"
		return ankka.StepUpdate(o).ThenTransitionTo("charge", o.Amount), nil
	}),
	ankka.Step("charge", func(_ context.Context, o kOrder, amount int) (ankka.StepEffect[kOrder], error) {
		if amount > 100 {
			return ankka.FailWorkflow[kOrder](ankka.BadRequest, "too much"), nil
		}
		o.Status = "charged"
		return ankka.StepUpdate(o).ThenPause(ankka.PauseOptions{After: time.Minute, OnTimeout: "cancel"}), nil
	}),
	ankka.Step("cancel", func(_ context.Context, o kOrder, _ ankka.Unit) (ankka.StepEffect[kOrder], error) {
		o.Status = "cancelled"
		return ankka.StepUpdate(o).ThenEnd(), nil
	}),
)

func TestTheWorkflowKitFollowsTransitionsToAPause(t *testing.T) {
	ctx := context.Background()
	k := testkit.Workflow(kOrderFlow)
	defer k.Close()
	r := k.Command(ctx, "start", 40)
	if r.Reply() != "started" || r.Transition().Step != "reserve" || k.Progress().Pending == nil {
		t.Fatalf("start answered %v, transition %v", r.Reply(), r.Transition())
	}
	p, err := k.RunUntilPause(ctx, 10)
	if err != nil || p.Paused == nil || p.Paused.After != time.Minute || p.Paused.OnTimeout.Step != "cancel" || k.State().Status != "charged" {
		t.Fatalf("the workflow stood %+v, %v, at %v", p, err, k.State())
	}
	if strings.Join(k.Steps, ",") != "reserve,charge" {
		t.Errorf("the steps run were %v", k.Steps)
	}
	if r := k.Command(ctx, "start", 1); r.Refusal() == nil {
		t.Error("a second start was not refused")
	}
	p, err = k.Resume(ctx)
	if err != nil || !p.Ended || p.Failed != nil || k.State().Status != "cancelled" {
		t.Errorf("after the timeout the workflow stood %+v, %v, at %v", p, err, k.State())
	}

	failing := testkit.Workflow(kOrderFlow)
	defer failing.Close()
	failing.Command(ctx, "start", 500)
	p, err = failing.RunUntilPause(ctx, 10)
	if err != nil || !p.Ended || p.Failed == nil || p.Failed.Message != "too much" {
		t.Errorf("a failed workflow stood %+v, %v", p, err)
	}
}

// ---- views ----

type kOrderRow struct {
	Status string `json:"status"`
	Step   string `json:"step"`
}

var kOrderRows = ankka.View[kOrderRow]("k-order-rows",
	ankka.OnChange(ankka.WorkflowSource(kOrderFlow), func(ctx context.Context, o kOrder, _ *kOrderRow) (ankka.ViewEffect[kOrderRow], error) {
		c, _ := ankka.ChangeFrom(ctx)
		if c.Standing == nil {
			return ankka.IgnoreChange[kOrderRow](), nil
		}
		if c.Standing.Terminal() {
			return ankka.DeleteRow[kOrderRow](), nil
		}
		return ankka.UpdateRow(kOrderRow{Status: o.Status, Step: c.Standing.Step}), nil
	}),
)

func TestTheViewKitTakesAWorkflowStanding(t *testing.T) {
	ctx := context.Background()
	k := testkit.View(kOrderRows)
	defer k.Close()
	ch, err := k.OnChange(ctx, "o1", kOrder{Status: "reserved"}, testkit.FromWorkflow(ankka.Standing{Status: "Running", Step: "charge"}))
	if err != nil || ch.Updated == nil {
		t.Fatalf("the change did %+v, %v", ch, err)
	}
	if row, ok := k.Row("o1"); !ok || row != (kOrderRow{Status: "reserved", Step: "charge"}) {
		t.Errorf("the row is %+v", row)
	}
	if ch, _ := k.OnChange(ctx, "o1", kOrder{Status: "reserved"}); !ch.Ignored {
		t.Errorf("a change with no standing did %+v", ch)
	}
	if ch, _ := k.OnChange(ctx, "o1", kOrder{Status: "charged"}, testkit.FromWorkflow(ankka.Standing{Status: "Completed"})); !ch.Deleted {
		t.Errorf("a completed workflow's change did %+v", ch)
	}
	if _, ok := k.Row("o1"); ok {
		t.Error("the row was not deleted")
	}
	if _, err := k.OnChange(ctx, "o1", "not an order"); err == nil {
		t.Error("a change of the wrong type was accepted")
	}
}

type kLine struct {
	Cart    string `json:"cart"`
	Product string `json:"product"`
	Status  string `json:"status"`
}

var kLines = ankka.KeyedView[kLine]("k-lines",
	ankka.On(ankka.Changes(kEntity), func(ctx context.Context, e kEvent, rows ankka.ViewRows[kLine]) (ankka.KeyedViewEffect[kLine], error) {
		c, _ := ankka.ChangeFrom(ctx)
		switch e := e.(type) {
		case kAdded:
			return ankka.UpdateRowAt(c.Subject+"/"+e.Item.ProductID, kLine{Cart: c.Subject, Product: e.Item.ProductID, Status: "open"}), nil
		}
		theirs, err := rows.Ask("of-cart", map[string]string{"cart": c.Subject})
		if err != nil {
			return ankka.KeyedViewEffect[kLine]{}, err
		}
		var all []ankka.KeyedViewEffect[kLine]
		for _, l := range theirs {
			all = append(all, ankka.DeleteRowAt[kLine](l.Cart+"/"+l.Product))
		}
		return ankka.AllRows(all...), nil
	}),
	ankka.On(ankka.Changes(kCounter), func(ctx context.Context, n kCount, rows ankka.ViewRows[kLine]) (ankka.KeyedViewEffect[kLine], error) {
		c, _ := ankka.ChangeFrom(ctx)
		l, ok, err := rows.Get(c.Subject + "/pen")
		if err != nil || !ok {
			return ankka.IgnoreRows[kLine](), err
		}
		l.Status = "counted " + strconv.Itoa(n.N)
		return ankka.UpdateRowAt(c.Subject+"/pen", l), nil
	}),
	ankka.Queries(ankka.Declared("of-cart", "SELECT payload FROM "+ankka.TableOf("k-lines")+" WHERE payload->>'cart' = :cart")),
)

func TestTheKeyedViewKitKeepsRowsByKey(t *testing.T) {
	ctx := context.Background()
	k := testkit.KeyedView(kLines)
	defer k.Close()
	written, _, err := k.Change(ctx, "k-cart", "c1", kAdded{kItem{"pen", 1}})
	if err != nil || strings.Join(written, ",") != "c1/pen" {
		t.Fatalf("the change wrote %v, %v", written, err)
	}
	k.Change(ctx, "k-cart", "c1", kAdded{kItem{"ink", 1}})
	if _, _, err := k.Change(ctx, "k-counter", "c1", kCount{N: 3}); err != nil {
		t.Fatal(err)
	}
	if l, _ := k.Row("c1/pen"); l.Status != "counted 3" {
		t.Errorf("the pen's row is %+v", l)
	}
	if _, _, err := k.Change(ctx, "k-cart", "c1", kCleared{}); err == nil || !strings.Contains(err.Error(), `Answering("of-cart"`) {
		t.Errorf("an unanswered query was %v", err)
	}
	k.Answering("of-cart", func(values map[string]string) []kLine {
		var out []kLine
		for _, l := range k.Rows() {
			if l.Cart == values["cart"] {
				out = append(out, l)
			}
		}
		return out
	})
	_, deleted, err := k.Change(ctx, "k-cart", "c1", kCleared{})
	if err != nil || len(deleted) != 2 || len(k.Rows()) != 0 {
		t.Errorf("clearing deleted %v, %v, leaving %v", deleted, err, k.Rows())
	}
	if _, _, err := k.Change(ctx, "k-elsewhere", "c1", kCleared{}); err == nil {
		t.Error("a change from a source the view does not read was accepted")
	}
}

// ---- consumers ----

type kNotice struct {
	Cart    string `json:"cart"`
	Product string `json:"product"`
}

var kNotifier = ankka.Consumer("k-notifier", ankka.Changes(kEntity), func(ctx context.Context, e kEvent) (ankka.ConsumerEffect, error) {
	c, _ := ankka.ChangeFrom(ctx)
	a, ok := e.(kAdded)
	if !ok {
		return ankka.ConsumerIgnore(), nil
	}
	if a.Item.ProductID == "both" {
		return ankka.ProduceAll(
			ankka.Message{Payload: kNotice{c.Subject, "one"}, Key: "k1"},
			ankka.Message{Payload: kNotice{c.Subject, "two"}, Metadata: ankka.Metadata{"ce-type": "notice"}},
		), nil
	}
	if a.Item.ProductID == "fail" {
		return ankka.ConsumerEffect{}, errors.New("the mail server is down")
	}
	return ankka.Produce(kNotice{c.Subject, a.Item.ProductID}), nil
}, ankka.ProducesTo[kNotice]("notices"))

func TestTheConsumerKitShowsWhatWasPublishedAndItsKeys(t *testing.T) {
	ctx := context.Background()
	k := testkit.Consumer(kNotifier)
	defer k.Close()
	if out, err := k.OnMessage(ctx, "c1", kAdded{kItem{"pen", 1}}); err != nil || len(out) != 1 || out[0].Key != "c1" || out[0].Payload != (kNotice{"c1", "pen"}) {
		t.Fatalf("one message published %+v, %v", out, err)
	}
	out, err := k.OnMessage(ctx, "c2", kAdded{kItem{"both", 1}})
	if err != nil || len(out) != 2 || out[0].Key != "k1" || out[1].Key != "c2" || out[1].Metadata["ce-type"] != "notice" {
		t.Fatalf("two messages published %+v, %v", out, err)
	}
	if out, _ := k.OnMessage(ctx, "c1", kCleared{}); len(out) != 0 {
		t.Errorf("an ignored change published %v", out)
	}
	if _, err := k.OnMessage(ctx, "c1", kAdded{kItem{"fail", 1}}); err == nil || !strings.Contains(err.Error(), "mail server") {
		t.Errorf("a failed change was %v", err)
	}
	if len(k.Published()) != 3 {
		t.Errorf("everything published was %+v", k.Published())
	}
}

var kGraph = ankka.GraphConsumer("k-graph", ankka.Changes(kEntity), "cart-graph", func(ctx context.Context, e kEvent) (ankka.GraphEffect, error) {
	c, _ := ankka.ChangeFrom(ctx)
	a, ok := e.(kAdded)
	if !ok {
		return ankka.GraphIgnore(), nil
	}
	return ankka.Publish(
		ankka.Node("cart:"+c.Subject, ankka.Labels("Cart")),
		ankka.Edge("holds:"+c.Subject+":"+a.Item.ProductID, "HOLDS", "cart:"+c.Subject, "product:"+a.Item.ProductID, ankka.WithProperties(ankka.Properties{"quantity": a.Item.Quantity})),
	), nil
}, ankka.WhenDeleted(func(ctx context.Context) (ankka.GraphEffect, error) {
	c, _ := ankka.ChangeFrom(ctx)
	return ankka.Publish(ankka.NodeTombstone("cart:" + c.Subject)), nil
}))

func TestTheGraphConsumerKitShowsItsElements(t *testing.T) {
	ctx := context.Background()
	k := testkit.GraphConsumer(kGraph)
	defer k.Close()
	out, err := k.OnMessage(ctx, "c1", kAdded{kItem{"pen", 2}}, testkit.Sequence(7))
	if err != nil || len(out) != 2 {
		t.Fatalf("the change published %+v, %v", out, err)
	}
	if out[0].Kind != "node" || out[0].Key() != "node:cart:c1" || out[0].Version != 7 || out[0].Labels[0] != "Cart" {
		t.Errorf("the node was %+v", out[0])
	}
	if out[1].Kind != "edge" || out[1].From != "cart:c1" || out[1].Properties["quantity"] != int64(2) {
		t.Errorf("the edge was %+v", out[1])
	}
	gone, err := k.OnDelete(ctx, "c1", testkit.Sequence(8))
	if err != nil || len(gone) != 1 || gone[0].Kind != "tombstone" || gone[0].Element != "node" {
		t.Errorf("the deletion published %+v, %v", gone, err)
	}
	if len(k.Elements()) != 3 {
		t.Errorf("every element was %+v", k.Elements())
	}
}

// ---- timed actions ----

var kSeenTimer ankka.TimerScope

var kReminder = ankka.TimedAction("k-reminder",
	ankka.Action("remind", func(ctx context.Context, cart string) (ankka.ActionEffect, error) {
		kSeenTimer, _ = ankka.TimerFrom(ctx)
		if cart == "" {
			return ankka.ActionFail(ankka.BadRequest, "no cart"), nil
		}
		// The action calls the cart it reminds about, through the kit's client.
		total, err := ankka.Invoke[int](ctx, kEntity.ID(), cart, "total", ankka.Unit{})
		if err != nil {
			return ankka.ActionEffect{}, err
		}
		if total == 0 {
			return ankka.ActionFail(ankka.NotFound, "an empty cart"), nil
		}
		return ankka.ActionDone(), nil
	}),
)

func TestTheTimedActionKitCallsAsTheSweeperWould(t *testing.T) {
	ctx := context.Background()
	k := testkit.TimedAction(kReminder, testkit.Calling(kEntity))
	defer k.Close()
	if err := k.Invoke(ctx, "remind", "c1", ankka.Metadata{"ankka.attempts": "3"}); err == nil || !strings.Contains(err.Error(), "an empty cart") {
		t.Errorf("a reminder of an empty cart was %v", err)
	}
	if kSeenTimer.Name != "test" || kSeenTimer.Attempts != 3 {
		t.Errorf("the action saw the timer %+v", kSeenTimer)
	}
	var r *ankka.RefusalError
	if err := k.Invoke(ctx, "remind", ""); !errors.As(err, &r) || r.Code != ankka.BadRequest {
		t.Errorf("a failed action was %v", err)
	}
}

// ---- agents ----

type kLookup struct {
	CartID string `json:"cartId" desc:"The cart to look up"`
}

var kAssistant = ankka.Agent("k-assistant",
	ankka.MaxToolCallSteps(3),
	ankka.Ask("ask", func(ctx context.Context, q string) (ankka.AgentEffect, error) {
		if q == "refuse" {
			return ankka.AgentError(ankka.Forbidden, "not for you"), nil
		}
		return ankka.SystemMessage("You help with carts.").UserMessage(q).Tools("lookup", "clear").Guardrails("no-keys").ThenReply(), nil
	}),
	ankka.AskStream("chat", func(ctx context.Context, q string) (ankka.AgentEffect, error) {
		return ankka.UserMessage(q).ThenReply(), nil
	}),
	ankka.Tools(
		ankka.Tool("lookup", "Looks a cart up", func(ctx context.Context, in kLookup) (string, error) {
			if in.CartID == "missing" {
				return "", errors.New("no such cart")
			}
			total, err := ankka.Invoke[int](ctx, kEntity.ID(), in.CartID, "total", ankka.Unit{})
			return in.CartID + " holds " + strconv.Itoa(total), err
		}),
		ankka.ApprovedTool("clear", "Empties a cart", time.Minute, func(ctx context.Context, in kLookup) (string, error) {
			return "cleared " + in.CartID, nil
		}),
	),
	ankka.Guardrails(ankka.Guardrail("no-keys", func(ctx context.Context, stage ankka.GuardrailStage, text string) (string, error) {
		if strings.Contains(text, "sk-") {
			return "a key", nil
		}
		return "", nil
	})),
)

func TestTheAgentKitRunsTheLoopAgainstTheScriptedModel(t *testing.T) {
	ctx := context.Background()
	model := testkit.NewScriptedModel().
		ExpectToolCall("lookup", kLookup{CartID: "c1"}).
		ExpectToolCall("lookup", kLookup{CartID: "missing"}).
		ExpectText("c1 is empty")
	k := testkit.Agent(kAssistant, "s1", model, testkit.AgentCalling(kEntity))
	defer k.Close()
	text, err := k.Ask(ctx, "ask", "what is in c1?")
	if err != nil || text != "c1 is empty" {
		t.Fatalf("the turn answered %q, %v", text, err)
	}
	last := model.Calls[2]
	if last.System != "You help with carts." || len(last.Messages) != 5 || last.Messages[2].Text != "c1 holds 0" || last.Messages[4].Text != "error: no such cart" {
		t.Errorf("the model was last shown %+v", last)
	}
	if strings.Join(last.Tools, ",") != "lookup,clear" {
		t.Errorf("the model was offered %v", last.Tools)
	}

	// The session remembers the turn.
	model.ExpectText("still empty")
	if _, err := k.Ask(ctx, "ask", "and now?"); err != nil || len(model.Calls[3].Messages) != 3 || len(k.History) != 4 {
		t.Errorf("the second turn saw %+v (%v), history %v", model.Calls[3].Messages, err, k.History)
	}

	// A guardrail blocks the input; the agent's own refusal is a refusal.
	var r *ankka.RefusalError
	if _, err := k.Ask(ctx, "ask", "here is sk-123"); !errors.As(err, &r) || r.Code != ankka.Forbidden || !strings.Contains(r.Message, "no-keys") {
		t.Errorf("a key in the input was %v", err)
	}
	if _, err := k.Ask(ctx, "ask", "refuse"); !errors.As(err, &r) || r.Message != "not for you" {
		t.Errorf("the agent's refusal was %v", err)
	}

	// The script running out fails the turn, loudly.
	if _, err := k.Ask(ctx, "ask", "again"); err == nil || !strings.Contains(err.Error(), "ran out") {
		t.Errorf("an empty script was %v", err)
	}
}

func TestTheAgentKitWaitsForApproval(t *testing.T) {
	ctx := context.Background()
	model := testkit.NewScriptedModel().ExpectToolCall("clear", kLookup{CartID: "c9"}).ExpectText("done")
	k := testkit.Agent(kAssistant, "s2", model)
	defer k.Close()
	_, err := k.Ask(ctx, "ask", "clear c9")
	var waiting *ankka.ApprovalAwaitedError
	if !errors.As(err, &waiting) || waiting.Requests[0].Tool != "clear" || waiting.Requests[0].ExpiresAt == nil {
		t.Fatalf("the turn was %v", err)
	}
	if _, err := k.Ask(ctx, "ask", "something else"); err == nil {
		t.Error("a second turn while one waits was accepted")
	}
	text, err := k.Decide(ctx, waiting.Requests[0].ID, true, "ada", "")
	if err != nil || text != "done" || model.Calls[1].Messages[2].Text != "cleared c9" {
		t.Errorf("approved, the turn answered %q, %v, the model saw %+v", text, err, model.Calls[1].Messages)
	}
	if _, err := k.Decide(ctx, waiting.Requests[0].ID, true, "ada", ""); err == nil {
		t.Error("a request was decided twice")
	}

	model.ExpectToolCall("clear", kLookup{CartID: "c9"}).ExpectText("left alone")
	_, err = k.Ask(ctx, "ask", "clear c9")
	errors.As(err, &waiting)
	if _, err := k.Decide(ctx, waiting.Requests[0].ID, false, "bob", "not today"); err != nil || !strings.Contains(model.Calls[3].Messages[len(model.Calls[3].Messages)-1].Text, "(bob) refused") {
		t.Errorf("refused, the turn was %v, the model saw %+v", err, model.Calls[3].Messages)
	}

	parts, err := testkit.Agent(kAssistant, "s3", testkit.NewScriptedModel().ExpectText("one two three")).Stream(ctx, "chat", "count")
	if err != nil || strings.Join(parts, "|") != "one |two |three" {
		t.Errorf("the stream was %q, %v", parts, err)
	}
}

// ---- MCP endpoints ----

var kMcp = ankka.NewMcpEndpoint("/mcp", ankka.AllowAll(),
	ankka.McpTool("whoami", "Says who called", func(ctx context.Context, _ ankka.Unit) (string, error) {
		if p := ankka.RequestFrom(ctx).Principal; p != nil {
			return p.Subject, nil
		}
		return "", ankka.Refuse(ankka.Unauthorized, "nobody")
	}),
	ankka.McpCommandRef("add", "Adds to a counter", "k-counter", "add", "counterId", ankka.InputArgument("by")),
	ankka.McpQueryRef("total", "A cart's total", "k-cart", "total", "cartId"),
)

func TestTheMcpKitRunsToolsAndTheirReferences(t *testing.T) {
	ctx := context.Background()
	k := testkit.Mcp(kMcp, kCounter, kEntity)
	defer k.Close()
	if a, err := k.Call(ctx, "whoami", nil, &ankka.Principal{Subject: "ada"}); err != nil || a.Text != "ada" {
		t.Errorf("whoami answered %+v, %v", a, err)
	}
	if _, err := k.Call(ctx, "whoami", nil, nil); err == nil || !strings.Contains(err.Error(), "nobody") {
		t.Errorf("whoami with no principal was %v", err)
	}
	k.Call(ctx, "add", map[string]any{"counterId": "n1", "by": 2}, nil)
	a, err := k.Call(ctx, "add", `{"counterId":"n1","by":3}`, nil)
	if err != nil || a.Text != `{"n":5}` || a.StructuredJSON != a.Text {
		t.Errorf("add answered %+v, %v", a, err)
	}
	if a, err := k.Call(ctx, "total", map[string]string{"cartId": "c1"}, nil); err != nil || a.Text != "0" {
		t.Errorf("total answered %+v, %v", a, err)
	}
}
