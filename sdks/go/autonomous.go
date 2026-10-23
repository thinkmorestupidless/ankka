package ankka

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"iter"
	"reflect"
	"strings"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"github.com/thinkmorestupidless/ankka-go/internal/server"
)

// Autonomous agents: a durable agent handed a task, which works it on its own, iteration by
// iteration, until it completes the task with a result its type's rules accept, fails it, or spends
// its budget. This process declares the agent (what it is for, its tools and guardrails, the task
// types it accepts) and the sidecar runs the loop, the model and every record. The process is asked
// to run a tool, check a guardrail or check a task rule, each naming the task it is for (1.2).

// Verdict is what a task rule decides of a result.
type Verdict struct {
	accepted bool
	reason   string
}

// Accepted passes a result.
func Accepted() Verdict { return Verdict{accepted: true} }

// Rejected refuses a result with the reason the model is told.
func Rejected(reason string) Verdict { return Verdict{reason: reason} }

// TaskRule is a check a task's result must pass. Rules run in declaration order; the first
// rejection is reported. A rule that fails or panics decides nothing, and the sidecar tries the
// iteration again.
type TaskRule[R any] struct {
	Name  string
	Check func(ctx context.Context, result R) (Verdict, error)
}

// Rule is a task rule named name.
func Rule[R any](name string, check func(ctx context.Context, result R) (Verdict, error)) TaskRule[R] {
	return TaskRule[R]{Name: name, Check: check}
}

// TaskType is a kind of work, with the result type R its tasks complete with. Its name is the
// wire name, written into every task of the type. A string result is text; any other type's JSON
// Schema is the result the model is asked for.
type TaskType[R any] struct {
	name, description string
	rules             []TaskRule[R]
}

// NewTaskType is the task type named name, described for the model, with its rules.
func NewTaskType[R any](name, description string, rules ...TaskRule[R]) *TaskType[R] {
	return &TaskType[R]{name: name, description: description, rules: rules}
}

// Name is the task type's wire name.
func (t *TaskType[R]) Name() string { return t.name }

// AnyTaskType is a task type of any result type.
type AnyTaskType interface {
	Name() string
	detail() (*pb.AutonomousAgentDetail_TaskType, []string)
	verdict(ctx context.Context, resultJSON string) (*pb.TaskResultVerdict, error)
}

func (t *TaskType[R]) detail() (*pb.AutonomousAgentDetail_TaskType, []string) {
	var problems []string
	if strings.TrimSpace(t.name) == "" {
		problems = append(problems, "a task type has no name")
	}
	if strings.TrimSpace(t.description) == "" {
		problems = append(problems, fmt.Sprintf("the task type %q has no description", t.name))
	}
	out := &pb.AutonomousAgentDetail_TaskType{Name: t.name, Description: t.description}
	seen := map[string]bool{}
	for _, r := range t.rules {
		if strings.TrimSpace(r.Name) == "" || r.Check == nil {
			problems = append(problems, fmt.Sprintf("the task type %q has a rule with no name or no check", t.name))
			continue
		}
		if seen[r.Name] {
			problems = append(problems, fmt.Sprintf("the task type %q declares the rule %q twice", t.name, r.Name))
		}
		seen[r.Name] = true
		out.Rules = append(out.Rules, r.Name)
	}
	if rt := reflect.TypeFor[R](); rt.Kind() != reflect.String {
		schema, schemaProblems := schemaJSON(rt)
		for _, p := range schemaProblems {
			problems = append(problems, fmt.Sprintf("the task type %q's result: %s", t.name, p))
		}
		if schema != "" {
			out.ResultSchemaJson = &schema
		}
	}
	return out, problems
}

// decodeResult is a stored result, JSON as the model sent it, as R.
func (t *TaskType[R]) decodeResult(stored string) (R, error) {
	var out R
	p, problems := planFor(reflect.TypeFor[R]())
	if len(problems) > 0 {
		return out, errors.New(strings.Join(problems, "; "))
	}
	err := decodeJSON(p, []byte(stored), reflect.ValueOf(&out).Elem())
	return out, err
}

