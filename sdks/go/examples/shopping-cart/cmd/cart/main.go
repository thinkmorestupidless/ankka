// Command cart runs the shopping cart beside a sidecar: `go run ./cmd/cart`, beside
// `docker compose --profile polyglot up -d` in the repository root.
package main

import (
	"context"
	"fmt"
	"os"

	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/cart"
)

// docs:start main
func main() {
	if err := cart.Service().Start(context.Background()); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

// docs:end main
