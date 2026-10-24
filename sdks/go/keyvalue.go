package ankka

import (
	"context"
	"fmt"
	"reflect"

	"github.com/thinkmorestupidless/ankka-go/internal/kit"
	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"github.com/thinkmorestupidless/ankka-go/internal/server"
)

// KeyValueEntity is a key value entity's declaration: its id, its empty state and its handlers.
// Its state is kept whole by the sidecar, replaced by each command that updates it.
type KeyValueEntity[S any] struct {
	id         string
	empty      func(entityID string) S
	updates    []kvUpdate[S]
	queries    []stateQuery[S]
	handlers   []handlerPart
	stateCodec Codec[S]
	misplaced  []Part
}

// kvUpdate is a command of a key value entity of state S.
type kvUpdate[S any] interface {
	handlerPart
	runUpdate(ctx context.Context, state S, payload *pb.Payload, sc Codec[S]) (S, *pb.KeyValueOut_Reply, error)
}

// KeyValue declares a key value entity. empty is the state of an entity nothing has written, given
// its id. The parts are its handlers (Update, Query) and options (StateCodec).
func KeyValue[S any](id string, empty func(entityID string) S, parts ...Part) *KeyValueEntity[S] {
	e := &KeyValueEntity[S]{id: id, empty: empty, stateCodec: CodecFor[S]()}
	for _, p := range parts {
		switch p := p.(type) {
		case kvUpdate[S]:
			e.updates = append(e.updates, p)
			e.handlers = append(e.handlers, p)
		case stateQuery[S]:
			e.queries = append(e.queries, p)
			e.handlers = append(e.handlers, p)
		case stateCodecPart[S]:
			e.stateCodec = p.codec
		default:
			e.misplaced = append(e.misplaced, p)
		}
	}
	return e
}

// ID is the entity's component id.
func (e *KeyValueEntity[S]) ID() string { return e.id }

// updatePart is a command of a key value entity.
type updatePart[S, I, R any] struct {
	handlerBase[I, R]
	f func(context.Context, S, I) (KeyValueEffect[S, R], error)
}

// Update is a handler named wire of a key value entity: given the state and its input, it answers
// an effect, replacing the state (UpdateState), keeping it (KeepState) or deleting the entity
// (DeleteState), then replying; or a refusal (Refuse) as its error. Any other error, or a panic,
// fails the command and changes nothing.
func Update[S, I, R any](wire string, f func(ctx context.Context, state S, input I) (KeyValueEffect[S, R], error)) Part {
	return &updatePart[S, I, R]{handlerBase: newBase[I, R](wire), f: f}
}

func (u *updatePart[S, I, R]) readOnly() bool { return false }

func (u *updatePart[S, I, R]) runUpdate(ctx context.Context, state S, payload *pb.Payload, sc Codec[S]) (S, *pb.KeyValueOut_Reply, error) {
	in, err := u.decode(payload)
	if err != nil {
		return state, nil, err
	}
	eff, err := u.f(ctx, state, in)
	if err != nil {
		return state, nil, err
	}
	return materialiseKeyValue(eff, state, sc, u.out)
}

func (e *KeyValueEntity[S]) declare(d *declarations) {
	where := e.id
	if e.id == "" {
		where = fmt.Sprintf("key value entity of %s", reflect.TypeFor[S]())
		d.problem(where, "a key value entity has an id")
	}
	if e.empty == nil {
		d.problem(where, "a key value entity is given its empty state")
	}
	if len(e.handlers) == 0 {
		d.problem(where, "a key value entity has at least one handler")
	}
	for _, p := range CodecProblems(e.stateCodec) {
		d.problem(where, "its state, a %s: %s", reflect.TypeFor[S](), p)
	}
	for _, p := range e.misplaced {
		misplaced(d, where, "key value entity", p)
	}
	handlerProblems(d, where, e.handlers)
	for _, h := range e.handlers {
		h.declareInput(d, e.id)
	}
	d.components = append(d.components, &pb.Component{
		Kind:     pb.Kind_KEY_VALUE_ENTITY,
		Id:       e.id,
		Handlers: handlersOf(e.handlers),
		Detail:   &pb.Component_KeyValue{KeyValue: &pb.KeyValueDetail{}},
	})
	d.keyValue[e.id] = func(entityID string) server.KeyValueInstance { return e.instance(entityID) }
	d.kvKits[e.id] = e.kitView()
}

