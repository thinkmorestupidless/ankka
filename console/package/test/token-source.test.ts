import { after, before, describe, test } from "node:test";
import assert from "node:assert/strict";
import { Issuer, IdentityProviderUnavailable } from "../src/auth/oidc.ts";
import { SealedCookieSessionStore } from "../src/session/cookie-store.ts";
import { TokenCache } from "../src/session/token-cache.ts";
import { SessionTokenSource } from "../src/session/token-source.ts";
import { fakeIssuer, type FakeIssuer } from "ankka-console/testing";

const cookieRequest = (setCookie: string) => new Request("http://console/", { headers: { cookie: setCookie.split(";")[0] } });

describe("SessionTokenSource", () => {
  let fake: FakeIssuer;
  let issuer: Issuer;
  const store = new SealedCookieSessionStore({ secret: "s", secure: false });

  before(async () => {
    fake = await fakeIssuer();
    issuer = new Issuer({ issuer: fake.issuer, clientId: "ankka-console", clientSecret: "dev", allowInsecure: true });
  });
  after(() => fake.close());

  async function sessionCookie(user: string) {
    const tokens = await fake.mint(user);
    const headers = new Headers();
    await store.write({ refreshToken: tokens.refreshToken, issuedAt: Math.floor(Date.now() / 1000) }, headers);
    return headers.get("set-cookie")!;
  }

  test("no cookie is nobody", async () => {
    const source = new SessionTokenSource({ store, cache: new TokenCache(), issuer });
    assert.equal(await source.accessToken(new Request("http://console/"), new Headers()), null);
  });

  test("a miss refreshes once and writes the rotated token back; the next request is a cache hit", async () => {
    const source = new SessionTokenSource({ store, cache: new TokenCache(), issuer });
    const cookie = await sessionCookie("owner");
    const out = new Headers();
    const [a, b] = await Promise.all([
      source.accessToken(cookieRequest(cookie), out),
      source.accessToken(cookieRequest(cookie), new Headers()),
    ]);
    assert.ok(a && a === b, "two concurrent requests share one refresh");
    assert.ok(out.get("set-cookie"), "the rotated refresh token is written back");
    assert.equal(await source.accessToken(cookieRequest(cookie), new Headers()), a);
  });

  test("an old cookie on a cold instance still works after another instance rotated it", async () => {
    const cookie = await sessionCookie("owner");
    const first = new SessionTokenSource({ store, cache: new TokenCache(), issuer });
    await first.accessToken(cookieRequest(cookie), new Headers());
    const second = new SessionTokenSource({ store, cache: new TokenCache(), issuer });
    assert.ok(await second.accessToken(cookieRequest(cookie), new Headers()));
  });

  test("an ended session clears the cookie and is nobody", async () => {
    const source = new SessionTokenSource({ store, cache: new TokenCache(), issuer });
    const cookie = await sessionCookie("member");
    fake.expireSession("member");
    const out = new Headers();
    assert.equal(await source.accessToken(cookieRequest(cookie), out), null);
    assert.match(out.get("set-cookie")!, /Max-Age=0/);
  });

  test("an unreachable identity provider is an error, and the cookie is kept", async () => {
    const source = new SessionTokenSource({ store, cache: new TokenCache(), issuer });
    const cookie = await sessionCookie("owner");
    await issuer.configuration();
    fake.failNext();
    const out = new Headers();
    await assert.rejects(source.accessToken(cookieRequest(cookie), out), IdentityProviderUnavailable);
    assert.equal(out.get("set-cookie"), null);
  });
});
