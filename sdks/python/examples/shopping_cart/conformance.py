"""The reference service's extras: what the conformance suite drives beside the cart. Every wire
name and route here is named in `specs/009-polyglot-runtimes/contracts/conformance.md`; the Scala
reference (`sidecar/src/test/.../ConformanceReference.scala`) has the same, and the suite says
where the two disagree."""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from datetime import timedelta
from typing import Any

from ankka import DONE, StartFrom, Acl, Callers, Done, Gateway, GraphConsumer, Metadata, ServiceCaller, Endpoint, ErrorCode, EventSourcedEffect, EventSourcedEntity, HttpProblem, ReadOnlyEffect, command, delete, get, json_codec, post, query, sse
from ankka.agent import Agent, Guardrail, Tool, stream
from ankka.autonomous import Accepted, AutonomousAgent, Rejected, TaskAcceptance, TaskRule, TaskSnapshot, TaskType
from ankka.client import CommandError, ComponentClient
from ankka.services import ServiceCallFailed, ServiceIdentityMismatch, ServiceUnanswered, ServiceUnresolvable
from ankka.consumer import Consumer
from ankka.effects.agent import AgentEffect
from ankka.effects.consumer import ConsumerEffect
from ankka.effects.key_value import KeyValueEffect, KeyValueReadOnlyEffect
from ankka.effects.timed_action import TimedActionEffect
from ankka.effects.view import ViewEffect
from ankka.graph import GraphEffect
from ankka.key_value_entity import KeyValueEntity
from ankka.service import Ankka, ServiceBuilder
from ankka.timed_action import TimedAction, action
from ankka.view import View

from examples.shopping_cart.cart_graph import CartGraph
from examples.shopping_cart.cart_rows import CartRows
from examples.shopping_cart.checkout_workflow import Checkout, CheckoutWorkflow
from examples.shopping_cart.domain import CheckedOut, ItemAdded, ItemRemoved, ShoppingCartEvent
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


# ── checkout-fanout: a consumer that publishes several messages for one change ──


# docs:start fanout
@dataclass(frozen=True)
class Fanned:
    n: int


class CheckoutFanout(Consumer[ShoppingCartEvent, Fanned]):
    """Three messages for a checkout — the second under a key of its own, the third with a header —
    none for an item added, and a single one, the old way, for an item removed."""

    component_id = "checkout-fanout"
    source = ShoppingCartEntity
    message_codec = ShoppingCartEntity.event_codec
    produces_to = "conformance-fanout"
    out_codec = json_codec(Fanned, "fanned")

    def on_message(self, event: ShoppingCartEvent) -> ConsumerEffect:
        match event:
            case ItemAdded():
                return self.effects.produce_all([])
            case ItemRemoved():
                return self.effects.produce(Fanned(0))
            case CheckedOut():
                return self.effects.produce_all(
                    [
                        self.effects.message(Fanned(1)),
                        self.effects.message(Fanned(2), key=f"second:{self.metadata.subject}"),
                        self.effects.message(Fanned(3), metadata=Metadata().set("x-n", "3")),
                    ]
                )
        return self.effects.ignore()
# docs:end fanout


# ── topic-rows and topic-relay: a view and a consumer over a topic ──


# docs:start topic-sources
class TopicRows(View[Fanned, Fanned]):
    """The latest message about each subject. Declares no start, so it reads from the earliest."""

    component_id = "topic-rows"
    topic = "conformance-topic"
    version = 2
    event_codec = json_codec(Fanned, "fanned")
    row_codec = json_codec(Fanned, "fanned")

    def on_change(self, message: Fanned) -> ViewEffect:
        return self.effects.update_row(message)


class TopicRelay(Consumer[Fanned, Fanned]):
    """Republishes what it reads, from the latest: none of what the topic held when it started."""

    component_id = "topic-relay"
    topic = "conformance-topic"
    start_from = StartFrom.LATEST
    message_codec = json_codec(Fanned, "fanned")
    produces_to = "conformance-topic-relayed"
    out_codec = json_codec(Fanned, "fanned")

    def on_message(self, message: Fanned) -> ConsumerEffect:
        return self.effects.produce(message)
# docs:end topic-sources


# ── cart-graph and profile-graph: graph consumers, over events and over a key value entity ──


class ConformanceCartGraph(CartGraph):
    """The example's cart graph, published where the suite reads it."""

    produces_to = "conformance-graph"


