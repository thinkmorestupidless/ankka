// The reference service's extras: what the conformance suite drives beside the cart. Every wire name
// and route here is named in `specs/009-polyglot-runtimes/contracts/conformance.md`; the Scala reference
// (`sidecar/src/test/.../ConformanceReference.scala`) and the Python one have the same.
import {
  Acl,
  Agent,
  AutonomousAgent,
  Callers,
  accepted,
  rejected,
  taskAcceptance,
  taskRule,
  taskType,
  Ankka,
  Consumer,
  Done,
  done,
  Duration,
  Endpoint,
  ErrorCode,
  EventSourcedEntity,
  GraphConsumer,
  HttpProblem,
  KeyValueEntity,
  TimedAction,
  action,
  command,
  del,
  get,
  guardrail,
  jsonCodec,
  post,
  query,
  s,
  sidecarProblems,
  sse,
  stream,
  tool,
  type Infer,
} from "ankka"
import { ShoppingCartEntity } from "./entity.ts"
import { ShoppingCartEndpoint } from "./endpoint.ts"
import { CartRows } from "./cartRows.ts"
import { CheckoutWorkflow } from "./checkoutWorkflow.ts"
import type { ShoppingCartEvent } from "./domain.ts"

// ── conformance: an entity whose handlers are the protocol's edge cases ──

export const Recorded = s.record("Recorded", { input: s.string })
export const Recordings = s.record("Recordings", { items: s.list(s.string) })
type Recorded = Infer<typeof Recorded>
type Recordings = Infer<typeof Recordings>

export class Conformance extends EventSourcedEntity<Recordings, Recorded> {
  static readonly componentId = "conformance"
  static readonly state = jsonCodec(Recordings, "conformance-state")
  static readonly events = jsonCodec(Recorded, "conformance-event")
  static readonly snapshotEvery = 3

  static readonly handlers = {
    record: command("record", s.string, s.string, (c: Conformance, input) => c.effects.persist({ input }).thenReply(() => "done")),
    recordMany: command("record-many", s.int, s.string, (c: Conformance, n) =>
      c.effects.persistAll(Array.from({ length: n }, (_, i) => ({ input: `many-${i}` }))).thenReply(() => "done"),
    ),
    refuse: command("refuse", s.string, (c: Conformance) => c.effects.error("refused on purpose", ErrorCode.Conflict)),
    noReply: command("no-reply", s.string, (c: Conformance) => c.effects.persist({ input: "silent" }).thenNoReply()),
    delete: command("delete", s.string, (c: Conformance) => c.effects.deleteEntity().thenReply(() => "done")),
    expire: command("expire", s.int, s.string, (c: Conformance, millis) =>
      c.effects.persist({ input: "expiring" }).expireAfter(Duration.ofMillis(millis)).thenReply(() => "done"),
    ),
    count: query("count", s.int, (c: Conformance) => c.effects.reply(c.state.items.length)),
    misbehave: command("misbehave", s.string, (): never => {
      throw new Error("boom")
    }),
  }

  emptyState(): Recordings {
    return { items: [] }
  }

  applyEvent(state: Recordings, event: Recorded): Recordings {
    return { items: [...state.items, event.input] }
  }
}

// ── profile: a key value entity ──

export const ProfileState = s.record("ProfileState", { name: s.string })

export class Profile extends KeyValueEntity<Infer<typeof ProfileState>> {
  static readonly componentId = "profile"
  static readonly state = jsonCodec(ProfileState, "profile")

  static readonly handlers = {
    set: command("set", s.string, s.string, (p: Profile, name) => (name ? p.effects.updateState({ name }).thenReply(() => "done") : p.effects.error("a name is needed"))),
    get: query("get", s.string, (p: Profile) => p.effects.reply(p.state.name || "none")),
    delete: command("delete", s.string, (p: Profile) => p.effects.deleteEntity().thenReply(() => "done")),
  }

