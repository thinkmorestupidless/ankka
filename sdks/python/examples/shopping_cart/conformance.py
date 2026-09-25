"""The reference service's extras: what the conformance suite drives beside the cart. Every wire
name and route here is named in `specs/009-polyglot-runtimes/contracts/conformance.md`; the Scala
reference (`sidecar/src/test/.../ConformanceReference.scala`) has the same, and the suite says
where the two disagree."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from datetime import timedelta

from ankka import DONE, Acl, Done, Endpoint, ErrorCode, EventSourcedEffect, EventSourcedEntity, HttpProblem, ReadOnlyEffect, command, delete, get, json_codec, post, query, sse
from ankka.agent import Agent, Guardrail, Tool, stream
from ankka.client import ComponentClient
from ankka.consumer import Consumer
from ankka.effects.agent import AgentEffect
from ankka.effects.consumer import ConsumerEffect
from ankka.effects.key_value import KeyValueEffect, KeyValueReadOnlyEffect
from ankka.effects.timed_action import TimedActionEffect
from ankka.key_value_entity import KeyValueEntity
from ankka.service import Ankka, ServiceBuilder
from ankka.timed_action import TimedAction, action

from examples.shopping_cart.cart_rows import CartRows
from examples.shopping_cart.checkout_workflow import Checkout, CheckoutWorkflow
from examples.shopping_cart.domain import CheckedOut, ShoppingCartEvent
from examples.shopping_cart.endpoint import ShoppingCartEndpoint
from examples.shopping_cart.entity import ShoppingCartEntity


# ── conformance: an entity whose handlers are the protocol's edge cases ──


@dataclass(frozen=True)
class Recorded:
    input: str


@dataclass(frozen=True)
class Recordings:
    items: list[str] = field(default_factory=list)


class Conformance(EventSourcedEntity[Recordings, Recorded]):
    component_id = "conformance"
    state_codec = json_codec(Recordings, "conformance-state")
    event_codec = json_codec(Recorded, "conformance-event")
    snapshot_every = 3

    def empty_state(self) -> Recordings:
        return Recordings()

    def apply_event(self, state: Recordings, event: Recorded) -> Recordings:
        return Recordings([*state.items, event.input])

    @command("record")
    def record(self, input: str) -> EventSourcedEffect[Recordings, Recorded, str]:
        return self.effects.persist(Recorded(input)).then_reply(lambda _: "done")

    @command("record-many")
    def record_many(self, n: int) -> EventSourcedEffect[Recordings, Recorded, str]:
        return self.effects.persist(*[Recorded(f"many-{i}") for i in range(n)]).then_reply(lambda _: "done")

    @command("refuse")
    def refuse(self) -> EventSourcedEffect[Recordings, Recorded, str]:
        return self.effects.error("refused on purpose", ErrorCode.CONFLICT)

    @command("no-reply")
    def no_reply(self) -> EventSourcedEffect[Recordings, Recorded, str]:
        return self.effects.persist(Recorded("silent")).then_no_reply()

    @command("delete")
    def delete(self) -> EventSourcedEffect[Recordings, Recorded, str]:
        return self.effects.delete_entity().then_reply(lambda _: "done")

    @command("expire")
    def expire(self, millis: int) -> EventSourcedEffect[Recordings, Recorded, str]:
        return self.effects.persist(Recorded("expiring")).expire_after(timedelta(milliseconds=millis)).then_reply(lambda _: "done")

    @query("count")
    def count(self) -> ReadOnlyEffect[Recordings, Recorded, int]:
        return self.effects.reply(len(self.state.items))

    @command("misbehave")
    def misbehave(self) -> EventSourcedEffect[Recordings, Recorded, str]:
        raise RuntimeError("boom")


# ── profile: a key value entity ──


@dataclass(frozen=True)
class ProfileState:
    name: str = ""


class Profile(KeyValueEntity[ProfileState]):
    component_id = "profile"
    state_codec = json_codec(ProfileState, "profile")

    def empty_state(self) -> ProfileState:
        return ProfileState()

    @command("set")
    def set(self, name: str) -> KeyValueEffect[ProfileState, str]:
        if not name:
            return self.effects.error("a name is needed")
        return self.effects.update_state(ProfileState(name)).then_reply(lambda _: "done")

    @query("get")
    def get(self) -> KeyValueReadOnlyEffect[ProfileState, str]:
        return self.effects.reply(self.state.name or "none")

    @command("delete")
    def delete(self) -> KeyValueEffect[ProfileState, str]:
        return self.effects.delete_entity().then_reply(lambda _: "done")


# ── checkout-recorder: a consumer that acts through the client ──


class CheckoutRecorder(Consumer[ShoppingCartEvent, None]):
    component_id = "checkout-recorder"
    source = ShoppingCartEntity
    message_codec = ShoppingCartEntity.event_codec

    async def on_message(self, event: ShoppingCartEvent) -> ConsumerEffect:  # type: ignore[override]
        if not isinstance(event, CheckedOut):
            return self.effects.ignore()
        assert self.client is not None
        await self.client.for_event_sourced_entity("conformance", self.metadata.subject or "").call("record").invoke("checkout", reply=str)
        return self.effects.done()


# ── reminder: a timed action ──


class Reminder(TimedAction):
    component_id = "reminder"

    @action("remind")
    async def remind(self, id: str) -> TimedActionEffect:
        assert self.client is not None
        await self.client.for_event_sourced_entity("conformance", id).call("record").invoke("reminded", reply=str)
        return self.effects.done()


# ── assistant: an agent whose tool acts through the client ──


@dataclass(frozen=True)
class LookupArguments:
    id: str


async def _lookup(agent: Agent, arguments: LookupArguments) -> str:
    if not arguments.id:
        raise ValueError("an id is needed")
    assert agent.client is not None
    entity = agent.client.for_event_sourced_entity("conformance", arguments.id)
    await entity.call("record").invoke("looked-up", reply=str)
    return f"count for {arguments.id} is {await entity.call('count').invoke(reply=int)}"


class ConformanceAssistant(Agent):
    component_id = "assistant"
    tools = {"lookup": Tool("Looks up how many things were recorded under an id.", _lookup, LookupArguments)}
    guardrails = {"no-secrets": Guardrail(lambda stage, text: f"{stage} rejected by no-secrets" if "sk-" in text else None)}

    def _describe(self, question: str) -> AgentEffect[str]:
        return self.effects.system_message("You are helpful.").user_message(question).tools("lookup").guardrails("no-secrets").then_reply()

    @command("ask")
    def ask(self, question: str) -> AgentEffect[str]:
        return self._describe(question)

    @stream("stream")
    def stream_ask(self, question: str) -> AgentEffect[str]:
        return self._describe(question)


# ── Endpoints ──


@dataclass(frozen=True)
class Echo:
    a: list[str]
    b: str | None
    headers: dict[str, str]


class ConformanceEndpoint(Endpoint):
    prefix = "/conformance"
    acl = Acl.ALLOW_ALL

    def __init__(self, client: ComponentClient) -> None:
        self.client = client

    def _scoped(self) -> ComponentClient:
        return self.client.with_metadata(self.request.metadata)

    @get("/problems")
    def problems(self) -> list[str]:
        from ankka.server import PROBLEMS

        return list(PROBLEMS)

    @get("/echo")
    def echo(self) -> Echo:
        headers = {k.lower(): v for k, v in self.request.headers}
        return Echo(
            [v for k, v in self.request.query if k == "a"],
            next((v for k, v in self.request.query if k == "b"), None),
            {"x-one": headers.get("x-one", ""), "x-two": headers.get("x-two", "")},
        )

    @get("/status/{code}")
    def status(self, code: int) -> str:
        raise HttpProblem(code, f"status {code} as asked")

    @get("/boom")
    def boom(self) -> str:
        raise RuntimeError("boom from the handler")

    # A route whose acl differs from its endpoint's: /conformance admits everyone, this one
    # admits nobody, and the routes declared around it are unaffected.
    @get("/closed", acl=Acl.DENY_ALL)
    def closed(self) -> str:
        return "never reached"

    @sse("/stream/{session}")
    async def stream_frames(self, session: str) -> AsyncIterator[str]:
        for frame in (" leading space", "two\nlines", "plain"):
            yield frame

    @post("/profile/{id}")
    async def set_profile(self, id: str, name: str) -> str:
        return await self._scoped().for_key_value_entity("profile", id).call("set").invoke(name, reply=str)

    @get("/profile/{id}")
    async def get_profile(self, id: str) -> str:
        return await self._scoped().for_key_value_entity("profile", id).call("get").invoke(reply=str)

    @delete("/profile/{id}")
    async def delete_profile(self, id: str) -> str:
        return await self._scoped().for_key_value_entity("profile", id).call("delete").invoke(reply=str)

    @post("/checkout/{id}")
    async def start_checkout(self, id: str, mode: str) -> str:
        await self._scoped().for_workflow("checkout", id).call("start").invoke(mode, reply=Done)
        return "started"

    @get("/checkout/{id}")
    async def checkout_status(self, id: str) -> str:
        checkout = await self._scoped().for_workflow("checkout", id).call("status").invoke(reply=Checkout)
        return str(checkout.status)

    @post("/remind/{id}")
    async def remind(self, id: str) -> Done:
        await self.client.timers.schedule(f"remind-{id}", timedelta(seconds=1), "reminder", "remind", id)
        return DONE

    @post("/ask/{session}")
    async def ask(self, session: str, question: str) -> str:
        return await self._scoped().for_agent("assistant", session).call("ask").invoke(question, reply=str)

    @sse("/stream-ask/{session}")
    async def stream_ask(self, session: str) -> AsyncIterator[str]:
        question = next((v for k, v in self.request.query if k == "q"), "")
        async for token in self._scoped().for_agent("assistant", session).call("stream").stream(question):
            yield token

    @get("/{id}/count")
    async def count(self, id: str) -> int:
        return await self._scoped().for_event_sourced_entity("conformance", id).call("count").invoke(reply=int)

    @post("/{id}/no-reply")
    async def no_reply(self, id: str) -> Done:
        # A handler that answers nothing: the call is never awaited, and the route says so with 204.
        call = self._scoped().for_event_sourced_entity("conformance", id).call("no-reply").invoke(reply=str)
        asyncio.get_running_loop().create_task(_swallow(call))
        return DONE

    @post("/{id}/{handler}")
    async def forward(self, id: str, handler: str, body: str) -> str:
        # The generic forwarder: the body is the handler's input as text, the reply comes back as text.
        return await self._scoped().for_event_sourced_entity("conformance", id).call(handler).invoke(body, reply=str)


async def _swallow(awaitable: object) -> None:
    try:
        await awaitable  # type: ignore[misc]
    except Exception:
        pass


class PrivateEndpoint(Endpoint):
    prefix = "/private"
    acl = Acl.AUTHENTICATED

    @get("/")
    def root(self) -> str:
        return "private"


def reference_service() -> ServiceBuilder:
    """The cart sample plus the conformance extras: what `uv run conformance` serves."""
    return (
        Ankka.service()
        .register(ShoppingCartEntity)
        .register(CartRows)
        .register(CheckoutWorkflow)
        .register(Conformance)
        .register(Profile)
        .register(CheckoutRecorder)
        .register(Reminder)
        .register(ConformanceAssistant)
        .register(ShoppingCartEndpoint)
        .register(ConformanceEndpoint)
        .register(PrivateEndpoint)
    )
