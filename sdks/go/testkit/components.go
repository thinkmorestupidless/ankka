package testkit

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"time"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/internal/kit"
	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"github.com/thinkmorestupidless/ankka-go/internal/server"
)

// Calling gives a kit the components its handlers call through the client: their calls reach
// those components' hosts in this process, as Endpoint's do. Without it a call fails, naming the
// component.
func Calling(components ...ankka.Declaration) Option {
	return func(o *options) { o.calling = append(o.calling, components...) }
}

// hosted is a service of one component and the ones it calls, and the client its handlers use.
type hosted struct {
	view   *kit.Service
	client *ankka.Client
	stop   func()
}

func host(component ankka.Declaration, o options) *hosted {
	v := view(ankka.NewService(append([]ankka.Declaration{component}, o.calling...)...))
	client, stop := serveStandIn(v)
	return &hosted{view: v, client: client, stop: stop}
}

// ctx is ctx with the kit's client, so a handler's calls reach the components the kit was given.
func (h *hosted) ctx(ctx context.Context) context.Context {
	return h.view.WithClient(ctx, h.client)
}

// Close stops the kit's stand-in for the sidecar.
func (h *hosted) Close() { h.stop() }

func changeMetadata(subject string, sequence int64, md ankka.Metadata) *pb.Metadata {
	all := ankka.Metadata{"ce-subject": subject, "ankka.sequence": strconv.FormatInt(sequence, 10), "ankka.protocol": ankka.ProtocolVersion}
	for k, v := range md {
		all[k] = v
	}
	return metadataOf(all)
}

// ChangeOption says more about a change a view or consumer kit is handed.
type ChangeOption func(*change)

type change struct {
	sequence int64
	md       ankka.Metadata
	standing *pb.WorkflowStanding
}

// Sequence is the change's sequence number; by default each change a kit is handed is one more
// than the last.
func Sequence(n int64) ChangeOption { return func(c *change) { c.sequence = n } }

// WithMetadata adds entries to the change's metadata.
func WithMetadata(md ankka.Metadata) ChangeOption { return func(c *change) { c.md = md } }

// FromWorkflow is the standing of the workflow source the change is from.
func FromWorkflow(s ankka.Standing) ChangeOption {
	return func(c *change) {
		ps := &pb.WorkflowStanding{Status: s.Status, Retries: map[string]int32{}}
		if s.Step != "" {
			ps.Step = &s.Step
		}
		if s.Failure != "" {
			ps.Failure = &s.Failure
		}
		for k, v := range s.Retries {
			ps.Retries[k] = int32(v)
		}
		c.standing = ps
	}
}

func changeOf(next *int64, opts []ChangeOption) change {
	*next++
	c := change{sequence: *next}
	for _, o := range opts {
		o(&c)
	}
	return c
}

// ---- key value entities ----

// KeyValueResult is what one command did to a key value entity.
type KeyValueResult[S any] struct {
	reply    any
	replied  bool
	metadata ankka.Metadata
	state    S
	refusal  *ankka.RefusalError
	failure  error
	deleted  bool
	expiry   time.Duration
}

// Reply is the reply, decoded by the handler's reply codec, or nil.
func (r *KeyValueResult[S]) Reply() any { return r.reply }

// Replied is whether the handler replied.
func (r *KeyValueResult[S]) Replied() bool { return r.replied }

// ReplyMetadata is the metadata the reply carried.
func (r *KeyValueResult[S]) ReplyMetadata() ankka.Metadata { return r.metadata }

// State is the state after the command.
func (r *KeyValueResult[S]) State() S { return r.state }

// Refusal is the command's refusal, or nil.
func (r *KeyValueResult[S]) Refusal() *ankka.RefusalError { return r.refusal }

// Failure is why the command failed rather than answered, or nil.
func (r *KeyValueResult[S]) Failure() error { return r.failure }

// Deleted is whether the command deleted the entity.
func (r *KeyValueResult[S]) Deleted() bool { return r.deleted }

