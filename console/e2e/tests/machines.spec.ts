import { test, expect, afterProjection, body } from "../fixtures.ts";
import type { Page } from "@playwright/test";
import type { Target } from "../target.ts";

/** A new organization, then its machines page reached from the rail, as a person reaches it. */
async function machinesOfNewOrganization(page: Page, target: Target, org: string) {
  await page.goto(`${target.url}/organizations/new`);
  await page.getByLabel("Id").fill(org);
  await page.getByLabel("Name").fill("Machines");
  await page.getByRole("button", { name: "Create organization" }).click();
  await page.waitForURL(`${target.url}/organizations/${org}`);
  await page.getByRole("navigation", { name: "Console" }).getByRole("link", { name: "Machines of Machines" }).click();
  await page.waitForURL(`${target.url}/organizations/${org}/machines`);
}

async function register(page: Page, name: string) {
  await page.getByLabel("Machine name", { exact: true }).fill(name);
  await page.getByRole("button", { name: "Register machine" }).click();
}

test("FR-040 a machine's client secret is shown once, and not after a reload", async ({ page, target, signIn, unique, audit }) => {
  await signIn(page, "owner");
  const org = unique("mach-org");
  await machinesOfNewOrganization(page, target, org);
  await expect(body(page).getByText("No machines registered.")).toBeVisible();
  await audit(page);

  await register(page, "affiliate-network");
  const secret = page.locator("[data-secret]");
  await expect(secret).toHaveText(/^\s*[0-9a-f]{64}\s*$/);
  const value = (await secret.textContent())!.trim();
  await expect(page.getByText("It is shown this once")).toBeVisible();
  await expect(body(page).locator(".ac-notice")).toContainText(`machine:${org}/affiliate-network`);
  await audit(page);

  await afterProjection(page, async () => {
    await expect(body(page).locator('tr[data-machine="affiliate-network"]')).toBeVisible({ timeout: 1_000 });
  });
  await expect(page.locator("[data-secret]")).toHaveCount(0);
  expect(await page.content()).not.toContain(value);
  await expect(body(page).locator('tr[data-machine="affiliate-network"]')).toContainText(`machine:${org}/affiliate-network`);
  // The reload re-sent nothing: one machine exists, not two.
  await expect(body(page).locator("tr[data-machine]")).toHaveCount(1);

  // A name already registered is refused, with the control plane's reason.
  await register(page, "affiliate-network");
  await expect(page.getByRole("alert")).toContainText(`machine 'affiliate-network' is already registered on '${org}'`);
});

test("FR-040 a machine is given byte rates, and one out of range is refused", async ({ page, target, signIn, unique }) => {
  await signIn(page, "owner");
  const org = unique("rate-org");
  await machinesOfNewOrganization(page, target, org);
  await register(page, "network");
  await expect(page.locator("[data-secret]")).toBeVisible();
  await afterProjection(page, async () => {
    await expect(body(page).locator('tr[data-machine="network"]')).toBeVisible({ timeout: 1_000 });
  });
  const row = body(page).locator('tr[data-machine="network"]');
  await expect(row.locator("[data-byte-rates]")).toHaveText("The installation's default");

  const fill = async (produce: string, consume: string, percentage: string) => {
    const form = page.locator("details", { hasText: "Limit a machine on the broker" });
    if (!(await form.evaluate((d) => (d as HTMLDetailsElement).open))) await form.locator("summary").click();
    await page.getByLabel("Machine", { exact: true }).selectOption("network");
    await page.getByLabel("Produce bytes a second", { exact: true }).fill(produce);
    await page.getByLabel("Consume bytes a second", { exact: true }).fill(consume);
    await page.getByLabel("Request percentage", { exact: true }).fill(percentage);
    await Promise.all([page.waitForResponse((r) => r.request().method() === "POST"), page.getByRole("button", { name: "Set byte rates" }).click()]);
  };

  await fill("1048576", "4194304", "50");
  await expect(row.locator("[data-byte-rates]")).toHaveText("produce 1048576 B/s, consume 4194304 B/s, 50% of requests");

  // Over the installation's ceiling of 32 MiB a second.
  await fill("40000000", "4194304", "50");
  await expect(page.getByRole("alert")).toContainText("the produce byte rate of 40000000 bytes a second is over the installation's ceiling");
  await expect(row.locator("[data-byte-rates]")).toHaveText("produce 1048576 B/s, consume 4194304 B/s, 50% of requests");
});

test("FR-040 a deleted machine leaves the listing", async ({ page, target, signIn, unique }) => {
  await signIn(page, "owner");
  const org = unique("del-org");
  await machinesOfNewOrganization(page, target, org);
  await register(page, "retired");
  await expect(page.locator("[data-secret]")).toBeVisible();
  await afterProjection(page, async () => {
    await expect(body(page).getByRole("button", { name: "Delete retired" })).toBeVisible({ timeout: 1_000 });
  });
  // The delete must land before anything reloads the page, or the reload cancels it.
  await Promise.all([page.waitForResponse((r) => r.request().method() === "POST"), body(page).getByRole("button", { name: "Delete retired" }).click()]);
  await afterProjection(page, async () => {
    await expect(body(page).locator("tr[data-machine]")).toHaveCount(0, { timeout: 1_000 });
  });
});

test("FR-040 a member sees the organization's machines and the grants they hold, and cannot register one", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "the member and the grant are seeded");
  const org = unique("held-org");
  const grantor = unique("held-grantor");
  const project = unique("held-p");
  target.controlPlane!.seed({
    organizations: [
      { id: org, name: "Holders", owners: ["someone-else"], members: ["member"] },
      { id: grantor, name: "Grantor", owners: ["someone-else"] },
    ],
    projects: [{ id: project, name: "Players", organizationId: grantor }],
    topics: [{ projectId: project, name: "casino.players", partitions: 3 }],
    machines: [{ organizationId: org, name: "network", byteRates: { produceBytesPerSecond: 1024, consumeBytesPerSecond: 2048, requestPercentage: 25 } }],
  });
  target.controlPlane!.seed({
    grants: [{ projectId: project, grantee: `machine:${org}/network`, target: { kind: "topic", topic: "casino.players", right: "consume", decrypt: true } }],
  });
  await signIn(page, "member", `/organizations/${org}/machines`);
  await expect(body(page).locator('tr[data-machine="network"] [data-byte-rates]')).toHaveText("produce 1024 B/s, consume 2048 B/s, 25% of requests");
  const held = body(page).locator("tr[data-held]");
  await expect(held).toHaveCount(1);
  await expect(held).toContainText(`machine:${org}/network`);
  await expect(held).toContainText("topic casino.players consume decrypt");
  await expect(held).toContainText("pending");
  // Owners register, limit and delete; a member is offered none of it.
  await expect(page.getByRole("button", { name: "Register machine" })).toHaveCount(0);
  await expect(body(page).getByRole("button", { name: "Delete network" })).toHaveCount(0);
});
