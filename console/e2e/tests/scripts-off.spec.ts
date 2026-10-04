/**
 * features/console/scripts-off.feature: every operation the console offers completes without
 * scripts, and an overlay is only an enhancement of a page that exists without it. Each test is
 * titled by the scenario it proves.
 */
import { test, expect, seedTenancy, ops, sections } from "../fixtures.ts";

test("an operation completes with scripts off", async ({ page, target, signIn, unique, scriptsOff }) => {
  test.skip(!scriptsOff, "the scripts-off project proves this");
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const project = unique("off");
  seedTenancy(target, { org: unique("offo"), project, service: "cart" });
  await signIn(page, "owner", `/projects/${project}/services/cart`);
  await ops(page).getByRole("button", { name: "Pause" }).click();
  await page.waitForURL(`${target.url}/projects/${project}/services/cart`);
  await expect(page.locator(".ac-state-line")).toContainText("Paused");
  await expect(ops(page).getByRole("button", { name: "Resume" })).toBeVisible();
});

test("a page's sections are reached as pages of their own with scripts off", async ({ page, target, signIn, unique, scriptsOff }) => {
  test.skip(!scriptsOff, "the scripts-off project proves this");
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const project = unique("sect");
  seedTenancy(target, { org: unique("secto"), project, service: "cart" });
  await signIn(page, "owner", `/projects/${project}/services/cart`);
  const path = `${target.url}/projects/${project}/services/cart`;
  for (const [label, url] of [
    ["Topology", `${path}/topology`],
    ["Logs", `${path}/logs`],
    ["History", `${path}/history`],
    ["Overview", path],
  ] as const) {
    await sections(page).getByRole("link", { name: label, exact: true }).click();
    await page.waitForURL(url);
    await expect(sections(page).locator('[aria-current="page"]')).toHaveText(label);
  }
});

test("further operations open as a disclosure with scripts off", async ({ page, target, signIn, unique, scriptsOff }) => {
  test.skip(!scriptsOff, "the scripts-off project proves this");
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const project = unique("more");
  seedTenancy(target, { org: unique("moreo"), project });
  await signIn(page, "owner", `/projects/${project}`);
  const more = ops(page).locator("details", { has: page.locator("summary", { hasText: "Rename or delete" }) });
  await expect(more.getByRole("button", { name: "Rename" })).toBeHidden();
  await more.locator("summary").click();
  await expect(more.getByRole("button", { name: "Rename" })).toBeVisible();
  await expect(more.getByRole("button", { name: "Delete project" })).toBeVisible();
});

test("a choice is made with scripts off", async ({ page, target, signIn, unique, scriptsOff }) => {
  test.skip(!scriptsOff, "the scripts-off project proves this");
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const org = unique("choice");
  seedTenancy(target, { org, project: unique("choicep") });
  await signIn(page, "owner", `/organizations/${org}/members`);
  await ops(page).getByLabel("Email").fill("ann@example.test");
  await ops(page).getByLabel("Role").selectOption("owner");
  await expect(ops(page).getByLabel("Role")).toHaveJSProperty("tagName", "SELECT");
  await ops(page).getByRole("button", { name: "Invite" }).click();
  await expect(page.locator('tr[data-invitation="ann@example.test"]')).toContainText("Owner");
});

test("with scripts on the same operation completes in place", async ({ page, target, signIn, unique, scriptsOff }) => {
  test.skip(scriptsOff, "the scripts-on project proves this");
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const project = unique("inplace");
  seedTenancy(target, { org: unique("inplaceo"), project, service: "cart" });
  await signIn(page, "owner", `/projects/${project}/services/cart`);
  await page.evaluate(() => ((window as unknown as { marker?: number }).marker = 7));
  await ops(page).getByRole("button", { name: "Pause" }).click();
  await expect(page.locator(".ac-state-line")).toContainText("Paused");
  // The same document: nothing was reloaded.
  expect(await page.evaluate(() => (window as unknown as { marker?: number }).marker)).toBe(7);
});