// ExpireAfter is the expiry the effect set, or 0.
func (r *KeyValueResult[S]) ExpireAfter() time.Duration { return r.expiry }

// KeyValueKit drives one instance of a key value entity.
type KeyValueKit[S any] struct {
	*hosted
	e     *kit.KeyValue
	id    string
	inst  server.KeyValueInstance
	next  int64
	state S
}

// KeyValue is a kit for the entity e. It panics, naming each problem, when the entity has problems
// a service would refuse to start with.
func KeyValue[S any](e *ankka.KeyValueEntity[S], opts ...Option) *KeyValueKit[S] {
	o := settings(opts)
	h := host(e, o)
	k := &KeyValueKit[S]{hosted: h, e: h.view.KeyValue[e.ID()], id: o.entityID}
	k.state = k.empty()
	return k
}

func (k *KeyValueKit[S]) empty() S {
	p, err := k.e.Empty(k.id)
	if err != nil {
		panic(fmt.Sprintf("testkit: the empty state of %s cannot be encoded: %v", k.e.ID, err))
	}
	s, err := k.e.State(p)
	if err != nil {
		panic(fmt.Sprintf("testkit: the empty state of %s cannot be read back: %v", k.e.ID, err))
	}
	return s.(S)
}

// EntityID is the id of the instance the kit drives.
func (k *KeyValueKit[S]) EntityID() string { return k.id }

// State is the instance's state, as of its last command.
func (k *KeyValueKit[S]) State() S { return k.state }

// Command runs the handler wire with input.
func (k *KeyValueKit[S]) Command(ctx context.Context, wire string, input any) *KeyValueResult[S] {
	return k.run(ctx, wire, input, nil)
}

// CommandWithMetadata runs the handler wire with input and the request's metadata.
func (k *KeyValueKit[S]) CommandWithMetadata(ctx context.Context, wire string, input any, md ankka.Metadata) *KeyValueResult[S] {
	return k.run(ctx, wire, input, md)
}

// Query runs the read-only handler wire; a handler that is not read-only fails the result.
func (k *KeyValueKit[S]) Query(ctx context.Context, wire string, input any) *KeyValueResult[S] {
	if ro, ok := k.e.Handlers[wire]; ok && !ro {
		return &KeyValueResult[S]{state: k.state, failure: fmt.Errorf("%s is a command, not a query", wire)}
	}
	return k.run(ctx, wire, input, nil)
}

func (k *KeyValueKit[S]) run(ctx context.Context, wire string, input any, md ankka.Metadata) *KeyValueResult[S] {
	r := &KeyValueResult[S]{state: k.state}
	if _, ok := k.e.Handlers[wire]; !ok {
		r.failure = fmt.Errorf("%s has no handler %q", k.e.ID, wire)
		return r
	}
	payload, err := k.e.Input(wire, input)
	if err != nil {
		r.failure = fmt.Errorf("the input cannot be encoded: %w", err)
		return r
	}
	ctx = k.ctx(ctx)
	if k.inst == nil {
		inst := k.e.New(k.id)
		init := &pb.KeyValueIn_Init{ComponentId: k.e.ID, EntityId: k.id}
		if err := server.Guard(func() error { return inst.Recover(ctx, init) }); err != nil {
			r.failure = fmt.Errorf("%s %s cannot recover: %w", k.e.ID, k.id, err)
			return r
		}
		k.inst = inst
	}
	k.next++
	cmd := &pb.KeyValueIn_Command{Id: k.next, Name: wire, Payload: payload, Metadata: metadataOf(md)}
	reply, err := server.GuardValue(func() (*pb.KeyValueOut_Reply, error) { return k.inst.Command(ctx, cmd) })
	if err != nil {
		r.failure = err
		return r
	}
	switch o := reply.GetOutcome().GetOutcome().(type) {
	case *pb.Outcome_Reply_:
		v, err := k.e.Reply(wire, o.Reply.GetPayload())
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
		k.state, k.inst = k.empty(), nil
	case reply.GetNewState() != nil:
		s, err := k.e.State(reply.GetNewState())
		if err != nil {
			r.failure = fmt.Errorf("the state cannot be read back: %w", err)
			return r
		}
		k.state = s.(S)
	}
	r.state = k.state
	return r
}

