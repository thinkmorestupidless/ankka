// An MCP endpoint as a TypeScript service declares it (protocol 1.20): what discovery carries, what
// registration refuses, how a tool runs and what it answers, the runtime too old to serve one, and
// client keys. The protocol itself is the platform's, held by the conformance suite's `mcp-endpoint.*`.
import { test, describe } from "node:test"
import assert from "node:assert/strict"
import { create } from "@bufbuild/protobuf"
import { Code, ConnectError } from "@connectrpc/connect"
import { McpToolRequestSchema } from "../src/_proto/ankka/protocol/v1/mcp_pb.ts"
import { SpecSchema } from "../src/_proto/ankka/protocol/v1/discovery_pb.ts"
import { ErrorCode as ProtoErrorCode } from "../src/_proto/ankka/protocol/v1/payload_pb.ts"
import {
  Acl,
  Ankka,
  ClientKeys,
  CommandError,
  ComponentClient,
  Done,
  Endpoint,
  EventSourcedEntity,
  Expiry,
  InMemoryClientKeys,
  McpEndpoint,
  PROTOCOL_VERSION,
  RegistrationError,
  command,
  createMcpDispatcher,
  done,
  get,
  post,
  query,
  s,
  tools,
  ToolTable,
  type Infer,
} from "../src/index.ts"
import { mcpRefusal } from "../src/server/discovery.ts"
import { encodePayload } from "../src/server/payloads.ts"
import { codecFor } from "../src/codec.ts"
import { Duration } from "../src/time.ts"

const Item = s.record("McpItem", { productId: s.string, quantity: s.int })
const Basket = s.record("McpBasket", { items: s.list(Item) })
type Basket = Infer<typeof Basket>

class Baskets extends EventSourcedEntity<Basket, Infer<typeof Item>> {
  static readonly componentId = "mcp-basket"
  static readonly state = Basket
  static readonly events = Item
  static readonly handlers = {
    add: command("add", Item, Done, (b: Baskets, item) => b.effects.persist(item).thenReply(() => done)),
    clear: command("clear", Done, (b: Baskets) => b.effects.reply(done)),
    get: query("get", Basket, (b: Baskets) => b.effects.reply(b.state)),
  }
  emptyState(): Basket {
    return { items: [] }
  }
  applyEvent(state: Basket, event: Infer<typeof Item>): Basket {
    return { items: [...state.items, event] }
  }
}

const Order = s.record("McpOrder", { day: s.string, count: s.int })

class Orders extends Endpoint {
  static readonly prefix = "/orders"
  static readonly acl = Acl.allowAll
  static readonly routes = {
    day: get("/{day}", Order, (_ep: Orders, req) => {
      if (req.params.day === "never") throw new CommandError({ message: "no such day", code: "NOT_FOUND" })
      return { day: req.params.day, count: req.principal ? 1 : 0 }
    }),
    place: post("/{day}", Order, Order, (_ep: Orders, req, body) => ({ day: req.params.day, count: body.count + 1 })),
  }
}

const Lookup = s.record("McpLookup", { basketId: s.string, flag: s.option(s.boolean) })

class BasketMcp extends McpEndpoint {
  static readonly path = "/mcp"
  static readonly acl = Acl.anyOf(Acl.clientKeys, Acl.authenticated)
  static readonly tools = tools<BasketMcp>()
    .tool("who", "Says who is calling", Lookup, s.record("McpWho", { basket: s.string, subject: s.option(s.string), person: s.option(s.string) }), (args, ep) => ({
      basket: args.basketId,
      subject: ep.request.principal?.subject ?? null,
      person: ep.request.principal?.claims.person ?? null,
    }))
    .tool("refuse", "Always refuses", Lookup, undefined, () => {
      throw new CommandError({ message: "quantity must be greater than zero", code: "BAD_REQUEST" })
    })
    .tool("say", "Answers text", Lookup, s.string, (args) => `basket ${args.basketId}`)
    .command("add", "Adds an item", Baskets, "add", { id: "basketId" })
    .command("clear", "Empties a basket", Baskets, Baskets.handlers.clear, { id: "basketId", acl: Acl.clientKeys })
    .query("read", "Reads a basket", Baskets, "get", { id: "basketId" })
    .route("orders", "The day's orders", Orders, "GET", "/orders/{day}")
    .route("place", "Places an order", Orders, "POST", "/orders/{day}")
}

