"""The documentation's secret store samples (docs/build/secrets.md): a workflow step that reads a
credential when it needs it, and a consumer that keeps one, each against the store in memory."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, replace

from ankka import DONE, Done, InMemorySecrets, json_codec
from ankka.consumer import Consumer
from ankka.start_from import StartFrom
from ankka.effects.consumer import ConsumerEffect
from ankka.effects.workflow import WorkflowEffect, WorkflowReadOnlyEffect, WorkflowStepEffect
from ankka.event_sourced_entity import command, query
from ankka.testkit import WorkflowTestKit
from ankka.workflow import Workflow, step


@dataclass(frozen=True)
class Charge:
    provider: str
    amount: int


@dataclass(frozen=True)
class ChargeState:
    provider: str
    credentialLength: int
    status: str


# docs:start charge-workflow
class ChargeWorkflow(Workflow[ChargeState]):
    """Charges a customer through a payment provider. The step reads the provider's credential from
    the secret store when it needs it, and records only that it charged."""

    component_id = "charge"
    state_codec = json_codec(ChargeState, "charge-state")

    def empty_state(self) -> ChargeState:
        return ChargeState("", 0, "not-started")

    @command("start")
    def start(self, charge: Charge) -> WorkflowEffect[ChargeState, Done]:
        return self.effects.update_state(ChargeState(charge.provider, 0, "started")).then_transition_to("charge", charge).then_reply(lambda _: DONE)

    @step("charge")
    async def charge(self, charge: Charge) -> WorkflowStepEffect[ChargeState]:
        credential = await self.secrets.get(f"provider/{charge.provider}")
        if credential is None:
            raise RuntimeError(f"no credential for {charge.provider}")
        # Calling the provider with `credential` goes here. The state records that it was charged,
        # and never the credential.
        return self.step_effects.update_state(replace(self.state, credentialLength=len(credential), status="charged")).then_end()
# docs:end charge-workflow

    @query("status")
    def status(self) -> WorkflowReadOnlyEffect[ChargeState, ChargeState]:
        return self.effects.reply(self.state)


@dataclass(frozen=True)
class StockEvent:
    sku: str
    delta: int
    warehouse: str


class WarehouseCredentialKeeper(Consumer[StockEvent, None]):
    """Keeps a token for each warehouse it hears of, once."""

    component_id = "warehouse-credential-keeper"
    topic = "stock-events"
    start_from = StartFrom.EARLIEST
    message_codec = json_codec(StockEvent, "stock-event")

    async def on_message(self, event: StockEvent) -> ConsumerEffect:  # type: ignore[override]
        name = f"warehouse/{event.warehouse}"
        if await self.secrets.get(name) is None:
            await self.secrets.put(name, f"token-{event.warehouse}")
        return self.effects.ignore()


def test_a_step_reads_the_credential_and_records_only_that_it_charged() -> None:
    kit = WorkflowTestKit.of(ChargeWorkflow, "ch-1")
    store = InMemorySecrets()
    asyncio.run(store.put("provider/stripe", "sk-test-1234"))
    kit.workflow.secrets = store
    assert kit.call("start", Charge("stripe", 100)).transition.step == "charge"  # type: ignore[union-attr]
    ended = kit.run_step()
    assert ended.new_state == ChargeState("stripe", 12, "charged")


def test_a_consumers_secret_store_is_in_memory_with_the_runtimes_rules() -> None:
    # docs:start unit-test
    keeper = WarehouseCredentialKeeper()
    keeper.secrets = InMemorySecrets()
    asyncio.run(keeper.on_message(StockEvent("sku-1", 1, "w9")))
    assert asyncio.run(keeper.secrets.get("warehouse/w9")) == "token-w9"
    # docs:end unit-test
    asyncio.run(keeper.on_message(StockEvent("sku-2", 1, "w9")))
    assert asyncio.run(keeper.secrets.get("warehouse/w9")) == "token-w9"