// ---- workflows ----

// StepRef is a step a workflow moves to, and its input encoded.
type StepRef struct {
	Step  string
	input *pb.Payload
}

// Pause is where a paused workflow waits: for a command, or until After passes, when OnTimeout runs.
type Pause struct {
	After     time.Duration
	OnTimeout *StepRef
}

// Progress is where a workflow stands: a step pending, paused, or ended (Failed says why it
// failed, when it did).
type Progress struct {
	Pending *StepRef
	Paused  *Pause
	Ended   bool
	Failed  *ankka.RefusalError
}

// WorkflowResult is what one command did to a workflow.
type WorkflowResult[S any] struct {
	reply      any
	replied    bool
	state      S
	refusal    *ankka.RefusalError
	failure    error
	transition *StepRef
}

// Reply is the reply, decoded by the command's reply codec, or nil.
func (r *WorkflowResult[S]) Reply() any { return r.reply }

// Replied is whether the command replied.
func (r *WorkflowResult[S]) Replied() bool { return r.replied }

// State is the state after the command.
func (r *WorkflowResult[S]) State() S { return r.state }

// Refusal is the command's refusal, or nil.
func (r *WorkflowResult[S]) Refusal() *ankka.RefusalError { return r.refusal }

// Failure is why the command failed rather than answered, or nil.
func (r *WorkflowResult[S]) Failure() error { return r.failure }

// Transition is the step the command moved the workflow to, or nil.
func (r *WorkflowResult[S]) Transition() *StepRef { return r.transition }

// WorkflowKit drives one workflow instance: its commands, and its steps as the engine runs them,
// following transitions until the workflow pauses or ends.
type WorkflowKit[S any] struct {
	*hosted
	w        *kit.Workflow
	id       string
	inst     server.WorkflowInstance
	next     int64
	state    S
	progress Progress
	// Steps are the steps run so far, in order.
	Steps []string
}

// Workflow is a kit for the workflow w. It panics, naming each problem, when the workflow has
// problems a service would refuse to start with.
func Workflow[S any](w *ankka.WorkflowComponent[S], opts ...Option) *WorkflowKit[S] {
	o := settings(opts)
	h := host(w, o)
	k := &WorkflowKit[S]{hosted: h, w: h.view.Workflows[w.ID()], id: o.entityID}
	p, err := k.w.Empty(k.id)
	if err == nil {
		var s any
		if s, err = k.w.State(p); err == nil {
			k.state = s.(S)
		}
	}
	if err != nil {
		panic(fmt.Sprintf("testkit: the empty state of %s: %v", k.w.ID, err))
	}
	return k
}

// State is the workflow's state.
func (k *WorkflowKit[S]) State() S { return k.state }

// Progress is where the workflow stands.
func (k *WorkflowKit[S]) Progress() Progress { return k.progress }

func (k *WorkflowKit[S]) instance(ctx context.Context) error {
	if k.inst != nil {
		return nil
	}
	inst := k.w.New(k.id)
	init := &pb.WorkflowIn_Init{ComponentId: k.w.ID, EntityId: k.id}
	if err := server.Guard(func() error { return inst.Recover(ctx, init) }); err != nil {
		return fmt.Errorf("%s %s cannot recover: %w", k.w.ID, k.id, err)
	}
	k.inst = inst
	return nil
}

func (k *WorkflowKit[S]) setState(p *pb.Payload) error {
	if p == nil {
		return nil
	}
	s, err := k.w.State(p)
	if err != nil {
		return fmt.Errorf("the state cannot be read back: %w", err)
	}
	k.state = s.(S)
	return nil
}

