"""Run the planner: `python -m examples.planner.main`, beside a sidecar with a model configured."""

from __future__ import annotations

import asyncio
import logging

from ankka import Ankka, ServiceBuilder

from examples.planner.endpoint import PlannerEndpoint
from examples.planner.planner_workflow import PlannerWorkflow
from examples.planner.preferences import PreferencesEntity
from examples.planner.specialists import ActivityAgent, BudgetAgent, SelectorAgent, SummaryAgent, WeatherAgent


def service() -> ServiceBuilder:
    """The whole inventory: registration is explicit, so nothing is discovered by scanning."""
    return (
        Ankka.service()
        .register(PreferencesEntity)
        .register(PlannerWorkflow)
        .register(SelectorAgent)
        .register(WeatherAgent)
        .register(ActivityAgent)
        .register(BudgetAgent)
        .register(SummaryAgent)
        .register(PlannerEndpoint)
    )


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    asyncio.run(service().listen())
