// The reference service's extras: what the conformance suite drives beside the cart. Every wire name
// and route here is named in `specs/009-polyglot-runtimes/contracts/conformance.md`; the Scala reference
// (`sidecar/src/test/.../ConformanceReference.scala`) and the Python one have the same.
import {
  socket,
  type Socket,
  Acl,
  Agent,
  AutonomousAgent,
  Callers,
  CommandError,
  ServiceCallFailed,
  ServiceIdentityMismatch,
  ServiceUnanswered,
  ServiceUnresolvable,
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
  StartFrom,
  stream,
  tool,
  View,
  mcpServer,
  resultGuardrail,
  type AgentOutcome,
  KeyedView,
  on,
  declaredQuery,
  tableOf,
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

// docs:start fanout
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
// docs:end fanout

// ── topic-rows and topic-relay: a view and a consumer over a topic ──

// docs:start topic-sources
/** The latest message about each subject. Declares no start, so it reads from the earliest. */
export class TopicRows extends View<Infer<typeof Fanned>, Infer<typeof Fanned>> {
  static readonly componentId = "topic-rows"
  static readonly topic = "conformance-topic"
  static readonly version = 2
  static readonly events = jsonCodec(Fanned, "fanned")
  static readonly row = jsonCodec(Fanned, "fanned")

  onChange(message: Infer<typeof Fanned>) {
    return this.effects.updateRow(message)
  }
}

/** Republishes what it reads, from the latest: none of what the topic held when it started. */
export class TopicRelay extends Consumer<Infer<typeof Fanned>, Infer<typeof Fanned>> {
  static readonly componentId = "topic-relay"
  static readonly topic = "conformance-topic"
  static readonly startFrom = StartFrom.latest
  static readonly message = jsonCodec(Fanned, "fanned")
  static readonly out = jsonCodec(Fanned, "fanned")
  static readonly producesTo = "conformance-topic-relayed"

  onMessage(message: Infer<typeof Fanned>) {
    return this.effects.produce(message)
  }
}
// docs:end topic-sources

// ── tree-node and tree-rows: a tree, walked by a declared recursive query ──

export const TreePlaced = s.record("TreePlaced", { under: s.option(s.string) })
export const TreeRow = s.record("TreeRow", { key: s.string, under: s.option(s.string) })
type TreePlaced = Infer<typeof TreePlaced>
type TreeRow = Infer<typeof TreeRow>

/** A node of a tree, placed under another node or under none: the parent's id, empty for none. */
export class TreeNode extends EventSourcedEntity<TreePlaced, TreePlaced> {
  static readonly componentId = "tree-node"
  static readonly state = jsonCodec(TreePlaced, "tree-node")
  static readonly events = jsonCodec(TreePlaced, "tree-event")

  static readonly handlers = {
    place: command("place", s.string, s.string, (n: TreeNode, under) => n.effects.persist({ under: under === "" ? null : under }).thenReply(() => "placed")),
  }

  emptyState(): TreePlaced {
    return { under: null }
  }

  applyEvent(_state: TreePlaced, event: TreePlaced): TreePlaced {
    return event
  }
}

// docs:start declared-query
/** One row per node, and every row under a row, to any depth, by key: the same statement in every language. */
export class TreeRows extends View<TreePlaced, TreeRow> {
  static readonly componentId = "tree-rows"
  static readonly source = TreeNode
  static readonly events = jsonCodec(TreePlaced, "tree-event")
  static readonly row = jsonCodec(TreeRow, "tree-row")
  static readonly declared = [
    declaredQuery(
      "under",
      `WITH RECURSIVE below AS (
  SELECT row_key, payload FROM ${tableOf("tree-rows")} WHERE payload::jsonb->>'under' = :row
  UNION
  SELECT n.row_key, n.payload FROM ${tableOf("tree-rows")} n JOIN below b ON n.payload::jsonb->>'under' = b.row_key
)
SELECT payload FROM below ORDER BY row_key`,
    ),
  ]

  onChange(event: TreePlaced) {
    return this.effects.updateRow({ key: this.subject, under: event.under })
  }
}
// docs:end declared-query

/** Places nodes of a tree and asks what is under one. */
export class TreeEndpoint extends Endpoint {
  static readonly prefix = "/tree"
  static readonly acl = Acl.allowAll

  static readonly routes = {
    root: post("/{nodeId}", s.string, (ep: TreeEndpoint, req) => ep.client.of(TreeNode, req.params.nodeId).call(TreeNode.handlers.place).invoke("")),
    under: post("/{nodeId}/under/{parentId}", s.string, (ep: TreeEndpoint, req) =>
      ep.client.of(TreeNode, req.params.nodeId).call(TreeNode.handlers.place).invoke(req.params.parentId),
    ),
    below: get("/{nodeId}/below", s.list(s.string), async (ep: TreeEndpoint, req) =>
      (await ep.client.views.ask(TreeRows.componentId, "under", { row: req.params.nodeId }, TreeRow)).map((row) => row.key),
    ),
  }
}

// ── joined-left, joined-right, joined-rows: a keyed view of two sources ──

export const Noted = s.record("Noted", { text: s.string })
type Noted = Infer<typeof Noted>
export const JoinedRow = s.record("JoinedRow", { key: s.string, holding: s.string, notes: s.list(s.string) })
type JoinedRow = Infer<typeof JoinedRow>

/** One side of the keyed view: an entity whose command records a line of text. */
export class JoinedLeft extends EventSourcedEntity<number, Noted> {
  static readonly componentId = "joined-left"
  static readonly state = jsonCodec(s.int, "joining")
  static readonly events = jsonCodec(Noted, "noted")
  static readonly handlers = {
    record: command("record", s.string, s.string, (e: JoinedLeft, text) => e.effects.persist({ text }).thenReply(() => "recorded")),
  }
  emptyState(): number {
    return 0
  }
  applyEvent(state: number): number {
    return state + 1
  }
}

/** The other side. */
export class JoinedRight extends EventSourcedEntity<number, Noted> {
  static readonly componentId = "joined-right"
  static readonly state = jsonCodec(s.int, "joining")
  static readonly events = jsonCodec(Noted, "noted")
  static readonly handlers = {
    record: command("record", s.string, s.string, (e: JoinedRight, text) => e.effects.persist({ text }).thenReply(() => "recorded")),
  }
  emptyState(): number {
    return 0
  }
  applyEvent(state: number): number {
    return state + 1
  }
}

/**
 * The left names a row `key|holding` and writes it from what it held, noting itself; the right finds
 * every row holding it by asking the view's own query, and notes itself on each.
 */
export class JoinedRows extends KeyedView<JoinedRow> {
  static readonly componentId = "joined-rows"
  static readonly row = jsonCodec(JoinedRow, "joined-row")
  // docs:start keyed-view
  static readonly sources = [on(JoinedLeft, Noted, (v: JoinedRows, e) => v.onLeft(e)), on(JoinedRight, Noted, (v: JoinedRows) => v.onRight())]
  static readonly declared = [
    declaredQuery("of-right", `SELECT payload FROM ${tableOf("joined-rows")} WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key`),
  ]

  async onLeft(event: Noted) {
    const [key, holding] = event.text.split("|")
    const held = (await this.rows.get(key))?.notes ?? []
    return this.effects.updateRow(key, { key, holding, notes: [...held, "left"] })
  }

  async onRight() {
    const theirs = await this.rows.ask("of-right", { holding: this.subject })
    return this.effects.updateRows(theirs.map((row) => [row.key, { ...row, notes: [...row.notes, "right"] }] as const))
  }
  // docs:end keyed-view
}

/** Records on either side of the keyed view, and reads its rows. */
export class JoinedEndpoint extends Endpoint {
  static readonly prefix = "/joined"
  static readonly acl = Acl.allowAll

  static readonly routes = {
    // The left entity is the row's own key: one left per row.
    left: post("/left/{key}/{holding}", s.string, (ep: JoinedEndpoint, req) =>
      ep.client.of(JoinedLeft, req.params.key).call(JoinedLeft.handlers.record).invoke(`${req.params.key}|${req.params.holding}`),
    ),
    right: post("/right/{rightId}", s.string, (ep: JoinedEndpoint, req) => ep.client.of(JoinedRight, req.params.rightId).call(JoinedRight.handlers.record).invoke("")),
    row: get("/rows/{key}", JoinedRow, async (ep: JoinedEndpoint, req) => {
      const found = await ep.client.views.get(JoinedRows.componentId, req.params.key, JoinedRow)
      if (found === null) throw new HttpProblem(404, `no row '${req.params.key}'`)
      return found
    }),
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
    // Records the due time it was run for, as the runtime told it.
    tick: action("tick", s.string, async (r: Reminder, id) => {
      await r.client.of(Conformance, id).call(Conformance.handlers.record).invoke(`due:${r.dueTime?.getTime()}`)
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

// ── approver: an agent whose tools wait for a person, with two MCP servers ──

const RefundArguments = s.record("RefundArguments", { id: s.string })
const PathArguments = s.record("PathArguments", { path: s.string })

// docs:start approver
export class Approver extends Agent {
  static readonly componentId = "approver"
  static readonly tools = {
    refund: tool(
      "refund",
      "Refunds what was recorded under an id. A person approves every refund.",
      RefundArguments,
      async (a: Approver, input) => {
        await a.client.of(Conformance, input.id).call(Conformance.handlers.record).invoke("refunded")
        return `refunded ${input.id}`
      },
      { approval: true },
    ),
    // A tool calls another service as this service: the called service's ACL can admit it by name.
    askScripted: tool("ask_scripted", "Asks the scripted service for what is at a path.", PathArguments, (a: Approver, input) =>
      a.services.service("scripted").getText(input.path),
    ),
  }
  // Both found at ANKKA_MCP_<NAME>_URL; every tool of `guarded` waits for a person.
  static readonly mcpServers = { tickets: mcpServer("tickets"), guarded: mcpServer("guarded", { approval: true }) }
  static readonly resultGuardrails = {
    noInstructions: resultGuardrail("no-instructions", (_tool, text) =>
      /ignore what you were told/i.test(text) ? "the result tries to instruct whoever reads it" : null,
    ),
  }
  static readonly handlers = {
    ask: command("ask", s.string, s.string, (a: Approver, question) =>
      a.effects.systemMessage("You approve refunds.").userMessage(question).tools("refund", "ask_scripted").thenReply(),
    ),
  }
}
// docs:end approver

/** As every reference renders it: `{"answered": text}` or `{"awaiting": [{"id", "tool", "arguments"}]}`. */
function renderOutcome(outcome: AgentOutcome<string>): string {
  return outcome.kind === "answered"
    ? JSON.stringify({ answered: outcome.value })
    : JSON.stringify({ awaiting: outcome.requests.map((r) => ({ id: r.id, tool: r.tool, arguments: r.arguments })) })
}

/** `{"approved": bool, "by": name, "note": text}`, as the suite sends it. */
function decisionOf(body: string): { approved: boolean; by: string; note?: string } {
  const d = JSON.parse(body) as { approved?: boolean; by?: string; note?: string }
  return { approved: d.approved === true, by: d.by ?? "", ...(d.note ? { note: d.note } : {}) }
}

// ── Endpoints ──

const Echo = s.record("Echo", { a: s.list(s.string), b: s.option(s.string), headers: s.stringMap(s.string) })

/** What a call to another service came to, as the reference answers it in every language. */
const ServiceCallRecord = s.record("service-call-record", {
  outcome: s.string,
  status: s.int,
  contentType: s.string,
  body: s.string,
  answer: s.string,
  message: s.string,
})
type ServiceCallRecordValue = Infer<typeof ServiceCallRecord>
/** What the socket handlers noticed, for a case to read once a socket has closed. */
const socketLog: string[] = []

const callerWord = (c: { kind: string; project?: string; name?: string }): string =>
  c.kind === "service" ? `service:${c.project}/${c.name}` : c.kind

export class ConformanceEndpoint extends Endpoint {
  static readonly prefix = "/conformance"
  static readonly acl = Acl.allowAll

  static readonly routes = {
    problems: get("/problems", s.list(s.string), () => [...sidecarProblems]),
    // The secret store. The name is a query parameter because it may hold a slash.
    // docs:start secrets
    keepSecret: post("/secrets", s.string, Done, async (ep: ConformanceEndpoint, req, value) => {
      await ep.secrets.put(req.query.get("name") ?? "", value)
      return done
    }),
    readSecret: get("/secrets", s.string, async (ep: ConformanceEndpoint, req) => {
      const name = req.query.get("name") ?? ""
      const value = await ep.secrets.get(name)
      if (value === undefined) throw new HttpProblem(404, `no secret '${name}'`)
      return value
    }),
    removeSecret: del("/secrets", Done, async (ep: ConformanceEndpoint, req) => {
      await ep.secrets.delete(req.query.get("name") ?? "")
      return done
    }),
    // docs:end secrets
    // docs:start service-call
    // A call to another service, as the case asks for it: the body and every `X-Conformance-*` header
    // sent on, and two headers no handler may send added, to show they never arrive.
    serviceCall: post("/service-call", s.string, ServiceCallRecord, async (ep: ConformanceEndpoint, req, body) => {
      const query = (name: string) => req.query.get(name) ?? ""
      const headers: (readonly [string, string])[] = [
        ...req.headers.entries().filter(([name]) => name.toLowerCase().startsWith("x-conformance-")),
        ["X-Ankka-Caller", "ankka://elsewhere/impostor"],
        ["Host", "elsewhere"],
      ]
      const client = ep.services.service(query("service"))
      const record = (outcome: string, fields: Partial<ServiceCallRecordValue> = {}): ServiceCallRecordValue => ({
        outcome, status: 0, contentType: "", body: "", answer: "", message: "", ...fields,
      })
      try {
        if (query("mode") === "typed") return record("response", { status: 200, body: await client.getText(query("path"), { headers }) })
        const answer = await client.request(query("method"), query("path"), {
          body: body ? new TextEncoder().encode(body) : undefined,
          contentType: body ? (req.headers.get("content-type") ?? undefined) : undefined,
          headers,
        })
        const header = answer.headers.find(([name]) => name.toLowerCase() === "x-answer")
        return record("response", { status: answer.status, contentType: answer.contentType, body: answer.text, answer: header?.[1] ?? "" })
      } catch (e) {
        if (e instanceof ServiceCallFailed) return record("failed", { status: e.status, body: e.body })
        if (e instanceof ServiceUnresolvable) return record("unresolvable", { message: e.message })
        if (e instanceof ServiceIdentityMismatch) return record("mismatch", { message: e.message })
        if (e instanceof ServiceUnanswered) return record("unanswered", { message: e.message })
        if (e instanceof CommandError) return record("refused", { message: e.message })
        throw e
      }
    }),
    // docs:end service-call
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
    // Sockets (protocol 1.9). Echoes each frame; "context" is answered with the room and the opening
    // request's `tag`, read after any number of frames.
    socketRoom: socket("/socket/{room}", async (_ep: ConformanceEndpoint, req, socket: Socket) => {
      for await (const text of socket) await socket.send(text === "context" ? `${req.params.room} ${req.query.get("tag") ?? ""}` : text)
      socketLog.push(`closed:${req.params.room}`)
    }),
    socketOnce: socket("/socket-once", async (_ep: ConformanceEndpoint, _req, socket: Socket) => {
      await socket.receive()
    }),
    socketFail: socket("/socket-fail", async (_ep: ConformanceEndpoint, _req, socket: Socket) => {
      await socket.receive()
      throw new Error("the socket handler broke")
    }),
    socketLog: get("/socket-log", s.list(s.string), () => [...socketLog]),
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
    // A recurring timer: due at once, then every second.
    recur: post("/recur/{id}", Done, async (ep: ConformanceEndpoint, req) => {
      await ep.client.timers.scheduleRecurring(`recur-${req.params.id}`, Duration.ZERO, Duration.ofSeconds(1), { component: Reminder, handler: Reminder.actions.tick }, req.params.id)
      return done
    }),
    // The same timer set again, with a delay a replacement would be first due after.
    recurAgain: post("/recur/{id}/again", Done, async (ep: ConformanceEndpoint, req) => {
      await ep.client.timers.scheduleRecurring(`recur-${req.params.id}`, Duration.ofSeconds(60), Duration.ofSeconds(1), { component: Reminder, handler: Reminder.actions.tick }, req.params.id)
      return done
    }),
    recurCancel: post("/recur/{id}/cancel", Done, async (ep: ConformanceEndpoint, req) => {
      await ep.client.timers.cancel(`recur-${req.params.id}`)
      return done
    }),
    // A period of zero: the SDK's CommandError(BAD_REQUEST) becomes a 400 carrying its message.
    recurRefused: post("/recur-refused/{id}", Done, async (ep: ConformanceEndpoint, req) => {
      await ep.client.timers.scheduleRecurring(`recur-${req.params.id}`, Duration.ZERO, Duration.ZERO, { component: Reminder, handler: Reminder.actions.tick }, req.params.id)
      return done
    }),
    ask: post("/ask/{session}", s.string, s.string, (ep: ConformanceEndpoint, req, question) => ep.client.of(ConformanceAssistant, req.params.session).call(ConformanceAssistant.handlers.ask).invoke(question)),
    // docs:start approver-routes
    // A turn that may wait: the model's answer, or the approval requests it waits on.
    askApprover: post("/approver/{session}", s.string, s.string, async (ep: ConformanceEndpoint, req, question) =>
      renderOutcome(await ep.client.of(Approver, req.params.session).call(Approver.handlers.ask).ask(question)),
    ),
    // A person's decision, answered as the turn's caller would have been once it goes on.
    decideApproval: post("/approver/{session}/decide/{id}", s.string, s.string, async (ep: ConformanceEndpoint, req, body) =>
      renderOutcome(await ep.client.of(Approver, req.params.session).call(Approver.handlers.ask).decide(req.params.id, decisionOf(body))),
    ),
    // docs:end approver-routes
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
    me: get("/me", s.string, (_ep: PrivateEndpoint, req) => {
      const p = req.principal
      if (!p) throw new Error("an authenticated route is handed its principal")
      return JSON.stringify({ subject: p.subject, roles: [...p.roles].sort(), tier: p.claims.tier ?? null, issuer: p.issuer })
    }),
    socket: socket("/socket", async (_ep: PrivateEndpoint, req, socket: Socket) => {
      const p = req.principal
      if (!p) throw new Error("an authenticated route is handed its principal")
      socketLog.push(`private:${p.subject}`)
      await socket.send(JSON.stringify({ subject: p.subject, roles: [...p.roles].sort(), caller: callerWord(req.caller) }))
      for await (const _ of socket) {
        // nothing: the socket stays open until the client closes it
      }
    }),
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
    // Waits for a person before it runs; what it records is counted as a run.
    sensitiveLookup: tool(
      "sensitive_lookup",
      "Looks up what was recorded under an id. A person approves every one.",
      LookupArguments,
      async (a: ConformanceAnswerer, input) => {
        const entity = a.client.of(Conformance, input.id)
        await entity.call(Conformance.handlers.record).invoke("sensitive")
        return `sensitive count for ${input.id} is ${await entity.call(Conformance.handlers.count).invoke()}`
      },
      { approval: true },
    ),
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
    decide: post("/instances/{instance}/decide/{id}", s.string, Done, async (ep: AutonomousEndpoint, req, body) => {
      await ep.client.forAutonomousAgent(ConformanceAnswerer, req.params.instance).decide(req.params.id, decisionOf(body))
      return done
    }),
    notifications: sse("/instances/{instance}/notifications", async function* (ep: AutonomousEndpoint, req) {
      for await (const n of ep.client.forAutonomousAgent(ConformanceAnswerer, req.params.instance).notifications()) yield JSON.stringify(n)
    }),
    state: get("/instances/{instance}/state", s.string, async (ep: AutonomousEndpoint, req) => {
      const st = await ep.client.forAutonomousAgent(ConformanceAnswerer, req.params.instance).state()
      return JSON.stringify({ phase: st.phase, queued: st.queued, currentTask: st.currentTask, awaiting: st.awaiting.map((r) => ({ id: r.id, tool: r.tool })) })
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
    .register(TopicRows)
    .register(TopicRelay)
    .register(TreeNode)
    .register(TreeRows)
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
    .register(TreeEndpoint)
    .register(JoinedEndpoint)
    .register(ConformanceEndpoint)
    .register(PrivateEndpoint)
    .register(CallersEndpoint)
    .register(AutonomousEndpoint)
}