func (t *TaskType[R]) verdict(ctx context.Context, resultJSON string) (*pb.TaskResultVerdict, error) {
	result, err := t.decodeResult(resultJSON)
	if err != nil {
		return &pb.TaskResultVerdict{Verdict: &pb.TaskResultVerdict_Malformed{Malformed: err.Error()}}, nil
	}
	for _, r := range t.rules {
		v, err := server.GuardValue(func() (Verdict, error) { return r.Check(ctx, result) })
		if err != nil {
			return nil, fmt.Errorf("rule %s failed: %w", r.Name, err)
		}
		if !v.accepted {
			return &pb.TaskResultVerdict{Verdict: &pb.TaskResultVerdict_Reject{Reject: &pb.TaskResultVerdict_Rejection{Rule: r.Name, Reason: v.reason}}}, nil
		}
	}
	return &pb.TaskResultVerdict{Verdict: &pb.TaskResultVerdict_Accept{Accept: &pb.Empty{}}}, nil
}

type acceptsPart struct {
	taskType      AnyTaskType
	maxIterations int
}

func (acceptsPart) part() {}

// Accepts is a task type the agent takes, and how many iterations a task of it may use (at least 1).
func Accepts(t AnyTaskType, maxIterations int) Part {
	return acceptsPart{taskType: t, maxIterations: maxIterations}
}

type instructionsPart string

func (instructionsPart) part() {}

// Instructions are what the agent is told for every task, beside the task's own.
func Instructions(text string) Part { return instructionsPart(text) }

type autonomousModelPart string

func (autonomousModelPart) part() {}

// AutonomousModel is a model named in the sidecar's configuration; unsaid, its default.
func AutonomousModel(name string) Part { return autonomousModelPart(name) }

// AutonomousSettings tune when an instance notices it is in trouble. A zero field keeps the
// platform's default: 0.8 of the budget, 3 repeated failures, 5 consecutive failures, five minutes
// waiting on a dependency.
type AutonomousSettings struct {
	ApproachingBudgetAt    float64
	RepeatedFailureAt      int
	MaxConsecutiveFailures int
	DependencyStuckAfter   time.Duration
}

type autonomousSettingsPart AutonomousSettings

func (autonomousSettingsPart) part() {}

// WithAutonomousSettings are the autonomous agent's AutonomousSettings.
func WithAutonomousSettings(s AutonomousSettings) Part { return autonomousSettingsPart(s) }

// AutonomousComponent is an autonomous agent's declaration.
type AutonomousComponent struct {
	id, description, instructions, model string
	accepts                              []acceptsPart
	settings                             *AutonomousSettings
	kit                                  agentKit
	parts                                []Part
}

// Autonomous declares an autonomous agent: what it is for, the task types it Accepts, and its
// Instructions, Tools, Guardrails, McpServers, AutonomousModel and Settings.
func Autonomous(id, description string, parts ...Part) *AutonomousComponent {
	a := &AutonomousComponent{id: id, description: description, kit: newAgentKit()}
	for _, p := range parts {
		switch v := p.(type) {
		case acceptsPart:
			a.accepts = append(a.accepts, v)
		case instructionsPart:
			a.instructions = string(v)
		case autonomousModelPart:
			a.model = string(v)
		case autonomousSettingsPart:
			s := AutonomousSettings(v)
			a.settings = &s
		default:
			if !a.kit.take(p) {
				a.parts = append(a.parts, p)
			}
		}
	}
	return a
}

// ID is the autonomous agent's component id.
func (a *AutonomousComponent) ID() string { return a.id }

