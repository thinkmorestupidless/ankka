import { test, expect, seedTenancy } from "../fixtures.ts";

test("US3-4a a service page and a listing follow the control plane's changes live", async ({ page, target, signIn, unique, scriptsOff }) => {
  test.skip(scriptsOff, "live updates need scripts; without them a page shows the state of its last request");
  test.skip(target.kind !== "fake", "moving a service through its lifecycle needs the fake");
  const org = unique("live-org");
  const project = unique("live-proj");
  seedTenancy(target, { org, project });
  target.controlPlane!.seed({ services: [{ projectId: project, name: "cart", lifecycle: "UpdateInProgress" }] });

  await signIn(page, "owner", `/projects/${project}/services/cart`);
  await expect(page.locator(".ac-state-line")).toContainText("Updating");
  await expect(page.getByText("Updating as the platform reports")).toBeVisible();
  const navigations = await page.evaluate(() => performance.getEntriesByType("navigation").length);
  const started = Date.now();
  target.controlPlane!.tick();
  await expect(page.locator(".ac-state-line")).toContainText("Ready", { timeout: 5_000 });
  test.info().annotations.push({ type: "status-latency-ms", description: String(Date.now() - started) });
  expect(await page.evaluate(() => performance.getEntriesByType("navigation").length)).toBe(navigations);

  await page.goto(`${target.url}/projects/${project}`);
  const row = page.locator('tr[data-service="cart"]');
  await expect(row).toContainText("Ready");
  await expect(page.getByText("Updating as the platform reports")).toBeVisible();
  target.controlPlane!.state.services.get(`${project}/cart`)!.lifecycle = "Failed";
  await expect(row).toContainText("Failed", { timeout: 5_000 });
});

test("US3-6 logs with the CLI's choices, followed live", async ({ page, target, signIn, unique, scriptsOff }) => {
  test.skip(target.kind !== "fake", "writing log lines needs the fake");
  const org = unique("log-org");
  const project = unique("log-proj");
  seedTenancy(target, { org, project, service: "cart" });
  target.controlPlane!.appendLog(project, "cart", "first line");

  await signIn(page, "owner", `/projects/${project}/services/cart/logs?tail=50`);
  await expect(page.getByLabel("Instance")).toBeVisible();
  await expect(page.getByLabel("Last lines")).toHaveValue("50");
  await expect(page.getByLabel("Last seconds")).toBeVisible();
  await expect(page.getByLabel("The container before the last restart")).toBeVisible();
  const log = page.locator('pre[data-instance="cart-0"]');
  await expect(log).toContainText("first line");

  if (scriptsOff) {
    // Without scripts the page is what it was when asked for; asking again shows what is new.
    target.controlPlane!.appendLog(project, "cart", "second line");
    await page.reload();
    await expect(log).toContainText("second line");
    return;
  }
  await expect(page.getByText("Following new lines")).toBeVisible();
  const started = Date.now();
  target.controlPlane!.appendLog(project, "cart", "a line written just now");
  await expect(log).toContainText("a line written just now", { timeout: 5_000 });
  test.info().annotations.push({ type: "log-latency-ms", description: String(Date.now() - started) });

  await page.getByRole("button", { name: "Pause following" }).click();
  await expect(page.getByText("Paused", { exact: true })).toBeVisible();
  target.controlPlane!.appendLog(project, "cart", "written while paused");
  await page.waitForTimeout(3_000);
  await expect(log).not.toContainText("written while paused");
});
