package conformance

import (
	"context"
	"errors"
	"slices"
	"time"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/cart"
)

// ── profile: a key value entity ──

// ProfileState is a profile's state.
type ProfileState struct {
	Name string `json:"name"`
}

type kvEffect[R any] = ankka.KeyValueEffect[ProfileState, R]

// Profile is the key value entity: set, get and delete a name.
var Profile = ankka.KeyValue[ProfileState]("profile", func(string) ProfileState { return ProfileState{} },
	ankka.Update("set", func(_ context.Context, _ ProfileState, name string) (kvEffect[string], error) {
		if name == "" {
			return kvEffect[string]{}, ankka.Refuse(ankka.BadRequest, "a name is needed")
		}
		return ankka.UpdateState[ProfileState, string](ProfileState{Name: name}).ThenReplyValue("done"), nil
	}),
	ankka.Query("get", func(_ context.Context, s ProfileState, _ ankka.Unit) (ankka.ReadOnlyEffect[string], error) {
		if s.Name == "" {
			return ankka.Reply("none"), nil
		}
		return ankka.Reply(s.Name), nil
	}),
	ankka.Update("delete", func(_ context.Context, _ ProfileState, _ ankka.Unit) (kvEffect[string], error) {
		return ankka.DeleteState[ProfileState, string]().ThenReplyValue("done"), nil
	}),
	ankka.StateCodec(ankka.CodecFor[ProfileState](ankka.Manifest("profile"))),
)

// ── checkout: a workflow ──

// Checkout is a checkout's state, in the Scala reference's shape: the id, the mode it was started
// in and where it has got to.
type Checkout struct {
	ID     string `json:"id"`
	Mode   string `json:"mode"`
	Status string `json:"status"`
}

var errDeclined = errors.New("payment declined")

func step(s Checkout) ankka.StepBuilder[Checkout] { return ankka.StepUpdate(s) }

// CheckoutFlow is a checkout as a durable multi-step process: reserve, then charge, or compensate.
// mode is ok, fail (the charge is declined and compensated), pause (a pause before charging),
// abort (declined; the compensation records it and fails the workflow), drop (declined; the
// compensation fails the workflow and records nothing) or doomed (declined, and the compensation
// fails too, so the workflow fails).
var CheckoutFlow = ankka.Workflow[Checkout]("checkout", func(id string) Checkout {
	return Checkout{ID: id, Status: "new"}
},
	ankka.WorkflowCommand("start", func(_ context.Context, s Checkout, mode string) (ankka.WorkflowEffect[Checkout, string], error) {
		if s.Status != "new" {
			return ankka.WorkflowEffect[Checkout, string]{}, ankka.Refuse(ankka.Conflict, "already started")
		}
		s.Status, s.Mode = "reserving", mode
		return ankka.WorkflowUpdate[Checkout, string](s).ThenTransitionTo("reserve", nil).ThenReplyValue("started"), nil
	}),
	ankka.Query("status", func(_ context.Context, s Checkout, _ ankka.Unit) (ankka.ReadOnlyEffect[string], error) {
		return ankka.Reply(s.Status), nil
	}),
	ankka.Step("reserve", func(ctx context.Context, s Checkout, _ ankka.Unit) (ankka.StepEffect[Checkout], error) {
		// A client call from a step: what the cart holds.
		if _, err := ankka.Invoke[int](ctx, cart.ComponentID, s.ID, "total-quantity", ankka.Unit{}); err != nil {
			return ankka.StepEffect[Checkout]{}, err
		}
		next := "charge"
		if s.Mode == "pause" {
			next = "wait"
		}
		s.Status = "reserved"
		return step(s).ThenTransitionTo(next, nil), nil
	}),
	ankka.Step("wait", func(_ context.Context, s Checkout, _ ankka.Unit) (ankka.StepEffect[Checkout], error) {
		s.Status = "waiting"
		return step(s).ThenPause(ankka.PauseOptions{After: 1500 * time.Millisecond, OnTimeout: "charge"}), nil
	}),
	ankka.Step("charge", func(_ context.Context, s Checkout, _ ankka.Unit) (ankka.StepEffect[Checkout], error) {
		if slices.Contains([]string{"fail", "abort", "drop", "doomed"}, s.Mode) {
			return ankka.StepEffect[Checkout]{}, errDeclined
		}
		s.Status = "charged"
		return step(s).ThenEnd(), nil
	}),
	ankka.Step("compensate", func(_ context.Context, s Checkout, _ ankka.Unit) (ankka.StepEffect[Checkout], error) {
		switch s.Mode {
		case "abort":
			s.Status = "aborted"
			return step(s).ThenFail(ankka.WorkflowFailed, "payment declined"), nil
		case "drop":
			return ankka.FailWorkflow[Checkout](ankka.WorkflowFailed, "payment declined"), nil
		case "doomed":
			return ankka.StepEffect[Checkout]{}, errors.New("compensation failed too")
		}
		s.Status = "compensated"
		return step(s).ThenEnd(), nil
	}),
	ankka.StateCodec(ankka.CodecFor[Checkout](ankka.Manifest("checkout"))),
	ankka.StepTimeout(10*time.Second),
	ankka.ForStep("charge", ankka.StepRecovery(ankka.Recovery{MaxRetries: 1, FailoverTo: "compensate"})),
)