  emptyState() {
    return { name: "" }
  }
}

// ── checkout-recorder: a consumer that acts through the client ──

export class CheckoutRecorder extends Consumer<ShoppingCartEvent> {
  static readonly componentId = "checkout-recorder"
  static readonly source = ShoppingCartEntity
  static readonly message = ShoppingCartEntity.events

  async onMessage(event: ShoppingCartEvent) {
    if (event.type !== "CheckedOut") return this.effects.ignore()
    await this.client.of(Conformance, this.subject).call(Conformance.handlers.record).invoke("checkout")
    return this.effects.done()
  }
}

// ── checkout-fanout: several messages for one change (protocol 1.3) ──

export const Fanned = s.record("Fanned", { n: s.int })

/**
 * Three messages for a checkout — the second under a key of its own, the third with a header — none
 * for an item added, and a single one, the old way, for an item removed.
 */
export class CheckoutFanout extends Consumer<ShoppingCartEvent, Infer<typeof Fanned>> {
  static readonly componentId = "checkout-fanout"
  static readonly source = ShoppingCartEntity
  static readonly message = ShoppingCartEntity.events
  static readonly out = jsonCodec(Fanned, "fanned")
  static readonly producesTo = "conformance-fanout"

  onMessage(event: ShoppingCartEvent) {
    switch (event.type) {
      case "ItemAdded":
        return this.effects.produceAll([])
      case "ItemRemoved":
        return this.effects.produce({ n: 0 })
      case "CheckedOut":
        return this.effects.produceAll([{ payload: { n: 1 } }, { payload: { n: 2 }, key: `second:${this.subject}` }, { payload: { n: 3 }, metadata: { "x-n": "3" } }])
      default:
        return this.effects.ignore()
    }
  }
}

// ── cart-graph: the cart as graph deltas ──

/** The cart graph every reference publishes, where the conformance suite reads it: the example's `CartGraph`, on another topic. */
export class ConformanceCartGraph extends GraphConsumer<ShoppingCartEvent> {
  static readonly componentId = "cart-graph"
  static readonly source = ShoppingCartEntity
  static readonly message = ShoppingCartEntity.events
  static readonly producesTo = "conformance-graph"

  onMessage(event: ShoppingCartEvent) {
    const id = this.subject
    const cart = (checkedOut: boolean) => this.graph.node(`cart:${id}`, { labels: ["Cart"], properties: { cartId: id, checkedOut } })
    switch (event.type) {
      case "ItemAdded":
      case "ItemRemoved":
        return this.effects.publish([cart(false)])
      case "CheckedOut":
        return this.effects.publish([
          cart(true),
          this.graph.node(`checkout:${id}`, { labels: ["Checkout"], properties: { cartId: id } }),
          this.graph.edge(`checked-out:${id}`, { type: "CHECKED_OUT", from: `cart:${id}`, to: `checkout:${id}` }),
        ])
      default:
        return this.effects.ignore()
    }
  }

  override onDelete() {
    return this.effects.publish([this.graph.tombstoneNode(`cart:${this.subject}`)])
  }
}

// ── profile-graph: a graph consumer over a key value entity; its versions are revisions ──

export class ProfileGraph extends GraphConsumer<Infer<typeof ProfileState>> {
  static readonly componentId = "profile-graph"
  static readonly source = Profile
  static readonly message = Profile.state
  static readonly producesTo = "conformance-profile-graph"

  onMessage(state: Infer<typeof ProfileState>) {
    return this.effects.publish([this.graph.node(`profile:${this.subject}`, { labels: ["Profile"], properties: { name: state.name } })])
  }

  override onDelete() {
    return this.effects.publish([this.graph.tombstoneNode(`profile:${this.subject}`)])
  }
}

// ── reminder: a timed action ──

