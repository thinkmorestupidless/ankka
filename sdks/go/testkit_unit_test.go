package ankka_test

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

type kItem struct {
	ProductID string `json:"productId"`
	Quantity  int    `json:"quantity"`
}

type kCart struct {
	ID    string  `json:"id"`
	Items []kItem `json:"items"`
}

type kEvent interface{ kEvent() }

type kAdded struct {
	Item kItem `json:"item"`
}

type kCleared struct{}

func (kAdded) kEvent()   {}
func (kCleared) kEvent() {}

var kEvents = ankka.Cases[kEvent](ankka.Case[kEvent, kAdded]("Added"), ankka.Case[kEvent, kCleared]("Cleared"))

func kApply(c kCart, e kEvent) kCart {
	switch e := e.(type) {
	case kAdded:
		c.Items = append(append([]kItem(nil), c.Items...), e.Item)
	case kCleared:
		c.Items = nil
	}
	return c
}

func kTotal(c kCart) int {
	n := 0
	for _, i := range c.Items {
		n += i.Quantity
	}
	return n
}

type kEffect[R any] = ankka.Effect[kCart, kEvent, R]

var kEntity = ankka.EventSourced[kCart, kEvent]("k-cart", kEvents,
	func(id string) kCart { return kCart{ID: id, Items: []kItem{}} },
	kApply,
	ankka.Command("add", func(_ context.Context, _ kCart, i kItem) (kEffect[int], error) {
		switch {
		case i.Quantity <= 0:
			return kEffect[int]{}, ankka.Refuse(ankka.BadRequest, "quantity must be positive")
		case i.ProductID == "boom":
			panic("the handler broke")
		case i.ProductID == "fail":
			return kEffect[int]{}, errors.New("the disk is full")
		}
		return ankka.Persist[kCart, kEvent, int](kAdded{i}).ThenReply(kTotal), nil
	}),
	ankka.Command("clear", func(context.Context, kCart, ankka.Unit) (kEffect[ankka.Done], error) {
		return ankka.Persist[kCart, kEvent, ankka.Done](kCleared{}).DeleteEntity().ThenReplyValue(ankka.Done{}), nil
	}),
	ankka.Query("total", func(_ context.Context, c kCart, _ ankka.Unit) (ankka.ReadOnlyEffect[int], error) {
		return ankka.Reply(kTotal(c)), nil
	}),
	ankka.Query("get", func(_ context.Context, c kCart, _ ankka.Unit) (ankka.ReadOnlyEffect[kCart], error) {
		return ankka.Reply(c), nil
	}),
)

func TestTheEntityKitShowsEffectsAsValues(t *testing.T) {
	ctx := context.Background()
	kit := testkit.EventSourced(kEntity, testkit.EntityID("c1"))
	if got := kit.State(); got.ID != "c1" || len(got.Items) != 0 {
		t.Fatalf("the empty state is %+v", got)
	}
	r := kit.Command(ctx, "add", kItem{"pen", 2})
	if r.Failure() != nil || r.Refusal() != nil {
		t.Fatalf("add failed: %v %v", r.Failure(), r.Refusal())
	}
	if ev := r.Events(); len(ev) != 1 || ev[0] != (kAdded{kItem{"pen", 2}}) {
		t.Fatalf("the events are %#v", ev)
	}
	if r.Reply() != 2 || !r.Replied() || kTotal(r.State()) != 2 {
		t.Fatalf("reply %v, state %+v", r.Reply(), r.State())
	}
	kit.Command(ctx, "add", kItem{"ink", 3})
	if q := kit.Query(ctx, "total", ankka.Unit{}); q.Reply() != 5 || len(q.Events()) != 0 {
		t.Fatalf("the total is %v", q.Reply())
	}

	refused := kit.Command(ctx, "add", kItem{"pen", 0})
	if refused.Refusal() == nil || refused.Refusal().Code != ankka.BadRequest || len(refused.Events()) != 0 {
		t.Fatalf("a refusal answered %+v", refused)
	}
	for _, p := range []string{"boom", "fail"} {
		if f := kit.Command(ctx, "add", kItem{p, 1}).Failure(); f == nil {
			t.Fatalf("%s did not fail", p)
		}
	}
	if kTotal(kit.State()) != 5 {
		t.Fatalf("a refusal or a failure changed the state: %+v", kit.State())
	}
	if q := kit.Query(ctx, "add", kItem{"pen", 1}); q.Failure() == nil {
		t.Fatal("a command run as a query was not refused")
	}

	cleared := kit.Command(ctx, "clear", ankka.Unit{})
	if !cleared.Deleted() || cleared.Reply() != (ankka.Done{}) {
		t.Fatalf("clear answered %+v", cleared)
	}
	if q := kit.Query(ctx, "total", ankka.Unit{}); q.Reply() != 0 {
		t.Fatalf("after a deletion the total is %v", q.Reply())
	}
}