// Command runs the command wire with input. A transition it makes is pending until RunUntilPause.
func (k *WorkflowKit[S]) Command(ctx context.Context, wire string, input any) *WorkflowResult[S] {
	r := &WorkflowResult[S]{state: k.state}
	if _, ok := k.w.Handlers[wire]; !ok {
		r.failure = fmt.Errorf("%s has no command %q", k.w.ID, wire)
		return r
	}
	payload, err := k.w.Input(wire, input)
	if err != nil {
		r.failure = fmt.Errorf("the input cannot be encoded: %w", err)
		return r
	}
	ctx = k.ctx(ctx)
	if r.failure = k.instance(ctx); r.failure != nil {
		return r
	}
	k.next++
	cmd := &pb.WorkflowIn_Command{Id: k.next, Name: wire, Payload: payload, Metadata: &pb.Metadata{}}
	reply, err := server.GuardValue(func() (*pb.WorkflowOut_Reply, error) { return k.inst.Command(ctx, cmd) })
	if err != nil {
		r.failure = err
		return r
	}
	switch o := reply.GetOutcome().GetOutcome().(type) {
	case *pb.Outcome_Reply_:
		v, err := k.w.Reply(wire, o.Reply.GetPayload())
		if err != nil {
			r.failure = fmt.Errorf("the reply cannot be read back: %w", err)
			return r
		}
		r.reply, r.replied = v, true
	case *pb.Outcome_Error:
		r.refusal = refusalOf(o.Error)
		return r
	}
	if r.failure = k.setState(reply.GetNewState()); r.failure != nil {
		return r
	}
	if t := reply.GetTransition(); t != nil {
		r.transition = &StepRef{Step: t.GetStep(), input: t.GetInput()}
		k.progress = Progress{Pending: r.transition}
	}
	r.state = k.state
	return r
}

// RunStep runs the pending step, as the engine would, and answers where the workflow stands.
func (k *WorkflowKit[S]) RunStep(ctx context.Context) (Progress, error) {
	ref := k.progress.Pending
	if ref == nil {
		return k.progress, errors.New("the workflow has no step pending")
	}
	return k.runStep(ctx, ref)
}

func (k *WorkflowKit[S]) runStep(ctx context.Context, ref *StepRef) (Progress, error) {
	ctx = k.ctx(ctx)
	if err := k.instance(ctx); err != nil {
		return k.progress, err
	}
	k.next++
	run := &pb.WorkflowIn_RunStep{Id: k.next, Step: ref.Step, Input: ref.input, Metadata: &pb.Metadata{}}
	reply, err := server.GuardValue(func() (*pb.WorkflowOut_StepReply, error) { return k.inst.Step(ctx, run) })
	if err != nil {
		return k.progress, fmt.Errorf("the step %s failed: %w", ref.Step, err)
	}
	k.Steps = append(k.Steps, ref.Step)
	if err := k.setState(reply.GetNewState()); err != nil {
		return k.progress, err
	}
	switch n := reply.GetNext().GetOutcome().(type) {
	case *pb.StepOutcome_TransitionTo:
		k.progress = Progress{Pending: &StepRef{Step: n.TransitionTo.GetStep(), input: n.TransitionTo.GetInput()}}
	case *pb.StepOutcome_Pause_:
		p := &Pause{}
		if n.Pause.AfterMillis != nil {
			p.After = time.Duration(n.Pause.GetAfterMillis()) * time.Millisecond
		}
		if t := n.Pause.GetOnTimeout(); t != nil {
			p.OnTimeout = &StepRef{Step: t.GetStep(), input: t.GetInput()}
		}
		k.progress = Progress{Paused: p}
	case *pb.StepOutcome_End_:
		k.progress = Progress{Ended: true}
	case *pb.StepOutcome_Fail:
		k.progress = Progress{Ended: true, Failed: refusalOf(n.Fail)}
	default:
		return k.progress, fmt.Errorf("the step %s answered no outcome", ref.Step)
	}
	return k.progress, nil
}

