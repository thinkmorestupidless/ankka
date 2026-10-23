package ankka

import (
	"fmt"
	"slices"
	"time"
)

// Effects are inert values: a handler returns a description of what should happen, and building one
// performs no I/O, reads no state and calls no model. The sidecar, or a test kit, interprets them.
//
// A refusal is not an effect here: a handler of any kind refuses by returning ankka.Refuse(code,
// message) as its error, and nothing it described is applied.

// Metadata is a request's or a reply's headers: trace and span ids, a source's id, a timer's name.
type Metadata map[string]string

type outcomeKind int

const (
	outcomeNone outcomeKind = iota // the zero effect: a handler that built nothing
	outcomeReply
	outcomeNoReply
)

// outcome is a reply computed from the state after the effect's changes, or no reply.
type outcome[S, R any] struct {
	kind     outcomeKind
	compute  func(S) R
	metadata Metadata
}

func replyWith[S, R any](compute func(S) R, md Metadata) outcome[S, R] {
	return outcome[S, R]{kind: outcomeReply, compute: compute, metadata: md}
}

func replyValue[S, R any](v R, md Metadata) outcome[S, R] {
	return replyWith(func(S) R { return v }, md)
}

func replyState[S, R any]() outcome[S, R] {
	return replyWith(func(s S) R {
		r, ok := any(s).(R)
		if !ok {
			panic(fmt.Sprintf("ThenReplyState replies with the state, a %T, but the handler's reply type is not the state's type", s))
		}
		return r
	}, nil)
}

// retention is what an entity asks the runtime to do with it after an effect.
type retention struct {
	deleteNow   bool
	expireAfter time.Duration // > 0: expire after this long
}

func (r retention) isSet() bool { return r.deleteNow || r.expireAfter > 0 }

// ReplicationChange is what a replicated entity's handler asks of its home beside its events (1.19):
// hand the home over, or change the replication set.
type ReplicationChange struct {
	HandOverTo string
	Add        []string
	Remove     []string
}

func (c *ReplicationChange) isHandOver() bool { return c != nil && c.HandOverTo != "" }

// ---- Event sourced entities ----

// Effect is what an event sourced entity's command returns: events to persist, then a reply computed
// from the state after them, or no reply. Built by Persist.
type Effect[S, E, R any] struct {
	events      []E
	retention   retention
	replication *ReplicationChange
	outcome     outcome[S, R]
	problem     string
}

// PersistBuilder is Persist's result, finished by ThenReply, ThenReplyValue, ThenReplyState or ThenNoReply.
type PersistBuilder[S, E, R any] struct {
	events      []E
	retention   retention
	replication *ReplicationChange
	problem     string
}

// Persist persists the events, in order; none persists nothing, which is how a command replies
// without changing anything.
func Persist[S, E, R any](events ...E) PersistBuilder[S, E, R] {
	return PersistBuilder[S, E, R]{events: slices.Clone(events)}
}

// DeleteEntity deletes the entity once the events are persisted.
func (b PersistBuilder[S, E, R]) DeleteEntity() PersistBuilder[S, E, R] {
	b.retention = retention{deleteNow: true}
	return b
}

// ExpireAfter deletes the entity d after the events are persisted, unless another command comes first.
func (b PersistBuilder[S, E, R]) ExpireAfter(d time.Duration) PersistBuilder[S, E, R] {
	b.retention = retention{expireAfter: d}
	return b
}

// HandOverTo hands a replicated entity's home to installation once the events are persisted; it
// must be in the entity's replication set.
func (b PersistBuilder[S, E, R]) HandOverTo(installation string) PersistBuilder[S, E, R] {
	if b.replication != nil {
		b.problem = "a command asks for one handover or one change to the replication set, not both"
		return b
	}
	b.replication = &ReplicationChange{HandOverTo: installation}
	return b
}

// ReplicateTo adds installation to a replicated entity's replication set.
func (b PersistBuilder[S, E, R]) ReplicateTo(installation string) PersistBuilder[S, E, R] {
	return b.setChange([]string{installation}, nil)
}

