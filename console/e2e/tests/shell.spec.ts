/**
 * features/console/shell.feature: every page is shown inside the shell. Each test is titled by the
 * scenario it proves. Locators are by role and name, so a test reads as what a person sees.
 */
import type { Page } from "@playwright/test";
import { test, expect, seedTenancy, body, ops } from "../fixtures.ts";

const rail = (page: Page) => page.getByRole("navigation", { name: "Console" });
const bar = (page: Page) => page.getByRole("banner");
const listing = (page: Page) => page.locator("nav.ac-listing");

test("a page is shown inside the shell, with every operation in its inspector", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const org = unique("shell");
  const project = unique("shellp");
  seedTenancy(target, { org, project, service: "cart" });
  await signIn(page, "owner", `/projects/${project}/services/cart`);

  // The rail marks the area and offers every area and signing out.
  await expect(rail(page).locator('[data-area="services"] [aria-current]')).toHaveCount(1);
  for (const area of ["organizations", "projects", "services", "members", "tokens"]) await expect(rail(page).locator(`[data-area="${area}"] a`)).toHaveCount(1);
  await expect(rail(page).getByRole("button", { name: "Sign out" })).toBeVisible();

  // The bar says where the member is, offers the primary operation, and names the member.
  const crumbs = bar(page).getByRole("navigation", { name: "Breadcrumb" });
  await expect(crumbs).toContainText(`Project ${project}`);
  await expect(crumbs.locator('[aria-current="page"]')).toHaveText("cart");
  await expect(bar(page).getByRole("link", { name: "Apply a new descriptor" })).toBeVisible();
  await expect(bar(page)).toContainText(target.kind === "fake" ? "Olive Owner" : "");

  // Every operation is in the inspector; whatever else offers one offers one the inspector has.
  const intents = async (scope: ReturnType<typeof body>) =>
    (await scope.locator('form[method="post"] input[name="intent"]').evaluateAll((els) => els.map((e) => (e as HTMLInputElement).value))).sort();
  const inInspector = await intents(ops(page));
  for (const intent of ["pause", "restart", "expose", "delete"]) expect(inInspector).toContain(intent);
  for (const intent of await intents(body(page))) expect(inInspector, `${intent} is offered outside the inspector only`).toContain(intent);
});

test("the rail opens the members and the deploy tokens of the organization the page is in", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const acme = unique("acme");
  const globex = unique("globex");
  const project = unique("rail");
  seedTenancy(target, { org: acme, project, service: "cart" });
  target.controlPlane!.seed({ organizations: [{ id: globex, name: `Org ${globex}`, owners: ["owner"] }] });
  await signIn(page, "owner", `/projects/${project}/services/cart`);
  await rail(page).locator('[data-area="members"] a').click();
  await page.waitForURL(`${target.url}/organizations/${acme}/members`);
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(`Members of Org ${acme}`);
  await expect(rail(page).locator('[data-area="tokens"] a')).toHaveAttribute("href", `/organizations/${acme}/tokens`);
});

test("the front page has no panel, and the organization's areas wait until one is opened", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const acme = unique("front");
  seedTenancy(target, { org: acme, project: unique("frontp") });
  await signIn(page, "owner", "/");
  await expect(body(page).getByRole("link", { name: `Org ${acme}` })).toBeVisible();
  await expect(listing(page)).toHaveCount(0);
  await expect(rail(page).locator('[data-area="organizations"] a')).toBeVisible();
  for (const area of ["members", "tokens"]) await expect(rail(page).locator(`[data-area="${area}"] [aria-disabled="true"]`)).toHaveCount(1);
  // The page takes the listing's width: the main column starts right after the rail.
  const railBox = (await rail(page).boundingBox())!;
  const mainBox = (await body(page).boundingBox())!;
  expect(mainBox.x - (railBox.x + railBox.width)).toBeLessThan(30);
});