// RunUntilPause follows transitions until the workflow pauses or ends, at most limit steps.
func (k *WorkflowKit[S]) RunUntilPause(ctx context.Context, limit int) (Progress, error) {
	for i := 0; i < limit && k.progress.Pending != nil; i++ {
		if _, err := k.runStep(ctx, k.progress.Pending); err != nil {
			return k.progress, err
		}
	}
	if k.progress.Pending != nil {
		return k.progress, fmt.Errorf("the workflow did not pause or end within %d steps", limit)
	}
	return k.progress, nil
}

// Resume is what the engine does when a pause's time passes: it runs the pause's OnTimeout step,
// then follows transitions until the workflow pauses or ends.
func (k *WorkflowKit[S]) Resume(ctx context.Context) (Progress, error) {
	p := k.progress.Paused
	if p == nil {
		return k.progress, errors.New("the workflow is not paused")
	}
	if p.OnTimeout == nil {
		return k.progress, errors.New("the pause has no timeout step; a command must move the workflow on")
	}
	if _, err := k.runStep(ctx, p.OnTimeout); err != nil {
		return k.progress, err
	}
	return k.RunUntilPause(ctx, 100)
}

// ---- views ----

// ViewChange is what a view did with a change: the row written (Updated), the row deleted, or
// nothing.
type ViewChange[Row any] struct {
	Updated *Row
	Deleted bool
	Ignored bool
}

// ViewKit feeds a view changes by source instance and keeps its rows as the sidecar would.
type ViewKit[Row any] struct {
	*hosted
	v    *kit.View
	rows map[string]Row
	next int64
}

// View is a kit for the view v.
func View[Row any](v *ankka.ViewComponent[Row], opts ...Option) *ViewKit[Row] {
	h := host(v, settings(opts))
	return &ViewKit[Row]{hosted: h, v: h.view.Views[v.ID()], rows: map[string]Row{}}
}

// Row is the row of source instance subject, and whether there is one.
func (k *ViewKit[Row]) Row(subject string) (Row, bool) {
	r, ok := k.rows[subject]
	return r, ok
}

// Rows are every row, by source instance.
func (k *ViewKit[Row]) Rows() map[string]Row { return k.rows }

// OnChange hands the view a change from source instance subject, encoded by the source's codec.
func (k *ViewKit[Row]) OnChange(ctx context.Context, subject string, value any, opts ...ChangeOption) (ViewChange[Row], error) {
	p, err := k.v.Change("", value)
	if err != nil {
		return ViewChange[Row]{}, err
	}
	return k.apply(ctx, subject, p, false, opts)
}

// OnDelete tells the view source instance subject was deleted.
func (k *ViewKit[Row]) OnDelete(ctx context.Context, subject string, opts ...ChangeOption) (ViewChange[Row], error) {
	return k.apply(ctx, subject, nil, true, opts)
}

func (k *ViewKit[Row]) apply(ctx context.Context, subject string, event *pb.Payload, deleted bool, opts []ChangeOption) (ViewChange[Row], error) {
	c := changeOf(&k.next, opts)
	req := &pb.ViewRequest{ComponentId: k.v.ID, Event: event, Deleted: deleted, Metadata: changeMetadata(subject, c.sequence, c.md), Standing: c.standing}
	if cur, ok := k.rows[subject]; ok {
		p, err := k.v.EncodeRow(cur)
		if err != nil {
			return ViewChange[Row]{}, err
		}
		req.Row = p
	}
	eff, err := server.GuardValue(func() (*pb.ViewEffect, error) { return k.v.Handle(k.ctx(ctx), req) })
	if err != nil {
		return ViewChange[Row]{}, err
	}
	switch e := eff.GetEffect().(type) {
	case *pb.ViewEffect_UpdateRow:
		r, err := k.v.Row(e.UpdateRow)
		if err != nil {
			return ViewChange[Row]{}, fmt.Errorf("the row cannot be read back: %w", err)
		}
		row := r.(Row)
		k.rows[subject] = row
		return ViewChange[Row]{Updated: &row}, nil
	case *pb.ViewEffect_DeleteRow:
		delete(k.rows, subject)
		return ViewChange[Row]{Deleted: true}, nil
	}
	return ViewChange[Row]{Ignored: true}, nil
}

