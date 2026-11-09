"""The documentation's workflow samples (docs/build/workflows.md): the transfer's command and
settings with nothing started, and the whole transfer, its compensation and a step that waits for
another workflow through the sidecar."""

from __future__ import annotations

import pytest

from ankka import Ankka, ErrorCode
from ankka.client import CommandError
from ankka.testkit import KeyValueTestKit, WorkflowTestKit
from ankka.testkit.integration import AnkkaTestKit
from tests.transfer_workflow import Balance, KycWorkflow, Onboarding, OnboardingWorkflow, Transfer, TransferState, TransferWorkflow, WalletBalance


def test_start_refuses_a_bad_amount_and_a_second_start_and_moves_to_withdraw() -> None:
    kit = WorkflowTestKit.of(TransferWorkflow, "t1")
    assert kit.call("start", Transfer("a", "b", 0)).error.code == ErrorCode.BAD_REQUEST  # type: ignore[union-attr]
    started = kit.call("start", Transfer("a", "b", 30))
    assert started.transition is not None and started.transition.step == "withdraw"
    assert kit.state == TransferState(Transfer("a", "b", 30), "started")
    assert kit.call("start", Transfer("a", "b", 30)).error.code == ErrorCode.CONFLICT  # type: ignore[union-attr]


def test_the_settings_give_the_deposit_one_retry_then_the_compensation() -> None:
    settings = TransferWorkflow.to_component().workflow.settings
    assert settings.timeout_millis == 60_000 and settings.default_step_timeout_millis == 10_000
    (deposit,) = settings.steps
    assert deposit.step == "deposit" and deposit.recovery.max_retries == 1 and deposit.recovery.failover_to == "compensate"


def test_a_wallet_refuses_an_overdraft() -> None:
    kit = KeyValueTestKit.of(WalletBalance, "a")
    assert kit.call("deposit", 100).reply == Balance(100)
    assert kit.call("withdraw", 130).error.code == ErrorCode.BAD_REQUEST  # type: ignore[union-attr]
    assert kit.call("withdraw", 30).reply == Balance(70)


def service() -> object:
    # docs:start register
    return Ankka.service().register(WalletBalance).register(TransferWorkflow).register(KycWorkflow).register(OnboardingWorkflow)
    # docs:end register


@pytest.mark.slow
async def test_a_transfer_completes_or_is_compensated_and_a_step_waits_for_another_workflow() -> None:
    async with await AnkkaTestKit.start(service()) as kit:  # type: ignore[arg-type]
        wallets = kit.client.for_key_value_entity
        await wallets("wallet-balance", "a").call("deposit").invoke(100, reply=Balance)

        done = await kit.client.for_workflow("transfer", "t1").call("start").then_await_end(30.0).invoke(Transfer("a", "b", 30), reply=TransferState)
        assert done.status == "completed"
        assert await wallets("wallet-balance", "a").call("get").invoke(reply=Balance) == Balance(70)
        assert await wallets("wallet-balance", "b").call("get").invoke(reply=Balance) == Balance(30)

        # The deposit is refused, retried once, and then the failover puts the money back.
        compensated = await kit.client.for_workflow("transfer", "t2").call("start").then_await_end(30.0).invoke(Transfer("a", "frozen", 20), reply=TransferState)
        assert compensated.status == "compensated"
        assert await wallets("wallet-balance", "a").call("get").invoke(reply=Balance) == Balance(70)

        with pytest.raises(CommandError) as refused:
            await kit.client.for_workflow("transfer", "t3").call("start").invoke(Transfer("a", "b", 0))
        assert refused.value.error.code == ErrorCode.BAD_REQUEST

        # A step that waits for another workflow's end, bounded by its own timeout.
        welcomed = await kit.client.for_workflow("onboarding", "ada").call("start").then_await_end(30.0).invoke("ada", reply=Onboarding)
        assert welcomed.status == "welcomed"
        declined = await kit.client.for_workflow("onboarding", "mallory").call("start").then_await_end(30.0).invoke("mallory", reply=Onboarding)
        assert declined.status == "declined"
