package ankka

import (
	"context"
	"fmt"
	"reflect"
	"sync"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/kit"
	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"github.com/thinkmorestupidless/ankka-go/internal/server"
)

// WorkflowComponent is a workflow's declaration: its id, its empty state, its steps and its
// commands. The sidecar's engine journals every transition and runs a step only once its
// transition is journalled; this process holds the state and runs the handlers.
type WorkflowComponent[S any] struct {
	id         string
	empty      func(workflowID string) S
	commands   []wfCommand[S]
	queries    []stateQuery[S]
	handlers   []handlerPart
	steps      []wfStep[S]
	settings   workflowSettings
	stateCodec Codec[S]
	misplaced  []Part
}

// wfCommand is a command of a workflow of state S.
type wfCommand[S any] interface {
	handlerPart
	runWorkflowCommand(ctx context.Context, state S, payload *pb.Payload, sc Codec[S], enc stepInputEncoder) (S, *pb.WorkflowOut_Reply, error)
}

// wfStep is a step of a workflow of state S.
type wfStep[S any] interface {
	Part
	stepName() string
	stepProblems() []string
	encodeStepInput(v any) (*pb.Payload, error)
	runStep(ctx context.Context, state S, payload *pb.Payload) (StepEffect[S], error)
}

// Workflow declares a workflow. empty is the state of a workflow not yet started, given its id.
// The parts are its steps (Step), its commands (WorkflowCommand, Query) and its settings
// (WorkflowTimeout, StepTimeout, StepRecovery, ForStep, StateCodec).
func Workflow[S any](id string, empty func(workflowID string) S, parts ...Part) *WorkflowComponent[S] {
	w := &WorkflowComponent[S]{id: id, empty: empty, stateCodec: CodecFor[S]()}
	for _, p := range parts {
		switch p := p.(type) {
		case wfStep[S]:
			w.steps = append(w.steps, p)
		case wfCommand[S]:
			w.commands = append(w.commands, p)
			w.handlers = append(w.handlers, p)
		case stateQuery[S]:
			w.queries = append(w.queries, p)
			w.handlers = append(w.handlers, p)
		case WorkflowOption:
			p.apply(&w.settings.workflowLevel)
		case workflowTimeoutPart:
			d := p.d
			w.settings.overall = &d
		case forStepPart:
			w.settings.perStep = append(w.settings.perStep, p)
		case stateCodecPart[S]:
			w.stateCodec = p.codec
		default:
			w.misplaced = append(w.misplaced, p)
		}
	}
	return w
}

// ID is the workflow's component id.
func (w *WorkflowComponent[S]) ID() string { return w.id }

// ---- Settings ----

// Recovery is what the engine does when a step fails (returns an error, panics or times out): try
// it again up to MaxRetries times, then run FailoverTo (a declared step, with no input) or, with
// none named, fail the workflow.
type Recovery struct {
	MaxRetries int
	FailoverTo string
}

type stepLimits struct {
	timeout  *time.Duration
	recovery *Recovery
}

type workflowSettings struct {
	workflowLevel stepLimits
	overall       *time.Duration
	perStep       []forStepPart
}

// WorkflowOption is a setting of a workflow, or of one step through ForStep.
type WorkflowOption struct {
	apply func(*stepLimits)
}

func (WorkflowOption) part() {}

// StepTimeout bounds how long a step may run before it counts as failed; 30 seconds when unsaid.
func StepTimeout(d time.Duration) WorkflowOption {
	return WorkflowOption{apply: func(l *stepLimits) { l.timeout = &d }}
}

// StepRecovery is what the engine does with a failed step; with none, the workflow fails.
func StepRecovery(r Recovery) WorkflowOption {
	return WorkflowOption{apply: func(l *stepLimits) { l.recovery = &r }}
}

// WorkflowTimeout is a ceiling on the whole workflow, from its start. It is a workflow's setting
// only, not a step's.
func WorkflowTimeout(d time.Duration) Part { return workflowTimeoutPart{d: d} }

type workflowTimeoutPart struct{ d time.Duration }

func (workflowTimeoutPart) part() {}

type forStepPart struct {
	step string
	opts []WorkflowOption
}

func (forStepPart) part() {}

// ForStep gives one step its own timeout or recovery, in place of the workflow's.
func ForStep(step string, opts ...WorkflowOption) Part { return forStepPart{step: step, opts: opts} }

// ---- Handlers ----

// stepPart is a step of a workflow.
type stepPart[S, I any] struct {
	name string
	in   Codec[I]
	f    func(context.Context, S, I) (StepEffect[S], error)
}

