// The documentation's client samples (docs/build/component-client.md, docs/build/streaming.md), against
// a fake sidecar: a component addressed by kind and instance, then by handler, and an agent's reply
// read token by token. The fake keeps the items a cart is given and streams a reply in two tokens.
import { test, describe, before, after } from "node:test"
import assert from "node:assert/strict"
import { createServer, type Http2Server, type ServerHttp2Session } from "node:http2"
import type { AddressInfo } from "node:net"
import { create } from "@bufbuild/protobuf"
import { connectNodeAdapter } from "@connectrpc/connect-node"
import { Client, InvokeReplySchema, StreamTokenSchema } from "../src/_proto/ankka/protocol/v1/client_pb.ts"
import { ComponentClient } from "../src/client.ts"
import { ShoppingCartEntity } from "../examples/shopping-cart/entity.ts"
import { CartAssistant } from "../examples/shopping-cart/assistant.ts"

const utf8 = new TextEncoder()

describe("the client samples", () => {
  let server: Http2Server
  let client: ComponentClient
  const sessions = new Set<ServerHttp2Session>()
  const items: unknown[] = []

  before(async () => {
    server = createServer(
      connectNodeAdapter({
        routes: (router) =>
          router.service(Client, {
            async invoke(req) {
              if (req.name === "add-item") {
                items.push(JSON.parse(new TextDecoder().decode(req.payload?.data)))
                return create(InvokeReplySchema, { result: { case: "reply", value: { payload: { contentType: "application/octet-stream", manifest: "done", data: new Uint8Array() } } } })
              }
              const cart = { cartId: req.entityId, items, checkedOut: false }
              return create(InvokeReplySchema, { result: { case: "reply", value: { payload: { contentType: "application/json", manifest: "ShoppingCart", data: utf8.encode(JSON.stringify(cart)) } } } })
            },
            async *invokeStream() {
              yield create(StreamTokenSchema, { token: { case: "text", value: "Rain " } })
              yield create(StreamTokenSchema, { token: { case: "text", value: "later" } })
              yield create(StreamTokenSchema, { token: { case: "completed", value: {} } })
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

  test("a component is addressed by kind and instance, then by handler", async () => {
    // docs:start addressing
    const cart = client.of(ShoppingCartEntity, "cart-1")

    await cart.call(ShoppingCartEntity.handlers.addItem).invoke({ productId: "p1", name: "Widget", quantity: 2 })
    const state = await cart.call(ShoppingCartEntity.handlers.getCart).invoke() // ShoppingCart
    // docs:end addressing
    assert.deepEqual(state, { cartId: "cart-1", items: [{ productId: "p1", name: "Widget", quantity: 2 }], checkedOut: false })
  })

  test("a streamed reply is read token by token", async () => {
    const tokens: string[] = []
    // docs:start consume
    for await (const token of client.of(CartAssistant, "s-1").call(CartAssistant.handlers.chat).stream("Will it rain?")) {
      tokens.push(token)
    }
    // docs:end consume
    assert.deepEqual(tokens, ["Rain ", "later"])
  })
})
