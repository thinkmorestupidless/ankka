"""An agent that answers questions about a cart. It declares its instructions, one tool and one
guardrail; the sidecar runs the loop — the model, memory, compaction — and asks this process to run
the tool and check the guardrail. No model key lives here."""

from __future__ import annotations

from dataclasses import dataclass

from ankka.agent import Agent, Guardrail, Tool, stream
from ankka.effects.agent import AgentEffect
from ankka.event_sourced_entity import command

from examples.shopping_cart.domain import ShoppingCart


@dataclass(frozen=True)
class CartLookup:
    cartId: str


async def _lookup(agent: Agent, arguments: CartLookup) -> str:
    assert agent.client is not None
    cart = await agent.client.for_event_sourced_entity("shopping-cart", arguments.cartId).call("get-cart").invoke(reply=ShoppingCart)
    if not cart.items:
        return f"cart {arguments.cartId} is empty"
    return ", ".join(f"{i.quantity} x {i.name}" for i in cart.items)


class CartAssistant(Agent):
    component_id = "assistant"
    tools = {"lookup": Tool("Looks up what is in a cart by its id.", _lookup, CartLookup)}
    guardrails = {"no-secrets": Guardrail(lambda stage, text: "a key leaked" if stage == "output" and "sk-" in text else None)}

    def _describe(self, question: str) -> AgentEffect[str]:
        return (
            self.effects.system_message("You help shoppers with their carts. Use the lookup tool before answering about a cart.")
            .user_message(question)
            .tools("lookup")
            .guardrails("no-secrets")
            .then_reply()
        )

    @command("ask")
    def ask(self, question: str) -> AgentEffect[str]:
        return self._describe(question)

    @stream("chat")
    def chat(self, question: str) -> AgentEffect[str]:
        return self._describe(question)
