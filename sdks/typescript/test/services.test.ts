// Other services as a TypeScript service calls them: what is sent to the runtime, how each reply is
// read, who is given a client, and a runtime too old to make the call. The call itself is made by the
// sidecar and is held by the conformance suite's `service.*` cases.
import { test, describe, before, after } from "node:test"
import assert from "node:assert/strict"
import { createServer, type Http2Server, type ServerHttp2Session } from "node:http2"
import type { AddressInfo } from "node:net"
import { create } from "@bufbuild/protobuf"
import { Code, ConnectError } from "@connectrpc/connect"
import { connectNodeAdapter } from "@connectrpc/connect-node"
import { Client, ServiceFailure_Reason, ServiceReplySchema, type ServiceReply, type ServiceRequest } from "../src/_proto/ankka/protocol/v1/client_pb.ts"
import { ErrorCode as ProtoErrorCode } from "../src/_proto/ankka/protocol/v1/payload_pb.ts"
import { ComponentClient } from "../src/client.ts"
import { CommandError, type Metadata } from "../src/effects/common.ts"
import { withRequest, type RequestContext } from "../src/context.ts"
import { s } from "../src/schema.ts"
import { Consumer } from "../src/consumer.ts"
import { GraphConsumer } from "../src/graph.ts"
import { TimedAction } from "../src/timedAction.ts"
import { Agent } from "../src/agent.ts"
import { AutonomousAgent } from "../src/autonomous.ts"
import { Endpoint } from "../src/endpoint.ts"
import { Workflow } from "../src/workflow.ts"
import { EventSourcedEntity } from "../src/eventSourcedEntity.ts"
import { KeyValueEntity } from "../src/keyValueEntity.ts"
import { View } from "../src/view.ts"
import {
  MAX_BODY_BYTES,
  ScriptedServices,
  ServiceCallFailed,
  ServiceError,
  ServiceIdentityMismatch,
  ServiceUnanswered,
  ServiceUnresolvable,
  Services,
} from "../src/services.ts"

const utf8 = new TextEncoder()
const TRACE: Metadata = { "ankka-trace-id": "abc", "ankka-caller": "payouts#initiate" }
const metadataOf = (r: ServiceRequest) => Object.fromEntries(r.metadata?.entries.map((e) => [e.key, e.value]) ?? [])

