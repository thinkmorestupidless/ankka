package main

// docs:start main
import (
	"context"
	"fmt"
	"os"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

func service() *ankka.Service { return ankka.NewService(ShoppingCartEntity, ShoppingCartEndpoint) }

func main() {
	if err := service().Start(context.Background()); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

// docs:end main
