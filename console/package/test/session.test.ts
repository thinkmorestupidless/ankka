import { describe, test } from "node:test";
import assert from "node:assert/strict";
import { deriveKey, seal, unseal } from "../src/session/seal.ts";
import { SealedCookieSessionStore, SealedFlash } from "../src/session/cookie-store.ts";
import { TokenCache } from "../src/session/token-cache.ts";

const requestWith = (setCookie: string | null) => {
  const pair = setCookie?.split(";")[0] ?? "";
  return new Request("http://console/", { headers: pair ? { cookie: pair } : {} });
};

describe("seal", () => {
  test("round-trips, and refuses tampering, the wrong name and the wrong key", async () => {
    const key = await deriveKey("secret", "info");
    const other = await deriveKey("another", "info");
    const sealed = await seal({ a: 1 }, key, "cookie");
    assert.deepEqual(await unseal(sealed, key, "cookie"), { a: 1 });
    assert.equal(await unseal(sealed, key, "another-cookie"), null);
    assert.equal(await unseal(sealed, other, "cookie"), null);
    const flipped = Buffer.from(sealed, "base64url");
    flipped[20] ^= 1;
    assert.equal(await unseal(flipped.toString("base64url"), key, "cookie"), null);
    assert.equal(await unseal("not-base64-at-all!", key, "cookie"), null);
  });

  test("a realistic refresh token seals well under two kilobytes", async () => {
    const key = await deriveKey("secret", "info");
    const sealed = await seal({ v: 1, rt: "x".repeat(900), iat: 1 }, key, "__Host-ankka_console");
    assert.ok(sealed.length < 1_400, `sealed length ${sealed.length}`);
  });
});

describe("SealedCookieSessionStore", () => {
  test("writes a cookie script cannot read, sent same-site, and reads it back", async () => {
    const store = new SealedCookieSessionStore({ secret: "s", secure: true });
    const headers = new Headers();
    await store.write({ refreshToken: "rt", issuedAt: Math.floor(Date.now() / 1000) }, headers);
    const set = headers.get("set-cookie")!;
    assert.match(set, /^__Host-ankka_console=/);
    for (const attr of ["HttpOnly", "Secure", "SameSite=Lax", "Path=/"]) assert.ok(set.includes(attr), attr);
    assert.ok(!set.includes("rt;") && !set.includes("=rt"), "the refresh token must not appear in clear");
    const session = await store.read(requestWith(set));
    assert.equal(session?.refreshToken, "rt");
  });

  test("plain HTTP gets no prefix and no Secure", async () => {
    const store = new SealedCookieSessionStore({ secret: "s", secure: false });
    const headers = new Headers();
    await store.write({ refreshToken: "rt", issuedAt: Math.floor(Date.now() / 1000) }, headers);
    const set = headers.get("set-cookie")!;
    assert.match(set, /^ankka_console=/);
    assert.ok(!set.includes("Secure"));
  });

  test("a cookie older than the maximum, or sealed under another secret, is no session", async () => {
    const store = new SealedCookieSessionStore({ secret: "s", secure: false, maxAgeSeconds: 10 });
    const headers = new Headers();
    await store.write({ refreshToken: "rt", issuedAt: Math.floor(Date.now() / 1000) - 11 }, headers);
    assert.equal(await store.read(requestWith(headers.get("set-cookie"))), null);

    const fresh = new Headers();
    await store.write({ refreshToken: "rt", issuedAt: Math.floor(Date.now() / 1000) }, fresh);
    const rotated = new SealedCookieSessionStore({ secret: "rotated", secure: false });
    assert.equal(await rotated.read(requestWith(fresh.get("set-cookie"))), null);
  });

  test("clear expires the cookie", async () => {
    const store = new SealedCookieSessionStore({ secret: "s", secure: true });
    const headers = new Headers();
    await store.clear(headers);
    assert.match(headers.get("set-cookie")!, /Max-Age=0/);
  });
});

describe("SealedFlash", () => {
  test("is read once and cleared", async () => {
    const flash = new SealedFlash<{ secret: string }>({ secret: "s", secure: false, name: "flash", maxAgeSeconds: 60 });
    const headers = new Headers();
    await flash.write({ secret: "ankka_x" }, headers);
    const out = new Headers();
    assert.deepEqual(await flash.take(requestWith(headers.get("set-cookie")), out), { secret: "ankka_x" });
    assert.match(out.get("set-cookie")!, /Max-Age=0/);
    assert.equal(await flash.take(requestWith(null), new Headers()), null);
  });
});

describe("TokenCache", () => {
  test("expires entries by their skewed deadline", () => {
    const cache = new TokenCache({ skewSeconds: 30 });
    cache.put(["rt"], { accessToken: "a", refreshToken: "rt" }, 60, 0);
    assert.equal(cache.get("rt", 29_000)?.accessToken, "a");
    assert.equal(cache.get("rt", 30_000), undefined);
  });

  test("stores under the old and the rotated refresh token", () => {
    const cache = new TokenCache();
    cache.put(["old", "new"], { accessToken: "a", refreshToken: "new" }, 300);
    assert.equal(cache.get("old")?.refreshToken, "new");
    assert.equal(cache.get("new")?.accessToken, "a");
  });

  test("evicts the least recently used past capacity", () => {
    const cache = new TokenCache({ capacity: 2 });
    cache.put(["a"], { accessToken: "1", refreshToken: "a" }, 300);
    cache.put(["b"], { accessToken: "2", refreshToken: "b" }, 300);
    cache.get("a");
    cache.put(["c"], { accessToken: "3", refreshToken: "c" }, 300);
    assert.equal(cache.get("b"), undefined);
    assert.equal(cache.get("a")?.accessToken, "1");
    assert.equal(cache.size, 2);
  });
});