func (a *AutonomousComponent) declare(d *declarations) {
	where := a.id
	if a.id == "" {
		where = "autonomous agent"
		d.problem(where, "an autonomous agent has an id")
	}
	if strings.TrimSpace(a.description) == "" {
		d.problem(where, "an autonomous agent has a description")
	}
	if len(a.accepts) == 0 {
		d.problem(where, "an autonomous agent accepts at least one task type")
	}
	for _, p := range a.parts {
		misplaced(d, where, "autonomous agent", p)
	}
	d.require(2, "%s is an autonomous agent", where)
	tools, guardrails, results, servers := a.kit.declare(d, where)
	for _, name := range a.kit.toolOrder {
		if name == "complete_task" || name == "fail_task" {
			d.problem(where, "the tool %q is the platform's; name the tool otherwise", name)
		}
	}
	detail := &pb.AutonomousAgentDetail{Description: a.description, Tools: tools, Guardrails: guardrails, McpServers: servers, ResultGuardrails: results}
	if a.instructions != "" {
		detail.Instructions = &a.instructions
	}
	if a.model != "" {
		detail.Model = &a.model
	}
	types := map[string]AnyTaskType{}
	for _, acc := range a.accepts {
		if acc.taskType == nil {
			d.problem(where, "Accepts is given no task type")
			continue
		}
		name := acc.taskType.Name()
		if _, dup := types[name]; dup {
			d.problem(where, "the task type %q is accepted twice", name)
			continue
		}
		types[name] = acc.taskType
		tt, problems := acc.taskType.detail()
		for _, p := range problems {
			d.problem(where, "%s", p)
		}
		if acc.maxIterations < 1 {
			d.problem(where, "the task type %q is accepted with %d iterations; a task has at least one", name, acc.maxIterations)
		}
		detail.TaskTypes = append(detail.TaskTypes, tt)
		detail.Accepts = append(detail.Accepts, &pb.AutonomousAgentDetail_TaskAcceptance{TaskType: name, MaxIterations: int32(acc.maxIterations)})
	}
	if s := a.settings; s != nil {
		out := &pb.AutonomousAgentDetail_AutonomousSettings{}
		if s.ApproachingBudgetAt != 0 {
			if s.ApproachingBudgetAt <= 0 || s.ApproachingBudgetAt >= 1 {
				d.problem(where, "ApproachingBudgetAt is %v; it is a fraction of the budget, between 0 and 1", s.ApproachingBudgetAt)
			}
			out.ApproachingBudgetAt = &s.ApproachingBudgetAt
		}
		if s.RepeatedFailureAt != 0 {
			n := int32(s.RepeatedFailureAt)
			out.RepeatedFailureAt = &n
		}
		if s.MaxConsecutiveFailures != 0 {
			n := int32(s.MaxConsecutiveFailures)
			out.MaxConsecutiveFailures = &n
		}
		if s.DependencyStuckAfter != 0 {
			ms := s.DependencyStuckAfter.Milliseconds()
			out.DependencyStuckAfterMillis = &ms
		}
		if s.RepeatedFailureAt < 0 || s.MaxConsecutiveFailures < 0 || s.DependencyStuckAfter < 0 {
			d.problem(where, "a setting is negative")
		}
		detail.Settings = out
	}
	d.components = append(d.components, &pb.Component{
		Kind:   pb.Kind_AUTONOMOUS_AGENT,
		Id:     a.id,
		Detail: &pb.Component_AutonomousAgent{AutonomousAgent: detail},
	})
	d.agents[a.id] = server.AgentHandlers{
		Tool: func(ctx context.Context, req *pb.ToolRequest) (string, error) { return a.kit.runTool(ctx, a.id, req) },
		Guardrail: func(ctx context.Context, req *pb.GuardrailRequest) (string, error) {
			return a.kit.checkGuardrail(ctx, a.id, req)
		},
		TaskResult: func(ctx context.Context, req *pb.TaskResultRequest) (*pb.TaskResultVerdict, error) {
			t, ok := types[req.GetTaskType()]
			if !ok {
				return nil, fmt.Errorf("no task type %q on %s", req.GetTaskType(), a.id)
			}
			ctx = withAgent(ctx, AgentScope{ComponentID: a.id, SessionID: "task:" + req.GetTaskId(), TaskID: req.GetTaskId(), Metadata: metadataFromPb(req.GetMetadata())})
			return t.verdict(ctx, req.GetResultJson())
		},
	}
}

