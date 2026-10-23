package conformance

import (
	"context"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/cart"
)

func subject(ctx context.Context) string {
	c, _ := ankka.ChangeFrom(ctx)
	return c.Subject
}

// ── checkout-recorder: a consumer that acts through the client ──

// CheckoutRecorder records each checkout on the conformance entity of the cart's id.
var CheckoutRecorder = ankka.Consumer[cart.Event]("checkout-recorder", ankka.Changes(cart.Entity),
	func(ctx context.Context, e cart.Event) (ankka.ConsumerEffect, error) {
		if _, ok := e.(cart.CheckedOut); !ok {
			return ankka.ConsumerIgnore(), nil
		}
		if _, err := ankka.Invoke[string](ctx, "conformance", subject(ctx), "record", "checkout"); err != nil {
			return ankka.ConsumerEffect{}, err
		}
		return ankka.ConsumerDone(), nil
	})

// ── checkout-ends: a consumer of a workflow ──

// docs:start workflow-consumer

// CheckoutEnds records each checkout that ended, completed or failed, and nothing else.
var CheckoutEnds = ankka.Consumer[Checkout]("checkout-ends", ankka.WorkflowSource(CheckoutFlow),
	func(ctx context.Context, _ Checkout) (ankka.ConsumerEffect, error) {
		c, _ := ankka.ChangeFrom(ctx)
		// Running or paused: not an end, and nothing to do.
		if c.Standing == nil || !c.Standing.Terminal() {
			return ankka.ConsumerIgnore(), nil
		}
		end := c.Standing.Status
		if c.Standing.Failure != "" {
			end += ": " + c.Standing.Failure
		}
		if _, err := ankka.Invoke[string](ctx, "profile", "end-"+c.Subject, "set", end); err != nil {
			return ankka.ConsumerEffect{}, err
		}
		return ankka.ConsumerDone(), nil
	})

// docs:end workflow-consumer

// ── checkout-fanout: several messages for one change (protocol 1.3) ──

// docs:start fanout

// CheckoutFanout answers three messages for a checkout (the second under a key of its own, the
// third with a header), none for an item added, and a single one, the old way, for an item removed.
var CheckoutFanout = ankka.Consumer[cart.Event]("checkout-fanout", ankka.Changes(cart.Entity),
	func(ctx context.Context, e cart.Event) (ankka.ConsumerEffect, error) {
		switch e.(type) {
		case cart.ItemAdded:
			return ankka.ProduceAll(), nil
		case cart.ItemRemoved:
			return ankka.Produce(Fanned{N: 0}), nil
		case cart.CheckedOut:
			return ankka.ProduceAll(
				ankka.Message{Payload: Fanned{N: 1}},
				ankka.Message{Payload: Fanned{N: 2}, Key: "second:" + subject(ctx)},
				ankka.Message{Payload: Fanned{N: 3}, Metadata: ankka.Metadata{"x-n": "3"}},
			), nil
		}
		return ankka.ConsumerIgnore(), nil
	},
	ankka.ProducesWith("conformance-fanout", fannedCodec),
)

// docs:end fanout

func relay(_ context.Context, m Fanned) (ankka.ConsumerEffect, error) { return ankka.Produce(m), nil }

// ── topic-relay: a consumer over a topic ──

// TopicRelay republishes what it reads, from the latest: none of what the topic held when it
// started.
var TopicRelay = ankka.Consumer[Fanned]("topic-relay", ankka.Topic("conformance-topic", ankka.StartFrom(ankka.Latest())), relay,
	ankka.ProducesWith("conformance-topic-relayed", fannedCodec),
)

// ── contract-relay: a contract, a declared broker and parallel reading ──

// orderSchema is the contract every reference states, from the same schema document: the
// fixtures' order.v1.
const orderSchema = `{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",` +
	`"required":["id","total"],"properties":{"id":{"type":"string"},"total":{"type":"number"}}}`

var orderContract = mustContract(ankka.ContractFromBytes([]byte(orderSchema), "order.v1"))

func mustContract(c ankka.Contract, err error) ankka.Contract {
	if err != nil {
		panic(err)
	}
	return c
}

