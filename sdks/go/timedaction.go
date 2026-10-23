package ankka

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

// TimedActionComponent is a timed action's declaration: the calls a timer makes later. Timers are
// scheduled through Schedule and ScheduleRecurring; the sidecar's sweeper delivers each one here
// and retries a failure with backoff.
type TimedActionComponent struct {
	id        string
	actions   []actionHandler
	handlers  []handlerPart
	misplaced []Part
}

type actionHandler interface {
	handlerPart
	run(ctx context.Context, payload *pb.Payload) (ActionEffect, error)
}

type actionPart[I any] struct {
	handlerBase[I, Done]
	f func(context.Context, I) (ActionEffect, error)
}

func (a *actionPart[I]) readOnly() bool { return false }

func (a *actionPart[I]) run(ctx context.Context, payload *pb.Payload) (ActionEffect, error) {
	in, err := a.decode(payload)
	if err != nil {
		return ActionEffect{}, err
	}
	return a.f(ctx, in)
}

// Action is a timed action's handler named wire: given its input, it answers ActionDone or
// ActionFail. An error, or a panic, fails the timer, which the sweeper retries; a refusal fails
// it with the refusal's code.
func Action[I any](wire string, f func(ctx context.Context, input I) (ActionEffect, error)) Part {
	return &actionPart[I]{handlerBase: newBase[I, Done](wire), f: f}
}

// TimedAction declares a timed action and its actions (Action).
func TimedAction(id string, parts ...Part) *TimedActionComponent {
	t := &TimedActionComponent{id: id}
	for _, p := range parts {
		if a, ok := p.(actionHandler); ok {
			t.actions = append(t.actions, a)
			t.handlers = append(t.handlers, a)
			continue
		}
		t.misplaced = append(t.misplaced, p)
	}
	return t
}

// ID is the timed action's component id.
func (t *TimedActionComponent) ID() string { return t.id }

func (t *TimedActionComponent) declare(d *declarations) {
	where := t.id
	if t.id == "" {
		where = "timed action"
		d.problem(where, "a timed action has an id")
	}
	if len(t.actions) == 0 {
		d.problem(where, "a timed action has at least one Action")
	}
	for _, p := range t.misplaced {
		misplaced(d, where, "timed action", p)
	}
	handlerProblems(d, where, t.handlers)
	for _, h := range t.handlers {
		h.declareInput(d, t.id)
	}
	d.components = append(d.components, &pb.Component{
		Kind:     pb.Kind_TIMED_ACTION,
		Id:       t.id,
		Handlers: handlersOf(t.handlers),
		Detail:   &pb.Component_TimedAction{TimedAction: &pb.TimedActionDetail{}},
	})
	d.timedActions[t.id] = t.invoke
}

// timerScope is the timer a request is for, from its metadata: ankka.timer its id, ankka.due its
// due time in epoch milliseconds (from 1.12), ankka.attempts how many times it has fired.
func timerScope(md Metadata) TimerScope {
	s := TimerScope{Name: md["ankka.timer"]}
	if ms, err := strconv.ParseInt(strings.TrimSpace(md["ankka.due"]), 10, 64); err == nil {
		s.Due = time.UnixMilli(ms)
	}
	if n, err := strconv.Atoi(strings.TrimSpace(md["ankka.attempts"])); err == nil {
		s.Attempts = n
	}
	return s
}

func actionFailed(code ErrorCode, message string) *pb.TimedActionEffect {
	return &pb.TimedActionEffect{Effect: &pb.TimedActionEffect_Fail{Fail: &pb.Error{Code: pb.ErrorCode(code), Message: message}}}
}

// invoke is one timer's call. Every failure is an answer, never an error: the sweeper retries it.
func (t *TimedActionComponent) invoke(ctx context.Context, req *pb.TimedActionRequest) (*pb.TimedActionEffect, error) {
	var action actionHandler
	for _, a := range t.actions {
		if a.wire() == req.GetName() {
			action = a
		}
	}
	if action == nil {
		return actionFailed(NotFound, fmt.Sprintf("no action %q on %s", req.GetName(), t.id)), nil
	}
	md := metadataFromPb(req.GetMetadata())
	ctx = withTimer(withMetadata(ctx, md), timerScope(md))
	eff, err := action.run(ctx, req.GetPayload())
	if err != nil {
		var r *RefusalError
		if errors.As(err, &r) {
			return actionFailed(r.Code, r.Message), nil
		}
		return actionFailed(Internal, err.Error()), nil
	}
	if !eff.set {
		return actionFailed(Internal, fmt.Sprintf("%s/%s returned no effect: build one with ActionDone or ActionFail", t.id, req.GetName())), nil
	}
	if eff.failed {
		return actionFailed(eff.code, eff.message), nil
	}
	return &pb.TimedActionEffect{Effect: &pb.TimedActionEffect_Done{Done: &pb.Empty{}}}, nil
}
