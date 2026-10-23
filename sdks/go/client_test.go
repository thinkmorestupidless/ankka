package ankka

import (
	"context"
	"errors"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// fakeSidecar is the sidecar's Client service, answering from what a test sets and keeping what it
// was asked.
type fakeSidecar struct {
	pb.UnimplementedClientServer
	mu       sync.Mutex
	invokes  []*pb.InvokeRequest
	reply    *pb.InvokeReply
	rows     []byte
	secrets  map[string]string
	service  *pb.ServiceReply
	awaited  *pb.AwaitEndRequest
	frames   []*pb.RowFrame
	tooOldTo map[string]bool
}

func (f *fakeSidecar) Invoke(_ context.Context, r *pb.InvokeRequest) (*pb.InvokeReply, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.invokes = append(f.invokes, r)
	return f.reply, nil
}

func (f *fakeSidecar) Query(_ context.Context, r *pb.QueryRequest) (*pb.QueryReply, error) {
	return &pb.QueryReply{Result: &pb.QueryReply_Rows{Rows: &pb.Payload{ContentType: JSONContent, Data: f.rows}}}, nil
}

func (f *fakeSidecar) GetSecret(_ context.Context, r *pb.GetSecretRequest) (*pb.GetSecretReply, error) {
	if v, ok := f.secrets[r.GetName()]; ok {
		return &pb.GetSecretReply{Result: &pb.GetSecretReply_Value{Value: v}}, nil
	}
	return &pb.GetSecretReply{Result: &pb.GetSecretReply_Absent{Absent: &pb.Empty{}}}, nil
}

func (f *fakeSidecar) Request(context.Context, *pb.ServiceRequest) (*pb.ServiceReply, error) {
	return f.service, nil
}

func (f *fakeSidecar) AwaitEnd(_ context.Context, r *pb.AwaitEndRequest) (*pb.InvokeReply, error) {
	if f.tooOldTo["await"] {
		return nil, status.Error(codes.Unimplemented, "unknown method AwaitEnd")
	}
	f.awaited = r
	return f.reply, nil
}

func (f *fakeSidecar) ScheduleRecurring(context.Context, *pb.ScheduleRecurringRequest) (*pb.ScheduleRecurringReply, error) {
	return nil, status.Error(codes.Unimplemented, "unknown method ScheduleRecurring")
}

func (f *fakeSidecar) Watch(_ *pb.WatchRequest, stream grpc.ServerStreamingServer[pb.RowFrame]) error {
	for _, fr := range f.frames {
		if err := stream.Send(fr); err != nil {
			return err
		}
	}
	return nil
}

// startFake serves f on loopback and is a context whose client speaks to it, as a handler of a
// service declaring decls.
func startFake(t *testing.T, f *fakeSidecar, decls *declarations) context.Context {
	t.Helper()
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	g := grpc.NewServer()
	pb.RegisterClientServer(g, f)
	go func() { _ = g.Serve(lis) }()
	c := &Client{address: lis.Addr().String(), decls: decls}
	t.Cleanup(func() { _ = c.Close(); g.Stop() })
	ctx := WithClient(context.Background(), c)
	return withMetadata(ctx, Metadata{"traceparent": "00-abc-def-01"})
}

func cartDecls() *declarations {
	d := newDeclarations()
	d.components = []*pb.Component{
		{Kind: pb.Kind_EVENT_SOURCED_ENTITY, Id: "shopping-cart"},
		{Kind: pb.Kind_TIMED_ACTION, Id: "reminder"},
		{Kind: pb.Kind_WORKFLOW, Id: "checkout"},
	}
	input(d, "shopping-cart", "add-item", CodecFor[mCart]())
	return d
}

func TestInvokeSendsTheWireAndDecodesTheReply(t *testing.T) {
	f := &fakeSidecar{reply: &pb.InvokeReply{Result: &pb.InvokeReply_Reply{Reply: &pb.Outcome_Reply{Payload: &pb.Payload{ContentType: TextContent, Manifest: "int", Data: []byte("3")}}}}}
	ctx := startFake(t, f, cartDecls())
	n, err := Invoke[int](ctx, "shopping-cart", "c1", "add-item", mCart{Items: []string{"pen"}})
	if err != nil || n != 3 {
		t.Fatalf("got %d, %v", n, err)
	}
	r := f.invokes[0]
	if r.GetKind() != pb.Kind_EVENT_SOURCED_ENTITY || r.GetEntityId() != "c1" || r.GetName() != "add-item" || r.GetPayload().GetManifest() != "mCart" {
		t.Fatalf("sent %v", r)
	}
	if metadataFromPb(r.GetMetadata())["traceparent"] != "00-abc-def-01" {
		t.Fatal("the handler's metadata did not travel, so the trace would not nest")
	}
	if _, err := Invoke[int](ctx, "shopping-cart", "c1", "add-item", 7); err == nil || !strings.Contains(err.Error(), "takes a") {
		t.Fatalf("an input of the wrong type: %v", err)
	}
	if _, err := Invoke[int](ctx, "nothing", "c1", "x", nil); err == nil {
		t.Fatal("a call to an undeclared component was sent")
	}
}

func TestARefusalArrivesWithItsCode(t *testing.T) {
	f := &fakeSidecar{reply: &pb.InvokeReply{Result: &pb.InvokeReply_Error{Error: &pb.Error{Code: pb.ErrorCode_NOT_FOUND, Message: "no such cart"}}}}
	ctx := startFake(t, f, cartDecls())
	_, err := Invoke[Done](ctx, "shopping-cart", "c1", "get-cart", Unit{})
	r, ok := AsRefusal(err)
	if !ok || r.Code != NotFound || r.Message != "no such cart" {
		t.Fatalf("got %v", err)
	}
}

func TestAnOlderSidecarIsTooOldForANewerCall(t *testing.T) {
	ctx := startFake(t, &fakeSidecar{}, cartDecls())
	err := ScheduleRecurring(ctx, "t1", time.Second, time.Minute, TimerTarget{ComponentID: "reminder", Handler: "tick"}, nil)
	var old *ProtocolTooOldError
	if !errors.As(err, &old) || old.Needs != "1.12" || !strings.Contains(err.Error(), "recurring timers") {
		t.Fatalf("got %v", err)
	}
	if err := ScheduleRecurring(ctx, "t1", 0, time.Minute, TimerTarget{ComponentID: "shopping-cart", Handler: "x"}, nil); err == nil {
		t.Fatal("a recurring timer on an entity was sent")
	}
	f := &fakeSidecar{tooOldTo: map[string]bool{"await": true}}
	ctx = startFake(t, f, cartDecls())
	if _, err := AwaitEnd[Done](ctx, "checkout", "o1", time.Second); !errors.As(err, &old) || old.Needs != "1.18" {
		t.Fatalf("got %v", err)
	}
}

func TestAwaitEndCarriesItsLimit(t *testing.T) {
	f := &fakeSidecar{reply: &pb.InvokeReply{Result: &pb.InvokeReply_Error{Error: &pb.Error{Code: pb.ErrorCode_WORKFLOW_FAILED, Message: "failed", Details: map[string]string{"step": "charge"}}}}}
	ctx := startFake(t, f, cartDecls())
	_, err := AwaitEnd[mCart](ctx, "checkout", "o1", 1500*time.Millisecond)
	r, ok := AsRefusal(err)
	if !ok || r.Code != WorkflowFailed || r.Details["step"] != "charge" || f.awaited.GetTimeoutMillis() != 1500 {
		t.Fatalf("got %v, %v", err, f.awaited)
	}
	if _, err := AwaitEnd[mCart](ctx, "checkout", "o1", 0); err == nil {
		t.Fatal("a wait without a limit was sent")
	}
}

func TestViewRowsKeepTheirBytes(t *testing.T) {
	f := &fakeSidecar{rows: []byte(`[{"items":["a"]},{"items":["b","c"]}]`)}
	ctx := startFake(t, f, cartDecls())
	rows, err := QueryView[mCart](ctx, "cart-rows", All())
	if err != nil || len(rows) != 2 || len(rows[1].Items) != 2 {
		t.Fatalf("got %v, %v", rows, err)
	}
}

func TestSecretsAreRefusedWhereTheyAreNotOffered(t *testing.T) {
	f := &fakeSidecar{secrets: map[string]string{"api": "k"}}
	ctx := startFake(t, f, cartDecls())
	if v, ok, err := Secrets(ctx).Get("api"); err != nil || !ok || v != "k" {
		t.Fatalf("got %q %v %v", v, ok, err)
	}
	if _, ok, err := Secrets(ctx).Get("none"); err != nil || ok {
		t.Fatal("an absent secret")
	}
	if _, _, err := Secrets(withoutSecrets(ctx, "an entity")).Get("api"); err == nil || !strings.Contains(err.Error(), "an entity") {
		t.Fatalf("got %v", err)
	}
	if err := Secrets(ctx).Put("x", ""); err == nil {
		t.Fatal("an empty value was kept")
	}
}

func TestACallToAServiceWithNoAnswer(t *testing.T) {
	f := &fakeSidecar{service: &pb.ServiceReply{Result: &pb.ServiceReply_Failure{Failure: &pb.ServiceFailure{Reason: pb.ServiceFailure_UNRESOLVABLE, Detail: "no such service"}}}}
	ctx := startFake(t, f, cartDecls())
	_, err := CallService(ctx, "billing", ServiceRequest{Method: "GET", Path: "/x", Project: "p"})
	var se *ServiceError
	if !errors.As(err, &se) || se.Reason != ServiceUnresolvable || se.Service != "p/billing" {
		t.Fatalf("got %v", err)
	}
	f.service = &pb.ServiceReply{Result: &pb.ServiceReply_Response{Response: &pb.HttpResponse{Status: 404, Body: []byte("nope")}}}
	res, err := CallService(ctx, "billing", ServiceRequest{Method: "GET", Path: "/x"})
	if err != nil || res.Status != 404 {
		t.Fatalf("a 404 is an answer: %v, %v", res, err)
	}
}

func TestAWatchGivesRowsThenCaughtUpThenEnds(t *testing.T) {
	f := &fakeSidecar{frames: []*pb.RowFrame{
		{Frame: &pb.RowFrame_Row{Row: &pb.WatchedRow{Key: "c1", Payload: &pb.Payload{Data: []byte(`{"items":[]}`)}}}},
		{Frame: &pb.RowFrame_CaughtUp{CaughtUp: &pb.Empty{}}},
		{Frame: &pb.RowFrame_Removed{Removed: &pb.Removed{Key: "c1"}}},
		{Frame: &pb.RowFrame_Ended{Ended: &pb.Ended{Reason: "rebuilt"}}},
	}}
	ctx := startFake(t, f, cartDecls())
	var got []string
	var ended error
	for ev, err := range Watch[mCart](ctx, "cart-rows", WatchKey("c1")) {
		if err != nil {
			ended = err
			break
		}
		switch {
		case ev.CaughtUp:
			got = append(got, "caught-up")
		case ev.Removed:
			got = append(got, "removed "+ev.Key)
		default:
			got = append(got, "row "+ev.Key)
		}
	}
	var we *WatchEndedError
	if strings.Join(got, ",") != "row c1,caught-up,removed c1" || !errors.As(ended, &we) || we.Reason != "rebuilt" {
		t.Fatalf("got %v, %v", got, ended)
	}
}

func TestNoClientOutsideAHandler(t *testing.T) {
	if _, err := Invoke[int](context.Background(), "shopping-cart", "c1", "add-item", nil); err == nil {
		t.Fatal("a call without a client")
	}
}
