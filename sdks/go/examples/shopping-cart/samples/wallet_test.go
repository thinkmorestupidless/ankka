package samples

import (
	"context"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

func TestATransferStartsAtItsFirstStep(t *testing.T) {
	ctx := context.Background()
	if p := TransferService().Problems(); len(p) > 0 {
		t.Fatal(p)
	}
	kit := testkit.Workflow(TransferWorkflow, testkit.Calling(WalletEntity))
	defer kit.Close()
	transfer := Transfer{From: "a", To: "b", Amount: 10}

	if r := kit.Command(ctx, "start", Transfer{From: "a", To: "b"}); r.Refusal() == nil || r.Refusal().Code != ankka.BadRequest {
		t.Fatalf("a transfer of nothing answered %v", r.Refusal())
	}
	r := kit.Command(ctx, "start", transfer)
	if r.Refusal() != nil || r.Reply() != (ankka.Done{}) || r.Transition() == nil || r.Transition().Step != "withdraw" {
		t.Fatalf("start answered %v, %v, %+v", r.Reply(), r.Refusal(), r.Transition())
	}
	if kit.State().Status != "started" {
		t.Fatalf("the state is %+v", kit.State())
	}
	if r := kit.Command(ctx, "start", transfer); r.Refusal() == nil || r.Refusal().Code != ankka.Conflict {
		t.Fatalf("starting twice answered %v", r.Refusal())
	}
	// The wallet the kit hosts is empty, so the withdrawal is refused and the step fails.
	progress, err := kit.RunStep(ctx)
	if err == nil && progress.Failed == nil && progress.Pending != nil && progress.Pending.Step == "withdraw" {
		t.Fatalf("the withdrawal ran: %+v", progress)
	}
}

func TestTheWalletAppliesAKeyOnce(t *testing.T) {
	ctx := context.Background()
	kit := testkit.EventSourced(WalletEntity, testkit.EntityID("p1"))
	first := kit.Command(ctx, "deposit", Deposit{Key: "k-1", Currency: "EUR", Amount: 10})
	if first.Reply() != (Deposited{Currency: "EUR", Balance: 10, Applied: true}) || len(first.Events()) != 1 {
		t.Fatalf("the first deposit answered %+v", first.Reply())
	}
	again := kit.Command(ctx, "deposit", Deposit{Key: "k-1", Currency: "EUR", Amount: 10})
	if again.Reply() != (Deposited{Currency: "EUR", Balance: 10}) || len(again.Events()) != 0 {
		t.Fatalf("the same key answered %+v with %d events", again.Reply(), len(again.Events()))
	}
	if r := kit.Command(ctx, "withdraw", 50); r.Refusal() == nil || r.Refusal().Code != ankka.Conflict {
		t.Fatalf("overdrawing answered %v", r.Refusal())
	}
	if r := kit.Command(ctx, "withdraw", 4); r.Reply() != 6 || kit.State().Balance != 6 {
		t.Fatalf("withdrawing answered %v, balance %d", r.Reply(), kit.State().Balance)
	}
}
