package main

// docs:start endpoint
import (
	"context"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

var ShoppingCartEndpoint = ankka.NewEndpoint("/carts", ankka.AllowAll(),
	ankka.Post("/{cartId}/items", func(ctx context.Context, req *ankka.Request, item LineItem) (ankka.Done, error) {
		return ankka.Invoke[ankka.Done](ctx, "shopping-cart", req.Param("cartId"), "add-item", item)
	}),
	ankka.Post("/{cartId}/checkout", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (ShoppingCart, error) {
		return ankka.Invoke[ShoppingCart](ctx, "shopping-cart", req.Param("cartId"), "checkout", ankka.Unit{})
	}),
	ankka.Get("/{cartId}", func(ctx context.Context, req *ankka.Request) (ShoppingCart, error) {
		return ankka.Invoke[ShoppingCart](ctx, "shopping-cart", req.Param("cartId"), "get-cart", ankka.Unit{})
	}),
)

// docs:end endpoint