export class Reminder extends TimedAction {
  static readonly componentId = "reminder"
  static readonly actions = {
    remind: action("remind", s.string, async (r: Reminder, id) => {
      await r.client.of(Conformance, id).call(Conformance.handlers.record).invoke("reminded")
      return r.effects.done()
    }),
  }
}

// ── assistant: an agent whose tool acts through the client ──

const LookupArguments = s.record("LookupArguments", { id: s.string })

export class ConformanceAssistant extends Agent {
  static readonly componentId = "assistant"
  static readonly tools = {
    lookup: tool("lookup", "Looks up how many things were recorded under an id.", LookupArguments, async (a: ConformanceAssistant, input) => {
      if (!input.id) throw new Error("an id is needed")
      const entity = a.client.of(Conformance, input.id)
      await entity.call(Conformance.handlers.record).invoke("looked-up")
      return `count for ${input.id} is ${await entity.call(Conformance.handlers.count).invoke()}`
    }),
  }
  static readonly guardrails = {
    noSecrets: guardrail("no-secrets", (stage, text) => (text.includes("sk-") ? `${stage} rejected by no-secrets` : null)),
  }
  static readonly handlers = {
    ask: command("ask", s.string, s.string, (a: ConformanceAssistant, question) => a.describe(question)),
    stream: stream("stream", s.string, (a: ConformanceAssistant, question) => a.describe(question)),
  }

  describe(question: string) {
    return this.effects.systemMessage("You are helpful.").userMessage(question).tools("lookup").guardrails("no-secrets").thenReply()
  }
}

// ── Endpoints ──

const Echo = s.record("Echo", { a: s.list(s.string), b: s.option(s.string), headers: s.stringMap(s.string) })

export class ConformanceEndpoint extends Endpoint {
  static readonly prefix = "/conformance"
  static readonly acl = Acl.allowAll

  static readonly routes = {
    problems: get("/problems", s.list(s.string), () => [...sidecarProblems]),
    echo: get("/echo", Echo, (ep: ConformanceEndpoint) => ({
      a: ep.request.query.getAll("a"),
      b: ep.request.query.get("b"),
      headers: { "x-one": ep.request.headers.get("x-one") ?? "", "x-two": ep.request.headers.get("x-two") ?? "" },
    })),
    status: get(
      "/status/{code}",
      s.string,
      (_ep: ConformanceEndpoint, req): string => {
        throw new HttpProblem(req.params.code, `status ${req.params.code} as asked`)
      },
      { params: { code: s.int } },
    ),
    boom: get("/boom", s.string, (): string => {
      throw new Error("boom from the handler")
    }),
    // A route whose acl differs from its endpoint's: /conformance admits everyone, this one admits
    // nobody, and the routes declared around it are unaffected.
    closed: get("/closed", s.string, () => "never reached", { acl: Acl.denyAll }),
    stream: sse("/stream/{session}", async function* () {
      for (const frame of [" leading space", "two\nlines", "plain"]) yield frame
    }),
    setProfile: post("/profile/{id}", s.string, s.string, (ep: ConformanceEndpoint, req, name) => ep.client.of(Profile, req.params.id).call(Profile.handlers.set).invoke(name)),
    getProfile: get("/profile/{id}", s.string, (ep: ConformanceEndpoint, req) => ep.client.of(Profile, req.params.id).call(Profile.handlers.get).invoke()),
    deleteProfile: del("/profile/{id}", s.string, (ep: ConformanceEndpoint, req) => ep.client.of(Profile, req.params.id).call(Profile.handlers.delete).invoke()),
    startCheckout: post("/checkout/{id}", s.string, s.string, async (ep: ConformanceEndpoint, req, mode) => {
      await ep.client.of(CheckoutWorkflow, req.params.id).call(CheckoutWorkflow.handlers.start).invoke(mode)
      return "started"
    }),
    checkoutStatus: get("/checkout/{id}", s.string, async (ep: ConformanceEndpoint, req) => (await ep.client.of(CheckoutWorkflow, req.params.id).call(CheckoutWorkflow.handlers.status).invoke()).status),
    remind: post("/remind/{id}", Done, async (ep: ConformanceEndpoint, req) => {
      await ep.client.timers.schedule(`remind-${req.params.id}`, Duration.ofSeconds(1), { component: Reminder, handler: Reminder.actions.remind }, req.params.id)
      return done
    }),
    ask: post("/ask/{session}", s.string, s.string, (ep: ConformanceEndpoint, req, question) => ep.client.of(ConformanceAssistant, req.params.session).call(ConformanceAssistant.handlers.ask).invoke(question)),
    streamAsk: sse("/stream-ask/{session}", (ep: ConformanceEndpoint, req) =>
      ep.client.of(ConformanceAssistant, req.params.session).call(ConformanceAssistant.handlers.stream).stream(req.query.get("q") ?? ""),
    ),
    count: get("/{id}/count", s.int, (ep: ConformanceEndpoint, req) => ep.client.of(Conformance, req.params.id).call(Conformance.handlers.count).invoke()),
    noReply: post("/{id}/no-reply", Done, (ep: ConformanceEndpoint, req) => {
      // A handler that answers nothing: the call is never awaited, and the route says so with 204.
      void ep.client.of(Conformance, req.params.id).call(Conformance.handlers.noReply).invoke().catch(() => undefined)
      return done
    }),
    // The generic forwarder: the body is the handler's input as text, the reply comes back as text.
    forward: post("/{id}/{handler}", s.string, s.string, (ep: ConformanceEndpoint, req, body) =>
      ep.client.forEventSourcedEntity(Conformance.componentId, req.params.id).call(req.params.handler, s.string, s.string).invoke(body),
    ),
  }
}

