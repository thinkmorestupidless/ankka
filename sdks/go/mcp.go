package ankka

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"reflect"
	"regexp"
	"strings"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

// An MCP endpoint (1.20): a path, an access rule and its tools, which the platform serves to any
// MCP client over Streamable HTTP. The platform answers the protocol, verifies the credential (a
// client key or a token), checks the arguments against each tool's schema and asks this process to
// run the tool; the process never sees the credential, only the principal it stood for, on
// RequestFrom(ctx).Principal.

// McpEndpoint is an MCP endpoint's declaration.
type McpEndpoint struct {
	path          string
	acl           *ACL
	brokered      bool
	name, version string
	tools         []*mcpToolDecl
	problems      []string
	resolved      []*pb.McpTool
	runs          map[string]func(ctx context.Context, args []byte) (*pb.McpToolAnswer, error)
}

// McpPart is a tool of an MCP endpoint, or one of its options.
type McpPart interface{ mcpPart() }

type mcpOption func(*McpEndpoint)

func (mcpOption) mcpPart() {}

// Brokered has the platform sign people in for clients that cannot hold a token; the endpoint's
// ACL must admit Authenticate.
func Brokered() McpPart { return mcpOption(func(e *McpEndpoint) { e.brokered = true }) }

// ServerInfo is the name and version the endpoint answers initialize with; unsaid, the service's
// name and its image's tag.
func ServerInfo(name, version string) McpPart {
	return mcpOption(func(e *McpEndpoint) { e.name, e.version = name, version })
}

// McpToolOption is an option of an MCP tool.
type McpToolOption func(*mcpToolDecl)

// ToolACL is who may call this tool, beside the endpoint's rule.
func ToolACL(acl *ACL) McpToolOption { return func(t *mcpToolDecl) { t.acl = acl; t.aclSet = true } }

// InputArgument names the argument holding a referenced handler's input; unsaid, a record input's
// fields sit beside the id, and any other input is the argument "input".
func InputArgument(name string) McpToolOption { return func(t *mcpToolDecl) { t.input = name } }

type mcpToolKind int

const (
	mcpFunction mcpToolKind = iota
	mcpCommand
	mcpQuery
	mcpRoute
)

type mcpToolDecl struct {
	kind              mcpToolKind
	name, description string
	acl               *ACL
	aclSet            bool
	// a function tool
	schema   func() (map[string]any, []string)
	function func(ctx context.Context, args []byte) (*pb.McpToolAnswer, error)
	// a reference to a component's handler
	componentID, handler, idArg, input string
	// a reference to a route
	method, path string
}

func (*mcpToolDecl) mcpPart() {}

// McpTool is a tool this process runs: its arguments read as an I (a struct, whose JSON Schema is
// the tool's), its answer R as text, and as structured content too when it is a record or a map. An
// error, a refusal or a panic is the tool error the client is shown.
func McpTool[I, R any](name, description string, f func(ctx context.Context, input I) (R, error), opts ...McpToolOption) McpPart {
	t := &mcpToolDecl{kind: mcpFunction, name: name, description: description}
	t.schema = func() (map[string]any, []string) {
		it := reflect.TypeFor[I]()
		if it.Kind() != reflect.Struct || it == timeType || it == localDateType || it == localDateTimeType {
			return nil, []string{fmt.Sprintf("its input is a %s; a tool's input is a struct, so its arguments are an object", it)}
		}
		var problems []string
		for _, p := range CodecProblems(CodecFor[R]()) {
			problems = append(problems, fmt.Sprintf("its result, a %s: %s", reflect.TypeFor[R](), p))
		}
		s, more := schemaOf(it)
		return s, append(problems, more...)
	}
	t.function = func(ctx context.Context, args []byte) (*pb.McpToolAnswer, error) {
		p, problems := planFor(reflect.TypeFor[I]())
		if len(problems) > 0 {
			return nil, errors.New(strings.Join(problems, "; "))
		}
		var in I
		if err := decodeJSON(p, args, reflect.ValueOf(&in).Elem()); err != nil {
			return nil, fmt.Errorf("the arguments cannot be read: %w", err)
		}
		out, err := f(ctx, in)
		if err != nil {
			return nil, err
		}
		c := CodecFor[R]()
		if reflect.TypeFor[R]() == doneType {
			return &pb.McpToolAnswer{Text: "done"}, nil
		}
		data, err := c.Encode(out)
		if err != nil {
			return nil, fmt.Errorf("the result cannot be encoded: %w", err)
		}
		return answerOf(data, c.ContentType()), nil
	}
	for _, o := range opts {
		o(t)
	}
	return t
}