class ProfileGraph(GraphConsumer[ProfileState]):
    """A profile as a node at its revision, and its tombstone when it is deleted."""

    component_id = "profile-graph"
    source = Profile
    produces_to = "conformance-profile-graph"
    message_codec = Profile.state_codec

    def on_message(self, state: ProfileState) -> GraphEffect:
        return self.effects.publish([self.graph.node(f"profile:{self.metadata.subject}", labels=["Profile"], properties={"name": state.name})])

    def on_delete(self) -> GraphEffect:
        return self.effects.publish([self.graph.tombstone_node(f"profile:{self.metadata.subject}")])


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


# ── answerer: an autonomous agent, whose tool acts through the client ──


@dataclass
class Answer:
    answer: str
    sources: list[str]


def _cites_sources(a: Answer) -> Accepted | Rejected:
    return Rejected("sources must not be empty") if not a.sources else Accepted()


_flaky_seen: set[str] = set()


def _steady(a: Answer) -> Accepted | Rejected:
    """Raises the first time it sees "flaky-once": a rule that fails once, then decides."""
    if a.answer == "flaky-once" and a.answer not in _flaky_seen:
        _flaky_seen.add(a.answer)
        raise RuntimeError("the rule threw")
    return Accepted()


ANSWER: TaskType[Answer] = TaskType(
    "answer",
    "Answer a question, citing what you looked up",
    result=Answer,
    rules=(TaskRule("cites-sources", _cites_sources), TaskRule("steady", _steady)),
)


class ConformanceAnswerer(AutonomousAgent):
    component_id = "answerer"
    description = "Answers questions"
    tools = {"lookup": Tool("Looks up how many things were recorded under an id.", _lookup, LookupArguments)}
    guardrails = {"no-secrets": Guardrail(lambda stage, text: f"{stage} rejected by no-secrets" if "sk-" in text else None)}
    accepts = [TaskAcceptance(ANSWER, max_iterations=4)]


# ── Endpoints ──


@dataclass(frozen=True)
class Echo:
    a: list[str]
    b: str | None
    headers: dict[str, str]


@dataclass
class ServiceCallRecord:
    """What a call to another service came to, as the reference answers it in every language."""

    outcome: str
    status: int
    contentType: str  # noqa: N815 — the record's wire name, the same in every language
    body: str
    answer: str
    message: str


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

    # The secret store. The name is a query parameter because it may hold a slash.
    # docs:start secrets
    def _secret_name(self) -> str:
        return next((v for k, v in self.request.query if k == "name"), "")

    @post("/secrets")
    async def keep_secret(self, value: str) -> Done:
        await self.secrets.put(self._secret_name(), value)
        return DONE

    @get("/secrets")
    async def read_secret(self) -> str:
        name = self._secret_name()
        value = await self.secrets.get(name)
        if value is None:
            raise HttpProblem(404, f"no secret '{name}'")
        return value

    @delete("/secrets")
    async def remove_secret(self) -> Done:
        await self.secrets.delete(self._secret_name())
        return DONE

    # docs:end secrets

    # docs:start service-call
    @post("/service-call")
    async def service_call(self, body: str) -> ServiceCallRecord:
        """A call to another service, as the case asks for it: the body and every ``X-Conformance-*``
        header sent on, and two headers no handler may send added, to show they never arrive."""
        q = dict(self.request.query)
        headers = [(k, v) for k, v in self.request.headers if k.lower().startswith("x-conformance-")]
        headers += [("X-Ankka-Caller", "ankka://elsewhere/impostor"), ("Host", "elsewhere")]
        client = self.services(q["service"])
        try:
            if q.get("mode") == "typed":
                return ServiceCallRecord("response", 200, "", await client.get_text(q["path"], headers=headers), "", "")
            answer = await client.request(
                q["method"],
                q["path"],
                body=body.encode() if body else None,
                content_type=self.request.header("content-type") if body else None,
                headers=headers,
            )
            return ServiceCallRecord("response", answer.status, answer.content_type, answer.text, answer.header("x-answer") or "", "")
        except ServiceCallFailed as e:
            return ServiceCallRecord("failed", e.status, "", e.body, "", "")
        except ServiceUnresolvable as e:
            return ServiceCallRecord("unresolvable", 0, "", "", "", str(e))
        except ServiceIdentityMismatch as e:
            return ServiceCallRecord("mismatch", 0, "", "", "", str(e))
        except ServiceUnanswered as e:
            return ServiceCallRecord("unanswered", 0, "", "", "", str(e))
        except CommandError as e:
            return ServiceCallRecord("refused", 0, "", "", "", e.error.message)

    # docs:end service-call

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


