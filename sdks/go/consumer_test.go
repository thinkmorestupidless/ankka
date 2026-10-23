package ankka

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

type tNotice struct {
	Cart    string `json:"cart"`
	Product string `json:"product"`
}

var tNotifier = Consumer("t-notifier", Changes(tCartEntity), func(ctx context.Context, e tEvent) (ConsumerEffect, error) {
	c, _ := ChangeFrom(ctx)
	a, ok := e.(tAdded)
	if !ok {
		return ConsumerIgnore(), nil
	}
	switch a.Item.ProductID {
	case "many":
		return ProduceAll(
			Message{Payload: tNotice{c.Subject, "one"}, Key: "k1"},
			Message{Payload: tNotice{c.Subject, "two"}, Metadata: Metadata{"ce-type": "notice"}},
		), nil
	case "none":
		return ProduceAll(), nil
	case "keyed":
		return ProduceAll(Message{Payload: tNotice{c.Subject, "keyed"}, Key: "k"}), nil
	case "wrong":
		return Produce("not a notice"), nil
	case "fail":
		return ConsumerEffect{}, errors.New("the mail server is down")
	case "quiet":
		return ConsumerDone(), nil
	}
	return Produce(tNotice{c.Subject, a.Item.ProductID}, Metadata{"ce-type": "notice"}), nil
}, ProducesTo[tNotice]("notices"), WhenDeleted(func(ctx context.Context) (ConsumerEffect, error) {
	c, _ := ChangeFrom(ctx)
	return Produce(tNotice{Cart: c.Subject, Product: "gone"}), nil
}))

func consumerRequest(t *testing.T, e tEvent, md Metadata) *pb.ConsumerRequest {
	t.Helper()
	req := &pb.ConsumerRequest{ComponentId: "t-notifier", Metadata: metadataToPb(md)}
	if e != nil {
		p, err := payloadOf(CodecFor[tEvent](), e)
		if err != nil {
			t.Fatal(err)
		}
		req.Message = p
	}
	return req
}

func TestAConsumerProducesOnward(t *testing.T) {
	svc := NewService(tCartEntity, tNotifier)
	if p := svc.Problems(); len(p) > 0 {
		t.Fatalf("problems: %v", p)
	}
	d := svc.Spec().GetComponents()[1].GetConsumer()
	if d.GetProducesTo() != "notices" || d.GetProduces().GetTopic() != "notices" || d.GetSource().GetComponent().GetId() != "t-cart" {
		t.Fatalf("declared %v", d)
	}
	client := pb.NewConsumerClient(loopback(t, svc))
	ctx := context.Background()
	md := Metadata{"ce-subject": "c1", "ankka.sequence": "4", "ankka.protocol": "1.22"}
	added := func(p string) tEvent { return tAdded{tItem{p, 1}} }

	one, err := client.Handle(ctx, consumerRequest(t, added("ink"), md))
	if err != nil || string(one.GetProduce().GetPayload().GetData()) != `{"cart":"c1","product":"ink"}` || metadataFromPb(one.GetProduce().GetMetadata())["ce-type"] != "notice" {
		t.Fatalf("one message answered %v, %v", one, err)
	}
	many, err := client.Handle(ctx, consumerRequest(t, added("many"), md))
	msgs := many.GetProduceAll().GetMessages()
	if err != nil || len(msgs) != 2 || msgs[0].GetKey() != "k1" || msgs[1].Key != nil {
		t.Fatalf("several messages answered %v, %v", many, err)
	}
	if out, _ := client.Handle(ctx, consumerRequest(t, added("none"), md)); out.GetDone() == nil {
		t.Fatalf("no messages answered %v", out)
	}
	if out, _ := client.Handle(ctx, consumerRequest(t, added("quiet"), md)); out.GetDone() == nil {
		t.Fatalf("done answered %v", out)
	}
	// One keyed message is several messages' form, which an old runtime would drop.
	if out, _ := client.Handle(ctx, consumerRequest(t, added("keyed"), md)); out.GetProduceAll() == nil {
		t.Fatalf("a keyed message answered %v", out)
	}
	old := Metadata{"ce-subject": "c1"}
	if _, err := client.Handle(ctx, consumerRequest(t, added("many"), old)); status.Code(err) != codes.Internal || !strings.Contains(err.Error(), "need 1.3") {
		t.Fatalf("several messages to an old runtime answered %v", err)
	}
	if _, err := client.Handle(ctx, consumerRequest(t, added("ink"), old)); err != nil {
		t.Fatalf("one message to an old runtime answered %v", err)
	}
	if _, err := client.Handle(ctx, consumerRequest(t, added("wrong"), md)); err == nil || !strings.Contains(err.Error(), "not a string") {
		t.Fatalf("a message of the wrong type answered %v", err)
	}
	if _, err := client.Handle(ctx, consumerRequest(t, added("fail"), md)); err == nil || !strings.Contains(err.Error(), "mail server") {
		t.Fatalf("a failing handler answered %v", err)
	}
	deleted := consumerRequest(t, nil, md)
	deleted.Deleted = true
	if out, err := client.Handle(ctx, deleted); err != nil || !strings.Contains(string(out.GetProduce().GetPayload().GetData()), "gone") {
		t.Fatalf("a deletion answered %v, %v", out, err)
	}
}

