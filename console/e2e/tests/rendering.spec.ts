import { test, expect, seedTenancy, body, ops } from "../fixtures.ts";

test("US7-1 every page is rendered before any script runs", async ({ page, target, signIn, unique, scriptsOff }) => {
  test.skip(scriptsOff, "the scripts-on project blocks scripts itself, to prove the same markup arrives without them");
  const org = unique("render");
  if (target.kind === "fake") seedTenancy(target, { org, project: unique("p") });
  await signIn(page, "owner");
  await page.route(/\.js(\?.*)?$/, (route) => route.abort());
  await page.goto(`${target.url}/`);
  await expect(page.getByRole("heading", { name: "Organizations" })).toBeVisible();
  await expect(page.getByRole("table")).toBeVisible();
  expect(await page.evaluate(() => typeof (window as unknown as { __reactRouterContext?: unknown }).__reactRouterContext)).not.toBe("undefined");
});

test("US7-2 navigation with scripts does not reload the document", async ({ page, target, signIn, unique, scriptsOff }) => {
  test.skip(scriptsOff, "without scripts every navigation is a document load, by design");
  const org = unique("nav");
  const project = unique("navp");
  if (target.kind === "fake") seedTenancy(target, { org, project });
  await signIn(page, "owner");
  if (target.kind !== "fake") return;
  await page.evaluate(() => ((window as unknown as { marker: number }).marker = 42));
  await page.getByRole("link", { name: `Org ${org}` }).click();
  await expect(page.getByRole("heading", { level: 1, name: `Org ${org}` })).toBeVisible();
  await body(page).getByRole("link", { name: `Project ${project}` }).click();
  await expect(page.getByRole("heading", { level: 1, name: `Project ${project}` })).toBeVisible();
  // The same document: the marker set before navigating is still there.
  expect(await page.evaluate(() => (window as unknown as { marker?: number }).marker)).toBe(42);
});

test("US7-3 every operation works with scripts disabled", async ({ page, target, signIn, unique, scriptsOff }) => {
  test.skip(!scriptsOff, "the scripts-off project is the one that proves it; every other spec runs there too");
  await signIn(page, "owner");
  const org = unique("noscript");
  const project = unique("noscriptp");
  await page.goto(`${target.url}/organizations/new`);
  await page.getByLabel("Id").fill(org);
  await page.getByLabel("Name").fill("No Scripts");
  await page.getByRole("button", { name: "Create organization" }).click();
  await page.getByRole("link", { name: "Create a project" }).click();
  await page.getByLabel("Id").fill(project);
  await page.getByLabel("Name").fill("No Scripts");
  await page.getByRole("button", { name: "Create project" }).click();
  await page.getByRole("link", { name: "Apply a descriptor" }).click();
  await page.getByLabel("Descriptor").fill(JSON.stringify({ name: "plain", service: { image: "plain:1" } }));
  await page.getByRole("button", { name: "Apply" }).click();
  await ops(page).getByRole("button", { name: "Pause" }).click();
  await expect(page.locator(".ac-state-line")).toContainText("Paused");
});

test("US7-4 a second submission while the first is in flight performs nothing", async ({ page, target, signIn, unique, scriptsOff }) => {
  test.skip(scriptsOff, "without scripts the browser itself sends one navigation per form");
  test.skip(target.kind !== "fake", "counting requests needs the fake");
  const org = unique("twice");
  const project = unique("twicep");
  seedTenancy(target, { org, project, service: "cart" });
  const route = "POST /services/{projectId}/{name}/restart";
  const before = target.controlPlane!.counts.get(route) ?? 0;
  await signIn(page, "owner", `/projects/${project}/services/cart`);
  await ops(page).getByRole("button", { name: "Restart" }).dblclick();
  await expect(page.locator("dt", { hasText: "Generation" }).locator("+ dd")).toHaveText("2");
  await page.waitForTimeout(500);
  expect((target.controlPlane!.counts.get(route) ?? 0) - before).toBe(1);
});