// KeyedViewKit feeds a keyed view changes of the entities it reads and keeps its rows by key, a
// later write to a key winning, as the runtime writes them.
//
// A declared query is SQL and there is no database to run it, so the test says what each query
// answers with Answering; a handler that asks one the test has not answered fails, naming it.
type KeyedViewKit[Row any] struct {
	*hosted
	v       *kit.View
	rows    map[string]Row
	answers map[string]func(values map[string]string) []Row
	next    int64
}

// KeyedView is a kit for the keyed view v.
func KeyedView[Row any](v *ankka.KeyedViewComponent[Row], opts ...Option) *KeyedViewKit[Row] {
	h := host(v, settings(opts))
	return &KeyedViewKit[Row]{hosted: h, v: h.view.Views[v.ID()], rows: map[string]Row{}, answers: map[string]func(map[string]string) []Row{}}
}

// Answering says what the declared query name answers, given the values it is asked with.
func (k *KeyedViewKit[Row]) Answering(name string, answer func(values map[string]string) []Row) *KeyedViewKit[Row] {
	k.answers[name] = answer
	return k
}

// Row is the row under key, and whether there is one.
func (k *KeyedViewKit[Row]) Row(key string) (Row, bool) {
	r, ok := k.rows[key]
	return r, ok
}

// Rows are every row, by key.
func (k *KeyedViewKit[Row]) Rows() map[string]Row { return k.rows }

// Change hands the view a change of instance subject of the entity sourceID, encoded by that
// source's codec. It answers the keys written and deleted.
func (k *KeyedViewKit[Row]) Change(ctx context.Context, sourceID, subject string, value any, opts ...ChangeOption) (written, deleted []string, err error) {
	p, err := k.v.Change(sourceID, value)
	if err != nil {
		return nil, nil, err
	}
	return k.apply(ctx, sourceID, subject, p, false, opts)
}

// Deleted tells the view instance subject of the entity sourceID was deleted.
func (k *KeyedViewKit[Row]) Deleted(ctx context.Context, sourceID, subject string, opts ...ChangeOption) (written, deleted []string, err error) {
	return k.apply(ctx, sourceID, subject, nil, true, opts)
}

func (k *KeyedViewKit[Row]) apply(ctx context.Context, sourceID, subject string, event *pb.Payload, del bool, opts []ChangeOption) ([]string, []string, error) {
	c := changeOf(&k.next, opts)
	src := sourceID
	req := &pb.ViewRequest{ComponentId: k.v.ID, Event: event, Deleted: del, SourceId: &src, Metadata: changeMetadata(subject, c.sequence, c.md), Standing: c.standing}
	get := func(key string) (*pb.Payload, bool) {
		r, ok := k.rows[key]
		if !ok {
			return nil, false
		}
		p, err := k.v.EncodeRow(r)
		return p, err == nil
	}
	ask := func(name string, values map[string]string) ([]*pb.Payload, error) {
		answer, ok := k.answers[name]
		if !ok {
			return nil, fmt.Errorf("%s asked the query %q, and the test has not said what it answers; say so with Answering(%q, ...)", k.v.ID, name, name)
		}
		var out []*pb.Payload
		for _, r := range answer(values) {
			p, err := k.v.EncodeRow(r)
			if err != nil {
				return nil, err
			}
			out = append(out, p)
		}
		return out, nil
	}
	ctx = k.v.WithRows(k.ctx(ctx), get, ask)
	eff, err := server.GuardValue(func() (*pb.ViewEffect, error) { return k.v.Handle(ctx, req) })
	if err != nil {
		return nil, nil, err
	}
	var written, deleted []string
	for _, ch := range eff.GetRows().GetChanges() {
		switch x := ch.GetChange().(type) {
		case *pb.RowChange_Upsert:
			r, err := k.v.Row(x.Upsert)
			if err != nil {
				return nil, nil, fmt.Errorf("the row %s cannot be read back: %w", ch.GetKey(), err)
			}
			k.rows[ch.GetKey()] = r.(Row)
			written = append(written, ch.GetKey())
		case *pb.RowChange_Delete:
			delete(k.rows, ch.GetKey())
			deleted = append(deleted, ch.GetKey())
		}
	}
	return written, deleted, nil
}

