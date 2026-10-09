"""The reference service's extras: what the conformance suite drives beside the cart. Every wire
name and route here is named in `specs/009-polyglot-runtimes/contracts/conformance.md`; the Scala
reference (`sidecar/src/test/.../ConformanceReference.scala`) has the same, and the suite says
where the two disagree."""

from __future__ import annotations

from ankka.contract import Contract, Publication
from ankka.erasure import Done as ErasureDone
from ankka.erasure import ErasureContext, Failed
from ankka.personal import Personal, personal

import asyncio
import json
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from datetime import timedelta
from typing import Any

from ankka import Answered, AwaitingApproval, McpServer, ResultGuardrail
from ankka import DONE, StartFrom, Acl, Callers, Done, Gateway, GraphConsumer, Metadata, ServiceCaller, Endpoint, ErrorCode, EventSourcedEffect, EventSourcedEntity, HttpProblem, ReadOnlyEffect, command, delete, get, json_codec, post, query, sse, Socket, socket
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
from ankka.view import View, query as declare, table_of
from ankka.effects.keyed_view import KeyedViewEffect
from ankka.keyed_view import KeyedView, on

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


# ── contract-relay: a consumer that states a contract, a declared broker and parallel reading ──

# The contract every reference states, from the same schema document: the fixtures' `order.v1`.
ORDER_SCHEMA = (
    b'{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",'
    b'"required":["id","total"],"properties":{"id":{"type":"string"},"total":{"type":"number"}}}'
)
ORDER_CONTRACT = Contract.from_bytes(ORDER_SCHEMA, name="order.v1")


# docs:start contract-relay
class ContractRelay(Consumer[Fanned, Fanned]):
    """Reads `conformance-contracts` as `order.v1` on broker `legacy`, partitions in parallel, and publishes one more."""

    component_id = "contract-relay"
    topic = "conformance-contracts"
    start_from = StartFrom.EARLIEST
    contract = ORDER_CONTRACT
    broker = "legacy"
    parallel = True
    message_codec = json_codec(Fanned, "fanned")
    produces_to = Publication("conformance-contracted", contract=ORDER_CONTRACT, broker="legacy")
    out_codec = json_codec(Fanned, "fanned")

    def on_message(self, message: Fanned) -> ConsumerEffect:
        return self.effects.produce(Fanned(n=message.n + 1))
# docs:end contract-relay


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

    @action("tick")
    async def tick(self, id: str) -> TimedActionEffect:
        """Records the due time it was run for, as the runtime told it."""
        assert self.client is not None
        due = self.metadata.get("ankka.due")
        assert due is not None, "a timed action is run with ankka.due"
        await self.client.for_event_sourced_entity("conformance", id).call("record").invoke(f"due:{int(due)}", reply=str)
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


async def _sensitive_lookup(agent: AutonomousAgent, arguments: LookupArguments) -> str:
    assert agent.client is not None
    entity = agent.client.for_event_sourced_entity("conformance", arguments.id)
    await entity.call("record").invoke("sensitive", reply=str)
    return f"sensitive count for {arguments.id} is {await entity.call('count').invoke(reply=int)}"


class ConformanceAnswerer(AutonomousAgent):
    component_id = "answerer"
    description = "Answers questions"
    tools = {
        "lookup": Tool("Looks up how many things were recorded under an id.", _lookup, LookupArguments),
        # Waits for a person before it runs; what it records is counted as a run.
        "sensitive_lookup": Tool(
            "Looks up what was recorded under an id. A person approves every one.", _sensitive_lookup, LookupArguments, approval=True
        ),
    }
    guardrails = {"no-secrets": Guardrail(lambda stage, text: f"{stage} rejected by no-secrets" if "sk-" in text else None)}
    accepts = [TaskAcceptance(ANSWER, max_iterations=4)]


# ── approver: an agent whose tools wait for a person, with two MCP servers ──


@dataclass(frozen=True)
class RefundArguments:
    id: str


@dataclass(frozen=True)
class PathArguments:
    path: str


# docs:start approver
async def _refund(agent: Agent, arguments: RefundArguments) -> str:
    assert agent.client is not None
    await agent.client.for_event_sourced_entity("conformance", arguments.id).call("record").invoke("refunded", reply=str)
    return f"refunded {arguments.id}"


