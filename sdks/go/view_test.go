package ankka

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

type tCartRow struct {
	CartID string `json:"cartId"`
	Items  int    `json:"items"`
}

var tCartRows = View[tCartRow]("t-cart-rows",
	OnChange(Changes(tCartEntity), func(ctx context.Context, e tEvent, row *tCartRow) (ViewEffect[tCartRow], error) {
		c, _ := ChangeFrom(ctx)
		r := tCartRow{CartID: c.Subject}
		if row != nil {
			r = *row
		}
		if a, ok := e.(tAdded); ok {
			if a.Item.ProductID == "skip" {
				return IgnoreChange[tCartRow](), nil
			}
			r.Items += a.Item.Quantity
		}
		return UpdateRow(r), nil
	}),
	Queries(Declared("big", "SELECT payload FROM "+TableOf("t-cart-rows")+" WHERE (payload->>'items')::int > :n")),
)

func viewRequest(t *testing.T, view string, event any, row any, md map[string]string) *pb.ViewRequest {
	t.Helper()
	req := &pb.ViewRequest{ComponentId: view, Metadata: metadataToPb(md)}
	if event != nil {
		p, err := payloadOf(CodecFor[tEvent](), event.(tEvent))
		if err != nil {
			t.Fatal(err)
		}
		req.Event = p
	}
	if row != nil {
		p, err := encodeAny(row)
		if err != nil {
			t.Fatal(err)
		}
		req.Row = p
	}
	return req
}

func TestAViewWritesItsRow(t *testing.T) {
	svc := NewService(tCartEntity, tCartRows)
	if p := svc.Problems(); len(p) > 0 {
		t.Fatalf("problems: %v", p)
	}
	client := pb.NewViewClient(loopback(t, svc))
	ctx := context.Background()
	md := map[string]string{"ce-subject": "c1", "ankka.sequence": "1"}

	out, err := client.Handle(ctx, viewRequest(t, "t-cart-rows", tAdded{tItem{"ink", 2}}, nil, md))
	if err != nil || string(out.GetUpdateRow().GetData()) != `{"cartId":"c1","items":2}` {
		t.Fatalf("a first change answered %v, %v", out, err)
	}
	out, err = client.Handle(ctx, viewRequest(t, "t-cart-rows", tAdded{tItem{"pen", 3}}, tCartRow{"c1", 2}, md))
	if err != nil || string(out.GetUpdateRow().GetData()) != `{"cartId":"c1","items":5}` {
		t.Fatalf("a change to a row answered %v, %v", out, err)
	}
	if out, _ := client.Handle(ctx, viewRequest(t, "t-cart-rows", tAdded{tItem{"skip", 1}}, nil, md)); out.GetIgnore() == nil {
		t.Fatalf("an ignored change answered %v", out)
	}
	deleted := viewRequest(t, "t-cart-rows", nil, tCartRow{"c1", 5}, md)
	deleted.Deleted = true
	if out, _ := client.Handle(ctx, deleted); out.GetDeleteRow() == nil {
		t.Fatalf("a deletion answered %v", out)
	}
	if _, err := client.Handle(ctx, &pb.ViewRequest{ComponentId: "nobody"}); status.Code(err) != codes.NotFound {
		t.Fatalf("an unknown view answered %v", err)
	}
	bad := viewRequest(t, "t-cart-rows", nil, nil, md)
	bad.Event = &pb.Payload{ContentType: JSONContent, Manifest: "nonsense", Data: []byte("{")}
	if _, err := client.Handle(ctx, bad); status.Code(err) != codes.Internal || !strings.Contains(err.Error(), "cannot be read") {
		t.Fatalf("an unreadable change answered %v", err)
	}
}

func TestAViewDeclaresItsSourceAndQueries(t *testing.T) {
	c := NewService(tCartEntity, tCartRows).Spec().GetComponents()[1]
	v := c.GetView()
	if c.GetKind() != pb.Kind_VIEW || v.GetSource().GetComponent().GetId() != "t-cart" || v.GetSource().GetComponent().GetKind() != pb.Kind_EVENT_SOURCED_ENTITY ||
		v.GetRowManifest() == "" || len(v.GetDeclaredQueries()) != 1 || !strings.Contains(v.GetDeclaredQueries()[0].GetStatement(), "ankka_view_t_cart_rows") {
		t.Fatalf("declared %v", c)
	}
	topical := View[tCartRow]("from-topic",
		OnChange(Topic("carts", StartFrom(Latest()), Parallel(), OnBroker("events")), func(context.Context, tCartRow, *tCartRow) (ViewEffect[tCartRow], error) {
			return IgnoreChange[tCartRow](), nil
		}),
		SourceVersion(3),
		Queries(WatchedQuery("all-big", "SELECT row_key, payload FROM "+TableOf("from-topic"))))
	svc := NewService(topical)
	src := svc.Spec().GetComponents()[0].GetView().GetSource()
	if src.GetTopic() != "carts" || src.GetStartFrom().GetNamed() != pb.StartFrom_LATEST || !src.GetParallel() || src.GetBroker() != "events" ||
		svc.Spec().GetComponents()[0].GetView().GetVersion() != 3 {
		t.Fatalf("a topic source declared %v", src)
	}
	if why := svc.decls.refusal("1.16"); !strings.Contains(why, "needs 1.17") || !strings.Contains(why, "all-big") {
		t.Fatalf("a watched query on 1.16 is refused as %q", why)
	}
	at := Topic("t", StartFrom(StartAt(time.UnixMilli(1700000000000))))
	if at.toPb().GetStartFrom().GetAtMillis() != 1700000000000 {
		t.Fatal("a start time is not declared in milliseconds")
	}
}

