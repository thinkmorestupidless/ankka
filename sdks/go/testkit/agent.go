package testkit

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"time"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"github.com/thinkmorestupidless/ankka-go/internal/server"
)

// Message is one message of a model call: who said it (user, assistant or tool) and what.
type Message struct {
	Role string
	Text string
}

// ModelCall is what the model saw when it answered; the scripted model records one per call.
type ModelCall struct {
	System   string
	Messages []Message
	Tools    []string
}

type modelAnswer struct {
	kind string // text, tool-call, refusal
	text string
	tool string
}

// ScriptedModel answers an agent's model calls from a script, in order, and fails loudly when the
// script runs out: a kit that answered something would pass an agent that asked one call too many.
type ScriptedModel struct {
	script []modelAnswer
	// Calls are the model calls made so far.
	Calls []ModelCall
}

// NewScriptedModel is a model with an empty script.
func NewScriptedModel() *ScriptedModel { return &ScriptedModel{} }

// ExpectText scripts an answer of text, which ends the turn.
func (m *ScriptedModel) ExpectText(text string) *ScriptedModel {
	m.script = append(m.script, modelAnswer{kind: "text", text: text})
	return m
}

// ExpectToolCall scripts a call of tool with args, a value encoded as JSON (a string is taken as
// JSON already).
func (m *ScriptedModel) ExpectToolCall(tool string, args any) *ScriptedModel {
	var text string
	switch a := args.(type) {
	case string:
		text = a
	case nil:
		text = "{}"
	default:
		b, err := json.Marshal(a)
		if err != nil {
			panic(fmt.Sprintf("testkit: the arguments of %s cannot be encoded: %v", tool, err))
		}
		text = string(b)
	}
	m.script = append(m.script, modelAnswer{kind: "tool-call", tool: tool, text: text})
	return m
}

// ExpectRefusal scripts the model refusing, with message.
func (m *ScriptedModel) ExpectRefusal(message string) *ScriptedModel {
	m.script = append(m.script, modelAnswer{kind: "refusal", text: message})
	return m
}

// Remaining is how many scripted answers are left.
func (m *ScriptedModel) Remaining() int { return len(m.script) }

func (m *ScriptedModel) next(call ModelCall) (modelAnswer, error) {
	m.Calls = append(m.Calls, call)
	if len(m.script) == 0 {
		return modelAnswer{}, fmt.Errorf("the scripted model ran out of answers after %d call(s); script more with ExpectText, ExpectToolCall or ExpectRefusal", len(m.Calls))
	}
	a := m.script[0]
	m.script = m.script[1:]
	return a, nil
}

// McpScript answers the tools of an agent's MCP servers: server name, then tool name, to a
// function of the model's arguments. The model calls them as mcp__<server>__<tool>.
type McpScript map[string]map[string]func(args map[string]any) (string, error)

// AgentOption is an option of an agent kit.
type AgentOption func(*agentOptions)

type agentOptions struct {
	options
	mcp McpScript
}

// AgentCalling gives the agent kit the components the agent's tools call.
func AgentCalling(components ...ankka.Declaration) AgentOption {
	return func(o *agentOptions) { o.calling = append(o.calling, components...) }
}

// ScriptedMcp scripts the agent's MCP servers.
func ScriptedMcp(s McpScript) AgentOption { return func(o *agentOptions) { o.mcp = s } }

type pendingTool struct {
	request ankka.ApprovalRequest
	tool    string
	args    string
}

type turn struct {
	plan     *pb.AgentPlan
	user     string
	messages []Message
	steps    int
	pending  *pendingTool
}

// AgentKit runs an agent's turns the way the sidecar does, in this process: the plan, the input
// guardrails, the model (scripted), the tool calls back into the agent, the output guardrails and
// the session's memory. A call of a tool that waits for approval is not run: the turn answers an
// *ankka.ApprovalAwaitedError, and Decide goes on.
type AgentKit struct {
	*hosted
	id      string
	session string
	model   *ScriptedModel
	mcp     McpScript
	detail  *pb.AgentDetail
	h       server.AgentHandlers
	turn    *turn
	decided map[string]bool
	next    int
	// History is the session's memory: what the user said and the agent answered, turn by turn.
	History []Message
}

