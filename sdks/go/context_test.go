package ankka

import (
	"context"
	"net/http"
	"testing"
	"time"
)

func TestTheRequestScopeTravelsOnTheContext(t *testing.T) {
	ctx := context.Background()
	if RequestFrom(ctx) != nil {
		t.Fatal("a request outside a handler")
	}
	if _, ok := CommandFrom(ctx); ok {
		t.Fatal("a command outside a handler")
	}
	r := &Request{Params: map[string]string{"cartId": "c1"}, Headers: http.Header{"X-Thing": {"v"}},
		Caller: Caller{Kind: CallerService, Project: "p", Name: "billing"}, Metadata: Metadata{"traceparent": "t"}}
	ctx = withRequest(ctx, r)
	if got := RequestFrom(ctx); got.Param("cartId") != "c1" || got.Header("x-thing") != "v" {
		t.Fatalf("got %+v", got)
	}
	if c := CallerFrom(ctx); c.Kind != CallerService || c.Name != "billing" || c.Kind.String() != "service" {
		t.Fatalf("got %+v", c)
	}
	if MetadataFrom(ctx)["traceparent"] != "t" {
		t.Fatal("the request's metadata")
	}
	cmd := withCommand(context.Background(), CommandScope{EntityID: "e1", Sequence: 4, AtHome: true})
	if c, ok := CommandFrom(cmd); !ok || c.EntityID != "e1" || c.Sequence != 4 {
		t.Fatalf("got %+v", c)
	}
	if CallerFrom(context.Background()).Kind != CallerLocal {
		t.Fatal("a context with nothing said is not local")
	}
}

func TestACancelledContextEndsAWaitInsideAHandler(t *testing.T) {
	parent, cancel := context.WithCancel(context.Background())
	ctx := withCommand(parent, CommandScope{EntityID: "e1"})
	done := make(chan error, 1)
	go func() {
		select {
		case <-ctx.Done():
			done <- ctx.Err()
		case <-time.After(5 * time.Second):
			done <- nil
		}
	}()
	cancel()
	if err := <-done; err != context.Canceled {
		t.Fatalf("the wait ended with %v", err)
	}
}

func TestPrincipalReadsClaimsAndRoles(t *testing.T) {
	var none *Principal
	if none.Claim("x") != "" || none.HasRole("admin") {
		t.Fatal("a nil principal has claims")
	}
	p := &Principal{Roles: []string{"admin"}, Claims: map[string]string{"org": "acme"}}
	if !p.HasRole("admin") || p.Claim("org") != "acme" {
		t.Fatal("claims and roles")
	}
}
