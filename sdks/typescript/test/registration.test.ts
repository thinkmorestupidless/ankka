// What the type checker refuses (`@ts-expect-error` lines fail `npm run typecheck` if the refusal ever
// stops) and what registration refuses at runtime for a caller who bypassed it — every problem at once.
import { test } from "node:test"
import assert from "node:assert/strict"
import { Ankka, RegistrationError } from "../src/service.ts"
import { EventSourcedEntity } from "../src/eventSourcedEntity.ts"
import { Endpoint } from "../src/endpoint.ts"
import { command, query } from "../src/handlers.ts"
import { Acl, get } from "../src/routes.ts"
import { jsonCodec } from "../src/codec.ts"
import { Done, done, s } from "../src/schema.ts"
import { noClient } from "../src/client.ts"
import { Counter } from "./fixtures/counter.ts"

const State = s.record("State", { n: s.int })
const Event = s.sumType("Event", { Bumped: {} })
type State = { n: number }
type Event = { type: "Bumped" }

class Base extends EventSourcedEntity<State, Event> {
  emptyState(): State {
    return { n: 0 }
  }
  applyEvent(state: State): State {
    return { n: state.n + 1 }
  }
}

test("a query that persists does not compile", () => {
  class BadQuery extends Base {
    static readonly componentId = "bad-query"
    static readonly state = jsonCodec(State)
    static readonly events = jsonCodec(Event)
    static readonly handlers = {
      // @ts-expect-error a query must return a read-only effect; persist(...) is not one
      bump: query("bump", s.int, (e: BadQuery) => e.effects.persist({ type: "Bumped" }).thenReply((st) => st.n)),
      ok: query("ok", s.int, (e: BadQuery) => e.effects.reply(e.state.n)),
      okCommand: command("bump-cmd", s.int, (e: BadQuery) => e.effects.persist({ type: "Bumped" }).thenReply((st) => st.n)),
    }
  }
  assert.ok(BadQuery)
})

test("a reply of the wrong type does not compile", () => {
  class Wrong extends Base {
    static readonly componentId = "wrong"
    static readonly state = jsonCodec(State)
    static readonly events = jsonCodec(Event)
    static readonly handlers = {
      // @ts-expect-error the handler replies a number where the declared reply is a string
      bump: command("bump", s.string, (e: Wrong) => e.effects.persist({ type: "Bumped" }).thenReply((st) => st.n)),
    }
  }
  assert.ok(Wrong)
})

test("a class missing a static is refused at the registration site, and at runtime with a message naming it", () => {
  class NoState extends Base {
    static readonly componentId = "no-state"
    static readonly events = jsonCodec(Event)
    static readonly handlers = { get: query("get", s.int, (e: NoState) => e.effects.reply(e.state.n)) }
  }
  // @ts-expect-error NoState has no static `state`
  Ankka.service({ client: noClient() }).register(NoState)

  assert.throws(
    () => Ankka.service({ client: noClient() }).register(NoState as never).validate(),
    (e: unknown) => e instanceof RegistrationError && e.problems.length === 1 && /NoState: needs a static state/.test(e.problems[0]!),
  )
})

test("an endpoint without an access rule is refused at compile time and at runtime", () => {
  class NoAcl extends Endpoint {
    static readonly prefix = "/things"
    static readonly routes = { get: get("/{id}", s.string, (_ep: NoAcl, req) => req.params.id) }
  }
  // @ts-expect-error NoAcl has no static `acl`
  Ankka.service({ client: noClient() }).register(NoAcl)

  assert.throws(
    () => Ankka.service({ client: noClient() }).register(NoAcl as never).validate(),
    (e: unknown) => e instanceof RegistrationError && /NoAcl: needs a static acl/.test(e.problems[0]!),
  )
})

test("every problem is reported at once", () => {
  class Dup1 extends Base {
    static readonly componentId = "counter" // clashes with Counter
    static readonly state = jsonCodec(State)
    static readonly events = jsonCodec(Event)
    static readonly handlers = {
      a: command("bump", Done, () => ({ kind: "read-only", retention: null, outcome: { kind: "reply", compute: () => done } }) as never),
      b: command("bump", Done, () => ({ kind: "read-only", retention: null, outcome: { kind: "reply", compute: () => done } }) as never),
    }
  }
  class Ep1 extends Endpoint {
    static readonly prefix = "/x"
    static readonly acl = Acl.allowAll
    static readonly routes = { a: get("/a", s.string, () => "a") }
  }
  class Ep2 extends Endpoint {
    static readonly prefix = "/x"
    static readonly acl = Acl.allowAll
    static readonly routes = { a: get("/a", s.string, () => "a"), b: get("/a", s.string, () => "b") }
  }
  class NotAComponent {}

  assert.throws(
    () =>
      Ankka.service({ client: noClient() })
        .register(Counter)
        .register(Dup1)
        .register(Ep1)
        .register(Ep2)
        .register(NotAComponent as never)
        .validate(),
    (e: unknown) => {
      assert.ok(e instanceof RegistrationError)
      const text = e.problems.join("\n")
      assert.match(text, /two handlers declare the wire name "bump"/)
      assert.match(text, /share the prefix \/x/)
      assert.match(text, /routes a and b both declare GET \/a/)
      assert.match(text, /NotAComponent is not a component class/)
      assert.equal(e.problems.length, 4, text)
      return true
    },
  )
})

test("a duplicate component id is a problem", () => {
  class Twin extends Base {
    static readonly componentId = "counter"
    static readonly state = jsonCodec(State)
    static readonly events = jsonCodec(Event)
    static readonly handlers = { get: query("get", s.int, (e: Twin) => e.effects.reply(e.state.n)) }
  }
  assert.throws(() => Ankka.service({ client: noClient() }).register(Counter).register(Twin).validate(), /two components declare the id "counter"/)
})

test("a declaration refuses a bad route template or a GET with a body", () => {
  assert.throws(() => get("no-leading-slash", s.string, () => ""), /not a route template/)
  assert.throws(() => get("/{a}/{a}", s.string, () => ""), /repeats a parameter/)
  assert.throws(() => get("/{a}", s.string, () => "", { params: { b: s.int } }), /params names b/)
  assert.throws(() => command("", Done, () => ({}) as never), /needs a wire name/)
})

test("a valid service validates and renders its spec", () => {
  const spec = Ankka.service({ client: noClient() }).register(Counter).spec()
  assert.equal(spec.components[0]?.id, "counter")
})
