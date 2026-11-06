package ankka_test

import (
	"context"
	"strings"
	"testing"
	"time"

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

type pmJoined struct {
	Email ankka.Personal[string] `json:"email"`
}

func (pmJoined) pmEvent() {}

type pmEvent interface{ pmEvent() }

type pmMember struct {
	Email *ankka.Personal[string] `json:"email"`
}

// pmMembers seals each member's email under the data subject member/<id>.
var pmMembers = ankka.EventSourced[pmMember, pmEvent]("pm-members",
	ankka.Cases[pmEvent](ankka.Case[pmEvent, pmJoined]("Joined")),
	func(string) pmMember { return pmMember{} },
	func(_ pmMember, e pmEvent) pmMember {
		email := e.(pmJoined).Email
		return pmMember{Email: &email}
	},
	ankka.Command("join", func(ctx context.Context, _ pmMember, email string) (ankka.Effect[pmMember, pmEvent, string], error) {
		scope, _ := ankka.CommandFrom(ctx)
		subject := ankka.MustSubject("member/" + scope.EntityID)
		return ankka.Persist[pmMember, pmEvent, string](pmJoined{Email: ankka.PersonalOf(subject, email)}).ThenReply(func(pmMember) string { return "joined" }), nil
	}),
	ankka.Query("email", func(_ context.Context, s pmMember, _ ankka.Unit) (ankka.ReadOnlyEffect[string], error) {
		if s.Email == nil {
			return ankka.Reply("none"), nil
		}
		return ankka.Reply(s.Email.OrElse("erased")), nil
	}),
)

// The integration kit's keyring and personal value assertion (feature 042, T071): a personal field
// written through the real sidecar is in no table as written, an erasure through Keyring reads it back
// as erased, and an outage refuses a key the sidecar does not hold.
func TestTheIntegrationKitSealsErasesAndRefusesInAnOutage(t *testing.T) {
	kit := testkit.StartT(t, ankka.NewService(pmMembers))
	ctx := t.Context()
	cctx := kit.Context(ctx)
	if r, err := ankka.Invoke[string](cctx, "pm-members", "8c1f", "join", "ada@example.com"); err != nil || r != "joined" {
		t.Fatalf("join answered %q %v", r, err)
	}
	kit.AssertNoPersonalValue(t, "ada@example.com")
	// The assertion can fail: the envelope names its data subject in the clear.
	if err := kit.CheckNoPersonalValue(ctx, "member/8c1f"); err == nil || !strings.Contains(err.Error(), "event_journal") {
		t.Fatalf("the subject, which the journal holds, was not found: %v", err)
	}

	// An outage refuses a key the sidecar has not fetched, and an erasure, as unavailable.
	if err := kit.Keyring().Outage(ctx, 10*time.Minute); err != nil {
		t.Fatal(err)
	}
	_, err := ankka.Invoke[string](cctx, "pm-members", "9d2e", "join", "grace@example.com")
	if r, ok := ankka.AsRefusal(err); !ok || r.Code != ankka.Unavailable {
		t.Fatalf("a key not held in an outage answered %v", err)
	}
	if _, err := kit.Keyring().Erase(ctx, "member/8c1f"); err == nil || !strings.Contains(err.Error(), "503") {
		t.Fatalf("an erasure in an outage answered %v", err)
	}
	if err := kit.Keyring().Recover(ctx); err != nil {
		t.Fatal(err)
	}

	if seq, err := kit.Keyring().Erase(ctx, "member/8c1f"); err != nil || seq < 1 {
		t.Fatalf("the erasure answered %d %v", seq, err)
	}
	if err := kit.Restart(ctx); err != nil {
		t.Fatal(err)
	}
	if r, err := ankka.Invoke[string](kit.Context(ctx), "pm-members", "8c1f", "email", ankka.Unit{}); err != nil || r != "erased" {
		t.Fatalf("the erased member's email read %q %v", r, err)
	}
	kit.AssertNoPersonalValue(t, "ada@example.com", "grace@example.com")
}
