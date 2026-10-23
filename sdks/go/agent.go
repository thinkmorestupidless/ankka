package ankka

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"regexp"
	"strings"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"github.com/thinkmorestupidless/ankka-go/internal/server"
)

// An agent: this process declares the instructions, the tools and the guardrails; the sidecar runs
// the loop (the model call, tool dispatch, session memory, compaction, token accounting). The
// process is asked to plan an interaction, to run a tool and to check a guardrail, and nothing else.

// AgentScope is what an agent's handler, tool or guardrail sees of the session it runs for.
type AgentScope struct {
	ComponentID string
	// SessionID is the session; for an autonomous agent, "task:<task id>".
	SessionID string
	// TaskID is the task an autonomous agent's tool, guardrail or rule runs for; "" for an agent.
	TaskID   string
	Metadata Metadata
}

type agentKeyType struct{}

func withAgent(ctx context.Context, s AgentScope) context.Context {
	return withMetadata(context.WithValue(ctx, agentKeyType{}, s), s.Metadata)
}

// AgentFrom is the session an agent's handler, tool, guardrail or rule runs for.
func AgentFrom(ctx context.Context) (AgentScope, bool) {
	s, ok := ctx.Value(agentKeyType{}).(AgentScope)
	return s, ok
}

func agentScope(componentID, sessionID string, md *pb.Metadata) AgentScope {
	s := AgentScope{ComponentID: componentID, SessionID: sessionID, Metadata: metadataFromPb(md)}
	if id, ok := strings.CutPrefix(sessionID, "task:"); ok {
		s.TaskID = id
	}
	return s
}

// ── Handlers ────────────────────────────────────────────────────────────────

type agentHandler interface {
	handlerPart
	streaming() bool
	plan(ctx context.Context, payload *pb.Payload) (AgentEffect, error)
}

type askPart[I any] struct {
	handlerBase[I, string]
	stream bool
	f      func(context.Context, I) (AgentEffect, error)
}

func (a *askPart[I]) readOnly() bool  { return false }
func (a *askPart[I]) streaming() bool { return a.stream }
func (a *askPart[I]) plan(ctx context.Context, payload *pb.Payload) (AgentEffect, error) {
	in, err := a.decode(payload)
	if err != nil {
		return AgentEffect{}, err
	}
	return a.f(ctx, in)
}

// Ask is an agent's handler named wire: given its input, it describes the interaction, and the
// caller is answered with the model's reply once the loop ends.
func Ask[I any](wire string, f func(ctx context.Context, input I) (AgentEffect, error)) Part {
	return &askPart[I]{handlerBase: newBase[I, string](wire), f: f}
}

// AskStream is Ask whose reply is streamed to the caller as the model writes it.
func AskStream[I any](wire string, f func(ctx context.Context, input I) (AgentEffect, error)) Part {
	return &askPart[I]{handlerBase: newBase[I, string](wire), stream: true, f: f}
}

// ── Tools ───────────────────────────────────────────────────────────────────

// AgentTool is a tool an agent or an autonomous agent offers the model: Tool or ApprovedTool.
type AgentTool interface {
	toolName() string
	toolPb() (*pb.Tool, []string)
	run(ctx context.Context, argumentsJSON string) (string, error)
}

type toolPart[I, R any] struct {
	name, description string
	approval          *time.Duration
	f                 func(context.Context, I) (R, error)
}

// Tool is a tool named name the model may call with an I, its JSON Schema derived from I's fields
// (a field's `desc` tag describes it), answered with f's result: text as it is, anything else in
// its encoding. An error is told to the model, which usually corrects itself; it is never thrown.
// A tool may run more than once for one call of the model, after a crash, so a side effect should
// tolerate a repeat.
func Tool[I, R any](name, description string, f func(ctx context.Context, input I) (R, error)) AgentTool {
	return &toolPart[I, R]{name: name, description: description, f: f}
}

// ApprovedTool is a Tool that waits for a person's decision before it runs (1.11). Within zero
// waits until decided; otherwise the platform refuses it when that much time passes undecided.
func ApprovedTool[I, R any](name, description string, within time.Duration, f func(ctx context.Context, input I) (R, error)) AgentTool {
	return &toolPart[I, R]{name: name, description: description, approval: &within, f: f}
}

func (t *toolPart[I, R]) toolName() string { return t.name }

