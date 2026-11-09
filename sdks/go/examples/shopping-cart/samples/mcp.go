package samples

import (
	"context"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/cart"
)

// AddItemArguments are add_item's.
type AddItemArguments struct {
	CartID    string `json:"cartId" desc:"The cart's id"`
	ProductID string `json:"productId" desc:"The product's id"`
	Quantity  int    `json:"quantity" desc:"How many; greater than zero"`
}

// CartMcpEndpoint is the cart's MCP endpoint: an assistant reads and fills carts through it.
var CartMcpEndpoint = ankka.NewMcpEndpoint("/mcp",
	// docs:start mcp-acl
	// A request carrying one of the cart's client keys, as the key's subject (McpKeys issues them).
	ankka.AllowClientKeys(),
	// docs:end mcp-acl
	// docs:start mcp-reference
	// A tool that refers to a query of the entity: the argument named is the entity's id.
	ankka.McpQueryRef("get_cart", "Reads one cart: its lines, and whether it is checked out",
		cart.ComponentID, "get-cart", "cartId"),
	// docs:end mcp-reference
	// docs:start mcp-handler
	// A tool with a handler of its own, for arguments that are not the command's input.
	ankka.McpTool("add_item", "Adds a quantity of a product to a cart",
		func(ctx context.Context, in AddItemArguments) (ankka.Done, error) {
			item := cart.LineItem{ProductID: in.ProductID, Name: in.ProductID, Quantity: in.Quantity}
			return ankka.Invoke[ankka.Done](ctx, cart.ComponentID, in.CartID, "add-item", item)
		}),
	// docs:end mcp-handler
)
