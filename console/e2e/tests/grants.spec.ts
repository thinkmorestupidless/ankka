import { test, expect, body } from "../fixtures.ts";
import type { Page } from "@playwright/test";
import type { Target } from "../target.ts";

/** An organization and a project in it, made through the console as a person makes them. */
async function organizationWithProject(page: Page, target: Target, org: string, project: string) {
  await page.goto(`${target.url}/organizations/new`);
  await page.getByLabel("Id").fill(org);
  await page.getByLabel("Name").fill(`Org ${org}`);
  await page.getByRole("button", { name: "Create organization" }).click();
  await page.waitForURL(`${target.url}/organizations/${org}`);
  await page.goto(`${target.url}/organizations/${org}/projects/new`);
  await page.getByLabel("Id").fill(project);
  await page.getByLabel("Name").fill(`Project ${project}`);
  await page.getByRole("button", { name: "Create project" }).click();
  await page.waitForURL(`${target.url}/projects/${project}`);
}

/** Fills the make-a-grant form and submits it, waiting for the write to land before anything reloads. */
async function makeGrant(page: Page, fields: { grantee: string; kind: string; service?: string; method?: string; path?: string }) {
  const form = page.locator("details", { hasText: "Make a grant" });
  if (!(await form.evaluate((d) => (d as HTMLDetailsElement).open))) await form.locator("summary").click();
  await page.getByLabel("Grantee", { exact: true }).fill(fields.grantee);
  await page.getByLabel("Grant kind", { exact: true }).selectOption(fields.kind);
  if (fields.service) await page.getByLabel("Granted service", { exact: true }).fill(fields.service);
  if (fields.method) await page.getByLabel("Granted method", { exact: true }).fill(fields.method);
  if (fields.path) await page.getByLabel("Granted path", { exact: true }).fill(fields.path);
  await Promise.all([page.waitForResponse((r) => r.request().method() === "POST"), page.getByRole("button", { name: "Make grant" }).click()]);
}

test("FR-040 a project lists its grants with their effect, and what other projects grant it", async ({ page, target, signIn, unique, audit }) => {
  test.skip(target.kind !== "fake", "the grants are seeded");
  const org = unique("grant-org");
  const other = unique("grant-other");
  const project = unique("wallet-p");
  const payments = unique("payments");
  const casino = unique("casino");
  target.controlPlane!.seed({
    organizations: [
      { id: org, name: "Grantor", owners: ["owner"] },
      { id: other, name: "Affiliates", owners: ["someone-else"] },
    ],
    projects: [
      { id: project, name: "Wallets", organizationId: org },
      { id: payments, name: "Payments", organizationId: org },
      { id: casino, name: "Casino", organizationId: other },
    ],
    services: [{ projectId: project, name: "wallet" }],
    topics: [
      { projectId: project, name: "casino.players", partitions: 3 },
      { projectId: casino, name: "game.rounds", partitions: 6, compacted: true },
    ],
  });
  target.controlPlane!.seed({
    grants: [
      // Within the organization, on a running service: accepted and in effect.
      { projectId: project, grantee: `service:${payments}/merchant`, target: { kind: "route", service: "wallet", method: "POST", path: "/v1/wallets/{player}/deposits" } },
      // A machine's topic grant, accepted, where the broker is not exposed outside the cluster.
      { projectId: project, grantee: `machine:${other}/network`, target: { kind: "topic", topic: "casino.players", right: "consume" }, state: "accepted" },
      // Another organization's project offering this project's service a topic.
      { projectId: casino, grantee: `service:${project}/wallet`, target: { kind: "topic", topic: "game.rounds", right: "consume" } },
    ],
  });
  await signIn(page, "owner", `/projects/${project}`);

  const merchant = body(page).locator("tr[data-grant]", { hasText: `service:${payments}/merchant` });
  await expect(merchant).toHaveAttribute("data-state", "accepted");
  await expect(merchant.locator("[data-effect]")).toHaveText("in effect");
  await expect(merchant).toContainText("route wallet POST /v1/wallets/{player}/deposits");
  await expect(merchant).toContainText("by seed");

  const network = body(page).locator("tr[data-grant]", { hasText: `machine:${other}/network` });
  await expect(network.locator("[data-effect]")).toHaveText("broker not exposed");
  await expect(network).toContainText("topic casino.players consume");

  const received = body(page).locator("tr[data-received]");
  await expect(received).toHaveCount(1);
  await expect(received).toHaveAttribute("data-state", "pending");
  await expect(received).toContainText(casino);
  await expect(received).toContainText(`of ${other}`);
  await expect(received).toContainText("topic game.rounds consume");
  await expect(received).toContainText("6 partitions, compacted");
  await expect(received).toContainText("offered");
  await audit(page);
});

