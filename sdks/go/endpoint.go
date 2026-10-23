package ankka

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"reflect"
	"regexp"
	"strings"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

// Endpoint is an HTTP endpoint's declaration: a prefix, who may call, and its routes. The sidecar
// serves the routes on the service's HTTP port, applies the ACL and forwards each request here; the
// process never binds an HTTP port.
type Endpoint struct {
	prefix  string
	acl     *ACL
	routes  []*Route
	issuers []string
	options []Part
}

// EndpointOption is an option of NewEndpoint, given among its routes.
type EndpointOption struct{ apply func(*Endpoint) }

func (EndpointOption) routeOrOption() {}

// Issuers are the issuers an authenticated route of the endpoint accepts when it names none.
func Issuers(issuers ...IssuerRef) EndpointOption {
	return EndpointOption{apply: func(e *Endpoint) {
		for _, i := range issuers {
			e.issuers = append(e.issuers, i.name)
		}
	}}
}

// RouteOrOption is a route of an endpoint, or one of its options.
type RouteOrOption interface{ routeOrOption() }

// NewEndpoint declares an endpoint at prefix ("/carts") admitting the callers acl admits, with its
// routes. The ACL is required and has no default.
func NewEndpoint(prefix string, acl *ACL, routes ...RouteOrOption) *Endpoint {
	e := &Endpoint{prefix: prefix, acl: acl}
	for _, r := range routes {
		switch r := r.(type) {
		case *Route:
			e.routes = append(e.routes, r)
		case EndpointOption:
			r.apply(e)
		}
	}
	return e
}

// Prefix is the endpoint's prefix.
func (e *Endpoint) Prefix() string { return e.prefix }

// RawResponse is a route's answer whose status, content type, headers and body the handler chooses.
type RawResponse struct {
	Status      int
	ContentType string
	Headers     http.Header
	Body        []byte
}

type routeKind int

const (
	routeRequest routeKind = iota
	routeStream
	routeSocket
)

// Route is one route of an endpoint.
type Route struct {
	method   string
	template string
	params   []string
	hasBody  bool
	kind     routeKind
	acl      *ACL
	problems []string
	describe string
	run      func(ctx context.Context, req *Request) (*pb.HttpResponse, error)
	stream   func(ctx context.Context, req *Request, send func(*pb.StreamFrame) error) error
	socket   func(ctx context.Context, req *Request, s *Socket) error
}

func (*Route) routeOrOption() {}

// ACL gives the route an ACL of its own, in place of the endpoint's.
func (r *Route) ACL(acl *ACL) *Route {
	r.acl = acl
	if acl == nil {
		r.problems = append(r.problems, "the route's own ACL is nil")
	}
	return r
}

var paramPattern = regexp.MustCompile(`\{([^{}/]*)\}`)

func newRoute(method, template string) *Route {
	r := &Route{method: method, template: template, describe: method + " " + template}
	for _, m := range paramPattern.FindAllStringSubmatch(template, -1) {
		if m[1] == "" {
			r.problems = append(r.problems, "a path parameter has a name: {name}")
		}
		r.params = append(r.params, m[1])
	}
	if template != "" && !strings.HasPrefix(template, "/") {
		r.problems = append(r.problems, fmt.Sprintf("the template %q starts with /", template))
	}
	return r
}

// Get is a GET route answering a R.
func Get[R any](template string, f func(ctx context.Context, req *Request) (R, error)) *Route {
	return bodiless(http.MethodGet, template, f)
}

// Delete is a DELETE route answering a R.
func Delete[R any](template string, f func(ctx context.Context, req *Request) (R, error)) *Route {
	return bodiless(http.MethodDelete, template, f)
}

// Post is a POST route taking a body B and answering a R.
func Post[B, R any](template string, f func(ctx context.Context, req *Request, body B) (R, error)) *Route {
	return withBody(http.MethodPost, template, f)
}

// Put is a PUT route taking a body B and answering a R.
func Put[B, R any](template string, f func(ctx context.Context, req *Request, body B) (R, error)) *Route {
	return withBody(http.MethodPut, template, f)
}

// Patch is a PATCH route taking a body B and answering a R.
func Patch[B, R any](template string, f func(ctx context.Context, req *Request, body B) (R, error)) *Route {
	return withBody(http.MethodPatch, template, f)
}

func bodiless[R any](method, template string, f func(context.Context, *Request) (R, error)) *Route {
	r := newRoute(method, template)
	out := replyEncoder[R](r)
	r.run = func(ctx context.Context, req *Request) (*pb.HttpResponse, error) {
		v, err := f(ctx, req)
		if err != nil {
			return nil, err
		}
		return out(v)
	}
	return r
}

