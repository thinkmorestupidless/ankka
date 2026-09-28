from __future__ import annotations

from dataclasses import dataclass

from collections.abc import AsyncIterator

from ankka import Acl, Endpoint, get, post, sse
from ankka.client import ComponentClient

from examples.shopping_cart.answerer import ANSWER, Answer, CartAnswerer


@dataclass
class Asked:
    taskId: str
    instanceId: str


@dataclass
class Question:
    taskId: str
    status: str
    answer: Answer | None
    reason: str | None
    iterations: int


class QuestionsEndpoint(Endpoint):
    """Questions about carts, answered by an autonomous agent while the caller gets on with things."""

    prefix = "/questions"
    acl = Acl.ALLOW_ALL

    def __init__(self, client: ComponentClient) -> None:
        self.client = client

    # docs:start run-task
    @post("/ask")
    async def ask(self, question: str) -> Asked:
        """Starts the work and answers at once, with where to look for the answer."""
        client = self.client.with_metadata(self.request.metadata)
        task_id = await client.for_autonomous_agent(CartAnswerer).run_single_task(ANSWER, question)
        task = await client.for_task(task_id).get()
        return Asked(task_id, task.assignee[1] if task.assignee else "")
    # docs:end run-task

    # docs:start read-task
    @get("/{taskId}")
    async def read(self, taskId: str) -> Question:
        task = await self.client.with_metadata(self.request.metadata).for_task(taskId).get(ANSWER)
        return Question(taskId, task.status, task.result, task.reason, task.iterations)
    # docs:end read-task

    # docs:start notifications
    @sse("/answerer/{instanceId}/notifications")
    async def notifications(self, instanceId: str) -> AsyncIterator[str]:
        """What an answerer instance does, as it happens. Each event's data is the notification's
        JSON, as a JSON string: a reader parses the field, then the notification."""
        async for n in self.client.with_metadata(self.request.metadata).for_autonomous_agent(CartAnswerer, instanceId).notifications():
            yield n.to_json()
    # docs:end notifications
