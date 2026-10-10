// A workflow as the source of a view or a consumer (protocol 1.15): the standing it is handed, and a
// sidecar too old to know one refused at discovery.
import { test } from "node:test"
import assert from "node:assert/strict"
import { Ankka } from "../src/service.ts"
import { Consumer } from "../src/consumer.ts"
import { View } from "../src/view.ts"
import { jsonCodec } from "../src/codec.ts"
import { s, type Infer } from "../src/schema.ts"
import { refusal } from "../src/server/discovery.ts"
import { isTerminal, isUnknown, standingFromProto, type Standing } from "../src/standing.ts"
import { ConsumerTestKit, ViewTestKit } from "../src/testkit/kinds.ts"
import { create } from "@bufbuild/protobuf"
import { Kind } from "../src/_proto/ankka/protocol/v1/discovery_pb.ts"
import { WorkflowStandingSchema } from "../src/_proto/ankka/protocol/v1/payload_pb.ts"
import { CheckoutWorkflow, type Checkout } from "../examples/shopping-cart/checkoutWorkflow.ts"
import { Counter } from "./fixtures/counter.ts"

const Row = s.record("Row", { status: s.string, standing: s.string })
type Row = Infer<typeof Row>

class Rows extends View<Checkout, Row> {
  static readonly componentId = "checkout-standings"
  static readonly source = CheckoutWorkflow
  static readonly events = CheckoutWorkflow.state
  static readonly row = jsonCodec(Row, "row")
  onChange(state: Checkout) {
    return this.effects.updateRow({ status: state.status, standing: this.standing?.status ?? "none" })
  }
}

const seen: (Standing | undefined)[] = []

class Ends extends Consumer<Checkout> {
  static readonly componentId = "checkout-ends-test"
  static readonly source = CheckoutWorkflow
  static readonly message = CheckoutWorkflow.state
  onMessage(_state: Checkout) {
    seen.push(this.standing)
    return this.effects.ignore()
  }
}

const checkout: Checkout = { cartId: "c1", status: "charged", reserved: 0, mode: "ok" }

test("a view of a workflow is handed the standing", async () => {
  const kit = ViewTestKit.of(Rows)
  await kit.onChange("c1", checkout, {}, { status: "Completed", retries: {} })
  assert.deepEqual(kit.rows.get("c1"), { status: "charged", standing: "Completed" })
})

test("a change handed no standing has none", async () => {
  const kit = ViewTestKit.of(Rows)
  await kit.onChange("c1", checkout)
  assert.deepEqual(kit.rows.get("c1"), { status: "charged", standing: "none" })
})

test("a consumer of a workflow is handed the standing", async () => {
  seen.length = 0
  const failed: Standing = { status: "Failed", retries: {}, failure: "payment declined" }
  await ConsumerTestKit.of(Ends).onMessage(checkout, "c1", {}, failed)
  assert.deepEqual(seen, [failed])
  assert.ok(isTerminal(failed) && !isUnknown(failed))
})

test("a standing reads from the wire as it was written", () => {
  const standing = standingFromProto(create(WorkflowStandingSchema, { status: "Paused", step: "charge", retries: { reserve: 2 } }))
  assert.deepEqual(standing, { status: "Paused", step: "charge", retries: { reserve: 2 } })
  assert.equal(standingFromProto(undefined), undefined)
  assert.ok(isUnknown(standingFromProto(create(WorkflowStandingSchema, { status: "Unknown" }))!))
})

test("a view of a workflow declares the workflow as its source", () => {
  const spec = Ankka.service().register(CheckoutWorkflow).register(Rows).spec()
  const view = spec.components.find((c) => c.id === "checkout-standings")!
  assert.equal(view.detail.case, "view")
  const source = view.detail.case === "view" ? view.detail.value.source?.source : undefined
  assert.equal(source?.case, "component")
  assert.equal(source?.case === "component" ? source.value.kind : undefined, Kind.WORKFLOW)
})

test("a sidecar too old for workflow sources is refused, naming the components", () => {
  const spec = Ankka.service().register(CheckoutWorkflow).register(Rows).register(Ends).spec()
  const said = refusal(spec, "1.14") ?? ""
  assert.match(said, /checkout-standings/)
  assert.match(said, /checkout-ends-test/)
  assert.match(said, /1\.14/)
  assert.match(said, /1\.15 or later/)
  assert.equal(refusal(spec, "1.15"), undefined)
})

test("a service reading no workflow is served by a 1.14 sidecar", () => {
  assert.equal(refusal(Ankka.service().register(Counter).spec(), "1.14"), undefined)
})
