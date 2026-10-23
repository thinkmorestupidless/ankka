package ankka

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

type tItem struct {
	ProductID string `json:"productId"`
	Quantity  int    `json:"quantity"`
}

type tState struct {
	Items []tItem `json:"items"`
}

type tEvent interface{ tEvent() }

type tAdded struct {
	Item tItem `json:"item"`
}

func (tAdded) tEvent() {}

var tEvents = Cases[tEvent](Case[tEvent, tAdded]("ItemAdded"))

func tApply(s tState, e tEvent) tState {
	if a, ok := e.(tAdded); ok {
		s.Items = append(append([]tItem(nil), s.Items...), a.Item)
	}
	return s
}

func tTotal(s tState) int {
	n := 0
	for _, i := range s.Items {
		n += i.Quantity
	}
	return n
}

var tCartEntity = EventSourced[tState, tEvent]("t-cart", tEvents, func() tState { return tState{} }, tApply,
	Command("add-item", func(ctx context.Context, s tState, item tItem) (Effect[tState, tEvent, int], error) {
		if item.Quantity <= 0 {
			return Effect[tState, tEvent, int]{}, Refuse(BadRequest, "quantity must be positive")
		}
		if item.ProductID == "boom" {
			panic("the handler broke")
		}
		return Persist[tState, tEvent, int](tAdded{item}).ThenReply(tTotal), nil
	}),
	Command("fail", func(ctx context.Context, s tState, _ Unit) (Effect[tState, tEvent, Done], error) {
		return Effect[tState, tEvent, Done]{}, errors.New("the disk is full")
	}),
	Query("total", func(ctx context.Context, s tState, _ Unit) (ReadOnlyEffect[int], error) {
		return Reply(tTotal(s)), nil
	}),
	Query("scope", func(ctx context.Context, s tState, _ Unit) (ReadOnlyEffect[string], error) {
		c, _ := CommandFrom(ctx)
		return Reply(c.EntityID + "@" + string(rune('0'+c.Sequence))), nil
	}),
	SnapshotEvery(3),
)

// conversation serves svc on loopback and opens one event sourced stream.
func conversation(t *testing.T, svc *Service) pb.EventSourced_HandleClient {
	t.Helper()
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	served := make(chan error, 1)
	go func() {
		served <- svc.Serve(ctx, lis, Logger(slog.New(slog.DiscardHandler)), GracePeriod(time.Second))
	}()
	conn, err := grpc.NewClient(lis.Addr().String(), grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close(); cancel(); <-served })
	stream, err := pb.NewEventSourcedClient(conn).Handle(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	return stream
}

