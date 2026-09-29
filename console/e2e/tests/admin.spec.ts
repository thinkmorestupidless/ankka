import { test, expect, seedTenancy } from "../fixtures.ts";
import type { Page } from "@playwright/test";

/** Opens a disclosure unless it is open already: after a submission with scripts on, it stays open. */
async function open(page: Page, summary: string) {
  const details = page.locator("details", { has: page.locator("summary", { hasText: summary }) });
  if ((await details.getAttribute("open")) === null) await details.locator("summary").click();
}

test("US4-7 an administrator disables and enables an organization", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "a seeded tenancy with a running service needs the fake");
  const org = unique("adm-org");
  const project = unique("adm-proj");
  seedTenancy(target, { org, project, service: "cart" });
  await signIn(page, "dev", `/organizations/${org}`);
  await open(page, "Platform administration");
  await page.getByRole("button", { name: "Disable organization" }).click();
  await expect(page.locator("dt", { hasText: "State" }).locator("+ dd")).toContainText("Disabled");
  await page.goto(`${target.url}/projects/${project}`);
  await expect(page.locator('tr[data-service="cart"]')).toContainText("Suspended");
  await page.goto(`${target.url}/organizations/${org}`);
  await open(page, "Platform administration");
  await page.getByRole("button", { name: "Enable organization" }).click();
  await expect(page.locator("dt", { hasText: "State" }).locator("+ dd")).toHaveText("Active");
  await page.goto(`${target.url}/projects/${project}`);
  await expect(page.locator('tr[data-service="cart"]')).toContainText("Ready");
});

test("US4-8 an administrator sets and clears a quota", async ({ page, target, signIn, unique, audit }) => {
  test.skip(target.kind !== "fake", "a seeded tenancy needs the fake");
  const org = unique("quota-org");
  seedTenancy(target, { org, project: unique("p") });
  await signIn(page, "dev", `/organizations/${org}`);
  await open(page, "Platform administration");
  await audit(page);
  await page.getByLabel("Projects", { exact: true }).fill("3");
  await page.getByLabel("Instances", { exact: true }).fill("10");
  await page.getByRole("button", { name: "Set quota" }).click();
  await expect(page.locator("dt", { hasText: "Quota" }).locator("+ dd")).toHaveText("3 projects, any number of services, 10 instances");
  await open(page, "Platform administration");
  await page.getByRole("button", { name: "Clear quota" }).click();
  await expect(page.locator("dt", { hasText: "Quota" })).toHaveCount(0);
});

test("an organization with no owner can be given one by an administrator", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "an ownerless organization needs the fake");
  const org = unique("orphan");
  target.controlPlane!.seed({ organizations: [{ id: org, name: `Org ${org}`, members: ["member"] }] });
  await signIn(page, "dev", `/organizations/${org}`);
  await open(page, "Platform administration");
  await page.getByLabel("Subject to make owner").fill("member");
  await page.getByRole("button", { name: "Add owner" }).click();
  await expect(page.getByLabel("Subject to make owner")).toHaveCount(0);
  await page.goto(`${target.url}/organizations/${org}/members`);
  await expect(page.locator('tr[data-member="member"]')).toContainText("Owner");
});
