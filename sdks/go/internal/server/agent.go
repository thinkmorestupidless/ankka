package server

import (
	"context"
	"fmt"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// The agent conversation: the sidecar asks this process to plan an interaction (an AgentEffect as
// data), to run a tool with the model's arguments, to check a guardrail and, for an autonomous
// agent, to check a task's result. The loop, the memory and the model's key are the sidecar's.

// AgentHandlers answer the agent conversation for one agent or autonomous agent. Plan is nil for an
// autonomous agent, which plans nothing; TaskResult is nil for an agent.
type AgentHandlers struct {
	Plan       func(ctx context.Context, req *pb.PlanRequest) (*pb.PlanReply, error)
	Tool       func(ctx context.Context, req *pb.ToolRequest) (string, error)
	Guardrail  func(ctx context.Context, req *pb.GuardrailRequest) (string, error)
	TaskResult func(ctx context.Context, req *pb.TaskResultRequest) (*pb.TaskResultVerdict, error)
}

type agentServer struct {
	pb.UnimplementedAgentServer
	agents map[string]AgentHandlers
	opts   Options
}

// RegisterAgent serves the agent conversation on s for the agents and autonomous agents given.
func RegisterAgent(s grpc.ServiceRegistrar, agents map[string]AgentHandlers, opts Options) {
	pb.RegisterAgentServer(s, &agentServer{agents: agents, opts: opts})
}

func planFailure(code pb.ErrorCode, message string) *pb.PlanReply {
	return &pb.PlanReply{Message: &pb.PlanReply_Failure{Failure: &pb.Failure{Error: &pb.Error{Code: code, Message: message}}}}
}

// Plan answers a failure, never a failed call, for anything that keeps a plan from being made.
func (s *agentServer) Plan(ctx context.Context, req *pb.PlanRequest) (*pb.PlanReply, error) {
	h, ok := s.agents[req.GetComponentId()]
	if !ok || h.Plan == nil {
		return planFailure(pb.ErrorCode_NOT_FOUND, fmt.Sprintf("this process hosts no agent %q", req.GetComponentId())), nil
	}
	ctx = s.opts.scope(ctx)
	out, err := GuardValue(func() (*pb.PlanReply, error) { return h.Plan(ctx, req) })
	if err != nil {
		s.opts.log().Warn("ankka: an agent's plan failed", "agent", req.GetComponentId(), "session", req.GetSessionId(), "handler", req.GetName(), "error", err)
		return planFailure(pb.ErrorCode_INTERNAL, err.Error()), nil
	}
	return out, nil
}

// InvokeTool answers a tool's error as the result the model is told, which it usually corrects.
func (s *agentServer) InvokeTool(ctx context.Context, req *pb.ToolRequest) (*pb.ToolResult, error) {
	h, ok := s.agents[req.GetComponentId()]
	if !ok {
		return &pb.ToolResult{Result: &pb.ToolResult_Error{Error: fmt.Sprintf("this process hosts no agent %q", req.GetComponentId())}}, nil
	}
	ctx = s.opts.scope(ctx)
	out, err := GuardValue(func() (string, error) { return h.Tool(ctx, req) })
	if err != nil {
		return &pb.ToolResult{Result: &pb.ToolResult_Error{Error: err.Error()}}, nil
	}
	return &pb.ToolResult{Result: &pb.ToolResult_Ok{Ok: out}}, nil
}

// CheckGuardrail answers a guardrail that failed as a block, never a pass.
func (s *agentServer) CheckGuardrail(ctx context.Context, req *pb.GuardrailRequest) (*pb.GuardrailResult, error) {
	h, ok := s.agents[req.GetComponentId()]
	if !ok {
		return block(fmt.Sprintf("no guardrail %q on %s", req.GetGuardrail(), req.GetComponentId())), nil
	}
	ctx = s.opts.scope(ctx)
	reason, err := GuardValue(func() (string, error) { return h.Guardrail(ctx, req) })
	if err != nil {
		s.opts.log().Warn("ankka: a guardrail failed", "agent", req.GetComponentId(), "guardrail", req.GetGuardrail(), "error", err)
		return block(fmt.Sprintf("guardrail %s failed: %v", req.GetGuardrail(), err)), nil
	}
	if reason != "" {
		return block(reason), nil
	}
	return &pb.GuardrailResult{Result: &pb.GuardrailResult_Pass{Pass: &pb.Empty{}}}, nil
}

func block(reason string) *pb.GuardrailResult {
	return &pb.GuardrailResult{Result: &pb.GuardrailResult_Block{Block: reason}}
}

// CheckTaskResult answers a rule that failed as a failed call, which the sidecar takes as a failed
// iteration and tries again: nothing was decided, so it is never a rejection.
func (s *agentServer) CheckTaskResult(ctx context.Context, req *pb.TaskResultRequest) (*pb.TaskResultVerdict, error) {
	h, ok := s.agents[req.GetComponentId()]
	if !ok || h.TaskResult == nil {
		return nil, status.Error(codes.NotFound, fmt.Sprintf("this process hosts no autonomous agent %q", req.GetComponentId()))
	}
	ctx = s.opts.scope(ctx)
	out, err := GuardValue(func() (*pb.TaskResultVerdict, error) { return h.TaskResult(ctx, req) })
	if err != nil {
		if _, isStatus := status.FromError(err); isStatus && status.Code(err) != codes.Unknown {
			return nil, err
		}
		s.opts.log().Warn("ankka: a task rule failed", "agent", req.GetComponentId(), "task", req.GetTaskId(), "error", err)
		return nil, status.Error(codes.Internal, err.Error())
	}
	return out, nil
}
