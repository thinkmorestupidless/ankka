import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { addItem, readCart } from "../src/api.ts";

// A stand-in for the cart under its mount: it keeps what is added and answers a read with it.
const added: string[] = [];
const cart = http.createServer(async (req, res) => {
  if (req.method === "POST" && req.url === "/api/cart/carts/c1/items") {
    let body = "";
    for await (const chunk of req) body += chunk;
    added.push(body);
    res.writeHead(204).end();
  } else if (req.url === "/api/cart/carts/c1") {
    res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ items: added.map((b) => JSON.parse(b)) }));
  } else {
    res.writeHead(404).end("no such cart");
  }
});
let base = "";

before(async () => {
  await new Promise<void>((resolve) => cart.listen(0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${(cart.address() as AddressInfo).port}`;
});
after(async () => {
  cart.closeAllConnections();
  await new Promise<void>((resolve) => cart.close(() => resolve()));
});

test("an item added through the mount is in the cart read back", async () => {
  assert.equal((await addItem("c1", { productId: "tea", name: "Tea", quantity: 1 }, fetch, base)).ok, true);
  const shown = await readCart("c1", fetch, base);
  assert.equal(shown.ok, true);
  assert.deepEqual(JSON.parse(shown.text), { items: [{ productId: "tea", name: "Tea", quantity: 1 }] });
});

test("a cart the service does not know is shown with its refusal", async () => {
  const shown = await readCart("c2", fetch, base);
  assert.equal(shown.ok, false);
  assert.match(shown.text, /answered 404: no such cart/);
});

test("nothing answering is said so", async () => {
  assert.deepEqual(await readCart("c1", fetch, "http://127.0.0.1:1"), {
    ok: false,
    text: "http://127.0.0.1:1/api/cart/carts/c1 did not answer",
  });
});
