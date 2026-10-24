package ankka

import (
	"errors"
	"fmt"
	"sort"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

// One reduction per kind, shared by the server and the test kits, so a test and a deployment cannot
// disagree about what an effect means. This is the SDK's copy of the rule that puts
// EventSourcedEffect.materialise in the platform's core: the reply is computed from the state after
// the events, and a refusal or a failure leaves everything as it was.

// errNoEffect is a handler that returned neither an effect nor an error.
var errNoEffect = errors.New("the handler returned no effect: build one with Persist, UpdateState, Reply or NoReply")

// payloadOf encodes v by c as a protocol payload.
func payloadOf[T any](c Codec[T], v T) (*pb.Payload, error) {
	data, err := c.Encode(v)
	if err != nil {
		return nil, err
	}
	return &pb.Payload{ContentType: c.ContentType(), Manifest: c.Manifest(), Data: data}, nil
}

// valueOf decodes a protocol payload by c. An absent payload is c's value of no bytes.
func valueOf[T any](c Codec[T], p *pb.Payload) (T, error) {
	if p == nil {
		return c.Decode(nil)
	}
	return c.Decode(p.GetData())
}

func metadataToPb(md Metadata) *pb.Metadata {
	if len(md) == 0 {
		return &pb.Metadata{}
	}
	keys := make([]string, 0, len(md))
	for k := range md {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	out := &pb.Metadata{Entries: make([]*pb.Metadata_Entry, 0, len(keys))}
	for _, k := range keys {
		out.Entries = append(out.Entries, &pb.Metadata_Entry{Key: k, Value: md[k]})
	}
	return out
}

func metadataFromPb(m *pb.Metadata) Metadata {
	out := Metadata{}
	for _, e := range m.GetEntries() {
		out[e.GetKey()] = e.GetValue()
	}
	return out
}

func retentionToPb(r retention) *pb.Retention {
	switch {
	case r.deleteNow:
		return &pb.Retention{Retention: &pb.Retention_DeleteNow_{DeleteNow: &pb.Retention_DeleteNow{}}}
	case r.expireAfter > 0:
		return &pb.Retention{Retention: &pb.Retention_ExpireAfter_{ExpireAfter: &pb.Retention_ExpireAfter{Millis: r.expireAfter.Milliseconds()}}}
	}
	return nil
}

func replicationToPb(c *ReplicationChange) *pb.ReplicationChange {
	if c == nil {
		return nil
	}
	if c.HandOverTo != "" {
		return &pb.ReplicationChange{Change: &pb.ReplicationChange_HandOverTo{HandOverTo: c.HandOverTo}}
	}
	return &pb.ReplicationChange{Change: &pb.ReplicationChange_Set{Set: &pb.ReplicationChange_SetChange{Add: c.Add, Remove: c.Remove}}}
}

// errorToPb is the protocol's error for err: a refusal keeps its code, message and details; anything
// else is INTERNAL with its message.
func errorToPb(err error) *pb.Error {
	if r, ok := AsRefusal(err); ok {
		return &pb.Error{Message: r.Message, Code: pb.ErrorCode(r.Code), Details: r.Details}
	}
	return &pb.Error{Message: err.Error(), Code: pb.ErrorCode_INTERNAL}
}

// ProtocolError is the refusal as the protocol's error, so a servicer that fails with it keeps its
// code.
func (e *RefusalError) ProtocolError() *pb.Error { return errorToPb(e) }

// errorFromPb is the refusal a protocol error describes.
func errorFromPb(e *pb.Error) *RefusalError {
	return &RefusalError{Code: ErrorCode(e.GetCode()), Message: e.GetMessage(), Details: e.GetDetails()}
}

// isRefusal reports whether a handler's error is a refusal, answered as an outcome, rather than a
// failure, answered as the protocol's Failure.
func isRefusal(err error) bool {
	_, ok := AsRefusal(err)
	return ok
}

func refusedOutcome(err error) *pb.Outcome {
	return &pb.Outcome{Outcome: &pb.Outcome_Error{Error: errorToPb(err)}}
}

func noReplyOutcome() *pb.Outcome {
	return &pb.Outcome{Outcome: &pb.Outcome_NoReply_{NoReply: &pb.Outcome_NoReply{}}}
}

// outcomeToPb computes o's reply from state and encodes it by rc.
func outcomeToPb[S, R any](o outcome[S, R], state S, rc Codec[R]) (*pb.Outcome, error) {
	switch o.kind {
	case outcomeReply:
		v, err := computeReply(o.compute, state)
		if err != nil {
			return nil, err
		}
		return replyOutcome(rc, v, o.metadata)
	case outcomeNoReply:
		return noReplyOutcome(), nil
	}
	return nil, errNoEffect
}

func replyOutcome[R any](rc Codec[R], v R, md Metadata) (*pb.Outcome, error) {
	p, err := payloadOf(rc, v)
	if err != nil {
		return nil, fmt.Errorf("the reply cannot be encoded: %w", err)
	}
	return &pb.Outcome{Outcome: &pb.Outcome_Reply_{Reply: &pb.Outcome_Reply{Payload: p, Metadata: metadataToPb(md)}}}, nil
}

// computeReply runs a reply function, turning its panic into an error so the caller answers a
// Failure with the state unchanged.
func computeReply[S, R any](compute func(S) R, state S) (r R, err error) {
	defer func() {
		if p := recover(); p != nil {
			err = fmt.Errorf("the reply panicked: %v", p)
		}
	}()
	return compute(state), nil
}

func readOnlyToPb[R any](e ReadOnlyEffect[R], rc Codec[R]) (*pb.Outcome, error) {
	if !e.reply {
		return noReplyOutcome(), nil
	}
	return replyOutcome(rc, e.value, e.metadata)
}

// foldEvents applies events to state in order, a panic in apply an error.
func foldEvents[S, E any](state S, events []E, apply func(S, E) S) (s S, err error) {
	defer func() {
		if p := recover(); p != nil {
			err = fmt.Errorf("applying an event panicked: %v", p)
		}
	}()
	for _, e := range events {
		state = apply(state, e)
	}
	return state, nil
}

// esCodecs are what an event sourced entity's reply is encoded with.
type esCodecs[S, E, R any] struct {
	state Codec[S]
	event Codec[E]
	reply Codec[R]
}

// materialiseEventSourced is an event sourced command's effect against state: the state after its
// events and the reply the protocol carries. An error leaves state as it was and is answered as a
// Failure by the caller.
func materialiseEventSourced[S, E, R any](eff Effect[S, E, R], state S, apply func(S, E) S, c esCodecs[S, E, R], snapshot bool) (S, *pb.EventSourcedOut_Reply, error) {
	if eff.problem != "" {
		return state, nil, errors.New(eff.problem)
	}
	if eff.outcome.kind == outcomeNone {
		return state, nil, errNoEffect
	}
	after, err := foldEvents(state, eff.events, apply)
	if err != nil {
		return state, nil, err
	}
	reply := &pb.EventSourcedOut_Reply{Retention: retentionToPb(eff.retention), Replication: replicationToPb(eff.replication)}
	for _, e := range eff.events {
		p, err := payloadOf(c.event, e)
		if err != nil {
			return state, nil, fmt.Errorf("an event cannot be encoded: %w", err)
		}
		reply.Events = append(reply.Events, p)
	}
	if reply.Outcome, err = outcomeToPb(eff.outcome, after, c.reply); err != nil {
		return state, nil, err
	}
	if snapshot {
		if reply.Snapshot, err = payloadOf(c.state, after); err != nil {
			return state, nil, fmt.Errorf("the state cannot be encoded for a snapshot: %w", err)
		}
	}
	return after, reply, nil
}

// materialiseEventSourcedQuery is a query's read-only effect: nothing persisted, the state as it was.
func materialiseEventSourcedQuery[S, R any](eff ReadOnlyEffect[R], state S, sc Codec[S], rc Codec[R], snapshot bool) (*pb.EventSourcedOut_Reply, error) {
	o, err := readOnlyToPb(eff, rc)
	if err != nil {
		return nil, err
	}
	reply := &pb.EventSourcedOut_Reply{Outcome: o}
	if snapshot {
		if reply.Snapshot, err = payloadOf(sc, state); err != nil {
			return nil, fmt.Errorf("the state cannot be encoded for a snapshot: %w", err)
		}
	}
	return reply, nil
}

// refusedEventSourced is a refusal's reply: no events, the caller sees the code.
func refusedEventSourced(err error) *pb.EventSourcedOut_Reply {
	return &pb.EventSourcedOut_Reply{Outcome: refusedOutcome(err)}
}

// materialiseKeyValue is a key value command's effect: the state to keep and the reply.
func materialiseKeyValue[S, R any](eff KeyValueEffect[S, R], state S, sc Codec[S], rc Codec[R]) (S, *pb.KeyValueOut_Reply, error) {
	if eff.outcome.kind == outcomeNone {
		return state, nil, errNoEffect
	}
	after := state
	reply := &pb.KeyValueOut_Reply{Retention: retentionToPb(eff.retention)}
	if eff.update {
		after = eff.state
		p, err := payloadOf(sc, after)
		if err != nil {
			return state, nil, fmt.Errorf("the state cannot be encoded: %w", err)
		}
		reply.NewState = p
	}
	o, err := outcomeToPb(eff.outcome, after, rc)
	if err != nil {
		return state, nil, err
	}
	reply.Outcome = o
	return after, reply, nil
}

func materialiseKeyValueQuery[R any](eff ReadOnlyEffect[R], rc Codec[R]) (*pb.KeyValueOut_Reply, error) {
	o, err := readOnlyToPb(eff, rc)
	if err != nil {
		return nil, err
	}
	return &pb.KeyValueOut_Reply{Outcome: o}, nil
}

// stepInputEncoder encodes a transition's input by the codec its step declares.
type stepInputEncoder func(step string, input any) (*pb.Payload, error)

func stepRefToPb(ref StepRef, enc stepInputEncoder) (*pb.StepRef, error) {
	out := &pb.StepRef{Step: ref.Step}
	if ref.Input != nil {
		p, err := enc(ref.Step, ref.Input)
		if err != nil {
			return nil, err
		}
		out.Input = p
	}
	return out, nil
}

// materialiseWorkflowCommand is a workflow command's effect: the state to keep, the step to start, the reply.
func materialiseWorkflowCommand[S, R any](eff WorkflowEffect[S, R], state S, sc Codec[S], rc Codec[R], enc stepInputEncoder) (S, *pb.WorkflowOut_Reply, error) {
	if eff.outcome.kind == outcomeNone {
		return state, nil, errNoEffect
	}
	after := state
	reply := &pb.WorkflowOut_Reply{}
	if eff.update {
		after = eff.state
		p, err := payloadOf(sc, after)
		if err != nil {
			return state, nil, fmt.Errorf("the state cannot be encoded: %w", err)
		}
		reply.NewState = p
	}
	if eff.transition != nil {
		ref, err := stepRefToPb(*eff.transition, enc)
		if err != nil {
			return state, nil, err
		}
		reply.Transition = ref
	}
	o, err := outcomeToPb(eff.outcome, after, rc)
	if err != nil {
		return state, nil, err
	}
	reply.Outcome = o
	return after, reply, nil
}

func materialiseWorkflowQuery[R any](eff ReadOnlyEffect[R], rc Codec[R]) (*pb.WorkflowOut_Reply, error) {
	o, err := readOnlyToPb(eff, rc)
	if err != nil {
		return nil, err
	}
	return &pb.WorkflowOut_Reply{Outcome: o}, nil
}

// materialiseStep is a step's effect: the state to keep and what happens next.
func materialiseStep[S any](eff StepEffect[S], state S, sc Codec[S], enc stepInputEncoder) (S, *pb.WorkflowOut_StepReply, error) {
	after := state
	reply := &pb.WorkflowOut_StepReply{}
	if eff.update {
		after = eff.state
		p, err := payloadOf(sc, after)
		if err != nil {
			return state, nil, fmt.Errorf("the state cannot be encoded: %w", err)
		}
		reply.NewState = p
	}
	switch eff.next {
	case stepTransition:
		ref, err := stepRefToPb(eff.ref, enc)
		if err != nil {
			return state, nil, err
		}
		reply.Next = &pb.StepOutcome{Outcome: &pb.StepOutcome_TransitionTo{TransitionTo: ref}}
	case stepPause:
		pause := &pb.StepOutcome_Pause{}
		if eff.pause.After > 0 {
			ms := eff.pause.After.Milliseconds()
			pause.AfterMillis = &ms
		}
		if eff.pause.OnTimeout != "" {
			ref, err := stepRefToPb(StepRef{Step: eff.pause.OnTimeout, Input: eff.pause.OnTimeoutInput}, enc)
			if err != nil {
				return state, nil, err
			}
			pause.OnTimeout = ref
		}
		reply.Next = &pb.StepOutcome{Outcome: &pb.StepOutcome_Pause_{Pause: pause}}
	case stepEnd:
		reply.Next = &pb.StepOutcome{Outcome: &pb.StepOutcome_End_{End: &pb.StepOutcome_End{}}}
	case stepFail:
		reply.Next = &pb.StepOutcome{Outcome: &pb.StepOutcome_Fail{Fail: &pb.Error{Message: eff.failText, Code: pb.ErrorCode(eff.failCode)}}}
	default:
		return state, nil, errors.New("the step returned no effect: build one with StepUpdate, TransitionTo, Pause, End or FailWorkflow")
	}
	return after, reply, nil
}
