// Key value entities, workflows, views, consumers and timed actions: through the unit testkits, and the
// workflow's two slots through the servicer.
import { test, describe, before, after } from "node:test"
import assert from "node:assert/strict"
import { create } from "@bufbuild/protobuf"
import { createClient } from "@connectrpc/connect"
import { Ankka, RegistrationError } from "../src/service.ts"
import { noClient } from "../src/client.ts"
import { Workflow } from "../src/workflow.ts"
import { command } from "../src/handlers.ts"
import { jsonCodec } from "../src/codec.ts"
import { Consumer } from "../src/consumer.ts"
import { Done, done, s, type Infer } from "../src/schema.ts"
import { workflowSettings } from "../src/effects/workflow.ts"
import { KeyValueTestKit, WorkflowTestKit, ViewTestKit, ConsumerTestKit, TimedActionTestKit } from "../src/testkit/kinds.ts"
import { Kind } from "../src/_proto/ankka/protocol/v1/discovery_pb.ts"
import { Workflow as WorkflowService, WorkflowInSchema, type WorkflowIn, type WorkflowOut } from "../src/_proto/ankka/protocol/v1/workflow_pb.ts"
import { AsyncQueue } from "../src/server/queue.ts"
import { Consumer as ConsumerService, ConsumerRequestSchema } from "../src/_proto/ankka/protocol/v1/consumer_pb.ts"
import { ConsumerEffects } from "../src/effects/stateless.ts"
import { PROTOCOL_VERSION } from "../src/spec.ts"
import { ApprovalWorkflow, Calculator, Fanout, Ping, Ponger, Profile, Reminder } from "./fixtures/kinds.ts"
import { Counter } from "./fixtures/counter.ts"
import { CartRows } from "../examples/shopping-cart/cartRows.ts"
import { payload, startServer, type Started } from "./helpers.ts"

describe("key value entities", () => {
  test("update, reply from the new state, query, delete, expire, refuse", async () => {
    const kit = KeyValueTestKit.of(Profile, "p1")
    const set = await kit.call(Profile.handlers.set, "Ada")
    assert.equal(set.reply, "Ada#1")
    assert.equal(set.changed, true)
    assert.deepEqual(kit.state, { name: "Ada", visits: 1 })
    assert.equal((await kit.call(Profile.handlers.get)).reply, "Ada")
    const bad = await kit.call(Profile.handlers.set, "")
    assert.equal(bad.error?.code, "BAD_REQUEST")
    assert.deepEqual(kit.state, { name: "Ada", visits: 1 })
    const removed = await kit.call(Profile.handlers.remove)
    assert.equal(removed.retention?.kind, "delete-now")
    assert.equal(removed.changed, false)
    const expired = await kit.call(Profile.handlers.expire)
    assert.equal(expired.retention?.kind, "expire-after")
    await assert.rejects(kit.call(Profile.handlers.boom), /kaboom/)
  })
})

