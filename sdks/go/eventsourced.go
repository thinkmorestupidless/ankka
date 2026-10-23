package ankka

import (
	"context"
	"fmt"
	"reflect"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"github.com/thinkmorestupidless/ankka-go/internal/server"
)

// EventSourcedEntity is an event sourced entity's declaration: its id, the events it persists,
// its empty state, how an event changes the state, and its handlers. Its state is the fold of its
// events, recovered from its journal; the sidecar holds the journal and this process the state, for
// as long as the sidecar keeps the instance's conversation open.
type EventSourcedEntity[S, E any] struct {
	id            string
	events        *SumType[E]
	empty         func() S
	apply         func(context.Context, S, E) S
	commands      []esCommand[S, E]
	queries       []stateQuery[S]
	handlers      []handlerPart
	snapshotEvery int
	replicated    bool
	stateCodec    Codec[S]
	eventCodec    Codec[E]
	misplaced     []Part
}

// esCommand is a command of an entity of state S and events E.
type esCommand[S, E any] interface {
	handlerPart
	runCommand(ctx context.Context, state S, payload *pb.Payload) (esRun[S, E], error)
}

// esRun is a command's effect, ready to be materialised with the entity's codecs.
type esRun[S, E any] interface {
	materialise(state S, apply func(S, E) S, state_ Codec[S], events Codec[E], snapshot bool) (S, *pb.EventSourcedOut_Reply, error)
}

// EventSourced declares an event sourced entity. events is the registration of the event
// interface's cases; empty is the state of an entity with no events; apply folds one event into the
// state and must do nothing else. The parts are its handlers (Command, Query) and options
// (SnapshotEvery, StateCodec, EventCodec, Replicated, ApplyWithContext).
func EventSourced[S, E any](id string, events *SumType[E], empty func() S, apply func(state S, event E) S, parts ...Part) *EventSourcedEntity[S, E] {
	e := &EventSourcedEntity[S, E]{id: id, events: events, empty: empty, stateCodec: CodecFor[S](), eventCodec: CodecFor[E]()}
	if apply != nil {
		e.apply = func(_ context.Context, s S, ev E) S { return apply(s, ev) }
	}
	for _, p := range parts {
		switch p := p.(type) {
		case esCommand[S, E]:
			e.commands = append(e.commands, p)
			e.handlers = append(e.handlers, p)
		case stateQuery[S]:
			e.queries = append(e.queries, p)
			e.handlers = append(e.handlers, p)
		case entityOption:
			p.applyEntity(e.options())
		case stateCodecPart[S]:
			e.stateCodec = p.codec
		case eventCodecPart[E]:
			e.eventCodec = p.codec
		case applyPart[S, E]:
			e.apply = p.apply
		default:
			e.misplaced = append(e.misplaced, p)
		}
	}
	return e
}

// ID is the entity's component id.
func (e *EventSourcedEntity[S, E]) ID() string { return e.id }

// entitySettings are the options an entity of any type takes.
type entitySettings struct {
	snapshotEvery *int
	replicated    *bool
}

func (e *EventSourcedEntity[S, E]) options() entitySettings {
	return entitySettings{snapshotEvery: &e.snapshotEvery, replicated: &e.replicated}
}

type entityOption struct{ applyEntity func(entitySettings) }

func (entityOption) part() {}

// SnapshotEvery asks the sidecar to keep a snapshot of the state every n events, so recovery folds
// at most n events. 0, the default, never.
func SnapshotEvery(n int) Part {
	return entityOption{applyEntity: func(s entitySettings) { *s.snapshotEvery = n }}
}

// Replicated declares the entity held at several installations and written only at its home (1.19).
func Replicated() Part {
	return entityOption{applyEntity: func(s entitySettings) { *s.replicated = true }}
}

type stateCodecPart[S any] struct{ codec Codec[S] }

func (stateCodecPart[S]) part() {}

// StateCodec is the codec the state is encoded with, in snapshots and replies of the state, in
// place of the default.
func StateCodec[S any](c Codec[S]) Part { return stateCodecPart[S]{codec: c} }