function service() {
  return Ankka.service({ client: new ComponentClient("unused") }).register(Baskets).register(Orders).register(BasketMcp)
}

function problemsOf(build: () => unknown): string[] {
  try {
    build()
  } catch (e) {
    if (e instanceof RegistrationError) return [...e.problems]
    throw e
  }
  return []
}

describe("what discovery carries", () => {
  const spec = service().spec()
  const endpoint = spec.mcpEndpoints[0]!

  test("one MCP endpoint, at its path, with the rule's two halves", () => {
    assert.equal(PROTOCOL_VERSION, "1.21")
    assert.equal(spec.mcpEndpoints.length, 1)
    assert.equal(endpoint.path, "/mcp")
    assert.equal(endpoint.id, "BasketMcp")
    assert.equal(endpoint.acl?.clientKeys, true)
    assert.equal(endpoint.acl?.tokens, true)
    assert.equal(endpoint.acl?.allowAll, false)
    assert.equal(endpoint.brokered, false)
    assert.deepEqual(endpoint.tools.map((t) => t.name), ["who", "refuse", "say", "add", "clear", "read", "orders", "place"])
  })

  test("a tool's schema is its input's, with integers and what is required", () => {
    const schemaOf = (name: string) => JSON.parse(endpoint.tools.find((t) => t.name === name)!.inputSchemaJson)
    assert.deepEqual(schemaOf("who").required, ["basketId"])
    const add = schemaOf("add")
    assert.equal(add.type, "object")
    assert.equal(add.properties.quantity.type, "integer")
    assert.deepEqual(add.required, ["basketId", "productId", "quantity"])
    assert.deepEqual(schemaOf("clear").required, ["basketId"])
    assert.deepEqual(Object.keys(schemaOf("place").properties).sort(), ["body", "day"])
    assert.deepEqual(schemaOf("orders").required, ["day"])
  })

  test("a tool's own rule rides beside it, and the others have none", () => {
    assert.equal(endpoint.tools.find((t) => t.name === "clear")!.acl?.clientKeys, true)
    assert.equal(endpoint.tools.find((t) => t.name === "who")!.acl, undefined)
  })
})

