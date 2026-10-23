package cart

import (
	"context"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// docs:start endpoint

// API is the Scala sample's routes, exactly: /carts/{cartId}, /total, /items, /items/{productId},
// /checkout, DELETE /carts/{cartId}.
var API = ankka.NewEndpoint("/carts", ankka.AllowAll(),
	// A literal beside a parameter: /carts/awkward is this route, never the cart "awkward".
	ankka.Get("/awkward", func(context.Context, *ankka.Request) (string, error) { return "literal", nil }),
	ankka.Get("/rows", func(ctx context.Context, _ *ankka.Request) ([]Row, error) {
		return ankka.QueryView[Row](ctx, RowsID, ankka.All())
	}),
	// docs:start problem
	ankka.Get("/{cartId}/rows", func(ctx context.Context, req *ankka.Request) (Row, error) {
		row, found, err := ankka.ViewRow[Row](ctx, RowsID, req.Param("cartId"))
		if err == nil && !found {
			err = &ankka.HttpProblem{Status: 404, Message: "no row for '" + req.Param("cartId") + "'"}
		}
		return row, err
	}),
	// docs:end problem
	// docs:start call-entity
	ankka.Get("/{cartId}", func(ctx context.Context, req *ankka.Request) (Cart, error) {
		return ankka.Invoke[Cart](ctx, ComponentID, req.Param("cartId"), "get-cart", ankka.Unit{})
	}),
	ankka.Get("/{cartId}/total", func(ctx context.Context, req *ankka.Request) (int, error) {
		return ankka.Invoke[int](ctx, ComponentID, req.Param("cartId"), "total-quantity", ankka.Unit{})
	}),
	ankka.Post("/{cartId}/items", func(ctx context.Context, req *ankka.Request, item LineItem) (ankka.Done, error) {
		return ankka.Invoke[ankka.Done](ctx, ComponentID, req.Param("cartId"), "add-item", item)
	}),
	// docs:end call-entity
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

// docs:start service

// Service is the whole inventory: registration is explicit, so nothing is found by scanning.
func Service() *ankka.Service { return ankka.NewService(Entity, API, Calling) }

// docs:end service
