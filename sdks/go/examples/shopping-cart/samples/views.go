package samples

import (
	"context"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/cart"
)

// CartSummary is a cart as the summaries view keeps it.
type CartSummary struct {
	CartID     string `json:"cartId"`
	Items      int    `json:"items"`
	CheckedOut bool   `json:"checkedOut"`
}

// SummariesID is the view's id.
const SummariesID = "cart-summaries"

// CartSummaries is one row per cart: how many lines it holds, and whether it was checked out.
var CartSummaries = ankka.View[CartSummary](SummariesID,
	ankka.OnChange(ankka.Changes(cart.Entity), func(ctx context.Context, e cart.Event, row *CartSummary) (ankka.ViewEffect[CartSummary], error) {
		c, _ := ankka.ChangeFrom(ctx)
		current := CartSummary{CartID: c.Subject}
		if row != nil {
			current = *row
		}
		switch e.(type) {
		case cart.ItemAdded:
			current.Items++
		case cart.ItemRemoved:
			current.Items--
		case cart.CheckedOut:
			current.CheckedOut = true
		default:
			return ankka.IgnoreChange[CartSummary](), nil
		}
		return ankka.UpdateRow(current), nil
	}),
	ankka.RowCodec(ankka.CodecFor[CartSummary](ankka.Manifest("cart-summary"))),
	// docs:start declared-queries
	// A query is SQL over the row's JSON, declared with the view and checked when the service
	// starts; a value the caller gives is bound as a parameter, never spliced into the text.
	ankka.Queries(
		// By an attribute rather than by key: the reason views exist.
		ankka.Declared("by-cart-id",
			"SELECT payload FROM "+ankka.TableOf(SummariesID)+" WHERE payload::jsonb->>'cartId' = :cart_id"),
		// The first twenty open carts, by id, descending. Every field reads as text, so a boolean
		// compares against 'true' or 'false'.
		ankka.Declared("open-carts",
			"SELECT payload FROM "+ankka.TableOf(SummariesID)+
				" WHERE payload::jsonb->>'checkedOut' = 'false' ORDER BY payload::jsonb->>'cartId' DESC LIMIT 20"),
	),
	// docs:end declared-queries
	// docs:start view-version
	// Raising the version rebuilds the view from every cart's first event.
	ankka.SourceVersion(2),
	// docs:end view-version
)

// SummariesEndpoint reads the view.
var SummariesEndpoint = ankka.NewEndpoint("/summaries", ankka.AllowAll(),
	// docs:start view-reads
	// One row by its key, or 404; every row, up to 1000.
	ankka.Get("/carts/{cartId}", func(ctx context.Context, req *ankka.Request) (CartSummary, error) {
		row, found, err := ankka.ViewRow[CartSummary](ctx, SummariesID, req.Param("cartId"))
		if err == nil && !found {
			err = &ankka.HttpProblem{Status: 404, Message: "no cart '" + req.Param("cartId") + "'"}
		}
		return row, err
	}),
	ankka.Get("/carts", func(ctx context.Context, _ *ankka.Request) ([]CartSummary, error) {
		return ankka.QueryView[CartSummary](ctx, SummariesID, ankka.All())
	}),
	// docs:end view-reads
	// docs:start named-query
	// A declared query, by its name, with the values its parameters take.
	ankka.Get("/by-cart-id/{cartId}", func(ctx context.Context, req *ankka.Request) ([]CartSummary, error) {
		return ankka.QueryView[CartSummary](ctx, SummariesID,
			ankka.Named("by-cart-id", map[string]string{"cart_id": req.Param("cartId")}))
	}),
	ankka.Get("/open", func(ctx context.Context, _ *ankka.Request) ([]CartSummary, error) {
		return ankka.QueryView[CartSummary](ctx, SummariesID, ankka.Named("open-carts", nil))
	}),
	// docs:end named-query
)

// SummariesService is the cart, its summaries and the routes that read them.
func SummariesService() *ankka.Service {
	return ankka.NewService(cart.Entity, cart.API, CartSummaries, SummariesEndpoint)
}
