package ankka

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

type aLookup struct {
	CartID string   `json:"cartId" desc:"The cart to look up"`
	Limit  *int     `json:"limit" desc:"How many items at most"`
	Tags   []string `json:"tags"`
}

type aCartSummary struct {
	CartID string `json:"cartId"`
	Items  int    `json:"items"`
}

type aTree struct {
	Name     string  `json:"name"`
	Children []aTree `json:"children"`
}

var aSeenScope AgentScope

var aAssistant = Agent("a-assistant",
	Role("Helps with carts"),
	MaxToolCallSteps(7),
	Ask("ask", func(ctx context.Context, q string) (AgentEffect, error) {
		aSeenScope, _ = AgentFrom(ctx)
		switch q {
		case "refuse":
			return AgentEffect{}, Refuse(Forbidden, "not for you")
		case "error":
			return AgentError(BadRequest, "ask properly"), nil
		case "undeclared":
			return SystemMessage("x").Tools("nope"), nil
		case "nothing":
			return AgentEffect{}, nil
		case "panic":
			panic("the handler broke")
		}
		return SystemMessage("You help with carts.").UserMessage(q).WithContext("cart c1 has 2 items").
			Tools("lookup").Guardrails("no-secrets").Memory(false).ThenReplyJSON("a summary"), nil
	}),
	AskStream("chat", func(ctx context.Context, q string) (AgentEffect, error) {
		return AgentModel("fast").UserMessage(q).ThenReply(), nil
	}),
	Tools(
		Tool("lookup", "Looks a cart up", func(ctx context.Context, in aLookup) (aCartSummary, error) {
			if in.CartID == "missing" {
				return aCartSummary{}, errors.New("no cart called missing")
			}
			if in.CartID == "panic" {
				panic("the tool broke")
			}
			s, _ := AgentFrom(ctx)
			items := len(in.Tags)
			if in.Limit != nil {
				items = *in.Limit
			}
			return aCartSummary{CartID: in.CartID + "@" + s.SessionID, Items: items}, nil
		}),
		ApprovedTool("clear", "Empties a cart", 2*time.Minute, func(ctx context.Context, in aLookup) (string, error) {
			return "cleared " + in.CartID, nil
		}),
	),
	Guardrails(
		Guardrail("no-secrets", func(ctx context.Context, stage GuardrailStage, text string) (string, error) {
			if strings.Contains(text, "sk-") {
				return string(stage) + ": a key leaked", nil
			}
			if text == "fail" {
				return "", errors.New("the check broke")
			}
			return "", nil
		}),
		ResultGuardrail("clean-results", func(ctx context.Context, tool, text string) (string, error) {
			if strings.Contains(text, "<script>") {
				return tool + " answered a script", nil
			}
			return "", nil
		}),
	),
	McpServers(McpServer("docs", McpService("", "docs", "/mcp"), McpApproval(0), McpHeader("X-Key", "ANKKA_MCP_DOCS_KEY"))),
)

func TestAToolsInputYieldsItsJSONSchema(t *testing.T) {
	got, problems := schemaOf(reflect.TypeFor[aLookup]())
	if len(problems) > 0 {
		t.Fatal(problems)
	}
	want := map[string]any{
		"type": "object",
		"properties": map[string]any{
			"cartId": map[string]any{"type": "string", "description": "The cart to look up"},
			"limit":  map[string]any{"anyOf": []any{map[string]any{"type": "integer"}, map[string]any{"type": "null"}}, "description": "How many items at most"},
			"tags":   map[string]any{"type": "array", "items": map[string]any{"type": "string"}},
		},
		"required":             []string{"cartId", "tags"},
		"additionalProperties": false,
	}
	if !reflect.DeepEqual(got, want) {
		gj, _ := json.Marshal(got)
		t.Fatalf("schema %s", gj)
	}
	tree, _ := schemaJSON(reflect.TypeFor[aTree]())
	if !strings.Contains(tree, `"$ref":"#/$defs/aTree"`) || !strings.Contains(tree, `"$defs":{"aTree":`) {
		t.Errorf("a recursive type's schema is %s", tree)
	}
	_, problems = schemaOf(reflect.TypeFor[struct{ F any }]())
	if len(problems) == 0 {
		t.Error("a field holding any value has a schema")
	}
}