async def _ask_scripted(agent: Agent, arguments: PathArguments) -> str:
    # A tool calls another service as this service: the called service's ACL can admit it by name.
    return await agent.services("scripted").get_text(arguments.path)


def _no_instructions(tool: str, text: str) -> str | None:
    return "the result tries to instruct whoever reads it" if "ignore what you were told" in text.lower() else None


class Approver(Agent):
    component_id = "approver"
    tools = {
        "refund": Tool("Refunds what was recorded under an id. A person approves every refund.", _refund, RefundArguments, approval=True),
        "ask_scripted": Tool("Asks the scripted service for what is at a path.", _ask_scripted, PathArguments),
    }
    # Both found at ANKKA_MCP_<NAME>_URL; every tool of `guarded` waits for a person.
    mcp_servers = {"tickets": McpServer(), "guarded": McpServer(approval=True)}
    result_guardrails = {"no-instructions": ResultGuardrail(_no_instructions)}

    @command("ask")
    def ask(self, question: str) -> AgentEffect[str]:
        return self.effects.system_message("You approve refunds.").user_message(question).tools("refund", "ask_scripted").then_reply()


# docs:end approver


def _render_outcome(outcome: Answered[str] | AwaitingApproval) -> str:
    """As every reference renders it: ``{"answered": text}`` or ``{"awaiting": [{"id", "tool", "arguments"}]}``."""
    if isinstance(outcome, Answered):
        return json.dumps({"answered": outcome.value})
    return json.dumps({"awaiting": [{"id": r.id, "tool": r.tool, "arguments": r.arguments} for r in outcome.requests]})


def _decision(body: str) -> dict[str, Any]:
    """``{"approved": bool, "by": name, "note": text}``, as the suite sends it."""
    request = json.loads(body)
    return {"approved": bool(request.get("approved", False)), "by": request.get("by", ""), "note": request.get("note") or None}


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
SOCKET_LOG: list[str] = []
"""What the socket handlers noticed, for a case to read once a socket has closed."""


