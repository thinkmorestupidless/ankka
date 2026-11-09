package samples

import (
	"context"
	"slices"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// ── the wallet: an event sourced entity the transfer workflow and the wallet endpoint call ──

// Wallet is a wallet's state: its balance, and the deposit keys it has applied.
type Wallet struct {
	Balance int      `json:"balance"`
	Applied []string `json:"applied"`
}

// Deposit is a deposit with the caller's idempotency key.
type Deposit struct {
	Key      string `json:"key"`
	Currency string `json:"currency"`
	Amount   int    `json:"amount"`
}

// Deposited is what a deposit answers: the balance, and whether this key was applied now.
type Deposited struct {
	Currency string `json:"currency"`
	Balance  int    `json:"balance"`
	Applied  bool   `json:"applied"`
}

// WalletEvent is what a wallet persists.
type WalletEvent interface{ walletEvent() }

// MoneyDeposited is money put in.
type MoneyDeposited struct {
	Key    string `json:"key"`
	Amount int    `json:"amount"`
}

// MoneyWithdrawn is money taken out.
type MoneyWithdrawn struct {
	Amount int `json:"amount"`
}

func (MoneyDeposited) walletEvent() {}
func (MoneyWithdrawn) walletEvent() {}

// WalletEvents are the cases of WalletEvent.
var WalletEvents = ankka.Cases[WalletEvent](
	ankka.Case[WalletEvent, MoneyDeposited]("MoneyDeposited"),
	ankka.Case[WalletEvent, MoneyWithdrawn]("MoneyWithdrawn"),
)

type walletEffect[R any] = ankka.Effect[Wallet, WalletEvent, R]

func applyWallet(w Wallet, e WalletEvent) Wallet {
	switch e := e.(type) {
	case MoneyDeposited:
		w.Balance += e.Amount
		if e.Key != "" {
			w.Applied = append(slices.Clone(w.Applied), e.Key)
		}
	case MoneyWithdrawn:
		w.Balance -= e.Amount
	}
	return w
}

// WalletEntity holds a player's money.
var WalletEntity = ankka.EventSourced[Wallet, WalletEvent]("wallet", WalletEvents,
	func(string) Wallet { return Wallet{Applied: []string{}} }, applyWallet,
	// A deposit by key: the same key twice is applied once, and answers the balance either way.
	ankka.Command("deposit", func(_ context.Context, w Wallet, d Deposit) (walletEffect[Deposited], error) {
		if d.Amount <= 0 {
			return walletEffect[Deposited]{}, ankka.Refuse(ankka.BadRequest, "a deposit is more than nothing")
		}
		if slices.Contains(w.Applied, d.Key) {
			// Nothing to persist: the reply alone.
			return ankka.Persist[Wallet, WalletEvent, Deposited]().
				ThenReplyValue(Deposited{Currency: d.Currency, Balance: w.Balance}), nil
		}
		return ankka.Persist[Wallet, WalletEvent, Deposited](MoneyDeposited{Key: d.Key, Amount: d.Amount}).
			ThenReply(func(w Wallet) Deposited {
				return Deposited{Currency: d.Currency, Balance: w.Balance, Applied: true}
			}), nil
	}),
	// A plain deposit of an amount, as the transfer makes it.
	ankka.Command("deposit-amount", func(_ context.Context, _ Wallet, amount int) (walletEffect[int], error) {
		return ankka.Persist[Wallet, WalletEvent, int](MoneyDeposited{Amount: amount}).
			ThenReply(func(w Wallet) int { return w.Balance }), nil
	}),
	ankka.Command("withdraw", func(_ context.Context, w Wallet, amount int) (walletEffect[int], error) {
		if w.Balance < amount {
			return walletEffect[int]{}, ankka.Refusef(ankka.Conflict, "the balance is %d", w.Balance)
		}
		return ankka.Persist[Wallet, WalletEvent, int](MoneyWithdrawn{Amount: amount}).
			ThenReply(func(w Wallet) int { return w.Balance }), nil
	}),
	ankka.Query("balance", func(_ context.Context, w Wallet, _ ankka.Unit) (ankka.ReadOnlyEffect[int], error) {
		return ankka.Reply(w.Balance), nil
	}),
	ankka.StateCodec(ankka.CodecFor[Wallet](ankka.Manifest("wallet"))),
	ankka.EventCodec(ankka.CodecFor[WalletEvent](ankka.Manifest("wallet-event"))),
)

// ── the transfer: a workflow, step by step ──

// Transfer is what a transfer moves, and between whom.
type Transfer struct {
	From   string `json:"from"`
	To     string `json:"to"`
	Amount int    `json:"amount"`
}

// TransferState is the workflow's state: the transfer, and how far it has got.
type TransferState struct {
	Transfer Transfer `json:"transfer"`
	Status   string   `json:"status"`
}

type transferStep = ankka.StepEffect[TransferState]
type transferStart = ankka.WorkflowEffect[TransferState, ankka.Done]

// docs:start transfer-companion

// TransferWorkflow moves money between two wallets and puts it back if the deposit fails. Every
// step and handler is registered under a wire name: renaming a Go function is safe, renaming the
// string is a protocol change for every instance already in flight.
var TransferWorkflow = ankka.Workflow[TransferState]("transfer",
	func(string) TransferState { return TransferState{Status: "not-started"} },
	ankka.WorkflowCommand("start", startTransfer),
	ankka.Query("status", func(_ context.Context, s TransferState, _ ankka.Unit) (ankka.ReadOnlyEffect[string], error) {
		return ankka.Reply(s.Status), nil
	}),
	// A step with an input, stored in the journal with the transition, and two without.
	ankka.Step("withdraw", withdrawStep),
	ankka.Step("deposit", depositStep),
	ankka.Step("compensate", compensateStep),
	// A deposit that fails after its retry is compensated rather than left half done.
	ankka.ForStep("deposit", ankka.StepRecovery(ankka.Recovery{MaxRetries: 1, FailoverTo: "compensate"})),
	ankka.StateCodec(ankka.CodecFor[TransferState](ankka.Manifest("transfer-state"))),
)

// docs:end transfer-companion

// docs:start transfer-start

// startTransfer sets the initial state and starts the workflow at its first step. The reply is sent
// once the new state and the transition are journalled, so a caller that sees it knows the
// workflow will run.
func startTransfer(_ context.Context, s TransferState, t Transfer) (transferStart, error) {
	if t.Amount <= 0 {
		return ankka.WorkflowEffect[TransferState, ankka.Done]{},
			ankka.Refuse(ankka.BadRequest, "transfer amount must be greater than zero")
	}
	if s.Status != "not-started" {
		return ankka.WorkflowEffect[TransferState, ankka.Done]{},
			ankka.Refuse(ankka.Conflict, "transfer already started")
	}
	return ankka.WorkflowUpdate[TransferState, ankka.Done](TransferState{Transfer: t, Status: "started"}).
		ThenTransitionTo("withdraw", t).
		ThenReplyValue(ankka.Done{}), nil
}

// docs:end transfer-start

// docs:start transfer-steps

// Steps are ordinary sequential code: they call other components and wait for the answer.

func withdrawStep(ctx context.Context, s TransferState, t Transfer) (transferStep, error) {
	if _, err := ankka.Invoke[int](ctx, "wallet", t.From, "withdraw", t.Amount); err != nil {
		return transferStep{}, err
	}
	s.Status = "withdrawn"
	return ankka.StepUpdate(s).ThenTransitionTo("deposit", t), nil
}

func depositStep(ctx context.Context, s TransferState, t Transfer) (transferStep, error) {
	if _, err := ankka.Invoke[int](ctx, "wallet", t.To, "deposit-amount", t.Amount); err != nil {
		return transferStep{}, err
	}
	s.Status = "completed"
	return ankka.StepUpdate(s).ThenEnd(), nil
}

// compensateStep puts the money back. It takes no input and reads the transfer from the state,
// which is why failover steps are input-free: what needs compensating is whatever the workflow has
// recorded, not whatever was known when the settings were written.
func compensateStep(ctx context.Context, s TransferState, _ ankka.Unit) (transferStep, error) {
	t := s.Transfer
	if _, err := ankka.Invoke[int](ctx, "wallet", t.From, "deposit-amount", t.Amount); err != nil {
		return transferStep{}, err
	}
	s.Status = "compensated"
	return ankka.StepUpdate(s).ThenEnd(), nil
}

// docs:end transfer-steps

// docs:start transfer-service

// TransferService registers the workflow and the wallets it moves money between.
func TransferService() *ankka.Service { return ankka.NewService(WalletEntity, TransferWorkflow) }

// docs:end transfer-service