func TestAnAgentIsDeclared(t *testing.T) {
	svc := NewService(aAssistant)
	if p := svc.Problems(); len(p) > 0 {
		t.Fatalf("problems: %v", p)
	}
	c := svc.Spec().GetComponents()[0]
	a := c.GetAgent()
	if c.GetKind() != pb.Kind_AGENT || a.GetRole() != "Helps with carts" || a.GetMaxToolCallSteps() != 7 {
		t.Fatalf("declared %v", c)
	}
	if len(c.GetHandlers()) != 2 || c.GetHandlers()[0].GetStreaming() || !c.GetHandlers()[1].GetStreaming() {
		t.Errorf("handlers %v", c.GetHandlers())
	}
	if len(a.GetTools()) != 2 || a.GetTools()[0].GetApproval() != nil || a.GetTools()[1].GetApproval().GetWithinMillis() != 120000 {
		t.Errorf("tools %v", a.GetTools())
	}
	if !strings.Contains(a.GetTools()[0].GetInputSchemaJson(), `"description":"The cart to look up"`) {
		t.Errorf("the tool's schema is %s", a.GetTools()[0].GetInputSchemaJson())
	}
	if !reflect.DeepEqual(a.GetGuardrails(), []string{"no-secrets"}) || !reflect.DeepEqual(a.GetResultGuardrails(), []string{"clean-results"}) {
		t.Errorf("guardrails %v, results %v", a.GetGuardrails(), a.GetResultGuardrails())
	}
	m := a.GetMcpServers()[0]
	if m.GetName() != "docs" || m.GetService().GetName() != "docs" || m.GetService().Project != nil || m.GetApproval() == nil || m.GetApproval().WithinMillis != nil || m.GetHeaders()[0].GetVariable() != "ANKKA_MCP_DOCS_KEY" {
		t.Errorf("MCP server %v", m)
	}
	if why := svc.decls.refusal("1.10"); !strings.Contains(why, "needs 1.11") {
		t.Errorf("a 1.10 sidecar is refused with %q", why)
	}
}