class CallersEndpoint(Endpoint):
    """Caller-naming ACLs: the suite names callers through the local caller header."""

    prefix = "/callers"
    acl = Acl.allow_callers(Callers.internet, Callers.service("orders"))

    @get("/whoami")
    def whoami(self) -> str:
        c = self.request.caller
        if isinstance(c, ServiceCaller):
            return f"service:{c.project}/{c.name}"
        return "gateway" if isinstance(c, Gateway) else "local"

    @get("/self", acl=Acl.allow_callers(Callers.self_))
    def only_self(self) -> str:
        return "self"

    @sse("/events", acl=Acl.allow_callers(Callers.self_))
    async def events(self) -> AsyncIterator[str]:
        yield "tick"


class AutonomousEndpoint(Endpoint):
    """Autonomous agents: tasks run, read and cancelled; instances driven and watched."""

    prefix = "/autonomous"
    acl = Acl.ALLOW_ALL

    def __init__(self, client: ComponentClient) -> None:
        self.client = client

    def _scoped(self) -> ComponentClient:
        return self.client.with_metadata(self.request.metadata)

    @post("/tasks/{taskType}")
    async def run_task(self, taskType: str, instructions: str) -> str:
        if taskType != ANSWER.name:
            raise HttpProblem(400, f"no task type '{taskType}'")
        client = self._scoped()
        task_id = await client.for_autonomous_agent(ConformanceAnswerer).run_single_task(ANSWER, instructions)
        task: TaskSnapshot[Any] = await client.for_task(task_id).get()
        return json.dumps({"taskId": task_id, "instanceId": task.assignee[1] if task.assignee else ""})

    @get("/tasks/{id}")
    async def read_task(self, id: str) -> str:
        return json.dumps((await self._scoped().for_task(id).get()).record)

    @post("/tasks/{id}/cancel")
    async def cancel_task(self, id: str) -> Done:
        await self._scoped().for_task(id).cancel()
        return DONE

    @post("/tasks/{taskType}/create")
    async def create_task(self, taskType: str, body: str) -> str:
        """{"instructions": "...", "dependsOn": ["..."]} — creates without running."""
        if taskType != ANSWER.name:
            raise HttpProblem(400, f"no task type '{taskType}'")
        request = json.loads(body)
        task_id = await self._scoped().tasks.create(ANSWER, request.get("instructions", ""), depends_on=request.get("dependsOn", []))
        return json.dumps({"taskId": task_id})

    @post("/instances/{instance}/assign")
    async def assign(self, instance: str, body: str) -> str:
        answer = await self._scoped().for_autonomous_agent(ConformanceAnswerer, instance).assign(*json.loads(body))
        return json.dumps({"accepted": answer["accepted"]})

    @post("/instances/{instance}/{op}")
    async def operate(self, instance: str, op: str) -> Done:
        calls = self._scoped().for_autonomous_agent(ConformanceAnswerer, instance)
        if op == "suspend":
            await calls.suspend()
        elif op == "resume":
            await calls.resume()
        elif op == "terminate":
            await calls.terminate()
        else:
            raise HttpProblem(404, f"no operation '{op}'")
        return DONE

    @sse("/instances/{instance}/notifications")
    async def notifications(self, instance: str) -> AsyncIterator[str]:
        async for n in self._scoped().for_autonomous_agent(ConformanceAnswerer, instance).notifications():
            yield n.to_json()

    @get("/instances/{instance}/state")
    async def state(self, instance: str) -> str:
        s = await self._scoped().for_autonomous_agent(ConformanceAnswerer, instance).state()
        return json.dumps({"phase": s.phase, "queued": s.queued, "currentTask": s.current_task})


class PrivateEndpoint(Endpoint):
    prefix = "/private"
    acl = Acl.AUTHENTICATED

    @get("/")
    def root(self) -> str:
        return "private"

    @get("/me")
    def me(self) -> str:
        p = self.request.principal
        assert p is not None, "an authenticated route is handed its principal"
        return json.dumps(
            {"subject": p.subject, "roles": sorted(p.roles), "tier": p.claims.get("tier"), "issuer": p.issuer}
        )


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
        .register(CheckoutFanout)
        .register(TopicRows)
        .register(TopicRelay)
        .register(ConformanceCartGraph)
        .register(ProfileGraph)
        .register(Reminder)
        .register(ConformanceAssistant)
        .register(ConformanceAnswerer)
        .register(ShoppingCartEndpoint)
        .register(ConformanceEndpoint)
        .register(PrivateEndpoint)
        .register(CallersEndpoint)
        .register(AutonomousEndpoint)
    )
