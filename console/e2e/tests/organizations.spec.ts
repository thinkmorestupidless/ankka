import { test, expect, seedTenancy, afterProjection, body } from "../fixtures.ts";
import type { Page } from "@playwright/test";

async function createOrganization(page: Page, url: string, id: string, name: string) {
  await page.goto(`${url}/organizations/new`);
  await page.getByLabel("Id").fill(id);
  await page.getByLabel("Name").fill(name);
  await page.getByRole("button", { name: "Create organization" }).click();
}

test("US2-1 a member sees their organizations with their role, and nothing else", async ({ page, target, signIn, unique, audit }) => {
  test.skip(target.kind !== "fake", "seeding memberships needs the fake control plane");
  const mine = unique("mine");
  const theirs = unique("theirs");
  seedTenancy(target, { org: mine, project: unique("p") });
  target.controlPlane!.seed({ organizations: [{ id: theirs, name: `Org ${theirs}`, owners: ["owner"] }] });
  await signIn(page, "member");
  const row = page.getByRole("row", { name: new RegExp(`Org ${mine}`) });
  await expect(row).toContainText("Member");
  await expect(page.getByText(`Org ${theirs}`)).toHaveCount(0);
  await audit(page);
});

test("US2-2 a platform administrator sees every organization", async ({ page, target, signIn, unique }) => {
  const other = unique("other");
  if (target.kind === "fake") target.controlPlane!.seed({ organizations: [{ id: other, name: `Org ${other}`, owners: ["owner"] }] });
  await signIn(page, "dev");
  await expect(page.getByText("a platform administrator: you see every organization")).toBeVisible();
  if (target.kind === "fake") await expect(page.getByRole("row", { name: new RegExp(`Org ${other}`) })).toContainText("Administrator (not a member)");
});

test("US2-3 creating an organization lands on it, read by id", async ({ page, target, signIn, unique, audit }) => {
  await signIn(page, "owner");
  await page.goto(`${target.url}/organizations/new`);
  await audit(page);
  // The listing lags the write, as the control plane's projections do; the page must not depend on it.
  target.controlPlane?.hideNewFromListings();
  const id = unique("acme");
  await createOrganization(page, target.url, id, "Acme Widgets");
  await page.waitForURL(`${target.url}/organizations/${id}`);
  await expect(page.getByRole("heading", { level: 1, name: "Acme Widgets" })).toBeVisible();
  await expect(page.getByText("Owner", { exact: true })).toBeVisible();
  target.controlPlane?.settle();
  await audit(page);
});

test("US2-4 an owner renames an organization; a member is not offered it", async ({ page, target, signIn, unique }) => {
  const id = unique("rename");
  await signIn(page, "owner");
  await createOrganization(page, target.url, id, "Before");
  await page.waitForURL(`${target.url}/organizations/${id}`);
  await page.getByText("Rename or delete").click();
  await page.getByLabel("New name").fill("After");
  await page.getByRole("button", { name: "Rename" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "After" })).toBeVisible();

  if (target.kind !== "fake") return;
  target.controlPlane!.state.organizations.get(id)!.members.set("member", { role: "member", since: new Date().toISOString() });
  await page.context().clearCookies();
  await signIn(page, "member", `/organizations/${id}`);
  await expect(page.getByText("Rename or delete")).toHaveCount(0);
  // Posted anyway, it is the control plane's refusal that answers.
  const res = await page.request.post(`${target.url}/organizations/${id}`, { form: { intent: "rename", name: "Hijacked" } });
  expect(res.status()).toBe(403);
  expect(await res.text()).toContain("owner role required");
});

test("US2-5 an empty organization is deleted; a non-empty one is refused with the reason", async ({ page, target, signIn, unique }) => {
  await signIn(page, "owner");
  const full = unique("full");
  await createOrganization(page, target.url, full, "Full");
  await page.getByRole("link", { name: "Create a project" }).click();
  await page.getByLabel("Id").fill(unique("proj"));
  await page.getByLabel("Name").fill("Something");
  await page.getByRole("button", { name: "Create project" }).click();
  await page.waitForURL(/\/projects\/proj-/);
  await page.goto(`${target.url}/organizations/${full}`);
  // The control plane counts projects from a projection; a delete that races it is allowed. Wait
  // until the listing shows the project, as a person reading the page would.
  await expect(async () => {
    await page.reload();
    await expect(body(page).getByRole("link", { name: "Something" })).toBeVisible({ timeout: 1_000 });
  }).toPass({ timeout: 15_000 });
  await page.getByText("Rename or delete").click();
  await page.getByRole("button", { name: "Delete organization" }).click();
  await expect(page.getByRole("alert")).toContainText(`organization '${full}' still has 1 project(s)`);

  const empty = unique("empty");
  await createOrganization(page, target.url, empty, "Empty");
  await page.waitForURL(`${target.url}/organizations/${empty}`);
  await page.getByText("Rename or delete").click();
  await page.getByRole("button", { name: "Delete organization" }).click();
  await page.waitForURL(`${target.url}/`);
  await afterProjection(page, async () => {
    await expect(page.getByRole("link", { name: "Empty", exact: true })).toHaveCount(0, { timeout: 1_000 });
  });
});

test("US2-6 a deleted id cannot be reused, and the page says so", async ({ page, target, signIn, unique }) => {
  await signIn(page, "owner");
  const id = unique("gone");
  await createOrganization(page, target.url, id, "Gone");
  await page.waitForURL(`${target.url}/organizations/${id}`);
  await page.getByText("Rename or delete").click();
  await page.getByRole("button", { name: "Delete organization" }).click();
  await page.waitForURL(`${target.url}/`);
  await createOrganization(page, target.url, id, "Again");
  await expect(page.getByRole("alert")).toContainText("its id is not reused");
  await expect(page.getByLabel("Name")).toHaveValue("Again");
});

test("US2-8 where only administrators create organizations, the refusal names the sign-up address", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "the installation's policy is fixed on the compose control plane");
  target.controlPlane!.policy("platform-admin", "https://ankka.example/sign-up");
  try {
    await signIn(page, "owner");
    await createOrganization(page, target.url, unique("denied"), "Denied");
    await expect(page.getByRole("alert")).toContainText("https://ankka.example/sign-up");
  } finally {
    target.controlPlane!.policy("open");
  }
});