func TestAnAgentsPlanToolsAndGuardrails(t *testing.T) {
	client := pb.NewAgentClient(loopback(t, NewService(aAssistant)))
	ctx := context.Background()
	plan := func(name, q string) *pb.PlanReply {
		t.Helper()
		p, _ := encodeAny(q)
		out, err := client.Plan(ctx, &pb.PlanRequest{ComponentId: "a-assistant", SessionId: "s1", Name: name, Payload: p, Metadata: metadataToPb(Metadata{"traceparent": "00-t"})})
		if err != nil {
			t.Fatalf("plan failed its call: %v", err)
		}
		return out
	}
	p := plan("ask", "how many?").GetPlan()
	if p.GetSystem() != "You help with carts." || p.GetUser() != "how many?" || p.Model != nil || p.GetMemory() != pb.AgentPlan_NONE ||
		!reflect.DeepEqual(p.GetContext(), []string{"cart c1 has 2 items"}) || !reflect.DeepEqual(p.GetTools(), []string{"lookup"}) ||
		!reflect.DeepEqual(p.GetGuardrails(), []string{"no-secrets"}) || p.GetResponseShape().GetJson().GetSchemaHint() != "a summary" {
		t.Fatalf("plan %v", p)
	}
	if aSeenScope.SessionID != "s1" || aSeenScope.Metadata["traceparent"] != "00-t" || aSeenScope.TaskID != "" {
		t.Errorf("the handler saw %+v", aSeenScope)
	}
	if s := plan("chat", "hi").GetPlan(); s.GetModel() != "fast" || s.GetResponseShape().GetText() == nil || s.GetMemory() != pb.AgentPlan_SESSION {
		t.Errorf("the stream's plan %v", s)
	}
	if f := plan("ask", "error").GetPlan().GetFailure(); f.GetCode() != pb.ErrorCode_BAD_REQUEST || f.GetMessage() != "ask properly" {
		t.Errorf("AgentError planned %v", f)
	}
	for q, want := range map[string]struct {
		code pb.ErrorCode
		text string
	}{
		"refuse":     {pb.ErrorCode_FORBIDDEN, "not for you"},
		"undeclared": {pb.ErrorCode_INTERNAL, `undeclared tool "nope"`},
		"nothing":    {pb.ErrorCode_INTERNAL, "no effect"},
		"panic":      {pb.ErrorCode_INTERNAL, "the handler broke"},
	} {
		f := plan("ask", q).GetFailure().GetError()
		if f.GetCode() != want.code || !strings.Contains(f.GetMessage(), want.text) {
			t.Errorf("%s planned %v", q, f)
		}
	}
	if f := plan("nap", "x").GetFailure().GetError(); f.GetCode() != pb.ErrorCode_NOT_FOUND {
		t.Errorf("an unknown handler planned %v", f)
	}

	tool := func(name, args string) *pb.ToolResult {
		t.Helper()
		out, err := client.InvokeTool(ctx, &pb.ToolRequest{ComponentId: "a-assistant", SessionId: "s1", Tool: name, ArgumentsJson: args})
		if err != nil {
			t.Fatalf("a tool failed its call: %v", err)
		}
		return out
	}
	if ok := tool("lookup", `{"cartId":"c1","limit":3,"tags":[]}`).GetOk(); ok != `{"cartId":"c1@s1","items":3}` {
		t.Errorf("lookup answered %q", ok)
	}
	if ok := tool("clear", `{"cartId":"c1","tags":["a"]}`).GetOk(); ok != "cleared c1" {
		t.Errorf("a text result is %q", ok)
	}
	for args, want := range map[string]string{
		`{"cartId":"missing","tags":[]}`: "no cart called missing",
		`{"cartId":"panic","tags":[]}`:   "the tool broke",
		`{"cartId":7,"tags":[]}`:         "cannot be read",
		`{"tags":[]}`:                    "missing required field",
	} {
		if e := tool("lookup", args).GetError(); !strings.Contains(e, want) {
			t.Errorf("%s told the model %q", args, e)
		}
	}
	if e := tool("nope", "{}").GetError(); !strings.Contains(e, `no tool "nope"`) {
		t.Errorf("an unknown tool told the model %q", e)
	}

	guard := func(name string, stage pb.GuardrailRequest_Stage, text string, toolName *string) *pb.GuardrailResult {
		t.Helper()
		out, err := client.CheckGuardrail(ctx, &pb.GuardrailRequest{ComponentId: "a-assistant", SessionId: "s1", Guardrail: name, Stage: stage, Text: text, Tool: toolName})
		if err != nil {
			t.Fatalf("a guardrail failed its call: %v", err)
		}
		return out
	}
	if guard("no-secrets", pb.GuardrailRequest_INPUT, "hello", nil).GetPass() == nil {
		t.Error("a clean input was blocked")
	}
	if b := guard("no-secrets", pb.GuardrailRequest_OUTPUT, "sk-123", nil).GetBlock(); b != "output: a key leaked" {
		t.Errorf("a leaked key was answered %q", b)
	}
	if b := guard("no-secrets", pb.GuardrailRequest_INPUT, "fail", nil).GetBlock(); !strings.Contains(b, "the check broke") {
		t.Errorf("a failed check was answered %q", b)
	}
	docs := "mcp__docs__read"
	if b := guard("clean-results", pb.GuardrailRequest_RESULT, "<script>", &docs).GetBlock(); b != "mcp__docs__read answered a script" {
		t.Errorf("a result guardrail answered %q", b)
	}
	if b := guard("clean-results", pb.GuardrailRequest_INPUT, "x", nil).GetBlock(); !strings.Contains(b, "no guardrail") {
		t.Errorf("a result guardrail was consulted as an input guardrail: %q", b)
	}
}

