// Package conformance is the Go reference service the platform's conformance suite runs against:
// the cart, and components whose handlers are the protocol's edge cases, under the same ids, wire
// names, manifests and routes as every other SDK's reference.
package conformance

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"sync/atomic"
	"time"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/cart"
)

// Recorded is one input the conformance entity kept.
type Recorded struct {
	Input string `json:"input"`
}

// Recordings is the conformance entity's state.
type Recordings struct {
	Items []string `json:"items"`
}

// recordedCases is nil: the entity's events are one record, not a sum type, so they need no cases.
var recordedCases *ankka.SumType[Recorded]

type effect[R any] = ankka.Effect[Recordings, Recorded, R]

func persist[R any](events ...Recorded) ankka.PersistBuilder[Recordings, Recorded, R] {
	return ankka.Persist[Recordings, Recorded, R](events...)
}

func doneText(Recordings) string { return "done" }

// Entity is the entity whose handlers are the protocol's edge cases.
var Entity = ankka.EventSourced[Recordings, Recorded]("conformance", recordedCases,
	func(string) Recordings { return Recordings{Items: []string{}} },
	func(s Recordings, e Recorded) Recordings {
		s.Items = append(append([]string(nil), s.Items...), e.Input)
		return s
	},
	ankka.Command("record", func(_ context.Context, _ Recordings, input string) (effect[string], error) {
		return persist[string](Recorded{Input: input}).ThenReply(doneText), nil
	}),
	ankka.Command("record-many", func(_ context.Context, _ Recordings, n int) (effect[string], error) {
		events := make([]Recorded, n)
		for i := range events {
			events[i] = Recorded{Input: fmt.Sprintf("many-%d", i)}
		}
		return persist[string](events...).ThenReply(doneText), nil
	}),
	ankka.Command("refuse", func(_ context.Context, _ Recordings, _ string) (effect[string], error) {
		return effect[string]{}, ankka.Refuse(ankka.Conflict, "refused on purpose")
	}),
	ankka.Command("no-reply", func(_ context.Context, _ Recordings, _ string) (effect[string], error) {
		return persist[string](Recorded{Input: "silent"}).ThenNoReply(), nil
	}),
	ankka.Command("delete", func(_ context.Context, _ Recordings, _ string) (effect[string], error) {
		return persist[string]().DeleteEntity().ThenReply(doneText), nil
	}),
	ankka.Command("expire", func(_ context.Context, _ Recordings, millis int) (effect[string], error) {
		return persist[string](Recorded{Input: "expiring"}).ExpireAfter(time.Duration(millis) * time.Millisecond).ThenReply(doneText), nil
	}),
	ankka.Query("count", func(_ context.Context, s Recordings, _ ankka.Unit) (ankka.ReadOnlyEffect[int], error) {
		return ankka.Reply(len(s.Items)), nil
	}),
	ankka.Command("misbehave", func(_ context.Context, _ Recordings, _ string) (effect[string], error) {
		return effect[string]{}, errors.New("boom")
	}),
	ankka.StateCodec(ankka.CodecFor[Recordings](ankka.Manifest("conformance-state"))),
	ankka.EventCodec(ankka.CodecFor[Recorded](ankka.Manifest("conformance-event"))),
	ankka.SnapshotEvery(3),
)

// intInputs are the handlers whose input is a number: the forwarder reads their body as one.
var intInputs = map[string]bool{"record-many": true, "expire": true}

// API is the routes the suite drives the conformance entity through.
var API = ankka.NewEndpoint("/conformance", ankka.AllowAll(),
	// What the sidecar reported to this process, in order: a refusal at discovery among them.
	ankka.Get("/problems", func(context.Context, *ankka.Request) ([]string, error) {
		if s := reference.Load(); s != nil {
			return append([]string{}, s.Reported()...), nil
		}
		return []string{}, nil
	}),
	ankka.Get("/{id}/count", func(ctx context.Context, req *ankka.Request) (int, error) {
		return ankka.Invoke[int](ctx, "conformance", req.Param("id"), "count", ankka.Unit{})
	}),
	// A handler that answers nothing: the call is not awaited, and the route says so with 204.
	ankka.Post("/{id}/no-reply", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (ankka.Done, error) {
		go func(ctx context.Context, id string) {
			_, _ = ankka.Invoke[ankka.Done](ctx, "conformance", id, "no-reply", "")
		}(context.WithoutCancel(ctx), req.Param("id"))
		return ankka.Done{}, nil
	}),
	// The generic forwarder: the body is the handler's input as text, the reply comes back as text.
	ankka.Post("/{id}/{handler}", func(ctx context.Context, req *ankka.Request, body string) (string, error) {
		handler := req.Param("handler")
		var input any = body
		if intInputs[handler] {
			n, err := strconv.Atoi(body)
			if err != nil {
				return "", ankka.Refusef(ankka.BadRequest, "%s takes a number, not %q", handler, body)
			}
			input = n
		}
		return ankka.Invoke[string](ctx, "conformance", req.Param("id"), handler, input)
	}),
)

// reference is the service Service last made, whose reported problems the problems route answers.
var reference atomic.Pointer[ankka.Service]

// Service is the reference: the cart and the conformance components.
func Service() *ankka.Service {
	s := ankka.NewService(cart.Entity, cart.API, Entity, API)
	reference.Store(s)
	return s
}