func eventPayload(t *testing.T, e tEvent) *pb.Payload {
	t.Helper()
	p, err := payloadOf(CodecFor[tEvent](), e)
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func send(t *testing.T, s pb.EventSourced_HandleClient, m *pb.EventSourcedIn) {
	t.Helper()
	if err := s.Send(m); err != nil {
		t.Fatal(err)
	}
}

func command(t *testing.T, s pb.EventSourced_HandleClient, id int64, name string, input any, snapshot bool) *pb.EventSourcedOut {
	t.Helper()
	p, err := encodeAny(input)
	if err != nil {
		t.Fatal(err)
	}
	send(t, s, &pb.EventSourcedIn{Message: &pb.EventSourcedIn_Command_{Command: &pb.EventSourcedIn_Command{Id: id, Name: name, Payload: p, SnapshotRequested: snapshot, AtHome: true}}})
	out, err := s.Recv()
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func replyText(out *pb.EventSourcedOut) string {
	return string(out.GetReply().GetOutcome().GetReply().GetPayload().GetData())
}

func TestAnEntityConversation(t *testing.T) {
	svc := NewService(tCartEntity)
	if len(svc.Problems()) > 0 {
		t.Fatalf("problems: %v", svc.Problems())
	}
	s := conversation(t, svc)
	send(t, s, &pb.EventSourcedIn{Message: &pb.EventSourcedIn_Init_{Init: &pb.EventSourcedIn_Init{ComponentId: "t-cart", EntityId: "c1"}}})
	for i := int64(1); i <= 3; i++ {
		send(t, s, &pb.EventSourcedIn{Message: &pb.EventSourcedIn_Event_{Event: &pb.EventSourcedIn_Event{Sequence: i, Payload: eventPayload(t, tAdded{tItem{"p", 1}})}}})
	}

	if got := replyText(command(t, s, 1, "total", Unit{}, false)); got != "3" {
		t.Fatalf("after replaying three events the total is %s", got)
	}
	if got := replyText(command(t, s, 2, "scope", Unit{}, false)); got != "c1@3" {
		t.Fatalf("the command's scope is %s", got)
	}

	out := command(t, s, 3, "add-item", tItem{"q", 2}, true)
	r := out.GetReply()
	if r.GetCommandId() != 3 || len(r.GetEvents()) != 1 || replyText(out) != "5" {
		t.Fatalf("add-item answered %v", out)
	}
	if r.GetSnapshot() == nil || !strings.Contains(string(r.GetSnapshot().GetData()), `"q"`) {
		t.Fatalf("a snapshot was asked for and not given: %v", r)
	}
	if command(t, s, 4, "add-item", tItem{"r", 1}, false).GetReply().GetSnapshot() != nil {
		t.Fatal("a snapshot was given unasked")
	}

	refused := command(t, s, 5, "add-item", tItem{"q", 0}, false)
	if e := refused.GetReply().GetOutcome().GetError(); e.GetCode() != pb.ErrorCode_BAD_REQUEST || len(refused.GetReply().GetEvents()) != 0 {
		t.Fatalf("a refusal answered %v", refused)
	}

	panicked := command(t, s, 6, "add-item", tItem{"boom", 1}, false)
	if f := panicked.GetFailure(); f.GetCommandId() != 6 || !strings.Contains(f.GetError().GetMessage(), "panicked") {
		t.Fatalf("a panic answered %v", panicked)
	}
	failed := command(t, s, 7, "fail", Unit{}, false)
	if f := failed.GetFailure(); f.GetError().GetCode() != pb.ErrorCode_INTERNAL || !strings.Contains(f.GetError().GetMessage(), "disk") {
		t.Fatalf("a failure answered %v", failed)
	}
	if got := replyText(command(t, s, 8, "total", Unit{}, false)); got != "6" {
		t.Fatalf("after a refusal, a panic and a failure the total is %s; the state changed", got)
	}
	if got := replyText(command(t, s, 9, "scope", Unit{}, false)); got != "c1@5" {
		t.Fatalf("the sequence after two persisted events is %s", got)
	}
	unknown := command(t, s, 10, "nothing", Unit{}, false)
	if unknown.GetFailure() == nil {
		t.Fatalf("an unknown command answered %v", unknown)
	}

	if err := s.CloseSend(); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Recv(); !errors.Is(err, io.EOF) {
		t.Fatalf("the conversation did not end when the sidecar closed it: %v", err)
	}
}

func TestASnapshotIsWhereRecoveryStarts(t *testing.T) {
	s := conversation(t, NewService(tCartEntity))
	snap, err := payloadOf(CodecFor[tState](), tState{Items: []tItem{{"a", 4}}})
	if err != nil {
		t.Fatal(err)
	}
	send(t, s, &pb.EventSourcedIn{Message: &pb.EventSourcedIn_Init_{Init: &pb.EventSourcedIn_Init{ComponentId: "t-cart", EntityId: "c2", Snapshot: &pb.EventSourcedIn_Snapshot{Sequence: 6, Payload: snap}}}})
	send(t, s, &pb.EventSourcedIn{Message: &pb.EventSourcedIn_Event_{Event: &pb.EventSourcedIn_Event{Sequence: 7, Payload: eventPayload(t, tAdded{tItem{"b", 1}})}}})
	if got := replyText(command(t, s, 1, "total", Unit{}, false)); got != "5" {
		t.Fatalf("the total from a snapshot and one event is %s", got)
	}
	if got := replyText(command(t, s, 2, "scope", Unit{}, false)); got != "c2@7" {
		t.Fatalf("the sequence is %s", got)
	}
}

func TestAnEntityDeclaresItselfAndItsProblems(t *testing.T) {
	spec := NewService(tCartEntity).Spec()
	c := spec.GetComponents()[0]
	if c.GetKind() != pb.Kind_EVENT_SOURCED_ENTITY || c.GetEventSourced().GetSnapshotEvery() != 3 || len(c.GetHandlers()) != 4 {
		t.Fatalf("got %v", c)
	}
	for _, h := range c.GetHandlers() {
		if h.GetReadOnly() != (h.GetName() == "total" || h.GetName() == "scope") {
			t.Fatalf("handler %v", h)
		}
	}
	type other struct{}
	bad := EventSourced[tState, tEvent]("bad", tEvents, func() tState { return tState{} }, tApply,
		Command("same", func(context.Context, tState, Unit) (Effect[tState, tEvent, Done], error) { return Persist[tState, tEvent, Done]().ThenNoReply(), nil }),
		Query("same", func(context.Context, tState, Unit) (ReadOnlyEffect[Done], error) { return NoReply[Done](), nil }),
		Query("elsewhere", func(context.Context, other, Unit) (ReadOnlyEffect[Done], error) { return NoReply[Done](), nil }),
		Command("unsigned", func(context.Context, tState, uint) (Effect[tState, tEvent, Done], error) { return Persist[tState, tEvent, Done]().ThenNoReply(), nil }),
		Replicated(),
	)
	msg := (&ProblemsError{Problems: NewService(bad).Problems()}).Error()
	for _, want := range []string{`share the wire name "same"`, "elsewhere", "unsigned", "uint"} {
		if !strings.Contains(msg, want) {
			t.Errorf("the problems do not name %q:\n%s", want, msg)
		}
	}
	if why := NewService(bad).decls.refusal("1.18"); !strings.Contains(why, "1.19") || !strings.Contains(why, "bad declares replication") {
		t.Fatalf("a replicated entity at 1.18: %q", why)
	}
}
