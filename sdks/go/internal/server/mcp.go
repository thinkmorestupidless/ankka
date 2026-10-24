package server

import (
	"context"
	"fmt"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc"
)

// McpHandler runs the tools of one MCP endpoint (1.20). The platform has answered the protocol,
// verified the credential and checked the arguments against the tool's schema. An error is the tool
// error the client is shown, which a model can act on.
type McpHandler func(ctx context.Context, req *pb.McpToolRequest) (*pb.McpToolAnswer, error)

type mcpServer struct {
	pb.UnimplementedMcpServiceServer
	endpoints map[string]McpHandler
	opts      Options
}

// RegisterMcp serves the MCP tool conversation on s for the endpoints given, by endpoint id.
func RegisterMcp(s grpc.ServiceRegistrar, endpoints map[string]McpHandler, opts Options) {
	pb.RegisterMcpServiceServer(s, &mcpServer{endpoints: endpoints, opts: opts})
}

// InvokeTool answers failure only when there is no such endpoint; whatever a tool does wrong is
// its error, panics included.
func (s *mcpServer) InvokeTool(ctx context.Context, req *pb.McpToolRequest) (*pb.McpToolReply, error) {
	h, ok := s.endpoints[req.GetEndpointId()]
	if !ok {
		return &pb.McpToolReply{Reply: &pb.McpToolReply_Failure{Failure: &pb.Failure{Error: &pb.Error{
			Code: pb.ErrorCode_NOT_FOUND, Message: fmt.Sprintf("no tool %s/%s is registered", req.GetEndpointId(), req.GetTool()),
		}}}}, nil
	}
	ctx = s.opts.scope(ctx)
	out, err := GuardValue(func() (*pb.McpToolAnswer, error) { return h(ctx, req) })
	if err != nil {
		s.opts.log().Info("ankka: an MCP tool answered an error", "endpoint", req.GetEndpointId(), "tool", req.GetTool(), "error", err)
		return &pb.McpToolReply{Reply: &pb.McpToolReply_Error{Error: err.Error()}}, nil
	}
	return &pb.McpToolReply{Reply: &pb.McpToolReply_Ok{Ok: out}}, nil
}
