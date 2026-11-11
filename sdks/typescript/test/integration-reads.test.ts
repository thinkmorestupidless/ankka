// The integration kit makes both reads against the running service (protocol 1.24): the TypeScript rows of
// "the test kit reads a workflow's standing from the running service" (features/polyglot/standing.feature)
// and "the test kit reads how a copy stands from the running service" (features/replication/status.feature).
// The cart sample, with the conformance reference's replicated vehicle beside it; the sidecar runs as the
// installation `kit` beside one, `elsewhere`, it never reaches, so the vehicle has a home. Needs Docker and
// the `ankka-sidecar` image (ANKKA_SIDECAR_IMAGE names this branch's).
import { test, describe } from "node:test"
import assert from "node:assert/strict"
import { AnkkaTestKit } from "../src/testkit/integration.ts"
import { CommandError, ErrorCode } from "../src/effects/common.ts"
import { service } from "../examples/shopping-cart/main.ts"
import { CheckoutWorkflow } from "../examples/shopping-cart/checkoutWorkflow.ts"
import { ShoppingCartEntity } from "../examples/shopping-cart/entity.ts"
import { ReplicatedVehicle } from "../examples/shopping-cart/conformance.ts"

const slow = process.env.ANKKA_SLOW ? false : "set ANKKA_SLOW=1 to run the tests that need Docker"

const installations = { ANKKA_INSTALLATION: "kit", ANKKA_INSTALLATION_ELSEWHERE_DB_HOST: "elsewhere.invalid" }
const pen = { productId: "p1", name: "Pen", quantity: 2 }

describe("the integration kit's reads", { skip: slow }, () => {
  test("the test kit reads a workflow's standing and how a copy stands from the running service", async () => {
    const kit = await AnkkaTestKit.start(service().register(ReplicatedVehicle), { env: installations })
    try {
      // A workflow that ended, one that failed, and one never started.
      assert.equal((await kit.http.post("/carts/ir1/items", pen)).status, 204)
      assert.equal((await kit.http.post("/carts/ir1/checkouts/wait", "ok")).status, 200)
      assert.equal((await kit.client.of(CheckoutWorkflow, "ir1").lifecycle()).status, "completed")
      assert.equal((await kit.http.post("/carts/ir2/items", pen)).status, 204)
      assert.equal((await kit.http.post("/carts/ir2/checkouts/wait", "doomed")).status, 424)
      const failed = await kit.client.of(CheckoutWorkflow, "ir2").lifecycle()
      assert.equal(failed.status, "failed")
      assert.ok(failed.failure)
      assert.equal(failed.retries.charge, 1)
      assert.equal((await kit.client.of(CheckoutWorkflow, "ir3").lifecycle()).status, "not-started")

      // A copy of the replicated vehicle: homed here, and its position counts its event and the platform's
      // records of its home and its replication set.
      await kit.client.of(ReplicatedVehicle, "ir4").call(ReplicatedVehicle.handlers.addStep).invoke("frame")
      assert.deepEqual(await kit.client.of(ReplicatedVehicle, "ir4").replication(), { home: "kit", replicationSet: ["kit"], position: 3n })

      // An entity that is not replicated is refused, as a Scala handler is.
      await assert.rejects(kit.client.of(ShoppingCartEntity, "ir1").replication(), (e: unknown) => e instanceof CommandError && e.code === ErrorCode.NotFound)
    } finally {
      await kit.stop()
    }
  })
})
