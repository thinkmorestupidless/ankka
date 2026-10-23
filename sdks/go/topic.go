package ankka

import (
	"context"
	"fmt"
	"reflect"
	"strconv"
	"strings"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

// Source is what a view or a consumer reads: a component's changes (Changes) or a topic (Topic).
type Source struct {
	kind        pb.Kind
	componentID string
	component   bool
	topic       string
	start       *StartPosition
	contract    *Contract
	broker      string
	project     string
	parallel    bool
	// codec is the component's change codec (a Codec of its events or its state), or nil for a topic.
	codec    any
	problems []string
}

// changeSource is a component whose changes can be read: an event sourced entity (its events), a
// key value entity (its state) or a workflow (its state, with where it stands).
type changeSource interface {
	ID() string
	changes() (pb.Kind, any)
}

func (e *EventSourcedEntity[S, E]) changes() (pb.Kind, any) {
	return pb.Kind_EVENT_SOURCED_ENTITY, e.eventCodec
}
func (e *KeyValueEntity[S]) changes() (pb.Kind, any) { return pb.Kind_KEY_VALUE_ENTITY, e.stateCodec }
func (w *WorkflowComponent[S]) changes() (pb.Kind, any) {
	return pb.Kind_WORKFLOW, w.stateCodec
}

// Changes is a component's changes as a source: an event sourced entity's events, a key value
// entity's state, or a workflow's state each time it records one (protocol 1.16), with where the
// workflow stood (ChangeScope.Standing).
func Changes(c changeSource) Source {
	if c == nil {
		return Source{component: true, problems: []string{"Changes is given no component"}}
	}
	kind, codec := c.changes()
	return Source{kind: kind, componentID: c.ID(), component: true, codec: codec}
}

// WorkflowSource is a workflow's changes as a source; the same as Changes(w).
func WorkflowSource[S any](w *WorkflowComponent[S]) Source { return Changes(w) }

// StartPosition is where a topic source begins the first time its consumer group reads a
// partition. It applies once and is committed at once, so a restart or a new instance resumes
// where the group got to.
type StartPosition struct {
	named pb.StartFrom_Named
	at    time.Time
}

// Earliest is the oldest message the broker still holds.
func Earliest() StartPosition { return StartPosition{named: pb.StartFrom_EARLIEST} }

// Latest is after the newest: only what is published from then on.
func Latest() StartPosition { return StartPosition{named: pb.StartFrom_LATEST} }

// StartAt is the first message published at or after t.
func StartAt(t time.Time) StartPosition { return StartPosition{at: t} }

func (p StartPosition) toPb() *pb.StartFrom {
	if p.named != 0 {
		return &pb.StartFrom{Position: &pb.StartFrom_Named_{Named: p.named}}
	}
	return &pb.StartFrom{Position: &pb.StartFrom_AtMillis{AtMillis: p.at.UnixMilli()}}
}

// TopicOption is an option of a topic source.
type TopicOption func(*Source)

// StartFrom is where the topic source begins; a consumer over a topic must say.
func StartFrom(p StartPosition) TopicOption {
	return func(s *Source) {
		if p.named == 0 && p.at.IsZero() {
			s.problems = append(s.problems, "StartFrom is given no position: use Earliest(), Latest() or StartAt(t)")
			return
		}
		s.start = &p
	}
}

// ExpectContract is the contract the topic is expected to carry, checked at start against the
// project's declaration (protocol 1.14).
func ExpectContract(c Contract) TopicOption { return func(s *Source) { s.contract = &c } }

// OnBroker is the declared broker the topic is on; without it, the installation's (protocol 1.14).
func OnBroker(name string) TopicOption { return func(s *Source) { s.broker = name } }

// FromProject reads another project's topic, which that project must grant this service consume on
// (protocol 1.15).
func FromProject(project string) TopicOption { return func(s *Source) { s.project = project } }

// Parallel handles the partitions an instance holds at once, each in order (protocol 1.14).
func Parallel() TopicOption { return func(s *Source) { s.parallel = true } }

// Topic is a topic as a source, under the name the project declares it by.
func Topic(name string, opts ...TopicOption) Source {
	s := Source{topic: name}
	for _, o := range opts {
		o(&s)
	}
	return s
}

func (s Source) describe() string {
	if s.component {
		return fmt.Sprintf("the changes of %q", s.componentID)
	}
	return fmt.Sprintf("the topic %q", s.topic)
}

// check names everything wrong with s, and the protocol it needs, for the component where.
func (s Source) check(d *declarations, where string, consumer bool) {
	for _, p := range s.problems {
		d.problem(where, "%s", p)
	}
	if s.component {
		if s.componentID == "" {
			d.problem(where, "it reads a component with no id")
		}
		if s.kind == pb.Kind_WORKFLOW {
			d.require(16, "%s reads the workflow %q", where, s.componentID)
		}
		return
	}
	if strings.TrimSpace(s.topic) == "" {
		d.problem(where, "it reads a topic with no name")
		return
	}
	if consumer && s.start == nil {
		d.problem(where, "it reads the topic %q and declares no start position: give Topic StartFrom(Earliest()), StartFrom(Latest()) or StartFrom(StartAt(t))", s.topic)
	}
	if s.start != nil {
		d.require(7, "%s declares where it starts reading %q", where, s.topic)
	}
	if s.contract != nil {
		if !contractName.MatchString(s.contract.Name) || !strings.HasPrefix(s.contract.Fingerprint, "sha256:") {
			d.problem(where, "the contract expected of %q is not one ContractFromFile made", s.topic)
		}
	}
	if s.broker != "" && strings.TrimSpace(s.broker) == "" {
		d.problem(where, "the broker of %q is blank", s.topic)
	}
	if s.contract != nil || s.broker != "" || s.parallel {
		d.require(14, "%s declares a contract, a broker or parallel reading of %q", where, s.topic)
	}
	if s.project != "" {
		if strings.TrimSpace(s.project) == "" {
			d.problem(where, "the project of %q is blank", s.topic)
		}
		if s.broker != "" {
			d.problem(where, "it names a project and a broker for %q; another project's topic is on the installation's broker", s.topic)
		}
		d.require(15, "%s reads another project's topic %q", where, s.topic)
	}
}

func (s Source) toPb() *pb.Source {
	if s.component {
		return &pb.Source{Source: &pb.Source_Component{Component: &pb.Source_ComponentRef{Kind: s.kind, Id: s.componentID}}}
	}
	out := &pb.Source{Source: &pb.Source_Topic{Topic: s.topic}}
	if s.start != nil {
		out.StartFrom = s.start.toPb()
	}
	if s.contract != nil {
		out.Contract = &pb.Contract{Name: s.contract.Name, Fingerprint: s.contract.Fingerprint}
	}
	if s.broker != "" {
		b := s.broker
		out.Broker = &b
	}
	if s.parallel {
		p := true
		out.Parallel = &p
	}
	if s.project != "" {
		p := s.project
		out.Project = &p
	}
	return out
}

// changeCodec is the codec a handler of Src reads the source's changes with: the component's own
// when it is one of Src, CodecFor[Src] for a topic.
func changeCodec[Src any](s Source) (Codec[Src], string) {
	if s.codec == nil {
		return CodecFor[Src](), ""
	}
	c, ok := s.codec.(Codec[Src])
	if !ok {
		return CodecFor[Src](), fmt.Sprintf("%s are not %s values; the handler reads them as the component writes them", s.describe(), reflect.TypeFor[Src]().String())
	}
	return c, ""
}

// ---- Publications ----

// Publication is the topic a consumer produces to, with the contract it states and its broker.
type Publication struct {
	topic    string
	contract *Contract
	broker   string
	project  string
}

// PublicationOption is an option of a publication.
type PublicationOption func(*Publication)

// StatesContract is the contract the published messages carry (protocol 1.14).
func StatesContract(c Contract) PublicationOption { return func(p *Publication) { p.contract = &c } }

// ToBroker publishes on a declared broker; without it, the installation's (protocol 1.14).
func ToBroker(name string) PublicationOption { return func(p *Publication) { p.broker = name } }

// ToProject publishes to another project's topic, which that project must grant produce on
// (protocol 1.15).
func ToProject(project string) PublicationOption { return func(p *Publication) { p.project = project } }

func (p Publication) check(d *declarations, where string) {
	if strings.TrimSpace(p.topic) == "" {
		d.problem(where, "it produces to a topic with no name")
		return
	}
	if p.contract != nil || p.broker != "" {
		d.require(14, "%s states a contract or a broker for %q", where, p.topic)
	}
	if p.project != "" {
		if p.broker != "" {
			d.problem(where, "it names a project and a broker for %q; another project's topic is on the installation's broker", p.topic)
		}
		d.require(15, "%s produces to another project's topic %q", where, p.topic)
	}
}

func (p Publication) toPb() *pb.Publication {
	out := &pb.Publication{Topic: p.topic}
	if p.contract != nil {
		out.Contract = &pb.Contract{Name: p.contract.Name, Fingerprint: p.contract.Fingerprint}
	}
	if p.broker != "" {
		b := p.broker
		out.Broker = &b
	}
	if p.project != "" {
		pr := p.project
		out.Project = &pr
	}
	return out
}

// ---- The change a handler is handling ----

// Standing is where a workflow stood once the effect that recorded a state was applied.
type Standing struct {
	// Status is NotStarted, Running, Paused, Completed, Failed, or Unknown for a state recorded
	// before the platform stamped standings.
	Status string
	// Step is the step the workflow is on, or the step a pause names or follows.
	Step string
	// Retries are the retries so far, per step.
	Retries map[string]int
	// Failure is why it failed, when it did.
	Failure string
}

// Terminal is whether the workflow has completed or failed.
func (s Standing) Terminal() bool { return s.Status == "Completed" || s.Status == "Failed" }

func standingFromPb(s *pb.WorkflowStanding) *Standing {
	if s == nil {
		return nil
	}
	out := &Standing{Status: s.GetStatus(), Step: s.GetStep(), Failure: s.GetFailure(), Retries: map[string]int{}}
	for k, v := range s.GetRetries() {
		out.Retries[k] = int(v)
	}
	return out
}

// ChangeScope is the change a view's or a consumer's handler is handling.
type ChangeScope struct {
	ComponentID string
	// Subject is the id of the source instance the change came from (ce-subject).
	Subject string
	// Sequence is the change's sequence number (an event's, or a key value state's revision), 0
	// when it has none, as a topic's message has none.
	Sequence int64
	// SourceID is, for a keyed view, the component the change came from.
	SourceID string
	// Deleted is whether the source instance was deleted.
	Deleted bool
	// Standing is where a workflow source stood, or nil for a change from an entity or a topic.
	Standing *Standing
	// Installation is where a replicated entity's change was written (protocol 1.19), else "".
	Installation string
	// Local is whether the change was written at this installation: false only for a replicated
	// entity's mirrored change.
	Local bool
	// Metadata is the change's metadata as it arrived.
	Metadata Metadata
}

type changeKey struct{}

func withChange(ctx context.Context, c ChangeScope) context.Context {
	return context.WithValue(ctx, changeKey{}, c)
}

// ChangeFrom is the change ctx's handler is handling, when it is a view's or a consumer's.
func ChangeFrom(ctx context.Context) (ChangeScope, bool) {
	c, ok := ctx.Value(changeKey{}).(ChangeScope)
	return c, ok
}

func changeScope(componentID string, md Metadata, deleted bool, standing *pb.WorkflowStanding, sourceID string) ChangeScope {
	c := ChangeScope{
		ComponentID:  componentID,
		Subject:      md["ce-subject"],
		SourceID:     sourceID,
		Deleted:      deleted,
		Standing:     standingFromPb(standing),
		Installation: md["ankka.installation"],
		Local:        md["ankka.local"] != "false",
		Metadata:     md,
	}
	if n, err := strconv.ParseInt(md["ankka.sequence"], 10, 64); err == nil {
		c.Sequence = n
	}
	return c
}

// requireSeveralMessages refuses an answer of several messages, or a record key, unless the
// request says the runtime accepts them: a runtime before 1.3 reads the reply as no effect and
// records the change handled with nothing published, so the change is failed instead and comes
// again, visibly.
func requireSeveralMessages(md Metadata) error {
	declared := strings.TrimSpace(md["ankka.protocol"])
	if minor, ok := protocolMinor(declared); ok && minor >= 3 {
		return nil
	}
	if declared == "" {
		declared = "1.2 or earlier"
	}
	return fmt.Errorf("this runtime speaks protocol %s; several messages or a record key need 1.3", declared)
}
