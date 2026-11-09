"""The planner's agents. Each declares its instructions and tools; the sidecar runs the loop, the
session memory and the model. Every agent in a plan is addressed with the plan's own id as the
session, so they accumulate one conversation."""

from __future__ import annotations

from dataclasses import dataclass

from ankka import ErrorCode
from ankka.agent import Agent, Tool
from ankka.effects.agent import AgentEffect
from ankka.event_sourced_entity import command

from examples.planner.domain import ACTIVITY, BUDGET, SPECIALISTS, WEATHER, AgentSelection, Preferences


# docs:start selector
class SelectorAgent(Agent):
    """Decides which specialists a request actually needs: the workflow asks, rather than
    hard-coding which agents to consult."""

    component_id = "selector-agent"

    @command("select")
    def select(self, request: str) -> AgentEffect[AgentSelection]:
        if not request.strip():
            return self.effects.error("nothing to plan", ErrorCode.BAD_REQUEST)
        return (
            self.effects.system_message(
                "You route planning requests to specialists.\n"
                f"Available specialists: {', '.join(SPECIALISTS)}.\n"
                'Reply with JSON: {"specialists":["..."],"reason":"..."}.\n'
                "Choose only the specialists the request genuinely needs."
            )
            .user_message(request)
            # The selector's own deliberation is not part of the conversation the
            # specialists and summariser share.
            .memory(False)
            .then_reply_json()
        )
# docs:end selector


# docs:start weather
@dataclass(frozen=True)
class ForecastRequest:
    """The city or region to forecast."""

    destination: str


def _get_forecast(agent: Agent, arguments: ForecastRequest) -> str:
    """A stand-in forecast service, deterministic so the sample behaves the same each run."""
    place = arguments.destination.lower()
    outlook = "cold and windy" if "reykjav" in place else "hot and dry" if "cairo" in place else "mild with occasional rain"
    return f"{arguments.destination}: {outlook}"


class WeatherAgent(Agent):
    component_id = "weather-agent"
    role = WEATHER
    tools = {"get_forecast": Tool("Returns a short weather forecast for a destination.", _get_forecast, ForecastRequest)}

    @command("consult")
    def consult(self, destination: str) -> AgentEffect[str]:
        return (
            self.effects.system_message("You are a concise weather specialist. Use your tools, then answer in one sentence.")
            .user_message(f"What is the weather like in {destination}?")
            .tools("get_forecast")
            .then_reply()
        )
# docs:end weather


@dataclass(frozen=True)
class ActivityRequest:
    userId: str
    destination: str


# docs:start activity
class ActivityAgent(Agent):
    component_id = "activity-agent"
    role = ACTIVITY

    @command("consult")
    async def consult(self, request: ActivityRequest) -> AgentEffect[str]:
        # `client` is the agent's own; no constructor plumbing needed.
        assert self.client is not None
        preferences = await self.client.for_key_value_entity("preferences", request.userId).call("get").invoke(reply=Preferences)
        return (
            self.effects.system_message("You are a concise activity specialist. Suggest two activities, in one sentence.")
            .user_message(f"What should I do in {request.destination}?")
            # Preferences are context, not something the user said — so memory records the
            # question, not the whole assembled prompt.
            .with_context(f"Traveller preferences: {preferences.summary}")
            .then_reply()
        )
# docs:end activity


class BudgetAgent(Agent):
    """Answers cost questions. Deliberately tool-free, to vary the mix."""

    component_id = "budget-agent"
    role = BUDGET

    @command("consult")
    def consult(self, destination: str) -> AgentEffect[str]:
        return (
            self.effects.system_message("You are a concise budget specialist. Answer in one sentence.")
            .user_message(f"Roughly what should I budget per day in {destination}?")
            .then_reply()
        )


# docs:start summary
class SummaryAgent(Agent):
    """Combines the specialists' answers, read back from the shared session."""

    component_id = "summary-agent"
    role = "summary"

    @command("summarise")
    def summarise(self, destination: str) -> AgentEffect[str]:
        return (
            self.effects.system_message("You write short trip briefs. Combine what the specialists said into two sentences.")
            .user_message(f"Write a brief for a trip to {destination}.")
            .then_reply()
        )
# docs:end summary
