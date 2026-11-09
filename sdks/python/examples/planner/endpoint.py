"""Drives the planner over HTTP. Starting a plan returns at once: the workflow runs to completion
in the background and the client polls, which is the honest shape for work that involves several
model calls, and why the workflow's state is queryable in the first place."""

from __future__ import annotations

from ankka import Acl, Done, Endpoint, get, post, put
from ankka.client import ComponentClient

from examples.planner.domain import PlanState, Preferences, Start


class PlannerEndpoint(Endpoint):
    prefix = "/plans"
    acl = Acl.ALLOW_ALL

    def __init__(self, client: ComponentClient) -> None:
        self.client = client

    def _scoped(self) -> ComponentClient:
        return self.client.with_metadata(self.request.metadata)

    @put("/preferences/{userId}")
    async def set_preferences(self, userId: str, preferences: Preferences) -> Done:
        """Stores a traveller's preferences, which the activity specialist reads."""
        return await self._scoped().for_key_value_entity("preferences", userId).call("set").invoke(preferences, reply=Done)

    @get("/preferences/{userId}")
    async def preferences(self, userId: str) -> Preferences:
        return await self._scoped().for_key_value_entity("preferences", userId).call("get").invoke(reply=Preferences)

    @post("/{planId}")
    async def start(self, planId: str, request: Start) -> Done:
        """Starts a plan. Returns 204; poll the plan for progress."""
        return await self._scoped().for_workflow("planner", planId).call("start").invoke(request, reply=Done)

    @get("/{planId}")
    async def plan(self, planId: str) -> PlanState:
        return await self._scoped().for_workflow("planner", planId).call("plan").invoke(reply=PlanState)