func (t *toolPart[I, R]) toolPb() (*pb.Tool, []string) {
	var problems []string
	if strings.TrimSpace(t.description) == "" {
		problems = append(problems, fmt.Sprintf("the tool %q has no description; the model chooses a tool by it", t.name))
	}
	it := reflect.TypeFor[I]()
	if it.Kind() != reflect.Struct || it == timeType || it == localDateType || it == localDateTimeType {
		problems = append(problems, fmt.Sprintf("the tool %q takes a %s; a tool's input is a struct, whose fields are its arguments", t.name, it))
		return nil, problems
	}
	schema, schemaProblems := schemaJSON(it)
	for _, p := range schemaProblems {
		problems = append(problems, fmt.Sprintf("the tool %q's input: %s", t.name, p))
	}
	for _, p := range CodecProblems(CodecFor[R]()) {
		problems = append(problems, fmt.Sprintf("the tool %q's result, a %s: %s", t.name, reflect.TypeFor[R](), p))
	}
	out := &pb.Tool{Name: t.name, Description: t.description, InputSchemaJson: schema}
	if t.approval != nil {
		out.Approval = approvalPb(*t.approval)
	}
	return out, problems
}

func approvalPb(within time.Duration) *pb.Approval {
	if within <= 0 {
		return &pb.Approval{}
	}
	ms := within.Milliseconds()
	return &pb.Approval{WithinMillis: &ms}
}

func (t *toolPart[I, R]) run(ctx context.Context, argumentsJSON string) (string, error) {
	if strings.TrimSpace(argumentsJSON) == "" {
		argumentsJSON = "{}"
	}
	p, problems := planFor(reflect.TypeFor[I]())
	if len(problems) > 0 {
		return "", errors.New(strings.Join(problems, "; "))
	}
	var in I
	if err := decodeJSON(p, []byte(argumentsJSON), reflect.ValueOf(&in).Elem()); err != nil {
		return "", fmt.Errorf("the arguments to %s cannot be read: %w", t.name, err)
	}
	out, err := t.f(ctx, in)
	if err != nil {
		return "", err
	}
	data, err := CodecFor[R]().Encode(out)
	if err != nil {
		return "", fmt.Errorf("the result of %s cannot be encoded: %w", t.name, err)
	}
	return string(data), nil
}

type toolsPart struct{ tools []AgentTool }

func (toolsPart) part() {}

// Tools are the tools an agent or an autonomous agent offers the model.
func Tools(tools ...AgentTool) Part { return toolsPart{tools: tools} }

// ── Guardrails ──────────────────────────────────────────────────────────────

// GuardrailStage is what a guardrail checks: what goes to the model, or what it answered.
type GuardrailStage string

const (
	// GuardrailInput is what goes to the model.
	GuardrailInput GuardrailStage = "input"
	// GuardrailOutput is what the model answered.
	GuardrailOutput GuardrailStage = "output"
)

// AgentGuardrail is a check an agent consults: Guardrail or ResultGuardrail.
type AgentGuardrail interface {
	guardrailName() string
	result() bool
	check(ctx context.Context, stage GuardrailStage, tool, text string) (string, error)
}

type guardrailPart struct {
	name     string
	isResult bool
	f        func(context.Context, GuardrailStage, string) (string, error)
	r        func(context.Context, string, string) (string, error)
}

func (g *guardrailPart) guardrailName() string { return g.name }
func (g *guardrailPart) result() bool          { return g.isResult }
func (g *guardrailPart) check(ctx context.Context, stage GuardrailStage, tool, text string) (string, error) {
	if g.isResult {
		return g.r(ctx, tool, text)
	}
	return g.f(ctx, stage, text)
}

// Guardrail is a check named name of what goes to the model and what it answers, consulted where
// the effect names it: "" passes, anything else blocks with that reason. An error blocks too.
func Guardrail(name string, f func(ctx context.Context, stage GuardrailStage, text string) (string, error)) AgentGuardrail {
	return &guardrailPart{name: name, f: f}
}

// ResultGuardrail is a check of what an MCP server's tool answered, before the model is told it
// (1.11): "" passes, anything else blocks with that reason.
func ResultGuardrail(name string, f func(ctx context.Context, tool, text string) (string, error)) AgentGuardrail {
	return &guardrailPart{name: name, isResult: true, r: f}
}

type guardrailsPart struct{ guardrails []AgentGuardrail }

func (guardrailsPart) part() {}

// Guardrails are the guardrails an agent or an autonomous agent consults.
func Guardrails(guardrails ...AgentGuardrail) Part { return guardrailsPart{guardrails: guardrails} }

// ── MCP servers ─────────────────────────────────────────────────────────────

// McpServerOption says where an MCP server is and how it is reached.
type McpServerOption func(*pb.McpServer)