func TestAViewsProblemsAreNamed(t *testing.T) {
	noop := func(context.Context, tCartRow, *tCartRow) (ViewEffect[tCartRow], error) {
		return IgnoreChange[tCartRow](), nil
	}
	for name, c := range map[string]struct {
		decl Declaration
		want string
	}{
		"no source":       {View[tCartRow]("v"), "one OnChange"},
		"two sources":     {View[tCartRow]("v", OnChange(Topic("a"), noop), OnChange(Topic("b"), noop)), "a KeyedView reads several"},
		"the wrong type":  {View[tCartRow]("v", OnChange(Changes(tCartEntity), noop)), "are not ankka.tCartRow values"},
		"an option":       {View[tCartRow]("v", OnChange(Topic("a"), noop), SnapshotEvery(2)), "not an option a view takes"},
		"no start given":  {View[tCartRow]("v", OnChange(Topic("a", StartFrom(StartPosition{})), noop)), "given no position"},
		"project+broker":  {View[tCartRow]("v", OnChange(Topic("a", FromProject("p"), OnBroker("b")), noop)), "names a project and a broker"},
		"unnamed query":   {View[tCartRow]("v", OnChange(Topic("a"), noop), Queries(Declared("", "SELECT 1"))), "has no name"},
		"version below 1": {View[tCartRow]("v", OnChange(Topic("a"), noop), SourceVersion(-1)), "a version is a whole number of 1 or more"},
	} {
		text := strings.Join(serviceProblems(NewService(c.decl)), "\n")
		if !strings.Contains(text, c.want) {
			t.Errorf("%s: problems do not say %q:\n%s", name, c.want, text)
		}
	}
}

type tShipment struct {
	ID       string `json:"id"`
	Customer string `json:"customer"`
	Name     string `json:"name"`
}

// tRows are a keyed view's rows in memory, as a test stands them up.
type tRows map[string]tShipment

func (r tRows) Get(key string) (tShipment, bool, error) { s, ok := r[key]; return s, ok, nil }
func (r tRows) Ask(name string, values map[string]string) ([]tShipment, error) {
	var out []tShipment
	for _, s := range r {
		if s.Customer == values["customer"] {
			out = append(out, s)
		}
	}
	return out, nil
}

var tShipments = KeyedView[tShipment]("t-shipments",
	On(Changes(tCartEntity), func(ctx context.Context, e tEvent, rows ViewRows[tShipment]) (KeyedViewEffect[tShipment], error) {
		c, _ := ChangeFrom(ctx)
		a := e.(tAdded)
		return UpdateRowAt(c.Subject+"/"+a.Item.ProductID, tShipment{ID: a.Item.ProductID, Customer: c.Subject}), nil
	}, func(ctx context.Context, rows ViewRows[tShipment]) (KeyedViewEffect[tShipment], error) {
		c, _ := ChangeFrom(ctx)
		theirs, err := rows.Ask("of-customer", map[string]string{"customer": c.Subject})
		if err != nil {
			return KeyedViewEffect[tShipment]{}, err
		}
		var all []KeyedViewEffect[tShipment]
		for _, s := range theirs {
			all = append(all, DeleteRowAt[tShipment](c.Subject+"/"+s.ID))
		}
		return AllRows(all...), nil
	}),
	On(Changes(tProfileEntity), func(ctx context.Context, p tProfile, rows ViewRows[tShipment]) (KeyedViewEffect[tShipment], error) {
		theirs, err := rows.Ask("of-customer", map[string]string{"customer": p.ID})
		if err != nil {
			return KeyedViewEffect[tShipment]{}, err
		}
		var all []KeyedViewEffect[tShipment]
		for _, s := range theirs {
			s.Name = p.Name
			all = append(all, UpdateRowAt(p.ID+"/"+s.ID, s))
		}
		return AllRows(all...), nil
	}),
	Queries(Declared("of-customer", "SELECT payload FROM "+TableOf("t-shipments")+" WHERE payload->>'customer' = :customer")),
)

