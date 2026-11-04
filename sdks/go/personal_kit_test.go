package ankka_test

import (
	"context"
	"strings"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

type pkEvent interface{ pkEvent() }
type pkRegistered struct {
	Email ankka.Personal[string] `json:"email"`
}

func (pkRegistered) pkEvent() {}

// The state's field is a pointer: a zero Personal has no subject, and cannot be written.
type pkPlayer struct {
	Email *ankka.Personal[string] `json:"email"`
}

var pkPlayers = ankka.EventSourced[pkPlayer, pkEvent]("pk-players",
	ankka.Cases[pkEvent](ankka.Case[pkEvent, pkRegistered]("Registered")),
	func(string) pkPlayer { return pkPlayer{} },
	func(_ pkPlayer, e pkEvent) pkPlayer {
		email := e.(pkRegistered).Email
		return pkPlayer{Email: &email}
	},
	ankka.Command("register", func(_ context.Context, _ pkPlayer, email string) (ankka.Effect[pkPlayer, pkEvent, ankka.Done], error) {
		return ankka.Persist[pkPlayer, pkEvent, ankka.Done](pkRegistered{Email: ankka.PersonalOf(ankka.MustSubject("player/kit-1"), email)}).ThenReply(func(pkPlayer) ankka.Done { return ankka.Done{} }), nil
	}),
	ankka.Query("get", func(_ context.Context, s pkPlayer, _ ankka.Unit) (ankka.ReadOnlyEffect[pkPlayer], error) {
		return ankka.Reply(s), nil
	}),
)

var pkAPI = ankka.NewEndpoint("/players", ankka.AllowAll(),
	ankka.Post("/{id}", func(ctx context.Context, req *ankka.Request, email string) (ankka.Done, error) {
		return ankka.Invoke[ankka.Done](ctx, "pk-players", req.Param("id"), "register", email)
	}),
	ankka.Get("/{id}", func(ctx context.Context, req *ankka.Request) (pkPlayer, error) {
		return ankka.Invoke[pkPlayer](ctx, "pk-players", req.Param("id"), "get", ankka.Unit{})
	}),
)

// The unit kits seal a personal field with the stand-in's keys, read it back, and at an endpoint
// answer its edge form; once the subject is erased the field reads as erased and a write is refused.
func TestTheUnitKitsSealAndOpenPersonalFields(t *testing.T) {
	ctx := context.Background()
	k := testkit.EventSourced(pkPlayers, testkit.EntityID("p1"))
	r := k.Command(ctx, "register", "ada@example.com")
	if r.Failure() != nil || r.Refusal() != nil {
		t.Fatalf("register failed: %v %v", r.Failure(), r.Refusal())
	}
	if v, ok := r.Events()[0].(pkRegistered).Email.Value(); !ok || v != "ada@example.com" {
		t.Fatalf("the event read back %v %v", v, ok)
	}
	if v, ok := r.State().Email.Value(); !ok || v != "ada@example.com" {
		t.Fatalf("the state read back %v %v", v, ok)
	}

	e := testkit.Endpoint(pkAPI, pkPlayers)
	defer e.Close()
	if r := e.Post(ctx, "/players/p1", "ada@example.com"); r.Status >= 300 {
		t.Fatalf("register answered %d %s %v", r.Status, r.Text(), r.Failure)
	}
	if r := e.Get(ctx, "/players/p1"); r.Status != 200 || !strings.Contains(r.Text(), `{"email":{"subject":"player/kit-1","value":"ada@example.com"}}`) {
		t.Fatalf("the endpoint answered %d %s %v", r.Status, r.Text(), r.Failure)
	}

	testkit.EraseSubject(ankka.MustSubject("player/kit-1"))
	refused := k.Command(ctx, "register", "again@example.com")
	if refused.Failure() == nil || !strings.Contains(refused.Failure().Error(), "is erased; nothing was written") {
		t.Fatalf("a write for an erased subject answered %v %v", refused.Failure(), refused.Refusal())
	}
}
