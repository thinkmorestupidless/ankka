package server

import (
	"context"
	"fmt"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// The stateless conversations: a view's change and a consumer's message, one call each, nothing
// held between calls. A handler that fails, or panics, fails the call; the runtime delivers the
// change again.

// ViewHandler handles one change to the view it was declared for.
type ViewHandler func(ctx context.Context, req *pb.ViewRequest) (*pb.ViewEffect, error)

// ConsumerHandler handles one change for the consumer it was declared for.
type ConsumerHandler func(ctx context.Context, req *pb.ConsumerRequest) (*pb.ConsumerEffect, error)

type viewServer struct {
	pb.UnimplementedViewServer
	views map[string]ViewHandler
	opts  Options
}

// RegisterView serves the view conversation on s for the views given, by component id.
func RegisterView(s grpc.ServiceRegistrar, views map[string]ViewHandler, opts Options) {
	pb.RegisterViewServer(s, &viewServer{views: views, opts: opts})
}

func (s *viewServer) Handle(ctx context.Context, req *pb.ViewRequest) (*pb.ViewEffect, error) {
	h, ok := s.views[req.GetComponentId()]
	if !ok {
		return nil, status.Error(codes.NotFound, fmt.Sprintf("this process hosts no view %q", req.GetComponentId()))
	}
	ctx = s.opts.scope(ctx)
	out, err := GuardValue(func() (*pb.ViewEffect, error) { return h(ctx, req) })
	if err != nil {
		subject := entry(req.GetMetadata(), "ce-subject")
		s.opts.log().Warn("ankka: a view's change failed", "view", req.GetComponentId(), "subject", subject, "error", err)
		return nil, status.Error(codes.Internal, err.Error())
	}
	return out, nil
}

type consumerServer struct {
	pb.UnimplementedConsumerServer
	consumers map[string]ConsumerHandler
	opts      Options
}

// RegisterConsumer serves the consumer conversation on s for the consumers given, by component id.
func RegisterConsumer(s grpc.ServiceRegistrar, consumers map[string]ConsumerHandler, opts Options) {
	pb.RegisterConsumerServer(s, &consumerServer{consumers: consumers, opts: opts})
}

func (s *consumerServer) Handle(ctx context.Context, req *pb.ConsumerRequest) (*pb.ConsumerEffect, error) {
	h, ok := s.consumers[req.GetComponentId()]
	if !ok {
		return nil, status.Error(codes.NotFound, fmt.Sprintf("this process hosts no consumer %q", req.GetComponentId()))
	}
	ctx = s.opts.scope(ctx)
	out, err := GuardValue(func() (*pb.ConsumerEffect, error) { return h(ctx, req) })
	if err != nil {
		subject := entry(req.GetMetadata(), "ce-subject")
		s.opts.log().Warn("ankka: a consumer's change failed", "consumer", req.GetComponentId(), "subject", subject, "error", err)
		return nil, status.Error(codes.Internal, err.Error())
	}
	return out, nil
}

// entry is the value of the metadata entry key, or "".
func entry(md *pb.Metadata, key string) string {
	for _, e := range md.GetEntries() {
		if e.GetKey() == key {
			return e.GetValue()
		}
	}
	return ""
}