type eventCodecPart[E any] struct{ codec Codec[E] }

func (eventCodecPart[E]) part() {}

// EventCodec is the codec the events are encoded with in the journal, in place of the default.
func EventCodec[E any](c Codec[E]) Part { return eventCodecPart[E]{codec: c} }

type applyPart[S, E any] struct {
	apply func(context.Context, S, E) S
}

func (applyPart[S, E]) part() {}

// ApplyWithContext replaces the entity's apply with one that is given a context, from which
// EventFrom reads where the event was written (1.19, 1.22).
func ApplyWithContext[S, E any](apply func(ctx context.Context, state S, event E) S) Part {
	return applyPart[S, E]{apply: apply}
}

// commandPart is a command of an event sourced entity.
type commandPart[S, E, I, R any] struct {
	handlerBase[I, R]
	f func(context.Context, S, I) (Effect[S, E, R], error)
}

// Command is a handler named wire of an event sourced entity: given the state and its input, it
// answers an effect, persisting events and replying from the state after them, or a refusal
// (Refuse) as its error. Any other error, or a panic, fails the command and changes nothing.
func Command[S, E, I, R any](wire string, f func(ctx context.Context, state S, input I) (Effect[S, E, R], error)) Part {
	return &commandPart[S, E, I, R]{handlerBase: newBase[I, R](wire), f: f}
}

func (c *commandPart[S, E, I, R]) readOnly() bool { return false }

func (c *commandPart[S, E, I, R]) runCommand(ctx context.Context, state S, payload *pb.Payload) (esRun[S, E], error) {
	in, err := c.decode(payload)
	if err != nil {
		return nil, err
	}
	eff, err := c.f(ctx, state, in)
	if err != nil {
		return nil, err
	}
	return esEffect[S, E, R]{eff: eff, reply: c.out}, nil
}

type esEffect[S, E, R any] struct {
	eff   Effect[S, E, R]
	reply Codec[R]
}

func (r esEffect[S, E, R]) materialise(state S, apply func(S, E) S, sc Codec[S], ec Codec[E], snapshot bool) (S, *pb.EventSourcedOut_Reply, error) {
	return materialiseEventSourced(r.eff, state, apply, esCodecs[S, E, R]{state: sc, event: ec, reply: r.reply}, snapshot)
}

func (e *EventSourcedEntity[S, E]) declare(d *declarations) {
	where := e.id
	if e.id == "" {
		where = fmt.Sprintf("event sourced entity of %s", reflect.TypeFor[S]())
		d.problem(where, "an event sourced entity has an id")
	}
	if e.events == nil {
		d.problem(where, "an event sourced entity is given its events' cases (ankka.Cases)")
	}
	if e.empty == nil || e.apply == nil {
		d.problem(where, "an event sourced entity is given its empty state and how an event changes it")
	}
	if len(e.handlers) == 0 {
		d.problem(where, "an event sourced entity has at least one handler")
	}
	if e.snapshotEvery < 0 {
		d.problem(where, "SnapshotEvery is 0 (never) or more, not %d", e.snapshotEvery)
	}
	for _, p := range CodecProblems(e.stateCodec) {
		d.problem(where, "its state, a %s: %s", reflect.TypeFor[S](), p)
	}
	for _, p := range CodecProblems(e.eventCodec) {
		d.problem(where, "its events, a %s: %s", reflect.TypeFor[E](), p)
	}
	for _, p := range e.misplaced {
		misplaced(d, where, "event sourced entity", p)
	}
	handlerProblems(d, where, e.handlers)
	for _, h := range e.handlers {
		h.declareInput(d, e.id)
	}
	d.components = append(d.components, &pb.Component{
		Kind:     pb.Kind_EVENT_SOURCED_ENTITY,
		Id:       e.id,
		Handlers: handlersOf(e.handlers),
		Detail:   &pb.Component_EventSourced{EventSourced: &pb.EventSourcedDetail{SnapshotEvery: int32(e.snapshotEvery), Replicated: e.replicated}},
	})
	if e.replicated {
		d.require(19, "%s declares replication", e.id)
	}
	d.eventSourced[e.id] = func(entityID string) server.EventSourcedInstance { return e.instance(entityID) }
}

