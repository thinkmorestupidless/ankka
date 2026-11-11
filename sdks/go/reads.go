package ankka

import (
	"context"
	"fmt"
	"sort"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

// ReadsSince is the first protocol whose runtime answers a workflow's standing and how a copy stands;
// an older one answers Unimplemented, which Lifecycle and Replication report as *ProtocolTooOldError.
const ReadsSince = "1.24"

// WorkflowStatus is where a workflow has got to, as the engine records it.
type WorkflowStatus string

const (
	NotStarted WorkflowStatus = "NotStarted"
	Running    WorkflowStatus = "Running"
	Paused     WorkflowStatus = "Paused"
	Completed  WorkflowStatus = "Completed"
	Failed     WorkflowStatus = "Failed"
)

// WorkflowLifecycle is a workflow's standing (1.24): its status, the step it is on or waits after,
// how many times each step was retried (a step never retried is absent), and why it failed.
type WorkflowLifecycle struct {
	Status      WorkflowStatus
	PendingStep *string
	Retries     map[string]int
	Failure     *string
}

// IsRunning, IsPaused, IsCompleted, IsFailed and IsTerminal read the status.
func (l WorkflowLifecycle) IsRunning() bool   { return l.Status == Running }
func (l WorkflowLifecycle) IsPaused() bool    { return l.Status == Paused }
func (l WorkflowLifecycle) IsCompleted() bool { return l.Status == Completed }
func (l WorkflowLifecycle) IsFailed() bool    { return l.Status == Failed }
func (l WorkflowLifecycle) IsTerminal() bool  { return l.IsCompleted() || l.IsFailed() }

// ReplicationStatus is how the copy of a replicated entity at this installation stands (1.24): its
// home, the installations that hold it (sorted), the installation a handover waiting goes to, the
// other home when it was created at two, and how many records this copy's history holds.
type ReplicationStatus struct {
	Home            *string
	ReplicationSet  []string
	PendingHandover *string
	Conflict        *string
	Position        int64
}

// InConflict says whether the entity was created at two installations.
func (r ReplicationStatus) InConflict() bool { return r.Conflict != nil }

var statuses = map[pb.WorkflowStatus]WorkflowStatus{
	pb.WorkflowStatus_NOT_STARTED: NotStarted,
	pb.WorkflowStatus_RUNNING:     Running,
	pb.WorkflowStatus_PAUSED:      Paused,
	pb.WorkflowStatus_COMPLETED:   Completed,
	pb.WorkflowStatus_FAILED:      Failed,
}

// Lifecycle answers the standing of the workflow id of the component componentID: the runtime's own
// read, which runs no handler and journals nothing. A workflow never started answers NotStarted.
func Lifecycle(ctx context.Context, componentID, id string) (WorkflowLifecycle, error) {
	_, rpc, err := clientOf(ctx)
	if err != nil {
		return WorkflowLifecycle{}, err
	}
	reply, err := rpc.Lifecycle(ctx, &pb.LifecycleRequest{ComponentId: componentID, WorkflowId: id})
	if err != nil {
		return WorkflowLifecycle{}, tooOld("reading a workflow's standing", ReadsSince, err)
	}
	if e := reply.GetError(); e != nil {
		return WorkflowLifecycle{}, errorFromPb(e)
	}
	return lifecycleFromPb(reply.GetLifecycle())
}

// Replication answers how the copy at this installation of the replicated entity id of the component
// componentID stands: the runtime's own read. An entity that is not replicated is refused.
func Replication(ctx context.Context, componentID, id string) (ReplicationStatus, error) {
	_, rpc, err := clientOf(ctx)
	if err != nil {
		return ReplicationStatus{}, err
	}
	reply, err := rpc.Replication(ctx, &pb.ReplicationRequest{ComponentId: componentID, EntityId: id})
	if err != nil {
		return ReplicationStatus{}, tooOld("reading how a copy stands", ReadsSince, err)
	}
	if e := reply.GetError(); e != nil {
		return ReplicationStatus{}, errorFromPb(e)
	}
	return replicationFromPb(reply.GetStatus()), nil
}

// lifecycleFromPb reads a standing as the protocol carries it; a status this SDK does not know is
// refused.
func lifecycleFromPb(m *pb.WorkflowLifecycle) (WorkflowLifecycle, error) {
	if m == nil {
		return WorkflowLifecycle{}, Refuse(Internal, "the runtime answered a standing with nothing")
	}
	status, ok := statuses[m.GetStatus()]
	if !ok {
		return WorkflowLifecycle{}, Refuse(Internal, fmt.Sprintf("a workflow's standing this SDK does not know: %v", m.GetStatus()))
	}
	retries := make(map[string]int, len(m.GetRetries()))
	for step, n := range m.GetRetries() {
		retries[step] = int(n)
	}
	return WorkflowLifecycle{Status: status, PendingStep: m.PendingStep, Retries: retries, Failure: m.Failure}, nil
}

// replicationFromPb reads how a copy stands as the protocol carries it.
func replicationFromPb(m *pb.ReplicationStatus) ReplicationStatus {
	set := append([]string{}, m.GetReplicationSet()...)
	sort.Strings(set)
	return ReplicationStatus{Home: m.Home, ReplicationSet: set, PendingHandover: m.PendingHandover, Conflict: m.Conflict, Position: m.GetPosition()}
}