// ── Calling ─────────────────────────────────────────────────────────────────

const (
	taskComponent     = "ankka-task"
	instanceComponent = "ankka-agent-instance"
)

// rawInvoke calls a component this process need not declare, with a JSON body, answering the reply's
// JSON (nil for none).
func rawInvoke(ctx context.Context, kind pb.Kind, componentID, entityID, wire string, body any) (json.RawMessage, error) {
	_, rpc, err := clientOf(ctx)
	if err != nil {
		return nil, err
	}
	var data []byte
	if body != nil {
		if data, err = json.Marshal(body); err != nil {
			return nil, err
		}
	}
	reply, err := rpc.Invoke(ctx, &pb.InvokeRequest{
		Kind: kind, ComponentId: componentID, EntityId: entityID, Name: wire,
		Payload:  &pb.Payload{ContentType: JSONContent, Data: data},
		Metadata: metadataToPb(MetadataFrom(ctx)),
	})
	if err != nil {
		return nil, tooOld("a call", "1.2", err)
	}
	switch r := reply.GetResult().(type) {
	case *pb.InvokeReply_Reply:
		if d := r.Reply.GetPayload().GetData(); len(d) > 0 {
			return d, nil
		}
		return nil, nil
	case *pb.InvokeReply_Error:
		return nil, errorFromPb(r.Error)
	case *pb.InvokeReply_Approval:
		return nil, approvalsOf(r.Approval)
	}
	return nil, errors.New("the sidecar answered nothing")
}

func newID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

// Attachment is a file a task carries: its text inline, or a URI it is read from.
type Attachment struct {
	Name        string
	ContentType string
	Content     string
	URI         string
}

// TaskOptions are a new task's id (a random one when ""), attachments and the tasks it waits for.
type TaskOptions struct {
	ID          string
	Attachments []Attachment
	DependsOn   []string
}

// CreateTask creates a task of type t and answers its id. A dependency that does not exist is
// refused before anything is written; one that has already ended cancels the new task.
func CreateTask(ctx context.Context, t AnyTaskType, instructions string, opts TaskOptions) (string, error) {
	id := opts.ID
	if id == "" {
		id = newID()
	}
	var deps []string
	seen := map[string]bool{}
	for _, d := range opts.DependsOn {
		if !seen[d] {
			seen[d] = true
			deps = append(deps, d)
		}
	}
	for _, dep := range deps {
		if _, err := rawInvoke(ctx, pb.Kind_EVENT_SOURCED_ENTITY, taskComponent, dep, "get", nil); err != nil {
			return "", err
		}
	}
	attachments := []map[string]any{}
	for _, a := range opts.Attachments {
		content := map[string]any{"type": "Inline", "text": a.Content}
		if a.URI != "" {
			content = map[string]any{"type": "Reference", "uri": a.URI}
		}
		attachments = append(attachments, map[string]any{"name": a.Name, "contentType": a.ContentType, "content": content})
	}
	if deps == nil {
		deps = []string{}
	}
	body := map[string]any{"typeName": t.Name(), "instructions": instructions, "attachments": attachments, "dependencies": deps}
	if _, err := rawInvoke(ctx, pb.Kind_EVENT_SOURCED_ENTITY, taskComponent, id, "create", body); err != nil {
		return "", err
	}
	for _, dep := range deps {
		answer, err := rawInvoke(ctx, pb.Kind_EVENT_SOURCED_ENTITY, taskComponent, dep, "add-dependent", map[string]any{"taskId": id})
		if err != nil {
			return "", err
		}
		var ended struct {
			AlreadyEnded string `json:"alreadyEnded"`
		}
		_ = json.Unmarshal(answer, &ended)
		if ended.AlreadyEnded != "" {
			if _, err := rawInvoke(ctx, pb.Kind_EVENT_SOURCED_ENTITY, taskComponent, id, "cancel", map[string]any{"reason": fmt.Sprintf("dependency '%s' %s", dep, ended.AlreadyEnded)}); err != nil {
				return "", err
			}
			break
		}
	}
	return id, nil
}

