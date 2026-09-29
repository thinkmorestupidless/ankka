import { test, expect, afterProjection } from "../fixtures.ts";

test("US4-5 a deploy token's secret is shown once, and not after a reload", async ({ page, target, signIn, unique, audit }) => {
  await signIn(page, "owner");
  const org = unique("tok-org");
  await page.goto(`${target.url}/organizations/new`);
  await page.getByLabel("Id").fill(org);
  await page.getByLabel("Name").fill("Tokens");
  await page.getByRole("button", { name: "Create organization" }).click();
  await page.waitForURL(`${target.url}/organizations/${org}`);
  await page.getByRole("link", { name: "Deploy tokens" }).click();
  await audit(page);
  await page.getByLabel("Label").fill("ci");
  await page.getByRole("button", { name: "Create token" }).click();
  const secret = page.locator("[data-secret]");
  await expect(secret).toContainText("ankka_");
  const value = (await secret.textContent())!.trim();
  await expect(page.getByText("It is shown this once")).toBeVisible();
  await audit(page);

  await afterProjection(page, async () => {
    await expect(page.getByRole("cell", { name: "ci", exact: true })).toBeVisible({ timeout: 1_000 });
  });
  await expect(page.locator("[data-secret]")).toHaveCount(0);
  expect(await page.content()).not.toContain(value);
  // The reload re-sent nothing: one token exists, not two.
  await expect(page.locator("tr[data-token]")).toHaveCount(1);
});

test("US4-6 a revoked token is refused", async ({ page, target, signIn, unique }) => {
  await signIn(page, "owner");
  const org = unique("rev-org");
  await page.goto(`${target.url}/organizations/new`);
  await page.getByLabel("Id").fill(org);
  await page.getByLabel("Name").fill("Revoke");
  await page.getByRole("button", { name: "Create organization" }).click();
  await page.waitForURL(`${target.url}/organizations/${org}`);
  await page.goto(`${target.url}/organizations/${org}/tokens`);
  await page.getByLabel("Label").fill("robot");
  await page.getByRole("button", { name: "Create token" }).click();
  const secret = (await page.locator("[data-secret]").textContent())!.trim();

  const call = () => fetch(`${target.controlPlaneUrl}/organizations/${org}`, { headers: { authorization: `Bearer ${secret}` } });
  await expect.poll(async () => (await call()).status, { timeout: 5_000 }).toBe(200);
  await afterProjection(page, async () => {
    await expect(page.getByRole("button", { name: "Revoke" })).toBeVisible({ timeout: 1_000 });
  });
  // The revoke must land before anything reloads the page, or the reload cancels it.
  await Promise.all([page.waitForResponse((r) => r.request().method() === "POST"), page.getByRole("button", { name: "Revoke" }).click()]);
  await afterProjection(page, async () => {
    await expect(page.locator("tr[data-token]")).toHaveCount(0, { timeout: 1_000 });
  });
  await expect.poll(async () => (await call()).status, { timeout: 5_000 }).toBe(401);
});
