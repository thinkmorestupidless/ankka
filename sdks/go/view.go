package ankka

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"regexp"
	"strings"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

// ViewComponent is a view's declaration: a queryable projection of one source's changes, one row
// per source instance. The sidecar runs the projection and keeps the rows; this process says what
// each change does to its row.
type ViewComponent[Row any] struct {
	id        string
	changes   []viewChange[Row]
	onDelete  func(context.Context, *Row) (ViewEffect[Row], error)
	rowCodec  Codec[Row]
	declared  []DeclaredQuery
	version   int
	misplaced []Part
}

// viewChange is a view's handler of one source's changes.
type viewChange[Row any] interface {
	Part
	source() Source
	codecProblem() string
	run(ctx context.Context, change *pb.Payload, row *Row) (ViewEffect[Row], error)
}

type onChangePart[Src, Row any] struct {
	src     Source
	codec   Codec[Src]
	problem string
	f       func(context.Context, Src, *Row) (ViewEffect[Row], error)
}

func (*onChangePart[Src, Row]) part()                  {}
func (o *onChangePart[Src, Row]) source() Source       { return o.src }
func (o *onChangePart[Src, Row]) codecProblem() string { return o.problem }

func (o *onChangePart[Src, Row]) run(ctx context.Context, change *pb.Payload, row *Row) (ViewEffect[Row], error) {
	v, err := valueOf(o.codec, change)
	if err != nil {
		return ViewEffect[Row]{}, fmt.Errorf("the change cannot be read as a %s: %w", reflect.TypeFor[Src](), err)
	}
	return o.f(ctx, v, row)
}

// OnChange is a view's handler of source's changes: given the change and the current row (nil
// when there is none yet), it answers what becomes of the row. An error, or a panic, fails the
// change, which comes again.
func OnChange[Src, Row any](source Source, f func(ctx context.Context, change Src, row *Row) (ViewEffect[Row], error)) Part {
	c, problem := changeCodec[Src](source)
	return &onChangePart[Src, Row]{src: source, codec: c, problem: problem, f: f}
}

type onDeletePart[Row any] struct {
	f func(context.Context, *Row) (ViewEffect[Row], error)
}

func (onDeletePart[Row]) part() {}

// OnDelete is what a view does when its source instance is deleted; without it, the row is
// deleted with it. An order history keeps a checked-out cart's row.
func OnDelete[Row any](f func(ctx context.Context, row *Row) (ViewEffect[Row], error)) Part {
	return onDeletePart[Row]{f: f}
}

// DeclaredQuery is a question a view can be asked by name: one SQL statement over the view's own
// table (TableOf), whose values are the :names it holds. The platform checks it when the service
// starts and binds each value as a parameter.
type DeclaredQuery struct {
	Name      string
	Statement string
	// Watched is whether it may be watched (protocol 1.17): its statement selects row_key beside
	// payload, has no limit and does not aggregate.
	Watched bool
}

// Declared is a declared query.
func Declared(name, statement string) DeclaredQuery {
	return DeclaredQuery{Name: name, Statement: statement}
}

// WatchedQuery is a declared query that may also be watched.
func WatchedQuery(name, statement string) DeclaredQuery {
	return DeclaredQuery{Name: name, Statement: statement, Watched: true}
}

type queriesPart struct{ queries []DeclaredQuery }

func (queriesPart) part() {}

// Queries are the view's declared queries (protocol 1.13). A recursive one is a statement like any
// other, a WITH RECURSIVE over the view's table.
func Queries(queries ...DeclaredQuery) Part { return queriesPart{queries: queries} }

type sourceVersionPart struct{ n int }

func (sourceVersionPart) part() {}

// SourceVersion is the version of what a view or consumer reads; raising it has a view emptied and
// read again from its start, and a consumer over a topic read again under a group of its own. 1
// when unsaid.
func SourceVersion(n int) Part { return sourceVersionPart{n: n} }

type rowCodecPart[Row any] struct{ codec Codec[Row] }

func (rowCodecPart[Row]) part() {}

// RowCodec encodes a view's rows by c in place of the default codec.
func RowCodec[Row any](c Codec[Row]) Part { return rowCodecPart[Row]{codec: c} }

var nonWord = regexp.MustCompile(`[^A-Za-z0-9]`)

// TableOf is the table a view's rows are kept in, for a declared query's statement to name.
func TableOf(viewID string) string { return "ankka_view_" + nonWord.ReplaceAllString(viewID, "_") }