describe("workflows", () => {
  test("a command starts a step; steps transition to the end", async () => {
    const kit = WorkflowTestKit.of(ApprovalWorkflow, "a1")
    const started = await kit.call(ApprovalWorkflow.handlers.start, "ok")
    assert.equal(started.reply, done)
    assert.deepEqual(kit.progress.pending, { step: "check", input: undefined })
    assert.equal(kit.state.status, "checking")
    const progress = await kit.runUntilEnd()
    assert.equal(progress.ended, true)
    assert.deepEqual(kit.state, { id: "a1", status: "done", mode: "ok", note: "all good" })
    const again = await kit.call(ApprovalWorkflow.handlers.start, "ok")
    assert.equal(again.error?.code, "CONFLICT")
  })

  test("a pause stops runUntilEnd; resume runs the timeout step with its input", async () => {
    const kit = WorkflowTestKit.of(ApprovalWorkflow, "a2")
    await kit.call(ApprovalWorkflow.handlers.start, "pause")
    const paused = await kit.runUntilEnd()
    assert.equal(paused.ended, false)
    assert.deepEqual(paused.paused, { after: 500, onTimeout: { step: "finish", input: "timed out" } })
    assert.equal(kit.state.status, "waiting")
    const ended = await kit.resume()
    assert.equal(ended.ended, true)
    assert.equal(kit.state.note, "timed out")
  })

  test("a command moves a paused workflow on", async () => {
    const kit = WorkflowTestKit.of(ApprovalWorkflow, "a3")
    await kit.call(ApprovalWorkflow.handlers.start, "pause")
    await kit.runUntilEnd()
    await kit.call(ApprovalWorkflow.handlers.approve)
    await kit.runUntilEnd()
    assert.deepEqual([kit.state.status, kit.state.note], ["done", "approved by hand"])
  })

  test("thenFail ends the workflow failed; a thrown step is thrown (the engine's to recover)", async () => {
    const kit = WorkflowTestKit.of(ApprovalWorkflow, "a4")
    await kit.call(ApprovalWorkflow.handlers.start, "fail")
    const progress = await kit.runUntilEnd()
    assert.deepEqual(progress.failed, { message: "declined", code: "FORBIDDEN" })
    const kit2 = WorkflowTestKit.of(ApprovalWorkflow, "a5")
    await kit2.call(ApprovalWorkflow.handlers.start, "throw")
    await assert.rejects(kit2.runUntilEnd(), /check exploded/)
  })

  test("settings that name an unknown step are refused at registration", () => {
    class Bad extends Workflow<{ n: number }> {
      static readonly componentId = "bad"
      static readonly state = jsonCodec(s.record("N", { n: s.int }))
      static readonly handlers = { go: command("go", Done, (w: Bad) => w.effects.transitionTo("a").thenReply(() => done)) }
      static readonly steps = {}
      static readonly settings = workflowSettings({ defaultRecovery: { maxRetries: 1, failoverTo: "nope" }, steps: { ghost: { timeout: undefined } } })
      emptyState() {
        return { n: 0 }
      }
    }
    assert.throws(
      () => Ankka.service({ client: noClient() }).register(Bad).validate(),
      (e: unknown) => e instanceof RegistrationError && /fails over to "nope"/.test(e.message) && /name step "ghost"/.test(e.message),
    )
  })

  test("a transition to an undeclared step is refused when it is made", async () => {
    class Stray extends Workflow<{ n: number }> {
      static readonly componentId = "stray"
      static readonly state = jsonCodec(s.record("N", { n: s.int }))
      static readonly handlers = { go: command("go", Done, (w: Stray) => w.effects.transitionTo("elsewhere").thenReply(() => done)) }
      static readonly steps = {}
      emptyState() {
        return { n: 0 }
      }
    }
    const kit = WorkflowTestKit.of(Stray, "s1")
    await assert.rejects(kit.call(Stray.handlers.go), /"elsewhere", which is not a declared step/)
  })
})

describe("the workflow servicer's two slots", () => {
  let started: Started
  before(async () => {
    started = await startServer(Ankka.service({ client: noClient(), log: () => {} }).register(ApprovalWorkflow))
  })
  after(() => started.stop())

  test("a command mid-step is answered from the pre-step state; the step replies when it ends; a second step is refused", async () => {
    const workflow = createClient(WorkflowService, started.transport)
    const requests = new AsyncQueue<WorkflowIn>()
    const replies = workflow.handle(requests)[Symbol.asyncIterator]()
    const next = async (): Promise<WorkflowOut> => {
      const r = await replies.next()
      if (r.done) throw new Error("ended")
      return r.value
    }
    requests.push(create(WorkflowInSchema, { message: { case: "init", value: { componentId: "approval", entityId: "w1" } } }))
    requests.push(create(WorkflowInSchema, { message: { case: "command", value: { id: 1n, name: "start", payload: payload(s.string, "ok"), metadata: { entries: [] } } } }))
    const startReply = await next()
    assert.equal(startReply.message.case, "reply")
    assert.equal(startReply.message.case === "reply" && startReply.message.value.transition?.step, "check")

    requests.push(create(WorkflowInSchema, { message: { case: "runStep", value: { id: 2n, step: "slow" } } }))
    requests.push(create(WorkflowInSchema, { message: { case: "command", value: { id: 3n, name: "status", payload: payload(s.string, ""), metadata: { entries: [] } } } }))
    requests.push(create(WorkflowInSchema, { message: { case: "runStep", value: { id: 4n, step: "check" } } }))

    const first = await next()
    assert.equal(first.message.case, "reply", "the command is answered before the slow step ends")
    assert.equal(first.message.case === "reply" && first.message.value.commandId, 3n)
    assert.equal(new TextDecoder().decode(first.message.case === "reply" && first.message.value.outcome?.outcome.case === "reply" ? first.message.value.outcome.outcome.value.payload?.data : undefined), "checking")

    const second = await next()
    assert.equal(second.message.case, "failure", "a second step while one runs is refused")
    assert.match(second.message.case === "failure" ? second.message.value.error?.message ?? "" : "", /already running/)

    const third = await next()
    assert.equal(third.message.case, "stepReply")
    assert.equal(third.message.case === "stepReply" && third.message.value.commandId, 2n)
    assert.equal(third.message.case === "stepReply" && third.message.value.next?.outcome.case, "end")

    requests.push(create(WorkflowInSchema, { message: { case: "command", value: { id: 5n, name: "status", payload: payload(s.string, ""), metadata: { entries: [] } } } }))
    const after = await next()
    assert.equal(new TextDecoder().decode(after.message.case === "reply" && after.message.value.outcome?.outcome.case === "reply" ? after.message.value.outcome.outcome.value.payload?.data : undefined), "slowed", "the step's new state applied when it replied")
    requests.close()
  })
})

