package ankka

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"strings"

	"github.com/thinkmorestupidless/ankka-go/internal/kit"
	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

// ConsumerComponent is a consumer's declaration: it reacts to each of a source's changes, at least
// once, optionally producing onward to a topic. A graph consumer is one too (GraphConsumer).
type ConsumerComponent[Msg any] struct {
	id        string
	source    Source
	codec     Codec[Msg]
	problem   string
	f         func(context.Context, Msg) (ConsumerEffect, error)
	graph     func(context.Context, Msg) (GraphEffect, error)
	onDelete  any
	produces  *Publication
	graphTo   string
	encodeOut func(v any) (*pb.Payload, error)
	decodeOut func(p *pb.Payload) (any, error)
	outType   reflect.Type
	outCodec  []string
	version   int
	mirrored  bool
	misplaced []Part
}

type producesPart struct {
	pub       Publication
	encode    func(v any) (*pb.Payload, error)
	outType   reflect.Type
	codecErrs []string
	decode    func(p *pb.Payload) (any, error)
}

func (producesPart) part() {}

// ProducesTo is the topic a consumer publishes to, and the type of what it publishes there: a
// message's payload must be an Out.
func ProducesTo[Out any](topic string, opts ...PublicationOption) Part {
	return ProducesWith(topic, CodecFor[Out](), opts...)
}

// ProducesWith is ProducesTo with the codec the messages are encoded by, as a manifest of the
// service's own choosing: ankka.ProducesWith("orders", ankka.CodecFor[Order](ankka.Manifest("order"))).
func ProducesWith[Out any](topic string, c Codec[Out], opts ...PublicationOption) Part {
	p := Publication{topic: topic}
	for _, o := range opts {
		o(&p)
	}
	return producesPart{
		pub:       p,
		outType:   reflect.TypeFor[Out](),
		codecErrs: CodecProblems(c),
		encode: func(v any) (*pb.Payload, error) {
			out, ok := v.(Out)
			if !ok {
				return nil, fmt.Errorf("the consumer produces %s to %q, not a %T", reflect.TypeFor[Out](), topic, v)
			}
			return payloadOf(c, out)
		},
		decode: func(p *pb.Payload) (any, error) { return valueOf(c, p) },
	}
}

type whenDeletedPart struct{ f any }

func (whenDeletedPart) part() {}

// WhenDeleted is what a consumer does when its source instance is deleted; without it, the
// deletion is ignored. Eff is ConsumerEffect for a consumer and GraphEffect for a graph consumer,
// which publishes tombstones here.
func WhenDeleted[Eff ConsumerEffect | GraphEffect](f func(ctx context.Context) (Eff, error)) Part {
	return whenDeletedPart{f: f}
}

type mirroredPart struct{}

func (mirroredPart) part() {}

// MirroredChanges gives a consumer that publishes a replicated entity's changes written at other
// installations too (protocol 1.19); without it, each is published once, where it was written.
func MirroredChanges() Part { return mirroredPart{} }

func newConsumer[Msg any](id string, source Source, parts []Part) *ConsumerComponent[Msg] {
	codec, problem := changeCodec[Msg](source)
	c := &ConsumerComponent[Msg]{id: id, source: source, codec: codec, problem: problem}
	for _, p := range parts {
		switch p := p.(type) {
		case producesPart:
			pub := p.pub
			c.produces, c.encodeOut, c.decodeOut, c.outType, c.outCodec = &pub, p.encode, p.decode, p.outType, p.codecErrs
		case whenDeletedPart:
			c.onDelete = p.f
		case sourceVersionPart:
			c.version = p.n
		case mirroredPart:
			c.mirrored = true
		default:
			c.misplaced = append(c.misplaced, p)
		}
	}
	return c
}

// Consumer declares a consumer of source: f handles each change, answering ConsumerDone,
// ConsumerIgnore, Produce or ProduceAll; an error, or a panic, fails the change, which comes again.
// The parts are its options (ProducesTo, WhenDeleted, SourceVersion, MirroredChanges).
func Consumer[Msg any](id string, source Source, f func(ctx context.Context, message Msg) (ConsumerEffect, error), parts ...Part) *ConsumerComponent[Msg] {
	c := newConsumer[Msg](id, source, parts)
	c.f = f
	return c
}

// GraphConsumer declares a consumer that publishes a graph to topic: f answers the elements a
// change leaves in which state, each published as a graph delta under its element key, at its
// stated version or the change's sequence number. The topic must be compacted to hold the graph.
func GraphConsumer[Msg any](id string, source Source, topic string, f func(ctx context.Context, message Msg) (GraphEffect, error), parts ...Part) *ConsumerComponent[Msg] {
	c := newConsumer[Msg](id, source, parts)
	c.graph, c.graphTo = f, topic
	return c
}