describe("calling another service", () => {
  let server: Http2Server
  let client: ComponentClient
  // http2's close() waits for every session, and a client keeps an idle one open, so they are destroyed by hand.
  const sessions = new Set<ServerHttp2Session>()
  const sent: ServiceRequest[] = []
  let next: () => ServiceReply = () => answer()

  function answer(status = 200, body = "ok", contentType = "text/plain"): ServiceReply {
    return create(ServiceReplySchema, {
      result: { case: "response", value: { status, contentType, body: utf8.encode(body), headers: [{ name: "X-Answer", value: "yes" }] } },
    })
  }

  before(async () => {
    server = createServer(
      connectNodeAdapter({
        routes: (router) =>
          router.service(Client, {
            async request(req) {
              sent.push(req)
              return next()
            },
          }),
      }),
    )
    server.on("session", (session) => {
      sessions.add(session)
      session.once("close", () => sessions.delete(session))
    })
    await new Promise<void>((r) => server.listen(0, "127.0.0.1", () => r()))
    client = new ComponentClient(`127.0.0.1:${(server.address() as AddressInfo).port}`)
  })
  after(async () => {
    for (const session of sessions) session.destroy()
    await new Promise<void>((r) => server.close(() => r()))
  })

  test("each way of calling builds the request the runtime is sent", async () => {
    sent.length = 0
    next = () => answer(200, '{"amount":5}', "application/json")
    const payout = s.record("payout", { amount: s.int })
    const psp = new Services(client).service("psp-gateway")
    await psp.get("/payouts/1", payout)
    await psp.getText("/text")
    await psp.post("/payouts", { amount: 5 }, { body: payout, returns: payout }, { headers: [["X-Request-Id", "r1"]] })
    await psp.put("/payouts/1", { amount: 6 }, { body: payout, returns: payout })
    await psp.delete("/payouts/1")
    await psp.request("PATCH", "/raw", { body: utf8.encode("x"), contentType: "text/plain" })
    await new Services(client).service("billing", "invoices").getText("/i")
    assert.deepEqual(
      sent.map((r) => [r.method, r.path]),
      [["GET", "/payouts/1"], ["GET", "/text"], ["POST", "/payouts"], ["PUT", "/payouts/1"], ["DELETE", "/payouts/1"], ["PATCH", "/raw"], ["GET", "/i"]],
    )
    assert.ok(sent.slice(0, 6).every((r) => r.service === "psp-gateway" && r.project === undefined))
    assert.deepEqual([sent[6]!.service, sent[6]!.project], ["invoices", "billing"])
    assert.equal(sent[2]!.contentType, "application/json")
    assert.equal(new TextDecoder().decode(sent[2]!.body), '{"amount":5}')
    assert.deepEqual(sent[2]!.headers.map((h) => [h.name, h.value]), [["X-Request-Id", "r1"]])
    assert.equal(sent[0]!.body, undefined)
  })

  test("a call carries the metadata of the handler making it, for every kind that may call", async () => {
    sent.length = 0
    next = () => answer()
    const scoped = client.withMetadata(TRACE)
    class Forwarder extends Consumer<string> {
      onMessage() {
        return this.effects.ignore()
      }
    }
    class Grapher extends GraphConsumer<string> {
      onMessage() {
        return this.effects.ignore()
      }
    }
    class Poller extends TimedAction {}
    class Asker extends Agent {}
    class Reporter extends AutonomousAgent {}
    const consumer = new Forwarder()
    consumer._bind({}, scoped)
    const graph = new Grapher()
    graph._bind({}, scoped)
    const action = new Poller()
    action._bind({}, scoped)
    const agent = new Asker()
    agent._bind("s-1", TRACE, scoped)
    const autonomous = new Reporter()
    autonomous._bind("i-1", scoped)
    for (const kind of [consumer, graph, action, agent, autonomous]) await kind.services.service("psp-gateway").getText("/x")
    assert.equal(sent.length, 5)
    for (const r of sent) assert.deepEqual(metadataOf(r), TRACE)
  })

  test("an endpoint's call carries the metadata of the request it is handling", async () => {
    sent.length = 0
    next = () => answer()
    class Calling extends Endpoint {
      static readonly prefix = "/calling"
    }
    const endpoint = new Calling()
    endpoint._bindClient(client)
    await withRequest({ metadata: TRACE } as unknown as RequestContext, () => endpoint.services.service("psp-gateway").getText("/x"))
    assert.deepEqual(metadataOf(sent[0]!), TRACE)
  })

  test("an answer outside 2xx is returned by request and raised by a typed helper", async () => {
    next = () => answer(404, "gone")
    const psp = new Services(client).service("psp-gateway")
    const answered = await psp.request("GET", "/x")
    assert.deepEqual([answered.status, answered.text], [404, "gone"])
    assert.deepEqual(answered.headers, [["X-Answer", "yes"]])
    await assert.rejects(psp.getText("/x"), (e: unknown) => e instanceof ServiceCallFailed && e.status === 404 && e.body === "gone")
  })

  test("each way a call gets no answer is its own error", async () => {
    for (const [reason, error] of [
      [ServiceFailure_Reason.UNRESOLVABLE, ServiceUnresolvable],
      [ServiceFailure_Reason.IDENTITY_MISMATCH, ServiceIdentityMismatch],
      [ServiceFailure_Reason.UNANSWERED, ServiceUnanswered],
    ] as const) {
      next = () => create(ServiceReplySchema, { result: { case: "failure", value: { reason, detail: "what was tried" } } })
      await assert.rejects(
        new Services(client).service("psp-gateway").request("GET", "/x"),
        (e: unknown) => e instanceof error && e instanceof ServiceError && e.message.includes("what was tried"),
      )
    }
  })

  test("a refusal by the runtime is a CommandError, and an empty reply a fault", async () => {
    next = () => create(ServiceReplySchema, { result: { case: "error", value: { message: "in a step", code: ProtoErrorCode.BAD_REQUEST } } })
    await assert.rejects(new Services(client).service("psp-gateway").request("GET", "/x"), (e: unknown) => e instanceof CommandError && e.code === "BAD_REQUEST")
    next = () => create(ServiceReplySchema, {})
    await assert.rejects(new Services(client).service("psp-gateway").request("GET", "/x"), (e: unknown) => e instanceof CommandError && e.code === "INTERNAL")
  })

  test("a body over the limit is refused before anything is sent", async () => {
    sent.length = 0
    await assert.rejects(
      new Services(client).service("psp-gateway").request("POST", "/x", { body: new Uint8Array(MAX_BODY_BYTES + 1) }),
      (e: unknown) => e instanceof CommandError && e.message.includes(String(MAX_BODY_BYTES)),
    )
    assert.equal(sent.length, 0)
  })
})