describe("views", () => {
  test("rows follow the source's events; the source's deletion removes the row", async () => {
    const kit = ViewTestKit.of(CartRows)
    await kit.onChange("c1", { type: "ItemAdded", item: { productId: "p1", name: "Pen", quantity: 2 } })
    await kit.onChange("c1", { type: "ItemAdded", item: { productId: "p1", name: "Pen", quantity: 3 } })
    await kit.onChange("c1", { type: "ItemAdded", item: { productId: "p2", name: "Ink", quantity: 1 } })
    await kit.onChange("c1", { type: "ItemRemoved", productId: "p2" })
    assert.deepEqual(kit.get("c1"), { cartId: "c1", quantities: { p1: 5 }, checkedOut: false })
    await kit.onChange("c1", { type: "CheckedOut" })
    assert.deepEqual(kit.get("c1"), { cartId: "c1", quantities: { p1: 5 }, checkedOut: true })
    // A discarded cart: the event changes nothing, the deletion that follows removes the row.
    await kit.onChange("c2", { type: "ItemAdded", item: { productId: "p1", name: "Pen", quantity: 1 } })
    assert.equal((await kit.onChange("c2", { type: "Discarded" })).kind, "ignore")
    assert.equal((await kit.onDelete("c2")).kind, "delete-row")
    assert.equal(kit.get("c2"), null)
  })
})

describe("consumers and timed actions", () => {
  test("a consumer produces, acknowledges or ignores; what it produces is round-tripped", async () => {
    const kit = ConsumerTestKit.of(Ponger)
    assert.equal((await kit.onMessage({ n: 2 }, "src-1")).kind, "produce")
    assert.equal((await kit.onMessage({ n: 0 })).kind, "done")
    assert.equal((await kit.onMessage({ n: -1 })).kind, "ignore")
    assert.equal((await kit.onDelete()).kind, "ignore")
    assert.deepEqual(kit.produced, [{ payload: { n: 2, from: "src-1" }, metadata: { "x-ping": "2" } }])
  })

  test("a consumer produces several messages in order, each with its key and its metadata", async () => {
    const kit = ConsumerTestKit.of(Fanout)
    const effect = await kit.onMessage({ n: 7 }, "src-1")
    assert.equal(effect.kind, "produceAll")
    assert.deepEqual(kit.produced, [
      { payload: { n: 1 }, metadata: {} },
      { payload: { n: 2 }, metadata: {}, key: "second:src-1" },
      { payload: { n: 3 }, metadata: { "x-n": "3" } },
    ])
    // The deletion handler may produce several too.
    await kit.onDelete("src-1")
    assert.deepEqual(kit.produced.at(-1), { payload: { n: -1 }, metadata: {}, key: "gone:src-1" })
  })

  test("an empty list produces nothing; a single produce is recorded as it always was", async () => {
    const kit = ConsumerTestKit.of(Fanout)
    const none = await kit.onMessage({ n: 0 })
    assert.equal(none.kind, "produceAll")
    assert.deepEqual(kit.produced, [])
    assert.equal((await kit.onMessage({ n: 1 })).kind, "produce")
    assert.deepEqual(kit.produced, [{ payload: { n: 1 }, metadata: {} }])
  })

  test("an empty record key is refused where it is named", () => {
    const effects = new ConsumerEffects<{ n: number }>()
    assert.throws(() => effects.produceAll([{ payload: { n: 1 }, key: "" }]), /empty record key/)
    assert.throws(() => effects.produceAll([{ payload: { n: 1 }, key: 7 as unknown as string }]), /not a string/)
    assert.throws(() => effects.produceAll({ payload: { n: 1 } } as never), /a list of messages/)
    // Building several messages publishes nothing: the effect is a frozen value.
    const effect = effects.produceAll([{ payload: { n: 1 }, key: "k" }])
    assert.ok(Object.isFrozen(effect))
    assert.deepEqual(effect, { kind: "produceAll", messages: [{ payload: { n: 1 }, key: "k" }] })
  })

  test("a consumer reads the change's sequence number", async () => {
    const seen: (bigint | undefined)[] = []
    class Watcher extends Consumer<Infer<typeof Ping>> {
      static readonly componentId = "watcher"
      static readonly topic = "pings"
      static readonly message = jsonCodec(Ping, "ping")
      onMessage() {
        seen.push(this.sequenceNumber)
        return this.effects.done()
      }
    }
    const kit = ConsumerTestKit.of(Watcher)
    await kit.onMessage({ n: 1 })
    await kit.onMessage({ n: 1 }, "s", { "ankka.sequence": "12" })
    await kit.onMessage({ n: 1 }, "s", { "ankka.sequence": "9007199254740993" })
    assert.deepEqual(seen, [undefined, 12n, 9007199254740993n])
  })

  test("the test kit holds the server's rule: several messages only to a runtime that accepts them", async () => {
    const kit = ConsumerTestKit.of(Fanout)
    await assert.rejects(kit.onMessage({ n: 7 }, "s", { "ankka.protocol": "1.2" }), /this runtime speaks protocol 1\.2; several messages or a record key need 1\.3/)
    // A single message and an empty list go to any runtime.
    assert.equal((await kit.onMessage({ n: 1 }, "s", { "ankka.protocol": "1.2" })).kind, "produce")
    assert.equal((await kit.onMessage({ n: 0 }, "s", { "ankka.protocol": "1.2" })).kind, "produceAll")
    assert.deepEqual(kit.produced, [{ payload: { n: 1 }, metadata: {} }])
  })

  test("a timed action is done or fails", async () => {
    const kit = TimedActionTestKit.of(Reminder)
    assert.equal((await kit.invoke(Reminder.actions.remind, "c1")).kind, "done")
    const failed = await kit.invoke("remind", "bad")
    assert.equal(failed.kind, "fail")
    assert.equal((await kit.invoke(Reminder.actions.ping)).kind, "done")
  })
})

