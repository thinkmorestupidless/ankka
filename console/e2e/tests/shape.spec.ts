/**
 * features/console/shape.feature: a service's page shows what the platform runs for it, joined in
 * the direction traffic and data flow. Each test is titled by the scenario it proves.
 */
import type { Page } from "@playwright/test";
import { test, expect, seedTenancy, ops } from "../fixtures.ts";

const shape = (page: Page, name: string) => page.getByRole("figure", { name: `What the platform runs for ${name}` });
const part = (page: Page, name: string, which: string) => shape(page, name).locator(`[data-part="${which}"]`);

test("a service's shape shows its hostname, the service with its controls, its instances and its database, joined in order", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const project = unique("shape");
  seedTenancy(target, { org: unique("shapeo"), project });
  target.controlPlane!.seed({ services: [{ projectId: project, name: "cart", instances: 3, exposed: true, database: "cart", image: "registry.example.test/cart:4" }] });
  await signIn(page, "owner", `/projects/${project}/services/cart`);
  const parts = await shape(page, "cart").locator("[data-part]").evaluateAll((els) => els.map((e) => e.getAttribute("data-part")));
  expect(parts).toEqual(["hostname", "service", "instances", "database"]);
  await expect(part(page, "cart", "hostname")).toContainText(`cart-${project}.example.test`);
  await expect(part(page, "cart", "service")).toContainText("Generation 1, registry.example.test/cart:4");
  await expect(part(page, "cart", "instances")).toContainText("3 of 3 ready");
  await expect(part(page, "cart", "database")).toContainText("cart");
  // Three joins: hostname to service, and service to its instances and to its database.
  await expect(shape(page, "cart").locator("svg.ac-edge path")).toHaveCount(3);
  // The service's own controls, each the operation the inspector offers.
  const controls = page.getByRole("group", { name: "Controls of cart" });
  await expect(controls.getByRole("link", { name: "Logs" })).toHaveAttribute("href", `/projects/${project}/services/cart/logs`);
  const intents = async (scope: ReturnType<typeof ops>) => scope.locator('input[name="intent"]').evaluateAll((els) => els.map((e) => (e as HTMLInputElement).value));
  const tab = await intents(controls);
  expect(tab.sort()).toEqual(["pause", "restart"]);
  const inspector = await intents(ops(page));
  for (const intent of tab) expect(inspector).toContain(intent);
});

test("a service that is not exposed has no hostname in its shape", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const project = unique("noaddr");
  seedTenancy(target, { org: unique("noaddro"), project, service: "cart" });
  await signIn(page, "owner", `/projects/${project}/services/cart`);
  await expect(part(page, "cart", "hostname")).toHaveCount(0);
  for (const which of ["service", "instances", "database"]) await expect(part(page, "cart", which)).toBeVisible();
  await expect(shape(page, "cart").locator("svg.ac-edge path")).toHaveCount(2);
});

test("a database the cluster has not reported yet is shown as waiting", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const project = unique("nodb");
  seedTenancy(target, { org: unique("nodbo"), project });
  target.controlPlane!.seed({ services: [{ projectId: project, name: "cart", database: null }] });
  await signIn(page, "owner", `/projects/${project}/services/cart`);
  await expect(part(page, "cart", "database")).toContainText("Not reported yet");
});

test("a service that is not deployed yet still has a shape", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const project = unique("notdep");
  seedTenancy(target, { org: unique("notdepo"), project });
  target.controlPlane!.seed({ services: [{ projectId: project, name: "orders", instances: 2, lifecycle: "NotDeployed" }] });
  await signIn(page, "owner", `/projects/${project}/services/orders`);
  await expect(part(page, "orders", "instances")).toContainText("0 of 2 ready");
});

test("the shape is on the page with scripts off", async ({ page, target, signIn, unique, scriptsOff }) => {
  test.skip(!scriptsOff, "the scripts-off project proves this");
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const project = unique("offshape");
  seedTenancy(target, { org: unique("offshapeo"), project, service: "cart" });
  await signIn(page, "owner", `/projects/${project}/services/cart`);
  await expect(shape(page, "cart")).toBeVisible();
  await expect(part(page, "cart", "instances")).toContainText("1 of 1 ready");
});

