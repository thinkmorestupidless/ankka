package ankka

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc"
)

type tOrder struct {
	ID     string `json:"id"`
	Status string `json:"status"`
	Amount int    `json:"amount"`
}

// tRelease holds the reserve step until the test lets it go.
var tRelease = make(chan struct{})

var tOrderFlow = Workflow[tOrder]("t-order", func(id string) tOrder { return tOrder{ID: id, Status: "new"} },
	WorkflowCommand("start", func(_ context.Context, o tOrder, amount int) (WorkflowEffect[tOrder, string], error) {
		if o.Status != "new" {
			return WorkflowEffect[tOrder, string]{}, Refuse(Conflict, "already started")
		}
		o.Status, o.Amount = "started", amount
		return WorkflowUpdate[tOrder, string](o).ThenTransitionTo("reserve", nil).ThenReplyValue("started"), nil
	}),
	WorkflowCommand("peek-secret", func(ctx context.Context, o tOrder, _ Unit) (WorkflowEffect[tOrder, string], error) {
		_, _, err := Secrets(ctx).Get("k")
		return WorkflowEffect[tOrder, string]{}, err
	}),
	Query("status", func(_ context.Context, o tOrder, _ Unit) (ReadOnlyEffect[string], error) {
		return Reply(o.Status), nil
	}),
	Step("reserve", func(ctx context.Context, o tOrder, _ Unit) (StepEffect[tOrder], error) {
		select {
		case <-tRelease:
		case <-ctx.Done():
			return StepEffect[tOrder]{}, ctx.Err()
		}
		o.Status = "reserved"
		return StepUpdate(o).ThenTransitionTo("charge", o.Amount), nil
	}),
	Step("charge", func(_ context.Context, o tOrder, amount int) (StepEffect[tOrder], error) {
		if amount > 100 {
			return FailWorkflow[tOrder](BadRequest, "too much"), nil
		}
		if amount == 13 {
			panic("unlucky")
		}
		o.Status = "charged"
		return StepUpdate(o).ThenPause(PauseOptions{After: time.Minute, OnTimeout: "cancel"}), nil
	}),
	Step("cancel", func(_ context.Context, o tOrder, _ Unit) (StepEffect[tOrder], error) {
		o.Status = "cancelled"
		return StepUpdate(o).ThenEnd(), nil
	}),
	StepTimeout(10*time.Second),
	StepRecovery(Recovery{MaxRetries: 2, FailoverTo: "cancel"}),
	WorkflowTimeout(time.Hour),
	ForStep("charge", StepTimeout(3*time.Second), StepRecovery(Recovery{MaxRetries: 0})),
)