test("FR-040 an owner makes a grant and revokes it; a pending one is withdrawn", async ({ page, target, signIn, unique, audit }) => {
  await signIn(page, "owner");
  const org = unique("mk-org");
  const project = unique("mk-wallet");
  const elsewhere = unique("mk-else");
  await organizationWithProject(page, target, org, project);
  // A second organization of the same owner: a grant to its machine waits for an answer.
  await page.goto(`${target.url}/organizations/new`);
  await page.getByLabel("Id").fill(elsewhere);
  await page.getByLabel("Name").fill("Elsewhere");
  await page.getByRole("button", { name: "Create organization" }).click();
  await page.waitForURL(`${target.url}/organizations/${elsewhere}`);

  await page.goto(`${target.url}/projects/${project}`);
  await expect(body(page).getByText("No grants.")).toBeVisible();

  // A grantee in another organization: pending, until that organization's owner answers.
  await makeGrant(page, { grantee: `machine:${elsewhere}/network`, kind: "erasure" });
  const pending = body(page).locator("tr[data-grant]", { hasText: `machine:${elsewhere}/network` });
  await expect(pending).toHaveAttribute("data-state", "pending");
  await expect(pending.locator("[data-effect]")).toHaveText("pending");
  await audit(page);

  // A grant to the project's own service is refused, with the control plane's reason beside the form.
  await makeGrant(page, { grantee: `service:${project}/merchant`, kind: "route", service: "wallet", method: "POST", path: "/v1/deposits" });
  await expect(page.getByRole("alert")).toContainText("a project's own services need no grant");

  await expect(body(page).locator("tr[data-grant]")).toHaveCount(1);

  // Ending a pending grant withdraws it.
  await page.goto(`${target.url}/projects/${project}`);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST"),
    pending.getByRole("button", { name: `Withdraw the grant to machine:${elsewhere}/network of erasure` }).click(),
  ]);
  await expect(pending).toHaveAttribute("data-state", "withdrawn");
  await expect(pending.locator("[data-effect]")).toHaveText("withdrawn");
  await expect(pending.getByRole("button")).toHaveCount(0);
});

test("FR-040 a grant within the organization is in effect at once, and revoking it ends it", async ({ page, target, signIn, unique }) => {
  await signIn(page, "owner");
  const org = unique("rv-org");
  const project = unique("rv-wallet");
  const payments = unique("rv-pay");
  await organizationWithProject(page, target, org, project);
  await page.goto(`${target.url}/organizations/${org}/projects/new`);
  await page.getByLabel("Id").fill(payments);
  await page.getByLabel("Name").fill("Payments");
  await page.getByRole("button", { name: "Create project" }).click();
  await page.waitForURL(`${target.url}/projects/${payments}`);

  await page.goto(`${target.url}/projects/${project}`);
  await makeGrant(page, { grantee: `service:${payments}/merchant`, kind: "method", service: "wallet", method: "WalletService/Deposit" });
  const grant = body(page).locator("tr[data-grant]", { hasText: `service:${payments}/merchant` });
  await expect(grant).toHaveAttribute("data-state", "accepted");
  // No instance of `wallet` has run, so the method has not been seen.
  await expect(grant.locator("[data-effect]")).toHaveText("route not seen");
  await expect(grant).toContainText("method wallet WalletService/Deposit");

  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST"),
    grant.getByRole("button", { name: `Revoke the grant to service:${payments}/merchant of method wallet WalletService/Deposit` }).click(),
  ]);
  await expect(grant).toHaveAttribute("data-state", "revoked");
  await expect(grant).toContainText("ended");
  await expect(grant.getByRole("button")).toHaveCount(0);
});

