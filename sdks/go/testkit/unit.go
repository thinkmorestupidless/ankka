// Package testkit tests ankka services written in Go.
//
// The unit kits drive one component, or an endpoint and the components it calls, with no sidecar,
// database or network: effects are shown as values, the state is carried forward, and every value
// crosses the component's own codecs on the way in and out, so a type the encoding cannot carry
// fails here rather than on a sidecar. The integration kit (Start) runs the real sidecar and a
// database in containers.
package testkit

import (
	"context"
	"fmt"
	"strings"
	"time"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/internal/kit"
	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"github.com/thinkmorestupidless/ankka-go/internal/server"
)

// Result is what one command or query did: its reply or refusal, the events it persisted and the
// state after them.
type Result[S, E any] struct {
	reply    any
	replied  bool
	metadata ankka.Metadata
	events   []E
	state    S
	refusal  *ankka.RefusalError
	failure  error
	deleted  bool
	expiry   time.Duration
}

// Reply is the reply, decoded by the handler's codec; nil when there was none.
func (r *Result[S, E]) Reply() any { return r.reply }

// Replied reports whether the handler replied (NoReply does not).
func (r *Result[S, E]) Replied() bool { return r.replied }

// ReplyMetadata is the metadata the reply carried.
func (r *Result[S, E]) ReplyMetadata() ankka.Metadata { return r.metadata }

// Events are the events persisted, each decoded by the entity's event codec.
func (r *Result[S, E]) Events() []E { return r.events }

// State is the state after the events, round-tripped through the entity's state codec.
func (r *Result[S, E]) State() S { return r.state }

// Refusal is the refusal the handler answered (Refuse), or nil.
func (r *Result[S, E]) Refusal() *ankka.RefusalError { return r.refusal }

// Failure is why the command failed (an error the handler returned, or a panic), or nil. A
// failed command changes nothing.
func (r *Result[S, E]) Failure() error { return r.failure }

// Deleted reports whether the effect deleted the entity.
func (r *Result[S, E]) Deleted() bool { return r.deleted }

// ExpireAfter is the expiry the effect set, or 0.
func (r *Result[S, E]) ExpireAfter() time.Duration { return r.expiry }

// Option is an option of a unit kit.
type Option func(*options)

type options struct {
	entityID string
	calling  []ankka.Declaration
}

// EntityID is the id of the instance the kit drives; "test-entity" by default.
func EntityID(id string) Option { return func(o *options) { o.entityID = id } }

func settings(opts []Option) options {
	o := options{entityID: "test-entity"}
	for _, f := range opts {
		f(&o)
	}
	return o
}

// view is the kit's view of a service, refusing (by panicking, naming every problem) a service
// whose Start would refuse: a kit that ran such a component would prove nothing about it.
func view(service *ankka.Service) *kit.Service {
	v, err := kit.Of(service)
	if err != nil {
		panic("testkit: " + err.Error())
	}
	if len(v.Problems) > 0 {
		panic("testkit: the service would not start:\n  - " + strings.Join(v.Problems, "\n  - "))
	}
	return v
}

// EntityKit drives one instance of an event sourced entity.
type EntityKit[S, E any] struct {
	host  *entityHost
	id    string
	state S
}

// EventSourced is a kit for the entity e. It panics, naming each problem, when the entity has
// problems a service would refuse to start with.
func EventSourced[S, E any](e *ankka.EventSourcedEntity[S, E], opts ...Option) *EntityKit[S, E] {
	v := view(ankka.NewService(e))
	k := &EntityKit[S, E]{host: newEntityHost(v.EventSourced[e.ID()]), id: settings(opts).entityID}
	k.state = k.empty()
	return k
}

func (k *EntityKit[S, E]) empty() S {
	p, err := k.host.e.Empty(k.id)
	if err != nil {
		panic(fmt.Sprintf("testkit: the empty state of %s cannot be encoded: %v", k.host.e.ID, err))
	}
	s, err := k.host.e.State(p)
	if err != nil {
		panic(fmt.Sprintf("testkit: the empty state of %s cannot be read back: %v", k.host.e.ID, err))
	}
	return s.(S)
}

// EntityID is the id of the instance the kit drives.
func (k *EntityKit[S, E]) EntityID() string { return k.id }

// State is the instance's state, as of its last command.
func (k *EntityKit[S, E]) State() S { return k.state }

// Command runs the handler wire with input, as the sidecar would hand it over.
func (k *EntityKit[S, E]) Command(ctx context.Context, wire string, input any) *Result[S, E] {
	return k.run(ctx, wire, input, nil)
}

// CommandWithMetadata runs the handler wire with input and the request's metadata.
func (k *EntityKit[S, E]) CommandWithMetadata(ctx context.Context, wire string, input any, md ankka.Metadata) *Result[S, E] {
	return k.run(ctx, wire, input, md)
}

// Query runs the read-only handler wire; a handler that is not read-only fails the result.
func (k *EntityKit[S, E]) Query(ctx context.Context, wire string, input any) *Result[S, E] {
	if ro, ok := k.host.e.Handlers[wire]; ok && !ro {
		return &Result[S, E]{state: k.state, failure: fmt.Errorf("%s is a command, not a query", wire)}
	}
	return k.run(ctx, wire, input, nil)
}