// McpURL is the server's address. Unsaid, with no McpService, ANKKA_MCP_<NAME>_URL must be set.
func McpURL(url string) McpServerOption {
	return func(s *pb.McpServer) { s.Address = &pb.McpServer_Url{Url: url} }
}

// McpService is a service of the installation serving MCP at path; project "" is this one.
func McpService(project, service, path string) McpServerOption {
	return func(s *pb.McpServer) {
		svc := &pb.McpServer_Service{Name: service, Path: path}
		if project != "" {
			svc.Project = &project
		}
		s.Address = &pb.McpServer_Service_{Service: svc}
	}
}

// McpApproval makes every tool of the server wait for a person's decision; within as ApprovedTool.
func McpApproval(within time.Duration) McpServerOption {
	return func(s *pb.McpServer) { s.Approval = approvalPb(within) }
}

// McpHeader sends the header name with the value of variable, read from the sidecar's environment;
// the variable starts ANKKA_MCP_.
func McpHeader(name, variable string) McpServerOption {
	return func(s *pb.McpServer) {
		s.Headers = append(s.Headers, &pb.McpServer_Header{Name: name, Variable: variable})
	}
}

// McpServerRef is an MCP server an agent lists: the sidecar connects to it, reads its tools and
// offers them as mcp__<name>__<tool>; the process is never asked to run one.
type McpServerRef struct{ server *pb.McpServer }

// McpServer names an MCP server ([a-z0-9-]+).
func McpServer(name string, opts ...McpServerOption) McpServerRef {
	s := &pb.McpServer{Name: name}
	for _, o := range opts {
		o(s)
	}
	return McpServerRef{server: s}
}

type mcpServersPart struct{ servers []McpServerRef }

func (mcpServersPart) part() {}

// McpServers are the MCP servers whose tools are offered beside the agent's own (1.11).
func McpServers(servers ...McpServerRef) Part { return mcpServersPart{servers: servers} }

var mcpServerName = regexp.MustCompile(`^[a-z0-9-]+$`)

// ── Options ─────────────────────────────────────────────────────────────────

type maxStepsPart int

func (maxStepsPart) part() {}

// MaxToolCallSteps is how many tool calls one turn may make; 100 by default.
func MaxToolCallSteps(n int) Part { return maxStepsPart(n) }

type rolePart string

func (rolePart) part() {}

// Role describes the agent, for the console and the model's system context.
func Role(text string) Part { return rolePart(text) }

// ── The agent ───────────────────────────────────────────────────────────────

// AgentComponent is an agent's declaration.
type AgentComponent struct {
	id       string
	role     string
	maxSteps int
	handlers []agentHandler
	kit      agentKit
	parts    []Part
}

// agentKit is what an agent and an autonomous agent share: tools, guardrails, MCP servers.
type agentKit struct {
	tools            map[string]AgentTool
	toolOrder        []string
	guardrails       map[string]AgentGuardrail
	resultGuardrails map[string]AgentGuardrail
	guardrailOrder   []string
	resultOrder      []string
	mcp              []McpServerRef
	duplicates       []string
}

func newAgentKit() agentKit {
	return agentKit{tools: map[string]AgentTool{}, guardrails: map[string]AgentGuardrail{}, resultGuardrails: map[string]AgentGuardrail{}}
}

// take keeps p when it is a tool, guardrail or MCP part, and says whether it was.
func (k *agentKit) take(p Part) bool {
	switch v := p.(type) {
	case toolsPart:
		for _, t := range v.tools {
			if t == nil {
				k.duplicates = append(k.duplicates, "a tool is nil")
				continue
			}
			if _, dup := k.tools[t.toolName()]; dup {
				k.duplicates = append(k.duplicates, fmt.Sprintf("the tool %q is declared twice", t.toolName()))
				continue
			}
			k.tools[t.toolName()] = t
			k.toolOrder = append(k.toolOrder, t.toolName())
		}
	case guardrailsPart:
		for _, g := range v.guardrails {
			if g == nil {
				k.duplicates = append(k.duplicates, "a guardrail is nil")
				continue
			}
			into, order := k.guardrails, &k.guardrailOrder
			if g.result() {
				into, order = k.resultGuardrails, &k.resultOrder
			}
			if _, dup := into[g.guardrailName()]; dup {
				k.duplicates = append(k.duplicates, fmt.Sprintf("the guardrail %q is declared twice", g.guardrailName()))
				continue
			}
			into[g.guardrailName()] = g
			*order = append(*order, g.guardrailName())
		}
	case mcpServersPart:
		k.mcp = append(k.mcp, v.servers...)
	default:
		return false
	}
	return true
}