// McpCommandRef is a tool that runs the command handler of the entity or workflow componentID;
// the argument idArg names the instance.
func McpCommandRef(name, description, componentID, handler, idArg string, opts ...McpToolOption) McpPart {
	t := &mcpToolDecl{kind: mcpCommand, name: name, description: description, componentID: componentID, handler: handler, idArg: idArg}
	for _, o := range opts {
		o(t)
	}
	return t
}

// McpQueryRef is a tool that runs a query of the entity or workflow componentID; it cannot change
// anything.
func McpQueryRef(name, description, componentID, handler, idArg string, opts ...McpToolOption) McpPart {
	t := &mcpToolDecl{kind: mcpQuery, name: name, description: description, componentID: componentID, handler: handler, idArg: idArg}
	for _, o := range opts {
		o(t)
	}
	return t
}

// McpRouteRef is a tool that calls a request route of one of this service's endpoints, in process:
// method and the full path ("/orders/{day}"), its path parameters the arguments and "body" its body.
func McpRouteRef(name, description, method, path string, opts ...McpToolOption) McpPart {
	t := &mcpToolDecl{kind: mcpRoute, name: name, description: description, method: method, path: path}
	for _, o := range opts {
		o(t)
	}
	return t
}

// NewMcpEndpoint declares an MCP endpoint at path ("/mcp") admitting the callers acl admits:
// AllowClientKeys(), Authenticate(...), AnyOf(...) or any other rule. The ACL is required.
func NewMcpEndpoint(path string, acl *ACL, parts ...McpPart) *McpEndpoint {
	e := &McpEndpoint{path: path, acl: acl, runs: map[string]func(context.Context, []byte) (*pb.McpToolAnswer, error){}}
	for _, p := range parts {
		switch v := p.(type) {
		case mcpOption:
			v(e)
		case *mcpToolDecl:
			e.tools = append(e.tools, v)
		case nil:
			e.problems = append(e.problems, "a tool is nil")
		}
	}
	return e
}

// Path is the endpoint's path.
func (e *McpEndpoint) Path() string { return e.path }

var toolNamePattern = regexp.MustCompile(`^[a-z0-9_-]{1,128}$`)

// mcpACL is a as an MCP endpoint's or tool's rule.
func (a *ACL) mcpACL() *pb.McpAcl {
	out := &pb.McpAcl{}
	var add func(a *ACL)
	add = func(a *ACL) {
		switch a.kind {
		case aclAllowAll:
			out.AllowAll = true
		case aclDenyAll:
			out.DenyAll = true
		case aclAuthenticated:
			out.Tokens = true
		case aclClientKeys:
			out.ClientKeys = true
		case aclCallers:
			for _, c := range a.callers {
				out.Callers = append(out.Callers, c.toPb())
			}
		case aclAnyOf:
			for _, o := range a.anyOf {
				if o != nil {
					add(o)
				}
			}
		}
	}
	add(a)
	return out
}

func (a *ACL) mcpProblems() []string {
	var out []string
	var walk func(a *ACL)
	walk = func(a *ACL) {
		if a == nil {
			out = append(out, "AnyOf is given a nil ACL")
			return
		}
		switch a.kind {
		case aclAuthenticated:
			if len(a.issuers) > 0 {
				out = append(out, "an MCP endpoint's tokens are the service's issuers; Authenticate takes no issuer here")
			}
		case aclAnyOf:
			for _, o := range a.anyOf {
				walk(o)
			}
		}
	}
	walk(a)
	if acl := a.mcpACL(); acl.DenyAll && (acl.AllowAll || acl.Tokens || acl.ClientKeys || len(acl.Callers) > 0) {
		out = append(out, "DenyAll beside another rule admits nobody and says otherwise; choose one")
	}
	return out
}

