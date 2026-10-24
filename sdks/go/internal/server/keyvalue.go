package server

import (
	"fmt"

	"context"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// KeyValueInstance is one loaded key value entity's state, held for the life of its stream.
type KeyValueInstance interface {
	// Recover starts from the state the sidecar holds, or from the empty state when there is none.
	Recover(ctx context.Context, init *pb.KeyValueIn_Init) error
	// Command handles one command, answering a reply (a refusal is a reply) or an error, which is
	// the command's failure with the state unchanged.
	Command(ctx context.Context, cmd *pb.KeyValueIn_Command) (*pb.KeyValueOut_Reply, error)
}

// KeyValueHost loads an instance of the component named, or refuses one it does not host.
type KeyValueHost func(componentID, entityID string) (KeyValueInstance, error)

type keyValueServer struct {
	pb.UnimplementedKeyValueServer
	host KeyValueHost
	opts Options
}

// RegisterKeyValue serves the key value conversation on s.
func RegisterKeyValue(s grpc.ServiceRegistrar, host KeyValueHost, opts Options) {
	pb.RegisterKeyValueServer(s, &keyValueServer{host: host, opts: opts})
}

func (s *keyValueServer) Handle(stream grpc.BidiStreamingServer[pb.KeyValueIn, pb.KeyValueOut]) error {
	ctx := s.opts.scope(stream.Context())
	first, err := stream.Recv()
	if err != nil {
		return ended(err)
	}
	init := first.GetInit()
	if init == nil {
		return status.Error(codes.InvalidArgument, "a key value conversation starts with Init")
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
			return ended(err)
		}
		switch m := in.GetMessage().(type) {
		case *pb.KeyValueIn_Command_:
			cmd := m.Command
			reply, err := GuardValue(func() (*pb.KeyValueOut_Reply, error) { return inst.Command(ctx, cmd) })
			var out *pb.KeyValueOut
			if err != nil {
				s.opts.log().Warn("ankka: a command failed", "component", init.GetComponentId(), "entity", init.GetEntityId(), "command", cmd.GetName(), "error", err)
				out = &pb.KeyValueOut{Message: &pb.KeyValueOut_Failure{Failure: &pb.Failure{CommandId: cmd.GetId(), Error: ErrorOf(err)}}}
			} else {
				reply.CommandId = cmd.GetId()
				out = &pb.KeyValueOut{Message: &pb.KeyValueOut_Reply_{Reply: reply}}
			}
			if err := stream.Send(out); err != nil {
				return ended(err)
			}
		case *pb.KeyValueIn_Init_:
			return status.Error(codes.InvalidArgument, "Init arrived twice on one conversation")
		}
	}
}
