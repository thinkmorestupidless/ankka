//go:build ignore

// An endpoint is given its ACL: there is no default, so leaving it out does not compile.
package main

import (
	"context"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

var _ = ankka.NewEndpoint("/open",
	ankka.Get("/", func(ctx context.Context, req *ankka.Request) (ankka.Done, error) { return ankka.Done{}, nil }),
)

func main() {}