describe("the consumer servicer and several messages", () => {
  let started: Started
  let consumer: ReturnType<typeof createClient<typeof ConsumerService>>
  before(async () => {
    started = await startServer(Ankka.service({ client: noClient(), log: () => {} }).register(Fanout).register(Ponger))
    consumer = createClient(ConsumerService, started.transport)
  })
  after(() => started.stop())

  const request = (componentId: string, n: number, metadata: Record<string, string>, deleted = false) =>
    create(ConsumerRequestSchema, {
      componentId,
      ...(deleted ? {} : { message: payload(jsonCodec(Ping, "ping"), { n }) }),
      metadata: { entries: Object.entries(metadata).map(([key, value]) => ({ key, value })) },
      deleted,
    })
  const text = (data: Uint8Array | undefined) => new TextDecoder().decode(data)

  test("the reply is produce_all: the messages in order, the key only where one was named", async () => {
    const reply = await consumer.handle(request("fanout", 7, { "ce-subject": "c1", "ankka.sequence": "3", "ankka.protocol": PROTOCOL_VERSION }))
    assert.equal(reply.effect.case, "produceAll")
    if (reply.effect.case !== "produceAll") return
    const messages = reply.effect.value.messages
    assert.deepEqual(messages.map((m) => text(m.payload?.data)), ['{"n":1}', '{"n":2}', '{"n":3}'])
    assert.deepEqual(messages.map((m) => m.payload?.manifest), ["fan", "fan", "fan"])
    assert.deepEqual(messages.map((m) => m.key), [undefined, "second:c1", undefined])
    assert.deepEqual(messages.map((m) => m.metadata?.entries.map((e) => [e.key, e.value]) ?? []), [[], [], [["x-n", "3"]]])
  })

  test("a later minor, and a later major, are runtimes that accept several messages", async () => {
    for (const version of ["1.3", "1.10", "2.0"]) {
      const reply = await consumer.handle(request("fanout", 7, { "ce-subject": "c1", "ankka.protocol": version }))
      assert.equal(reply.effect.case, "produceAll", version)
    }
  })

  test("to a request that does not say the runtime accepts them, several messages fail the request", async () => {
    const refused = (metadata: Record<string, string>, deleted = false) => consumer.handle(request("fanout", 7, { "ce-subject": "c1", ...metadata }, deleted))
    // The message is the contract's, exactly: it is what the runtime's log shows.
    await assert.rejects(refused({}), (e: Error) => e.message.endsWith('this runtime speaks protocol 1.2 or earlier; several messages or a record key need 1.3'))
    await assert.rejects(refused({ "ankka.protocol": "1.2" }), (e: Error) => e.message.endsWith("this runtime speaks protocol 1.2; several messages or a record key need 1.3"))
    await assert.rejects(refused({ "ankka.protocol": "1.0" }), /speaks protocol 1\.0;/)
    await assert.rejects(refused({ "ankka.protocol": "nonsense" }), /speaks protocol nonsense;/)
    // One keyed message is still a record key, and still needs 1.3.
    await assert.rejects(refused({}, true), /several messages or a record key need 1\.3/)
  })

  test("a single un-keyed produce, and an empty list, are answered to any runtime", async () => {
    const single = await consumer.handle(request("fanout", 1, { "ce-subject": "c1" }))
    assert.equal(single.effect.case, "produce")
    assert.equal(single.effect.case === "produce" && text(single.effect.value.payload?.data), '{"n":1}')
    // No messages is `done`, which every runtime reads the same way.
    const none = await consumer.handle(request("fanout", 0, { "ce-subject": "c1" }))
    assert.equal(none.effect.case, "done")
    // And the consumer of old is untouched.
    const pong = await consumer.handle(request("ponger", 2, { "ce-subject": "src-1" }))
    assert.equal(pong.effect.case, "produce")
    assert.equal(pong.effect.case === "produce" && text(pong.effect.value.payload?.data), '{"n":2,"from":"src-1"}')
    assert.deepEqual(pong.effect.case === "produce" && pong.effect.value.metadata?.entries.map((e) => [e.key, e.value]), [["x-ping", "2"]])
  })
})