// Step is a step named name of a workflow: given the state and its input (Unit for a step that
// takes none), it answers what the state becomes and what happens next. An error or a panic is a
// fault, which the engine retries and fails over as the workflow's settings say; ThenFail is a
// decision, which ends the workflow.
func Step[S, I any](name string, f func(ctx context.Context, state S, input I) (StepEffect[S], error)) Part {
	return &stepPart[S, I]{name: name, in: CodecFor[I](), f: f}
}

func (s *stepPart[S, I]) part()            {}
func (s *stepPart[S, I]) stepName() string { return s.name }

func (s *stepPart[S, I]) stepProblems() []string {
	var out []string
	for _, p := range CodecProblems(s.in) {
		out = append(out, fmt.Sprintf("its input, a %s: %s", reflect.TypeFor[I](), p))
	}
	return out
}

func (s *stepPart[S, I]) takesNoInput() bool { return reflect.TypeFor[I]() == reflect.TypeFor[Unit]() }

func (s *stepPart[S, I]) encodeStepInput(v any) (*pb.Payload, error) {
	if v == nil {
		if s.takesNoInput() {
			return nil, nil
		}
		return nil, fmt.Errorf("the step %s takes a %s, and the transition gives none", s.name, reflect.TypeFor[I]())
	}
	in, ok := v.(I)
	if !ok {
		return nil, fmt.Errorf("the step %s takes a %s, not a %T", s.name, reflect.TypeFor[I](), v)
	}
	if s.takesNoInput() {
		return nil, nil
	}
	return payloadOf(s.in, in)
}

func (s *stepPart[S, I]) runStep(ctx context.Context, state S, payload *pb.Payload) (StepEffect[S], error) {
	var in I
	if payload != nil && !s.takesNoInput() {
		v, err := valueOf(s.in, payload)
		if err != nil {
			return StepEffect[S]{}, fmt.Errorf("the input to the step %s cannot be read as a %s: %w", s.name, reflect.TypeFor[I](), err)
		}
		in = v
	} else if payload == nil && !s.takesNoInput() {
		return StepEffect[S]{}, fmt.Errorf("the step %s takes a %s and was given none", s.name, reflect.TypeFor[I]())
	}
	return s.f(ctx, state, in)
}

// workflowCommandPart is a command of a workflow.
type workflowCommandPart[S, I, R any] struct {
	handlerBase[I, R]
	f func(context.Context, S, I) (WorkflowEffect[S, R], error)
}

// WorkflowCommand is a handler named wire of a workflow: given the state and its input, it
// answers an effect, recording a state, starting a step, and replying; or a refusal (Refuse) as its
// error. A command is answered while a step runs, from the state before the step. Secrets and other
// services are a step's, never a command's.
func WorkflowCommand[S, I, R any](wire string, f func(ctx context.Context, state S, input I) (WorkflowEffect[S, R], error)) Part {
	return &workflowCommandPart[S, I, R]{handlerBase: newBase[I, R](wire), f: f}
}

func (c *workflowCommandPart[S, I, R]) readOnly() bool { return false }

func (c *workflowCommandPart[S, I, R]) runWorkflowCommand(ctx context.Context, state S, payload *pb.Payload, sc Codec[S], enc stepInputEncoder) (S, *pb.WorkflowOut_Reply, error) {
	in, err := c.decode(payload)
	if err != nil {
		return state, nil, err
	}
	eff, err := c.f(ctx, state, in)
	if err != nil {
		return state, nil, err
	}
	return materialiseWorkflowCommand(eff, state, sc, c.out, enc)
}

// ---- Declaration ----

func (w *WorkflowComponent[S]) stepNamed(name string) wfStep[S] {
	for _, s := range w.steps {
		if s.stepName() == name {
			return s
		}
	}
	return nil
}

// encodeStepInput encodes a transition's input by the codec the target step declares.
func (w *WorkflowComponent[S]) encodeStepInput(step string, input any) (*pb.Payload, error) {
	s := w.stepNamed(step)
	if s == nil {
		return nil, fmt.Errorf("a transition to %q, which is not a step of %s", step, w.id)
	}
	return s.encodeStepInput(input)
}

func limitsToPb(l stepLimits) (*int64, *pb.WorkflowDetail_Recovery) {
	var timeout *int64
	if l.timeout != nil {
		ms := l.timeout.Milliseconds()
		timeout = &ms
	}
	var rec *pb.WorkflowDetail_Recovery
	if l.recovery != nil {
		rec = &pb.WorkflowDetail_Recovery{MaxRetries: int32(l.recovery.MaxRetries)}
		if l.recovery.FailoverTo != "" {
			f := l.recovery.FailoverTo
			rec.FailoverTo = &f
		}
	}
	return timeout, rec
}

