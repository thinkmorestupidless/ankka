import { after, before, describe, test } from "node:test";
import assert from "node:assert/strict";
import { Issuer, IdentityProviderUnavailable, SessionEnded } from "../src/auth/oidc.ts";
import { fakeIssuer, type FakeIssuer } from "ankka-console/testing";

const redirectUri = "http://console.test/auth/callback";

/** Plays the browser: loads the form, posts the credentials, returns the callback URL it was sent to. */
async function signInAsBrowser(authorizationUrl: URL, listening: string, username: string): Promise<URL> {
  const toListening = (u: string) => {
    const url = new URL(u);
    const real = new URL(listening);
    url.protocol = real.protocol;
    url.host = real.host;
    return url.toString();
  };
  const page = await (await fetch(toListening(authorizationUrl.toString()))).text();
  const action = /action="([^"]+)"/.exec(page)![1].replace(/&amp;/g, "&");
  const form = new URLSearchParams();
  for (const [, name, value] of page.matchAll(/type="hidden" name="([^"]+)" value="([^"]*)"/g)) {
    form.set(name, value.replace(/&quot;/g, '"').replace(/&lt;/g, "<").replace(/&amp;/g, "&"));
  }
  form.set("username", username);
  form.set("password", username);
  const posted = await fetch(toListening(action), { method: "POST", body: form, redirect: "manual" });
  assert.equal(posted.status, 302);
  return new URL(posted.headers.get("location")!);
}

describe("Issuer", () => {
  let fake: FakeIssuer;
  let issuer: Issuer;

  before(async () => {
    // The issuer names an address that does not exist, as in a cluster; every server-side call must
    // therefore go through the backchannel or fail.
    fake = await fakeIssuer({ publicUrl: "http://auth.invalid:8443" });
    issuer = new Issuer({
      issuer: fake.issuer,
      clientId: "ankka-console",
      clientSecret: "dev",
      backchannelUrl: fake.url,
      allowInsecure: true,
    });
  });
  after(() => fake.close());

  test("sends the browser to the external address with PKCE, state and nonce", async () => {
    const began = await issuer.beginSignIn(redirectUri);
    assert.equal(began.url.origin, "http://auth.invalid:8443");
    assert.equal(began.url.searchParams.get("code_challenge_method"), "S256");
    assert.equal(began.url.searchParams.get("state"), began.state);
    assert.equal(began.url.searchParams.get("nonce"), began.nonce);
    assert.equal(began.url.searchParams.get("scope"), "openid");
  });

  test("completes the code flow through the backchannel and refreshes with rotation", async () => {
    const began = await issuer.beginSignIn(redirectUri);
    const callback = await signInAsBrowser(began.url, fake.url, "owner");
    const tokens = await issuer.finishSignIn(callback, began);
    assert.ok((await fake.verifyAccessToken(tokens.accessToken))?.sub === "owner");
    const refreshed = await issuer.refresh(tokens.refreshToken);
    assert.notEqual(refreshed.refreshToken, tokens.refreshToken);
    // Keycloak's default: the previous refresh token stays valid until it expires.
    await issuer.refresh(tokens.refreshToken);
  });

  test("refuses a callback whose state it did not issue", async () => {
    const began = await issuer.beginSignIn(redirectUri);
    const callback = await signInAsBrowser(began.url, fake.url, "owner");
    await assert.rejects(issuer.finishSignIn(callback, { ...began, state: "someone-else" }));
  });

  test("an ended session is SessionEnded; an unreachable issuer is IdentityProviderUnavailable", async () => {
    const tokens = await fake.mint("member");
    fake.expireSession("member");
    await assert.rejects(issuer.refresh(tokens.refreshToken), SessionEnded);
    const other = await fake.mint("owner");
    fake.failNext();
    await assert.rejects(issuer.refresh(other.refreshToken), IdentityProviderUnavailable);
  });

  test("ending a session makes its refresh token useless", async () => {
    const tokens = await fake.mint("owner");
    await issuer.endSession(tokens.refreshToken);
    await assert.rejects(issuer.refresh(tokens.refreshToken), SessionEnded);
  });

  test("a Keycloak named by host only answers the backchannel with the public issuer, and issues tokens for it", async () => {
    // Keycloak configured with a host name only takes its issuer's scheme and port from each request.
    // Reached directly on another port, it would name another issuer — unless the console sends the
    // forwarded headers the gateway would have.
    const keycloak = await fakeIssuer({ hostOnly: "auth.invalid" });
    try {
      const publicIssuer = "https://auth.invalid:8443/realms/ankka";
      const direct = await (await fetch(`${keycloak.url}/realms/ankka/.well-known/openid-configuration`)).json();
      assert.notEqual(direct.issuer, publicIssuer, "the direct answer names another issuer, as Keycloak's would");

      const console_ = new Issuer({ issuer: publicIssuer, clientId: "ankka-console", clientSecret: "dev", backchannelUrl: keycloak.url, allowInsecure: true });
      await console_.configuration();
      const tokens = await keycloak.mint("owner");
      const refreshed = await console_.refresh(tokens.refreshToken);
      const claims = JSON.parse(Buffer.from(refreshed.accessToken.split(".")[1], "base64url").toString());
      assert.equal(claims.iss, publicIssuer, "a token issued over the backchannel names the public issuer");
    } finally {
      await keycloak.close();
    }
  });

  test("refuses an issuer that is not the one the control plane expects", async () => {
    const wrong = new Issuer({
      issuer: "http://auth.invalid/realms/ankka",
      clientId: "ankka-console",
      clientSecret: "dev",
      backchannelUrl: fake.url,
      allowInsecure: true,
    });
    await assert.rejects(wrong.configuration(), /port included/);
  });
});
