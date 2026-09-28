"""An autonomous agent that answers questions about carts. It is handed a task and works it on its
own until it completes the task with an ``Answer`` that cites its sources. The loop, the model and
the task's record are the sidecar's; this process runs the tools and checks the rule."""

from __future__ import annotations

from dataclasses import dataclass

from ankka.agent import Tool
from ankka.autonomous import Accepted, AutonomousAgent, Rejected, TaskAcceptance, TaskRule, TaskType

from examples.shopping_cart.domain import ShoppingCart


# docs:start task-type
@dataclass
class Answer:
    answer: str
    sources: list[str]


def cites_sources(answer: Answer) -> Accepted | Rejected:
    return Rejected("say which tools you used in sources") if not answer.sources else Accepted()


# "answer" is the wire name, written into every task of this type.
ANSWER = TaskType(
    "answer",
    "Answer a question about a shopping cart, citing what you looked at",
    result=Answer,
    rules=(TaskRule("cites-sources", cites_sources),),
)
# docs:end task-type


@dataclass(frozen=True)
class CartRef:
    cartId: str


# docs:start agent
async def _cart(agent: AutonomousAgent, ref: CartRef) -> ShoppingCart:
    assert agent.client is not None
    return await agent.client.for_event_sourced_entity("shopping-cart", ref.cartId).call("get-cart").invoke(reply=ShoppingCart)


async def cart_contents(agent: AutonomousAgent, ref: CartRef) -> str:
    cart = await _cart(agent, ref)
    return ", ".join(f"{i.quantity} x {i.name}" for i in cart.items) or f"cart {ref.cartId} is empty"


async def cart_total(agent: AutonomousAgent, ref: CartRef) -> str:
    cart = await _cart(agent, ref)
    return f"cart {ref.cartId} holds {sum(i.quantity for i in cart.items)} items"


class CartAnswerer(AutonomousAgent):
    """Tools may run more than once for one request of the model — after a crash, the last recorded
    request's tools run again — and these only read, so that costs nothing."""

    component_id = "cart-answerer"
    description = "Answers questions about shopping carts"
    instructions = "Look carts up rather than guessing. Be brief."
    tools = {
        "cart_contents": Tool("Lists what is in a cart.", cart_contents, CartRef),
        "cart_total": Tool("Counts the items in a cart.", cart_total, CartRef),
    }
    accepts = [TaskAcceptance(ANSWER, max_iterations=5)]
# docs:end agent