test("the shape changes as the platform reports", async ({ page, target, signIn, unique, scriptsOff }) => {
  test.skip(scriptsOff, "following the platform needs scripts");
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const project = unique("liveshape");
  seedTenancy(target, { org: unique("liveshapeo"), project });
  target.controlPlane!.seed({ services: [{ projectId: project, name: "cart", instances: 3 }] });
  await signIn(page, "owner", `/projects/${project}/services/cart`);
  await expect(part(page, "cart", "instances")).toContainText("3 of 3 ready");
  await page.evaluate(() => ((window as unknown as { marker?: number }).marker = 3));
  const cart = [...target.controlPlane!.state.services.values()].find((s) => s.projectId === project && s.name === "cart")!;
  cart.readyInstances = 2;
  await expect(part(page, "cart", "instances")).toContainText("2 of 3 ready", { timeout: 5_000 });
  expect(await page.evaluate(() => (window as unknown as { marker?: number }).marker), "the page was not reloaded").toBe(3);
});

for (const [value, seed, inPart, inFacts] of [
  [
    "image",
    { name: "cart", image: "registry.example.test/a-very-long-organization-name/a-longer-image-name-still:1.2.3" },
    "service",
    "registry.example.test/a-very-long-organization-name/a-longer-image-name-still:1.2.3",
  ],
  ["hostname", { name: "a-service-with-a-very-long-name-in-a-project", exposed: true }, "hostname", ".example.test"],
] as const) {
  test(`a value longer than its part is clipped, and whole below: ${value}`, async ({ page, target, signIn, unique }) => {
    test.skip(target.kind !== "fake", "needs a seeded tenancy");
    const project = unique("a-project-with-a-long-name");
    seedTenancy(target, { org: unique("clipo"), project });
    target.controlPlane!.seed({ services: [{ projectId: project, ...seed }] });
    await signIn(page, "owner", `/projects/${project}/services/${seed.name}`);
    const line = part(page, seed.name, inPart).locator(inPart === "service" ? ".ac-part-sub" : ".ac-part-title");
    const clipped = await line.evaluate((el) => ({ overflow: el.scrollWidth > el.clientWidth, ellipsis: getComputedStyle(el).textOverflow }));
    expect(clipped).toEqual({ overflow: true, ellipsis: "ellipsis" });
    await expect(page.locator(".ac-facts dd", { hasText: inFacts })).toBeVisible();
  });
}

test("a web-hosted service's shape shows each of its mounts, and no database", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const project = unique("webshape");
  seedTenancy(target, { org: unique("webshapeo"), project, service: "cart" });
  target.controlPlane!.seed({
    services: [{ projectId: project, name: "web", hosting: "web", processPort: 3000, mounts: [{ path: "/backend/cart", service: "cart", state: "ok" }] }],
  });
  await signIn(page, "owner", `/projects/${project}/services/web`);
  const parts = await shape(page, "web").locator("[data-part]").evaluateAll((els) => els.map((e) => e.getAttribute("data-part")));
  expect(parts).toEqual(["service", "instances", "mount"]);
  await expect(part(page, "web", "service")).toContainText("Your program, port 3000");
  const mount = part(page, "web", "mount");
  await expect(mount).toContainText("/backend/cart");
  await expect(mount.getByRole("link", { name: "cart" })).toHaveAttribute("href", `/projects/${project}/services/cart`);
  await expect(shape(page, "web").locator("svg.ac-edge path")).toHaveCount(2);
});

test("a mount with no service behind it is marked in the shape", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const project = unique("webmiss");
  seedTenancy(target, { org: unique("webmisso"), project });
  target.controlPlane!.seed({ services: [{ projectId: project, name: "web", hosting: "web", mounts: [{ path: "/backend/orders", service: "orders", state: "no service" }] }] });
  await signIn(page, "owner", `/projects/${project}/services/web`);
  const mount = shape(page, "web").locator('[data-mount="/backend/orders"]');
  await expect(mount).toContainText("No service of that name");
  await expect(mount.locator(".ac-status-mark")).toHaveText("▲");
  await expect(mount.getByRole("link")).toHaveCount(0);
});