// TaskStatus is where a task is in its life.
type TaskStatus string

// The statuses a task passes through.
const (
	TaskPending        TaskStatus = "pending"
	TaskAssigned       TaskStatus = "assigned"
	TaskInProgress     TaskStatus = "in-progress"
	TaskResultRejected TaskStatus = "result-rejected"
	TaskCompleted      TaskStatus = "completed"
	TaskFailed         TaskStatus = "failed"
	TaskCancelled      TaskStatus = "cancelled"
)

// Ended reports whether the task has completed, failed or been cancelled.
func (s TaskStatus) Ended() bool { return s == TaskCompleted || s == TaskFailed || s == TaskCancelled }

// Assignee is the instance a task is assigned to.
type Assignee struct {
	ComponentID string `json:"componentId"`
	InstanceID  string `json:"instanceId"`
}

// TaskSnapshot is a task as its record holds it, its result decoded as R.
type TaskSnapshot[R any] struct {
	ID         string
	TypeName   string
	Status     TaskStatus
	Result     *R
	Reason     string
	Iterations int
	Assignee   *Assignee
	// Record is the whole record, as JSON.
	Record json.RawMessage
}

type taskRecord struct {
	ID         string    `json:"id"`
	TypeName   string    `json:"typeName"`
	Status     string    `json:"status"`
	Result     *string   `json:"result"`
	Reason     *string   `json:"reason"`
	Iterations int       `json:"iterations"`
	Assignee   *Assignee `json:"assignee"`
}

// GetTask is the task id, its result decoded as the task type's.
func GetTask[R any](ctx context.Context, t *TaskType[R], id string) (TaskSnapshot[R], error) {
	var out TaskSnapshot[R]
	raw, err := rawInvoke(ctx, pb.Kind_EVENT_SOURCED_ENTITY, taskComponent, id, "get", nil)
	if err != nil {
		return out, err
	}
	var rec taskRecord
	if err := json.Unmarshal(raw, &rec); err != nil {
		return out, fmt.Errorf("task '%s' cannot be read: %w", id, err)
	}
	if t != nil && rec.TypeName != t.name {
		return out, fmt.Errorf("task '%s' is a '%s' task, not '%s'", id, rec.TypeName, t.name)
	}
	out = TaskSnapshot[R]{ID: rec.ID, TypeName: rec.TypeName, Status: TaskStatus(rec.Status), Iterations: rec.Iterations, Assignee: rec.Assignee, Record: raw}
	if rec.Reason != nil {
		out.Reason = *rec.Reason
	}
	if rec.Result != nil && t != nil {
		r, err := t.decodeResult(*rec.Result)
		if err != nil {
			return out, fmt.Errorf("task '%s''s result cannot be read as a %s: %w", id, reflect.TypeFor[R](), err)
		}
		out.Result = &r
	}
	return out, nil
}

// WaitForTask waits up to within for the task to end, answering it as GetTask does.
func WaitForTask[R any](ctx context.Context, t *TaskType[R], id string, within time.Duration) (TaskSnapshot[R], error) {
	deadline := time.Now().Add(within)
	for {
		s, err := GetTask(ctx, t, id)
		if err != nil || s.Status.Ended() {
			return s, err
		}
		if time.Now().After(deadline) {
			return s, fmt.Errorf("task '%s' had not ended in time; it is %s", id, s.Status)
		}
		select {
		case <-ctx.Done():
			return s, ctx.Err()
		case <-time.After(250 * time.Millisecond):
		}
	}
}

