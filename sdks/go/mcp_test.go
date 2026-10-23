package ankka

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

type mpCounter struct {
	Count int `json:"count"`
}

type mpAdd struct {
	By   int    `json:"by" desc:"How much to add"`
	Note string `json:"note"`
}

type mpWho struct {
	Shout bool `json:"shout"`
}

type mpGreeting struct {
	Hello string   `json:"hello"`
	Roles []string `json:"roles"`
}

var mpCounters = KeyValue("mp-counter", func(string) mpCounter { return mpCounter{} },
	Update("add", func(ctx context.Context, s mpCounter, in mpAdd) (KeyValueEffect[mpCounter, mpCounter], error) {
		s.Count += in.By
		return UpdateState[mpCounter, mpCounter](s).ThenReplyState(), nil
	}),
	Update("label", func(ctx context.Context, s mpCounter, in string) (KeyValueEffect[mpCounter, string], error) {
		return KeepState[mpCounter, string]().ThenReplyValue(in), nil
	}),
	Query("get", func(ctx context.Context, s mpCounter, _ Unit) (ReadOnlyEffect[mpCounter], error) {
		return Reply(s), nil
	}),
)

var mpRoutes = NewEndpoint("/orders", AllowAll(),
	Get("/{day}", func(ctx context.Context, req *Request) (mpGreeting, error) {
		return mpGreeting{Hello: "orders of " + req.Param("day"), Roles: []string{}}, nil
	}),
	Post("/{day}/notes", func(ctx context.Context, req *Request, body mpAdd) (string, error) {
		if body.By < 0 {
			return "", &HttpProblem{Status: 422, Message: "a note adds something"}
		}
		return req.Param("day") + ": " + body.Note, nil
	}),
)

var mpEndpoint = NewMcpEndpoint("/mcp", AnyOf(AllowClientKeys(), Authenticate()),
	Brokered(),
	ServerInfo("carts", "1.2.3"),
	McpTool("whoami", "Says who called", func(ctx context.Context, in mpWho) (mpGreeting, error) {
		p := RequestFrom(ctx).Principal
		if p == nil {
			return mpGreeting{}, Refuse(Unauthorized, "nobody called")
		}
		name := p.Subject
		if in.Shout {
			name = strings.ToUpper(name)
		}
		return mpGreeting{Hello: name, Roles: p.Roles}, nil
	}, ToolACL(AllowClientKeys())),
	McpTool("broken", "Breaks", func(ctx context.Context, in mpWho) (string, error) {
		if in.Shout {
			panic("the tool panicked")
		}
		return "", errors.New("the tool broke")
	}),
	McpCommandRef("add", "Adds to a counter", "mp-counter", "add", "counterId"),
	McpCommandRef("label", "Labels a counter", "mp-counter", "label", "counterId", InputArgument("text")),
	McpQueryRef("get", "Reads a counter", "mp-counter", "get", "counterId"),
	McpRouteRef("orders", "Lists a day's orders", "GET", "/orders/{day}"),
	McpRouteRef("note", "Notes on a day", "POST", "/orders/{day}/notes"),
)

func mcpCall(t *testing.T, svc *Service, ctx context.Context, tool, args string, principal *pb.Principal) (*pb.McpToolAnswer, error) {
	t.Helper()
	return svc.decls.mcpTools["/mcp"](ctx, &pb.McpToolRequest{EndpointId: "/mcp", Tool: tool, ArgumentsJson: args, Principal: principal})
}

