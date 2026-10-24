//go:build ignore

// The twin of the other files here, written as they should be: it compiles.
package main

import (
	"context"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

type state struct{}

var _ = ankka.Query("get", func(ctx context.Context, s state, _ ankka.Unit) (ankka.ReadOnlyEffect[ankka.Done], error) {
	return ankka.NoReply[ankka.Done](), nil
})

var _ = ankka.NewEndpoint("/open", ankka.AllowAll(),
	ankka.Get("/", func(ctx context.Context, req *ankka.Request) (ankka.Done, error) { return ankka.Done{}, nil }),
)

func main() {}