// CancelTask cancels the task: at once when it waits, at the end of the current iteration when an
// instance works it.
func CancelTask(ctx context.Context, id, reason string) error {
	if reason == "" {
		reason = "cancelled by caller"
	}
	if _, err := rawInvoke(ctx, pb.Kind_EVENT_SOURCED_ENTITY, taskComponent, id, "cancel", map[string]any{"reason": reason}); err != nil {
		return err
	}
	raw, err := rawInvoke(ctx, pb.Kind_EVENT_SOURCED_ENTITY, taskComponent, id, "get", nil)
	if err != nil {
		return err
	}
	var rec taskRecord
	if err := json.Unmarshal(raw, &rec); err != nil {
		return err
	}
	if rec.Assignee != nil {
		_, err = rawInvoke(ctx, pb.Kind_AUTONOMOUS_AGENT, rec.Assignee.ComponentID, rec.Assignee.InstanceID, "dequeue", map[string]any{"taskId": id, "reason": reason})
	}
	return err
}

// AgentInstance is calls to one instance of an autonomous agent, by the id the caller chose.
type AgentInstance struct {
	ComponentID string
	InstanceID  string
}

// Instance is the instance instanceID of the autonomous agent componentID.
func Instance(componentID, instanceID string) AgentInstance {
	return AgentInstance{ComponentID: componentID, InstanceID: instanceID}
}

// RunSingleTask creates a task of type t, starts a fresh instance of the agent on it, and answers
// the task's id at once.
func RunSingleTask(ctx context.Context, componentID string, t AnyTaskType, instructions string, opts TaskOptions) (string, error) {
	id, err := CreateTask(ctx, t, instructions, opts)
	if err != nil {
		return "", err
	}
	_, err = rawInvoke(ctx, pb.Kind_AUTONOMOUS_AGENT, componentID, newID(), "run-single-task", map[string]any{"taskId": id})
	return id, err
}

// Assigned is what an instance answered an assignment with.
type Assigned struct {
	Accepted []string                     `json:"accepted"`
	Refused  map[string]AssignmentRefusal `json:"refused"`
}

// AssignmentRefusal is why an instance refused a task.
type AssignmentRefusal struct {
	Message string `json:"message"`
	Code    string `json:"code"`
}

func (a AgentInstance) call(ctx context.Context, wire string, body any) (json.RawMessage, error) {
	if a.InstanceID == "" {
		return nil, errors.New("this call needs an instance id: ankka.Instance(agent, instanceID)")
	}
	return rawInvoke(ctx, pb.Kind_AUTONOMOUS_AGENT, a.ComponentID, a.InstanceID, wire, body)
}

// Assign queues the tasks on the instance.
func (a AgentInstance) Assign(ctx context.Context, taskIDs ...string) (Assigned, error) {
	var out Assigned
	if taskIDs == nil {
		taskIDs = []string{}
	}
	raw, err := a.call(ctx, "assign", map[string]any{"taskIds": taskIDs})
	if err != nil {
		return out, err
	}
	err = json.Unmarshal(raw, &out)
	return out, err
}

// Suspend stops the instance taking iterations until Resume.
func (a AgentInstance) Suspend(ctx context.Context) error {
	_, err := a.call(ctx, "suspend", nil)
	return err
}

// Resume lets a suspended instance go on.
func (a AgentInstance) Resume(ctx context.Context) error {
	_, err := a.call(ctx, "resume", nil)
	return err
}

// Terminate stops the instance for good; its tasks go back to pending for another to take.
func (a AgentInstance) Terminate(ctx context.Context) error {
	_, err := a.call(ctx, "terminate", nil)
	return err
}

// InstancePhase is what an instance is doing.
type InstancePhase string

