"""Move money between two wallets, with compensation if the deposit fails.

The interesting property is not the happy path: it is that a crash after the withdrawal but before
the deposit does not lose the money. The withdrawal is journalled as a completed step, so recovery
resumes at the deposit rather than starting over or giving up. Beside it, an onboarding that waits
for another workflow's end from a step."""

from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import timedelta

from ankka import DONE, Done, ErrorCode, json_codec
from ankka.effects.key_value import KeyValueEffect, KeyValueReadOnlyEffect
from ankka.key_value_entity import KeyValueEntity
from ankka.client import Calls
from ankka.effects.workflow import WorkflowEffect, WorkflowReadOnlyEffect, WorkflowStepEffect
from ankka.event_sourced_entity import command, query
from ankka.workflow import Recovery, StepSettings, Workflow, WorkflowSettings, step


@dataclass(frozen=True)
class Balance:
    amount: int = 0


class WalletBalance(KeyValueEntity[Balance]):
    """A wallet's balance. A withdrawal of more than it holds is refused; a deposit to a frozen
    wallet is refused too, so a transfer to one must be compensated."""

    component_id = "wallet-balance"
    state_codec = json_codec(Balance, "balance")

    def empty_state(self) -> Balance:
        return Balance()

    @command("withdraw")
    def withdraw(self, amount: int) -> KeyValueEffect[Balance, Balance]:
        if amount > self.state.amount:
            return self.effects.error(f"insufficient funds: {self.state.amount} < {amount}")
        return self.effects.update_state(Balance(self.state.amount - amount)).then_reply(lambda b: b)

    @command("deposit")
    def deposit(self, amount: int) -> KeyValueEffect[Balance, Balance]:
        if self.entity_id == "frozen":
            return self.effects.error("wallet is frozen", ErrorCode.CONFLICT)
        return self.effects.update_state(Balance(self.state.amount + amount)).then_reply(lambda b: b)

    @query("get")
    def get(self) -> KeyValueReadOnlyEffect[Balance, Balance]:
        return self.effects.reply(self.state)


@dataclass(frozen=True)
class Transfer:
    source: str
    target: str
    amount: int


@dataclass(frozen=True)
class TransferState:
    transfer: Transfer
    status: str


class TransferWorkflow(Workflow[TransferState]):
    component_id = "transfer"
    state_codec = json_codec(TransferState, "transfer-state")

    # docs:start settings
    settings = WorkflowSettings(
        timeout=timedelta(seconds=60),
        default_step_timeout=timedelta(seconds=10),
        # One retry, then compensate. The deposit is the step that can fail for reasons outside
        # this workflow's control, so it is the one worth a second attempt.
        steps={"deposit": StepSettings(recovery=Recovery(max_retries=1, failover_to="compensate"))},
    )
    # docs:end settings

    def empty_state(self) -> TransferState:
        return TransferState(Transfer("", "", 0), "not-started")

    # docs:start start
    @command("start")
    def start(self, transfer: Transfer) -> WorkflowEffect[TransferState, Done]:
        if transfer.amount <= 0:
            return self.effects.error("transfer amount must be greater than zero")
        if self.state.status != "not-started":
            return self.effects.error("transfer already started", ErrorCode.CONFLICT)
        return self.effects.update_state(TransferState(transfer, "started")).then_transition_to("withdraw", transfer).then_reply(lambda _: DONE)
    # docs:end start

    @query("status")
    def status(self) -> WorkflowReadOnlyEffect[TransferState, TransferState]:
        return self.effects.reply(self.state)

    # docs:start steps
    @step("withdraw")
    async def withdraw(self, transfer: Transfer) -> WorkflowStepEffect[TransferState]:
        await self._wallet(transfer.source).call("withdraw").invoke(transfer.amount, reply=Balance)
        return self.step_effects.update_state(replace(self.state, status="withdrawn")).then_transition_to("deposit", transfer)

    @step("deposit")
    async def deposit(self, transfer: Transfer) -> WorkflowStepEffect[TransferState]:
        await self._wallet(transfer.target).call("deposit").invoke(transfer.amount, reply=Balance)
        return self.step_effects.update_state(replace(self.state, status="completed")).then_end()

    @step("compensate")
    async def compensate(self) -> WorkflowStepEffect[TransferState]:
        """Puts the money back.

        Takes no input and reads the transfer from the state, which is why failover steps are
        input-free: what needs compensating is whatever the workflow has recorded, not whatever
        was known when the settings were written."""
        transfer = self.state.transfer
        await self._wallet(transfer.source).call("deposit").invoke(transfer.amount, reply=Balance)
        return self.step_effects.update_state(replace(self.state, status="compensated")).then_end()
    # docs:end steps

    def _wallet(self, wallet: str) -> Calls:
        return self.context.client.for_key_value_entity("wallet-balance", wallet)


# ── An onboarding that waits for a KYC check, another workflow, from a step ──


@dataclass(frozen=True)
class Kyc:
    applicant: str
    decision: str | None = None


class KycWorkflow(Workflow[Kyc]):
    """Decides on an applicant in one step: everyone but `mallory` is approved."""

    component_id = "kyc"
    state_codec = json_codec(Kyc, "kyc")

    def empty_state(self) -> Kyc:
        return Kyc("")

    @command("start")
    def start(self, applicant: str) -> WorkflowEffect[Kyc, Done]:
        return self.effects.update_state(Kyc(applicant)).then_transition_to("check").then_reply(lambda _: DONE)

    @step("check")
    def check(self) -> WorkflowStepEffect[Kyc]:
        decision = "declined" if self.state.applicant == "mallory" else "approved"
        return self.step_effects.update_state(replace(self.state, decision=decision)).then_end()


@dataclass(frozen=True)
class Onboarding:
    applicant: str
    status: str = "new"


VERIFY_TIMEOUT = 30.0


class OnboardingWorkflow(Workflow[Onboarding]):
    component_id = "onboarding"
    state_codec = json_codec(Onboarding, "onboarding")

    def empty_state(self) -> Onboarding:
        return Onboarding("")

    @command("start")
    def start(self, applicant: str) -> WorkflowEffect[Onboarding, Done]:
        return self.effects.update_state(Onboarding(applicant, "verifying")).then_transition_to("verify").then_reply(lambda _: DONE)

    @query("status")
    def status(self) -> WorkflowReadOnlyEffect[Onboarding, Onboarding]:
        return self.effects.reply(self.state)

    # docs:start step-awaits
    @step("verify")
    async def verify(self) -> WorkflowStepEffect[Onboarding]:
        kyc = await (
            self.context.client.for_workflow("kyc", f"kyc-{self.entity_id}")
            .call("start")
            .then_await_end(VERIFY_TIMEOUT)
            .invoke(self.state.applicant, reply=Kyc)
        )
        if kyc.decision == "approved":
            return self.step_effects.transition_to("welcome")
        return self.step_effects.transition_to("decline")
    # docs:end step-awaits

    @step("welcome")
    def welcome(self) -> WorkflowStepEffect[Onboarding]:
        return self.step_effects.update_state(replace(self.state, status="welcomed")).then_end()

    @step("decline")
    def decline(self) -> WorkflowStepEffect[Onboarding]:
        return self.step_effects.update_state(replace(self.state, status="declined")).then_end()