// View declares a view of one source: its handler (OnChange) and its options (OnDelete, Queries,
// SourceVersion, RowCodec).
func View[Row any](id string, parts ...Part) *ViewComponent[Row] {
	v := &ViewComponent[Row]{id: id, rowCodec: CodecFor[Row]()}
	for _, p := range parts {
		switch p := p.(type) {
		case viewChange[Row]:
			v.changes = append(v.changes, p)
		case onDeletePart[Row]:
			v.onDelete = p.f
		case queriesPart:
			v.declared = append(v.declared, p.queries...)
		case sourceVersionPart:
			v.version = p.n
		case rowCodecPart[Row]:
			v.rowCodec = p.codec
		default:
			v.misplaced = append(v.misplaced, p)
		}
	}
	return v
}

// ID is the view's component id.
func (v *ViewComponent[Row]) ID() string { return v.id }

func declaredProblems(d *declarations, where string, queries []DeclaredQuery) []*pb.DeclaredQuery {
	var out []*pb.DeclaredQuery
	seen := map[string]bool{}
	for _, q := range queries {
		if strings.TrimSpace(q.Name) == "" {
			d.problem(where, "a declared query has no name")
		} else if seen[q.Name] {
			d.problem(where, "two declared queries are named %q", q.Name)
		}
		seen[q.Name] = true
		if strings.TrimSpace(q.Statement) == "" {
			d.problem(where, "the declared query %q has no statement", q.Name)
		}
		if q.Watched {
			d.require(17, "%s declares the watched query %q", where, q.Name)
		}
		out = append(out, &pb.DeclaredQuery{Name: q.Name, Statement: q.Statement, Watched: q.Watched})
	}
	if len(queries) > 0 {
		d.require(13, "%s declares queries", where)
	}
	return out
}

func versionToPb(d *declarations, where string, n int) *uint32 {
	if n == 0 {
		return nil
	}
	if n < 1 || n > 1<<31 {
		d.problem(where, "declares version %d; a version is a whole number of 1 or more", n)
		return nil
	}
	u := uint32(n)
	return &u
}

func (v *ViewComponent[Row]) declare(d *declarations) {
	where := v.id
	if v.id == "" {
		where = fmt.Sprintf("view of %s", reflect.TypeFor[Row]())
		d.problem(where, "a view has an id")
	}
	var source Source
	switch len(v.changes) {
	case 0:
		d.problem(where, "a view has one OnChange, naming its source")
	case 1:
		source = v.changes[0].source()
		source.check(d, where, false)
		if p := v.changes[0].codecProblem(); p != "" {
			d.problem(where, "%s", p)
		}
	default:
		d.problem(where, "a view reads one source and has %d OnChange; a KeyedView reads several", len(v.changes))
	}
	for _, p := range CodecProblems(v.rowCodec) {
		d.problem(where, "its row, a %s: %s", reflect.TypeFor[Row](), p)
	}
	for _, p := range v.misplaced {
		misplaced(d, where, "view", p)
	}
	if v.version != 0 && !source.component {
		d.require(7, "%s declares a version", where)
	}
	if v.version != 0 && source.component {
		d.require(13, "%s declares a version over a component", where)
	}
	detail := &pb.ViewDetail{
		RowManifest:     v.rowCodec.Manifest(),
		Queries:         []string{"get", "all"},
		Version:         versionToPb(d, where, v.version),
		DeclaredQueries: declaredProblems(d, where, v.declared),
	}
	if len(v.changes) == 1 {
		detail.Source = source.toPb()
	}
	d.components = append(d.components, &pb.Component{Kind: pb.Kind_VIEW, Id: v.id, Detail: &pb.Component_View{View: detail}})
	d.views[v.id] = v.handle
}

// handle is one change to the view: its row's fate.
func (v *ViewComponent[Row]) handle(ctx context.Context, req *pb.ViewRequest) (*pb.ViewEffect, error) {
	md := metadataFromPb(req.GetMetadata())
	ctx = withChange(withMetadata(withoutSecrets(ctx, "a view"), md), changeScope(v.id, md, req.GetDeleted(), req.GetStanding(), ""))
	var row *Row
	if p := req.GetRow(); p != nil {
		r, err := valueOf(v.rowCodec, p)
		if err != nil {
			return nil, fmt.Errorf("the row cannot be read as a %s: %w", reflect.TypeFor[Row](), err)
		}
		row = &r
	}
	var eff ViewEffect[Row]
	var err error
	switch {
	case req.GetDeleted() && v.onDelete != nil:
		eff, err = v.onDelete(ctx, row)
	case req.GetDeleted():
		eff = DeleteRow[Row]()
	case len(v.changes) == 1:
		eff, err = v.changes[0].run(ctx, req.GetEvent(), row)
	default:
		return nil, errors.New("the view has no handler")
	}
	if err != nil {
		return nil, err
	}
	return materialiseView(eff, v.rowCodec)
}