func (k *EntityKit[S, E]) run(ctx context.Context, wire string, input any, md ankka.Metadata) *Result[S, E] {
	r := &Result[S, E]{state: k.state}
	if _, ok := k.host.e.Handlers[wire]; !ok {
		r.failure = fmt.Errorf("%s has no handler %q", k.host.e.ID, wire)
		return r
	}
	payload, err := k.host.e.Input(wire, input)
	if err != nil {
		r.failure = fmt.Errorf("the input cannot be encoded: %w", err)
		return r
	}
	out, err := k.host.command(ctx, k.id, wire, payload, metadataOf(md))
	if err != nil {
		r.failure = err
		return r
	}
	if f := out.GetFailure(); f != nil {
		r.failure = fmt.Errorf("%s", f.GetError().GetMessage())
		return r
	}
	reply := out.GetReply()
	for _, p := range reply.GetEvents() {
		ev, err := k.host.e.Event(p)
		if err != nil {
			r.failure = fmt.Errorf("a persisted event cannot be read back: %w", err)
			return r
		}
		r.events = append(r.events, ev.(E))
	}
	switch o := reply.GetOutcome().GetOutcome().(type) {
	case *pb.Outcome_Reply_:
		v, err := k.host.e.Reply(wire, o.Reply.GetPayload())
		if err != nil {
			r.failure = fmt.Errorf("the reply cannot be read back: %w", err)
			return r
		}
		r.reply, r.replied, r.metadata = v, true, metadataFrom(o.Reply.GetMetadata())
	case *pb.Outcome_Error:
		r.refusal = refusalOf(o.Error)
		return r
	}
	if ret := reply.GetRetention(); ret != nil {
		r.deleted = ret.GetDeleteNow() != nil
		if ea := ret.GetExpireAfter(); ea != nil {
			r.expiry = time.Duration(ea.GetMillis()) * time.Millisecond
		}
	}
	switch {
	case r.deleted:
		k.state = k.empty()
	case reply.GetSnapshot() != nil:
		s, err := k.host.e.State(reply.GetSnapshot())
		if err != nil {
			r.failure = fmt.Errorf("the state cannot be read back: %w", err)
			return r
		}
		k.state = s.(S)
	}
	r.state = k.state
	return r
}

func refusalOf(e *pb.Error) *ankka.RefusalError {
	return &ankka.RefusalError{Code: ankka.ErrorCode(e.GetCode()), Message: e.GetMessage(), Details: e.GetDetails()}
}

func metadataOf(md ankka.Metadata) *pb.Metadata {
	out := &pb.Metadata{}
	for _, k := range sortedKeys(md) {
		out.Entries = append(out.Entries, &pb.Metadata_Entry{Key: k, Value: md[k]})
	}
	return out
}

func metadataFrom(md *pb.Metadata) ankka.Metadata {
	if len(md.GetEntries()) == 0 {
		return nil
	}
	out := ankka.Metadata{}
	for _, e := range md.GetEntries() {
		out[e.GetKey()] = e.GetValue()
	}
	return out
}

// entityHost holds an entity's instances, as the sidecar holds their conversations.
type entityHost struct {
	e         *kit.EventSourced
	instances map[string]*liveEntity
}

type liveEntity struct {
	inst server.EventSourcedInstance
	next int64
}

func newEntityHost(e *kit.EventSourced) *entityHost {
	return &entityHost{e: e, instances: map[string]*liveEntity{}}
}

func (h *entityHost) instance(ctx context.Context, entityID string) (*liveEntity, error) {
	if l, ok := h.instances[entityID]; ok {
		return l, nil
	}
	inst := h.e.New(entityID)
	init := &pb.EventSourcedIn_Init{ComponentId: h.e.ID, EntityId: entityID}
	if err := server.Guard(func() error { return inst.Recover(ctx, init) }); err != nil {
		return nil, fmt.Errorf("%s %s cannot recover: %w", h.e.ID, entityID, err)
	}
	l := &liveEntity{inst: inst}
	h.instances[entityID] = l
	return l, nil
}

// command runs one command on an instance, asking for the state after it; a failure is answered
// as the protocol's Failure, and a deletion drops the instance, so the next command starts fresh.
func (h *entityHost) command(ctx context.Context, entityID, wire string, payload *pb.Payload, md *pb.Metadata) (*pb.EventSourcedOut, error) {
	l, err := h.instance(ctx, entityID)
	if err != nil {
		return nil, err
	}
	l.next++
	cmd := &pb.EventSourcedIn_Command{Id: l.next, Name: wire, Payload: payload, Metadata: md, SnapshotRequested: true, AtHome: true}
	reply, err := server.GuardValue(func() (*pb.EventSourcedOut_Reply, error) { return l.inst.Command(ctx, cmd) })
	if err != nil {
		return &pb.EventSourcedOut{Message: &pb.EventSourcedOut_Failure{Failure: &pb.Failure{CommandId: cmd.Id, Error: server.ErrorOf(err)}}}, nil
	}
	reply.CommandId = cmd.Id
	if reply.GetRetention().GetDeleteNow() != nil {
		delete(h.instances, entityID)
	}
	return &pb.EventSourcedOut{Message: &pb.EventSourcedOut_Reply_{Reply: reply}}, nil
}
