package server

import (
	"context"
	"errors"
	"fmt"
	"io"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// EventSourcedInstance is one loaded entity's state, held for the life of its stream.
type EventSourcedInstance interface {
	// Recover starts from the snapshot, or from the empty state when there is none.
	Recover(ctx context.Context, init *pb.EventSourcedIn_Init) error
	// Apply folds one recovered event into the state.
	Apply(ctx context.Context, event *pb.EventSourcedIn_Event) error
	// Command handles one command, answering a reply (a refusal is a reply) or an error, which is
	// the command's failure with the state unchanged.
	Command(ctx context.Context, cmd *pb.EventSourcedIn_Command) (*pb.EventSourcedOut_Reply, error)
}

// EventSourcedHost loads an instance of the component named, or refuses one it does not host.
type EventSourcedHost func(componentID, entityID string) (EventSourcedInstance, error)

// ErrorOf is the protocol's error for a failure. A failure carrying its own (ProtocolError) keeps it.
func ErrorOf(err error) *pb.Error {
	var pe interface{ ProtocolError() *pb.Error }
	if errors.As(err, &pe) {
		return pe.ProtocolError()
	}
	return &pb.Error{Code: pb.ErrorCode_INTERNAL, Message: err.Error()}
}

type eventSourcedServer struct {
	pb.UnimplementedEventSourcedServer
	host EventSourcedHost
	opts Options
}

// RegisterEventSourced serves the event sourced conversation on s.
func RegisterEventSourced(s grpc.ServiceRegistrar, host EventSourcedHost, opts Options) {
	pb.RegisterEventSourcedServer(s, &eventSourcedServer{host: host, opts: opts})
}

func (s *eventSourcedServer) Handle(stream grpc.BidiStreamingServer[pb.EventSourcedIn, pb.EventSourcedOut]) error {
	ctx := s.opts.scope(stream.Context())
	first, err := stream.Recv()
	if err != nil {
		return ended(err)
	}
	init := first.GetInit()
	if init == nil {
		return status.Error(codes.InvalidArgument, "an event sourced conversation starts with Init")
	}
	inst, err := s.host(init.GetComponentId(), init.GetEntityId())
	if err != nil {
		return status.Error(codes.NotFound, err.Error())
	}
	if err := Guard(func() error { return inst.Recover(ctx, init) }); err != nil {
		s.opts.log().Error("ankka: recovering an entity failed", "component", init.GetComponentId(), "entity", init.GetEntityId(), "error", err)
		return status.Error(codes.Internal, fmt.Sprintf("recovering %s/%s: %v", init.GetComponentId(), init.GetEntityId(), err))
	}
	for {
		in, err := stream.Recv()
		if err != nil {
			return ended(err) // the state goes with this goroutine
		}
		switch m := in.GetMessage().(type) {
		case *pb.EventSourcedIn_Event_:
			if err := Guard(func() error { return inst.Apply(ctx, m.Event) }); err != nil {
				s.opts.log().Error("ankka: applying a recovered event failed", "component", init.GetComponentId(), "entity", init.GetEntityId(), "sequence", m.Event.GetSequence(), "error", err)
				return status.Error(codes.Internal, fmt.Sprintf("applying event %d of %s/%s: %v", m.Event.GetSequence(), init.GetComponentId(), init.GetEntityId(), err))
			}
		case *pb.EventSourcedIn_Command_:
			cmd := m.Command
			reply, err := GuardValue(func() (*pb.EventSourcedOut_Reply, error) { return inst.Command(ctx, cmd) })
			var out *pb.EventSourcedOut
			if err != nil {
				s.opts.log().Warn("ankka: a command failed", "component", init.GetComponentId(), "entity", init.GetEntityId(), "command", cmd.GetName(), "error", err)
				out = &pb.EventSourcedOut{Message: &pb.EventSourcedOut_Failure{Failure: &pb.Failure{CommandId: cmd.GetId(), Error: ErrorOf(err)}}}
			} else {
				reply.CommandId = cmd.GetId()
				out = &pb.EventSourcedOut{Message: &pb.EventSourcedOut_Reply_{Reply: reply}}
			}
			if err := stream.Send(out); err != nil {
				return ended(err)
			}
		case *pb.EventSourcedIn_Init_:
			return status.Error(codes.InvalidArgument, "Init arrived twice on one conversation")
		}
	}
}

// ended is how a conversation ends when its stream does: the sidecar closing it (passivation) or
// cancelling it (a restart) is not an error.
func ended(err error) error {
	if errors.Is(err, io.EOF) || errors.Is(err, context.Canceled) || status.Code(err) == codes.Canceled {
		return nil
	}
	return err
}