func (e *McpEndpoint) declare(d *declarations) {
	where := "MCP endpoint " + e.path
	if !strings.HasPrefix(e.path, "/") || (len(e.path) > 1 && strings.HasSuffix(e.path, "/")) || strings.ContainsAny(e.path, "{}") {
		d.problem(where, "the path starts with /, has no parameters and does not end with /")
	}
	if e.path == "/_ankka" || strings.HasPrefix(e.path, "/_ankka/") {
		d.problem(where, "/_ankka is the platform's")
	}
	if e.acl == nil {
		d.problem(where, "the endpoint has no ACL; give it one: ankka.AllowClientKeys(), ankka.Authenticate(), ankka.AnyOf(...)")
	} else {
		for _, p := range e.acl.mcpProblems() {
			d.problem(where, "%s", p)
		}
		if e.brokered && !e.acl.mcpACL().Tokens {
			d.problem(where, "a brokered endpoint signs people in with the service's issuer; its ACL admits Authenticate()")
		}
	}
	for _, p := range e.problems {
		d.problem(where, "%s", p)
	}
	if len(e.tools) == 0 {
		d.problem(where, "an MCP endpoint has at least one tool")
	}
	d.require(20, "an MCP endpoint (%s)", e.path)
	d.mcpDecls[e.path] = e
}

// resolve resolves the endpoint's tools against every component and route, once all are declared.
func (e *McpEndpoint) resolve(d *declarations) {
	where := "MCP endpoint " + e.path
	out := &pb.McpEndpoint{Id: e.path, Path: e.path, Name: e.name, Version: e.version, Brokered: e.brokered}
	if e.acl != nil {
		out.Acl = e.acl.mcpACL()
	}
	seen := map[string]bool{}
	for _, t := range e.tools {
		at := fmt.Sprintf("%s tool %q", where, t.name)
		if !toolNamePattern.MatchString(t.name) {
			d.problem(at, "a tool's name is [a-z0-9_-]+, at most 128 characters")
			continue
		}
		if seen[t.name] {
			d.problem(at, "the tool is declared twice")
		}
		seen[t.name] = true
		if strings.TrimSpace(t.description) == "" {
			d.problem(at, "the tool has no description; it is what a model reads to choose it")
		}
		tool := &pb.McpTool{Name: t.name, Description: t.description}
		if t.aclSet {
			if t.acl == nil {
				d.problem(at, "the tool's own ACL is nil")
			} else {
				for _, p := range t.acl.mcpProblems() {
					d.problem(at, "%s", p)
				}
				tool.Acl = t.acl.mcpACL()
			}
		}
		schema, run, problems := t.resolve(d)
		for _, p := range problems {
			d.problem(at, "%s", p)
		}
		if run == nil {
			continue
		}
		data, err := json.Marshal(schema)
		if err != nil {
			d.problem(at, "its schema cannot be written: %v", err)
			continue
		}
		tool.InputSchemaJson = string(data)
		out.Tools = append(out.Tools, tool)
		e.runs[t.name] = run
	}
	d.mcp = append(d.mcp, out)
	d.mcpTools[e.path] = e.invoke
}

func objectSchema(properties map[string]any, required []string) map[string]any {
	return map[string]any{"type": "object", "properties": properties, "required": required, "additionalProperties": false}
}

func (t *mcpToolDecl) resolve(d *declarations) (map[string]any, func(context.Context, []byte) (*pb.McpToolAnswer, error), []string) {
	switch t.kind {
	case mcpFunction:
		s, problems := t.schema()
		if len(problems) > 0 {
			return nil, nil, problems
		}
		return s, t.function, nil
	case mcpCommand, mcpQuery:
		return t.resolveHandler(d)
	case mcpRoute:
		return t.resolveRoute(d)
	}
	return nil, nil, []string{"an unknown kind of tool"}
}