// declare checks the kit and answers its discovery form.
func (k *agentKit) declare(d *declarations, where string) (tools []*pb.Tool, guardrails, results []string, servers []*pb.McpServer) {
	for _, p := range k.duplicates {
		d.problem(where, "%s", p)
	}
	approvals := false
	for _, name := range k.toolOrder {
		if strings.TrimSpace(name) == "" {
			d.problem(where, "a tool has no name")
		}
		t, problems := k.tools[name].toolPb()
		for _, p := range problems {
			d.problem(where, "%s", p)
		}
		if t != nil {
			tools = append(tools, t)
			approvals = approvals || t.Approval != nil
		}
	}
	for _, name := range k.guardrailOrder {
		if strings.TrimSpace(name) == "" {
			d.problem(where, "a guardrail has no name")
		}
		guardrails = append(guardrails, name)
	}
	results = append(results, k.resultOrder...)
	names := map[string]bool{}
	for _, s := range k.mcp {
		if s.server == nil {
			d.problem(where, "an MCP server is declared without McpServer")
			continue
		}
		name := s.server.GetName()
		if !mcpServerName.MatchString(name) {
			d.problem(where, "the MCP server %q is not named in [a-z0-9-]+", name)
		}
		if names[name] {
			d.problem(where, "the MCP server %q is declared twice", name)
		}
		names[name] = true
		for _, h := range s.server.GetHeaders() {
			if !strings.HasPrefix(h.GetVariable(), "ANKKA_MCP_") {
				d.problem(where, "the MCP server %q's header %s reads %q; a header's variable starts ANKKA_MCP_", name, h.GetName(), h.GetVariable())
			}
		}
		approvals = approvals || s.server.Approval != nil
		servers = append(servers, s.server)
	}
	if approvals {
		d.require(11, "%s has a tool that waits for approval", where)
	}
	if len(servers) > 0 {
		d.require(11, "%s lists MCP servers", where)
	}
	if len(results) > 0 {
		d.require(11, "%s declares result guardrails", where)
	}
	return tools, guardrails, results, servers
}

func (k *agentKit) runTool(ctx context.Context, componentID string, req *pb.ToolRequest) (string, error) {
	t, ok := k.tools[req.GetTool()]
	if !ok {
		return "", fmt.Errorf("no tool %q on %s", req.GetTool(), componentID)
	}
	return t.run(withAgent(ctx, agentScope(componentID, req.GetSessionId(), req.GetMetadata())), req.GetArgumentsJson())
}

func (k *agentKit) checkGuardrail(ctx context.Context, componentID string, req *pb.GuardrailRequest) (string, error) {
	ctx = withAgent(ctx, agentScope(componentID, req.GetSessionId(), req.GetMetadata()))
	if req.GetStage() == pb.GuardrailRequest_RESULT {
		g, ok := k.resultGuardrails[req.GetGuardrail()]
		if !ok {
			return fmt.Sprintf("no result guardrail %q on %s", req.GetGuardrail(), componentID), nil
		}
		return g.check(ctx, "", req.GetTool(), req.GetText())
	}
	g, ok := k.guardrails[req.GetGuardrail()]
	if !ok {
		return fmt.Sprintf("no guardrail %q on %s", req.GetGuardrail(), componentID), nil
	}
	stage := GuardrailInput
	if req.GetStage() == pb.GuardrailRequest_OUTPUT {
		stage = GuardrailOutput
	}
	return g.check(ctx, stage, "", req.GetText())
}

// Agent declares an agent: its handlers (Ask, AskStream) and its Tools, Guardrails, McpServers,
// MaxToolCallSteps and Role.
func Agent(id string, parts ...Part) *AgentComponent {
	a := &AgentComponent{id: id, maxSteps: 100, kit: newAgentKit()}
	for _, p := range parts {
		switch v := p.(type) {
		case agentHandler:
			a.handlers = append(a.handlers, v)
		case maxStepsPart:
			a.maxSteps = int(v)
		case rolePart:
			a.role = string(v)
		default:
			if !a.kit.take(p) {
				a.parts = append(a.parts, p)
			}
		}
	}
	return a
}

// ID is the agent's component id.
func (a *AgentComponent) ID() string { return a.id }