// StopReplicatingTo takes installation out of a replicated entity's replication set; it keeps its copy.
func (b PersistBuilder[S, E, R]) StopReplicatingTo(installation string) PersistBuilder[S, E, R] {
	return b.setChange(nil, []string{installation})
}

func (b PersistBuilder[S, E, R]) setChange(add, remove []string) PersistBuilder[S, E, R] {
	if b.replication.isHandOver() {
		b.problem = "a command asks for one handover or one change to the replication set, not both"
		return b
	}
	var cur ReplicationChange
	if b.replication != nil {
		cur = *b.replication
	}
	adding := sortedUnique(append(slices.Clone(cur.Add), add...))
	removing := sortedUnique(append(slices.Clone(cur.Remove), remove...))
	for _, a := range adding {
		if slices.Contains(removing, a) {
			b.problem = fmt.Sprintf("a command both adds and removes %s in the replication set", a)
		}
	}
	b.replication = &ReplicationChange{Add: adding, Remove: removing}
	return b
}

func sortedUnique(in []string) []string {
	out := slices.Clone(in)
	slices.Sort(out)
	return slices.Compact(out)
}

func (b PersistBuilder[S, E, R]) finish(o outcome[S, R]) Effect[S, E, R] {
	return Effect[S, E, R]{events: b.events, retention: b.retention, replication: b.replication, outcome: o, problem: b.problem}
}

// ThenReply replies with a value computed from the state after the events.
func (b PersistBuilder[S, E, R]) ThenReply(compute func(S) R) Effect[S, E, R] {
	return b.finish(replyWith(compute, nil))
}

// ThenReplyWithMetadata is ThenReply with headers on the reply.
func (b PersistBuilder[S, E, R]) ThenReplyWithMetadata(compute func(S) R, md Metadata) Effect[S, E, R] {
	return b.finish(replyWith(compute, md))
}

// ThenReplyValue replies with v.
func (b PersistBuilder[S, E, R]) ThenReplyValue(v R) Effect[S, E, R] {
	return b.finish(replyValue[S](v, nil))
}

// ThenReplyState replies with the state after the events; the reply type must be the state's type.
func (b PersistBuilder[S, E, R]) ThenReplyState() Effect[S, E, R] {
	return b.finish(replyState[S, R]())
}

// ThenNoReply answers nothing.
func (b PersistBuilder[S, E, R]) ThenNoReply() Effect[S, E, R] {
	return b.finish(outcome[S, R]{kind: outcomeNoReply})
}

// Events are the events the effect persists.
func (e Effect[S, E, R]) Events() []E { return e.events }

// ReadOnlyEffect is what a query returns, of any stateful kind: a reply or no reply, never a change.
// It is a type of its own so that a query declared with a persisting handler does not compile.
type ReadOnlyEffect[R any] struct {
	reply    bool
	value    R
	metadata Metadata
}

// Reply answers v without changing anything.
func Reply[R any](v R) ReadOnlyEffect[R] { return ReadOnlyEffect[R]{reply: true, value: v} }

// ReplyWithMetadata answers v with headers on the reply.
func ReplyWithMetadata[R any](v R, md Metadata) ReadOnlyEffect[R] {
	return ReadOnlyEffect[R]{reply: true, value: v, metadata: md}
}

// NoReply answers nothing.
func NoReply[R any]() ReadOnlyEffect[R] { return ReadOnlyEffect[R]{} }

// ---- Key value entities ----

// KeyValueEffect is what a key value entity's command returns: a new state (or none), then a reply.
type KeyValueEffect[S, R any] struct {
	update    bool
	state     S
	retention retention
	outcome   outcome[S, R]
}

// KeyValueBuilder is UpdateState's, KeepState's or DeleteState's result.
type KeyValueBuilder[S, R any] struct {
	update    bool
	state     S
	retention retention
}