func materialiseView[Row any](eff ViewEffect[Row], rc Codec[Row]) (*pb.ViewEffect, error) {
	switch eff.kind {
	case viewUpdate:
		p, err := payloadOf(rc, eff.row)
		if err != nil {
			return nil, fmt.Errorf("the row cannot be encoded: %w", err)
		}
		return &pb.ViewEffect{Effect: &pb.ViewEffect_UpdateRow{UpdateRow: p}}, nil
	case viewDelete:
		return &pb.ViewEffect{Effect: &pb.ViewEffect_DeleteRow{DeleteRow: &pb.Empty{}}}, nil
	case viewIgnore:
		return &pb.ViewEffect{Effect: &pb.ViewEffect_Ignore{Ignore: &pb.Empty{}}}, nil
	}
	return nil, errors.New("the handler returned no effect: build one with UpdateRow, DeleteRow or IgnoreChange")
}

// ---- Keyed views ----

// ViewRows are a keyed view's own rows, for the length of one change. They reach no other view.
type ViewRows[Row any] interface {
	// Get is the row under key, and whether there is one.
	Get(key string) (Row, bool, error)
	// Ask is the rows one of the view's declared queries answers, given its values.
	Ask(name string, values map[string]string) ([]Row, error)
}

// clientRows are a keyed view's rows read through the sidecar's client, on the view's own id.
type clientRows[Row any] struct {
	ctx    context.Context
	viewID string
}

func (r clientRows[Row]) Get(key string) (Row, bool, error) {
	return ViewRow[Row](r.ctx, r.viewID, key)
}
func (r clientRows[Row]) Ask(name string, values map[string]string) ([]Row, error) {
	return QueryView[Row](r.ctx, r.viewID, Named(name, values))
}

type rowsKey struct{}

// keyedChange is a keyed view's handler of one source's changes.
type keyedChange[Row any] interface {
	Part
	source() Source
	codecProblem() string
	run(ctx context.Context, change *pb.Payload, rows ViewRows[Row]) (KeyedViewEffect[Row], error)
	deleted(ctx context.Context, rows ViewRows[Row]) (KeyedViewEffect[Row], error)
}

type onKeyedPart[Src, Row any] struct {
	src       Source
	codec     Codec[Src]
	problem   string
	f         func(context.Context, Src, ViewRows[Row]) (KeyedViewEffect[Row], error)
	onDeleted func(context.Context, ViewRows[Row]) (KeyedViewEffect[Row], error)
}

func (*onKeyedPart[Src, Row]) part()                  {}
func (o *onKeyedPart[Src, Row]) source() Source       { return o.src }
func (o *onKeyedPart[Src, Row]) codecProblem() string { return o.problem }

func (o *onKeyedPart[Src, Row]) run(ctx context.Context, change *pb.Payload, rows ViewRows[Row]) (KeyedViewEffect[Row], error) {
	v, err := valueOf(o.codec, change)
	if err != nil {
		return KeyedViewEffect[Row]{}, fmt.Errorf("the change cannot be read as a %s: %w", reflect.TypeFor[Src](), err)
	}
	return o.f(ctx, v, rows)
}

func (o *onKeyedPart[Src, Row]) deleted(ctx context.Context, rows ViewRows[Row]) (KeyedViewEffect[Row], error) {
	if o.onDeleted == nil {
		return IgnoreRows[Row](), nil
	}
	return o.onDeleted(ctx, rows)
}

// On is a keyed view's handler of one entity's changes: given the change and the view's own rows,
// it answers which rows to write and delete, by key. deleted, when given, is what the view does when
// the entity is deleted; without it, nothing.
func On[Src, Row any](source Source, f func(ctx context.Context, change Src, rows ViewRows[Row]) (KeyedViewEffect[Row], error), deleted ...func(ctx context.Context, rows ViewRows[Row]) (KeyedViewEffect[Row], error)) Part {
	c, problem := changeCodec[Src](source)
	o := &onKeyedPart[Src, Row]{src: source, codec: c, problem: problem, f: f}
	if len(deleted) > 0 {
		o.onDeleted = deleted[0]
	}
	return o
}

// KeyedViewComponent is a keyed view's declaration: several entities' changes, each through a
// handler of its own, written to whichever rows a change is about, by key. The sidecar handles one
// change at a time across all of them (protocol 1.13).
type KeyedViewComponent[Row any] struct {
	id        string
	sources   []keyedChange[Row]
	rowCodec  Codec[Row]
	declared  []DeclaredQuery
	version   int
	misplaced []Part
}