// ── replicated-vehicle: a replicated entity ──

// VehicleRecord is a replicated entity's state: each step, where its event was written ("" when no
// installation is named), whether that was here, who acted and where it was forwarded from.
type VehicleRecord struct {
	Steps         []string `json:"steps"`
	WrittenAt     []string `json:"writtenAt"`
	Local         []bool   `json:"local"`
	ActedBy       []string `json:"actedBy"`
	ForwardedFrom []string `json:"forwardedFrom"`
}

// VehiclePlacement is where a command ran.
type VehiclePlacement struct {
	Installation  string `json:"installation"`
	Home          bool   `json:"home"`
	Attribution   string `json:"attribution"`
	ForwardedFrom string `json:"forwardedFrom"`
}

// StepAdded is a vehicle's event.
type StepAdded struct {
	Step string `json:"step"`
}

type vehicleEffect[R any] = ankka.Effect[VehicleRecord, StepAdded, R]

func vehicle[R any](events ...StepAdded) ankka.PersistBuilder[VehicleRecord, StepAdded, R] {
	return ankka.Persist[VehicleRecord, StepAdded, R](events...)
}

func emptyVehicle(string) VehicleRecord {
	return VehicleRecord{Steps: []string{}, WrittenAt: []string{}, Local: []bool{}, ActedBy: []string{}, ForwardedFrom: []string{}}
}

func appendStep(s VehicleRecord, e StepAdded, at EventPlace) VehicleRecord {
	return VehicleRecord{
		Steps:         append(slices.Clone(s.Steps), e.Step),
		WrittenAt:     append(slices.Clone(s.WrittenAt), at.Installation),
		Local:         append(slices.Clone(s.Local), at.Local),
		ActedBy:       append(slices.Clone(s.ActedBy), at.Attribution),
		ForwardedFrom: append(slices.Clone(s.ForwardedFrom), at.ForwardedFrom),
	}
}

// EventPlace is where an event was written, as the apply is told it.
type EventPlace struct {
	Installation, Attribution, ForwardedFrom string
	Local                                    bool
}

func replicate(f func(target string) ankka.PersistBuilder[VehicleRecord, StepAdded, string]) func(context.Context, VehicleRecord, string) (vehicleEffect[string], error) {
	return func(_ context.Context, _ VehicleRecord, target string) (vehicleEffect[string], error) {
		return f(target).ThenReplyValue(target), nil
	}
}