// UpdateState replaces the state with s.
func UpdateState[S, R any](s S) KeyValueBuilder[S, R] {
	return KeyValueBuilder[S, R]{update: true, state: s}
}

// KeepState leaves the state as it is, for a command that only replies.
func KeepState[S, R any]() KeyValueBuilder[S, R] { return KeyValueBuilder[S, R]{} }

// DeleteState deletes the entity.
func DeleteState[S, R any]() KeyValueBuilder[S, R] {
	return KeyValueBuilder[S, R]{retention: retention{deleteNow: true}}
}

// DeleteEntity deletes the entity once the state is stored.
func (b KeyValueBuilder[S, R]) DeleteEntity() KeyValueBuilder[S, R] {
	b.retention = retention{deleteNow: true}
	return b
}

// ExpireAfter deletes the entity d after this command, unless another comes first.
func (b KeyValueBuilder[S, R]) ExpireAfter(d time.Duration) KeyValueBuilder[S, R] {
	b.retention = retention{expireAfter: d}
	return b
}

func (b KeyValueBuilder[S, R]) finish(o outcome[S, R]) KeyValueEffect[S, R] {
	return KeyValueEffect[S, R]{update: b.update, state: b.state, retention: b.retention, outcome: o}
}

// ThenReply replies with a value computed from the new state.
func (b KeyValueBuilder[S, R]) ThenReply(compute func(S) R) KeyValueEffect[S, R] {
	return b.finish(replyWith(compute, nil))
}

// ThenReplyValue replies with v.
func (b KeyValueBuilder[S, R]) ThenReplyValue(v R) KeyValueEffect[S, R] {
	return b.finish(replyValue[S](v, nil))
}

// ThenReplyState replies with the new state; the reply type must be the state's type.
func (b KeyValueBuilder[S, R]) ThenReplyState() KeyValueEffect[S, R] {
	return b.finish(replyState[S, R]())
}

// ThenNoReply answers nothing.
func (b KeyValueBuilder[S, R]) ThenNoReply() KeyValueEffect[S, R] {
	return b.finish(outcome[S, R]{kind: outcomeNoReply})
}

// ---- Workflows ----

// StepRef is a step to run, by wire name, with its input if it takes one.
type StepRef struct {
	Step  string
	Input any
}

// WorkflowEffect is what a workflow's command returns: a new state (or none), a step to start (or
// none), and a reply.
type WorkflowEffect[S, R any] struct {
	update     bool
	state      S
	transition *StepRef
	outcome    outcome[S, R]
}

// WorkflowBuilder is WorkflowUpdate's or WorkflowKeep's result.
type WorkflowBuilder[S, R any] struct {
	update     bool
	state      S
	transition *StepRef
}

// WorkflowUpdate records s as the workflow's state.
func WorkflowUpdate[S, R any](s S) WorkflowBuilder[S, R] {
	return WorkflowBuilder[S, R]{update: true, state: s}
}

// WorkflowKeep leaves the state as it is.
func WorkflowKeep[S, R any]() WorkflowBuilder[S, R] { return WorkflowBuilder[S, R]{} }

// ThenTransitionTo starts step, with input (nil for a step that takes none), once the state is recorded.
func (b WorkflowBuilder[S, R]) ThenTransitionTo(step string, input any) WorkflowBuilder[S, R] {
	b.transition = &StepRef{Step: step, Input: input}
	return b
}

func (b WorkflowBuilder[S, R]) finish(o outcome[S, R]) WorkflowEffect[S, R] {
	return WorkflowEffect[S, R]{update: b.update, state: b.state, transition: b.transition, outcome: o}
}

// ThenReply replies with a value computed from the state after the command.
func (b WorkflowBuilder[S, R]) ThenReply(compute func(S) R) WorkflowEffect[S, R] {
	return b.finish(replyWith(compute, nil))
}

// ThenReplyValue replies with v.
func (b WorkflowBuilder[S, R]) ThenReplyValue(v R) WorkflowEffect[S, R] {
	return b.finish(replyValue[S](v, nil))
}

