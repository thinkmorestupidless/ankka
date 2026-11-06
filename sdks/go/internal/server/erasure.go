package server

import (
	"context"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc"
)

// ErasureHandler runs the service's erasure handler (1.23) for one application of an erasure: what
// the platform cannot erase for it, such as objects in a bucket.
type ErasureHandler func(ctx context.Context, call *pb.ErasureCall) (*pb.ErasureReply, error)

type erasureServer struct {
	pb.UnimplementedErasureServer
	handler ErasureHandler
	opts    Options
}

// RegisterErasure serves the Erasure service on s.
func RegisterErasure(s grpc.ServiceRegistrar, h ErasureHandler, opts Options) {
	pb.RegisterErasureServer(s, &erasureServer{handler: h, opts: opts})
}

// Invoke answers the handler's report, or its error in the reply: the sidecar records the run as
// failed and runs it again on the next pass. A panic is the handler's error too.
func (s *erasureServer) Invoke(ctx context.Context, call *pb.ErasureCall) (*pb.ErasureReply, error) {
	ctx = s.opts.scope(ctx)
	out, err := GuardValue(func() (*pb.ErasureReply, error) { return s.handler(ctx, call) })
	if err != nil {
		s.opts.log().Warn("ankka: the erasure handler failed", "erasureSeq", call.GetErasureSeq(), "rerun", call.GetRerun(), "error", err)
		return &pb.ErasureReply{Error: ErrorOf(err)}, nil
	}
	return out, nil
}