/** Caller-naming ACLs: the suite names callers through the local caller header. */
export class CallersEndpoint extends Endpoint {
  static readonly prefix = "/callers"
  static readonly acl = Acl.allowCallers(Callers.internet, Callers.service("orders"))
  static readonly routes = {
    whoami: get("/whoami", s.string, (_ep: CallersEndpoint, req) => {
      const c = req.caller
      return c.kind === "service" ? `service:${c.project}/${c.name}` : c.kind
    }),
    onlySelf: get("/self", s.string, () => "self", { acl: Acl.allowCallers(Callers.self) }),
    events: sse("/events", async function* () {
      yield "tick"
    }, { acl: Acl.allowCallers(Callers.self) }),
  }
}

export class PrivateEndpoint extends Endpoint {
  static readonly prefix = "/private"
  static readonly acl = Acl.authenticated
  static readonly routes = {
    root: get("/", s.string, () => "private"),
  }
}

// ── answerer: an autonomous agent, whose tool acts through the client ──

const Answer = s.record("Answer", { answer: s.string, sources: s.list(s.string) })
type Answer = Infer<typeof Answer>
const ANSWER = taskType("answer", "Answer a question, citing what you looked up", {
  result: Answer,
  rules: [
    taskRule<Answer>("cites-sources", (a) => (a.sources.length > 0 ? accepted() : rejected("sources must not be empty"))),
    // Throws the first time it sees "flaky-once": a rule that fails once, then decides.
    taskRule<Answer>("steady", (a) => {
      if (a.answer === "flaky-once" && !flakySeen.has(a.answer)) {
        flakySeen.add(a.answer)
        throw new Error("the rule threw")
      }
      return accepted()
    }),
  ],
})
const flakySeen = new Set<string>()