func wfOpen(t *testing.T, conn *grpc.ClientConn, id string) pb.Workflow_HandleClient {
	t.Helper()
	s, err := pb.NewWorkflowClient(conn).Handle(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if err := s.Send(&pb.WorkflowIn{Message: &pb.WorkflowIn_Init_{Init: &pb.WorkflowIn_Init{ComponentId: "t-order", EntityId: id}}}); err != nil {
		t.Fatal(err)
	}
	return s
}

func wfSend(t *testing.T, s pb.Workflow_HandleClient, m *pb.WorkflowIn) {
	t.Helper()
	if err := s.Send(m); err != nil {
		t.Fatal(err)
	}
}

func cmdIn(t *testing.T, id int64, name string, input any) *pb.WorkflowIn {
	t.Helper()
	p, err := encodeAny(input)
	if err != nil {
		t.Fatal(err)
	}
	return &pb.WorkflowIn{Message: &pb.WorkflowIn_Command_{Command: &pb.WorkflowIn_Command{Id: id, Name: name, Payload: p}}}
}

func stepIn(t *testing.T, id int64, step string, input any) *pb.WorkflowIn {
	t.Helper()
	run := &pb.WorkflowIn_RunStep{Id: id, Step: step}
	if input != nil {
		p, err := encodeAny(input)
		if err != nil {
			t.Fatal(err)
		}
		run.Input = p
	}
	return &pb.WorkflowIn{Message: &pb.WorkflowIn_RunStep_{RunStep: run}}
}

func wfRecv(t *testing.T, s pb.Workflow_HandleClient) *pb.WorkflowOut {
	t.Helper()
	out, err := s.Recv()
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func TestAWorkflowConversation(t *testing.T) {
	svc := NewService(tOrderFlow)
	if p := svc.Problems(); len(p) > 0 {
		t.Fatalf("problems: %v", p)
	}
	s := wfOpen(t, loopback(t, svc), "o1")

	wfSend(t, s, cmdIn(t, 1, "start", 40))
	start := wfRecv(t, s).GetReply()
	if start.GetCommandId() != 1 || start.GetTransition().GetStep() != "reserve" || start.GetTransition().Input != nil ||
		string(start.GetNewState().GetData()) != `{"id":"o1","status":"started","amount":40}` {
		t.Fatalf("start answered %v", start)
	}

	// The step blocks; a query is answered meanwhile, from the state before the step.
	wfSend(t, s, stepIn(t, 2, "reserve", nil))
	wfSend(t, s, cmdIn(t, 3, "status", Unit{}))
	if q := wfRecv(t, s).GetReply(); q.GetCommandId() != 3 || string(q.GetOutcome().GetReply().GetPayload().GetData()) != "started" {
		t.Fatalf("the mid-step query answered %v", q)
	}
	// A second step beside the first is refused, and the first carries on.
	wfSend(t, s, stepIn(t, 4, "cancel", nil))
	if f := wfRecv(t, s).GetFailure(); f.GetCommandId() != 4 || !strings.Contains(f.GetError().GetMessage(), "already running") {
		t.Fatalf("a second step answered %v", f)
	}
	tRelease <- struct{}{}
	reserved := wfRecv(t, s).GetStepReply()
	if reserved.GetCommandId() != 2 || reserved.GetNext().GetTransitionTo().GetStep() != "charge" ||
		string(reserved.GetNext().GetTransitionTo().GetInput().GetData()) != "40" ||
		!strings.Contains(string(reserved.GetNewState().GetData()), `"reserved"`) {
		t.Fatalf("reserve answered %v", reserved)
	}

	wfSend(t, s, stepIn(t, 5, "charge", 40))
	pause := wfRecv(t, s).GetStepReply().GetNext().GetPause()
	if pause.GetAfterMillis() != 60000 || pause.GetOnTimeout().GetStep() != "cancel" {
		t.Fatalf("charge paused as %v", pause)
	}
	wfSend(t, s, cmdIn(t, 6, "status", Unit{}))
	if q := wfRecv(t, s).GetReply(); string(q.GetOutcome().GetReply().GetPayload().GetData()) != "charged" {
		t.Fatalf("after the step the status is %v", q)
	}
	// A refusal changes nothing; a decision to fail is a reply; a panic is a fault.
	wfSend(t, s, cmdIn(t, 7, "start", 1))
	if r := wfRecv(t, s).GetReply(); r.GetOutcome().GetError().GetCode() != pb.ErrorCode_CONFLICT || r.NewState != nil || r.Transition != nil {
		t.Fatalf("a refused start answered %v", r)
	}
	wfSend(t, s, stepIn(t, 8, "charge", 500))
	if f := wfRecv(t, s).GetStepReply().GetNext().GetFail(); f.GetCode() != pb.ErrorCode_BAD_REQUEST || f.GetMessage() != "too much" {
		t.Fatalf("a failing charge answered %v", f)
	}
	wfSend(t, s, stepIn(t, 9, "charge", 13))
	if f := wfRecv(t, s).GetFailure(); f.GetCommandId() != 9 || !strings.Contains(f.GetError().GetMessage(), "unlucky") {
		t.Fatalf("a panicking step answered %v", f)
	}
	wfSend(t, s, stepIn(t, 10, "cancel", nil))
	if e := wfRecv(t, s).GetStepReply(); e.GetNext().GetEnd() == nil {
		t.Fatalf("cancel answered %v", e)
	}
	// A command is not offered the secret store.
	wfSend(t, s, cmdIn(t, 11, "peek-secret", Unit{}))
	if f := wfRecv(t, s).GetFailure(); !strings.Contains(f.GetError().GetMessage(), "not offered to a workflow's command") {
		t.Fatalf("a command reading a secret answered %v", f)
	}
}

func TestAStepStillRunningSeesTheConversationEnd(t *testing.T) {
	conn := loopback(t, NewService(tOrderFlow))
	ctx, cancel := context.WithCancel(context.Background())
	s, err := pb.NewWorkflowClient(conn).Handle(ctx)
	if err != nil {
		t.Fatal(err)
	}
	wfSend(t, s, &pb.WorkflowIn{Message: &pb.WorkflowIn_Init_{Init: &pb.WorkflowIn_Init{ComponentId: "t-order", EntityId: "o2"}}})
	wfSend(t, s, stepIn(t, 1, "reserve", nil))
	// Closing the stream with the step blocked must not leave the server waiting on it: the
	// cleanup's Serve returns within its one-second grace only if the step is cancelled.
	time.Sleep(50 * time.Millisecond)
	cancel()
}

func TestAWorkflowDeclaresItsStepsAndSettings(t *testing.T) {
	c := NewService(tOrderFlow).Spec().GetComponents()[0]
	d := c.GetWorkflow()
	if c.GetKind() != pb.Kind_WORKFLOW || strings.Join(d.GetSteps(), ",") != "reserve,charge,cancel" {
		t.Fatalf("declared %v", c)
	}
	st := d.GetSettings()
	if st.GetTimeoutMillis() != 3600000 || st.GetDefaultStepTimeoutMillis() != 10000 ||
		st.GetDefaultRecovery().GetMaxRetries() != 2 || st.GetDefaultRecovery().GetFailoverTo() != "cancel" {
		t.Fatalf("settings %v", st)
	}
	if len(st.GetSteps()) != 1 || st.GetSteps()[0].GetStep() != "charge" || st.GetSteps()[0].GetTimeoutMillis() != 3000 ||
		st.GetSteps()[0].GetRecovery().GetMaxRetries() != 0 || st.GetSteps()[0].GetRecovery().FailoverTo != nil {
		t.Fatalf("per-step settings %v", st.GetSteps())
	}
	none := Workflow[tOrder]("plain", func(string) tOrder { return tOrder{} },
		WorkflowCommand("go", func(context.Context, tOrder, Unit) (WorkflowEffect[tOrder, Done], error) {
			return WorkflowKeep[tOrder, Done]().ThenReplyValue(Done{}), nil
		}),
		Step("only", func(_ context.Context, o tOrder, _ Unit) (StepEffect[tOrder], error) { return End[tOrder](), nil }))
	if NewService(none).Spec().GetComponents()[0].GetWorkflow().Settings != nil {
		t.Fatal("a workflow that says nothing declares settings")
	}
}

func TestAWorkflowsProblemsAreNamed(t *testing.T) {
	noop := func(_ context.Context, o tOrder, _ Unit) (StepEffect[tOrder], error) { return End[tOrder](), nil }
	bad := NewService(Workflow[tOrder]("w", nil,
		Step("a", noop), Step("a", noop),
		StepRecovery(Recovery{MaxRetries: -1, FailoverTo: "nowhere"}),
		StepTimeout(0),
		ForStep("ghost", StepTimeout(time.Second)),
		SnapshotEvery(2),
	))
	text := ""
	for _, p := range bad.Problems() {
		text += p.String() + "\n"
	}
	for _, want := range []string{
		"empty state", `2 steps share the name "a"`, "retries 0 times or more", `fails over to "nowhere"`,
		"more than zero", `ForStep names "ghost"`, "at least one command", "not an option a workflow takes",
	} {
		if !strings.Contains(text, want) {
			t.Errorf("problems do not say %q:\n%s", want, text)
		}
	}
	if !strings.Contains(strings.Join(serviceProblems(NewService(Workflow[tOrder]("w2", func(string) tOrder { return tOrder{} }))), "\n"), "at least one step") {
		t.Error("a workflow with no step is not a problem")
	}
}

func serviceProblems(s *Service) []string {
	var out []string
	for _, p := range s.Problems() {
		out = append(out, p.String())
	}
	return out
}
