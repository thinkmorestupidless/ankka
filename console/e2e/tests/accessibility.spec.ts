import { test, expect, seedTenancy } from "../fixtures.ts";
import type { Locator, Page } from "@playwright/test";

test("every page meets WCAG 2.1 AA as axe checks it", async ({ page, target, signIn, unique, audit, scriptsOff }) => {
  test.skip(scriptsOff, "axe runs in the page; the scripts-on run audits the same markup");
  test.skip(target.kind !== "fake", "every page needs a seeded tenancy");
  const org = unique("a11y");
  const project = unique("a11yp");
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
    "/auth/sign-out",
  ];
  for (const path of pages) {
    await page.goto(target.url + path);
    // Every disclosure open, so what is inside them is audited too.
    while ((await page.locator("details:not([open]) > summary").count()) > 0) await page.locator("details:not([open]) > summary").first().click();
    await audit(page);
  }
  await page.context().clearCookies();
  await signIn(page, "dev", `/organizations/${org}`);
  while ((await page.locator("details:not([open]) > summary").count()) > 0) await page.locator("details:not([open]) > summary").first().click();
  await audit(page);
});

/** Presses Tab until `target` has focus, as a keyboard user would, and checks the focus is visible. */
async function tabTo(page: Page, target: Locator) {
  for (let i = 0; i < 80; i++) {
    if (await target.evaluate((el) => el === document.activeElement)) {
      const outline = await target.evaluate((el) => getComputedStyle(el).outlineStyle);
      expect(outline, "focus must be visible").not.toBe("none");
      return;
    }
    await page.keyboard.press("Tab");
  }
  throw new Error(`could not reach ${target} with the Tab key`);
}

test("every operation can be completed with the keyboard alone", async ({ page, target, signIn, unique, scriptsOff }) => {
  test.skip(scriptsOff, "focus is inspected from inside the page");
  test.skip(target.kind !== "fake", "the full set of operations needs a seeded tenancy");
  const org = unique("keys");
  const project = unique("keysp");
  await signIn(page, "owner", "/organizations/new");
  await tabTo(page, page.getByLabel("Id"));
  await page.keyboard.type(org);
  await tabTo(page, page.getByLabel("Name"));
  await page.keyboard.type("Keyboard");
  await page.keyboard.press("Enter");
  await page.waitForURL(`${target.url}/organizations/${org}`);

  await tabTo(page, page.getByRole("link", { name: "Create a project" }));
  await page.keyboard.press("Enter");
  await tabTo(page, page.getByLabel("Id"));
  await page.keyboard.type(project);
  await tabTo(page, page.getByLabel("Name"));
  await page.keyboard.type("Keys");
  await page.keyboard.press("Enter");
  await page.waitForURL(`${target.url}/projects/${project}`);

  await tabTo(page, page.getByRole("link", { name: "Apply a descriptor" }));
  await page.keyboard.press("Enter");
  await tabTo(page, page.getByLabel("Descriptor"));
  await page.keyboard.type(JSON.stringify({ name: "cart", service: { image: "cart:1" } }));
  await tabTo(page, page.getByRole("button", { name: "Apply" }));
  await page.keyboard.press("Enter");
  await page.waitForURL(`${target.url}/projects/${project}/services/cart`);

  await tabTo(page, page.getByRole("button", { name: "Pause" }));
  await page.keyboard.press("Enter");
  await expect(page.locator(".ac-state-line")).toContainText("Paused");

  await page.goto(`${target.url}/organizations/${org}/members`);
  await tabTo(page, page.getByLabel("Email"));
  await page.keyboard.type("someone@example.test");
  await page.keyboard.press("Enter");
  await expect(page.locator('tr[data-invitation="someone@example.test"]')).toBeVisible();

  await page.goto(`${target.url}/organizations/${org}/tokens`);
  await tabTo(page, page.getByLabel("Label"));
  await page.keyboard.type("keyboard-ci");
  await page.keyboard.press("Enter");
  await expect(page.locator("[data-secret]")).toBeVisible();

  await page.goto(`${target.url}/organizations/${org}`);
  await tabTo(page, page.locator("summary", { hasText: "Rename or delete" }));
  await page.keyboard.press("Enter");
  await tabTo(page, page.getByLabel("New name"));
  await page.keyboard.press("ControlOrMeta+a");
  await page.keyboard.type("Renamed by keys");
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { level: 1, name: "Renamed by keys" })).toBeVisible();
});
