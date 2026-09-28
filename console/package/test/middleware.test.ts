import { describe, test } from "node:test";
import assert from "node:assert/strict";
import { crossSite } from "../src/middleware.ts";
import { safeReturnTo } from "../src/context.ts";
import { createRuntime } from "../src/runtime.ts";

const origin = "https://console.example.com";
const req = (method: string, headers: Record<string, string> = {}) =>
  new Request("https://console.example.com/organizations/acme", { method, headers });

describe("crossSite", () => {
  test("a read is never refused", () => {
    assert.equal(crossSite(req("GET", { "sec-fetch-site": "cross-site", origin: "https://evil.example" }), origin), false);
  });

  test("a POST from the console's own origin passes; from another site it does not", () => {
    assert.equal(crossSite(req("POST", { origin, "sec-fetch-site": "same-origin" }), origin), false);
    assert.equal(crossSite(req("POST", { origin: "https://evil.example" }), origin), true);
    assert.equal(crossSite(req("POST", { "sec-fetch-site": "cross-site" }), origin), true);
    assert.equal(crossSite(req("POST", { origin: "null" }), origin), true);
  });

  test("a POST with no Origin at all, as some clients send, is left to SameSite", () => {
    assert.equal(crossSite(req("POST"), origin), false);
  });
});

describe("safeReturnTo", () => {
  const runtime = createRuntime({
    controlPlane: { url: "http://cp" },
    auth: { clientId: "c", clientSecret: "s", issuer: "http://issuer" },
    publicOrigin: origin,
    mount: "/console",
    sessionSecret: "x",
    log: () => undefined,
  });

  test("keeps a path under the mount, with its query", () => {
    assert.equal(safeReturnTo(runtime, "/console/projects/p?x=1"), "/console/projects/p?x=1");
  });

  test("anything else lands on the front page", () => {
    for (const bad of ["https://evil.example/", "//evil.example/console/", "/elsewhere", "/console\\..\\x", "", null]) {
      assert.equal(safeReturnTo(runtime, bad), "/console/", String(bad));
    }
  });
});
