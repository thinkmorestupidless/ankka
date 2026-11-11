package ankka_test

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"time"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// The documentation's samples of personal data: a personal field, a session and an instance about a
// data subject, an erasure handler, and an erasure asked from code. The calls run against a stand-in
// sidecar that keeps what it was sent.

func TestAPersonalFieldIsItsDataSubjectsAndPrintsWithoutItsValue(t *testing.T) {
	id, email := "8c1f", "ada@example.com"
	// docs:start personal-field
	type PlayerRegistered struct {
		PlayerID string                 `json:"playerId"`
		Email    ankka.Personal[string] `json:"email"`
		Currency string                 `json:"currency"`
	}

	event := PlayerRegistered{PlayerID: id, Email: ankka.PersonalOf(ankka.MustSubject("player/"+id), email), Currency: "EUR"}
	// docs:end personal-field

	if event.Email.Subject().String() != "player/8c1f" {
		t.Fatalf("the field is of %s", event.Email.Subject())
	}
	if v, ok := event.Email.Value(); !ok || v != "ada@example.com" {
		t.Fatalf("the field holds %q, %v", v, ok)
	}
	if printed := fmt.Sprintf("%v %+v", event.Email, event); strings.Contains(printed, "ada@") || !strings.Contains(printed, "Personal(player/8c1f)") {
		t.Fatalf("printed %s", printed)
	}
	if _, err := ankka.Subject("player 8c1f"); err == nil {
		t.Fatal("a data subject outside the rule was made")
	}
}

func withErasureHandler(svc *ankka.Service) *ankka.Service {
	// docs:start erasure-handler
	svc.ErasureHandler(func(ctx context.Context, subject ankka.DataSubject) (ankka.ErasureReport, error) {
		bucket, err := ankka.StorageFrom(ctx).Bucket("kyc")
		if err != nil {
			return ankka.ErasureReport{}, err
		}
		removed, err := bucket.Erase(ctx, subject)
		return ankka.ErasureReport{ObjectsErased: removed.Count, Finality: removed.Finality}, err
	})
	// docs:end erasure-handler
	return svc
}

func TestTheErasureHandlerErasesTheSubjectsObjectsAndKeepsTheRest(t *testing.T) {
	store := ankka.NewMemoryStorage()
	if err := store.Declare("kyc", ankka.ExposurePrivate); err != nil {
		t.Fatal(err)
	}
	ctx := ankka.WithStorage(context.Background(), store)
	kyc, err := store.Bucket("kyc")
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"subjects/player%2F8c1f/passport.png", "subjects/player%2F8c1f/selfie.png", "subjects/player%2F9d2e/passport.png"} {
		if err := kyc.Put(ctx, name, strings.NewReader("scan"), 4, "image/png"); err != nil {
			t.Fatal(err)
		}
	}
	svc := withErasureHandler(ankka.NewService())
	if p := svc.Problems(); len(p) > 0 {
		t.Fatal(p)
	}
	erased, finality, err := ankka.RunErasureHandler(ctx, svc, "player/8c1f")
	if err != nil || erased != 2 || finality != "immediate" {
		t.Fatalf("erased %d (%s), %v", erased, finality, err)
	}
	// Run again, it does nothing it did not do the first time.
	if again, _, err := ankka.RunErasureHandler(ctx, svc, "player/8c1f"); err != nil || again != 0 {
		t.Fatalf("a re-run erased %d, %v", again, err)
	}
	if kept, err := kyc.Get(ctx, "subjects/player%2F9d2e/passport.png"); err != nil || kept == nil {
		t.Fatalf("another subject's object was erased: %v", err)
	}
}

func aboutAPlayer(ctx context.Context) (string, ankka.AgentInstance, error) {
	// docs:start agent-subject
	// docs:start session-subject
	ctx = ankka.WithDataSubject(ctx, ankka.MustSubject("player/8c1f"))
	// docs:end session-subject
	// every call to an agent made with ctx names the data subject
	answer, err := ankka.Invoke[string](ctx, "helper", "support-91", "ask", "my card was declined")

	instance := ankka.Instance("resolver", "case-91").WithSubject(ankka.MustSubject("player/8c1f"))
	// docs:end agent-subject
	return answer, instance, err
}

func TestASessionAndAnInstanceAboutAPlayerNameTheirDataSubject(t *testing.T) {
	ctx, sidecar := ankka.StartDocsSidecar(t)
	sidecar.Answer("Your card was declined by its issuer.")
	answer, instance, err := aboutAPlayer(ctx)
	if err != nil || answer != "Your card was declined by its issuer." {
		t.Fatalf("answered %q, %v", answer, err)
	}
	sidecar.Answer(`{"accepted":["t-1"]}`)
	if assigned, err := instance.Assign(ctx, "t-1"); err != nil || len(assigned.Accepted) != 1 {
		t.Fatalf("assigned %+v, %v", assigned, err)
	}
	if got := fmt.Sprint(sidecar.Subjects()); got != "[player/8c1f player/8c1f]" {
		t.Fatalf("the session's call and the instance's assignment named %s", got)
	}
}

func closeAccount(ctx context.Context) (ankka.ErasureAsked, error) {
	// docs:start ask-erasure
	asked, err := ankka.Erasures(ctx).Ask(ankka.AskErasure{
		Subject:     ankka.MustSubject("player/8c1f"),
		NotBefore:   ankka.NewLocalDate(2031, time.October, 8),
		Reason:      "aml-retention",
		Correlation: "closure-4411",
	})
	// asked.ID, asked.State: "held", "applying" or "applied"
	// docs:end ask-erasure
	return asked, err
}

func TestAnErasureIsAskedFromCodeWithItsDateReasonAndCorrelation(t *testing.T) {
	ctx, sidecar := ankka.StartDocsSidecar(t)
	asked, err := closeAccount(ctx)
	if err != nil || asked.ID != "er-1" || asked.State != "held" {
		t.Fatalf("asked %+v, %v", asked, err)
	}
	want := [4]string{"player/8c1f", "2031-10-08", "aml-retention", "closure-4411"}
	if got := sidecar.ErasuresAsked(); len(got) != 1 || got[0] != want {
		t.Fatalf("the sidecar was asked %v", got)
	}
}
