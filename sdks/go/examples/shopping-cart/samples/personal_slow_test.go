package samples

import (
	"context"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/conformance"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

// docs:start erase-test

func TestAnErasedSubjectReadsAsErasedAfterARestart(t *testing.T) {
	service := ankka.NewService(conformance.PersonalContact, conformance.API).
		ErasureHandler(func(context.Context, ankka.DataSubject) (ankka.ErasureReport, error) {
			return ankka.ErasureReport{}, nil
		})
	kit := testkit.StartT(t, service)
	ctx := t.Context()
	h := kit.HTTP()

	contact := conformance.RecordContact{Subject: "player/8c1f", Email: "ada@example.com"}
	r, err := h.Post(ctx, "/conformance/personal/card-1", contact)
	if err != nil || r.Status != 200 {
		t.Fatalf("recording answered %v, %v", r, err)
	}
	// Destroys the key now and sends the notice; the kit's keyring outlives a restart as an
	// installation's does.
	if _, err := kit.Keyring().Erase(ctx, "player/8c1f"); err != nil {
		t.Fatal(err)
	}
	if err := kit.Restart(ctx); err != nil {
		t.Fatal(err)
	}
	// Read from the database, not from memory.
	record, err := testkit.GetJSON[conformance.PersonalRecord](ctx, kit.HTTP(), "/conformance/personal/card-1")
	if err != nil || record.Outcome != "erased" {
		t.Fatalf("the contact reads %+v, %v\n%s", record, err, kit.SidecarLogs(ctx))
	}
	// Fails naming the table and column holding the value, as written, in base64 or in hex.
	kit.AssertNoPersonalValue(t, "ada@example.com")
}

// docs:end erase-test
