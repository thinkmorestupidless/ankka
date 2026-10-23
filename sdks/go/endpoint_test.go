package ankka

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

var tAPI = NewEndpoint("/carts", AllowAll(),
	Post("/{cartId}/items", func(ctx context.Context, req *Request, item tItem) (tItem, error) {
		item.ProductID = req.Param("cartId") + ":" + item.ProductID + ":" + req.Query.Get("note")
		return item, nil
	}),
	Get("/{cartId}", func(ctx context.Context, req *Request) (string, error) {
		if r := RequestFrom(ctx); r == nil || r.Param("cartId") != req.Param("cartId") {
			return "", errors.New("the request is not on the context")
		}
		return "cart " + req.Param("cartId"), nil
	}),
	Delete("/{cartId}", func(ctx context.Context, req *Request) (Done, error) { return Done{}, nil }),
	Get("/teapot", func(ctx context.Context, req *Request) (Done, error) {
		return Done{}, &HttpProblem{Status: 418, Message: "short and stout"}
	}),
	Get("/missing", func(ctx context.Context, req *Request) (Done, error) {
		return Done{}, Refuse(NotFound, "no such cart")
	}),
	Get("/broken", func(ctx context.Context, req *Request) (Done, error) { panic("the route broke") }),
	Get("/me", func(ctx context.Context, req *Request) (string, error) {
		return req.Principal.Subject + " in " + req.Principal.Claim("org"), nil
	}).ACL(Authenticate(Issuer("keycloak"))),
)

func httpClient(t *testing.T, svc *Service) pb.HttpClient {
	t.Helper()
	if len(svc.Problems()) > 0 {
		t.Fatalf("problems: %v", svc.Problems())
	}
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	served := make(chan error, 1)
	go func() { served <- svc.Serve(ctx, lis, Logger(slog.New(slog.DiscardHandler)), GracePeriod(time.Second)) }()
	conn, err := grpc.NewClient(lis.Addr().String(), grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close(); cancel(); <-served })
	return pb.NewHttpClient(conn)
}

