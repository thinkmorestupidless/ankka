"""A checkout as a durable multi-step process: reserve the stock, charge the customer, and check
the cart out — or compensate. The sidecar journals every transition and runs the steps; a step
that throws is retried and failed over as declared in ``settings``."""

from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import timedelta

from ankka import DONE, Done, ErrorCode, json_codec
from ankka.effects.workflow import WorkflowEffect, WorkflowReadOnlyEffect, WorkflowStepEffect
from ankka.event_sourced_entity import command, query
from ankka.workflow import Recovery, StepSettings, Workflow, WorkflowSettings, step

from examples.shopping_cart.domain import ShoppingCart


@dataclass(frozen=True)
class Checkout:
    cartId: str
    status: str = "new"
    reserved: int = 0


class PaymentDeclined(Exception):
    pass


class CheckoutWorkflow(Workflow[Checkout]):
    component_id = "checkout"
    state_codec = json_codec(Checkout, "checkout")
    settings = WorkflowSettings(
        default_step_timeout=timedelta(seconds=10),
        steps={
            "reserve": StepSettings(recovery=Recovery(failover_to="compensate")),
            "charge": StepSettings(recovery=Recovery(max_retries=1, failover_to="compensate")),
        },
    )

    def empty_state(self) -> Checkout:
        return Checkout(self.entity_id)

    @command("start")
    def start(self) -> WorkflowEffect[Checkout, Done]:
        if self.state.status != "new":
            return self.effects.error(f"checkout is already {self.state.status}", ErrorCode.CONFLICT)
        return self.effects.update_state(replace(self.state, status="reserving")).then_transition_to("reserve").then_reply(lambda _: DONE)

    @query("status")
    def status(self) -> WorkflowReadOnlyEffect[Checkout, Checkout]:
        return self.effects.reply(self.state)

    @step("reserve")
    async def reserve(self) -> WorkflowStepEffect[Checkout]:
        total = await self._cart().call("total-quantity").invoke(reply=int)
        if total == 0:
            raise ValueError("nothing to reserve")
        return self.step_effects.update_state(replace(self.state, status="reserved", reserved=total)).then_transition_to("charge")

    @step("charge")
    async def charge(self) -> WorkflowStepEffect[Checkout]:
        if self.state.reserved > 100:
            raise PaymentDeclined(f"{self.state.reserved} items is over the limit")
        # Not idempotent — a retry after the cart was checked out is refused — which is why
        # ``charge`` is allowed one retry and then fails over, and why compensation exists.
        await self._cart().call("checkout").invoke(reply=ShoppingCart)
        return self.step_effects.update_state(replace(self.state, status="charged")).then_end()

    @step("compensate")
    def compensate(self) -> WorkflowStepEffect[Checkout]:
        return self.step_effects.update_state(replace(self.state, status="compensated", reserved=0)).then_end()

    def _cart(self):  # type: ignore[no-untyped-def]
        return self.context.client.for_event_sourced_entity("shopping-cart", self.state.cartId)
