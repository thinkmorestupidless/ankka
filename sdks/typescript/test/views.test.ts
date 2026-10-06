// A view's declared queries: what discovery carries, what asking one sends, and the sidecar it needs.
import { test, describe, before, after } from "node:test"
import assert from "node:assert/strict"
import { createServer, type Http2Server, type ServerHttp2Session } from "node:http2"
import type { AddressInfo } from "node:net"
import { create } from "@bufbuild/protobuf"
import { connectNodeAdapter } from "@connectrpc/connect-node"
import { Client, QueryReplySchema, type QueryRequest } from "../src/_proto/ankka/protocol/v1/client_pb.ts"
import { ComponentClient } from "../src/client.ts"
import { Ankka, RegistrationError } from "../src/service.ts"
import { View, declaredQuery, tableOf, type DeclaredQuery } from "../src/view.ts"
import { jsonCodec } from "../src/codec.ts"
import { s, type Infer } from "../src/schema.ts"
import { refusal } from "../src/server/discovery.ts"
import { handleView } from "../src/server/stateless.ts"
import { ViewRequestSchema } from "../src/_proto/ankka/protocol/v1/view_pb.ts"
import { EventSourcedEntity } from "../src/eventSourcedEntity.ts"
import { KeyedView, on } from "../src/keyedView.ts"
import { KeyedViewTestKit } from "../src/testkit/kinds.ts"
import { Counter } from "./fixtures/counter.ts"

const Row = s.record("Row", { key: s.string, under: s.option(s.string) })
type Row = Infer<typeof Row>
const codec = jsonCodec(Row, "row")

const under = `WITH RECURSIVE below AS (
  SELECT row_key, payload FROM ${tableOf("nodes")} WHERE payload::jsonb->>'under' = :row
  UNION
  SELECT n.row_key, n.payload FROM ${tableOf("nodes")} n JOIN below b ON n.payload::jsonb->>'under' = b.row_key
)
SELECT payload FROM below ORDER BY row_key`

function nodes(declared: readonly DeclaredQuery[] | undefined) {
  return class Nodes extends View<Row, Row> {
    static readonly componentId = "nodes"
    static readonly source = Counter
    static readonly events = codec
    static readonly row = codec
    static readonly declared = declared
    onChange(row: Row) {
      return this.effects.updateRow(row)
    }
  }
}

function specOf(cls: unknown) {
  return Ankka.service().register(cls as never).spec()
}

test("a view's table is named as the platform names it", () => {
  assert.equal(tableOf("cart-rows"), "ankka_view_cart_rows")
  assert.equal(tableOf("a.b-c_d"), "ankka_view_a_b_c_d")
})

test("discovery carries a view's declared queries as written", () => {
  const detail = specOf(nodes([declaredQuery("under", under), declaredQuery("all-of-kind", "SELECT 1")])).components[0].detail
  assert.equal(detail.case, "view")
  assert.deepEqual(
    detail.value.declaredQueries.map((q) => [q.name, q.statement]),
    [
      ["under", under],
      ["all-of-kind", "SELECT 1"],
    ],
  )
  const none = specOf(nodes(undefined)).components[0].detail
  assert.equal(none.case === "view" && none.value.declaredQueries.length, 0)
})

test("a query declared twice, or declared without a statement, is refused at registration", () => {
  const problemsOf = (declared: readonly DeclaredQuery[]): readonly string[] => {
    try {
      specOf(nodes(declared))
    } catch (e) {
      assert.ok(e instanceof RegistrationError, String(e))
      return e.problems
    }
    return []
  }
  assert.match(problemsOf([declaredQuery("under", under), declaredQuery("under", under)])[0], /'under' twice/)
  assert.match(problemsOf([declaredQuery("under", "  ")])[0], /declaredQuery\(name, statement\)/)
})

test("a sidecar too old for declared queries is refused, naming the view and both versions", () => {
  const spec = specOf(nodes([declaredQuery("under", under)]))
  const why = refusal(spec, "1.7")
  assert.ok(why !== undefined)
  assert.match(why, /nodes/)
  assert.match(why, /1\.7/)
  assert.match(why, /1\.11/)
  assert.equal(refusal(spec, "1.13"), undefined)
  assert.equal(refusal(specOf(nodes(undefined)), "1.7"), undefined)
})

