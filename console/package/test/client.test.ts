import { after, before, describe, test } from "node:test";
import assert from "node:assert/strict";
import { ControlPlaneClient, ControlPlaneError, SignInRequired } from "ankka-console/client";
import { fakeControlPlane, fakeIssuer, type FakeControlPlane, type FakeIssuer } from "ankka-console/testing";

describe("ControlPlaneClient against the fake control plane", () => {
  let issuer: FakeIssuer;
  let cp: FakeControlPlane;
  const as = async (user: string) => {
    const { accessToken } = await issuer.mint(user);
    return new ControlPlaneClient({ baseUrl: cp.url, bearer: async () => accessToken });
  };

  before(async () => {
    issuer = await fakeIssuer();
    cp = await fakeControlPlane({ verify: (t) => issuer.verifyAccessToken(t) as never, issuer: issuer.issuer });
    cp.seed({
      organizations: [{ id: "acme", name: "Acme", owners: ["owner"], members: ["member"] }],
      projects: [{ id: "checkout", name: "Checkout", organizationId: "acme" }],
      services: [{ projectId: "checkout", name: "cart" }],
    });
  });
  after(async () => {
    await cp.close();
    await issuer.close();
  });

  test("reads the discovery answer without a bearer", async () => {
    const client = new ControlPlaneClient({ baseUrl: cp.url, bearer: async () => null });
    const discovery = await client.authDiscovery();
    assert.equal(discovery.issuer, issuer.issuer);
  });

  test("decodes listings and details", async () => {
    const owner = await as("owner");
    const orgs = await owner.listOrganizations();
    assert.deepEqual(orgs.map((o) => [o.id, o.role, o.projects]), [["acme", "owner", 1]]);
    const svc = await owner.getService("checkout", "cart");
    assert.equal(svc.lifecycle, "Ready");
    assert.equal(svc.hostname, undefined);
    const whoami = await owner.whoami();
    assert.equal(whoami.subject, "owner");
    assert.deepEqual(whoami.organizations, [{ id: "acme", name: "Acme", role: "owner" }]);
  });

  test("a refusal carries the control plane's reason verbatim", async () => {
    const member = await as("member");
    await assert.rejects(member.renameOrganization("acme", "X"), (e: unknown) => {
      assert.ok(e instanceof ControlPlaneError);
      assert.equal(e.status, 403);
      assert.equal(e.reason, "owner role required in organization 'acme'");
      return true;
    });
    const outsider = await as("outsider");
    await assert.rejects(outsider.getOrganization("acme"), (e: unknown) => e instanceof ControlPlaneError && e.status === 404);
    const owner = await as("owner");
    await assert.rejects(owner.deleteOrganization("acme"), (e: unknown) =>
      e instanceof ControlPlaneError && e.status === 409 && e.reason === "organization 'acme' still has 1 project(s)",
    );
  });

  test("an invalid descriptor's problems are split out of the one message", async () => {
    const owner = await as("owner");
    await assert.rejects(owner.applyService("checkout", "Bad_Name", JSON.stringify({ name: "Bad_Name", service: {} })), (e: unknown) => {
      assert.ok(e instanceof ControlPlaneError);
      assert.equal(e.status, 400);
      assert.equal(e.problems.length, 2);
      return true;
    });
  });

  test("a rollback names its generation, and a past descriptor reads back as it was applied", async () => {
    const owner = await as("owner");
    const second = { name: "cart", service: { image: "cart:2" } };
    await owner.applyService("checkout", "cart", JSON.stringify(second));
    const rolled = await owner.rollback("checkout", "cart", 1);
    assert.equal(rolled.rolledBackTo, 1);
    assert.deepEqual([rolled.status.generation, rolled.status.image], [3, "cart:latest"]);
    assert.deepEqual(await owner.descriptor("checkout", "cart", 2), second);
    const history = await owner.history("checkout", "cart");
    const last = history.at(-1)!;
    assert.deepEqual([last.kind, last.rolledBackTo, last.image], ["rolled-back", 1, "cart:latest"]);
    assert.equal(last.digest, history[0]!.digest);
  });

  test("a refused rollback carries the control plane's reason verbatim", async () => {
    const owner = await as("owner");
    await assert.rejects(owner.rollback("checkout", "cart", 1), (e: unknown) =>
      e instanceof ControlPlaneError && e.status === 409 && e.reason === "service 'cart' already has the descriptor of generation 1",
    );
    await assert.rejects(owner.descriptor("checkout", "cart", 9), (e: unknown) =>
      e instanceof ControlPlaneError && e.status === 404 && e.reason === "service 'cart' has no generation 9",
    );
  });

  test("a 401 is retried once with a refreshed bearer, then becomes SignInRequired", async () => {
    const { accessToken } = await issuer.mint("owner");
    const asked: boolean[] = [];
    const recovering = new ControlPlaneClient({
      baseUrl: cp.url,
      bearer: async ({ refresh }) => (asked.push(refresh), refresh ? accessToken : "stale.token.here"),
    });
    assert.equal((await recovering.listOrganizations()).length, 1);
    assert.deepEqual(asked, [false, true]);

    const hopeless = new ControlPlaneClient({ baseUrl: cp.url, bearer: async () => "stale.token.here" });
    await assert.rejects(hopeless.listOrganizations(), SignInRequired);
    const nobody = new ControlPlaneClient({ baseUrl: cp.url, bearer: async () => null });
    await assert.rejects(nobody.whoami(), SignInRequired);
  });

  test("unknown fields in a response are dropped, not refused", async () => {
    const client = new ControlPlaneClient({
      baseUrl: "http://unused",
      bearer: async () => "t",
      transport: async () =>
        new Response(JSON.stringify({ subject: "s", emailVerified: true, platformAdmin: false, organizations: [], somethingNew: 1 }), {
          headers: { "content-type": "application/json" },
        }),
    });
    const whoami = await client.whoami();
    assert.equal("somethingNew" in whoami, false);
  });

  test("a deploy token is shown once and works as a bearer until revoked", async () => {
    const owner = await as("owner");
    const created = await owner.createToken("acme", { label: "ci" });
    assert.match(created.secret, /^ankka_/);
    assert.equal((await owner.tokens("acme")).some((t) => "secret" in t), false);
    const machine = new ControlPlaneClient({ baseUrl: cp.url, bearer: async () => created.secret });
    assert.equal((await machine.listServices("checkout")).length, 1);
    await owner.revokeToken("acme", created.id);
    await assert.rejects(machine.listServices("checkout"), SignInRequired);
  });
});