def _caller_word(c: Any) -> str:
    if isinstance(c, ServiceCaller):
        return f"service:{c.project}/{c.name}"
    return "gateway" if isinstance(c, Gateway) else "local"


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

    # Sockets (protocol 1.9). Echoes each frame; "context" is answered with the room and the
    # opening request's `tag`, read after any number of frames.
    @socket("/socket/{room}")
    async def socket_room(self, room: str, socket: Socket) -> None:
        async for text in socket:
            if text == "context":
                await socket.send(f"{room} {self.request.query_param('tag') or ''}")
            else:
                await socket.send(text)
        SOCKET_LOG.append(f"closed:{room}")

    @socket("/socket-once")
    async def socket_once(self, socket: Socket) -> None:
        await socket.receive()

    @socket("/socket-fail")
    async def socket_fail(self, socket: Socket) -> None:
        await socket.receive()
        raise RuntimeError("the socket handler broke")

    @get("/socket-log")
    def socket_log(self) -> list[str]:
        return list(SOCKET_LOG)

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

    # A recurring timer: due at once, then every second.
    @post("/recur/{id}")
    async def recur(self, id: str) -> Done:
        await self.client.timers.schedule_recurring(f"recur-{id}", timedelta(0), timedelta(seconds=1), "reminder", "tick", id)
        return DONE

    # The same timer set again, with a delay a replacement would be first due after.
    @post("/recur/{id}/again")
    async def recur_again(self, id: str) -> Done:
        await self.client.timers.schedule_recurring(f"recur-{id}", timedelta(seconds=60), timedelta(seconds=1), "reminder", "tick", id)
        return DONE

    @post("/recur/{id}/cancel")
    async def recur_cancel(self, id: str) -> Done:
        await self.client.timers.cancel(f"recur-{id}")
        return DONE

    # A period of zero: the SDK refuses it with BAD_REQUEST, which the endpoint answers as a 400.
    @post("/recur-refused/{id}")
    async def recur_refused(self, id: str) -> Done:
        await self.client.timers.schedule_recurring(f"recur-{id}", timedelta(0), timedelta(0), "reminder", "tick", id)
        return DONE

    @post("/ask/{session}")
    async def ask(self, session: str, question: str) -> str:
        return await self._scoped().for_agent("assistant", session).call("ask").invoke(question, reply=str)

    # docs:start approver-routes
    @post("/approver/{session}")
    async def ask_approver(self, session: str, question: str) -> str:
        """A turn that may wait: the model's answer, or the approval requests it waits on."""
        return _render_outcome(await self._scoped().for_agent("approver", session).call("ask").ask(question))

    @post("/approver/{session}/decide/{id}")
    async def decide_approval(self, session: str, id: str, body: str) -> str:
        """A person's decision, answered as the turn's caller would have been once it goes on."""
        return _render_outcome(await self._scoped().for_agent("approver", session).call("ask").decide(id, **_decision(body)))

    # docs:end approver-routes

    @sse("/stream-ask/{session}")
    async def stream_ask(self, session: str) -> AsyncIterator[str]:
        question = next((v for k, v in self.request.query if k == "q"), "")
        async for token in self._scoped().for_agent("assistant", session).call("stream").stream(question):
            yield token

    # A personal field: written, read back, and found by its lookup token (protocol 1.15).
    @post("/members/{id}")
    async def join_member(self, id: str, email: str) -> str:
        return await self._scoped().for_event_sourced_entity("member", id).call("join").invoke(email, reply=str)

    @get("/members/{id}")
    async def member_email(self, id: str) -> str:
        return await self._scoped().for_event_sourced_entity("member", id).call("email").invoke(reply=str)

    @get("/members/by-email/{email}")
    async def members_by_email(self, email: str) -> list[str]:
        scoped = self._scoped()
        token = await scoped.lookup_token(email)
        rows = await scoped.views.ask("member-rows", "by-email", MemberRow, {"email": token})
        return [row.memberId for row in rows]

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

    @post("/instances/{instance}/decide/{id}")
    async def decide(self, instance: str, id: str, body: str) -> Done:
        await self._scoped().for_autonomous_agent(ConformanceAnswerer, instance).decide(id, **_decision(body))
        return DONE

    @sse("/instances/{instance}/notifications")
    async def notifications(self, instance: str) -> AsyncIterator[str]:
        async for n in self._scoped().for_autonomous_agent(ConformanceAnswerer, instance).notifications():
            yield n.to_json()

    @get("/instances/{instance}/state")
    async def state(self, instance: str) -> str:
        s = await self._scoped().for_autonomous_agent(ConformanceAnswerer, instance).state()
        awaiting = [{"id": r.id, "tool": r.tool} for r in s.awaiting]
        return json.dumps({"phase": s.phase, "queued": s.queued, "currentTask": s.current_task, "awaiting": awaiting})


# ── member and member-rows: a personal field (protocol 1.15) ──
#
# The email is a personal field of the data subject ``member/<id>``; the row the view keeps marks it
# for lookup, so a declared query finds a member by email without the table ever holding it.


@dataclass(frozen=True)
class MemberJoined:
    memberId: str
    email: Personal[str]


@dataclass(frozen=True)
class MemberState:
    email: Personal[str] | None = None


class Member(EventSourcedEntity[MemberState, MemberJoined]):
    component_id = "member"
    state_codec = json_codec(MemberState, "member-state")
    event_codec = json_codec(MemberJoined, "member-event")

    def empty_state(self) -> MemberState:
        return MemberState()

    def apply_event(self, state: MemberState, event: MemberJoined) -> MemberState:
        return MemberState(event.email)

    @command("join")
    def join(self, email: str) -> EventSourcedEffect[MemberState, MemberJoined, str]:
        joined = MemberJoined(self.entity_id, personal(f"member/{self.entity_id}", email, lookup=True))
        return self.effects.persist(joined).then_reply(lambda _: "done")

    @query("email")
    def email(self) -> ReadOnlyEffect[MemberState, MemberJoined, str]:
        if self.state.email is None:
            return self.effects.reply("none")
        value = self.state.email.value
        return self.effects.reply("erased" if value is None else value)


@dataclass(frozen=True)
class MemberRow:
    memberId: str
    email: Personal[str]


