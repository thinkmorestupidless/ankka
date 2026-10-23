package testkit

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"sync"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/internal/kit"
	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"github.com/thinkmorestupidless/ankka-go/internal/server"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/status"
	"google.golang.org/grpc/test/bufconn"
)

// EndpointKit drives an endpoint's routes, and the components they call through the client, in
// this process: a call to a component reaches the same host a sidecar's request would.
type EndpointKit struct {
	view   *kit.Service
	client *ankka.Client
	conn   *grpc.ClientConn
	server *grpc.Server
}

// Endpoint is a kit for the endpoint e and the components its routes call. It panics, naming
// each problem, when the declarations have problems a service would refuse to start with.
func Endpoint(e *ankka.Endpoint, components ...ankka.Declaration) *EndpointKit {
	v := view(ankka.NewService(append([]ankka.Declaration{e}, components...)...))
	lis := bufconn.Listen(1 << 20)
	g := grpc.NewServer()
	pb.RegisterClientServer(g, &standIn{view: v, entities: entityHosts(v)})
	go func() { _ = g.Serve(lis) }()
	conn, err := grpc.NewClient("passthrough:///testkit",
		grpc.WithContextDialer(func(ctx context.Context, _ string) (net.Conn, error) { return lis.DialContext(ctx) }),
		grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		panic("testkit: " + err.Error())
	}
	return &EndpointKit{view: v, client: v.Client(conn).(*ankka.Client), conn: conn, server: g}
}

// Close stops the kit's stand-in for the sidecar.
func (k *EndpointKit) Close() {
	_ = k.conn.Close()
	k.server.Stop()
}

// Request is one request to a route.
type Request struct {
	Method string
	// Path is the whole path, the endpoint's prefix included, with any query.
	Path   string
	Body   any
	Header http.Header
	// Principal, when set, is the caller an authenticated route sees.
	Principal *ankka.Principal
}

// Response is a route's answer.
type Response struct {
	Status      int
	ContentType string
	Header      http.Header
	Body        []byte
	// Failure is why the request failed rather than answered (a handler's error or panic, which
	// the sidecar answers 500), or nil.
	Failure error
}

// Text is the body as text.
func (r *Response) Text() string { return string(r.Body) }

// JSON reads a response's body as JSON into a T.
func JSON[T any](r *Response) (T, error) {
	return ankka.CodecFor[T](ankka.Manifest("json")).Decode(r.Body)
}

// Get requests path with GET.
func (k *EndpointKit) Get(ctx context.Context, path string) *Response {
	return k.Do(ctx, Request{Method: http.MethodGet, Path: path})
}

// Delete requests path with DELETE.
func (k *EndpointKit) Delete(ctx context.Context, path string) *Response {
	return k.Do(ctx, Request{Method: http.MethodDelete, Path: path})
}

// Post requests path with POST and body, encoded by the route's codec.
func (k *EndpointKit) Post(ctx context.Context, path string, body any) *Response {
	return k.Do(ctx, Request{Method: http.MethodPost, Path: path, Body: body})
}

// Put requests path with PUT and body.
func (k *EndpointKit) Put(ctx context.Context, path string, body any) *Response {
	return k.Do(ctx, Request{Method: http.MethodPut, Path: path, Body: body})
}

// Patch requests path with PATCH and body.
func (k *EndpointKit) Patch(ctx context.Context, path string, body any) *Response {
	return k.Do(ctx, Request{Method: http.MethodPatch, Path: path, Body: body})
}

