package ankka_test

import (
	"context"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

// kOpenAPI is the cart's routes a sidecar with no issuer configured serves.
var kOpenAPI = ankka.NewEndpoint("/carts", ankka.AllowAll(),
	ankka.Post("/{id}/items", func(ctx context.Context, req *ankka.Request, i kItem) (int, error) {
		return ankka.Invoke[int](ctx, "k-cart", req.Param("id"), "add", i)
	}),
	ankka.Get("/{id}", func(ctx context.Context, req *ankka.Request) (kCart, error) {
		return ankka.Invoke[kCart](ctx, "k-cart", req.Param("id"), "get", ankka.Unit{})
	}),
)

func TestTheIntegrationKitIsReadyAndARestartKeepsState(t *testing.T) {
	kit := testkit.StartT(t, ankka.NewService(kEntity, kOpenAPI))
	ctx := t.Context()

	// The first line of the body: the kit answered only once the sidecar serves the routes.
	r, err := kit.HTTP().Post(ctx, "/carts/i1/items", kItem{"pen", 2})
	if err != nil || r.Status != 200 || r.Text() != "2" {
		t.Fatalf("the first request answered %v %v", r, err)
	}
	if n, err := ankka.Invoke[int](kit.Context(ctx), "k-cart", "i1", "total", ankka.Unit{}); err != nil || n != 2 {
		t.Fatalf("the kit's client answered %d %v", n, err)
	}

	if err := kit.Restart(ctx); err != nil {
		t.Fatal(err)
	}
	cart, err := testkit.GetJSON[kCart](ctx, kit.HTTP(), "/carts/i1")
	if err != nil || cart.ID != "i1" || len(cart.Items) != 1 || cart.Items[0] != (kItem{"pen", 2}) {
		t.Fatalf("after a restart the cart is %+v (%v)", cart, err)
	}
	// The service's own client follows the new sidecar: the route calls the entity through it.
	if r, err := kit.HTTP().Post(ctx, "/carts/i1/items", kItem{"ink", 1}); err != nil || r.Text() != "3" {
		t.Fatalf("after a restart a route's call answered %v %v", r, err)
	}
	if n, err := ankka.Invoke[int](kit.Context(ctx), "k-cart", "i1", "total", ankka.Unit{}); err != nil || n != 3 {
		t.Fatalf("after a restart the kit's client answered %d %v", n, err)
	}
}