// ID is the consumer's component id.
func (c *ConsumerComponent[Msg]) ID() string { return c.id }

func (c *ConsumerComponent[Msg]) declare(d *declarations) {
	where := c.id
	if c.id == "" {
		where = fmt.Sprintf("consumer of %s", reflect.TypeFor[Msg]())
		d.problem(where, "a consumer has an id")
	}
	if c.f == nil && c.graph == nil {
		d.problem(where, "a consumer is given its handler")
	}
	c.source.check(d, where, true)
	if c.problem != "" {
		d.problem(where, "%s", c.problem)
	}
	for _, p := range CodecProblems(c.codec) {
		d.problem(where, "its message, a %s: %s", reflect.TypeFor[Msg](), p)
	}
	for _, p := range c.misplaced {
		misplaced(d, where, "consumer", p)
	}
	if c.version != 0 && c.source.component {
		d.problem(where, "declares a version, which applies to a topic; it reads a component")
	}
	if c.version != 0 {
		d.require(7, "%s declares a version", where)
	}
	if c.mirrored {
		d.require(19, "%s is given mirrored changes", where)
	}
	if c.onDelete != nil {
		_, consumerKind := c.onDelete.(func(context.Context) (ConsumerEffect, error))
		_, graphKind := c.onDelete.(func(context.Context) (GraphEffect, error))
		if (c.graph != nil && !graphKind) || (c.graph == nil && !consumerKind) {
			d.problem(where, "its WhenDeleted answers the other kind of effect: a graph consumer's answers a GraphEffect, a consumer's a ConsumerEffect")
		}
	}
	detail := &pb.ConsumerDetail{Version: versionToPb(d, where, c.version), MirroredChanges: c.mirrored, Source: c.source.toPb()}
	switch {
	case c.graph != nil:
		if c.produces != nil {
			d.problem(where, "a graph consumer publishes to its own topic and takes no ProducesTo")
		}
		if strings.TrimSpace(c.graphTo) == "" {
			d.problem(where, "a graph consumer is given the topic its deltas are published to")
		}
		to := c.graphTo
		detail.ProducesTo = &to
	case c.produces != nil:
		c.produces.check(d, where)
		for _, p := range c.outCodec {
			d.problem(where, "what it produces, a %s: %s", c.outType, p)
		}
		to := c.produces.topic
		detail.ProducesTo = &to
		detail.Produces = c.produces.toPb()
	}
	d.components = append(d.components, &pb.Component{Kind: pb.Kind_CONSUMER, Id: c.id, Detail: &pb.Component_Consumer{Consumer: detail}})
	d.consumers[c.id] = c.handle
	d.consumerKits[c.id] = c.kitView()
}

// kitView is the consumer as a test kit drives it.
func (c *ConsumerComponent[Msg]) kitView() *kit.Consumer {
	return &kit.Consumer{
		ID:     c.id,
		Graph:  c.graph != nil,
		Handle: c.handle,
		Message: func(v any) (*pb.Payload, error) {
			m, ok := v.(Msg)
			if !ok {
				return nil, fmt.Errorf("%s reads %s, not a %T", c.id, reflect.TypeFor[Msg](), v)
			}
			return payloadOf(c.codec, m)
		},
		Out: func(p *pb.Payload) (any, error) {
			if c.decodeOut == nil {
				return nil, fmt.Errorf("%s produced a message and declares no ProducesTo", c.id)
			}
			return c.decodeOut(p)
		},
	}
}

var (
	consumerDoneEffect   = &pb.ConsumerEffect{Effect: &pb.ConsumerEffect_Done{Done: &pb.Empty{}}}
	consumerIgnoreEffect = &pb.ConsumerEffect{Effect: &pb.ConsumerEffect_Ignore{Ignore: &pb.Empty{}}}
)

