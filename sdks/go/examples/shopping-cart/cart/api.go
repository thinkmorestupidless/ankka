package cart

import (
	"context"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// docs:start endpoint

// API is the Scala sample's routes, exactly: /carts/{cartId}, /total, /items, /items/{productId},
// /checkout, DELETE /carts/{cartId}.
var API = ankka.NewEndpoint("/carts", ankka.AllowAll(),
	ankka.Get("/{cartId}", func(ctx context.Context, req *ankka.Request) (Cart, error) {
		return ankka.Invoke[Cart](ctx, ComponentID, req.Param("cartId"), "get-cart", ankka.Unit{})
	}),
	ankka.Get("/{cartId}/total", func(ctx context.Context, req *ankka.Request) (int, error) {
		return ankka.Invoke[int](ctx, ComponentID, req.Param("cartId"), "total-quantity", ankka.Unit{})
	}),
	ankka.Post("/{cartId}/items", func(ctx context.Context, req *ankka.Request, item LineItem) (ankka.Done, error) {
		return ankka.Invoke[ankka.Done](ctx, ComponentID, req.Param("cartId"), "add-item", item)
	}),
	ankka.Delete("/{cartId}/items/{productId}", func(ctx context.Context, req *ankka.Request) (ankka.Done, error) {
		return ankka.Invoke[ankka.Done](ctx, ComponentID, req.Param("cartId"), "remove-item", req.Param("productId"))
	}),
	ankka.Post("/{cartId}/checkout", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (Cart, error) {
		return ankka.Invoke[Cart](ctx, ComponentID, req.Param("cartId"), "checkout", ankka.Unit{})
	}),
	ankka.Delete("/{cartId}", func(ctx context.Context, req *ankka.Request) (ankka.Done, error) {
		return ankka.Invoke[ankka.Done](ctx, ComponentID, req.Param("cartId"), "discard", ankka.Unit{})
	}),
)

// docs:end endpoint

// Service is the whole inventory: registration is explicit, so nothing is found by scanning.
func Service() *ankka.Service { return ankka.NewService(Entity, API) }
