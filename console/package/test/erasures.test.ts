/**
 * The console's erasure requests (feature 042) against the fake control plane: asked and answered
 * as the control plane answers, refused while erasure is off, an override an owner's alone, and a
 * certificate issued only once a request is applied.
 */
import { after, before, describe, test } from "node:test";
import assert from "node:assert/strict";
import { ControlPlaneClient, ControlPlaneError } from "ankka-console/client";
import { fakeControlPlane, fakeIssuer, type FakeControlPlane, type FakeIssuer } from "ankka-console/testing";
import { notBeforeOf } from "../src/ui/erasures.ts";

describe("erasure requests through the client", () => {
  let issuer: FakeIssuer;
  let cp: FakeControlPlane;
  const as = async (user: string) => {
    const { accessToken } = await issuer.mint(user);
    return new ControlPlaneClient({ baseUrl: cp.url, bearer: async () => accessToken });
  };
  const refused = (status: number, reason: string | RegExp) => (e: unknown) => {
    assert.ok(e instanceof ControlPlaneError);
    assert.equal(e.status, status);
    if (typeof reason === "string") assert.equal(e.reason, reason);
    else assert.match(e.reason, reason);
    return true;
  };

  before(async () => {
    issuer = await fakeIssuer();
    cp = await fakeControlPlane({ verify: (t) => issuer.verifyAccessToken(t) as never, issuer: issuer.issuer });
    cp.seed({
      organizations: [{ id: "acme", name: "Acme", owners: ["owner"], members: ["member"] }],
      projects: [{ id: "brand", name: "Brand", organizationId: "acme" }],
      services: [{ projectId: "brand", name: "players" }],
    });
  });
  after(async () => {
    await cp.close();
    await issuer.close();
  });

  test("every write is refused while the installation's erasure is off", async () => {
    const member = await as("member");
    assert.equal((await member.installation()).erasure?.enabled, false);
    await assert.rejects(member.askErasure("brand", { subject: "player/8c1f" }), refused(409, "erasure is off on this installation (ANKKA_ERASURE)"));
    cp.seed({ erasure: true });
    assert.equal((await member.installation()).erasure?.enabled, true);
  });

  test("an unheld request is applied after the answer, with each service's completion, and asked again is the same request", async () => {
    const member = await as("member");
    const asked = await member.askErasure("brand", { subject: "player/8c1f", correlation: "ticket-1" });
    assert.equal(asked.state, "applying");
    assert.equal(asked.askedBy, "member");
    const read = await member.getErasure("brand", asked.id);
    assert.equal(read.state, "applied");
    assert.deepEqual(read.completions.map((c) => c.service), ["players"]);
    const again = await member.askErasure("brand", { subject: "player/8c1f" });
    assert.equal(again.id, asked.id);
    const listed = await member.listErasures("brand", { correlation: "ticket-1" });
    assert.deepEqual(listed.map((e) => [e.id, e.state]), [[asked.id, "applied"]]);
    const certificate = await member.erasureCertificate("brand", asked.id);
    assert.deepEqual([certificate.request, certificate.subject, certificate.services.map((s) => s.service)], [asked.id, "player/8c1f", ["players"]]);
  });

  test("a subject outside the rule is refused in the control plane's words", async () => {
    const member = await as("member");
    await assert.rejects(member.askErasure("brand", { subject: "player 8c1f" }), refused(400, /^the value given is not a data subject: a data subject is 1 to 253 characters/));
  });

  test("a held request is replaced and withdrawn by a member, overridden only by an owner, and has no certificate until applied", async () => {
    const member = await as("member");
    const owner = await as("owner");
    const held = await member.askErasure("brand", { subject: "player/41d2", notBefore: notBeforeOf("2099-01-15"), reason: "litigation hold" });
    assert.equal(held.state, "held");
    assert.equal(held.notBefore, "2099-01-15T00:00:00.000Z");
    await assert.rejects(member.erasureCertificate("brand", held.id), refused(409, /a certificate is issued once it is applied/));
    const replaced = await member.replaceErasure("brand", held.id, { notBefore: notBeforeOf("2099-03-01") });
    assert.deepEqual([replaced.notBefore, replaced.reason], ["2099-03-01T00:00:00.000Z", undefined]);
    await assert.rejects(member.overrideErasureHold("brand", held.id, "regulator order 2026-114"), refused(403, "owner role required in organization 'acme'"));
    await assert.rejects(owner.overrideErasureHold("brand", held.id, " "), refused(400, "an override needs a reason"));
    const overridden = await owner.overrideErasureHold("brand", held.id, "regulator order 2026-114");
    assert.deepEqual(overridden.holdOverride && [overridden.holdOverride.owner, overridden.holdOverride.reason], ["owner", "regulator order 2026-114"]);
    assert.equal((await member.getErasure("brand", held.id)).state, "applied");
    await assert.rejects(member.withdrawErasure("brand", held.id), refused(409, /it is applied and cannot be withdrawn/));

    const another = await member.askErasure("brand", { subject: "player/77aa", notBefore: notBeforeOf("2099-01-15") });
    assert.equal((await member.withdrawErasure("brand", another.id)).state, "withdrawn");
  });
});