func TestAConsumerSeesTheChangeItHandles(t *testing.T) {
	var seen ChangeScope
	watcher := Consumer("watcher", Changes(tOrderFlow), func(ctx context.Context, o tOrder) (ConsumerEffect, error) {
		seen, _ = ChangeFrom(ctx)
		_, _, err := Secrets(ctx).Get("x")
		if err != nil && strings.Contains(err.Error(), "not offered") {
			return ConsumerEffect{}, err
		}
		return ConsumerDone(), nil
	})
	svc := NewService(tOrderFlow, watcher)
	state, _ := payloadOf(CodecFor[tOrder](), tOrder{ID: "o1", Status: "charged"})
	step := "charge"
	_, err := svc.decls.consumers["watcher"](context.Background(), &pb.ConsumerRequest{
		ComponentId: "watcher", Message: state,
		Metadata: metadataToPb(Metadata{"ce-subject": "o1", "ankka.sequence": "7", "ankka.installation": "eu", "ankka.local": "false"}),
		Standing: &pb.WorkflowStanding{Status: "Paused", Step: &step, Retries: map[string]int32{"charge": 2}},
	})
	if err != nil || (seen.Subject != "o1" || seen.Sequence != 7 || seen.Installation != "eu" || seen.Local ||
		seen.Standing == nil || seen.Standing.Status != "Paused" || seen.Standing.Step != "charge" || seen.Standing.Retries["charge"] != 2) {
		t.Fatalf("the consumer saw %+v, %v", seen, err)
	}
	if err != nil && strings.Contains(err.Error(), "not offered") {
		t.Fatalf("a consumer is not offered the secret store: %v", err)
	}
	if why := svc.decls.refusal("1.15"); !strings.Contains(why, "needs 1.16") || !strings.Contains(why, `the workflow "t-order"`) {
		t.Fatalf("a workflow source on 1.15 is refused as %q", why)
	}
}

func TestAConsumersProblemsAreNamed(t *testing.T) {
	done := func(context.Context, tNotice) (ConsumerEffect, error) { return ConsumerDone(), nil }
	for name, c := range map[string]struct {
		decl Declaration
		want string
	}{
		"no start":          {Consumer("c", Topic("in"), done), `reads the topic "in" and declares no start position`},
		"a component's":     {Consumer("c", Changes(tCartEntity), done), "are not ankka.tNotice values"},
		"version over one":  {Consumer("c", Changes(tProfileEntity), func(context.Context, tProfile) (ConsumerEffect, error) { return ConsumerDone(), nil }, SourceVersion(2)), "applies to a topic"},
		"nameless output":   {Consumer("c", Topic("in", StartFrom(Earliest())), done, ProducesTo[tNotice]("")), "produces to a topic with no name"},
		"output both ways":  {Consumer("c", Topic("in", StartFrom(Earliest())), done, ProducesTo[tNotice]("out", ToProject("p"), ToBroker("b"))), "names a project and a broker"},
		"the other delete":  {Consumer("c", Topic("in", StartFrom(Earliest())), done, WhenDeleted(func(context.Context) (GraphEffect, error) { return GraphIgnore(), nil })), "the other kind of effect"},
		"graph with output": {GraphConsumer("g", Topic("in", StartFrom(Earliest())), "deltas", func(context.Context, tNotice) (GraphEffect, error) { return GraphIgnore(), nil }, ProducesTo[tNotice]("x")), "takes no ProducesTo"},
		"graph no topic":    {GraphConsumer("g", Topic("in", StartFrom(Earliest())), "", func(context.Context, tNotice) (GraphEffect, error) { return GraphIgnore(), nil }), "the topic its deltas are published to"},
	} {
		text := strings.Join(serviceProblems(NewService(c.decl)), "\n")
		if !strings.Contains(text, c.want) {
			t.Errorf("%s: problems do not say %q:\n%s", name, c.want, text)
		}
	}
	mirrored := NewService(Consumer("m", Topic("in", StartFrom(Earliest()), FromProject("other")), done, MirroredChanges(), ProducesTo[tNotice]("out", StatesContract(Contract{Name: "notice", Fingerprint: "sha256:00"}))))
	if why := mirrored.decls.refusal("1.14"); !strings.Contains(why, "needs 1.15") {
		t.Fatalf("another project's topic on 1.14 is refused as %q", why)
	}
	if why := mirrored.decls.refusal("1.18"); !strings.Contains(why, "needs 1.19") {
		t.Fatalf("mirrored changes on 1.18 are refused as %q", why)
	}
	d := mirrored.Spec().GetComponents()[0].GetConsumer()
	if !d.GetMirroredChanges() || d.GetSource().GetProject() != "other" || d.GetProduces().GetContract().GetName() != "notice" {
		t.Fatalf("declared %v", d)
	}
}

