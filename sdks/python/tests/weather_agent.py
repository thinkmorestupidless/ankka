"""The weather agent of the documentation's agent samples: two tools, a structured reply and a
guarded interaction, as the Scala ``WeatherAgent`` has them."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date

from ankka.agent import Agent, Guardrail, Tool
from ankka.effects.agent import AgentEffect
from ankka.event_sourced_entity import command

SYSTEM_MESSAGE = "You are a weather specialist. Use the tools for forecasts and dates."

tool_calls: list[str] = []


# docs:start forecast
@dataclass(frozen=True)
class Forecast:
    location: str
    summary: str
    degreesCelsius: int
# docs:end forecast


# docs:start tools
@dataclass(frozen=True)
class WeatherLookup:
    """The model's arguments: a location or city name, and a forecast date in yyyy-MM-dd format."""

    location: str
    date: str | None = None


def _get_weather(agent: Agent, arguments: WeatherLookup) -> str:
    tool_calls.append(f"get_weather({arguments.location},{arguments.date or '-'})")
    if arguments.location == "Nowhere":
        raise RuntimeError("unknown location")
    return f"{arguments.location}: 18C, sunny"


def _current_date(agent: Agent) -> str:
    return date.today().isoformat()


class WeatherAgent(Agent):
    component_id = "weather-agent"
    tools = {
        "get_weather": Tool("Returns the weather forecast for a given city.", _get_weather, WeatherLookup),
        "current_date": Tool("Returns today's date in yyyy-MM-dd format.", _current_date),
    }
    # docs:end tools
    # docs:start no-keys
    guardrails = {
        "max-input-length": Guardrail(lambda stage, text: "the question is too long" if stage == "input" and len(text) > 40 else None),
        "no-keys": Guardrail(lambda stage, text: "the reply contains what looks like a key" if stage == "output" and "sk-" in text else None),
    }
    # docs:end no-keys

    @command("ask")
    def ask(self, question: str) -> AgentEffect[str]:
        return self.effects.system_message(SYSTEM_MESSAGE).user_message(question).tools("get_weather", "current_date").then_reply()

    # docs:start structured
    @command("ask-structured")
    def ask_structured(self, question: str) -> AgentEffect[Forecast]:
        """Same interaction, but the reply is parsed into a ``Forecast``."""
        return self.effects.system_message(SYSTEM_MESSAGE + " Reply with JSON.").user_message(question).then_reply_json()
    # docs:end structured

    # docs:start guarded
    @command("guarded")
    def guarded(self, question: str) -> AgentEffect[str]:
        """Guarded, to exercise rejection before a model is called."""
        return self.effects.system_message(SYSTEM_MESSAGE).user_message(question).guardrails("max-input-length").then_reply()
    # docs:end guarded
