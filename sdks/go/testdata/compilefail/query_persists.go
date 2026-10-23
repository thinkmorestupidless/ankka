//go:build ignore

// A query cannot persist: its handler answers a ReadOnlyEffect, and a persisting Effect is not one.
package main

import (
	"context"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

type state struct{}

type event interface{ event() }

type happened struct{}

func (happened) event() {}

var _ = ankka.Query("get", func(ctx context.Context, s state, _ ankka.Unit) (ankka.ReadOnlyEffect[ankka.Done], error) {
	return ankka.Persist[state, event, ankka.Done](happened{}).ThenNoReply(), nil
})

func main() {}