func call(t *testing.T, c pb.HttpClient, req *pb.HttpRequest) *pb.HttpReply {
	t.Helper()
	req.EndpointId = "/carts"
	out, err := c.Handle(context.Background(), req)
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func TestARouteRoundTrip(t *testing.T) {
	c := httpClient(t, NewService(tAPI))
	out := call(t, c, &pb.HttpRequest{RouteId: "POST /{cartId}/items", PathArgs: []string{"c1"}, Query: []*pb.HttpRequest_Pair{{Name: "note", Value: "gift"}},
		ContentType: JSONContent, Body: []byte(`{"productId":"pen","quantity":2}`)})
	r := out.GetResponse()
	if r.GetStatus() != 200 || r.GetContentType() != JSONContent || string(r.GetBody()) != `{"productId":"c1:pen:gift","quantity":2}` {
		t.Fatalf("got %v", out)
	}
	if got := call(t, c, &pb.HttpRequest{RouteId: "GET /{cartId}", PathArgs: []string{"c7"}}).GetResponse(); string(got.GetBody()) != "cart c7" || !strings.HasPrefix(got.GetContentType(), "text/plain") {
		t.Fatalf("got %v", got)
	}
	if got := call(t, c, &pb.HttpRequest{RouteId: "DELETE /{cartId}", PathArgs: []string{"c7"}}).GetResponse(); got.GetStatus() != 204 || len(got.GetBody()) != 0 {
		t.Fatalf("Done answered %v", got)
	}
	if got := call(t, c, &pb.HttpRequest{RouteId: "POST /{cartId}/items", PathArgs: []string{"c1"}, Body: []byte(`{"productId":1}`)}).GetResponse(); got.GetStatus() != 400 {
		t.Fatalf("a body that cannot be read answered %v", got)
	}
}

func TestAProblemOrARefusalSetsTheStatus(t *testing.T) {
	c := httpClient(t, NewService(tAPI))
	if got := call(t, c, &pb.HttpRequest{RouteId: "GET /teapot"}).GetResponse(); got.GetStatus() != 418 || string(got.GetBody()) != "short and stout" {
		t.Fatalf("got %v", got)
	}
	if got := call(t, c, &pb.HttpRequest{RouteId: "GET /missing"}).GetResponse(); got.GetStatus() != 404 {
		t.Fatalf("got %v", got)
	}
	if got := call(t, c, &pb.HttpRequest{RouteId: "GET /broken"}); got.GetFailure() == nil || !strings.Contains(got.GetFailure().GetError().GetMessage(), "panicked") {
		t.Fatalf("a panicking route answered %v", got)
	}
	if got := call(t, c, &pb.HttpRequest{RouteId: "GET /nothing"}).GetResponse(); got.GetStatus() != 404 {
		t.Fatalf("an unknown route answered %v", got)
	}
}

func TestThePrincipalsClaimsByName(t *testing.T) {
	c := httpClient(t, NewService(tAPI))
	who := &pb.Principal{Subject: "ada", Claims: map[string]string{"org": "acme"}}
	issuer := "keycloak"
	who.Issuer = &issuer
	if got := call(t, c, &pb.HttpRequest{RouteId: "GET /me", Principal: who}).GetResponse(); string(got.GetBody()) != "ada in acme" {
		t.Fatalf("got %v", got)
	}
	other := "elsewhere"
	who.Issuer = &other
	if got := call(t, c, &pb.HttpRequest{RouteId: "GET /me", Principal: who}).GetResponse(); got.GetStatus() != 403 {
		t.Fatalf("a token from another issuer answered %v", got)
	}
}

func TestTheEndpointDeclaresItsRoutes(t *testing.T) {
	spec := NewService(tAPI).Spec()
	e := spec.GetEndpoints()[0]
	if e.GetPrefix() != "/carts" || e.GetAcl() != pb.Endpoint_ALLOW_ALL || len(e.GetRoutes()) != 7 {
		t.Fatalf("got %v", e)
	}
	for _, r := range e.GetRoutes() {
		if r.GetTemplate() == "/me" && (r.Acl == nil || r.GetAcl() != pb.Endpoint_AUTHENTICATED) {
			t.Fatalf("the route's own ACL: %v", r)
		}
		if r.GetHasBody() != (r.GetMethod() == "POST") {
			t.Fatalf("route %v", r)
		}
	}
}

func TestStartRefusesNamingAllFour(t *testing.T) {
	dup := EventSourced[tState, tEvent]("t-cart", tEvents, func() tState { return tState{} }, tApply,
		Query("total", func(context.Context, tState, Unit) (ReadOnlyEffect[int], error) { return Reply(0), nil }),
		Query("total", func(context.Context, tState, Unit) (ReadOnlyEffect[int], error) { return Reply(0), nil }),
	)
	noACL := NewEndpoint("/open", nil, Get("/", func(context.Context, *Request) (Done, error) { return Done{}, nil }))
	shared := NewEndpoint("/carts", DenyAll(), Get("/x", func(context.Context, *Request) (Done, error) { return Done{}, nil }))
	authenticated := NewEndpoint("/admin", Authenticate(), Get("/", func(context.Context, *Request) (Done, error) { return Done{}, nil }))
	err := NewService(tCartEntity, dup, tAPI, noACL, shared, authenticated).Start(context.Background(), Port(0))
	msg := err.Error()
	for _, want := range []string{`share the id "t-cart"`, `share the wire name "total"`, `share the prefix "/carts"`, "endpoint /open: the endpoint has no ACL", "names its issuers"} {
		if !strings.Contains(msg, want) {
			t.Errorf("the refusal does not say %q:\n%s", want, msg)
		}
	}
	if err := NewService(NewEndpoint("/x", AllowAll(), Get("/", func(context.Context, *Request) (Done, error) { return Done{}, nil }).ACL(AllowIf(Granted())))).decls.refusal("1.14"); !strings.Contains(err, "1.15") {
		t.Fatalf("a granted route at 1.14: %q", err)
	}
}
