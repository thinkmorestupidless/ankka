package server

import (
	"context"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc"
)

// HttpHost runs the process's routes. Handle answers a request route; an error is the request's
// failure, which the sidecar answers 500.
type HttpHost struct {
	Handle func(ctx context.Context, req *pb.HttpRequest) (*pb.HttpResponse, error)
}

type httpServer struct {
	pb.UnimplementedHttpServer
	host HttpHost
	opts Options
}

// RegisterHttp serves the process's routes on s.
func RegisterHttp(s grpc.ServiceRegistrar, host HttpHost, opts Options) {
	pb.RegisterHttpServer(s, &httpServer{host: host, opts: opts})
}

func (s *httpServer) Handle(ctx context.Context, req *pb.HttpRequest) (*pb.HttpReply, error) {
	ctx = s.opts.scope(ctx)
	res, err := GuardValue(func() (*pb.HttpResponse, error) { return s.host.Handle(ctx, req) })
	if err != nil {
		s.opts.log().Error("ankka: a route failed", "endpoint", req.GetEndpointId(), "route", req.GetRouteId(), "error", err)
		return &pb.HttpReply{Message: &pb.HttpReply_Failure{Failure: &pb.Failure{Error: ErrorOf(err)}}}, nil
	}
	return &pb.HttpReply{Message: &pb.HttpReply_Response{Response: res}}, nil
}