// The phases of an instance.
const (
	PhaseIdle       InstancePhase = "idle"
	PhaseWorking    InstancePhase = "working"
	PhaseWaiting    InstancePhase = "waiting"
	PhaseSuspended  InstancePhase = "suspended"
	PhaseTerminated InstancePhase = "terminated"
)

// InstanceState is an instance as its record holds it.
type InstanceState struct {
	Phase       InstancePhase
	CurrentTask string
	Iteration   int
	Queued      []string
	// Awaiting are the approval requests the current task's tool calls wait on; while any does, the
	// instance calls no model.
	Awaiting []ApprovalRequest
}

// State is the instance's state.
func (a AgentInstance) State(ctx context.Context) (InstanceState, error) {
	var out InstanceState
	if a.InstanceID == "" {
		return out, errors.New("this call needs an instance id: ankka.Instance(agent, instanceID)")
	}
	raw, err := rawInvoke(ctx, pb.Kind_EVENT_SOURCED_ENTITY, instanceComponent, a.ComponentID+"/"+a.InstanceID, "get", nil)
	if err != nil {
		return out, err
	}
	var rec struct {
		Terminated bool     `json:"terminated"`
		Suspended  bool     `json:"suspended"`
		Queue      []string `json:"queue"`
		Current    *struct {
			TaskID    string `json:"taskId"`
			Iteration int    `json:"iteration"`
			Approvals []struct {
				ID          string          `json:"id"`
				Tool        string          `json:"tool"`
				Arguments   json.RawMessage `json:"arguments"`
				RequestedAt int64           `json:"requestedAt"`
				ExpiresAt   *int64          `json:"expiresAt"`
				Decision    json.RawMessage `json:"decision"`
			} `json:"approvals"`
		} `json:"current"`
	}
	if err := json.Unmarshal(raw, &rec); err != nil {
		return out, err
	}
	out.Queued = append([]string{}, rec.Queue...)
	switch {
	case rec.Terminated:
		out.Phase = PhaseTerminated
	case rec.Suspended:
		out.Phase = PhaseSuspended
	case rec.Current != nil:
		out.Phase = PhaseWorking
	case len(rec.Queue) > 0:
		out.Phase = PhaseWaiting
	default:
		out.Phase = PhaseIdle
	}
	if c := rec.Current; c != nil {
		out.CurrentTask, out.Iteration = c.TaskID, c.Iteration
		for _, r := range c.Approvals {
			if len(r.Decision) > 0 && !bytes.Equal(r.Decision, []byte("null")) {
				continue
			}
			req := ApprovalRequest{ID: r.ID, Tool: r.Tool, ArgumentsJSON: string(r.Arguments), RequestedAt: time.UnixMilli(r.RequestedAt).UTC()}
			if req.ArgumentsJSON == "" {
				req.ArgumentsJSON = "{}"
			}
			if r.ExpiresAt != nil {
				e := time.UnixMilli(*r.ExpiresAt).UTC()
				req.ExpiresAt = &e
			}
			out.Awaiting = append(out.Awaiting, req)
		}
	}
	return out, nil
}

// Decision is a person's decision of an approval request: By, who decided, is required.
type Decision struct {
	Approved bool
	By       string
	Note     string
}

func decide(ctx context.Context, kind pb.Kind, componentID, entityID, handler, approvalID string, d Decision) (*pb.InvokeReply, error) {
	if strings.TrimSpace(d.By) == "" {
		return nil, Refuse(BadRequest, "a decision names who decided")
	}
	_, rpc, err := clientOf(ctx)
	if err != nil {
		return nil, err
	}
	req := &pb.DecideRequest{Kind: kind, ComponentId: componentID, EntityId: entityID, Name: handler, ApprovalId: approvalID, Approved: d.Approved, By: d.By, Metadata: metadataToPb(MetadataFrom(ctx))}
	if d.Note != "" {
		req.Note = &d.Note
	}
	reply, err := rpc.Decide(ctx, req)
	if err != nil {
		return nil, tooOld("a decision", "1.11", err)
	}
	if e := reply.GetError(); e != nil {
		return nil, errorFromPb(e)
	}
	return reply, nil
}