// Agent is a kit for the agent a in session sessionID, answering its model calls from model.
func Agent(a *ankka.AgentComponent, sessionID string, model *ScriptedModel, opts ...AgentOption) *AgentKit {
	o := agentOptions{options: settings(nil)}
	for _, f := range opts {
		f(&o)
	}
	h := host(a, o.options)
	return &AgentKit{hosted: h, id: a.ID(), session: sessionID, model: model, mcp: o.mcp,
		detail: h.view.Components[a.ID()].GetAgent(), h: h.view.Agents[a.ID()], decided: map[string]bool{}}
}

// Ask runs one turn of the handler wire with input, answering the reply's text (JSON text for a
// JSON reply). A refusal, a blocked guardrail or the model's refusal is a *RefusalError; a turn
// that waits for approval is an *ankka.ApprovalAwaitedError.
func (k *AgentKit) Ask(ctx context.Context, wire string, input any) (string, error) {
	if k.turn != nil && k.turn.pending != nil {
		return "", &ankka.RefusalError{Code: ankka.Conflict, Message: fmt.Sprintf("session %q has a turn awaiting a decision", k.session)}
	}
	ctx = k.ctx(ctx)
	p, err := k.view.Input(k.id, wire, input)
	if err != nil {
		return "", err
	}
	reply, err := server.GuardValue(func() (*pb.PlanReply, error) {
		return k.h.Plan(ctx, &pb.PlanRequest{ComponentId: k.id, SessionId: k.session, Name: wire, Payload: p, Metadata: &pb.Metadata{}})
	})
	if err != nil {
		return "", err
	}
	if f := reply.GetFailure(); f != nil {
		return "", fmt.Errorf("the plan failed: %s", f.GetError().GetMessage())
	}
	plan := reply.GetPlan()
	if f := plan.GetFailure(); f != nil {
		return "", refusalOf(f)
	}
	var parts []string
	for _, c := range append(append([]string{}, plan.GetContext()...), plan.GetUser()) {
		if c != "" {
			parts = append(parts, c)
		}
	}
	user := strings.Join(parts, "\n\n")
	if err := k.guard(ctx, plan, pb.GuardrailRequest_INPUT, user); err != nil {
		return "", err
	}
	t := &turn{plan: plan, user: plan.GetUser()}
	if plan.GetMemory() == pb.AgentPlan_SESSION {
		t.messages = append(t.messages, k.History...)
	}
	t.messages = append(t.messages, Message{Role: "user", Text: user})
	k.turn = t
	return k.goOn(ctx, t)
}

// Stream runs a streaming handler's turn, answering its reply in the parts a model streams: the
// text split after each space.
func (k *AgentKit) Stream(ctx context.Context, wire string, input any) ([]string, error) {
	text, err := k.Ask(ctx, wire, input)
	if err != nil {
		return nil, err
	}
	var out []string
	for text != "" {
		i := strings.IndexByte(text, ' ')
		if i < 0 {
			out = append(out, text)
			break
		}
		out = append(out, text[:i+1])
		text = text[i+1:]
	}
	return out, nil
}

// Decide decides the approval request the turn waits on and goes on as the sidecar would: approved,
// the tool runs and the model is told its result; refused, the model is told who refused it and why.
func (k *AgentKit) Decide(ctx context.Context, approvalID string, approved bool, by, note string) (string, error) {
	if strings.TrimSpace(by) == "" {
		return "", &ankka.RefusalError{Code: ankka.BadRequest, Message: "a decision names who made it"}
	}
	if k.decided[approvalID] {
		return "", &ankka.RefusalError{Code: ankka.Conflict, Message: fmt.Sprintf("approval request %q is decided", approvalID)}
	}
	t := k.turn
	if t == nil || t.pending == nil || t.pending.request.ID != approvalID {
		return "", &ankka.RefusalError{Code: ankka.NotFound, Message: fmt.Sprintf("no approval request %q in session %q", approvalID, k.session)}
	}
	ctx = k.ctx(ctx)
	p := t.pending
	t.pending = nil
	k.decided[approvalID] = true
	var result string
	if approved {
		result = k.run(ctx, p.tool, p.args)
	} else {
		result = fmt.Sprintf("A person (%s) refused this tool call; the tool did not run.", by)
		if note != "" {
			result += " Their note: " + note
		}
	}
	t.messages = append(t.messages, Message{Role: "tool", Text: result})
	return k.goOn(ctx, t)
}

