package samples

import (
	"context"
	"errors"
	"fmt"
	"sync"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/cart"
)

// ── the component client, from an endpoint ──

// CartTotals is what the fan-out answers: each cart's total, by id.
type CartTotals map[string]int

// ClientEndpoint drives the cart through the client, as the component client guide shows.
var ClientEndpoint = ankka.NewEndpoint("/client", ankka.AllowAll(),
	// docs:start addressing
	// A component is addressed by its id and instance, then by its handler's wire name; the reply is
	// decoded as the type the caller names.
	ankka.Post("/carts/{cartId}", func(ctx context.Context, req *ankka.Request, item cart.LineItem) (cart.Cart, error) {
		cartID := req.Param("cartId")
		if _, err := ankka.Invoke[ankka.Done](ctx, "shopping-cart", cartID, "add-item", item); err != nil {
			return cart.Cart{}, err
		}
		return ankka.Invoke[cart.Cart](ctx, "shopping-cart", cartID, "get-cart", ankka.Unit{})
	}),
	// docs:end addressing
	// docs:start fan-out
	// One goroutine per call; the whole takes as long as the slowest. Each goroutine's ctx is the
	// handler's, so every call joins the request's trace.
	ankka.Post("/totals", func(ctx context.Context, _ *ankka.Request, cartIDs []string) (CartTotals, error) {
		var wg sync.WaitGroup
		var mu sync.Mutex
		totals, errs := CartTotals{}, []error{}
		for _, id := range cartIDs {
			wg.Add(1)
			go func() {
				defer wg.Done()
				total, err := ankka.Invoke[int](ctx, "shopping-cart", id, "total-quantity", ankka.Unit{})
				mu.Lock()
				defer mu.Unlock()
				totals[id], errs = total, append(errs, err)
			}()
		}
		wg.Wait()
		return totals, errors.Join(errs...)
	}),
	// docs:end fan-out
	// docs:start refusal
	// A refused command is a *RefusalError carrying the entity's code; nothing was persisted. Left
	// to propagate, the sidecar answers the status for its code; handled, it is read like this.
	ankka.Post("/carts/{cartId}/try", func(ctx context.Context, req *ankka.Request, item cart.LineItem) (string, error) {
		_, err := ankka.Invoke[ankka.Done](ctx, "shopping-cart", req.Param("cartId"), "add-item", item)
		var refusal *ankka.RefusalError
		if errors.As(err, &refusal) && refusal.Code == ankka.BadRequest {
			return "refused: " + refusal.Message, nil
		}
		if err != nil {
			return "", err
		}
		return "added", nil
	}),
	// docs:end refusal
	// docs:start view-query
	// A view is queried by its id and a declared query, not by an instance: every row of the cart
	// rows view, decoded as the row type.
	ankka.Get("/rows", func(ctx context.Context, _ *ankka.Request) ([]cart.Row, error) {
		return ankka.QueryView[cart.Row](ctx, "cart-rows", ankka.All())
	}),
	// docs:end view-query
	// docs:start start-checkout
	// The handler's ctx carries the request's trace, so the call is a child of the request in the
	// console rather than a trace of its own; mode is the body: ok, fail or pause.
	ankka.Post("/{cartId}/checkouts", func(ctx context.Context, req *ankka.Request, mode string) (string, error) {
		if mode == "" {
			mode = "ok"
		}
		return ankka.Invoke[string](ctx, "checkout", req.Param("cartId"), "start", mode)
	}),
	// docs:end start-checkout
)

// describeRefusal is how a caller reads a refusal without errors.As, as the guide's table shows.
func describeRefusal(err error) string {
	if r, ok := ankka.AsRefusal(err); ok {
		return fmt.Sprintf("%s: %s", r.Code, r.Message)
	}
	return err.Error()
}
