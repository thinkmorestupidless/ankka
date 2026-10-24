package ankka

import (
	"context"
	"log/slog"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

type tProfile struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

var tProfileEntity = KeyValue[tProfile]("t-profile", func(id string) tProfile { return tProfile{ID: id} },
	Update("set-name", func(_ context.Context, p tProfile, name string) (KeyValueEffect[tProfile, Done], error) {
		if name == "" {
			return KeyValueEffect[tProfile, Done]{}, Refuse(BadRequest, "a name is not empty")
		}
		p.Name = name
		return UpdateState[tProfile, Done](p).ThenReplyValue(Done{}), nil
	}),
	Update("forget", func(context.Context, tProfile, Unit) (KeyValueEffect[tProfile, Done], error) {
		return DeleteState[tProfile, Done]().ThenReplyValue(Done{}), nil
	}),
	Update("touch", func(context.Context, tProfile, Unit) (KeyValueEffect[tProfile, string], error) {
		return KeepState[tProfile, string]().ThenReply(func(p tProfile) string { return p.Name }), nil
	}),
	Query("get", func(_ context.Context, p tProfile, _ Unit) (ReadOnlyEffect[tProfile], error) {
		return Reply(p), nil
	}),
)

// loopback serves svc on loopback and answers a connection to it.
func loopback(t *testing.T, svc *Service) *grpc.ClientConn {
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
	return conn
}

func kvOpen(t *testing.T, conn *grpc.ClientConn, entityID string, state *pb.Payload) pb.KeyValue_HandleClient {
	t.Helper()
	s, err := pb.NewKeyValueClient(conn).Handle(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if err := s.Send(&pb.KeyValueIn{Message: &pb.KeyValueIn_Init_{Init: &pb.KeyValueIn_Init{ComponentId: "t-profile", EntityId: entityID, State: state}}}); err != nil {
		t.Fatal(err)
	}
	return s
}

func kvCommand(t *testing.T, s pb.KeyValue_HandleClient, id int64, name string, input any) *pb.KeyValueOut {
	t.Helper()
	p, err := encodeAny(input)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.Send(&pb.KeyValueIn{Message: &pb.KeyValueIn_Command_{Command: &pb.KeyValueIn_Command{Id: id, Name: name, Payload: p}}}); err != nil {
		t.Fatal(err)
	}
	out, err := s.Recv()
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func TestAKeyValueConversation(t *testing.T) {
	svc := NewService(tProfileEntity)
	if p := svc.Problems(); len(p) > 0 {
		t.Fatalf("problems: %v", p)
	}
	conn := loopback(t, svc)
	s := kvOpen(t, conn, "p1", nil)

	got := kvCommand(t, s, 1, "get", Unit{})
	if r := got.GetReply(); r.GetCommandId() != 1 || r.NewState != nil || string(r.GetOutcome().GetReply().GetPayload().GetData()) != `{"id":"p1","name":""}` {
		t.Fatalf("get of a fresh profile answered %v", got)
	}
	set := kvCommand(t, s, 2, "set-name", "Ada").GetReply()
	if string(set.GetNewState().GetData()) != `{"id":"p1","name":"Ada"}` || set.GetOutcome().GetReply() == nil {
		t.Fatalf("set-name answered %v", set)
	}
	// Keeping the state sends none, and the reply is from the state kept.
	touch := kvCommand(t, s, 3, "touch", Unit{}).GetReply()
	if touch.NewState != nil || string(touch.GetOutcome().GetReply().GetPayload().GetData()) != "Ada" {
		t.Fatalf("touch answered %v", touch)
	}
	refused := kvCommand(t, s, 4, "set-name", "").GetReply()
	if refused.NewState != nil || refused.GetOutcome().GetError().GetCode() != pb.ErrorCode_BAD_REQUEST {
		t.Fatalf("a refusal answered %v", refused)
	}
	forget := kvCommand(t, s, 5, "forget", Unit{}).GetReply()
	if forget.GetRetention().GetDeleteNow() == nil {
		t.Fatalf("forget answered %v", forget)
	}
	if r := kvCommand(t, s, 6, "get", Unit{}).GetReply(); !strings.Contains(string(r.GetOutcome().GetReply().GetPayload().GetData()), `"name":""`) {
		t.Fatalf("after a deletion the profile is %v", r)
	}
	if f := kvCommand(t, s, 7, "nothing", Unit{}).GetFailure(); f == nil || !strings.Contains(f.GetError().GetMessage(), `no handler "nothing"`) {
		t.Fatalf("an unknown handler answered %v", f)
	}
}

func TestAKeyValueEntityRecoversFromItsState(t *testing.T) {
	conn := loopback(t, NewService(tProfileEntity))
	state, err := payloadOf(CodecFor[tProfile](), tProfile{ID: "p2", Name: "Grace"})
	if err != nil {
		t.Fatal(err)
	}
	s := kvOpen(t, conn, "p2", state)
	if r := kvCommand(t, s, 1, "get", Unit{}).GetReply(); string(r.GetOutcome().GetReply().GetPayload().GetData()) != `{"id":"p2","name":"Grace"}` {
		t.Fatalf("a recovered profile is %v", r)
	}
}

func TestAKeyValueEntityDeclaresItselfAndItsProblems(t *testing.T) {
	spec := NewService(tProfileEntity).Spec()
	c := spec.GetComponents()[0]
	if c.GetKind() != pb.Kind_KEY_VALUE_ENTITY || c.GetId() != "t-profile" || len(c.GetHandlers()) != 4 || c.GetKeyValue() == nil {
		t.Fatalf("declared %v", c)
	}
	readOnly := map[string]bool{}
	for _, h := range c.GetHandlers() {
		readOnly[h.GetName()] = h.GetReadOnly()
	}
	if !readOnly["get"] || readOnly["set-name"] {
		t.Fatalf("read-only flags %v", readOnly)
	}
	bad := NewService(KeyValue[tProfile]("", nil, SnapshotEvery(2)))
	text := ""
	for _, p := range bad.Problems() {
		text += p.String() + "\n"
	}
	for _, want := range []string{"has an id", "empty state", "at least one handler", "not an option a key value entity takes"} {
		if !strings.Contains(text, want) {
			t.Errorf("problems do not say %q:\n%s", want, text)
		}
	}
}