func (c *ConsumerComponent[Msg]) handle(ctx context.Context, req *pb.ConsumerRequest) (*pb.ConsumerEffect, error) {
	md := metadataFromPb(req.GetMetadata())
	scope := changeScope(c.id, md, req.GetDeleted(), req.GetStanding(), "")
	ctx = withChange(withMetadata(ctx, md), scope)
	if c.graph != nil {
		var eff GraphEffect
		var err error
		switch {
		case req.GetDeleted() && c.onDelete != nil:
			eff, err = c.onDelete.(func(context.Context) (GraphEffect, error))(ctx)
		case req.GetDeleted():
			eff = GraphIgnore()
		default:
			var msg Msg
			if msg, err = c.message(req.GetMessage()); err == nil {
				eff, err = c.graph(ctx, msg)
			}
		}
		if err != nil {
			return nil, err
		}
		return materialiseGraph(eff, md, scope.Sequence)
	}
	var eff ConsumerEffect
	var err error
	switch {
	case req.GetDeleted() && c.onDelete != nil:
		eff, err = c.onDelete.(func(context.Context) (ConsumerEffect, error))(ctx)
	case req.GetDeleted():
		eff = ConsumerIgnore()
	default:
		var msg Msg
		if msg, err = c.message(req.GetMessage()); err == nil {
			eff, err = c.f(ctx, msg)
		}
	}
	if err != nil {
		return nil, err
	}
	return c.materialise(eff, md)
}

func (c *ConsumerComponent[Msg]) message(p *pb.Payload) (Msg, error) {
	m, err := valueOf(c.codec, p)
	if err != nil {
		return m, fmt.Errorf("the message cannot be read as a %s: %w", reflect.TypeFor[Msg](), err)
	}
	return m, nil
}

func (c *ConsumerComponent[Msg]) encode(m Message) (*pb.Payload, error) {
	if c.encodeOut == nil {
		return nil, fmt.Errorf("%s produced a message and declares no ProducesTo", c.id)
	}
	return c.encodeOut(m.Payload)
}

// materialise is a consumer's effect as the protocol answers it. One message without a key is
// produce, which every runtime reads; several, or a key, need a runtime at 1.3.
func (c *ConsumerComponent[Msg]) materialise(eff ConsumerEffect, request Metadata) (*pb.ConsumerEffect, error) {
	if eff.problem != "" {
		return nil, errors.New(eff.problem)
	}
	switch eff.kind {
	case consumerDone:
		return consumerDoneEffect, nil
	case consumerIgnore:
		return consumerIgnoreEffect, nil
	case consumerProduce, consumerProduceAll:
	default:
		return nil, errors.New("the handler returned no effect: build one with ConsumerDone, ConsumerIgnore, Produce or ProduceAll")
	}
	if eff.kind == consumerProduce && eff.messages[0].Key == "" {
		p, err := c.encode(eff.messages[0])
		if err != nil {
			return nil, err
		}
		return &pb.ConsumerEffect{Effect: &pb.ConsumerEffect_Produce_{Produce: &pb.ConsumerEffect_Produce{Payload: p, Metadata: metadataToPb(eff.messages[0].Metadata)}}}, nil
	}
	if len(eff.messages) == 0 {
		return consumerDoneEffect, nil
	}
	if err := requireSeveralMessages(request); err != nil {
		return nil, err
	}
	all := &pb.ConsumerEffect_ProduceAll{}
	for _, m := range eff.messages {
		p, err := c.encode(m)
		if err != nil {
			return nil, err
		}
		msg := &pb.ConsumerEffect_Message{Payload: p, Metadata: metadataToPb(m.Metadata)}
		if m.Key != "" {
			k := m.Key
			msg.Key = &k
		}
		all.Messages = append(all.Messages, msg)
	}
	return &pb.ConsumerEffect{Effect: &pb.ConsumerEffect_ProduceAll_{ProduceAll: all}}, nil
}

// materialiseGraph is a graph consumer's effect: each element a delta under its own key, as
// produce_all, which needs a runtime at 1.3.
func materialiseGraph(eff GraphEffect, request Metadata, sequence int64) (*pb.ConsumerEffect, error) {
	switch eff.kind {
	case graphDone:
		return consumerDoneEffect, nil
	case graphIgnore:
		return consumerIgnoreEffect, nil
	case graphNone:
		return nil, errors.New("the handler returned no effect: build one with Publish, GraphDone or GraphIgnore")
	}
	records, err := deltaRecords(eff.elements, sequence)
	if err != nil {
		return nil, err
	}
	if len(records) == 0 {
		return consumerDoneEffect, nil
	}
	if err := requireSeveralMessages(request); err != nil {
		return nil, err
	}
	all := &pb.ConsumerEffect_ProduceAll{}
	for _, r := range records {
		k := r.key
		all.Messages = append(all.Messages, &pb.ConsumerEffect_Message{
			Payload:  &pb.Payload{ContentType: JSONContent, Manifest: GraphDeltaSchema, Data: r.value},
			Metadata: metadataToPb(Metadata{"ce-type": GraphDeltaSchema}),
			Key:      &k,
		})
	}
	return &pb.ConsumerEffect{Effect: &pb.ConsumerEffect_ProduceAll_{ProduceAll: all}}, nil
}