func withBody[B, R any](method, template string, f func(context.Context, *Request, B) (R, error)) *Route {
	r := newRoute(method, template)
	r.hasBody = true
	in := CodecFor[B]()
	for _, p := range CodecProblems(in) {
		r.problems = append(r.problems, fmt.Sprintf("its body, a %s: %s", reflect.TypeFor[B](), p))
	}
	out := replyEncoder[R](r)
	r.run = func(ctx context.Context, req *Request) (*pb.HttpResponse, error) {
		body, err := in.Decode(req.Body)
		if err != nil {
			return textResponse(http.StatusBadRequest, fmt.Sprintf("the body cannot be read as a %s: %v", reflect.TypeFor[B](), err)), nil
		}
		v, err := f(ctx, req, body)
		if err != nil {
			return nil, err
		}
		return out(v)
	}
	return r
}

var (
	rawType  = reflect.TypeFor[RawResponse]()
)

// replyEncoder is how a route's reply of type R becomes a response: Done is 204 with no body, a
// RawResponse is as given, anything else 200 encoded by R's codec.
func replyEncoder[R any](r *Route) func(R) (*pb.HttpResponse, error) {
	t := reflect.TypeFor[R]()
	switch t {
	case doneType:
		return func(R) (*pb.HttpResponse, error) { return &pb.HttpResponse{Status: http.StatusNoContent}, nil }
	case rawType:
		return func(v R) (*pb.HttpResponse, error) {
			raw := any(v).(RawResponse)
			status := raw.Status
			if status == 0 {
				status = http.StatusOK
			}
			return &pb.HttpResponse{Status: int32(status), ContentType: raw.ContentType, Body: raw.Body, Headers: pairsOf(raw.Headers)}, nil
		}
	}
	c := CodecFor[R]()
	for _, p := range CodecProblems(c) {
		r.problems = append(r.problems, fmt.Sprintf("its reply, a %s: %s", t, p))
	}
	contentType := c.ContentType()
	if contentType == TextContent {
		contentType = "text/plain; charset=utf-8"
	}
	return func(v R) (*pb.HttpResponse, error) {
		body, err := c.Encode(v)
		if err != nil {
			return nil, fmt.Errorf("the reply cannot be encoded: %w", err)
		}
		return &pb.HttpResponse{Status: http.StatusOK, ContentType: contentType, Body: body}, nil
	}
}

func pairsOf(h http.Header) []*pb.HttpRequest_Pair {
	var out []*pb.HttpRequest_Pair
	for _, name := range sortedKeys(h) {
		for _, v := range h[name] {
			out = append(out, &pb.HttpRequest_Pair{Name: name, Value: v})
		}
	}
	return out
}

func textResponse(status int, text string) *pb.HttpResponse {
	return &pb.HttpResponse{Status: int32(status), ContentType: "text/plain; charset=utf-8", Body: []byte(text)}
}

// routeID is how the sidecar names a route back on each request.
func (r *Route) routeID() string { return r.method + " " + r.template }

func (e *Endpoint) id() string { return e.prefix }

func (e *Endpoint) declare(d *declarations) {
	where := "endpoint " + e.prefix
	if e.acl == nil {
		d.problem(where, "the endpoint has no ACL; give it one: ankka.AllowAll(), ankka.DenyAll(), ankka.Authenticate(...) or ankka.AllowIf(...)")
	} else {
		for _, p := range e.acl.httpProblems(e.issuers) {
			d.problem(where, "%s", p)
		}
	}
	if !strings.HasPrefix(e.prefix, "/") {
		d.problem(where, "the prefix starts with /")
	}
	if e.prefix == "/_ankka" || strings.HasPrefix(e.prefix, "/_ankka/") {
		d.problem(where, "/_ankka is the platform's")
	}
	if len(e.routes) == 0 {
		d.problem(where, "an endpoint has at least one route")
	}
	out := &pb.Endpoint{Id: e.id(), Prefix: e.prefix}
	if e.acl != nil {
		out.Acl, out.AllowCallers = e.acl.endpointACL()
		if e.acl.grants() {
			d.require(15, "endpoint %s admits granted callers", e.prefix)
		}
	}
	seen := map[string]bool{}
	routes := map[string]*Route{}
	for _, r := range e.routes {
		at := where + " " + r.describe
		if seen[r.routeID()] {
			d.problem(at, "the route is declared twice")
		}
		seen[r.routeID()] = true
		routes[r.routeID()] = r
		for _, p := range r.problems {
			d.problem(at, "%s", p)
		}
		route := &pb.Route{Id: r.routeID(), Method: r.method, Template: r.template, HasBody: r.hasBody, Streaming: r.kind == routeStream, Socket: r.kind == routeSocket}
		if r.acl != nil {
			for _, p := range r.acl.httpProblems(e.issuers) {
				d.problem(at, "%s", p)
			}
			acl, callers := r.acl.endpointACL()
			route.Acl, route.AllowCallers = &acl, callers
			if r.acl.grants() {
				d.require(15, "route %s %s admits granted callers", e.prefix, r.describe)
			}
		}
		if r.kind == routeSocket {
			d.require(9, "a socket route (%s %s)", e.prefix, r.template)
		}
		out.Routes = append(out.Routes, route)
	}
	d.endpoints = append(d.endpoints, out)
	d.routes[e.id()] = routes
	d.endpointDecls[e.id()] = e
}