func (k *AgentKit) guard(ctx context.Context, plan *pb.AgentPlan, stage pb.GuardrailRequest_Stage, text string) error {
	for _, name := range plan.GetGuardrails() {
		reason, err := server.GuardValue(func() (string, error) {
			return k.h.Guardrail(ctx, &pb.GuardrailRequest{ComponentId: k.id, SessionId: k.session, Guardrail: name, Stage: stage, Text: text, Metadata: &pb.Metadata{}})
		})
		if err != nil {
			reason = err.Error()
		}
		if reason != "" {
			return &ankka.RefusalError{Code: ankka.Forbidden, Message: name + ": " + reason}
		}
	}
	return nil
}

const mcpPrefix = "mcp__"

func mcpTool(name string) (srv, tool string, ok bool) {
	rest, ok := strings.CutPrefix(name, mcpPrefix)
	if !ok {
		return "", "", false
	}
	srv, tool, ok = strings.Cut(rest, "__")
	return srv, tool, ok
}

func (k *AgentKit) offered(plan *pb.AgentPlan) []string {
	out := append([]string{}, plan.GetTools()...)
	for _, s := range k.detail.GetMcpServers() {
		tools := k.mcp[s.GetName()]
		names := make([]string, 0, len(tools))
		for t := range tools {
			names = append(names, mcpPrefix+s.GetName()+"__"+t)
		}
		sort.Strings(names)
		out = append(out, names...)
	}
	return out
}

func (k *AgentKit) approval(name string) (*pb.Approval, bool) {
	if s, _, ok := mcpTool(name); ok {
		for _, m := range k.detail.GetMcpServers() {
			if m.GetName() == s && m.Approval != nil {
				return m.Approval, true
			}
		}
		return nil, false
	}
	for _, t := range k.detail.GetTools() {
		if t.GetName() == name && t.Approval != nil {
			return t.Approval, true
		}
	}
	return nil, false
}

// run runs a tool, answering what the model is told: its result, or its error.
func (k *AgentKit) run(ctx context.Context, name, args string) string {
	if s, tool, ok := mcpTool(name); ok {
		f := k.mcp[s][tool]
		var a map[string]any
		if err := json.Unmarshal([]byte(args), &a); err != nil {
			return "error: " + err.Error()
		}
		text, err := f(a)
		if err != nil {
			return "error: " + err.Error()
		}
		for _, g := range k.detail.GetResultGuardrails() {
			reason, err := server.GuardValue(func() (string, error) {
				return k.h.Guardrail(ctx, &pb.GuardrailRequest{ComponentId: k.id, SessionId: k.session, Guardrail: g, Stage: pb.GuardrailRequest_RESULT, Text: text, Tool: &name, Metadata: &pb.Metadata{}})
			})
			if err != nil {
				reason = err.Error()
			}
			if reason != "" {
				return "A result guardrail refused what this tool answered, so it is not shown: " + g + ": " + reason
			}
		}
		return text
	}
	text, err := server.GuardValue(func() (string, error) {
		return k.h.Tool(ctx, &pb.ToolRequest{ComponentId: k.id, SessionId: k.session, Tool: name, ArgumentsJson: args, Metadata: &pb.Metadata{}})
	})
	if err != nil {
		return "error: " + err.Error()
	}
	return text
}