// kvInstance is one loaded key value entity.
type kvInstance[S any] struct {
	e        *KeyValueEntity[S]
	entityID string
	state    S
}

func (e *KeyValueEntity[S]) instance(entityID string) *kvInstance[S] {
	return &kvInstance[S]{e: e, entityID: entityID}
}

func (i *kvInstance[S]) Recover(_ context.Context, init *pb.KeyValueIn_Init) error {
	if p := init.GetState(); p != nil {
		s, err := valueOf(i.e.stateCodec, p)
		if err != nil {
			return fmt.Errorf("the state cannot be read as a %s: %w", reflect.TypeFor[S](), err)
		}
		i.state = s
		return nil
	}
	i.state = i.e.empty(i.entityID)
	return nil
}

func (i *kvInstance[S]) Command(ctx context.Context, cmd *pb.KeyValueIn_Command) (*pb.KeyValueOut_Reply, error) {
	md := metadataFromPb(cmd.GetMetadata())
	ctx = withMetadata(withoutSecrets(ctx, "an entity"), md)
	ctx = withCommand(ctx, CommandScope{ComponentID: i.e.id, EntityID: i.entityID, Metadata: md, AtHome: true})
	for _, q := range i.e.queries {
		if q.wire() != cmd.GetName() {
			continue
		}
		o, err := q.runQuery(ctx, i.state, cmd.GetPayload())
		if err != nil {
			if isRefusal(err) {
				return &pb.KeyValueOut_Reply{Outcome: refusedOutcome(err)}, nil
			}
			return nil, err
		}
		return &pb.KeyValueOut_Reply{Outcome: o}, nil
	}
	for _, u := range i.e.updates {
		if u.wire() != cmd.GetName() {
			continue
		}
		after, reply, err := u.runUpdate(ctx, i.state, cmd.GetPayload(), i.e.stateCodec)
		if err != nil {
			if isRefusal(err) {
				return &pb.KeyValueOut_Reply{Outcome: refusedOutcome(err)}, nil
			}
			return nil, err
		}
		if reply.GetRetention().GetDeleteNow() != nil {
			// A deleted entity written again starts from its empty state.
			after = i.e.empty(i.entityID)
		}
		i.state = after
		return reply, nil
	}
	return nil, fmt.Errorf("%s has no handler %q", i.e.id, cmd.GetName())
}

// kitView is the entity as a test kit drives it.
func (e *KeyValueEntity[S]) kitView() *kit.KeyValue {
	handlers := map[string]bool{}
	byWire := map[string]handlerPart{}
	for _, h := range e.handlers {
		handlers[h.wire()] = h.readOnly()
		byWire[h.wire()] = h
	}
	return &kit.KeyValue{
		ID:  e.id,
		New: func(entityID string) server.KeyValueInstance { return e.instance(entityID) },
		Input: func(wire string, v any) (*pb.Payload, error) {
			h, ok := byWire[wire]
			if !ok {
				return nil, fmt.Errorf("%s has no handler %q", e.id, wire)
			}
			return h.encodeInput(v)
		},
		Reply: func(wire string, p *pb.Payload) (any, error) {
			h, ok := byWire[wire]
			if !ok {
				return nil, fmt.Errorf("%s has no handler %q", e.id, wire)
			}
			return h.decodeReply(p)
		},
		State: func(p *pb.Payload) (any, error) { return valueOf(e.stateCodec, p) },
		Empty: func(entityID string) (*pb.Payload, error) {
			return payloadOf(e.stateCodec, e.empty(entityID))
		},
		Handlers: handlers,
	}
}