func TestAGraphConsumerPublishesDeltas(t *testing.T) {
	g := GraphConsumer("t-graph", Changes(tCartEntity), "cart-graph", func(ctx context.Context, e tEvent) (GraphEffect, error) {
		c, _ := ChangeFrom(ctx)
		a := e.(tAdded)
		if a.Item.ProductID == "twice" {
			return Publish(Node("cart:"+c.Subject), Node("cart:"+c.Subject)), nil
		}
		return Publish(
			Node("cart:"+c.Subject, Labels("Cart"), WithProperties(Properties{"cartId": c.Subject})),
			Edge("holds:"+c.Subject+":"+a.Item.ProductID, "HOLDS", "cart:"+c.Subject, "product:"+a.Item.ProductID, WithProperties(Properties{"quantity": a.Item.Quantity}), AtVersion(99)),
		), nil
	}, WhenDeleted(func(ctx context.Context) (GraphEffect, error) {
		c, _ := ChangeFrom(ctx)
		return Publish(NodeTombstone("cart:" + c.Subject)), nil
	}))
	svc := NewService(tCartEntity, g)
	if p := svc.Problems(); len(p) > 0 {
		t.Fatalf("problems: %v", p)
	}
	md := Metadata{"ce-subject": "c1", "ankka.sequence": "5", "ankka.protocol": "1.22"}
	ev, _ := payloadOf(CodecFor[tEvent](), tEvent(tAdded{tItem{"ink", 2}}))
	out, err := svc.decls.consumers["t-graph"](context.Background(), &pb.ConsumerRequest{ComponentId: "t-graph", Message: ev, Metadata: metadataToPb(md)})
	if err != nil {
		t.Fatal(err)
	}
	msgs := out.GetProduceAll().GetMessages()
	if len(msgs) != 2 || msgs[0].GetKey() != "node:cart:c1" || msgs[1].GetKey() != "edge:holds:c1:ink" || msgs[0].GetPayload().GetManifest() != GraphDeltaSchema ||
		metadataFromPb(msgs[0].GetMetadata())["ce-type"] != GraphDeltaSchema {
		t.Fatalf("published %v", msgs)
	}
	node, err := ReadDelta(msgs[0].GetPayload().GetData(), msgs[0].GetKey())
	if err != nil || node.Version != 5 || node.Labels[0] != "Cart" || node.Properties["cartId"] != "c1" {
		t.Fatalf("the node reads %+v, %v", node, err)
	}
	edge, err := ReadDelta(msgs[1].GetPayload().GetData(), msgs[1].GetKey())
	if err != nil || edge.Version != 99 || edge.Type != "HOLDS" || edge.Properties["quantity"] != int64(2) {
		t.Fatalf("the edge reads %+v, %v", edge, err)
	}
	twice, _ := payloadOf(CodecFor[tEvent](), tEvent(tAdded{tItem{"twice", 1}}))
	if _, err := svc.decls.consumers["t-graph"](context.Background(), &pb.ConsumerRequest{ComponentId: "t-graph", Message: twice, Metadata: metadataToPb(md)}); err == nil || !strings.Contains(err.Error(), "twice") {
		t.Fatalf("a node named twice answered %v", err)
	}
	gone, err := svc.decls.consumers["t-graph"](context.Background(), &pb.ConsumerRequest{ComponentId: "t-graph", Deleted: true, Metadata: metadataToPb(md)})
	if err != nil || !strings.Contains(string(gone.GetProduceAll().GetMessages()[0].GetPayload().GetData()), `"kind":"tombstone"`) {
		t.Fatalf("a deletion published %v, %v", gone, err)
	}
	if _, err := svc.decls.consumers["t-graph"](context.Background(), &pb.ConsumerRequest{ComponentId: "t-graph", Message: ev, Metadata: metadataToPb(Metadata{"ce-subject": "c1", "ankka.sequence": "5"})}); err == nil {
		t.Fatal("deltas were answered to a runtime that would drop them")
	}
}
