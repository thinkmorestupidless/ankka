package server

import (
	"context"
	"fmt"
	"sync"
	"sync/atomic"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// WorkflowInstance is one loaded workflow: its state, held for the life of its stream. Command and
// Step may be called at once, a command while a step runs; the instance keeps its state safe.
type WorkflowInstance interface {
	// Recover starts from the state the sidecar holds, or from the empty state when there is none.
	Recover(ctx context.Context, init *pb.WorkflowIn_Init) error
	// Command handles one command or query from the state as it stands, which while a step runs is
	// the state before the step.
	Command(ctx context.Context, cmd *pb.WorkflowIn_Command) (*pb.WorkflowOut_Reply, error)
	// Step runs one step; its state becomes the instance's when it returns.
	Step(ctx context.Context, run *pb.WorkflowIn_RunStep) (*pb.WorkflowOut_StepReply, error)
}

// WorkflowHost loads an instance of the component named, or refuses one it does not host.
type WorkflowHost func(componentID, entityID string) (WorkflowInstance, error)

type workflowServer struct {
	pb.UnimplementedWorkflowServer
	host WorkflowHost
	opts Options
}

// RegisterWorkflow serves the workflow conversation on s.
func RegisterWorkflow(s grpc.ServiceRegistrar, host WorkflowHost, opts Options) {
	pb.RegisterWorkflowServer(s, &workflowServer{host: host, opts: opts})
}

// Handle carries commands and steps on one stream. The engine keeps sending commands while a step
// runs, so a command is answered at once from the stream's goroutine while the step runs in its
// own; both answer through one sender. A second step while one runs is a protocol fault.
func (s *workflowServer) Handle(stream grpc.BidiStreamingServer[pb.WorkflowIn, pb.WorkflowOut]) error {
	ctx := s.opts.scope(stream.Context())
	first, err := stream.Recv()
	if err != nil {
		return ended(err)
	}
	init := first.GetInit()
	if init == nil {
		return status.Error(codes.InvalidArgument, "a workflow conversation starts with Init")
	}
	inst, err := s.host(init.GetComponentId(), init.GetEntityId())
	if err != nil {
		return status.Error(codes.NotFound, err.Error())
	}
	if err := Guard(func() error { return inst.Recover(ctx, init) }); err != nil {
		s.opts.log().Error("ankka: recovering a workflow failed", "component", init.GetComponentId(), "workflow", init.GetEntityId(), "error", err)
		return status.Error(codes.Internal, fmt.Sprintf("recovering %s/%s: %v", init.GetComponentId(), init.GetEntityId(), err))
	}
	var sending sync.Mutex
	send := func(out *pb.WorkflowOut) error {
		sending.Lock()
		defer sending.Unlock()
		return stream.Send(out)
	}
	failure := func(id int64, err error) *pb.WorkflowOut {
		return &pb.WorkflowOut{Message: &pb.WorkflowOut_Failure{Failure: &pb.Failure{CommandId: id, Error: ErrorOf(err)}}}
	}
	var running atomic.Bool
	var steps sync.WaitGroup
	defer steps.Wait()
	stepCtx, cancel := context.WithCancel(ctx)
	defer cancel() // runs first: a step still running sees the conversation end
	for {
		in, err := stream.Recv()
		if err != nil {
			return ended(err) // a step still running has nowhere to answer; its result goes with the state
		}
		switch m := in.GetMessage().(type) {
		case *pb.WorkflowIn_Command_:
			cmd := m.Command
			reply, err := GuardValue(func() (*pb.WorkflowOut_Reply, error) { return inst.Command(ctx, cmd) })
			var out *pb.WorkflowOut
			if err != nil {
				out = failure(cmd.GetId(), err)
				s.opts.log().Warn("ankka: a workflow command failed", "component", init.GetComponentId(), "workflow", init.GetEntityId(), "command", cmd.GetName(), "error", err)
			} else {
				reply.CommandId = cmd.GetId()
				out = &pb.WorkflowOut{Message: &pb.WorkflowOut_Reply_{Reply: reply}}
			}
			if err := send(out); err != nil {
				return ended(err)
			}
		case *pb.WorkflowIn_RunStep_:
			run := m.RunStep
			if !running.CompareAndSwap(false, true) {
				if err := send(failure(run.GetId(), fmt.Errorf("a step is already running; %s arrived beside it", run.GetStep()))); err != nil {
					return ended(err)
				}
				continue
			}
			steps.Add(1)
			go func() {
				defer steps.Done()
				reply, err := GuardValue(func() (*pb.WorkflowOut_StepReply, error) { return inst.Step(stepCtx, run) })
				running.Store(false)
				// A step that fails is a fault the engine recovers from as the workflow declares; a
				// step that decides to fail answers ThenFail, which is a reply.
				var out *pb.WorkflowOut
				if err != nil {
					out = failure(run.GetId(), err)
					s.opts.log().Warn("ankka: a workflow step failed", "component", init.GetComponentId(), "workflow", init.GetEntityId(), "step", run.GetStep(), "error", err)
				} else {
					reply.CommandId = run.GetId()
					out = &pb.WorkflowOut{Message: &pb.WorkflowOut_StepReply_{StepReply: reply}}
				}
				_ = send(out)
			}()
		case *pb.WorkflowIn_Init_:
			return status.Error(codes.InvalidArgument, "Init arrived twice on one conversation")
		}
	}
}