describe("what registration refuses", () => {
  const minimal = (fill: (t: ToolTable<McpEndpoint>) => ToolTable<McpEndpoint>, statics: Record<string, unknown> = {}) => {
    const cls = class extends McpEndpoint {
      static readonly path = "/m"
      static readonly acl = Acl.clientKeys
      static readonly tools = fill(tools())
    }
    Object.defineProperty(cls, "name", { value: "Minimal" })
    for (const [k, v] of Object.entries(statics)) Object.defineProperty(cls, k, { value: v })
    return cls
  }
  const one = (t: ToolTable<McpEndpoint>) => t.tool("ok", "Fine", Lookup, undefined, () => done)

  test("a bad tool name, in the platform's words", () => {
    const problems = problemsOf(() => Ankka.service().register(minimal((t) => t.tool("Get Cart", "x", Lookup, undefined, () => done))).validate())
    assert.ok(problems.some((p) => p.includes("tool 'Get Cart' is not a tool name ([a-z0-9_-]+, at most 128 characters)")), problems.join("\n"))
  })

  test("no tool, a repeated tool and a blank description", () => {
    assert.ok(problemsOf(() => Ankka.service().register(minimal((t) => t)).validate()).some((p) => p.includes("declares no tool")))
    assert.ok(problemsOf(() => Ankka.service().register(minimal((t) => one(one(t)))).validate()).some((p) => p.includes("'ok' twice")))
    assert.ok(problemsOf(() => Ankka.service().register(minimal((t) => t.tool("x", " ", Lookup, undefined, () => done))).validate()).some((p) => p.includes("needs a description")))
  })

  test("a reference to what is not registered", () => {
    const problems = problemsOf(() =>
      Ankka.service()
        .register(minimal((t) => t.command("a", "A", Baskets, "add", { id: "basketId" }).route("b", "B", Orders, "GET", "/orders/{day}")))
        .validate(),
    )
    assert.ok(problems.some((p) => p.includes("mcp-basket, which is not registered")), problems.join("\n"))
    assert.ok(problems.some((p) => p.includes("Orders, which is not registered")), problems.join("\n"))
  })

  test("a handler or a route the target does not declare, and a command that is a query", () => {
    const problems = problemsOf(() =>
      Ankka.service()
        .register(Baskets)
        .register(Orders)
        .register(minimal((t) => t.command("a", "A", Baskets, "remove", { id: "basketId" }).command("b", "B", Baskets, "get", { id: "basketId" }).route("c", "C", Orders, "DELETE", "/orders/{day}")))
        .validate(),
    )
    assert.ok(problems.some((p) => p.includes("'remove'")), problems.join("\n"))
    assert.ok(problems.some((p) => p.includes("which is a query")), problems.join("\n"))
    assert.ok(problems.some((p) => p.includes("DELETE /orders/{day}")), problems.join("\n"))
  })

  test("brokered without tokens, two endpoints on one path, and a path a route has", () => {
    assert.ok(problemsOf(() => Ankka.service().register(minimal(one, { brokered: true })).validate()).some((p) => p.includes("Acl.authenticated")))
    const other = minimal(one)
    Object.defineProperty(other, "name", { value: "Other" })
    assert.ok(problemsOf(() => Ankka.service().register(minimal(one)).register(other).validate()).some((p) => p.includes("share the path /m")))
    assert.ok(problemsOf(() => Ankka.service().register(Orders).register(minimal(one, { path: "/orders/{day}" })).validate()).some((p) => p.includes("no parameters")))
    class Root extends Endpoint {
      static readonly prefix = "/m"
      static readonly acl = Acl.allowAll
      static readonly routes = { root: get("/", s.string, () => "root") }
    }
    assert.ok(problemsOf(() => Ankka.service().register(Root).register(minimal(one)).validate()).some((p) => p.includes("is also the route GET /m")))
  })

  test("an HTTP endpoint cannot admit client keys", () => {
    class Keyed extends Endpoint {
      static readonly prefix = "/k"
      static readonly acl = Acl.clientKeys
      static readonly routes = { root: get("/", s.string, () => "k") }
    }
    assert.ok(problemsOf(() => Ankka.service().register(Keyed).validate()).some((p) => p.includes("only an MCP endpoint can")))
  })
})

/** A client whose sidecar answers `invoke` from `answer`, recording what it was asked. */
function scriptedClient(answer: (req: { componentId: string; entityId: string; name: string; payload?: { data: Uint8Array } }) => unknown) {
  const asked: { componentId: string; entityId: string; name: string; input: string }[] = []
  const stub = {
    invoke: async (req: { componentId: string; entityId: string; name: string; payload?: { data: Uint8Array } }) => {
      asked.push({ componentId: req.componentId, entityId: req.entityId, name: req.name, input: new TextDecoder().decode(req.payload?.data ?? new Uint8Array()) })
      return answer(req)
    },
  }
  const client = new ComponentClient("scripted", { address: "scripted", transport: undefined, stub: stub as never })
  return { client, asked }
}

function dispatcherWith(client: ComponentClient) {
  const registry = Ankka.service({ client }).register(Baskets).register(Orders).register(BasketMcp).validate()
  return createMcpDispatcher({ registry, client, log: () => {} })
}

const call = (tool: string, args: unknown, extra: Record<string, unknown> = {}) =>
  create(McpToolRequestSchema, { endpointId: "BasketMcp", tool, argumentsJson: JSON.stringify(args), ...extra })

