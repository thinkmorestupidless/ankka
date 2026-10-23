package server

import (
	"context"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// Discovery answers the sidecar's first question. Discover returns the spec, or a refusal the
// sidecar sees as FAILED_PRECONDITION; Report receives each problem the sidecar refused for.
type Discovery struct {
	Discover func(ctx context.Context, info *pb.SidecarInfo) (*pb.Spec, error)
	Report   func(message string)
}

type discoveryServer struct {
	pb.UnimplementedDiscoveryServer
	d Discovery
}

// RegisterDiscovery serves d on s.
func RegisterDiscovery(s grpc.ServiceRegistrar, d Discovery) {
	pb.RegisterDiscoveryServer(s, &discoveryServer{d: d})
}

func (s *discoveryServer) Discover(ctx context.Context, info *pb.SidecarInfo) (*pb.Spec, error) {
	spec, err := s.d.Discover(ctx, info)
	if err != nil {
		return nil, status.Error(codes.FailedPrecondition, err.Error())
	}
	return spec, nil
}

func (s *discoveryServer) ReportError(_ context.Context, p *pb.Problem) (*pb.Empty, error) {
	if s.d.Report != nil {
		s.d.Report(p.GetMessage())
	}
	return &pb.Empty{}, nil
}
