// The journal is shared: the TypeScript cart writes, the Scala cart (its image beside, on the same
// Postgres) reads the same state, and the reverse. Needs Docker, the `ankka-sidecar` image and the
// `sample-shopping-cart` image — both built by `sbt sidecar/docker:publishLocal shoppingCart/docker:publishLocal`.
//
// The tags: `ankka-sidecar:latest` and `sample-shopping-cart:latest` are what `publishLocal` produces
// on a laptop; CI sets ANKKA_SIDECAR_IMAGE and ANKKA_SAMPLE_IMAGE to the tags the same sbt session made,
// so a stale image of the right name cannot pass the test for it.
import { test, describe } from "node:test"
import assert from "node:assert/strict"
import { AnkkaTestKit } from "../src/testkit/integration.ts"
import { service } from "../examples/shopping-cart/main.ts"

const slow = process.env.ANKKA_SLOW ? false : "set ANKKA_SLOW=1 to run the tests that need Docker"
const SAMPLE_IMAGE = process.env.ANKKA_SAMPLE_IMAGE ?? "sample-shopping-cart:latest"

const pen = { productId: "p1", name: "Pen", quantity: 2 }
const ink = { productId: "p2", name: "Ink", quantity: 1 }

describe("journal portability", { skip: slow }, () => {
  test("a journal written by the TypeScript cart is recovered by the Scala cart, and the reverse", async () => {
    const kit = await AnkkaTestKit.start(service())
    try {
      // TypeScript writes.
      for (const item of [pen, ink, { ...pen, quantity: 3 }]) assert.equal((await kit.http.post("/carts/port1/items", item)).status, 204)
      const written = (await kit.http.get("/carts/port1")).json()

      // Scala reads the same rows.
      const scala = await kit.startBeside(SAMPLE_IMAGE)
      try {
        const read = (await scala.http.get("/carts/port1")).json()
        assert.deepEqual(read, written)
        assert.deepEqual(read, { cartId: "port1", items: [{ ...pen, quantity: 5 }, ink], checkedOut: false })

        // Scala writes; TypeScript reads.
        for (const item of [ink, pen]) assert.equal((await scala.http.post("/carts/port2/items", item)).status, 204)
        const scalaWrote = (await scala.http.get("/carts/port2")).json()
        const tsRead = (await kit.http.get("/carts/port2")).json()
        assert.deepEqual(tsRead, scalaWrote)
        assert.deepEqual(tsRead, { cartId: "port2", items: [pen, ink], checkedOut: false })
      } finally {
        await scala.stop()
      }
    } finally {
      await kit.stop()
    }
  })
})