// ---- consumers ----

// Published is one message a consumer published: its payload decoded by its ProducesTo codec, its
// metadata and its record key (the change's subject, unless the message named one).
type Published struct {
	Payload  any
	Metadata ankka.Metadata
	Key      string
}

// ConsumerKit feeds a consumer messages and keeps what it published.
type ConsumerKit[Msg any] struct {
	*hosted
	c         *kit.Consumer
	published []Published
	next      int64
}

// Consumer is a kit for the consumer c.
func Consumer[Msg any](c *ankka.ConsumerComponent[Msg], opts ...Option) *ConsumerKit[Msg] {
	h := host(c, settings(opts))
	return &ConsumerKit[Msg]{hosted: h, c: h.view.Consumers[c.ID()]}
}

// Published is everything the consumer published, in order.
func (k *ConsumerKit[Msg]) Published() []Published { return k.published }

// OnMessage hands the consumer a message from source instance subject. It answers what this message
// published.
func (k *ConsumerKit[Msg]) OnMessage(ctx context.Context, subject string, msg Msg, opts ...ChangeOption) ([]Published, error) {
	p, err := k.c.Message(msg)
	if err != nil {
		return nil, err
	}
	return k.apply(ctx, subject, p, false, opts)
}

// OnDelete tells the consumer source instance subject was deleted.
func (k *ConsumerKit[Msg]) OnDelete(ctx context.Context, subject string, opts ...ChangeOption) ([]Published, error) {
	return k.apply(ctx, subject, nil, true, opts)
}

func (k *ConsumerKit[Msg]) apply(ctx context.Context, subject string, msg *pb.Payload, deleted bool, opts []ChangeOption) ([]Published, error) {
	c := changeOf(&k.next, opts)
	req := &pb.ConsumerRequest{ComponentId: k.c.ID, Message: msg, Deleted: deleted, Metadata: changeMetadata(subject, c.sequence, c.md), Standing: c.standing}
	eff, err := server.GuardValue(func() (*pb.ConsumerEffect, error) { return k.c.Handle(k.ctx(ctx), req) })
	if err != nil {
		return nil, err
	}
	var out []Published
	add := func(p *pb.Payload, md *pb.Metadata, key *string) error {
		v, err := k.c.Out(p)
		if err != nil {
			return err
		}
		pub := Published{Payload: v, Metadata: metadataFrom(md), Key: subject}
		if key != nil {
			pub.Key = *key
		}
		out = append(out, pub)
		return nil
	}
	switch e := eff.GetEffect().(type) {
	case *pb.ConsumerEffect_Produce_:
		if err := add(e.Produce.GetPayload(), e.Produce.GetMetadata(), nil); err != nil {
			return nil, err
		}
	case *pb.ConsumerEffect_ProduceAll_:
		for _, m := range e.ProduceAll.GetMessages() {
			if err := add(m.GetPayload(), m.GetMetadata(), m.Key); err != nil {
				return nil, err
			}
		}
	}
	k.published = append(k.published, out...)
	return out, nil
}

// GraphConsumerKit feeds a graph consumer changes and answers the elements it published, each
// read back from the bytes that would be published, under the key it would be published under.
type GraphConsumerKit[Msg any] struct {
	*hosted
	c        *kit.Consumer
	elements []ankka.Delta
	next     int64
}