func (k *AgentKit) goOn(ctx context.Context, t *turn) (string, error) {
	offered := k.offered(t.plan)
	limit := int(k.detail.GetMaxToolCallSteps())
	if limit <= 0 {
		limit = 10
	}
	for t.steps <= limit {
		a, err := k.model.next(ModelCall{System: t.plan.GetSystem(), Messages: append([]Message{}, t.messages...), Tools: offered})
		if err != nil {
			k.turn = nil
			return "", err
		}
		switch a.kind {
		case "refusal":
			k.turn = nil
			return "", &ankka.RefusalError{Code: ankka.BadRequest, Message: a.text}
		case "tool-call":
			t.steps++
			found := false
			for _, o := range offered {
				found = found || o == a.tool
			}
			if !found {
				k.turn = nil
				return "", fmt.Errorf("the model called %q, which the plan did not offer (it offered %v)", a.tool, offered)
			}
			t.messages = append(t.messages, Message{Role: "assistant", Text: fmt.Sprintf("[tool call %s %s]", a.tool, a.text)})
			if ap, ok := k.approval(a.tool); ok {
				k.next++
				now := time.Now()
				req := ankka.ApprovalRequest{ID: fmt.Sprintf("approval-%d", k.next), Tool: a.tool, ArgumentsJSON: a.text, RequestedAt: now}
				if ap.WithinMillis != nil {
					at := now.Add(time.Duration(ap.GetWithinMillis()) * time.Millisecond)
					req.ExpiresAt = &at
				}
				t.pending = &pendingTool{request: req, tool: a.tool, args: a.text}
				return "", &ankka.ApprovalAwaitedError{Requests: []ankka.ApprovalRequest{req}}
			}
			t.messages = append(t.messages, Message{Role: "tool", Text: k.run(ctx, a.tool, a.text)})
		default:
			k.turn = nil
			if err := k.guard(ctx, t.plan, pb.GuardrailRequest_OUTPUT, a.text); err != nil {
				return "", err
			}
			if t.plan.GetMemory() == pb.AgentPlan_SESSION {
				k.History = append(k.History, Message{Role: "user", Text: t.user}, Message{Role: "assistant", Text: a.text})
			}
			return a.text, nil
		}
	}
	k.turn = nil
	return "", &ankka.RefusalError{Code: ankka.Internal, Message: fmt.Sprintf("the agent made more than %d tool calls", limit)}
}

// ---- MCP endpoints ----

// McpAnswer is what a tool answered: its text, and the same as structured JSON when it was a JSON
// object.
type McpAnswer struct {
	Text           string
	StructuredJSON string
}

// McpKit runs an MCP endpoint's tools as the platform would after it has checked the caller and the
// arguments; the components its references call are hosted in this process.
type McpKit struct {
	*hosted
	path string
}

// Mcp is a kit for the MCP endpoint e and the components and endpoints its tools reach.
func Mcp(e *ankka.McpEndpoint, components ...ankka.Declaration) *McpKit {
	return &McpKit{hosted: host(e, options{calling: components}), path: e.Path()}
}

// Call runs tool with args (a value encoded as JSON; a string is taken as JSON already) as the
// principal, or no one when nil. The tool's error is returned as the error.
func (k *McpKit) Call(ctx context.Context, tool string, args any, principal *ankka.Principal) (McpAnswer, error) {
	var text string
	switch a := args.(type) {
	case string:
		text = a
	case nil:
		text = "{}"
	default:
		b, err := json.Marshal(a)
		if err != nil {
			return McpAnswer{}, err
		}
		text = string(b)
	}
	req := &pb.McpToolRequest{EndpointId: k.path, Tool: tool, ArgumentsJson: text, Principal: principalOf(principal), Metadata: &pb.Metadata{}}
	out, err := server.GuardValue(func() (*pb.McpToolAnswer, error) { return k.view.Mcp[k.path](k.ctx(ctx), req) })
	if err != nil {
		return McpAnswer{}, err
	}
	return McpAnswer{Text: out.GetText(), StructuredJSON: out.GetStructuredJson()}, nil
}