func TestAnMcpEndpointIsDeclared(t *testing.T) {
	svc := NewService(mpEndpoint, mpCounters, mpRoutes)
	if p := svc.Problems(); len(p) > 0 {
		t.Fatalf("problems: %v", p)
	}
	m := svc.Spec().GetMcpEndpoints()[0]
	if m.GetPath() != "/mcp" || !m.GetBrokered() || m.GetName() != "carts" || m.GetVersion() != "1.2.3" || !m.GetAcl().GetClientKeys() || !m.GetAcl().GetTokens() || m.GetAcl().GetAllowAll() {
		t.Fatalf("declared %v", m)
	}
	schemas := map[string]string{}
	for _, tool := range m.GetTools() {
		schemas[tool.GetName()] = tool.GetInputSchemaJson()
	}
	if len(schemas) != 7 {
		t.Fatalf("tools %v", schemas)
	}
	if m.GetTools()[0].GetAcl().GetClientKeys() != true || m.GetTools()[1].Acl != nil {
		t.Errorf("tool ACLs %v, %v", m.GetTools()[0].GetAcl(), m.GetTools()[1].GetAcl())
	}
	for tool, want := range map[string][]string{
		"add":    {`"counterId"`, `"by"`, `"description":"How much to add"`, `"required":["counterId","by","note"]`},
		"label":  {`"text":{"type":"string"}`, `"required":["counterId","text"]`},
		"get":    {`"required":["counterId"]`},
		"orders": {`"day":{"type":"string"}`},
		"note":   {`"body":{"type":"object"}`},
	} {
		for _, w := range want {
			if !strings.Contains(schemas[tool], w) {
				t.Errorf("%s's schema %s lacks %s", tool, schemas[tool], w)
			}
		}
	}
	if why := svc.decls.refusal("1.19"); !strings.Contains(why, "needs 1.20") || !strings.Contains(why, "MCP endpoint") {
		t.Errorf("a 1.19 sidecar was refused with %q", why)
	}
}

func TestAnMcpEndpointsToolsRun(t *testing.T) {
	svc := NewService(mpEndpoint, mpCounters, mpRoutes)
	f := &fakeSidecar{reply: &pb.InvokeReply{Result: &pb.InvokeReply_Reply{Reply: &pb.Outcome_Reply{Payload: &pb.Payload{ContentType: JSONContent, Data: []byte(`{"count":5}`)}}}}}
	ctx := startFake(t, f, svc.decls)

	out, err := mcpCall(t, svc, ctx, "whoami", `{"shout":true}`, &pb.Principal{Subject: "ada", Roles: []string{"admin"}})
	if err != nil || out.GetText() != `{"hello":"ADA","roles":["admin"]}` || out.GetStructuredJson() != out.GetText() {
		t.Errorf("whoami answered %v, %v", out, err)
	}
	if _, err := mcpCall(t, svc, ctx, "whoami", `{"shout":false}`, nil); err == nil || !strings.Contains(err.Error(), "nobody called") {
		t.Errorf("whoami with no principal was %v", err)
	}
	for args, want := range map[string]string{`{"shout":false}`: "the tool broke", `{"shout":"x"}`: "cannot be read"} {
		if _, err := mcpCall(t, svc, ctx, "broken", args, nil); err == nil || !strings.Contains(err.Error(), want) {
			t.Errorf("broken with %s was %v", args, err)
		}
	}

	out, err = mcpCall(t, svc, ctx, "add", `{"counterId":"c1","by":2,"note":"n"}`, nil)
	if err != nil || out.GetText() != `{"count":5}` {
		t.Fatalf("add answered %v, %v", out, err)
	}
	sent := f.invokes[len(f.invokes)-1]
	if sent.GetKind() != pb.Kind_KEY_VALUE_ENTITY || sent.GetComponentId() != "mp-counter" || sent.GetEntityId() != "c1" || sent.GetName() != "add" || string(sent.GetPayload().GetData()) != `{"by":2,"note":"n"}` {
		t.Errorf("add sent %v", sent)
	}
	if _, err := mcpCall(t, svc, ctx, "label", `{"counterId":"c1","text":"blue"}`, nil); err != nil {
		t.Fatal(err)
	}
	if sent := f.invokes[len(f.invokes)-1]; string(sent.GetPayload().GetData()) != "blue" || sent.GetPayload().GetManifest() != "string" {
		t.Errorf("label sent %v", sent.GetPayload())
	}
	if _, err := mcpCall(t, svc, ctx, "get", `{"counterId":"c1"}`, nil); err != nil || f.invokes[len(f.invokes)-1].GetName() != "get" {
		t.Errorf("get was %v", err)
	}
	if _, err := mcpCall(t, svc, ctx, "get", `{}`, nil); err == nil || !strings.Contains(err.Error(), "counterId") {
		t.Errorf("get with no id was %v", err)
	}
	f.reply = &pb.InvokeReply{Result: &pb.InvokeReply_Error{Error: &pb.Error{Code: pb.ErrorCode_CONFLICT, Message: "too many"}}}
	if _, err := mcpCall(t, svc, ctx, "add", `{"counterId":"c1","by":2,"note":"n"}`, nil); err == nil || !strings.Contains(err.Error(), "too many") {
		t.Errorf("a refused add was %v", err)
	}

	out, err = mcpCall(t, svc, ctx, "orders", `{"day":"monday"}`, nil)
	if err != nil || !strings.Contains(out.GetText(), "orders of monday") || out.StructuredJson == nil {
		t.Errorf("orders answered %v, %v", out, err)
	}
	out, err = mcpCall(t, svc, ctx, "note", `{"day":"tue","body":{"by":1,"note":"quiet"}}`, nil)
	if err != nil || out.GetText() != "tue: quiet" || out.StructuredJson != nil {
		t.Errorf("note answered %v, %v", out, err)
	}
	if _, err := mcpCall(t, svc, ctx, "note", `{"day":"tue","body":{"by":-1,"note":"x"}}`, nil); err == nil || !strings.Contains(err.Error(), "a note adds something") {
		t.Errorf("a problem from the route was %v", err)
	}
}