test("FR-040 an owner of the grantee's organization accepts, declines and relinquishes what another organization offers; a member only reads", async ({
  page,
  target,
  signIn,
  unique,
  audit,
}) => {
  test.skip(target.kind !== "fake", "the grants are seeded");
  const org = unique("of-org");
  const grantor = unique("of-grantor");
  const casino = unique("of-casino");
  target.controlPlane!.seed({
    organizations: [
      { id: org, name: "Affiliates", owners: ["owner"], members: ["member"] },
      { id: grantor, name: "Casino operator", owners: ["someone-else"] },
    ],
    projects: [{ id: casino, name: "Casino", organizationId: grantor }],
    topics: [{ projectId: casino, name: "game.rounds", partitions: 6 }],
  });
  target.controlPlane!.seed({
    grants: [
      { projectId: casino, grantee: `machine:${org}/network`, target: { kind: "topic", topic: "game.rounds", right: "consume" } },
      { projectId: casino, grantee: `machine:${org}/reader`, target: { kind: "erasure" } },
      { projectId: casino, grantee: `machine:${org}/archive`, target: { kind: "topic", topic: "game.rounds", right: "consume" }, state: "accepted" },
    ],
  });

  // A member sees what is offered, and no control to answer it.
  await signIn(page, "member", `/organizations/${org}`);
  const offered = body(page).locator("tr[data-offered]");
  await expect(offered).toHaveCount(3);
  await expect(offered.getByRole("button")).toHaveCount(0);
  await page.context().clearCookies();

  await signIn(page, "owner", `/organizations/${org}`);
  const network = body(page).locator("tr[data-offered]", { hasText: `machine:${org}/network` });
  await expect(network).toHaveAttribute("data-state", "pending");
  await expect(network).toContainText(`${casino} of ${grantor}`);
  await expect(network).toContainText("topic game.rounds consume");
  await expect(network).toContainText("offered");
  await expect(network).toContainText("by seed");
  await audit(page);

  // Accepting a pending grant puts it in effect, and it can then be given up.
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST"),
    network.getByRole("button", { name: `Accept topic game.rounds consume to machine:${org}/network from ${casino}` }).click(),
  ]);
  await expect(network).toHaveAttribute("data-state", "accepted");
  await expect(network.getByRole("button", { name: /^Accept/ })).toHaveCount(0);
  await expect(network.getByRole("button", { name: /^Relinquish/ })).toHaveCount(1);

  // Declining one ends it: nothing more to answer.
  const reader = body(page).locator("tr[data-offered]", { hasText: `machine:${org}/reader` });
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST"),
    reader.getByRole("button", { name: `Decline erasure to machine:${org}/reader from ${casino}` }).click(),
  ]);
  await expect(reader).toHaveAttribute("data-state", "declined");
  await expect(reader).toContainText("declined");
  await expect(reader.getByRole("button")).toHaveCount(0);

  // Relinquishing an accepted grant gives it up without the grantor.
  const archive = body(page).locator("tr[data-offered]", { hasText: `machine:${org}/archive` });
  await expect(archive).toHaveAttribute("data-state", "accepted");
  await expect(archive.getByRole("button", { name: /^(Accept|Decline)/ })).toHaveCount(0);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST"),
    archive.getByRole("button", { name: `Relinquish topic game.rounds consume to machine:${org}/archive from ${casino}` }).click(),
  ]);
  await expect(archive).toHaveAttribute("data-state", "relinquished");
  await expect(archive).toContainText("relinquished");
  await expect(archive.getByRole("button")).toHaveCount(0);

  // A grant ended elsewhere meanwhile is refused, with the control plane's reason beside the card.
  target.controlPlane!.script("POST /organizations/{organizationId}/grants/{grantId}/relinquish", 409, "grant is revoked; relinquish applies to an accepted grant");
  await Promise.all([page.waitForResponse((r) => r.request().method() === "POST"), network.getByRole("button", { name: /^Relinquish/ }).click()]);
  await expect(page.getByRole("alert")).toContainText("relinquish applies to an accepted grant");
});