// ReplicatedVehicle is the replicated entity. The conformance service runs at installation a.
var ReplicatedVehicle = ankka.EventSourced[VehicleRecord, StepAdded]("replicated-vehicle", nil, emptyVehicle,
	func(s VehicleRecord, e StepAdded) VehicleRecord { return appendStep(s, e, EventPlace{Local: true}) },
	ankka.ApplyWithContext(func(ctx context.Context, s VehicleRecord, e StepAdded) VehicleRecord {
		at := EventPlace{Local: true}
		if ev, ok := ankka.EventFrom(ctx); ok {
			at = EventPlace{Installation: ev.Installation, Local: ev.Local, Attribution: ev.Attribution, ForwardedFrom: ev.ForwardedFrom}
		}
		return appendStep(s, e, at)
	}),
	ankka.Replicated(),
	ankka.Command("add-step", func(ctx context.Context, _ VehicleRecord, s string) (vehicleEffect[VehiclePlacement], error) {
		// Read while the handler runs: the reply is computed once it has returned.
		var p VehiclePlacement
		if c, ok := ankka.CommandFrom(ctx); ok {
			p = VehiclePlacement{Installation: c.Installation, Home: c.AtHome, Attribution: c.Attribution, ForwardedFrom: c.ForwardedFrom}
		}
		return vehicle[VehiclePlacement](StepAdded{Step: s}).ThenReplyValue(p), nil
	}),
	ankka.Command("hand-over", replicate(func(t string) ankka.PersistBuilder[VehicleRecord, StepAdded, string] {
		return vehicle[string]().HandOverTo(t)
	})),
	ankka.Command("replicate-to", replicate(func(t string) ankka.PersistBuilder[VehicleRecord, StepAdded, string] {
		return vehicle[string]().ReplicateTo(t)
	})),
	ankka.Command("stop-replicating-to", replicate(func(t string) ankka.PersistBuilder[VehicleRecord, StepAdded, string] {
		return vehicle[string]().StopReplicatingTo(t)
	})),
	ankka.Query("get", func(_ context.Context, s VehicleRecord, _ ankka.Unit) (ankka.ReadOnlyEffect[VehicleRecord], error) {
		return ankka.Reply(s), nil
	}),
	ankka.StateCodec(ankka.CodecFor[VehicleRecord](ankka.Manifest("vehicle-record"))),
	ankka.EventCodec(ankka.CodecFor[StepAdded](ankka.Manifest("step-added"))),
)

// ── tree-node: a tree, walked by a declared recursive query ──

// TreePlaced is a node placed under another, or under none (nil).
type TreePlaced struct {
	Under *string `json:"under"`
}

// TreeNode is a node of a tree; place's input is the parent's id, empty for none.
var TreeNode = ankka.EventSourced[TreePlaced, TreePlaced]("tree-node", nil, func(string) TreePlaced { return TreePlaced{} },
	func(_ TreePlaced, e TreePlaced) TreePlaced { return e },
	ankka.Command("place", func(_ context.Context, _ TreePlaced, under string) (ankka.Effect[TreePlaced, TreePlaced, string], error) {
		var e TreePlaced
		if under != "" {
			e.Under = &under
		}
		return ankka.Persist[TreePlaced, TreePlaced, string](e).ThenReplyValue("placed"), nil
	}),
	ankka.StateCodec(ankka.CodecFor[TreePlaced](ankka.Manifest("tree-node"))),
	ankka.EventCodec(ankka.CodecFor[TreePlaced](ankka.Manifest("tree-event"))),
)

// ── joined-left and joined-right: the two sides of a keyed view ──

// Noted is a line of text an entity recorded.
type Noted struct {
	Text string `json:"text"`
}

func joinedSide(id string) *ankka.EventSourcedEntity[int, Noted] {
	return ankka.EventSourced[int, Noted](id, nil, func(string) int { return 0 },
		func(s int, _ Noted) int { return s + 1 },
		ankka.Command("record", func(_ context.Context, _ int, text string) (ankka.Effect[int, Noted, string], error) {
			return ankka.Persist[int, Noted, string](Noted{Text: text}).ThenReplyValue("recorded"), nil
		}),
		ankka.StateCodec(ankka.CodecFor[int](ankka.Manifest("joining"))),
		ankka.EventCodec(ankka.CodecFor[Noted](ankka.Manifest("noted"))),
	)
}

// JoinedLeft and JoinedRight are the keyed view's two sources.
var (
	JoinedLeft  = joinedSide("joined-left")
	JoinedRight = joinedSide("joined-right")
)