// Do makes one request, as the sidecar forwards it: a path no route matches is 404.
func (k *EndpointKit) Do(ctx context.Context, req Request) *Response {
	endpointID, routeID, args, ok := k.view.Route(req.Method, req.Path)
	if !ok {
		return &Response{Status: http.StatusNotFound, ContentType: "text/plain; charset=utf-8", Body: []byte("no route " + req.Method + " " + req.Path)}
	}
	in := &pb.HttpRequest{EndpointId: endpointID, RouteId: routeID, PathArgs: args, Metadata: &pb.Metadata{}, Caller: &pb.Caller{Kind: &pb.Caller_Local{Local: &pb.Empty{}}}}
	if _, query, found := strings.Cut(req.Path, "?"); found {
		values, err := url.ParseQuery(query)
		if err != nil {
			return &Response{Status: http.StatusBadRequest, Body: []byte(err.Error())}
		}
		for _, name := range sortedKeys(values) {
			for _, v := range values[name] {
				in.Query = append(in.Query, &pb.HttpRequest_Pair{Name: name, Value: v})
			}
		}
	}
	for _, name := range sortedKeys(req.Header) {
		for _, v := range req.Header[name] {
			in.Headers = append(in.Headers, &pb.HttpRequest_Pair{Name: name, Value: v})
		}
	}
	if req.Body != nil {
		body, contentType, err := k.view.Body(endpointID, routeID, req.Body)
		if err != nil {
			return &Response{Status: http.StatusBadRequest, Failure: err}
		}
		in.Body, in.ContentType = body, contentType
	}
	if p := req.Principal; p != nil {
		in.Principal = &pb.Principal{Subject: p.Subject, EmailVerified: p.EmailVerified, Roles: p.Roles, Claims: p.Claims}
		if p.Name != "" {
			in.Principal.Name = &p.Name
		}
		if p.Email != "" {
			in.Principal.Email = &p.Email
		}
		if p.Issuer != "" {
			in.Principal.Issuer = &p.Issuer
		}
	}
	res, err := server.GuardValue(func() (*pb.HttpResponse, error) { return k.view.HTTP(ankka.WithClient(ctx, k.client), in) })
	if err != nil {
		return &Response{Status: http.StatusInternalServerError, Failure: err}
	}
	header := http.Header{}
	for _, p := range res.GetHeaders() {
		header.Add(p.GetName(), p.GetValue())
	}
	return &Response{Status: int(res.GetStatus()), ContentType: res.GetContentType(), Header: header, Body: res.GetBody()}
}

func entityHosts(v *kit.Service) map[string]*entityHost {
	out := map[string]*entityHost{}
	for id, e := range v.EventSourced {
		out[id] = newEntityHost(e)
	}
	return out
}

// standIn answers the client's calls in place of the sidecar, from the kit's own hosts.
type standIn struct {
	pb.UnimplementedClientServer
	view     *kit.Service
	mu       sync.Mutex
	entities map[string]*entityHost
}

func (s *standIn) Invoke(ctx context.Context, req *pb.InvokeRequest) (*pb.InvokeReply, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	h, ok := s.entities[req.GetComponentId()]
	if !ok {
		if _, declared := s.view.Kinds[req.GetComponentId()]; declared {
			return nil, status.Errorf(codes.Unimplemented, "the endpoint kit does not host %s's kind yet", req.GetComponentId())
		}
		return &pb.InvokeReply{Result: &pb.InvokeReply_Error{Error: &pb.Error{Code: pb.ErrorCode_NOT_FOUND, Message: fmt.Sprintf("no component %q; give it to testkit.Endpoint", req.GetComponentId())}}}, nil
	}
	out, err := h.command(ctx, req.GetEntityId(), req.GetName(), req.GetPayload(), req.GetMetadata())
	if err != nil {
		return nil, status.Error(codes.Internal, err.Error())
	}
	if f := out.GetFailure(); f != nil {
		return &pb.InvokeReply{Result: &pb.InvokeReply_Error{Error: f.GetError()}}, nil
	}
	switch o := out.GetReply().GetOutcome().GetOutcome().(type) {
	case *pb.Outcome_Reply_:
		return &pb.InvokeReply{Result: &pb.InvokeReply_Reply{Reply: o.Reply}}, nil
	case *pb.Outcome_Error:
		return &pb.InvokeReply{Result: &pb.InvokeReply_Error{Error: o.Error}}, nil
	}
	return &pb.InvokeReply{Result: &pb.InvokeReply_Reply{Reply: &pb.Outcome_Reply{Payload: &pb.Payload{Manifest: "done"}}}}, nil
}

func sortedKeys[V any](m map[string]V) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}