// docs:start contract-relay

// ContractRelay reads conformance-contracts as order.v1 on broker legacy, partitions in parallel,
// and publishes one more.
var ContractRelay = ankka.Consumer[Fanned]("contract-relay",
	ankka.Topic("conformance-contracts", ankka.StartFrom(ankka.Earliest()), ankka.ExpectContract(orderContract), ankka.OnBroker("legacy"), ankka.Parallel()),
	func(_ context.Context, m Fanned) (ankka.ConsumerEffect, error) {
		return ankka.Produce(Fanned{N: m.N + 1}), nil
	},
	ankka.ProducesWith("conformance-contracted", fannedCodec, ankka.StatesContract(orderContract), ankka.ToBroker("legacy")),
)

// docs:end contract-relay

// ── partner-relay: another project's topic, read and published to (1.15) ──

// docs:start partner-relay

// PartnerRelay reads conformance-shared of project partner from the earliest, and republishes to
// its other.
var PartnerRelay = ankka.Consumer[Fanned]("partner-relay",
	ankka.Topic("conformance-shared", ankka.FromProject("partner"), ankka.StartFrom(ankka.Earliest())), relay,
	ankka.ProducesWith("conformance-shared-relayed", fannedCodec, ankka.ToProject("partner")),
)

// docs:end partner-relay

// ── vehicle-feed: a consumer given a replicated entity's mirrored changes ──

// VehicleFeed publishes nothing; declared to show a consumer given mirrored changes discovers so.
var VehicleFeed = ankka.Consumer[StepAdded]("vehicle-feed", ankka.Changes(ReplicatedVehicle),
	func(context.Context, StepAdded) (ankka.ConsumerEffect, error) { return ankka.ConsumerIgnore(), nil },
	ankka.ProducesWith("conformance-vehicle-steps", ankka.CodecFor[StepAdded](ankka.Manifest("step-added"))),
	ankka.MirroredChanges(),
)

// ── cart-graph: the cart as graph deltas ──

func cartNode(id string, checkedOut bool) ankka.Element {
	return ankka.Node("cart:"+id, ankka.Labels("Cart"), ankka.WithProperties(ankka.Properties{"cartId": id, "checkedOut": checkedOut}))
}

// CartGraph is the cart graph every reference publishes, where the conformance suite reads it.
var CartGraph = ankka.GraphConsumer[cart.Event]("cart-graph", ankka.Changes(cart.Entity), "conformance-graph",
	func(ctx context.Context, e cart.Event) (ankka.GraphEffect, error) {
		id := subject(ctx)
		switch e.(type) {
		case cart.ItemAdded, cart.ItemRemoved:
			return ankka.Publish(cartNode(id, false)), nil
		case cart.CheckedOut:
			return ankka.Publish(
				cartNode(id, true),
				ankka.Node("checkout:"+id, ankka.Labels("Checkout"), ankka.WithProperties(ankka.Properties{"cartId": id})),
				ankka.Edge("checked-out:"+id, "CHECKED_OUT", "cart:"+id, "checkout:"+id),
			), nil
		}
		return ankka.GraphIgnore(), nil
	},
	ankka.WhenDeleted(func(ctx context.Context) (ankka.GraphEffect, error) {
		return ankka.Publish(ankka.NodeTombstone("cart:" + subject(ctx))), nil
	}),
)

// ── profile-graph: a graph consumer over a key value entity; its versions are revisions ──

// ProfileGraph publishes each profile as a node.
var ProfileGraph = ankka.GraphConsumer[ProfileState]("profile-graph", ankka.Changes(Profile), "conformance-profile-graph",
	func(ctx context.Context, s ProfileState) (ankka.GraphEffect, error) {
		return ankka.Publish(ankka.Node("profile:"+subject(ctx), ankka.Labels("Profile"), ankka.WithProperties(ankka.Properties{"name": s.Name}))), nil
	},
	ankka.WhenDeleted(func(ctx context.Context) (ankka.GraphEffect, error) {
		return ankka.Publish(ankka.NodeTombstone("profile:" + subject(ctx))), nil
	}),
)