export class ConformanceAnswerer extends AutonomousAgent {
  static readonly componentId = "answerer"
  static readonly description = "Answers questions"
  static readonly tools = {
    lookup: tool("lookup", "Looks up how many things were recorded under an id.", LookupArguments, async (a: ConformanceAnswerer, input) => {
      if (!input.id) throw new Error("an id is needed")
      const entity = a.client.of(Conformance, input.id)
      await entity.call(Conformance.handlers.record).invoke("looked-up")
      return `count for ${input.id} is ${await entity.call(Conformance.handlers.count).invoke()}`
    }),
  }
  static readonly guardrails = { noSecrets: guardrail("no-secrets", (stage, text) => (text.includes("sk-") ? `${stage} rejected by no-secrets` : null)) }
  static readonly accepts = [taskAcceptance(ANSWER, { maxIterations: 4 })]
}

/** Autonomous agents: tasks run, read and cancelled; instances driven and watched. */
export class AutonomousEndpoint extends Endpoint {
  static readonly prefix = "/autonomous"
  static readonly acl = Acl.allowAll
  static readonly routes = {
    run: post("/tasks/{taskType}", s.string, s.string, async (ep: AutonomousEndpoint, req, instructions) => {
      if (req.params.taskType !== ANSWER.name) throw new HttpProblem(400, `no task type '${req.params.taskType}'`)
      const taskId = await ep.client.forAutonomousAgent(ConformanceAnswerer).runSingleTask(ANSWER, instructions)
      const task = await ep.client.forTask(taskId).get()
      return JSON.stringify({ taskId, instanceId: task.assignee?.instanceId ?? "" })
    }),
    read: get("/tasks/{id}", s.string, async (ep: AutonomousEndpoint, req) => JSON.stringify((await ep.client.forTask(req.params.id).get()).record)),
    cancel: post("/tasks/{id}/cancel", Done, async (ep: AutonomousEndpoint, req) => {
      await ep.client.forTask(req.params.id).cancel()
      return done
    }),
    create: post("/tasks/{taskType}/create", s.string, s.string, async (ep: AutonomousEndpoint, req, body) => {
      if (req.params.taskType !== ANSWER.name) throw new HttpProblem(400, `no task type '${req.params.taskType}'`)
      const request = JSON.parse(body) as { instructions?: string; dependsOn?: string[] }
      const taskId = await ep.client.tasks.create(ANSWER, request.instructions ?? "", { dependsOn: request.dependsOn ?? [] })
      return JSON.stringify({ taskId })
    }),
    assign: post("/instances/{instance}/assign", s.string, s.string, async (ep: AutonomousEndpoint, req, body) => {
      const answer = await ep.client.forAutonomousAgent(ConformanceAnswerer, req.params.instance).assign(...(JSON.parse(body) as string[]))
      return JSON.stringify({ accepted: answer.accepted })
    }),
    operate: post("/instances/{instance}/{op}", Done, async (ep: AutonomousEndpoint, req) => {
      const calls = ep.client.forAutonomousAgent(ConformanceAnswerer, req.params.instance)
      if (req.params.op === "suspend") await calls.suspend()
      else if (req.params.op === "resume") await calls.resume()
      else if (req.params.op === "terminate") await calls.terminate()
      else throw new HttpProblem(404, `no operation '${req.params.op}'`)
      return done
    }),
    notifications: sse("/instances/{instance}/notifications", async function* (ep: AutonomousEndpoint, req) {
      for await (const n of ep.client.forAutonomousAgent(ConformanceAnswerer, req.params.instance).notifications()) yield JSON.stringify(n)
    }),
    state: get("/instances/{instance}/state", s.string, async (ep: AutonomousEndpoint, req) => {
      const st = await ep.client.forAutonomousAgent(ConformanceAnswerer, req.params.instance).state()
      return JSON.stringify({ phase: st.phase, queued: st.queued, currentTask: st.currentTask })
    }),
  }
}

/** The cart sample plus the conformance extras: what `npm run conformance` serves. */
export function referenceService() {
  return Ankka.service()
    .register(ShoppingCartEntity)
    .register(CartRows)
    .register(CheckoutWorkflow)
    .register(Conformance)
    .register(Profile)
    .register(CheckoutRecorder)
    .register(CheckoutFanout)
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
}