test("the panel lists the project's services with their lifecycle and marks the one being read", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const org = unique("panel");
  const project = unique("panelp");
  seedTenancy(target, { org, project, service: "cart" });
  target.controlPlane!.seed({ services: [{ projectId: project, name: "inventory", instances: 2, lifecycle: "PartiallyReady" }] });
  const inventory = [...target.controlPlane!.state.services.values()].find((s) => s.projectId === project && s.name === "inventory")!;
  inventory.readyInstances = 1;
  await signIn(page, "owner", `/projects/${project}/services/cart`);
  const items = listing(page).locator("li");
  await expect(items).toHaveCount(2);
  await expect(items.nth(0)).toContainText("cart");
  await expect(items.nth(0)).toContainText("Ready");
  await expect(items.nth(1)).toContainText("inventory");
  await expect(items.nth(1)).toContainText("1 of 2");
  await expect(items.nth(1)).toContainText("Partially ready");
  await expect(listing(page).locator('[aria-current="page"]')).toContainText("cart");
});

test("the destructive operation waits behind a disclosure at the inspector's foot", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const org = unique("foot");
  const project = unique("footp");
  seedTenancy(target, { org, project, service: "cart" });
  await signIn(page, "owner", `/projects/${project}/services/cart`);
  const last = ops(page).locator(":scope > *").last();
  await expect(last).toHaveJSProperty("tagName", "DETAILS");
  await expect(last.getByRole("button", { name: "Delete service" })).toBeHidden();
  await last.locator("summary").click();
  await expect(last.getByRole("button", { name: "Delete service" })).toBeVisible();
  await expect(last.getByRole("button")).toHaveCount(1);
});

test("on a narrow screen the shell collapses in reading order and hides nothing", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const org = unique("narrow");
  const project = unique("narrowp");
  seedTenancy(target, { org, project, service: "cart" });
  await page.setViewportSize({ width: 400, height: 800 });
  await signIn(page, "owner", `/projects/${project}/services/cart`);
  const parts = [rail(page), bar(page), listing(page), body(page), ops(page)];
  let y = -1;
  for (const part of parts) {
    await expect(part).toBeVisible();
    const box = (await part.boundingBox())!;
    expect(box.y, "each part below the one before").toBeGreaterThan(y);
    y = box.y;
  }
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow, "the page scrolls sideways").toBeLessThanOrEqual(0);
});

test("a project with more services than the panel shows at once keeps the one being read in view", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const org = unique("many");
  const project = unique("manyp");
  seedTenancy(target, { org, project });
  const names = Array.from({ length: 40 }, (_x, i) => `svc-${String(i).padStart(2, "0")}`);
  target.controlPlane!.seed({ services: names.map((name) => ({ projectId: project, name })) });
  const last = names[names.length - 1]!;
  await signIn(page, "owner", `/projects/${project}/services/${last}`);
  const items = listing(page).locator("li");
  await expect(items).toHaveCount(40);
  expect(await items.locator(".ac-listing-name").allTextContents()).toEqual(names);
  const scroller = listing(page).locator(".ac-listing-items");
  expect(await scroller.evaluate((el) => el.scrollHeight > el.clientHeight), "the panel scrolls").toBe(true);
  const current = (await listing(page).locator('[aria-current="page"]').boundingBox())!;
  const view = (await scroller.boundingBox())!;
  expect(current.y).toBeGreaterThanOrEqual(view.y - 1);
  expect(current.y + current.height).toBeLessThanOrEqual(view.y + view.height + 1);
});

test("an organization's page lists its projects in the panel", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const org = unique("orgp");
  const checkout = unique("checkout");
  const billing = unique("billing");
  seedTenancy(target, { org, project: checkout, service: "cart" });
  target.controlPlane!.seed({ projects: [{ id: billing, name: `Project ${billing}`, organizationId: org }] });
  await signIn(page, "owner", `/organizations/${org}`);
  await expect(listing(page)).toContainText(`Project ${checkout}`);
  await expect(listing(page)).toContainText("1 service");
  await expect(listing(page)).toContainText(`Project ${billing}`);
  await expect(listing(page)).toContainText("0 services");
});