class MemberRows(View[MemberJoined, MemberRow]):
    component_id = "member-rows"
    source = Member
    event_codec = Member.event_codec
    row_codec = json_codec(MemberRow, "member-row")
    by_email = declare(
        "by-email",
        f"SELECT payload FROM {table_of('member-rows')} WHERE payload::jsonb->'email'->>'lookup' = :email ORDER BY row_key",
    )

    def on_change(self, event: MemberJoined) -> ViewEffect:
        # The journal carries no token: the row marks the email for lookup where it is written.
        return self.effects.update_row(MemberRow(event.memberId, event.email.for_lookup()))


# ── tree-node and tree-rows: a tree, walked by a declared recursive query ──


@dataclass(frozen=True)
class Placed:
    under: str | None = None


class TreeNode(EventSourcedEntity[Placed, Placed]):
    """A node of a tree: ``place`` takes its parent's id, empty for none."""

    component_id = "tree-node"
    state_codec = json_codec(Placed, "tree-node")
    event_codec = json_codec(Placed, "tree-event")

    def empty_state(self) -> Placed:
        return Placed()

    def apply_event(self, state: Placed, event: Placed) -> Placed:
        return event

    @command("place")
    def place(self, under: str) -> EventSourcedEffect[Placed, Placed, str]:
        return self.effects.persist(Placed(under or None)).then_reply(lambda _: "placed")


@dataclass(frozen=True)
class TreeRow:
    key: str
    under: str | None = None


class TreeRows(View[Placed, TreeRow]):
    """One row per node, and every row under one to any depth: the same statement in every language."""

    component_id = "tree-rows"
    source = TreeNode
    event_codec = TreeNode.event_codec
    row_codec = json_codec(TreeRow, "tree-row")
    under = declare(
        "under",
        f"""WITH RECURSIVE below AS (
  SELECT row_key, payload FROM {table_of("tree-rows")} WHERE payload::jsonb->>'under' = :row
  UNION
  SELECT n.row_key, n.payload FROM {table_of("tree-rows")} n JOIN below b ON n.payload::jsonb->>'under' = b.row_key
)
SELECT payload FROM below ORDER BY row_key""",
    )

    def on_change(self, event: Placed) -> ViewEffect:
        return self.effects.update_row(TreeRow(self.metadata.subject or "", event.under))


class TreeEndpoint(Endpoint):
    """Places nodes of a tree and asks what is under one."""

    prefix = "/tree"
    acl = Acl.ALLOW_ALL

    def __init__(self, client: ComponentClient) -> None:
        self.client = client

    async def _place(self, node_id: str, under: str) -> str:
        calls = self.client.with_metadata(self.request.metadata).for_event_sourced_entity("tree-node", node_id)
        return await calls.call("place").invoke(under, reply=str)

    @post("/{nodeId}")
    async def root(self, nodeId: str) -> str:
        return await self._place(nodeId, "")

    @post("/{nodeId}/under/{parentId}")
    async def under(self, nodeId: str, parentId: str) -> str:
        return await self._place(nodeId, parentId)

    @get("/{nodeId}/below")
    async def below(self, nodeId: str) -> list[str]:
        views = self.client.with_metadata(self.request.metadata).views
        rows = await views.ask("tree-rows", "under", TreeRow, {"row": nodeId})
        return [row.key for row in rows]


# ── joined-left, joined-right, joined-rows: a keyed view of two sources ──


@dataclass(frozen=True)
class JoinedNote:
    text: str = ""


class JoinedLeft(EventSourcedEntity[int, JoinedNote]):
    """``record`` takes text and records it; ``joined-right`` is the same entity under its own id."""

    component_id = "joined-left"
    state_codec = json_codec(int, "joining")
    event_codec = json_codec(JoinedNote, "noted")

    def empty_state(self) -> int:
        return 0

    def apply_event(self, state: int, event: JoinedNote) -> int:
        return state + 1

    @command("record")
    def record(self, text: str) -> EventSourcedEffect[int, JoinedNote, str]:
        return self.effects.persist(JoinedNote(text)).then_reply(lambda _: "recorded")


class JoinedRight(JoinedLeft):
    component_id = "joined-right"


@dataclass(frozen=True)
class JoinedRow:
    key: str
    holding: str
    notes: list[str] = field(default_factory=list)