func (w *WorkflowComponent[S]) settingsToPb() *pb.WorkflowDetail_Settings {
	s := w.settings
	if s.overall == nil && s.workflowLevel.timeout == nil && s.workflowLevel.recovery == nil && len(s.perStep) == 0 {
		return nil
	}
	out := &pb.WorkflowDetail_Settings{}
	if s.overall != nil {
		ms := s.overall.Milliseconds()
		out.TimeoutMillis = &ms
	}
	out.DefaultStepTimeoutMillis, out.DefaultRecovery = limitsToPb(s.workflowLevel)
	for _, p := range s.perStep {
		var l stepLimits
		for _, o := range p.opts {
			if o.apply != nil {
				o.apply(&l)
			}
		}
		t, r := limitsToPb(l)
		out.Steps = append(out.Steps, &pb.WorkflowDetail_StepSettings{Step: p.step, TimeoutMillis: t, Recovery: r})
	}
	return out
}

func (w *WorkflowComponent[S]) declare(d *declarations) {
	where := w.id
	if w.id == "" {
		where = fmt.Sprintf("workflow of %s", reflect.TypeFor[S]())
		d.problem(where, "a workflow has an id")
	}
	if w.empty == nil {
		d.problem(where, "a workflow is given its empty state")
	}
	if len(w.steps) == 0 {
		d.problem(where, "a workflow has at least one step")
	}
	seen := map[string]int{}
	var names []string
	for _, s := range w.steps {
		if s.stepName() == "" {
			d.problem(where, "a step has no name")
		}
		if seen[s.stepName()] == 0 {
			names = append(names, s.stepName())
		}
		seen[s.stepName()]++
		for _, p := range s.stepProblems() {
			d.problem(where+"."+s.stepName(), "%s", p)
		}
	}
	for _, n := range sortedKeys(seen) {
		if n != "" && seen[n] > 1 {
			d.problem(where, "%d steps share the name %q; a name names one step", seen[n], n)
		}
	}
	checkRecovery := func(at string, r *Recovery) {
		if r == nil {
			return
		}
		if r.MaxRetries < 0 {
			d.problem(at, "a recovery retries 0 times or more, not %d", r.MaxRetries)
		}
		if r.FailoverTo != "" && seen[r.FailoverTo] == 0 {
			d.problem(at, "the recovery fails over to %q, which is not a step of %s", r.FailoverTo, w.id)
		}
	}
	checkTimeout := func(at string, t *time.Duration) {
		if t != nil && *t <= 0 {
			d.problem(at, "a timeout is more than zero, not %s", *t)
		}
	}
	checkTimeout(where, w.settings.workflowLevel.timeout)
	checkTimeout(where, w.settings.overall)
	checkRecovery(where, w.settings.workflowLevel.recovery)
	for _, p := range w.settings.perStep {
		at := where + "." + p.step
		if seen[p.step] == 0 {
			d.problem(where, "ForStep names %q, which is not a step of %s", p.step, w.id)
		}
		var l stepLimits
		for _, o := range p.opts {
			if o.apply != nil {
				o.apply(&l)
			}
		}
		checkTimeout(at, l.timeout)
		checkRecovery(at, l.recovery)
	}
	if len(w.handlers) == 0 {
		d.problem(where, "a workflow has at least one command, to start it")
	}
	for _, p := range CodecProblems(w.stateCodec) {
		d.problem(where, "its state, a %s: %s", reflect.TypeFor[S](), p)
	}
	for _, p := range w.misplaced {
		misplaced(d, where, "workflow", p)
	}
	handlerProblems(d, where, w.handlers)
	for _, h := range w.handlers {
		h.declareInput(d, w.id)
	}
	d.components = append(d.components, &pb.Component{
		Kind:     pb.Kind_WORKFLOW,
		Id:       w.id,
		Handlers: handlersOf(w.handlers),
		Detail:   &pb.Component_Workflow{Workflow: &pb.WorkflowDetail{Steps: names, Settings: w.settingsToPb()}},
	})
	d.workflows[w.id] = func(workflowID string) server.WorkflowInstance { return w.instance(workflowID) }
	d.wfKits[w.id] = w.kitView()
}

// ---- The instance ----

// wfInstance is one loaded workflow. A command and a step may run at once; mu guards the state.
type wfInstance[S any] struct {
	w          *WorkflowComponent[S]
	workflowID string
	mu         sync.Mutex
	state      S
}