// ThenReplyState replies with the state; the reply type must be the state's type.
func (b WorkflowBuilder[S, R]) ThenReplyState() WorkflowEffect[S, R] {
	return b.finish(replyState[S, R]())
}

// ThenNoReply answers nothing.
func (b WorkflowBuilder[S, R]) ThenNoReply() WorkflowEffect[S, R] {
	return b.finish(outcome[S, R]{kind: outcomeNoReply})
}

type stepNext int

const (
	stepNone stepNext = iota
	stepTransition
	stepPause
	stepEnd
	stepFail
)

// StepEffect is what a workflow's step returns: a new state (or none) and what happens next: a
// transition, a pause, the end, or a failure.
type StepEffect[S any] struct {
	update   bool
	state    S
	next     stepNext
	ref      StepRef
	pause    PauseOptions
	failCode ErrorCode
	failText string
}

// PauseOptions say how a step's pause ends: a command moves the workflow on, or After passes and
// OnTimeout runs (with OnTimeoutInput). Zero After waits for a command.
type PauseOptions struct {
	After          time.Duration
	OnTimeout      string
	OnTimeoutInput any
}

// StepBuilder is StepUpdate's or StepKeep's result.
type StepBuilder[S any] struct {
	update bool
	state  S
}

// StepUpdate records s as the workflow's state.
func StepUpdate[S any](s S) StepBuilder[S] { return StepBuilder[S]{update: true, state: s} }

// StepKeep leaves the state as it is.
func StepKeep[S any]() StepBuilder[S] { return StepBuilder[S]{} }

func (b StepBuilder[S]) effect() StepEffect[S] {
	return StepEffect[S]{update: b.update, state: b.state}
}

// ThenTransitionTo runs step next, with input (nil for none).
func (b StepBuilder[S]) ThenTransitionTo(step string, input any) StepEffect[S] {
	e := b.effect()
	e.next, e.ref = stepTransition, StepRef{Step: step, Input: input}
	return e
}

// ThenPause pauses the workflow until a command moves it on or the pause times out.
func (b StepBuilder[S]) ThenPause(opts PauseOptions) StepEffect[S] {
	e := b.effect()
	e.next, e.pause = stepPause, opts
	return e
}

// ThenEnd ends the workflow.
func (b StepBuilder[S]) ThenEnd() StepEffect[S] {
	e := b.effect()
	e.next = stepEnd
	return e
}

// ThenFail ends the workflow as failed, on purpose. A step that returns an error or panics is
// retried and failed over as the workflow's settings declare; this is not.
func (b StepBuilder[S]) ThenFail(code ErrorCode, message string) StepEffect[S] {
	e := b.effect()
	e.next, e.failCode, e.failText = stepFail, code, message
	return e
}

// TransitionTo runs step next without changing the state.
func TransitionTo[S any](step string, input any) StepEffect[S] {
	return StepKeep[S]().ThenTransitionTo(step, input)
}

// Pause pauses without changing the state.
func Pause[S any](opts PauseOptions) StepEffect[S] { return StepKeep[S]().ThenPause(opts) }

// End ends the workflow without changing the state.
func End[S any]() StepEffect[S] { return StepKeep[S]().ThenEnd() }

// FailWorkflow ends the workflow as failed without changing the state.
func FailWorkflow[S any](code ErrorCode, message string) StepEffect[S] {
	return StepKeep[S]().ThenFail(code, message)
}

// ---- Views ----

type viewEffectKind int

const (
	viewNone viewEffectKind = iota
	viewUpdate
	viewDelete
	viewIgnore
)

// ViewEffect is what a view's handler says a change does to its row.
type ViewEffect[Row any] struct {
	kind viewEffectKind
	row  Row
}

// UpdateRow writes row as the change's row.
func UpdateRow[Row any](row Row) ViewEffect[Row] { return ViewEffect[Row]{kind: viewUpdate, row: row} }