describe("asking a declared query", () => {
  let server: Http2Server
  let client: ComponentClient
  const sessions = new Set<ServerHttp2Session>()
  const asked: QueryRequest[] = []

  before(async () => {
    server = createServer(
      connectNodeAdapter({
        routes: (router) =>
          router.service(Client, {
            async query(req) {
              asked.push(req)
              const rows = '[{"key":"b","under":"a"},{"key":"c","under":"b"}]'
              return create(QueryReplySchema, { result: { case: "rows", value: { contentType: "application/json", manifest: "rows", data: new TextEncoder().encode(rows) } } })
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

  test("ask sends the query's name, its values and a limit, and decodes the rows", async () => {
    const rows = await client.views.ask("nodes", "under", { row: "a" }, Row, 5)
    assert.deepEqual(rows, [
      { key: "b", under: "a" },
      { key: "c", under: "b" },
    ])
    const request = asked.at(-1)!
    assert.equal(request.viewId, "nodes")
    assert.equal(request.name, "under")
    assert.deepEqual({ ...request.values }, { row: "a" })
    assert.equal(request.limit, 5)
  })

  test("a limit not given is not sent, so the sidecar's default applies", async () => {
    await client.views.ask("nodes", "under", { row: "a" }, Row)
    assert.equal(asked.at(-1)!.limit, undefined)
  })
})

// ── Keyed views ─────────────────────────────────────────────────────────────

const Noted = s.record("Noted", { text: s.string })
type Noted = Infer<typeof Noted>
const notedCodec = jsonCodec(Noted, "noted")

class Left extends EventSourcedEntity<number, Noted> {
  static readonly componentId = "joined-left"
  emptyState() {
    return 0
  }
  applyEvent(state: number) {
    return state + 1
  }
}
class Right extends EventSourcedEntity<number, Noted> {
  static readonly componentId = "joined-right"
  emptyState() {
    return 0
  }
  applyEvent(state: number) {
    return state + 1
  }
}

const JoinedRow = s.record("JoinedRow", { key: s.string, holding: s.string, notes: s.list(s.string) })
type JoinedRow = Infer<typeof JoinedRow>
const ofRight = `SELECT payload FROM ${tableOf("joined-rows")} WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key`

class Joined extends KeyedView<JoinedRow> {
  static readonly componentId = "joined-rows"
  static readonly row = JoinedRow
  static readonly declared = [declaredQuery("of-right", ofRight)]
  static readonly sources = [
    on(Left, notedCodec, (v: Joined, e) => v.onLeft(e)),
    on(Right, notedCodec, (v: Joined) => v.onRight(), { deleted: (v: Joined) => v.effects.deleteRow(`gone-${v.subject}`) }),
  ]
  async onLeft(event: Noted) {
    const [key, holding] = event.text.split("|")
    const held = (await this.rows.get(key))?.notes ?? []
    return this.effects.updateRow(key, { key, holding, notes: [...held, "left"] })
  }
  async onRight() {
    const theirs = await this.rows.ask("of-right", { holding: this.subject })
    return this.effects.updateRows(theirs.map((r) => [r.key, { ...r, notes: [...r.notes, "right"] }] as const))
  }
}

test("discovery carries a keyed view's sources, and no single source", () => {
  const component = specOf(Joined).components[0]
  assert.equal(component.id, "joined-rows")
  assert.equal(component.detail.case, "view")
  const detail = component.detail.value as { source?: unknown; sources: { source: { case: string; value: { id: string } } }[]; declaredQueries: unknown[] }
  assert.equal(detail.source, undefined)
  assert.deepEqual(detail.sources.map((x) => x.source.value.id), ["joined-left", "joined-right"])
  assert.equal(detail.declaredQueries.length, 1)
})

test("a keyed view with no source, or reading one entity twice, is refused at registration", () => {
  const problemsOf = (cls: unknown): readonly string[] => {
    try {
      specOf(cls)
    } catch (e) {
      assert.ok(e instanceof RegistrationError, String(e))
      return e.problems
    }
    return []
  }
  class None extends KeyedView<JoinedRow> {
    static readonly componentId = "none"
    static readonly row = JoinedRow
    static readonly sources = []
  }
  class Twice extends KeyedView<JoinedRow> {
    static readonly componentId = "twice"
    static readonly row = JoinedRow
    static readonly sources = [on(Left, notedCodec, () => undefined as never), on(Left, notedCodec, () => undefined as never)]
  }
  assert.match(problemsOf(None)[0], /declares no source/)
  assert.match(problemsOf(Twice)[0], /"joined-left" twice/)
})

test("a sidecar too old for keyed views is refused, naming the view and both versions", () => {
  const spec = specOf(Joined)
  const why = refusal(spec, "1.7")
  assert.ok(why !== undefined)
  assert.match(why, /joined-rows/)
  assert.equal(refusal(spec, "1.13"), undefined)
})

test("a change sent with its source goes to that source's handler and is answered with rows", async () => {
  const registry = Ankka.service().register(Joined).validate()
  const ctx = { registry, client: new ComponentClient("127.0.0.1:1"), log: () => {} }
  const left = create(ViewRequestSchema, {
    componentId: "joined-rows",
    sourceId: "joined-right",
    deleted: true,
    metadata: { entries: [{ key: "ce-subject", value: "r1" }] },
  })
  const deleted = await handleView(left, ctx)
  assert.equal(deleted.effect.case, "rows")
  const changes = deleted.effect.case === "rows" ? deleted.effect.value.changes : []
  assert.deepEqual(changes.map((c) => [c.key, c.change.case]), [["gone-r1", "delete"]])
})

test("the keyed test kit applies effects as the runtime does, and refuses an unanswered query", async () => {
  const kit = KeyedViewTestKit.of(Joined)
  await kit.change(Left, "l1", { text: "s1|r1" })
  await kit.change(Left, "l2", { text: "s2|r1" })
  await kit.change(Left, "l3", { text: "s3|r2" })
  await assert.rejects(() => kit.change(Right, "r1", { text: "" }), /"of-right"/)
  kit.answering("of-right", (values) => [...kit.rows.values()].filter((r) => r.holding === values.holding))
  await kit.change(Right, "r1", { text: "" })
  assert.deepEqual(kit.get("s1")?.notes, ["left", "right"])
  assert.deepEqual(kit.get("s2")?.notes, ["left", "right"])
  assert.deepEqual(kit.get("s3")?.notes, ["left"])
  await kit.deleted(Right, "r1")
  assert.equal(kit.get("gone-r1"), null)
})
