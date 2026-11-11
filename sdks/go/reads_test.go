package ankka

import (
	"context"
	"errors"
	"net"
	"reflect"
	"testing"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// readsSidecar answers the two reads (1.24) as told; with nothing set it is a sidecar before 1.24.
type readsSidecar struct {
	pb.UnimplementedClientServer
	lifecycle   *pb.LifecycleReply
	replication *pb.ReplicationReply
	asked       []any
}

func (f *readsSidecar) Lifecycle(_ context.Context, r *pb.LifecycleRequest) (*pb.LifecycleReply, error) {
	f.asked = append(f.asked, r)
	if f.lifecycle == nil {
		return nil, status.Error(codes.Unimplemented, "unknown method Lifecycle")
	}
	return f.lifecycle, nil
}

func (f *readsSidecar) Replication(_ context.Context, r *pb.ReplicationRequest) (*pb.ReplicationReply, error) {
	f.asked = append(f.asked, r)
	if f.replication == nil {
		return nil, status.Error(codes.Unimplemented, "unknown method Replication")
	}
	return f.replication, nil
}

func startReads(t *testing.T, f *readsSidecar) context.Context {
	t.Helper()
	return startFakeServer(t, f)
}

func standingReply(l *pb.WorkflowLifecycle) *pb.LifecycleReply {
	return &pb.LifecycleReply{Result: &pb.LifecycleReply_Lifecycle{Lifecycle: l}}
}

func TestEachStandingThePlatformRecordsIsRead(t *testing.T) {
	for status, expected := range statuses {
		f := &readsSidecar{lifecycle: standingReply(&pb.WorkflowLifecycle{Status: status})}
		got, err := Lifecycle(startReads(t, f), "transfer", "t1")
		if err != nil || got.Status != expected {
			t.Fatalf("%v: got %v, %v", status, got, err)
		}
		asked := f.asked[0].(*pb.LifecycleRequest)
		if asked.GetComponentId() != "transfer" || asked.GetWorkflowId() != "t1" {
			t.Fatalf("asked %v", asked)
		}
	}
}

func TestAFailedWorkflowCarriesItsReasonAndItsRetries(t *testing.T) {
	failure := "the bank was shut"
	f := &readsSidecar{lifecycle: standingReply(&pb.WorkflowLifecycle{Status: pb.WorkflowStatus_FAILED, Retries: map[string]int32{"deposit": 2}, Failure: &failure})}
	got, err := Lifecycle(startReads(t, f), "transfer", "t1")
	if err != nil || !got.IsFailed() || !got.IsTerminal() || got.Retries["deposit"] != 2 || *got.Failure != failure || got.PendingStep != nil {
		t.Fatalf("got %+v, %v", got, err)
	}
}

func TestARunningWorkflowNamesItsStep(t *testing.T) {
	step := "withdraw"
	f := &readsSidecar{lifecycle: standingReply(&pb.WorkflowLifecycle{Status: pb.WorkflowStatus_RUNNING, PendingStep: &step})}
	got, err := Lifecycle(startReads(t, f), "transfer", "t1")
	if err != nil || !got.IsRunning() || got.IsTerminal() || *got.PendingStep != "withdraw" {
		t.Fatalf("got %+v, %v", got, err)
	}
}

func TestAStatusThisSDKDoesNotKnowIsRefused(t *testing.T) {
	f := &readsSidecar{lifecycle: standingReply(&pb.WorkflowLifecycle{})}
	_, err := Lifecycle(startReads(t, f), "transfer", "t1")
	if r, ok := AsRefusal(err); !ok || r.Code != Internal {
		t.Fatalf("got %v", err)
	}
}

func TestACopyReadsItsHomeItsSetAHandoverAConflictAndItsPosition(t *testing.T) {
	home, next := "leipzig", "vienna"
	f := &readsSidecar{replication: &pb.ReplicationReply{Result: &pb.ReplicationReply_Status{Status: &pb.ReplicationStatus{
		Home: &home, ReplicationSet: []string{"vienna", "leipzig"}, PendingHandover: &next, Conflict: &next, Position: 5,
	}}}}
	got, err := Replication(startReads(t, f), "vehicle", "v1")
	if err != nil || *got.Home != "leipzig" || !reflect.DeepEqual(got.ReplicationSet, []string{"leipzig", "vienna"}) ||
		*got.PendingHandover != "vienna" || !got.InConflict() || got.Position != 5 {
		t.Fatalf("got %+v, %v", got, err)
	}
	asked := f.asked[0].(*pb.ReplicationRequest)
	if asked.GetComponentId() != "vehicle" || asked.GetEntityId() != "v1" {
		t.Fatalf("asked %v", asked)
	}
}

func TestARefusalCrossesWithTheRuntimesCode(t *testing.T) {
	refusal := &pb.Error{Code: pb.ErrorCode_NOT_FOUND, Message: "not replicated"}
	f := &readsSidecar{
		lifecycle:   &pb.LifecycleReply{Result: &pb.LifecycleReply_Error{Error: refusal}},
		replication: &pb.ReplicationReply{Result: &pb.ReplicationReply_Error{Error: refusal}},
	}
	ctx := startReads(t, f)
	if _, err := Lifecycle(ctx, "transfer", "t1"); !isRefused(err, NotFound) {
		t.Fatalf("standing: %v", err)
	}
	if _, err := Replication(ctx, "vehicle", "v1"); !isRefused(err, NotFound) {
		t.Fatalf("copy: %v", err)
	}
}

func isRefused(err error, code ErrorCode) bool {
	r, ok := AsRefusal(err)
	return ok && r.Code == code
}

func TestARuntimeFromBeforeTheReadRefusesAWorkflowsStandingNamingTheProtocolVersion(t *testing.T) {
	_, err := Lifecycle(startReads(t, &readsSidecar{}), "transfer", "t1")
	var old *ProtocolTooOldError
	if !errors.As(err, &old) || old.Needs != "1.24" {
		t.Fatalf("got %v", err)
	}
}

func TestARuntimeFromBeforeTheReadRefusesHowACopyStandsNamingTheProtocolVersion(t *testing.T) {
	_, err := Replication(startReads(t, &readsSidecar{}), "vehicle", "v1")
	var old *ProtocolTooOldError
	if !errors.As(err, &old) || old.Needs != "1.24" {
		t.Fatalf("got %v", err)
	}
}

func startFakeServer(t *testing.T, f pb.ClientServer) context.Context {
	t.Helper()
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	g := grpc.NewServer()
	pb.RegisterClientServer(g, f)
	go func() { _ = g.Serve(lis) }()
	c := &Client{address: lis.Addr().String()}
	t.Cleanup(func() { _ = c.Close(); g.Stop() })
	return WithClient(context.Background(), c)
}