func (t *mcpToolDecl) resolveHandler(d *declarations) (map[string]any, func(context.Context, []byte) (*pb.McpToolAnswer, error), []string) {
	var comp *pb.Component
	for _, c := range d.components {
		if c.GetId() == t.componentID {
			comp = c
		}
	}
	if comp == nil {
		return nil, nil, []string{fmt.Sprintf("it refers to the component %q, which this service does not declare", t.componentID)}
	}
	switch comp.GetKind() {
	case pb.Kind_EVENT_SOURCED_ENTITY, pb.Kind_KEY_VALUE_ENTITY, pb.Kind_WORKFLOW:
	default:
		return nil, nil, []string{fmt.Sprintf("it refers to %s, a %s; a reference names an entity's or a workflow's handler", comp.GetId(), strings.ToLower(comp.GetKind().String()))}
	}
	h, ok := d.handlerParts[handlerKey(t.componentID, t.handler)]
	if !ok {
		return nil, nil, []string{fmt.Sprintf("it refers to the handler %q of %s, which it does not declare", t.handler, t.componentID)}
	}
	var problems []string
	if t.kind == mcpQuery && !h.readOnly() {
		problems = append(problems, fmt.Sprintf("it is a query of %q, which is a command; declare it with McpCommandRef", t.handler))
	}
	if t.kind == mcpCommand && h.readOnly() {
		problems = append(problems, fmt.Sprintf("it is a command of %q, which is a query; declare it with McpQueryRef", t.handler))
	}
	if strings.TrimSpace(t.idArg) == "" {
		return nil, nil, append(problems, "it names the argument that holds the instance's id")
	}
	properties := map[string]any{t.idArg: map[string]any{"type": "string", "description": "The id of the " + t.componentID}}
	required := []string{t.idArg}
	merge, argument := false, ""
	if !h.takesNoInput() {
		s, schemaProblems := h.inputSchema()
		if len(schemaProblems) > 0 {
			return nil, nil, append(problems, schemaProblems...)
		}
		if s["type"] == "object" && t.input == "" && s["properties"] != nil {
			fields := s["properties"].(map[string]any)
			if _, clash := fields[t.idArg]; clash {
				problems = append(problems, fmt.Sprintf("the input has a field %q, which is the id's argument; name the input with InputArgument", t.idArg))
			}
			for k, v := range fields {
				properties[k] = v
			}
			required = append(required, s["required"].([]string)...)
			merge = true
		} else {
			argument = t.input
			if argument == "" {
				argument = "input"
			}
			if argument == t.idArg {
				problems = append(problems, fmt.Sprintf("the id and the input are both the argument %q", argument))
			}
			properties[argument] = s
			required = append(required, argument)
		}
	} else if t.input != "" {
		problems = append(problems, fmt.Sprintf("it names an input argument, but %q takes no input", t.handler))
	}
	if len(problems) > 0 {
		return nil, nil, problems
	}
	kind, componentID, wire, idArg := comp.GetKind(), t.componentID, t.handler, t.idArg
	run := func(ctx context.Context, raw []byte) (*pb.McpToolAnswer, error) {
		var args map[string]json.RawMessage
		if err := json.Unmarshal(raw, &args); err != nil {
			return nil, fmt.Errorf("the arguments must be an object: %w", err)
		}
		var id string
		if err := json.Unmarshal(args[idArg], &id); err != nil || id == "" {
			return nil, fmt.Errorf("parameter '%s': expected a non-empty string", idArg)
		}
		payload := &pb.Payload{ContentType: JSONContent}
		var err error
		switch {
		case merge:
			delete(args, idArg)
			rest, _ := json.Marshal(args)
			payload, err = h.inputFromJSON(rest)
		case argument != "":
			payload, err = h.inputFromJSON(args[argument])
		default:
			payload, err = encodeAny(Unit{})
		}
		if err != nil {
			return nil, fmt.Errorf("the input cannot be read: %w", err)
		}
		_, rpc, err := clientOf(ctx)
		if err != nil {
			return nil, err
		}
		reply, err := rpc.Invoke(ctx, &pb.InvokeRequest{Kind: kind, ComponentId: componentID, EntityId: id, Name: wire, Payload: payload, Metadata: metadataToPb(MetadataFrom(ctx))})
		if err != nil {
			return nil, tooOld("a call", "1.0", err)
		}
		switch r := reply.GetResult().(type) {
		case *pb.InvokeReply_Reply:
			p := r.Reply.GetPayload()
			if len(p.GetData()) == 0 {
				return &pb.McpToolAnswer{Text: "done"}, nil
			}
			return answerOf(p.GetData(), p.GetContentType()), nil
		case *pb.InvokeReply_Error:
			return nil, errorFromPb(r.Error)
		case *pb.InvokeReply_Approval:
			return nil, approvalsOf(r.Approval)
		}
		return nil, errors.New("the sidecar answered nothing")
	}
	return objectSchema(properties, required), run, nil
}

