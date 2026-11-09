"""The planner: each agent's plan against the unit kit's scripted model, the workflow's commands
and settings, and the orchestration end to end through the sidecar with its model scripted."""

from __future__ import annotations

import json

import pytest

from ankka import DONE, ErrorCode
from ankka.testkit import AgentTestKit, KeyValueTestKit, ScriptedModel, WorkflowTestKit
from ankka.testkit.integration import AnkkaTestKit

from examples.planner.domain import ACTIVITY, BUDGET, COMPLETED, SELECTING, WEATHER, AgentSelection, PlanState, Preferences, Start
from examples.planner.main import service
from examples.planner.planner_workflow import PlannerWorkflow
from examples.planner.preferences import PreferencesEntity
from examples.planner.specialists import BudgetAgent, SelectorAgent, SummaryAgent, WeatherAgent

# ── The agents, each on its own ──────────────────────────────────────────────


def test_the_selector_answers_structured_and_stays_out_of_the_conversation() -> None:
    model = ScriptedModel().expect_text(json.dumps({"specialists": ["weather", "budget"], "reason": "rain and cost"}))
    answer = AgentTestKit.of(SelectorAgent, "p-1", model).call("select", "Plan a trip to Lisbon")
    assert answer.reply == AgentSelection(["weather", "budget"], "rain and cost")
    assert answer.plan.session_memory is False and answer.plan.json_reply
    refused = AgentTestKit.of(SelectorAgent, "p-2", ScriptedModel()).call("select", "  ")
    assert refused.error is not None and refused.error.code == ErrorCode.BAD_REQUEST


def test_the_weather_specialist_uses_its_tool() -> None:
    model = ScriptedModel().expect_tool_call("get_forecast", {"destination": "Lisbon"}).expect_text("Mild, with some rain.")
    answer = AgentTestKit.of(WeatherAgent, "p-3", model).call("consult", "Lisbon")
    assert answer.tool_results == ["Lisbon: mild with occasional rain"] and answer.reply == "Mild, with some rain."


def test_the_budget_specialist_and_the_summariser_share_the_session() -> None:
    assert AgentTestKit.of(BudgetAgent, "p-4", ScriptedModel().expect_text("About 90 euros.")).call("consult", "Lisbon").plan.session_memory
    summary = AgentTestKit.of(SummaryAgent, "p-4", ScriptedModel().expect_text("A mild trip.")).call("summarise", "Lisbon")
    assert summary.plan.user == "Write a brief for a trip to Lisbon." and summary.plan.session_memory


def test_preferences_are_kept_per_traveller() -> None:
    kit = KeyValueTestKit.of(PreferencesEntity, "u-1")
    assert kit.call("set", Preferences("ignored", ["trams"], [], 90)).reply == DONE
    assert kit.state == Preferences("u-1", ["trams"], [], 90)
    assert kit.call("add-like", "pastries").reply.likes == ["trams", "pastries"]
    assert kit.call("set", Preferences("u-1", maxBudget=-1)).error is not None


# ── The workflow's own decisions ─────────────────────────────────────────────


def test_a_plan_starts_once_and_declares_how_long_its_steps_may_take() -> None:
    kit = WorkflowTestKit.of(PlannerWorkflow, "p-5")
    started = kit.call("start", Start("u-1", "Lisbon"))
    assert started.transition is not None and started.transition.step == "select-specialists"
    assert kit.state.status == SELECTING and kit.state.destination == "Lisbon"
    again = kit.call("start", Start("u-1", "Porto"))
    assert again.error is not None and again.error.code == ErrorCode.CONFLICT
    assert WorkflowTestKit.of(PlannerWorkflow, "p-6").call("start", Start("u-1", " ")).error is not None
    settings = PlannerWorkflow.to_component().workflow.settings
    assert settings.default_step_timeout_millis == 90_000 and settings.default_recovery.max_retries == 1


# ── The orchestration, through the sidecar ───────────────────────────────────


# docs:start script
def plan_script() -> str:
    """Scripts the sidecar's model by rule rather than by turn, because the specialists are consulted
    at once and their requests arrive in no fixed order: each rule answers the request whose latest
    user message, or latest tool result, contains its text. The weather specialist has a tool, so it
    takes two model turns."""
    return json.dumps(
        [
            {"when": "Plan a trip to Lisbon", "text": json.dumps({"specialists": [WEATHER, BUDGET], "reason": "chosen for the test"})},
            {"when": "Plan a trip to Porto", "text": json.dumps({"specialists": [ACTIVITY], "reason": "chosen for the test"})},
            {"when": "budget per day", "text": "Budget about 90 euros a day."},
            {"when": "What should I do", "text": "Try the tram and a pastel de nata."},
            {"when": "Write a brief", "text": "A mild, affordable trip."},
            {"when_tool_result": "occasional rain", "text": "Lisbon is mild with occasional rain."},
            {"when": "weather like", "tool": "get_forecast", "arguments": {"destination": "Lisbon"}},
        ]
    )
# docs:end script


# docs:start assert
@pytest.mark.slow
async def test_the_selector_decides_which_specialists_run_and_only_those_run() -> None:
    async with await AnkkaTestKit.start(service(), env={"ANKKA_MODEL_SCRIPT": plan_script()}) as kit:
        planner = kit.client.for_workflow("planner", "p-select")
        assert await planner.call("start").invoke(Start("u-1", "Lisbon"), reply=type(DONE)) == DONE

        plan = await planner.await_end(90, reply=PlanState)
        assert plan.status == COMPLETED
        assert plan.selection is not None and plan.selection.specialists == [WEATHER, BUDGET]
        assert [c.specialist for c in plan.contributions] == [WEATHER, BUDGET]
        # The activity specialist was never consulted, so it contributed nothing.
        assert plan.contribution_from(ACTIVITY) is None
        assert plan.summary == "A mild, affordable trip."
        # docs:end assert

        # The activity specialist reads the traveller's preferences itself, as context.
        preferences = {"userId": "u-1", "likes": ["trams"], "dislikes": [], "maxBudget": 90}
        assert (await kit.http.put("/plans/preferences/u-1", json=preferences)).status_code == 204
        assert (await kit.http.post("/plans/p-porto", json={"userId": "u-1", "destination": "Porto"})).status_code == 204
        # docs:start drain
        # Let the plan finish before the test ends. A workflow left mid-flight keeps consuming
        # the scripted model, and a kit stopped under it reports the stop, not the test.
        porto = await kit.client.for_workflow("planner", "p-porto").await_end(90, reply=PlanState)
        # docs:end drain
        assert porto.contribution_from(ACTIVITY) == "Try the tram and a pastel de nata."
        assert (await kit.http.get("/plans/p-porto")).json()["status"] == COMPLETED
