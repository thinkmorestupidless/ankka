package samples

import (
	"context"
	"strings"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/cart"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

// docs:start mcp-test

func TestAnAssistantFillsACartThroughTheTools(t *testing.T) {
	ctx := context.Background()
	// The kit runs the tools as the platform would once it has admitted the caller: the key's
	// subject is the principal the tools see.
	kit := testkit.Mcp(CartMcpEndpoint, cart.Entity)
	defer kit.Close()
	assistant := &ankka.Principal{Subject: "assistant-1"}

	added, err := kit.Call(ctx, "add_item", map[string]any{"cartId": "c1", "productId": "pen", "quantity": 2}, assistant)
	if err != nil {
		t.Fatalf("add_item answered %v", err)
	}
	if added.Text == "" {
		t.Fatal("add_item answered nothing")
	}
	got, err := kit.Call(ctx, "get_cart", map[string]any{"cartId": "c1"}, assistant)
	if err != nil || !strings.Contains(got.StructuredJSON, `"productId":"pen"`) {
		t.Fatalf("get_cart answered %+v, %v", got, err)
	}
	// A refused command is the tool's error, which a model reads and can act on.
	if _, err := kit.Call(ctx, "add_item", map[string]any{"cartId": "c1", "productId": "ink", "quantity": 0}, assistant); err == nil {
		t.Fatal("a zero quantity was added")
	}
}

// docs:end mcp-test