func (t *mcpToolDecl) resolveRoute(d *declarations) (map[string]any, func(context.Context, []byte) (*pb.McpToolAnswer, error), []string) {
	var endpoint *Endpoint
	var route *Route
	for _, id := range sortedKeys(d.endpointDecls) {
		e := d.endpointDecls[id]
		rest, ok := strings.CutPrefix(t.path, e.prefix)
		if !ok || (rest != "" && !strings.HasPrefix(rest, "/") && e.prefix != "/") {
			continue
		}
		if e.prefix == "/" {
			rest = t.path
		}
		for _, r := range e.routes {
			if r.method == strings.ToUpper(t.method) && r.template == rest {
				endpoint, route = e, r
			}
		}
	}
	if route == nil {
		return nil, nil, []string{fmt.Sprintf("it refers to %s %s, which no endpoint of this service declares", t.method, t.path)}
	}
	if route.kind != routeRequest {
		return nil, nil, []string{fmt.Sprintf("it refers to %s %s, which streams; a tool answers once", t.method, t.path)}
	}
	properties := map[string]any{}
	for _, p := range route.params {
		properties[p] = map[string]any{"type": "string"}
	}
	if route.hasBody {
		properties["body"] = map[string]any{"type": "object"}
	}
	required := append([]string{}, route.params...)
	run := func(ctx context.Context, raw []byte) (*pb.McpToolAnswer, error) {
		var args map[string]json.RawMessage
		if err := json.Unmarshal(raw, &args); err != nil {
			return nil, fmt.Errorf("the arguments must be an object: %w", err)
		}
		call := RequestFrom(ctx)
		req := &Request{Method: route.method, Path: route.template, Params: map[string]string{}, Query: nil, Headers: http.Header{}, Metadata: MetadataFrom(ctx)}
		if call != nil {
			req.Principal, req.Caller = call.Principal, call.Caller
		}
		for _, p := range route.params {
			var s string
			if err := json.Unmarshal(args[p], &s); err != nil {
				var n json.Number
				dec := json.NewDecoder(bytes.NewReader(args[p]))
				dec.UseNumber()
				if dec.Decode(&n) != nil {
					return nil, fmt.Errorf("parameter '%s': expected a string", p)
				}
				s = n.String()
			}
			req.Params[p] = s
		}
		if route.hasBody {
			req.Body, req.ContentType = args["body"], JSONContent
			if len(req.Body) == 0 {
				req.Body = []byte("{}")
			}
		}
		if ok, why := endpoint.aclFor(route).admitsIssuer(req.Principal, endpoint.issuers); !ok {
			return nil, Refuse(Forbidden, why)
		}
		res, err := route.run(withRequest(ctx, req), req)
		if err != nil {
			if out := errorResponse(err); out != nil {
				return nil, errors.New(string(out.GetBody()))
			}
			return nil, err
		}
		if res.GetStatus() >= 400 {
			return nil, fmt.Errorf("%d: %s", res.GetStatus(), res.GetBody())
		}
		if res.GetStatus() == http.StatusNoContent || len(res.GetBody()) == 0 {
			return &pb.McpToolAnswer{Text: "done"}, nil
		}
		return answerOf(res.GetBody(), res.GetContentType()), nil
	}
	return objectSchema(properties, required), run, nil
}

// answerOf is a tool's encoded result as an answer: its text, and the same as structured content
// when it is a JSON object.
func answerOf(data []byte, contentType string) *pb.McpToolAnswer {
	text := string(data)
	out := &pb.McpToolAnswer{Text: text}
	if strings.HasPrefix(contentType, JSONContent) {
		var probe any
		if json.Unmarshal(data, &probe) == nil {
			if _, isObject := probe.(map[string]any); isObject {
				out.StructuredJson = &text
			}
		}
	}
	return out
}

func (e *McpEndpoint) invoke(ctx context.Context, req *pb.McpToolRequest) (*pb.McpToolAnswer, error) {
	run, ok := e.runs[req.GetTool()]
	if !ok {
		return nil, fmt.Errorf("no tool %q on %s", req.GetTool(), e.path)
	}
	call := &Request{Method: http.MethodPost, Path: e.path, Params: map[string]string{}, Headers: http.Header{}, Metadata: metadataFromPb(req.GetMetadata()), Caller: callerOf(req.GetCaller())}
	if p := req.GetPrincipal(); p != nil {
		call.Principal = &Principal{Subject: p.GetSubject(), Name: p.GetName(), Email: p.GetEmail(), EmailVerified: p.GetEmailVerified(), Roles: p.GetRoles(), Claims: p.GetClaims(), Issuer: p.GetIssuer()}
	}
	args := []byte(req.GetArgumentsJson())
	if len(bytes.TrimSpace(args)) == 0 {
		args = []byte("{}")
	}
	return run(withRequest(withMetadata(ctx, call.Metadata), call), args)
}