describe("how a tool runs", () => {
  test("a tool is told the principal and answers text and structured content", async () => {
    const { client } = scriptedClient(() => ({}))
    const reply = await dispatcherWith(client).invokeTool(
      call("who", { basketId: "b1" }, { principal: { subject: "acme", claims: { kind: "client-key", person: "alice" }, roles: [] } }),
    )
    assert.equal(reply.reply.case, "ok")
    const ok = reply.reply.value as { text: string; structuredJson?: string }
    assert.deepEqual(JSON.parse(ok.text), { basket: "b1", subject: "acme", person: "alice" })
    assert.equal(ok.structuredJson, ok.text)
  })

  test("text is text alone", async () => {
    const reply = await dispatcherWith(scriptedClient(() => ({})).client).invokeTool(call("say", { basketId: "b2" }))
    assert.deepEqual(reply.reply.value, { ...(reply.reply.value as object), text: "basket b2" })
    assert.equal((reply.reply.value as { structuredJson?: string }).structuredJson, undefined)
  })

  test("a refusal is a tool error with its message", async () => {
    const reply = await dispatcherWith(scriptedClient(() => ({})).client).invokeTool(call("refuse", { basketId: "b" }))
    assert.equal(reply.reply.case, "error")
    assert.equal(reply.reply.value, "quantity must be greater than zero")
  })

  test("an unknown tool is a failure naming it", async () => {
    const reply = await dispatcherWith(scriptedClient(() => ({})).client).invokeTool(call("refund", {}))
    assert.equal(reply.reply.case, "failure")
    assert.ok(JSON.stringify(reply.reply.value?.error?.message).includes("refund"))
  })

  test("a reference runs the handler, its input beside the id, encoded by the handler's own shape", async () => {
    const { client, asked } = scriptedClient(() => ({ result: { case: "reply", value: { payload: encodePayload(codecFor(Done), done) } } }))
    const reply = await dispatcherWith(client).invokeTool(call("add", { basketId: "b3", productId: "tea", quantity: 2 }))
    assert.equal(reply.reply.case, "ok", JSON.stringify(reply.reply.value))
    assert.deepEqual(asked.map((a) => [a.componentId, a.entityId, a.name]), [["mcp-basket", "b3", "add"]])
    assert.deepEqual(JSON.parse(asked[0]!.input), { productId: "tea", quantity: 2 })
  })

  test("a reference's refusal is a tool error", async () => {
    const { client } = scriptedClient(() => ({ result: { case: "error", value: { message: "quantity must be greater than zero, was 0", code: ProtoErrorCode.BAD_REQUEST } } }))
    const reply = await dispatcherWith(client).invokeTool(call("add", { basketId: "b4", productId: "tea", quantity: 0 }))
    assert.equal(reply.reply.case, "error")
    assert.equal(reply.reply.value, "quantity must be greater than zero, was 0")
  })

  test("a query reference answers the state as structured content", async () => {
    const { client } = scriptedClient(() => ({ result: { case: "reply", value: { payload: encodePayload(codecFor(Basket), { items: [{ productId: "tea", quantity: 1 }] }) } } }))
    const reply = await dispatcherWith(client).invokeTool(call("read", { basketId: "b5" }))
    assert.equal(reply.reply.case, "ok")
    assert.deepEqual(JSON.parse((reply.reply.value as { structuredJson: string }).structuredJson), { items: [{ productId: "tea", quantity: 1 }] })
  })

  test("a route reference runs the route in process, as the tool's caller", async () => {
    const dispatcher = dispatcherWith(scriptedClient(() => ({})).client)
    const got = await dispatcher.invokeTool(call("orders", { day: "monday" }, { principal: { subject: "ada", claims: {}, roles: [] } }))
    assert.deepEqual(JSON.parse((got.reply.value as { text: string }).text), { day: "monday", count: 1 })
    const placed = await dispatcher.invokeTool(call("place", { day: "tuesday", body: { day: "x", count: 4 } }))
    assert.deepEqual(JSON.parse((placed.reply.value as { text: string }).text), { day: "tuesday", count: 5 })
    const refused = await dispatcher.invokeTool(call("orders", { day: "never" }))
    assert.equal(refused.reply.case, "error")
    assert.equal(refused.reply.value, "no such day")
  })
})

