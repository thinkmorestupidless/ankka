package ankka

import (
	"context"
	"errors"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

// TimerTarget is what a timer calls when it is due: a handler of one of this service's components,
// with the instance for an entity, workflow or agent.
type TimerTarget struct {
	ComponentID string
	EntityID    string
	Handler     string
}

// Schedule makes the timer timerID call target with input after delay, replacing any timer under
// the id. A timer is a database row: it survives a restart and fires once.
func Schedule(ctx context.Context, timerID string, delay time.Duration, target TimerTarget, input any) error {
	c, rpc, err := clientOf(ctx)
	if err != nil {
		return err
	}
	kind, err := c.kindOf(target.ComponentID)
	if err != nil {
		return err
	}
	payload, err := c.encodeInput(target.ComponentID, target.Handler, input)
	if err != nil {
		return err
	}
	req := &pb.ScheduleRequest{TimerId: timerID, DelayMillis: delay.Milliseconds(), Kind: kind, ComponentId: target.ComponentID, Name: target.Handler, Payload: payload}
	if target.EntityID != "" {
		req.EntityId = &target.EntityID
	}
	_, err = rpc.Schedule(ctx, req)
	return tooOld("timers", "1.0", err)
}

// ScheduleRecurring makes the timer timerID call a timed action's handler first after delay, then
// once every period, until it is cancelled or replaced (1.12). Scheduling again with the same target
// and period keeps the next due and takes the input.
func ScheduleRecurring(ctx context.Context, timerID string, delay, period time.Duration, target TimerTarget, input any) error {
	if period <= 0 {
		return Refuse(BadRequest, "a recurring timer's period is above zero")
	}
	if target.EntityID != "" {
		return Refuse(BadRequest, "a recurring timer calls a timed action, which has no instance; leave EntityID empty")
	}
	c, rpc, err := clientOf(ctx)
	if err != nil {
		return err
	}
	if kind, err := c.kindOf(target.ComponentID); err != nil {
		return err
	} else if kind != pb.Kind_TIMED_ACTION {
		return Refusef(BadRequest, "a recurring timer calls a timed action; %s is not one", target.ComponentID)
	}
	payload, err := c.encodeInput(target.ComponentID, target.Handler, input)
	if err != nil {
		return err
	}
	reply, err := rpc.ScheduleRecurring(ctx, &pb.ScheduleRecurringRequest{TimerId: timerID, DelayMillis: delay.Milliseconds(), PeriodMillis: period.Milliseconds(), ComponentId: target.ComponentID, Name: target.Handler, Payload: payload})
	if err != nil {
		return tooOld("recurring timers", "1.12", err)
	}
	if reply.GetError() != nil {
		return errorFromPb(reply.GetError())
	}
	return nil
}

// Cancel cancels the timer timerID; none under the id is no error.
func Cancel(ctx context.Context, timerID string) error {
	_, rpc, err := clientOf(ctx)
	if err != nil {
		return err
	}
	if timerID == "" {
		return errors.New("a timer is cancelled by its id")
	}
	_, err = rpc.Cancel(ctx, &pb.CancelRequest{TimerId: timerID})
	return tooOld("timers", "1.0", err)
}