func (a *AgentComponent) declare(d *declarations) {
	where := a.id
	if a.id == "" {
		where = "agent"
		d.problem(where, "an agent has an id")
	}
	if len(a.handlers) == 0 {
		d.problem(where, "an agent has at least one handler (Ask or AskStream)")
	}
	if a.maxSteps < 1 {
		d.problem(where, "MaxToolCallSteps is %d; a turn may make at least one tool call", a.maxSteps)
	}
	for _, p := range a.parts {
		misplaced(d, where, "agent", p)
	}
	hs := make([]handlerPart, 0, len(a.handlers))
	for _, h := range a.handlers {
		hs = append(hs, h)
	}
	handlerProblems(d, where, hs)
	declared := handlersOf(hs)
	for i, h := range a.handlers {
		declared[i].Streaming = h.streaming()
		h.declareInput(d, a.id)
	}
	tools, guardrails, results, servers := a.kit.declare(d, where)
	d.components = append(d.components, &pb.Component{
		Kind:     pb.Kind_AGENT,
		Id:       a.id,
		Handlers: declared,
		Detail: &pb.Component_Agent{Agent: &pb.AgentDetail{
			Role: a.role, MaxToolCallSteps: int32(a.maxSteps), Tools: tools, Guardrails: guardrails,
			McpServers: servers, ResultGuardrails: results,
		}},
	})
	d.agents[a.id] = server.AgentHandlers{
		Plan: a.plan,
		Tool: func(ctx context.Context, req *pb.ToolRequest) (string, error) { return a.kit.runTool(ctx, a.id, req) },
		Guardrail: func(ctx context.Context, req *pb.GuardrailRequest) (string, error) {
			return a.kit.checkGuardrail(ctx, a.id, req)
		},
	}
}

// checkPlan is why an effect cannot be planned: it names a tool or guardrail the agent did not declare.
func (a *AgentComponent) checkPlan(e AgentEffect) error {
	var unknown []string
	for _, t := range e.ToolNames {
		if _, ok := a.kit.tools[t]; !ok {
			unknown = append(unknown, fmt.Sprintf("tool %q", t))
		}
	}
	for _, g := range e.GuardrailNames {
		if _, ok := a.kit.guardrails[g]; !ok {
			unknown = append(unknown, fmt.Sprintf("guardrail %q", g))
		}
	}
	if len(unknown) > 0 {
		return fmt.Errorf("%s: the plan names undeclared %s", a.id, strings.Join(unknown, ", "))
	}
	return nil
}

func (a *AgentComponent) plan(ctx context.Context, req *pb.PlanRequest) (*pb.PlanReply, error) {
	var h agentHandler
	for _, candidate := range a.handlers {
		if candidate.wire() == req.GetName() {
			h = candidate
		}
	}
	failure := func(code ErrorCode, message string) *pb.PlanReply {
		return &pb.PlanReply{Message: &pb.PlanReply_Failure{Failure: &pb.Failure{Error: &pb.Error{Code: pb.ErrorCode(code), Message: message}}}}
	}
	if h == nil {
		return failure(NotFound, fmt.Sprintf("no handler %q on %s", req.GetName(), a.id)), nil
	}
	ctx = withAgent(ctx, agentScope(a.id, req.GetSessionId(), req.GetMetadata()))
	eff, err := h.plan(ctx, req.GetPayload())
	if err != nil {
		var r *RefusalError
		if errors.As(err, &r) {
			return failure(r.Code, r.Message), nil
		}
		return nil, err
	}
	if !eff.set {
		return nil, fmt.Errorf("%s/%s returned no effect: build one with SystemMessage, UserMessage, AgentModel or AgentError", a.id, req.GetName())
	}
	if err := a.checkPlan(eff); err != nil {
		return nil, err
	}
	return &pb.PlanReply{Message: &pb.PlanReply_Plan{Plan: eff.toPb()}}, nil
}

// toPb is the effect as the plan the sidecar's loop interprets.
func (e AgentEffect) toPb() *pb.AgentPlan {
	opt := func(s string) *string {
		if s == "" {
			return nil
		}
		return &s
	}
	p := &pb.AgentPlan{Model: opt(e.Model), System: opt(e.System), User: opt(e.User), Context: e.Context, Tools: e.ToolNames, Guardrails: e.GuardrailNames}
	if !e.SessionMemory {
		p.Memory = pb.AgentPlan_NONE
	}
	if e.JSONReply {
		p.ResponseShape = &pb.AgentPlan_ResponseShape{Shape: &pb.AgentPlan_ResponseShape_Json_{Json: &pb.AgentPlan_ResponseShape_Json{SchemaHint: e.SchemaHint}}}
	} else {
		p.ResponseShape = &pb.AgentPlan_ResponseShape{Shape: &pb.AgentPlan_ResponseShape_Text{Text: &pb.Empty{}}}
	}
	if e.failure != nil {
		p.Failure = &pb.Error{Code: pb.ErrorCode(e.failure.Code), Message: e.failure.Message}
	}
	return p
}
