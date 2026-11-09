package samples

import (
	"context"
	"time"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/cart"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/conformance"
)

// docs:start checkout-notifier

// CheckoutNotice is what leaves the service when a cart is checked out.
type CheckoutNotice struct {
	CartID string `json:"cartId"`
	At     int64  `json:"at"`
}

// CheckoutNotifier turns an internal event into a published one. The cart's own event type is an
// implementation detail; this consumer decides which events are worth telling the outside world
// about and in what shape, so the domain stays free to change without breaking anyone downstream.
var CheckoutNotifier = ankka.Consumer[cart.Event]("checkout-notifier", ankka.Changes(cart.Entity),
	func(ctx context.Context, e cart.Event) (ankka.ConsumerEffect, error) {
		if _, ok := e.(cart.CheckedOut); !ok {
			return ankka.ConsumerIgnore(), nil
		}
		change, _ := ankka.ChangeFrom(ctx)
		return ankka.Produce(CheckoutNotice{CartID: change.Subject, At: time.Now().UnixMilli()}), nil
	},
	// The topic, and the codec every message is written with.
	ankka.ProducesWith("cart-checkouts", ankka.CodecFor[CheckoutNotice](ankka.Manifest("checkout-notice"))),
)

// docs:end checkout-notifier

// Build is what the vehicle feed publishes: a step, and where it was written.
type Build struct {
	Vehicle      string `json:"vehicle"`
	Step         string `json:"step"`
	Installation string `json:"installation"`
	Local        bool   `json:"local"`
}

// docs:start mirrored-change

// VehicleBuilds publishes each step of a replicated vehicle, noting where the change was written
// and whether it was written here. Publishing, it is given local changes only; MirroredChanges()
// among its parts would hand it the mirrored ones too.
var VehicleBuilds = ankka.Consumer[conformance.StepAdded]("vehicle-builds",
	ankka.Changes(conformance.ReplicatedVehicle),
	func(ctx context.Context, e conformance.StepAdded) (ankka.ConsumerEffect, error) {
		change, _ := ankka.ChangeFrom(ctx)
		return ankka.Produce(Build{Vehicle: change.Subject, Step: e.Step,
			Installation: change.Installation, Local: change.Local}), nil
	},
	ankka.ProducesWith("vehicle-builds", ankka.CodecFor[Build](ankka.Manifest("build"))),
)

// docs:end mirrored-change
