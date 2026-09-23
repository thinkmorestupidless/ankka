"""Effects for agents: a description of one interaction with a model — which model, what
instructions, which tools and guardrails, what memory — as data. Building one calls no model; the
sidecar's loop interprets it, calling back for tools and guardrails."""

from __future__ import annotations

from dataclasses import dataclass, replace
from typing import Any, Generic, TypeVar

from ankka._proto.ankka.protocol.v1 import agent_pb2
from ankka.effects.common import Error, ErrorCode

R = TypeVar("R")


@dataclass(frozen=True)
class AgentEffect(Generic[R]):
    model: str | None = None
    system: str | None = None
    user: str | None = None
    context: tuple[str, ...] = ()
    session_memory: bool = True
    tool_names: tuple[str, ...] = ()
    guardrail_names: tuple[str, ...] = ()
    json_reply: bool = False
    schema_hint: str = ""
    failure: Error | None = None

    def with_model(self, name: str) -> AgentEffect[R]:
        """A model by the name the sidecar configured; absent, the sidecar's default."""
        return replace(self, model=name)

    def system_message(self, text: str) -> AgentEffect[R]:
        return replace(self, system=text)

    def user_message(self, text: str) -> AgentEffect[R]:
        return replace(self, user=text)

    def with_context(self, text: str) -> AgentEffect[R]:
        """Context the user did not type — retrieved documents, an entity's state — kept apart
        from the user message so memory records what the user actually said."""
        return replace(self, context=self.context + (text,))

    def memory(self, session: bool) -> AgentEffect[R]:
        """``False``: no memory at all, for a one-shot classification."""
        return replace(self, session_memory=session)

    def tools(self, *names: str) -> AgentEffect[R]:
        return replace(self, tool_names=self.tool_names + names)

    def guardrails(self, *names: str) -> AgentEffect[R]:
        return replace(self, guardrail_names=self.guardrail_names + names)

    def then_reply(self) -> AgentEffect[str]:
        """Replies with the model's text."""
        return replace(self, json_reply=False)  # type: ignore[return-value]

    def then_reply_json(self, schema_hint: str = "JSON") -> AgentEffect[Any]:
        """Replies with the model's JSON, decoded by the handler's reply type. The schema is not
        sent to the model — say what you want in the system message."""
        return replace(self, json_reply=True, schema_hint=schema_hint)

    def to_pb(self) -> agent_pb2.AgentPlan:
        plan = agent_pb2.AgentPlan(
            context=list(self.context),
            memory=agent_pb2.AgentPlan.SESSION if self.session_memory else agent_pb2.AgentPlan.NONE,
            tools=list(self.tool_names),
            guardrails=list(self.guardrail_names),
        )
        if self.model is not None:
            plan.model = self.model
        if self.system is not None:
            plan.system = self.system
        if self.user is not None:
            plan.user = self.user
        if self.json_reply:
            plan.response_shape.json.schema_hint = self.schema_hint
        else:
            plan.response_shape.text.SetInParent()
        if self.failure is not None:
            plan.failure.CopyFrom(self.failure.to_pb())
        return plan


class AgentEffects:
    """Inside an agent's handler."""

    def model(self, name: str) -> AgentEffect[str]:
        return AgentEffect(model=name)

    def system_message(self, text: str) -> AgentEffect[str]:
        return AgentEffect(system=text)

    def user_message(self, text: str) -> AgentEffect[str]:
        return AgentEffect(user=text)

    def error(self, message: str, code: ErrorCode = ErrorCode.BAD_REQUEST) -> AgentEffect[Any]:
        """Rejects the request without calling a model."""
        return AgentEffect(failure=Error(message, code))