describe("a runtime too old for an MCP endpoint", () => {
  const spec = service().spec()

  test("is refused at discovery, naming 1.20 and the path", () => {
    const why = mcpRefusal(spec, "1.19")
    assert.ok(why?.includes("1.20") && why.includes("/mcp"), why)
    assert.equal(mcpRefusal(spec, "1.20"), undefined)
    assert.equal(mcpRefusal(create(SpecSchema, {}), "1.19"), undefined)
  })
})

describe("client keys", () => {
  test("an endpoint, an MCP endpoint, a timed action and an agent are given them", async () => {
    const { TimedAction } = await import("../src/timedAction.ts")
    const { Agent } = await import("../src/agent.ts")
    for (const cls of [Endpoint, McpEndpoint, TimedAction, Agent]) assert.equal("clientKeys" in cls.prototype, true, cls.name)
    assert.equal("clientKeys" in EventSourcedEntity.prototype, false)
  })

  test("issue states the expiry and answers the key once", async () => {
    const asked: unknown[] = []
    const stub = {
      issueClientKey: async (req: unknown) => {
        asked.push(req)
        return { reply: { case: "issued", value: { id: "ck_1", clientKey: "ak_live_x" } } }
      },
    }
    const keys = new ClientKeys(new ComponentClient("k", { address: "k", transport: undefined, stub: stub as never }))
    assert.deepEqual(await keys.issue({ subject: "acme", expiry: Expiry.never, scope: ["get_cart"], attributes: { person: "alice" } }), { id: "ck_1", clientKey: "ak_live_x" })
    await keys.issue({ subject: "acme", expiry: Expiry.after(Duration.ofMillis(1000)) })
    const [first, second] = asked as { expiry: { case: string; value: unknown }; scope: string[]; attributes: Record<string, string> }[]
    assert.deepEqual(first!.expiry, { case: "never", value: true })
    assert.deepEqual(first!.scope, ["get_cart"])
    assert.deepEqual(first!.attributes, { person: "alice" })
    assert.deepEqual(second!.expiry, { case: "afterMillis", value: 1000n })
  })

  test("a refusal is a CommandError with the runtime's code", async () => {
    const stub = { revokeClientKey: async () => ({ error: { message: "no client key 'x'", code: ProtoErrorCode.NOT_FOUND, details: {} } }) }
    const keys = new ClientKeys(new ComponentClient("k", { address: "k", transport: undefined, stub: stub as never }))
    await assert.rejects(keys.revoke("x"), (e: unknown) => e instanceof CommandError && e.code === "NOT_FOUND")
  })

  test("a runtime without them is reported as too old, naming 1.20", async () => {
    const refuse = () => Promise.reject(new ConnectError("Method not found", Code.Unimplemented))
    const stub = new Proxy({}, { get: () => refuse })
    const keys = new ClientKeys(new ComponentClient("old", { address: "old", transport: undefined, stub: stub as never }))
    for (const attempt of [() => keys.issue({ subject: "a", expiry: Expiry.never }), () => keys.list(), () => keys.revoke("x")]) {
      await assert.rejects(attempt(), (e: unknown) => e instanceof CommandError && e.message.includes("1.20"))
    }
  })

  test("the unit-test double issues, lists and revokes", async () => {
    const keys = new InMemoryClientKeys()
    const issued = await keys.issue({ subject: "acme", expiry: Expiry.after({ days: 1 }), label: "Alice's assistant" })
    assert.equal(keys.keyOf(issued.id), issued.clientKey)
    const [listed] = await keys.list()
    assert.equal(listed?.subject, "acme")
    assert.ok(!JSON.stringify(listed).includes(issued.clientKey))
    await keys.revoke(issued.id)
    assert.ok((await keys.list())[0]?.revokedAt)
    await assert.rejects(keys.issue({ subject: " ", expiry: Expiry.never }), CommandError)
  })
})
