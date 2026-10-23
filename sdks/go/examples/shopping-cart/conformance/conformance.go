// Package conformance is the Go reference service the platform's conformance suite runs against:
// the cart, and components whose handlers are the protocol's edge cases, under the same ids, wire
// names, manifests and routes as every other SDK's reference.
package conformance

import (
	"context"
	"errors"
	"fmt"
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

// reference is the service Service last made, whose reported problems the problems route answers.
var reference atomic.Pointer[ankka.Service]

// Service is the reference: the cart and every conformance component and endpoint, registered
// explicitly.
func Service() *ankka.Service {
	s := ankka.NewService(
		cart.Entity, cart.Rows, cart.API,
		Entity, Profile, CheckoutFlow, ReplicatedVehicle, TreeNode, JoinedLeft, JoinedRight,
		CheckoutRows, TopicRows, TreeRows, JoinedRows,
		CheckoutEnds, CheckoutRecorder, CheckoutFanout, TopicRelay, ContractRelay, PartnerRelay, VehicleFeed,
		CartGraph, ProfileGraph,
		Reminder, Assistant, Approver, Answerer,
		Tree, Vehicles, Joined, API, Private, Callers, AutonomousAPI, McpKeys, CartMcp,
	)
	reference.Store(s)
	return s
}
