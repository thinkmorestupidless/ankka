/**
 * features/console/accessibility.feature and features/console/theme.feature: the console in both
 * themes, audited; what a browser that asks for less motion or forces its own colours sees; and
 * that everything a page fetches comes from the console's own origin. Each test is titled by the
 * scenario it proves.
 */
import { test, expect, seedTenancy } from "../fixtures.ts";

test("every page has no accessibility violation in either theme", async ({ page, target, signIn, unique, audit, scriptsOff }) => {
  test.skip(scriptsOff, "axe runs in the page; the scripts-on run audits the same markup");
  test.skip(target.kind !== "fake", "every page needs a seeded tenancy");
  const org = unique("themes");
  const project = unique("themesp");
  seedTenancy(target, { org, project, service: "cart" });
  await signIn(page, "owner");
  const pages = [
    "/",
    "/organizations/new",
    `/organizations/${org}`,
    `/organizations/${org}/members`,
    `/organizations/${org}/tokens`,
    `/organizations/${org}/projects/new`,
    `/projects/${project}`,
    `/projects/${project}/services/apply`,
    `/projects/${project}/services/cart`,
    `/projects/${project}/services/cart/logs`,
    `/projects/${project}/services/cart/topology`,
    `/projects/${project}/services/cart/history`,
  ];
  const grounds: Record<string, string> = {};
  for (const scheme of ["dark", "light"] as const) {
    for (const path of pages) {
      await page.goto(target.url + path);
      // The light theme is a host's choice; the suite makes it as a host would, on the root.
      if (scheme === "light") await page.locator(".ac-root").evaluate((el) => el.classList.add("ac-light"));
      while ((await page.locator("details:not([open]) > summary").count()) > 0) await page.locator("details:not([open]) > summary").first().click();
      await audit(page);
    }
    grounds[scheme] = await page.locator(".ac-backdrop").evaluate((el) => getComputedStyle(el).backgroundImage);
  }
  expect(grounds.dark, "the two themes draw different backdrops").not.toBe(grounds.light);
});

test("a lifecycle is told by its shape as well as its colour", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const project = unique("marks");
  seedTenancy(target, { org: unique("markso"), project, service: "cart" });
  target.controlPlane!.seed({ services: [{ projectId: project, name: "inventory", lifecycle: "Failed" }] });
  await signIn(page, "owner", `/projects/${project}`);
  const mark = (name: string) => page.locator(`tr[data-service="${name}"] .ac-status-mark`);
  await expect(mark("cart")).toHaveText("●");
  await expect(mark("inventory")).toHaveText("▲");
});

test("a member who asks for reduced motion sees the live indicator still", async ({ page, target, signIn, unique, scriptsOff }) => {
  test.skip(scriptsOff, "the live indicator shows only while scripts follow the platform");
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const project = unique("motion");
  seedTenancy(target, { org: unique("motiono"), project, service: "cart" });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await signIn(page, "owner", `/projects/${project}/services/cart`);
  const live = page.locator(".ac-state-line .ac-live");
  await expect(live).toBeVisible();
  expect(await live.evaluate((el) => getComputedStyle(el, "::before").animationName)).toBe("none");
  await page.emulateMedia({ reducedMotion: "no-preference" });
  expect(await live.evaluate((el) => getComputedStyle(el, "::before").animationName), "it moves when motion is welcome").not.toBe("none");
});

test("in forced colours every surface keeps its edge", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const project = unique("forced");
  seedTenancy(target, { org: unique("forcedo"), project, service: "cart" });
  await page.emulateMedia({ forcedColors: "active" });
  await signIn(page, "owner", `/projects/${project}/services/cart`);
  for (const selector of [".ac-rail", ".ac-bar", ".ac-listing", ".ac-inspector", ".ac-card", ".ac-part"]) {
    const styles = await page.locator(selector).evaluateAll((els) => els.map((el) => getComputedStyle(el).borderTopStyle));
    expect(styles.length, `${selector} is on the page`).toBeGreaterThan(0);
    for (const s of styles) expect(s, `${selector} has an edge`).not.toBe("none");
  }
  for (const s of await page.locator(".ac-inspector .ac-button").evaluateAll((els) => els.map((el) => getComputedStyle(el).borderTopStyle))) expect(s).not.toBe("none");
});

test("a member reads the console dark whatever the browser prefers", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const project = unique("nopref");
  seedTenancy(target, { org: unique("noprefo"), project, service: "cart" });
  await page.emulateMedia({ colorScheme: "light" });
  await signIn(page, "owner", `/projects/${project}/services/cart`);
  expect(await page.locator(".ac-root").evaluate((el) => getComputedStyle(el).colorScheme)).toBe("dark");
  expect(await page.locator(".ac-root").evaluate((el) => getComputedStyle(el).getPropertyValue("--ac-color-mesh-base").trim())).toBe("#111f26");
});

test("a host that chooses the light theme shows the console light", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const project = unique("light");
  seedTenancy(target, { org: unique("lighto"), project, service: "cart" });
  await signIn(page, "owner", `/projects/${project}/services/cart`);
  // What <Shell theme="light"> renders; the fixture host's own test proves the prop.
  await page.locator(".ac-root").evaluate((el) => el.classList.add("ac-light"));
  expect(await page.locator(".ac-root").evaluate((el) => getComputedStyle(el).colorScheme)).toBe("light");
  expect(await page.locator(".ac-root").evaluate((el) => getComputedStyle(el).getPropertyValue("--ac-color-mesh-base").trim())).toBe("#e6eeee");
});

test("everything a page fetches comes from the console's own origin", async ({ page, target, signIn, unique, scriptsOff }) => {
  test.skip(scriptsOff, "a page without scripts fetches less; the scripts-on run sees everything");
  test.skip(target.kind !== "fake", "needs a seeded tenancy");
  const project = unique("origin");
  seedTenancy(target, { org: unique("origino"), project, service: "cart" });
  await signIn(page, "owner", "/");
  const origin = new URL(target.url).origin;
  const requests: { url: string; type: string }[] = [];
  page.on("request", (r) => requests.push({ url: r.url(), type: r.resourceType() }));
  await page.goto(`${target.url}/projects/${project}/services/cart`, { waitUntil: "networkidle" });
  await page.evaluate(() => document.fonts.ready);
  const elsewhere = requests.filter((r) => !r.url.startsWith("data:") && new URL(r.url).origin !== origin);
  expect(elsewhere.map((r) => r.url), "fetched from another origin").toEqual([]);
  const fonts = requests.filter((r) => r.type === "font");
  expect(fonts.length, "the face was fetched").toBeGreaterThan(0);
  for (const f of fonts) expect(f.url).toMatch(/\.woff2$/);
});