// aclFor is the ACL that governs r.
func (e *Endpoint) aclFor(r *Route) *ACL {
	if r.acl != nil {
		return r.acl
	}
	return e.acl
}

// requestOf is the request a route's handler sees.
func requestOf(r *Route, in *pb.HttpRequest) *Request {
	req := &Request{
		Method: r.method, Path: r.template, Params: map[string]string{}, Query: url.Values{}, Headers: http.Header{},
		ContentType: in.GetContentType(), Body: in.GetBody(), Metadata: metadataFromPb(in.GetMetadata()), Caller: callerOf(in.GetCaller()),
	}
	for i, name := range r.params {
		if i < len(in.GetPathArgs()) {
			req.Params[name] = in.GetPathArgs()[i]
		}
	}
	for _, p := range in.GetQuery() {
		req.Query.Add(p.GetName(), p.GetValue())
	}
	for _, p := range in.GetHeaders() {
		req.Headers.Add(p.GetName(), p.GetValue())
	}
	if p := in.GetPrincipal(); p != nil {
		req.Principal = &Principal{Subject: p.GetSubject(), Name: p.GetName(), Email: p.GetEmail(), EmailVerified: p.GetEmailVerified(), Roles: p.GetRoles(), Claims: p.GetClaims(), Issuer: p.GetIssuer()}
	}
	return req
}

func callerOf(c *pb.Caller) Caller {
	switch k := c.GetKind().(type) {
	case *pb.Caller_Gateway:
		return Caller{Kind: CallerGateway}
	case *pb.Caller_Service:
		return Caller{Kind: CallerService, Project: k.Service.GetProject(), Name: k.Service.GetName()}
	case *pb.Caller_Machine:
		return Caller{Kind: CallerMachine, Organization: k.Machine.GetOrganization(), Name: k.Machine.GetName()}
	}
	return Caller{Kind: CallerLocal}
}

// errorResponse is a handler's error as an answer: an HttpProblem its status, a refusal its code's,
// anything else nil, which the caller answers as the request's failure.
func errorResponse(err error) *pb.HttpResponse {
	var hp *HttpProblem
	if errors.As(err, &hp) {
		return textResponse(hp.Status, hp.Message)
	}
	if r, ok := AsRefusal(err); ok {
		return textResponse(r.Code.HTTPStatus(), r.Message)
	}
	return nil
}

// handleHTTP runs one request of a route, as Http.Handle and the endpoint test kit do.
func (d *declarations) handleHTTP(ctx context.Context, in *pb.HttpRequest) (*pb.HttpResponse, error) {
	e, r := d.endpointDecls[in.GetEndpointId()], d.routes[in.GetEndpointId()][in.GetRouteId()]
	if e == nil || r == nil {
		return textResponse(http.StatusNotFound, fmt.Sprintf("no route %s %s", in.GetEndpointId(), in.GetRouteId())), nil
	}
	if r.kind != routeRequest {
		return nil, fmt.Errorf("%s %s is not a request route; this runtime asked for a request", e.prefix, r.describe)
	}
	req := requestOf(r, in)
	if ok, why := e.aclFor(r).admitsIssuer(req.Principal, e.issuers); !ok {
		return textResponse(http.StatusForbidden, why), nil
	}
	ctx = withRequest(withMetadata(ctx, req.Metadata), req)
	res, err := r.run(ctx, req)
	if err != nil {
		if out := errorResponse(err); out != nil {
			return out, nil
		}
		return nil, err
	}
	return res, nil
}
