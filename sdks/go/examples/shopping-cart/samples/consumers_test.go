package samples

import (
	"context"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/cart"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/conformance"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

func TestTheNotifierPublishesCheckoutsOnly(t *testing.T) {
	ctx := context.Background()
	kit := testkit.Consumer(CheckoutNotifier)
	defer kit.Close()
	if out, err := kit.OnMessage(ctx, "c1", cart.ItemAdded{Item: pen}, testkit.Sequence(1)); err != nil || len(out) != 0 {
		t.Fatalf("an item added published %v, %v", out, err)
	}
	out, err := kit.OnMessage(ctx, "c1", cart.CheckedOut{}, testkit.Sequence(2))
	if err != nil || len(out) != 1 {
		t.Fatalf("a checkout published %v, %v", out, err)
	}
	notice, ok := out[0].Payload.(CheckoutNotice)
	if !ok || notice.CartID != "c1" || notice.At == 0 || out[0].Key != "c1" {
		t.Fatalf("the notice is %+v under %q", out[0].Payload, out[0].Key)
	}
}

func TestAMirroredChangeSaysWhereItWasWritten(t *testing.T) {
	ctx := context.Background()
	kit := testkit.Consumer(VehicleBuilds)
	defer kit.Close()
	out, err := kit.OnMessage(ctx, "v1", conformance.StepAdded{Step: "frame"}, testkit.Sequence(1),
		testkit.WithMetadata(ankka.Metadata{"ankka.installation": "leipzig", "ankka.local": "false"}))
	if err != nil || len(out) != 1 {
		t.Fatalf("published %v, %v", out, err)
	}
	if b := out[0].Payload.(Build); b != (Build{Vehicle: "v1", Step: "frame", Installation: "leipzig", Local: false}) {
		t.Fatalf("the build is %+v", b)
	}
}

// docs:start consumer-test

func TestACheckoutIsAnsweredWithThreeMessages(t *testing.T) {
	ctx := context.Background()
	kit := testkit.Consumer(conformance.CheckoutFanout)
	defer kit.Close()
	out, err := kit.OnMessage(ctx, "c1", cart.CheckedOut{}, testkit.Sequence(4))
	if err != nil || len(out) != 3 {
		t.Fatalf("a checkout published %v, %v", out, err)
	}
	for i, n := range []int{1, 2, 3} {
		if out[i].Payload != (conformance.Fanned{N: n}) {
			t.Fatalf("message %d is %+v", i, out[i].Payload)
		}
	}
	// The key the second message named; the others are keyed by their subject, the cart's id.
	if out[0].Key != "c1" || out[1].Key != "second:c1" || out[2].Key != "c1" {
		t.Fatalf("the keys are %q %q %q", out[0].Key, out[1].Key, out[2].Key)
	}
	if out[2].Metadata["x-n"] != "3" {
		t.Fatalf("the third message's metadata is %v", out[2].Metadata)
	}
}

// docs:end consumer-test
