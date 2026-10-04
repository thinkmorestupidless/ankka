import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { read } from "../src/api.ts";

// A stand-in for the backend under its mount: it answers /api/ and refuses everything else.
const backend = http.createServer((req, res) => {
  res.writeHead(req.url === "/api/" ? 200 : 403, { "Content-Type": "text/plain" });
  res.end(req.url === "/api/" ? "hello" : "refused");
});
let base = "";

before(async () => {
  await new Promise<void>((resolve) => backend.listen(0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${(backend.address() as AddressInfo).port}`;
});
after(async () => {
  backend.closeAllConnections();
  await new Promise<void>((resolve) => backend.close(() => resolve()));
});

test("what answered is shown", async () => {
  assert.deepEqual(await read(`${base}/api/`), { ok: true, text: "hello" });
});

test("a refusal is shown with its status", async () => {
  const shown = await read(`${base}/other`);
  assert.equal(shown.ok, false);
  assert.match(shown.text, /answered 403: refused/);
});

test("nothing answering is said so", async () => {
  assert.deepEqual(await read("http://127.0.0.1:1/api/"), {
    ok: false,
    text: "http://127.0.0.1:1/api/ did not answer",
  });
});