func (w *WorkflowComponent[S]) instance(workflowID string) *wfInstance[S] {
	return &wfInstance[S]{w: w, workflowID: workflowID}
}

func (i *wfInstance[S]) current() S {
	i.mu.Lock()
	defer i.mu.Unlock()
	return i.state
}

func (i *wfInstance[S]) Recover(_ context.Context, init *pb.WorkflowIn_Init) error {
	if p := init.GetState(); p != nil {
		s, err := valueOf(i.w.stateCodec, p)
		if err != nil {
			return fmt.Errorf("the state cannot be read as a %s: %w", reflect.TypeFor[S](), err)
		}
		i.state = s
		return nil
	}
	i.state = i.w.empty(i.workflowID)
	return nil
}

func (i *wfInstance[S]) Command(ctx context.Context, cmd *pb.WorkflowIn_Command) (*pb.WorkflowOut_Reply, error) {
	md := metadataFromPb(cmd.GetMetadata())
	ctx = withMetadata(withoutSecrets(ctx, "a workflow's command"), md)
	ctx = withCommand(ctx, CommandScope{ComponentID: i.w.id, EntityID: i.workflowID, Metadata: md, AtHome: true})
	state := i.current()
	for _, q := range i.w.queries {
		if q.wire() != cmd.GetName() {
			continue
		}
		o, err := q.runQuery(ctx, state, cmd.GetPayload())
		if err != nil {
			if isRefusal(err) {
				return &pb.WorkflowOut_Reply{Outcome: refusedOutcome(err)}, nil
			}
			return nil, err
		}
		return &pb.WorkflowOut_Reply{Outcome: o}, nil
	}
	for _, c := range i.w.commands {
		if c.wire() != cmd.GetName() {
			continue
		}
		after, reply, err := c.runWorkflowCommand(ctx, state, cmd.GetPayload(), i.w.stateCodec, i.w.encodeStepInput)
		if err != nil {
			if isRefusal(err) {
				return &pb.WorkflowOut_Reply{Outcome: refusedOutcome(err)}, nil
			}
			return nil, err
		}
		i.mu.Lock()
		i.state = after
		i.mu.Unlock()
		return reply, nil
	}
	return nil, fmt.Errorf("%s has no handler %q", i.w.id, cmd.GetName())
}

func (i *wfInstance[S]) Step(ctx context.Context, run *pb.WorkflowIn_RunStep) (*pb.WorkflowOut_StepReply, error) {
	s := i.w.stepNamed(run.GetStep())
	if s == nil {
		return nil, fmt.Errorf("%s has no step %q", i.w.id, run.GetStep())
	}
	md := metadataFromPb(run.GetMetadata())
	ctx = withMetadata(ctx, md)
	ctx = withCommand(ctx, CommandScope{ComponentID: i.w.id, EntityID: i.workflowID, Metadata: md, AtHome: true})
	state := i.current()
	eff, err := s.runStep(ctx, state, run.GetInput())
	if err != nil {
		return nil, err
	}
	after, reply, err := materialiseStep(eff, state, i.w.stateCodec, i.w.encodeStepInput)
	if err != nil {
		return nil, err
	}
	i.mu.Lock()
	i.state = after
	i.mu.Unlock()
	return reply, nil
}

// kitView is the workflow as a test kit drives it.
func (w *WorkflowComponent[S]) kitView() *kit.Workflow {
	handlers := map[string]bool{}
	byWire := map[string]handlerPart{}
	for _, h := range w.handlers {
		handlers[h.wire()] = h.readOnly()
		byWire[h.wire()] = h
	}
	return &kit.Workflow{
		ID:  w.id,
		New: func(workflowID string) server.WorkflowInstance { return w.instance(workflowID) },
		Input: func(wire string, v any) (*pb.Payload, error) {
			h, ok := byWire[wire]
			if !ok {
				return nil, fmt.Errorf("%s has no handler %q", w.id, wire)
			}
			return h.encodeInput(v)
		},
		Reply: func(wire string, p *pb.Payload) (any, error) {
			h, ok := byWire[wire]
			if !ok {
				return nil, fmt.Errorf("%s has no handler %q", w.id, wire)
			}
			return h.decodeReply(p)
		},
		State: func(p *pb.Payload) (any, error) { return valueOf(w.stateCodec, p) },
		Empty: func(workflowID string) (*pb.Payload, error) {
			return payloadOf(w.stateCodec, w.empty(workflowID))
		},
		Handlers: handlers,
	}
}