describe("discovery of every kind", () => {
  test("the spec carries each kind's detail", () => {
    const spec = Ankka.service({ client: noClient() }).register(Counter).register(Profile).register(ApprovalWorkflow).register(CartRows).register(Ponger).register(Reminder).register(Calculator).validate()
    const rendered = Ankka.service({ client: noClient() }).register(Counter).register(Profile).register(ApprovalWorkflow).register(CartRows).register(Ponger).register(Reminder).register(Calculator).spec()
    assert.equal(spec.components.size, 7)
    const byId = Object.fromEntries(rendered.components.map((c) => [c.id, c]))
    assert.equal(byId["profile"]!.kind, Kind.KEY_VALUE_ENTITY)
    assert.equal(byId["profile"]!.detail.case, "keyValue")
    const wf = byId["approval"]!.detail
    assert.equal(wf.case, "workflow")
    if (wf.case === "workflow") {
      assert.deepEqual(wf.value.steps, ["check", "finish", "slow"])
      assert.equal(wf.value.settings?.defaultStepTimeoutMillis, 5000n)
      assert.deepEqual(wf.value.settings?.steps.map((st) => [st.step, st.recovery?.maxRetries, st.recovery?.failoverTo]), [["check", 2, "finish"]])
    }
    const view = byId["cart-rows"]!.detail
    assert.equal(view.case, "view")
    if (view.case === "view") {
      assert.equal(view.value.rowManifest, "cart-row")
      assert.deepEqual(view.value.queries, ["by-id", "all"])
      assert.equal(view.value.source?.source.case, "component")
      assert.equal(view.value.source?.source.case === "component" && view.value.source.source.value.id, "shopping-cart")
    }
    const consumer = byId["ponger"]!.detail
    assert.equal(consumer.case, "consumer")
    if (consumer.case === "consumer") {
      assert.equal(consumer.value.source?.source.case, "topic")
      assert.equal(consumer.value.producesTo, "pongs")
    }
    assert.equal(byId["reminder"]!.kind, Kind.TIMED_ACTION)
    assert.deepEqual(byId["reminder"]!.handlers.map((h) => h.name), ["ping", "remind"])
    const agent = byId["calculator"]!.detail
    assert.equal(agent.case, "agent")
    if (agent.case === "agent") {
      assert.equal(agent.value.role, "adds numbers")
      assert.equal(agent.value.maxToolCallSteps, 3)
      assert.deepEqual(agent.value.guardrails, ["polite"])
      assert.equal(agent.value.tools[0]?.name, "add")
      assert.deepEqual(JSON.parse(agent.value.tools[0]!.inputSchemaJson), { type: "object", properties: { a: { type: "integer" }, b: { type: "integer" } }, required: ["a", "b"], additionalProperties: false })
    }
    assert.deepEqual(byId["calculator"]!.handlers.filter((h) => h.streaming).map((h) => h.name), ["chat"])
  })
})