class JoinedRows(KeyedView[JoinedRow]):
    """A keyed view of two sources: the left names a row ``key|holding`` and notes itself on it; the
    right finds every row holding it by asking the view's own query, and notes itself on each."""

    component_id = "joined-rows"
    row_codec = json_codec(JoinedRow, "joined-row")
    of_right = declare(
        "of-right",
        f"SELECT payload FROM {table_of('joined-rows')} WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key",
    )

    @on(JoinedLeft, JoinedLeft.event_codec)
    async def on_left(self, event: JoinedNote) -> KeyedViewEffect:
        key, holding = event.text.split("|")
        held = await self.rows.get(key)
        notes = held.notes if held is not None else []
        return self.effects.update_row(key, JoinedRow(key, holding, [*notes, "left"]))

    @on(JoinedRight, JoinedRight.event_codec)
    async def on_right(self, event: JoinedNote) -> KeyedViewEffect:
        theirs = await self.rows.ask("of-right", holding=self.subject)
        return self.effects.update_rows({row.key: JoinedRow(row.key, row.holding, [*row.notes, "right"]) for row in theirs})


class JoinedEndpoint(Endpoint):
    """Records on either side of the keyed view, and reads its rows."""

    prefix = "/joined"
    acl = Acl.ALLOW_ALL

    def __init__(self, client: ComponentClient) -> None:
        self.client = client

    async def _record(self, entity: str, entity_id: str, text: str) -> str:
        calls = self.client.with_metadata(self.request.metadata).for_event_sourced_entity(entity, entity_id)
        return await calls.call("record").invoke(text, reply=str)

    # The left entity is the row's own key: one left per row.
    @post("/left/{key}/{holding}")
    async def left(self, key: str, holding: str) -> str:
        return await self._record("joined-left", key, f"{key}|{holding}")

    @post("/right/{rightId}")
    async def right(self, rightId: str) -> str:
        return await self._record("joined-right", rightId, "")

    @get("/rows/{key}")
    async def rows(self, key: str) -> JoinedRow:
        found = await self.client.with_metadata(self.request.metadata).views.get("joined-rows", key, JoinedRow)
        if found is None:
            raise HttpProblem(404, f"no row '{key}'")
        return found  # type: ignore[no-any-return]


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

    @socket("/socket")
    async def socket_me(self, socket: Socket) -> None:
        p = self.request.principal
        assert p is not None, "an authenticated route is handed its principal"
        SOCKET_LOG.append(f"private:{p.subject}")
        await socket.send(json.dumps({"subject": p.subject, "roles": sorted(p.roles), "caller": _caller_word(self.request.caller)}))
        async for _ in socket:
            pass


# docs:start erasure-handler
async def erase_objects(ctx: ErasureContext) -> ErasureDone | Failed:
    """Erases the data subject's objects from the service's bucket, on every application."""
    erased = await ctx.objects.erase()
    return ErasureDone(f"erased {erased.count} objects", erased)


# docs:end erasure-handler


def reference_service() -> ServiceBuilder:
    """The cart sample plus the conformance extras: what `uv run conformance` serves."""
    return (
        Ankka.service()
        .on_erasure(erase_objects)
        .register(ShoppingCartEntity)
        .register(CartRows)
        .register(CheckoutWorkflow)
        .register(Conformance)
        .register(Profile)
        .register(CheckoutRecorder)
        .register(CheckoutFanout)
        .register(TopicRows)
        .register(TopicRelay)
        .register(ContractRelay)
        .register(TreeNode)
        .register(TreeRows)
        .register(Member)
        .register(MemberRows)
        .register(JoinedLeft)
        .register(JoinedRight)
        .register(JoinedRows)
        .register(ConformanceCartGraph)
        .register(ProfileGraph)
        .register(Reminder)
        .register(ConformanceAssistant)
        .register(ConformanceAnswerer)
        .register(Approver)
        .register(ShoppingCartEndpoint)
        .register(ConformanceEndpoint)
        .register(TreeEndpoint)
        .register(JoinedEndpoint)
        .register(PrivateEndpoint)
        .register(CallersEndpoint)
        .register(AutonomousEndpoint)
    )