func TestTheMcpServicerAnswersEveryKindOfOutcome(t *testing.T) {
	client := pb.NewMcpClient(loopback(t, NewService(mpEndpoint, mpCounters, mpRoutes)))
	ctx := context.Background()
	call := func(endpoint, tool, args string) *pb.McpToolReply {
		t.Helper()
		out, err := client.InvokeTool(ctx, &pb.McpToolRequest{EndpointId: endpoint, Tool: tool, ArgumentsJson: args, Principal: &pb.Principal{Subject: "ada"}})
		if err != nil {
			t.Fatalf("the tool failed its call: %v", err)
		}
		return out
	}
	if ok := call("/mcp", "whoami", `{"shout":false}`).GetOk(); ok.GetText() != `{"hello":"ada","roles":[]}` {
		t.Errorf("whoami answered %v", ok)
	}
	if e := call("/mcp", "broken", `{"shout":true}`).GetError(); !strings.Contains(e, "the tool panicked") {
		t.Errorf("a panic was answered %q", e)
	}
	if f := call("/elsewhere", "x", "{}").GetFailure(); f.GetError().GetCode() != pb.ErrorCode_NOT_FOUND {
		t.Errorf("an unknown endpoint was answered %v", f)
	}
	if e := call("/mcp", "nope", "{}").GetError(); !strings.Contains(e, `no tool "nope"`) {
		t.Errorf("an unknown tool was answered %q", e)
	}
}

func TestAnMcpEndpointsProblemsAreNamed(t *testing.T) {
	bad := NewMcpEndpoint("/_ankka/mcp/", nil,
		Brokered(),
		McpTool("Bad Name", "x", func(ctx context.Context, in mpWho) (string, error) { return "", nil }),
		McpTool("text", "", func(ctx context.Context, in string) (string, error) { return "", nil }),
		McpCommandRef("missing", "x", "nobody", "add", "id"),
		McpQueryRef("q", "x", "mp-counter", "add", "id"),
		McpCommandRef("c", "x", "mp-counter", "get", "id"),
		McpCommandRef("handler", "x", "mp-counter", "nope", "id"),
		McpCommandRef("noid", "x", "mp-counter", "add", ""),
		McpQueryRef("noinput", "x", "mp-counter", "get", "id", InputArgument("in")),
		McpRouteRef("route", "x", "GET", "/orders/{week}"),
	)
	text := strings.Join(serviceProblems(NewService(bad, mpCounters, mpRoutes)), "\n")
	for _, want := range []string{"does not end with /", "/_ankka is the platform's", "has no ACL", "[a-z0-9_-]+", "has no description",
		"a tool's input is a struct", `the component "nobody"`, "which is a command", "which is a query", `the handler "nope"`,
		"holds the instance's id", "takes no input", "/orders/{week}, which no endpoint"} {
		if !strings.Contains(text, want) {
			t.Errorf("problems do not say %q:\n%s", want, text)
		}
	}
	notBrokerable := strings.Join(serviceProblems(NewService(NewMcpEndpoint("/mcp", AllowClientKeys(), Brokered(), McpTool("t", "x", func(ctx context.Context, in mpWho) (string, error) { return "", nil })))), "\n")
	if !strings.Contains(notBrokerable, "admits Authenticate()") {
		t.Errorf("a brokered endpoint without tokens: %s", notBrokerable)
	}
}