type kUnrenderable struct {
	Count uint `json:"count"`
}

func TestAnUnrenderableFieldFailsInTheKitNamingIt(t *testing.T) {
	defer func() {
		r := recover()
		if r == nil || !strings.Contains(fmt.Sprint(r), "uint") {
			t.Fatalf("the kit did not refuse naming the field: %v", r)
		}
	}()
	testkit.EventSourced(ankka.EventSourced[kUnrenderable, kEvent]("bad", kEvents,
		func(string) kUnrenderable { return kUnrenderable{} },
		func(s kUnrenderable, _ kEvent) kUnrenderable { return s },
		ankka.Query("get", func(_ context.Context, s kUnrenderable, _ ankka.Unit) (ankka.ReadOnlyEffect[int], error) {
			return ankka.Reply(0), nil
		}),
	))
}

var kAPI = ankka.NewEndpoint("/carts", ankka.AllowAll(),
	ankka.Post("/{id}/items", func(ctx context.Context, req *ankka.Request, i kItem) (int, error) {
		return ankka.Invoke[int](ctx, "k-cart", req.Param("id"), "add", i)
	}),
	ankka.Get("/{id}", func(ctx context.Context, req *ankka.Request) (kCart, error) {
		return ankka.Invoke[kCart](ctx, "k-cart", req.Param("id"), "get", ankka.Unit{})
	}),
	ankka.Get("/{id}/note", func(_ context.Context, req *ankka.Request) (string, error) {
		return req.Param("id") + ":" + req.Query.Get("q"), nil
	}),
	ankka.Get("/{id}/who", func(_ context.Context, req *ankka.Request) (string, error) {
		return req.Principal.Subject, nil
	}).ACL(ankka.Authenticate(ankka.Issuer("keycloak"))),
)

func TestTheEndpointKitCallsTheComponentsItIsGiven(t *testing.T) {
	ctx := context.Background()
	kit := testkit.Endpoint(kAPI, kEntity)
	defer kit.Close()
	if r := kit.Post(ctx, "/carts/c9/items", kItem{"pen", 2}); r.Status != 200 || r.Text() != "2" {
		t.Fatalf("add answered %d %q %v", r.Status, r.Text(), r.Failure)
	}
	r := kit.Get(ctx, "/carts/c9")
	cart, err := testkit.JSON[kCart](r)
	if err != nil || cart.ID != "c9" || len(cart.Items) != 1 {
		t.Fatalf("the cart is %+v (%v): %s", cart, err, r.Text())
	}
	if r := kit.Post(ctx, "/carts/c9/items", kItem{"pen", 0}); r.Status != 400 || !strings.Contains(r.Text(), "positive") {
		t.Fatalf("a refusal answered %d %q", r.Status, r.Text())
	}
	if r := kit.Get(ctx, "/carts/c9/note?q=hi"); r.Text() != "c9:hi" {
		t.Fatalf("the query answered %q", r.Text())
	}
	if r := kit.Do(ctx, testkit.Request{Method: http.MethodGet, Path: "/carts/c9/who", Principal: &ankka.Principal{Subject: "ada", Issuer: "keycloak"}}); r.Text() != "ada" {
		t.Fatalf("the principal answered %d %q", r.Status, r.Text())
	}
	if r := kit.Get(ctx, "/nowhere"); r.Status != 404 {
		t.Fatalf("no route answered %d", r.Status)
	}
	if r := testkit.Endpoint(kAPI).Post(ctx, "/carts/c9/items", kItem{"pen", 1}); r.Status != 500 || r.Failure == nil || !strings.Contains(r.Failure.Error(), `no component "k-cart"`) {
		t.Fatalf("a component not given answered %d %v", r.Status, r.Failure)
	}
}