// esInstance is one loaded entity: its state and the sequence of its last event.
type esInstance[S, E any] struct {
	e            *EventSourcedEntity[S, E]
	entityID     string
	state        S
	sequence     int64
	installation string
}

func (e *EventSourcedEntity[S, E]) instance(entityID string) *esInstance[S, E] {
	return &esInstance[S, E]{e: e, entityID: entityID}
}

func (i *esInstance[S, E]) Recover(_ context.Context, init *pb.EventSourcedIn_Init) error {
	i.installation = init.GetInstallation()
	if snap := init.GetSnapshot(); snap != nil {
		s, err := valueOf(i.e.stateCodec, snap.GetPayload())
		if err != nil {
			return fmt.Errorf("the snapshot cannot be read as a %s: %w", reflect.TypeFor[S](), err)
		}
		i.state, i.sequence = s, snap.GetSequence()
		return nil
	}
	i.state = i.e.empty()
	return nil
}

func (i *esInstance[S, E]) Apply(ctx context.Context, ev *pb.EventSourcedIn_Event) error {
	event, err := valueOf(i.e.eventCodec, ev.GetPayload())
	if err != nil {
		return fmt.Errorf("event %d cannot be read as a %s: %w", ev.GetSequence(), reflect.TypeFor[E](), err)
	}
	ctx = withEvent(ctx, EventScope{
		Installation: ev.GetInstallation(), Local: ev.GetInstallation() == "" || ev.GetInstallation() == i.installation,
		Sequence: ev.GetSequence(), Attribution: ev.GetAttribution(), ForwardedFrom: ev.GetForwardedFrom(),
	})
	i.state = i.e.apply(ctx, i.state, event)
	i.sequence = ev.GetSequence()
	return nil
}

func (i *esInstance[S, E]) Command(ctx context.Context, cmd *pb.EventSourcedIn_Command) (*pb.EventSourcedOut_Reply, error) {
	md := metadataFromPb(cmd.GetMetadata())
	ctx = withMetadata(withoutSecrets(ctx, "an entity"), md)
	ctx = withCommand(ctx, CommandScope{
		ComponentID: i.e.id, EntityID: i.entityID, Sequence: i.sequence, Metadata: md,
		Installation: cmd.GetInstallation(), AtHome: cmd.GetAtHome() || cmd.GetInstallation() == "",
		Attribution: cmd.GetAttribution(), ForwardedFrom: cmd.GetForwardedFrom(),
	})
	for _, q := range i.e.queries {
		if q.wire() != cmd.GetName() {
			continue
		}
		o, err := q.runQuery(ctx, i.state, cmd.GetPayload())
		if err != nil {
			if isRefusal(err) {
				return refusedEventSourced(err), nil
			}
			return nil, err
		}
		reply := &pb.EventSourcedOut_Reply{Outcome: o}
		if cmd.GetSnapshotRequested() {
			if reply.Snapshot, err = payloadOf(i.e.stateCodec, i.state); err != nil {
				return nil, fmt.Errorf("the state cannot be encoded for a snapshot: %w", err)
			}
		}
		return reply, nil
	}
	for _, c := range i.e.commands {
		if c.wire() != cmd.GetName() {
			continue
		}
		run, err := c.runCommand(ctx, i.state, cmd.GetPayload())
		if err != nil {
			if isRefusal(err) {
				return refusedEventSourced(err), nil
			}
			return nil, err
		}
		apply := func(s S, ev E) S { return i.e.apply(ctx, s, ev) }
		after, reply, err := run.materialise(i.state, apply, i.e.stateCodec, i.e.eventCodec, cmd.GetSnapshotRequested())
		if err != nil {
			return nil, err
		}
		i.state = after
		i.sequence += int64(len(reply.GetEvents()))
		return reply, nil
	}
	return nil, fmt.Errorf("%s has no handler %q", i.e.id, cmd.GetName())
}
