"""Orchestrates the specialists into one plan: dynamic (the first step asks which specialists are
needed), parallel (the second consults them all at once) and durable (each step's result is
journalled before the next begins). Every agent is addressed with the plan's own id as the
session, so they accumulate one shared conversation the summariser reads back."""

from __future__ import annotations

import asyncio
from dataclasses import replace
from datetime import timedelta

from ankka import DONE, Done, ErrorCode, json_codec
from ankka.client import Calls
from ankka.effects.workflow import WorkflowEffect, WorkflowReadOnlyEffect, WorkflowStepEffect
from ankka.event_sourced_entity import command, query
from ankka.workflow import Recovery, Workflow, WorkflowSettings, step

from examples.planner.domain import (
    ACTIVITY,
    BUDGET,
    COMPLETED,
    CONSULTING,
    NOT_STARTED,
    SELECTING,
    SPECIALISTS,
    WEATHER,
    AgentSelection,
    Contribution,
    PlanState,
    Start,
)
from examples.planner.specialists import ActivityRequest


class PlannerWorkflow(Workflow[PlanState]):
    component_id = "planner"
    state_codec = json_codec(PlanState, "plan-state")
    # docs:start settings
    settings = WorkflowSettings(
        timeout=timedelta(minutes=5),
        # Model calls are slow, and a tool loop can legitimately take a while.
        default_step_timeout=timedelta(seconds=90),
        default_recovery=Recovery(max_retries=1),
    )
    # docs:end settings

    def empty_state(self) -> PlanState:
        return PlanState()

    @command("start")
    def start(self, request: Start) -> WorkflowEffect[PlanState, Done]:
        if not request.destination.strip():
            return self.effects.error("a destination is required")
        if self.state.status != NOT_STARTED:
            return self.effects.error("this plan has already started", ErrorCode.CONFLICT)
        started = PlanState(request.userId, request.destination, SELECTING)
        return self.effects.update_state(started).then_transition_to("select-specialists", request.destination).then_reply(lambda _: DONE)

    @query("plan")
    def plan(self) -> WorkflowReadOnlyEffect[PlanState, PlanState]:
        return self.effects.reply(self.state)

    # docs:start select
    @step("select-specialists")
    async def select_specialists(self, destination: str) -> WorkflowStepEffect[PlanState]:
        """Asks the selector which specialists this request needs."""
        selection = await self._agent("selector-agent").call("select").invoke(f"Plan a trip to {destination}", reply=AgentSelection)
        # Ignore anything the model invented that is not a real specialist.
        known = [s for s in selection.specialists if s in SPECIALISTS]
        # A selector that names nothing usable should not stall the plan.
        chosen = known or [ACTIVITY]
        consulting = replace(self.state, status=CONSULTING, selection=replace(selection, specialists=chosen))
        return self.step_effects.update_state(consulting).then_transition_to("consult-specialists")
    # docs:end select

    # docs:start parallel
    @step("consult-specialists")
    async def consult_specialists(self) -> WorkflowStepEffect[PlanState]:
        """Consults every chosen specialist at once. `asyncio.gather` rather than one await after
        another: the specialists are independent, so waiting for each in turn would make the step
        as slow as the sum of the model calls instead of the slowest one."""
        chosen = self.state.selection.specialists if self.state.selection is not None else []
        answers = await asyncio.gather(*(self._consult(specialist) for specialist in chosen))
        contributions = [Contribution(specialist, answer) for specialist, answer in zip(chosen, answers, strict=True)]
        return self.step_effects.update_state(replace(self.state, contributions=contributions)).then_transition_to("summarise")

    async def _consult(self, specialist: str) -> str:
        state = self.state
        if specialist == WEATHER:
            return await self._agent("weather-agent").call("consult").invoke(state.destination, reply=str)
        if specialist == ACTIVITY:
            request = ActivityRequest(state.userId, state.destination)
            return await self._agent("activity-agent").call("consult").invoke(request, reply=str)
        if specialist == BUDGET:
            return await self._agent("budget-agent").call("consult").invoke(state.destination, reply=str)
        return f"no specialist named '{specialist}'"
    # docs:end parallel

    # docs:start summarise
    @step("summarise")
    async def summarise(self) -> WorkflowStepEffect[PlanState]:
        """Combines the contributions, reading them back from the shared session."""
        brief = await self._agent("summary-agent").call("summarise").invoke(self.state.destination, reply=str)
        return self.step_effects.update_state(replace(self.state, status=COMPLETED, summary=brief)).then_end()
    # docs:end summarise

    def _agent(self, component_id: str) -> Calls:
        # The plan's own id is the session every agent in it shares.
        return self.context.client.for_agent(component_id, self.entity_id)