func TestAKeyedViewWritesRowsByKey(t *testing.T) {
	svc := NewService(tCartEntity, tProfileEntity, tShipments)
	if p := svc.Problems(); len(p) > 0 {
		t.Fatalf("problems: %v", p)
	}
	d := svc.Spec().GetComponents()[2].GetView()
	if d.Source != nil || len(d.GetSources()) != 2 || d.GetSources()[1].GetComponent().GetKind() != pb.Kind_KEY_VALUE_ENTITY {
		t.Fatalf("declared %v", d)
	}
	if why := svc.decls.refusal("1.12"); !strings.Contains(why, "needs 1.13") {
		t.Fatalf("a keyed view on 1.12 is refused as %q", why)
	}
	rows := tRows{"ada/ink": {ID: "ink", Customer: "ada"}, "ada/pen": {ID: "pen", Customer: "ada"}, "bob/ink": {ID: "ink", Customer: "bob"}}
	ctx := context.WithValue(context.Background(), rowsKey{}, ViewRows[tShipment](rows))
	md := metadataToPb(Metadata{"ce-subject": "ada"})
	ev, _ := payloadOf(CodecFor[tEvent](), tEvent(tAdded{tItem{"cup", 1}}))
	out, err := svc.decls.views["t-shipments"](ctx, &pb.ViewRequest{ComponentId: "t-shipments", SourceId: ptr("t-cart"), Event: ev, Metadata: md})
	if err != nil || len(out.GetRows().GetChanges()) != 1 || out.GetRows().GetChanges()[0].GetKey() != "ada/cup" {
		t.Fatalf("an added item wrote %v, %v", out, err)
	}
	profile, _ := payloadOf(CodecFor[tProfile](), tProfile{ID: "ada", Name: "Ada"})
	out, err = svc.decls.views["t-shipments"](ctx, &pb.ViewRequest{ComponentId: "t-shipments", SourceId: ptr("t-profile"), Event: profile, Metadata: md})
	if err != nil || len(out.GetRows().GetChanges()) != 2 || !strings.Contains(string(out.GetRows().GetChanges()[0].GetUpsert().GetData()), `"name":"Ada"`) {
		t.Fatalf("a renamed customer wrote %v, %v", out, err)
	}
	out, err = svc.decls.views["t-shipments"](ctx, &pb.ViewRequest{ComponentId: "t-shipments", SourceId: ptr("t-cart"), Deleted: true, Metadata: md})
	if err != nil || len(out.GetRows().GetChanges()) != 2 || out.GetRows().GetChanges()[0].GetDelete() == nil {
		t.Fatalf("a deleted cart wrote %v, %v", out, err)
	}
	// A profile's deletion names no handler, so it writes nothing.
	out, err = svc.decls.views["t-shipments"](ctx, &pb.ViewRequest{ComponentId: "t-shipments", SourceId: ptr("t-profile"), Deleted: true, Metadata: md})
	if err != nil || out.GetRows() == nil || len(out.GetRows().GetChanges()) != 0 {
		t.Fatalf("a deleted profile wrote %v, %v", out, err)
	}
	if _, err := svc.decls.views["t-shipments"](ctx, &pb.ViewRequest{ComponentId: "t-shipments", SourceId: ptr("t-order")}); err == nil || !strings.Contains(err.Error(), `reads no "t-order"`) {
		t.Fatalf("a change from a source it does not read answered %v", err)
	}
	empty := AllRows(UpdateRowAt("", tShipment{}))
	if _, err := materialiseKeyed(empty, CodecFor[tShipment]()); err == nil {
		t.Fatal("an empty row key is written")
	}
}

func TestAKeyedViewsProblemsAreNamed(t *testing.T) {
	f := func(context.Context, tEvent, ViewRows[tShipment]) (KeyedViewEffect[tShipment], error) {
		return IgnoreRows[tShipment](), nil
	}
	text := strings.Join(serviceProblems(NewService(KeyedView[tShipment]("k",
		On(Changes(tCartEntity), f), On(Changes(tCartEntity), f), On(Topic("x", StartFrom(Earliest())), f), On(Changes(tOrderFlow), f)))), "\n")
	for _, want := range []string{`"t-cart" is read by two On`, `the topic "x" is not one`, `"t-order" is not one`} {
		if !strings.Contains(text, want) {
			t.Errorf("problems do not say %q:\n%s", want, text)
		}
	}
	if !strings.Contains(strings.Join(serviceProblems(NewService(KeyedView[tShipment]("k"))), "\n"), "at least one On") {
		t.Error("a keyed view with no source is not a problem")
	}
}