describe("a runtime that cannot call another service", () => {
  test("is reported as too old", async () => {
    const refuse = () => Promise.reject(new ConnectError("Method not found", Code.Unimplemented))
    const stub = new Proxy({}, { get: () => refuse })
    const old = new ComponentClient("old-runtime", { address: "old-runtime", transport: undefined, stub: stub as never })
    await assert.rejects(new Services(old).service("psp-gateway").getText("/x"), (e: unknown) => e instanceof CommandError && e.code === "INTERNAL" && e.message.includes("1.8"))
  })
})

describe("who is given a client for other services", () => {
  test("an entity or a view is given none", () => {
    for (const cls of [EventSourcedEntity, KeyValueEntity, View]) assert.equal("services" in cls.prototype, false, cls.name)
    // And by type: none of these compiles, which `npm run typecheck` holds.
    const neverCalled = (es: EventSourcedEntity<any, any>, kv: KeyValueEntity<any>, view: View<any, any>) => {
      // @ts-expect-error an event sourced entity has no client for other services
      void es.services
      // @ts-expect-error a key value entity has no client for other services
      void kv.services
      // @ts-expect-error a view has no client for other services
      void view.services
    }
    void neverCalled
  })

  test("every other kind is given one", () => {
    for (const cls of [Consumer, GraphConsumer, TimedAction, Agent, AutonomousAgent, Endpoint, Workflow]) assert.equal("services" in cls.prototype, true, cls.name)
  })

  test("a workflow calls another service in a step and not in a command", () => {
    class Payout extends Workflow<string> {
      static readonly componentId = "payout-services-test"
      emptyState(): string {
        return ""
      }
    }
    const workflow = new Payout()
    workflow.services = new ScriptedServices()
    assert.throws(() => workflow.services, (e: unknown) => e instanceof CommandError && e.code === "BAD_REQUEST" && e.message.includes("in a step"))
    workflow._enterStep()
    assert.ok(workflow.services instanceof ScriptedServices)
  })
})

// docs:start step-calls-service
class PayoutWorkflow extends Workflow<string> {
  static readonly componentId = "payout"
  emptyState(): string {
    return ""
  }

  /** A step asks the PSP gateway service to start a payout, and goes on with the answer. */
  async initiatePayout(amount: number): Promise<string> {
    return this.services.service("psp-gateway").getText(`/payouts?amount=${amount}`)
  }
}

test("a workflow's step calls another service and goes on with the answer", async () => {
  const workflow = new PayoutWorkflow()
  const scripted = new ScriptedServices().answer("psp-gateway", (request) => ScriptedServices.text(`started ${request.path}`))
  workflow.services = scripted
  workflow._enterStep()
  assert.equal(await workflow.initiatePayout(25), "started /payouts?amount=25")
  assert.deepEqual(scripted.requests.map((r) => r.path), ["/payouts?amount=25"])
})
// docs:end step-calls-service

test("the unit-test double answers as scripted and records what it was asked", async () => {
  const scripted = new ScriptedServices()
    .answer("psp-gateway", (r) => ScriptedServices.json(new TextDecoder().decode(r.body)))
    .answer("invoices", () => ScriptedServices.text("i"), "billing")
    .unresolvable("a")
    .unanswered("b")
    .mismatch("c")
  const payout = s.record("payout", { amount: s.int })
  assert.deepEqual(await scripted.service("psp-gateway").post("/p", { amount: 3 }, { body: payout, returns: payout }), { amount: 3 })
  assert.equal(await scripted.service("billing", "invoices").getText("/"), "i")
  await assert.rejects(scripted.service("a").getText("/"), ServiceUnresolvable)
  await assert.rejects(scripted.service("b").getText("/"), ServiceUnanswered)
  await assert.rejects(scripted.service("c").getText("/"), ServiceIdentityMismatch)
  await assert.rejects(scripted.service("ledger").getText("/"), (e: unknown) => e instanceof Error && e.message.includes('answer("ledger"'))
})