func TestAnAgentsProblemsAreNamed(t *testing.T) {
	bad := Agent("",
		MaxToolCallSteps(0),
		SnapshotEvery(1),
		Tools(
			Tool("t", "", func(ctx context.Context, in aLookup) (string, error) { return "", nil }),
			Tool("t", "twice", func(ctx context.Context, in aLookup) (string, error) { return "", nil }),
			Tool("text", "takes text", func(ctx context.Context, in string) (string, error) { return "", nil }),
		),
		McpServers(McpServer("Docs!", McpHeader("X", "SECRET"))),
	)
	text := strings.Join(serviceProblems(NewService(bad)), "\n")
	for _, want := range []string{"has an id", "at least one handler", "MaxToolCallSteps is 0", "not an option a agent takes",
		`"t" has no description`, `"t" is declared twice`, "a tool's input is a struct", "[a-z0-9-]+", "starts ANKKA_MCP_"} {
		if !strings.Contains(text, want) {
			t.Errorf("problems do not say %q:\n%s", want, text)
		}
	}
}

type aAnswer struct {
	Answer  string   `json:"answer" desc:"The answer"`
	Sources []string `json:"sources"`
}

var aAnswerTask = NewTaskType[aAnswer]("answer", "Answer a question",
	Rule("cites-sources", func(ctx context.Context, a aAnswer) (Verdict, error) {
		if len(a.Sources) == 0 {
			return Rejected("cite something"), nil
		}
		return Accepted(), nil
	}),
	Rule("not-broken", func(ctx context.Context, a aAnswer) (Verdict, error) {
		if a.Answer == "fail" {
			return Verdict{}, errors.New("the rule broke")
		}
		if a.Answer == "panic" {
			panic("the rule panicked")
		}
		s, _ := AgentFrom(ctx)
		if a.Answer == "who" && s.TaskID != "t-1" {
			return Rejected("ran for " + s.TaskID), nil
		}
		return Accepted(), nil
	}),
)

var aSummaryTask = NewTaskType[string]("summary", "Summarise")

var aAnswerer = Autonomous("a-answerer", "Answers questions",
	Instructions("Be brief."),
	AutonomousModel("smart"),
	Accepts(aAnswerTask, 5),
	Accepts(aSummaryTask, 2),
	WithAutonomousSettings(AutonomousSettings{ApproachingBudgetAt: 0.7, DependencyStuckAfter: time.Minute}),
	Tools(Tool("count_items", "Counts a cart's items", func(ctx context.Context, in aLookup) (int, error) {
		s, _ := AgentFrom(ctx)
		if s.TaskID != "t-1" {
			return 0, errors.New("ran for " + s.SessionID)
		}
		return len(in.Tags), nil
	})),
)

