import { after, before, describe, test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { mkdtemp, mkdir, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { createAppServer } from "../server/server.ts";

/** Listens on a free loopback port and answers its address. */
async function listen(server: http.Server): Promise<string> {
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  return `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
}

async function close(server: http.Server): Promise<void> {
  server.closeAllConnections();
  await new Promise<void>((resolve) => server.close(() => resolve()));
}

describe("the server's call to the cart", () => {
  // A stand-in for the calling address: it answers a cart's total as the cart would.
  const calling = http.createServer((req, res) => {
    const total = req.url === "/cart/carts/c1/total";
    res.writeHead(total ? 200 : 404, { "Content-Type": "text/plain" });
    res.end(total ? "3" : "not found");
  });
  let callingUrl = "";
  let app: http.Server;
  let appUrl = "";

  before(async () => {
    callingUrl = await listen(calling);
    app = createAppServer({ servicesUrl: callingUrl });
    appUrl = await listen(app);
  });
  after(async () => {
    await close(app);
    await close(calling);
  });

  test("/summary is the cart's total, read at the calling address", async () => {
    const answer = await fetch(`${appUrl}/summary?cart=c1`);
    assert.equal(answer.status, 200);
    assert.deepEqual(await answer.json(), { status: 200, body: "3" });
  });

  test("/summary says the cart did not answer when nothing is there", async () => {
    const nowhere = createAppServer({ servicesUrl: "http://127.0.0.1:1" });
    const url = await listen(nowhere);
    try {
      const answer = await fetch(`${url}/summary?cart=c1`);
      assert.equal(answer.status, 502);
      assert.match((await answer.json()).error, /the cart did not answer/);
    } finally {
      await close(nowhere);
    }
  });
});

describe("the built app", () => {
  let app: http.Server;
  let url = "";

  before(async () => {
    const dist = await mkdtemp(path.join(tmpdir(), "dist-"));
    await mkdir(path.join(dist, "assets"));
    await writeFile(path.join(dist, "index.html"), "<p>the app</p>");
    await writeFile(path.join(dist, "assets", "app.js"), "console.log(1)");
    app = createAppServer({ servicesUrl: "http://127.0.0.1:1", dist });
    url = await listen(app);
  });
  after(async () => close(app));

  test("an app's own route is the index page, never cached", async () => {
    const answer = await fetch(`${url}/carts/c1`);
    assert.equal(answer.status, 200);
    assert.equal(await answer.text(), "<p>the app</p>");
    assert.equal(answer.headers.get("cache-control"), "no-cache");
  });

  test("a built asset is served and may be cached for good", async () => {
    const answer = await fetch(`${url}/assets/app.js`);
    assert.equal(answer.status, 200);
    assert.equal(answer.headers.get("content-type"), "text/javascript");
    assert.match(answer.headers.get("cache-control") ?? "", /immutable/);
  });

  test("a file that is not there is 404, not the index page", async () => {
    const answer = await fetch(`${url}/assets/missing.js`);
    assert.equal(answer.status, 404);
  });
});
