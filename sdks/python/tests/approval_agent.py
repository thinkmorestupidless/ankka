"""The agent of the documentation's approval samples, whose tools wait for a person, and the
endpoint that streams one of its turns, as the Scala ``ApprovalAgent`` and ``ChatEndpoint`` do."""

from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass
from datetime import timedelta

from ankka import Acl, Approval, AwaitingApproval, Endpoint, SseEvent, sse
from ankka.agent import Agent, Tool, stream
from ankka.client import ComponentClient
from ankka.effects.agent import AgentEffect
from ankka.event_sourced_entity import command

runs: list[str] = []


# docs:start requires-approval
@dataclass(frozen=True)
class RefundRequest:
    """The order to refund, and the amount."""

    order: str
    amount: int


def _issue_refund(agent: Agent, arguments: RefundRequest) -> str:
    runs.append(f"issue_refund({arguments.order},{arguments.amount})")
    return f"refunded {arguments.amount} on {arguments.order}"


issue_refund = Tool(
    "Refunds an order. A supervisor approves every refund before it is made.", _issue_refund, RefundRequest, approval=True
)
# docs:end requires-approval


# docs:start requires-approval-within
@dataclass(frozen=True)
class CloseRequest:
    """The customer whose account to close."""

    customer: str


def _close_account(agent: Agent, arguments: CloseRequest) -> str:
    runs.append(f"close_account({arguments.customer})")
    return f"closed {arguments.customer}"


close_account = Tool("Closes a customer's account.", _close_account, CloseRequest, approval=Approval(within=timedelta(seconds=2)))
# docs:end requires-approval-within


class ApprovalAgent(Agent):
    component_id = "approval-agent"
    tools = {"issue_refund": issue_refund, "close_account": close_account}

    @command("ask")
    def ask(self, question: str) -> AgentEffect[str]:
        return self.effects.system_message("You handle support requests.").user_message(question).tools("issue_refund", "close_account").then_reply()

    @stream("chat")
    def chat(self, question: str) -> AgentEffect[str]:
        return self.effects.system_message("You handle support requests.").user_message(question).tools("issue_refund", "close_account").then_reply()


class ChatEndpoint(Endpoint):
    prefix = "/support"
    acl = Acl.ALLOW_ALL

    def __init__(self, client: ComponentClient) -> None:
        self.client = client

    # docs:start sse-approval
    @sse("/{session}")
    async def support(self, session: str) -> AsyncIterator[str | SseEvent]:
        async for part in self.client.for_agent("approval-agent", session).call("chat").stream_parts("refund order o-7"):
            if isinstance(part, AwaitingApproval):
                yield SseEvent("approval", part.requests)
            else:
                yield part
    # docs:end sse-approval