// GraphConsumer is a kit for the graph consumer c.
func GraphConsumer[Msg any](c *ankka.ConsumerComponent[Msg], opts ...Option) *GraphConsumerKit[Msg] {
	h := host(c, settings(opts))
	k := &GraphConsumerKit[Msg]{hosted: h, c: h.view.Consumers[c.ID()]}
	if !k.c.Graph {
		panic(fmt.Sprintf("testkit: %s is not a graph consumer; use testkit.Consumer", c.ID()))
	}
	return k
}

// Elements are every element published so far, in order.
func (k *GraphConsumerKit[Msg]) Elements() []ankka.Delta { return k.elements }

// OnMessage hands the graph consumer a change from source instance subject; it answers the
// elements this change published.
func (k *GraphConsumerKit[Msg]) OnMessage(ctx context.Context, subject string, msg Msg, opts ...ChangeOption) ([]ankka.Delta, error) {
	p, err := k.c.Message(msg)
	if err != nil {
		return nil, err
	}
	return k.apply(ctx, subject, p, false, opts)
}

// OnDelete tells the graph consumer source instance subject was deleted.
func (k *GraphConsumerKit[Msg]) OnDelete(ctx context.Context, subject string, opts ...ChangeOption) ([]ankka.Delta, error) {
	return k.apply(ctx, subject, nil, true, opts)
}

func (k *GraphConsumerKit[Msg]) apply(ctx context.Context, subject string, msg *pb.Payload, deleted bool, opts []ChangeOption) ([]ankka.Delta, error) {
	c := changeOf(&k.next, opts)
	req := &pb.ConsumerRequest{ComponentId: k.c.ID, Message: msg, Deleted: deleted, Metadata: changeMetadata(subject, c.sequence, c.md), Standing: c.standing}
	eff, err := server.GuardValue(func() (*pb.ConsumerEffect, error) { return k.c.Handle(k.ctx(ctx), req) })
	if err != nil {
		return nil, err
	}
	var out []ankka.Delta
	for _, m := range eff.GetProduceAll().GetMessages() {
		d, err := ankka.ReadDelta(m.GetPayload().GetData(), m.GetKey())
		if err != nil {
			return nil, fmt.Errorf("a published delta cannot be read back: %w", err)
		}
		out = append(out, d)
	}
	k.elements = append(k.elements, out...)
	return out, nil
}

// ---- timed actions ----

// TimedActionKit calls a timed action's handlers as the timer sweeper would.
type TimedActionKit struct {
	*hosted
	id string
}

// TimedAction is a kit for the timed action t.
func TimedAction(t *ankka.TimedActionComponent, opts ...Option) *TimedActionKit {
	return &TimedActionKit{hosted: host(t, settings(opts)), id: t.ID()}
}

// Invoke calls the handler wire with input as the timer named "test" would, on its first attempt;
// md overrides that (ankka.timer, ankka.due, ankka.attempts). A failed call is a *RefusalError.
func (k *TimedActionKit) Invoke(ctx context.Context, wire string, input any, md ...ankka.Metadata) error {
	p, err := k.view.Input(k.id, wire, input)
	if err != nil {
		return err
	}
	all := ankka.Metadata{"ankka.timer": "test", "ankka.attempts": "1", "ankka.due": time.Now().UTC().Format(time.RFC3339Nano)}
	for _, m := range md {
		for key, v := range m {
			all[key] = v
		}
	}
	req := &pb.TimedActionRequest{ComponentId: k.id, Name: wire, Payload: p, Metadata: metadataOf(all)}
	eff, err := server.GuardValue(func() (*pb.TimedActionEffect, error) { return k.view.TimedActions[k.id](k.ctx(ctx), req) })
	if err != nil {
		return err
	}
	if f := eff.GetFail(); f != nil {
		return refusalOf(f)
	}
	return nil
}