func TestAnAutonomousAgentIsDeclaredAndChecksResults(t *testing.T) {
	svc := NewService(aAnswerer)
	if p := svc.Problems(); len(p) > 0 {
		t.Fatalf("problems: %v", p)
	}
	d := svc.Spec().GetComponents()[0].GetAutonomousAgent()
	if d.GetDescription() != "Answers questions" || d.GetInstructions() != "Be brief." || d.GetModel() != "smart" || len(d.GetTaskTypes()) != 2 ||
		d.GetAccepts()[0].GetMaxIterations() != 5 || d.GetSettings().GetApproachingBudgetAt() != 0.7 || d.GetSettings().GetDependencyStuckAfterMillis() != 60000 ||
		d.GetSettings().RepeatedFailureAt != nil {
		t.Fatalf("declared %v", d)
	}
	if tt := d.GetTaskTypes()[0]; !reflect.DeepEqual(tt.GetRules(), []string{"cites-sources", "not-broken"}) || !strings.Contains(tt.GetResultSchemaJson(), `"sources"`) {
		t.Errorf("task type %v", tt)
	}
	if d.GetTaskTypes()[1].ResultSchemaJson != nil {
		t.Error("a text result has a schema")
	}
	if why := svc.decls.refusal("1.1"); !strings.Contains(why, "needs 1.2") {
		t.Errorf("a 1.1 sidecar is refused with %q", why)
	}
	client := pb.NewAgentClient(loopback(t, svc))
	ctx := context.Background()
	check := func(taskType, result string) (*pb.TaskResultVerdict, error) {
		return client.CheckTaskResult(ctx, &pb.TaskResultRequest{ComponentId: "a-answerer", TaskId: "t-1", TaskType: taskType, ResultJson: result})
	}
	if v, err := check("answer", `{"answer":"3","sources":["count_items"]}`); err != nil || v.GetAccept() == nil {
		t.Errorf("a good answer was %v, %v", v, err)
	}
	if v, _ := check("answer", `{"answer":"3","sources":[]}`); v.GetReject().GetRule() != "cites-sources" || v.GetReject().GetReason() != "cite something" {
		t.Errorf("an uncited answer was %v", v)
	}
	if v, _ := check("answer", `{"answer":"who","sources":["x"]}`); v.GetAccept() == nil {
		t.Errorf("a rule did not see its task: %v", v)
	}
	if v, _ := check("answer", `{"answer":3}`); v.GetMalformed() == "" {
		t.Errorf("a malformed answer was %v", v)
	}
	if v, _ := check("summary", `"in short"`); v.GetAccept() == nil {
		t.Errorf("a text result was %v", v)
	}
	for _, answer := range []string{"fail", "panic"} {
		_, err := check("answer", `{"answer":"`+answer+`","sources":["x"]}`)
		if status.Code(err) != codes.Internal {
			t.Errorf("a rule that %s was answered %v, not a failed call", answer, err)
		}
	}
	if _, err := check("other", `{}`); status.Code(err) != codes.Internal || !strings.Contains(err.Error(), `no task type "other"`) {
		t.Errorf("an unknown task type was answered %v", err)
	}
	out, err := client.InvokeTool(ctx, &pb.ToolRequest{ComponentId: "a-answerer", SessionId: "task:t-1", Tool: "count_items", ArgumentsJson: `{"cartId":"c","tags":["a","b"]}`})
	if err != nil || out.GetOk() != "2" {
		t.Errorf("an autonomous agent's tool answered %v, %v", out, err)
	}
	if p, _ := client.Plan(ctx, &pb.PlanRequest{ComponentId: "a-answerer"}); p.GetFailure().GetError().GetCode() != pb.ErrorCode_NOT_FOUND {
		t.Errorf("an autonomous agent was asked to plan: %v", p)
	}
}

func TestAnAutonomousAgentsProblemsAreNamed(t *testing.T) {
	bad := Autonomous("", " ",
		Accepts(NewTaskType[aAnswer]("", "", Rule[aAnswer]("r", nil)), 0),
		Tools(Tool("complete_task", "mine", func(ctx context.Context, in aLookup) (string, error) { return "", nil })),
	)
	text := strings.Join(serviceProblems(NewService(bad)), "\n")
	for _, want := range []string{"has an id", "has a description", "a task type has no name", "has no description", "no name or no check", "with 0 iterations", `"complete_task" is the platform's`} {
		if !strings.Contains(text, want) {
			t.Errorf("problems do not say %q:\n%s", want, text)
		}
	}
	if p := serviceProblems(NewService(Autonomous("x", "y"))); !strings.Contains(strings.Join(p, "\n"), "at least one task type") {
		t.Errorf("an agent accepting nothing: %v", p)
	}
}

func TestANotificationReadsAsTheFixtureWritesIt(t *testing.T) {
	raw, err := os.ReadFile("../../protocol/fixtures/autonomous/agent-notification-approval-requested.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Bytes string `json:"bytes_base64"`
	}
	if err := json.Unmarshal(raw, &fixture); err != nil {
		t.Fatal(err)
	}
	data, _ := base64.StdEncoding.DecodeString(fixture.Bytes)
	n, err := notificationOf(string(data))
	if err != nil {
		t.Fatal(err)
	}
	if n.Type != "ApprovalRequested" || n.ComponentID != "operator" || n.InstanceID != "i-1" || n.TaskID != "t-1" || n.At.UnixMilli() != 5 || string(n.Fields["tool"]) != `"restart_service"` {
		t.Errorf("read %+v", n)
	}
}

func TestADecisionNamesWhoDecided(t *testing.T) {
	err := Instance("a", "i").Decide(context.Background(), "a-1", Decision{Approved: true})
	var r *RefusalError
	if !errors.As(err, &r) || r.Code != BadRequest {
		t.Errorf("a decision by nobody was %v", err)
	}
	if _, err := Instance("a", "").State(context.Background()); err == nil || !strings.Contains(err.Error(), "instance id") {
		t.Errorf("a state with no instance was %v", err)
	}
}
