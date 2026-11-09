// The cart's MCP endpoint: what it declares, how its handler tool runs, the keys its own route issues,
// and, with ANKKA_SLOW=1 and Docker, the protocol's requests through the real sidecar.
import { test, describe } from "node:test"
import assert from "node:assert/strict"
import { Ankka, InMemoryClientKeys, type ClientKeys, type ComponentClient } from "ankka"
import { AnkkaTestKit, EndpointTestKit } from "ankka/testkit"
import { CartKeysEndpoint, CartMcp, type IssuedKey } from "./cartMcp.ts"
import { ShoppingCartEntity } from "./entity.ts"
import { service } from "./main.ts"

const slow = process.env.ANKKA_SLOW ? false : "set ANKKA_SLOW=1 to run the tests that need Docker"

describe("the cart's MCP endpoint, without a sidecar", () => {
  test("declares a reference, a tool of its own, and the rule that admits client keys", () => {
    const spec = Ankka.service().register(ShoppingCartEntity).register(CartMcp).spec()
    const endpoint = spec.mcpEndpoints[0]!
    assert.equal(endpoint.path, "/mcp")
    assert.equal(endpoint.acl?.clientKeys, true)
    assert.equal(endpoint.acl?.tokens, false)
    assert.deepEqual(endpoint.tools.map((t) => t.name), ["get_cart", "add_item"])
    assert.deepEqual(JSON.parse(endpoint.tools[0]!.inputSchemaJson).required, ["cartId"])
    assert.deepEqual(JSON.parse(endpoint.tools[1]!.inputSchemaJson).required, ["cartId", "productId", "quantity"])
  })

  test("a reference to a handler the entity does not have fails registration, naming the tool", () => {
    class Wrong extends CartMcp {
      static override readonly tools = CartMcp.tools.query("get_total", "Reads a total", ShoppingCartEntity, "grand-total", { id: "cartId" })
    }
    assert.throws(() => Ankka.service().register(ShoppingCartEntity).register(Wrong).validate(), /get_total|grand-total/)
  })

  test("the handler tool adds the item through the client", async () => {
    const asked: unknown[] = []
    const client = { of: (_c: unknown, id: string) => ({ call: () => ({ invoke: async (input: unknown) => asked.push([id, input]) }) }) }
    const addItem = CartMcp.tools.declared.find((t) => t.name === "add_item")!
    assert.equal(addItem.kind, "tool")
    if (addItem.kind !== "tool") return
    await addItem.run({ cartId: "c1", productId: "p1", quantity: 2 }, { client } as unknown as CartMcp)
    assert.deepEqual(asked, [["c1", { productId: "p1", name: "p1", quantity: 2 }]])
  })

  test("the keys route issues a key, shown once, and revokes it by id", async () => {
    const keys = new InMemoryClientKeys()
    class Keyed extends CartKeysEndpoint {
      override get clientKeys(): ClientKeys {
        return keys
      }
    }
    const kit = EndpointTestKit.of(Keyed, undefined as unknown as ComponentClient)
    const issued = (await kit.post("/mcp-keys/", { subject: "acme", scope: ["get_cart"], label: "Alice's assistant" })).json() as IssuedKey
    assert.equal(keys.keyOf(issued.id), issued.clientKey)
    assert.equal((await keys.list())[0]?.subject, "acme")
    assert.equal((await kit.delete(`/mcp-keys/${issued.id}`)).text(), "revoked")
    assert.ok((await keys.list())[0]?.revokedAt)
  })
})

describe("the cart's MCP endpoint through the real sidecar", { skip: slow }, () => {
  // docs:start mcp-request
  test("an assistant lists and calls the tools with a key the cart issued", async () => {
    const kit = await AnkkaTestKit.start(service())
    try {
      const issued = (await kit.http.post("/mcp-keys/", { subject: "acme", scope: [], label: null })).json() as IssuedKey
      /** One JSON-RPC request to the cart's MCP endpoint, as any MCP client sends it. */
      const mcp = async (method: string, params: unknown) => {
        const request = { jsonrpc: "2.0", id: 1, method, params }
        const r = await kit.http.post("/mcp", request, { Authorization: `Bearer ${issued.clientKey}` })
        assert.equal(r.status, 200, r.text())
        return r.json() as { result: { tools?: { name: string }[]; isError?: boolean; content?: { text: string }[] } }
      }
      const listed = await mcp("tools/list", {})
      assert.deepEqual(listed.result.tools?.map((t) => t.name), ["get_cart", "add_item"])
      const added = await mcp("tools/call", { name: "add_item", arguments: { cartId: "m1", productId: "p1", quantity: 2 } })
      assert.notEqual(added.result.isError, true, JSON.stringify(added))
      const read = await mcp("tools/call", { name: "get_cart", arguments: { cartId: "m1" } })
      assert.match(read.result.content?.[0]?.text ?? "", /"quantity":2/)
    } finally {
      await kit.stop()
    }
  })
  // docs:end mcp-request
})
