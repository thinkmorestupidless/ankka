// The secret store as a TypeScript service sees it: who is given one, the rules, and a runtime too old
// to have one. The store itself runs in the sidecar and is held by the conformance suite's `secret.*`.
import { test, describe } from "node:test"
import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { join } from "node:path"
import { Code, ConnectError } from "@connectrpc/connect"
import { ComponentClient, InMemorySecrets, Secrets, secretNameProblem, secretValueProblem } from "../src/client.ts"
import { CommandError } from "../src/effects/common.ts"
import { Consumer } from "../src/consumer.ts"
import { EventSourcedEntity } from "../src/eventSourcedEntity.ts"
import { KeyValueEntity } from "../src/keyValueEntity.ts"
import { View } from "../src/view.ts"
import { TimedAction } from "../src/timedAction.ts"
import { Endpoint } from "../src/endpoint.ts"
import { Workflow } from "../src/workflow.ts"

interface Row {
  unit: string
  repeat: number
  accepted: boolean
  why: string
}
const rules = JSON.parse(readFileSync(join(import.meta.dirname, "..", "proto", "fixtures", "secrets", "rules.json"), "utf8")) as { names: Row[]; values: Row[] }

describe("the rules", () => {
  test("every name in the fixture gets its verdict", () => {
    assert.ok(rules.names.length > 0)
    for (const row of rules.names) assert.equal(secretNameProblem(row.unit.repeat(row.repeat)) === undefined, row.accepted, row.why)
  })

  test("every value in the fixture gets its verdict", () => {
    assert.ok(rules.values.length > 0)
    for (const row of rules.values) assert.equal(secretValueProblem(row.unit.repeat(row.repeat)) === undefined, row.accepted, row.why)
  })

  test("the unit-test double refuses what the runtime refuses", async () => {
    const store = new InMemorySecrets()
    await store.put("provider/acme", "sk-1")
    assert.equal(await store.get("provider/acme"), "sk-1")
    assert.equal(await store.get("never"), undefined)
    await assert.rejects(store.put("provider acme", "sk-1"), (e: unknown) => e instanceof CommandError && e.code === "BAD_REQUEST" && e.message.includes("'.', '_', '-' or '/'"))
    await assert.rejects(store.put("empty", ""), CommandError)
  })
})

describe("who is given a store", () => {
  test("an entity or a view is given no secret store", () => {
    for (const cls of [EventSourcedEntity, KeyValueEntity, View]) assert.equal("secrets" in cls.prototype, false, cls.name)
    // And by type: none of these compiles, which `npm run typecheck` holds.
    const neverCalled = (es: EventSourcedEntity<any, any>, kv: KeyValueEntity<any>, view: View<any, any>) => {
      // @ts-expect-error an event sourced entity has no secret store
      void es.secrets
      // @ts-expect-error a key value entity has no secret store
      void kv.secrets
      // @ts-expect-error a view has no secret store
      void view.secrets
    }
    void neverCalled
  })

  test("a consumer, a timed action, an endpoint and a workflow are given one", () => {
    for (const cls of [Consumer, TimedAction, Endpoint, Workflow]) assert.equal("secrets" in cls.prototype, true, cls.name)
  })

  test("a workflow reads a service secret in a step and not in a command", () => {
    class Charge extends Workflow<string> {
      static readonly componentId = "charge-secrets-test"
      emptyState(): string {
        return ""
      }
    }
    const workflow = new Charge()
    workflow.secrets = new InMemorySecrets()
    assert.throws(() => workflow.secrets, (e: unknown) => e instanceof CommandError && e.code === "BAD_REQUEST" && e.message.includes("in a step"))
    workflow._enterStep()
    assert.ok(workflow.secrets instanceof InMemorySecrets)
  })
})

describe("a runtime without the store", () => {
  test("is reported as too old, not as absent", async () => {
    const refuse = () => Promise.reject(new ConnectError("Method not found", Code.Unimplemented))
    const stub = new Proxy({}, { get: () => refuse })
    const client = new ComponentClient("old-runtime", { address: "old-runtime", transport: undefined, stub: stub as never })
    const store = new Secrets(client)
    for (const call of [() => store.get("acme"), () => store.put("acme", "sk-1"), () => store.delete("acme")]) {
      await assert.rejects(call(), (e: unknown) => e instanceof CommandError && e.message.includes("1.4"))
    }
  })
})