// Decide decides one of the instance's approval requests. Approved, the tool runs and the task goes
// on; refused, the model is told so. A request already decided is a conflict.
func (a AgentInstance) Decide(ctx context.Context, approvalID string, d Decision) error {
	if a.InstanceID == "" {
		return errors.New("this call needs an instance id: ankka.Instance(agent, instanceID)")
	}
	_, err := decide(ctx, pb.Kind_AUTONOMOUS_AGENT, a.ComponentID, a.InstanceID, "", approvalID, d)
	return err
}

// DecideApproval decides an approval request an agent's turn waits on: the agent componentID, the
// session sessionID and the handler that was called (1.11). It answers as the turn's caller would
// have been answered once the turn goes on: its reply as R, or an *ApprovalAwaitedError when it
// waits on another decision.
func DecideApproval[R any](ctx context.Context, componentID, sessionID, handler, approvalID string, d Decision) (R, error) {
	reply, err := decide(ctx, pb.Kind_AGENT, componentID, sessionID, handler, approvalID, d)
	if err != nil {
		var zero R
		return zero, err
	}
	return decodeReply[R](reply)
}

// Notification is one thing an instance did: Type names it, and Fields holds every field as sent.
type Notification struct {
	Type        string
	ComponentID string
	InstanceID  string
	TaskID      string
	At          time.Time
	Fields      map[string]json.RawMessage
}

// Notifications is what the instance does from now on, as it happens; nothing is replayed. The
// sequence ends when ctx does, or with the error that ended it.
func (a AgentInstance) Notifications(ctx context.Context) iter.Seq2[Notification, error] {
	return func(yield func(Notification, error) bool) {
		_, rpc, err := clientOf(ctx)
		if err != nil {
			yield(Notification{}, err)
			return
		}
		ctx, cancel := context.WithCancel(ctx)
		defer cancel()
		stream, err := rpc.InvokeStream(ctx, &pb.InvokeRequest{
			Kind: pb.Kind_AUTONOMOUS_AGENT, ComponentId: a.ComponentID, EntityId: a.InstanceID, Name: "notifications",
			Payload: &pb.Payload{ContentType: JSONContent}, Metadata: metadataToPb(MetadataFrom(ctx)),
		})
		if err != nil {
			yield(Notification{}, tooOld("notifications", "1.2", err))
			return
		}
		for {
			tok, err := stream.Recv()
			if err != nil {
				if !errors.Is(err, io.EOF) && ctx.Err() == nil {
					yield(Notification{}, tooOld("notifications", "1.2", err))
				}
				return
			}
			switch t := tok.GetToken().(type) {
			case *pb.StreamToken_Text:
				n, err := notificationOf(t.Text)
				if !yield(n, err) {
					return
				}
			case *pb.StreamToken_Completed:
				return
			case *pb.StreamToken_Failed:
				yield(Notification{}, errorFromPb(t.Failed))
				return
			}
		}
	}
}

func notificationOf(text string) (Notification, error) {
	var fields map[string]json.RawMessage
	if err := json.Unmarshal([]byte(text), &fields); err != nil {
		return Notification{}, fmt.Errorf("a notification cannot be read: %w", err)
	}
	n := Notification{Fields: fields}
	str := func(k string) string {
		var s string
		_ = json.Unmarshal(fields[k], &s)
		return s
	}
	n.Type, n.ComponentID, n.InstanceID, n.TaskID = str("type"), str("componentId"), str("instanceId"), str("taskId")
	var at int64
	if err := json.Unmarshal(fields["at"], &at); err == nil {
		n.At = time.UnixMilli(at).UTC()
	}
	return n, nil
}