// DeleteRow deletes the change's row.
func DeleteRow[Row any]() ViewEffect[Row] { return ViewEffect[Row]{kind: viewDelete} }

// IgnoreChange leaves the row as it is.
func IgnoreChange[Row any]() ViewEffect[Row] { return ViewEffect[Row]{kind: viewIgnore} }

// RowChange is one row a keyed view writes under its key, or the key's row deleted.
type RowChange[Row any] struct {
	Key     string
	Row     Row
	Deleted bool
}

// KeyedViewEffect is what a keyed view's handler says to do with one change: rows to write and rows
// to delete, in order. Empty is nothing. A row is moved by deleting its old key and writing the new.
type KeyedViewEffect[Row any] struct {
	changes []RowChange[Row]
	problem string
}

// UpdateRowAt writes row under key.
func UpdateRowAt[Row any](key string, row Row) KeyedViewEffect[Row] {
	return keyedRows([]RowChange[Row]{{Key: key, Row: row}})
}

// DeleteRowAt deletes key's row.
func DeleteRowAt[Row any](key string) KeyedViewEffect[Row] {
	return keyedRows([]RowChange[Row]{{Key: key, Deleted: true}})
}

// IgnoreRows writes and deletes nothing.
func IgnoreRows[Row any]() KeyedViewEffect[Row] { return KeyedViewEffect[Row]{} }

// AllRows is several keyed view effects as one, in order.
func AllRows[Row any](effects ...KeyedViewEffect[Row]) KeyedViewEffect[Row] {
	var all []RowChange[Row]
	for _, e := range effects {
		all = append(all, e.changes...)
	}
	return keyedRows(all)
}

func keyedRows[Row any](changes []RowChange[Row]) KeyedViewEffect[Row] {
	e := KeyedViewEffect[Row]{changes: changes}
	for _, c := range changes {
		if c.Key == "" {
			e.problem = "a row key is a string of one character or more"
		}
	}
	return e
}

// Changes are the effect's rows, in order.
func (e KeyedViewEffect[Row]) Changes() []RowChange[Row] { return e.changes }

// ---- Consumers ----

type consumerKind int

const (
	consumerNone consumerKind = iota
	consumerProduce
	consumerProduceAll
	consumerDone
	consumerIgnore
)

// Message is one message a consumer publishes: the payload, optionally the record key it is
// published under (empty: keyed by its subject, the source instance's id), and its headers.
type Message struct {
	Payload  any
	Key      string
	Metadata Metadata
}

// ConsumerEffect is what a consumer's handler answers: done, ignored, or messages produced onward.
type ConsumerEffect struct {
	kind     consumerKind
	messages []Message
	problem  string
}

// Produce publishes payload to the topic the consumer produces to, with optional headers.
func Produce(payload any, md ...Metadata) ConsumerEffect {
	m := Message{Payload: payload}
	if len(md) > 0 {
		m.Metadata = md[0]
	}
	return ConsumerEffect{kind: consumerProduce, messages: []Message{m}}
}

// ProduceAll publishes several messages for this change, in order. The change is handled when the
// broker has accepted every one; if one is refused the change comes again and all are published
// again. None publishes nothing and is handled at once.
func ProduceAll(messages ...Message) ConsumerEffect {
	e := ConsumerEffect{kind: consumerProduceAll, messages: slices.Clone(messages)}
	for i, m := range messages {
		if m.Payload == nil {
			e.problem = fmt.Sprintf("ProduceAll: message %d has no payload", i)
		}
	}
	return e
}

// ConsumerDone acknowledges the change.
func ConsumerDone() ConsumerEffect { return ConsumerEffect{kind: consumerDone} }

// ConsumerIgnore acknowledges the change, saying it was of no interest.
func ConsumerIgnore() ConsumerEffect { return ConsumerEffect{kind: consumerIgnore} }

// Messages are what the effect publishes.
func (e ConsumerEffect) Messages() []Message { return e.messages }

