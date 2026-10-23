package ankka

import (
	"context"
	"fmt"
	"reflect"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

// Part is one thing a component's constructor is given beside what it cannot do without: a
// handler (Command, Query, Update, ...) or an option (SnapshotEvery, StateCodec, ...). A part
// meant for another kind, or for another state type, is a problem the service names at start.
type Part interface{ part() }

// handlerPart is what every handler part says about itself.
type handlerPart interface {
	Part
	wire() string
	readOnly() bool
	codecProblems() []string
	declareInput(d *declarations, componentID string)
	describe() string
}

// handlerBase is the wire name and the input and reply codecs every handler has.
type handlerBase[I, R any] struct {
	wireName string
	in       Codec[I]
	out      Codec[R]
}

func newBase[I, R any](wire string) handlerBase[I, R] {
	return handlerBase[I, R]{wireName: wire, in: CodecFor[I](), out: CodecFor[R]()}
}

func (h handlerBase[I, R]) part()        {}
func (h handlerBase[I, R]) wire() string { return h.wireName }
func (h handlerBase[I, R]) describe() string {
	return fmt.Sprintf("%s (%s to %s)", h.wireName, reflect.TypeFor[I](), reflect.TypeFor[R]())
}

func (h handlerBase[I, R]) codecProblems() []string {
	var out []string
	for _, p := range CodecProblems(h.in) {
		out = append(out, fmt.Sprintf("its input, a %s: %s", reflect.TypeFor[I](), p))
	}
	for _, p := range CodecProblems(h.out) {
		out = append(out, fmt.Sprintf("its reply, a %s: %s", reflect.TypeFor[R](), p))
	}
	return out
}

func (h handlerBase[I, R]) declareInput(d *declarations, componentID string) {
	input(d, componentID, h.wireName, h.in)
}

// decode is the handler's input, or a refusal saying why it cannot be read.
func (h handlerBase[I, R]) decode(p *pb.Payload) (I, error) {
	v, err := valueOf(h.in, p)
	if err != nil {
		return v, Refusef(BadRequest, "the input to %s cannot be read as a %s: %v", h.wireName, reflect.TypeFor[I](), err)
	}
	return v, nil
}

// queryPart is a read-only handler of any stateful kind: it answers from the state and changes
// nothing, which its function type makes a compiler guarantee.
type queryPart[S, I, R any] struct {
	handlerBase[I, R]
	f func(context.Context, S, I) (ReadOnlyEffect[R], error)
}

// stateQuery is a query over the state S, answering its outcome encoded.
type stateQuery[S any] interface {
	handlerPart
	runQuery(ctx context.Context, state S, payload *pb.Payload) (*pb.Outcome, error)
}

// Query is a read-only handler named wire, for an event sourced entity, a key value entity or a
// workflow: it answers from the state with Reply or NoReply and cannot change it.
func Query[S, I, R any](wire string, f func(ctx context.Context, state S, input I) (ReadOnlyEffect[R], error)) Part {
	return &queryPart[S, I, R]{handlerBase: newBase[I, R](wire), f: f}
}

func (q *queryPart[S, I, R]) readOnly() bool { return true }

func (q *queryPart[S, I, R]) runQuery(ctx context.Context, state S, payload *pb.Payload) (*pb.Outcome, error) {
	in, err := q.decode(payload)
	if err != nil {
		return nil, err
	}
	eff, err := q.f(ctx, state, in)
	if err != nil {
		return nil, err
	}
	return readOnlyToPb(eff, q.out)
}

// handlerProblems checks a component's handlers: wire names present and distinct, codecs sound.
func handlerProblems(d *declarations, componentID string, handlers []handlerPart) {
	seen := map[string]int{}
	for _, h := range handlers {
		if h.wire() == "" {
			d.problem(componentID, "a handler has no wire name: %s", h.describe())
		}
		seen[h.wire()]++
		for _, p := range h.codecProblems() {
			d.problem(componentID+"."+h.wire(), "%s", p)
		}
	}
	for _, w := range sortedKeys(seen) {
		if w != "" && seen[w] > 1 {
			d.problem(componentID, "%d handlers share the wire name %q; a wire name names one handler", seen[w], w)
		}
	}
}

// handlersOf is the protocol's description of a component's handlers.
func handlersOf(handlers []handlerPart) []*pb.Handler {
	out := make([]*pb.Handler, 0, len(handlers))
	for _, h := range handlers {
		out = append(out, &pb.Handler{Name: h.wire(), ReadOnly: h.readOnly()})
	}
	return out
}

// misplaced names a part given to a component it does not belong to.
func misplaced(d *declarations, componentID, kind string, p Part) {
	if h, ok := p.(handlerPart); ok {
		d.problem(componentID, "the handler %s is not one a %s takes, or handles another state type", h.describe(), kind)
		return
	}
	d.problem(componentID, "%T is not an option a %s takes", p, kind)
}