// KeyedView declares a keyed view: one On per entity it reads, and its options (Queries,
// SourceVersion, RowCodec).
func KeyedView[Row any](id string, parts ...Part) *KeyedViewComponent[Row] {
	v := &KeyedViewComponent[Row]{id: id, rowCodec: CodecFor[Row]()}
	for _, p := range parts {
		switch p := p.(type) {
		case keyedChange[Row]:
			v.sources = append(v.sources, p)
		case queriesPart:
			v.declared = append(v.declared, p.queries...)
		case sourceVersionPart:
			v.version = p.n
		case rowCodecPart[Row]:
			v.rowCodec = p.codec
		default:
			v.misplaced = append(v.misplaced, p)
		}
	}
	return v
}

// ID is the keyed view's component id.
func (v *KeyedViewComponent[Row]) ID() string { return v.id }

func (v *KeyedViewComponent[Row]) declare(d *declarations) {
	where := v.id
	if v.id == "" {
		where = fmt.Sprintf("keyed view of %s", reflect.TypeFor[Row]())
		d.problem(where, "a keyed view has an id")
	}
	if len(v.sources) == 0 {
		d.problem(where, "a keyed view has at least one On, naming an entity it reads")
	}
	seen := map[string]bool{}
	var sources []*pb.Source
	for _, s := range v.sources {
		src := s.source()
		switch {
		case !src.component:
			d.problem(where, "a keyed view reads entities, and %s is not one", src.describe())
		case src.kind != pb.Kind_EVENT_SOURCED_ENTITY && src.kind != pb.Kind_KEY_VALUE_ENTITY:
			d.problem(where, "a keyed view reads entities, and %q is not one", src.componentID)
		case seen[src.componentID]:
			d.problem(where, "%q is read by two On; one handler reads one entity", src.componentID)
		}
		seen[src.componentID] = true
		src.check(d, where, false)
		if p := s.codecProblem(); p != "" {
			d.problem(where, "%s", p)
		}
		sources = append(sources, src.toPb())
	}
	for _, p := range CodecProblems(v.rowCodec) {
		d.problem(where, "its row, a %s: %s", reflect.TypeFor[Row](), p)
	}
	for _, p := range v.misplaced {
		misplaced(d, where, "keyed view", p)
	}
	d.require(13, "%s is a keyed view", where)
	d.components = append(d.components, &pb.Component{Kind: pb.Kind_VIEW, Id: v.id, Detail: &pb.Component_View{View: &pb.ViewDetail{
		RowManifest:     v.rowCodec.Manifest(),
		Version:         versionToPb(d, where, v.version),
		Sources:         sources,
		DeclaredQueries: declaredProblems(d, where, v.declared),
	}}})
	d.views[v.id] = v.handle
}

func (v *KeyedViewComponent[Row]) handle(ctx context.Context, req *pb.ViewRequest) (*pb.ViewEffect, error) {
	sourceID := req.GetSourceId()
	var src keyedChange[Row]
	for _, s := range v.sources {
		if s.source().componentID == sourceID {
			src = s
		}
	}
	if src == nil {
		return nil, fmt.Errorf("the keyed view %s reads no %q", v.id, sourceID)
	}
	md := metadataFromPb(req.GetMetadata())
	ctx = withChange(withMetadata(withoutSecrets(ctx, "a view"), md), changeScope(v.id, md, req.GetDeleted(), req.GetStanding(), sourceID))
	rows, ok := ctx.Value(rowsKey{}).(ViewRows[Row])
	if !ok {
		rows = clientRows[Row]{ctx: ctx, viewID: v.id}
	}
	var eff KeyedViewEffect[Row]
	var err error
	if req.GetDeleted() {
		eff, err = src.deleted(ctx, rows)
	} else {
		eff, err = src.run(ctx, req.GetEvent(), rows)
	}
	if err != nil {
		return nil, err
	}
	return materialiseKeyed(eff, v.rowCodec)
}

func materialiseKeyed[Row any](eff KeyedViewEffect[Row], rc Codec[Row]) (*pb.ViewEffect, error) {
	if eff.problem != "" {
		return nil, errors.New(eff.problem)
	}
	changes := &pb.RowChanges{}
	for _, c := range eff.changes {
		if c.Deleted {
			changes.Changes = append(changes.Changes, &pb.RowChange{Key: c.Key, Change: &pb.RowChange_Delete{Delete: &pb.Empty{}}})
			continue
		}
		p, err := payloadOf(rc, c.Row)
		if err != nil {
			return nil, fmt.Errorf("the row under %q cannot be encoded: %w", c.Key, err)
		}
		changes.Changes = append(changes.Changes, &pb.RowChange{Key: c.Key, Change: &pb.RowChange_Upsert{Upsert: p}})
	}
	return &pb.ViewEffect{Effect: &pb.ViewEffect_Rows{Rows: changes}}, nil
}