// ---- Timed actions ----

// ActionEffect is what a timed action answers: done, or failed (and retried by the sweeper).
type ActionEffect struct {
	failed  bool
	set     bool
	code    ErrorCode
	message string
}

// ActionDone says the action did what it was for.
func ActionDone() ActionEffect { return ActionEffect{set: true} }

// ActionFail says the action failed; the sweeper retries it on its schedule.
func ActionFail(code ErrorCode, message string) ActionEffect {
	return ActionEffect{set: true, failed: true, code: code, message: message}
}

// ---- Agents ----

// AgentEffect describes one interaction with a model: which model, the instructions, the context,
// the tools and guardrails, whether to keep the session's memory, and the shape of the reply. Building
// one calls no model; the sidecar's loop interprets it and calls back for tools and guardrails. The
// model's key never lives in this process.
type AgentEffect struct {
	Model          string
	System         string
	User           string
	Context        []string
	SessionMemory  bool
	ToolNames      []string
	GuardrailNames []string
	JSONReply      bool
	SchemaHint     string
	set            bool
	// failure is AgentError's refusal, made before any model is called.
	failure *Refusal
}

// Refusal is a code and a message, as an effect carries a refusal it was built with.
type Refusal struct {
	Code    ErrorCode
	Message string
}

// AgentError refuses the request without calling a model.
func AgentError(code ErrorCode, message string) AgentEffect {
	e := newAgentEffect()
	e.failure = &Refusal{Code: code, Message: message}
	return e
}

// Failure is AgentError's refusal, or nil.
func (e AgentEffect) Failure() *Refusal { return e.failure }

func newAgentEffect() AgentEffect { return AgentEffect{SessionMemory: true, set: true} }

// AgentModel starts an agent effect on the model the sidecar configured under name.
func AgentModel(name string) AgentEffect { e := newAgentEffect(); e.Model = name; return e }

// SystemMessage starts an agent effect with the system instructions.
func SystemMessage(text string) AgentEffect { e := newAgentEffect(); e.System = text; return e }

// UserMessage starts an agent effect with the user's message.
func UserMessage(text string) AgentEffect { e := newAgentEffect(); e.User = text; return e }

// WithModel uses the model the sidecar configured under name.
func (e AgentEffect) WithModel(name string) AgentEffect { e.Model = name; return e }

// SystemMessage sets the system instructions.
func (e AgentEffect) SystemMessage(text string) AgentEffect { e.System = text; return e }

// UserMessage sets the user's message.
func (e AgentEffect) UserMessage(text string) AgentEffect { e.User = text; return e }

// WithContext adds context the user did not type (a retrieved document, an entity's state), kept
// apart from the user message so memory records what the user said.
func (e AgentEffect) WithContext(text string) AgentEffect {
	e.Context = append(slices.Clone(e.Context), text)
	return e
}

// Memory keeps (true) or drops (false) the session's memory; false is for a one-shot classification.
func (e AgentEffect) Memory(session bool) AgentEffect { e.SessionMemory = session; return e }

// Tools offers the model the named tools.
func (e AgentEffect) Tools(names ...string) AgentEffect {
	e.ToolNames = append(slices.Clone(e.ToolNames), names...)
	return e
}

// Guardrails applies the named guardrails.
func (e AgentEffect) Guardrails(names ...string) AgentEffect {
	e.GuardrailNames = append(slices.Clone(e.GuardrailNames), names...)
	return e
}

// ThenReply replies with the model's text.
func (e AgentEffect) ThenReply() AgentEffect { e.JSONReply = false; return e }

// ThenReplyJSON replies with the model's JSON, decoded by the handler's reply type. The schema is
// not sent to the model: say what you want in the system message.
func (e AgentEffect) ThenReplyJSON(schemaHint string) AgentEffect {
	if schemaHint == "" {
		schemaHint = "JSON"
	}
	e.JSONReply, e.SchemaHint = true, schemaHint
	return e
}
