import { test, expect, seedTenancy, afterProjection, ops, sections } from "../fixtures.ts";
import type { Page } from "@playwright/test";
import type { Target } from "../target.ts";

/** An organization and project owned by `owner`: seeded on the fake, created through the pages on compose. */
async function tenancy(page: Page, target: Target, signIn: (p: Page, u: string, path?: string) => Promise<void>, unique: (p: string) => string) {
  const org = unique("svc-org");
  const project = unique("svc-proj");
  if (target.kind === "fake") {
    seedTenancy(target, { org, project });
    await signIn(page, "owner", `/projects/${project}`);
  } else {
    await signIn(page, "owner");
    await page.goto(`${target.url}/organizations/new`);
    await page.getByLabel("Id").fill(org);
    await page.getByLabel("Name").fill("Services");
    await page.getByRole("button", { name: "Create organization" }).click();
    await page.getByRole("link", { name: "Create a project" }).click();
    await page.getByLabel("Id").fill(project);
    await page.getByLabel("Name").fill("Services");
    await page.getByRole("button", { name: "Create project" }).click();
    await page.waitForURL(`${target.url}/projects/${project}`);
  }
  return { org, project };
}

async function apply(page: Page, target: Target, project: string, descriptor: object) {
  await page.goto(`${target.url}/projects/${project}/services/apply`);
  await page.getByLabel("Descriptor").fill(JSON.stringify(descriptor));
  await page.getByRole("button", { name: "Apply" }).click();
}

test("US3-1 a project lists its services with state, instances, image, generation and hostname", async ({ page, target, signIn, unique, audit }) => {
  const { project } = await tenancy(page, target, signIn, unique);
  await apply(page, target, project, { name: "cart", service: { image: "cart:1.0.0" } });
  await page.waitForURL(`${target.url}/projects/${project}/services/cart`);
  target.controlPlane?.tick();
  await page.goto(`${target.url}/projects/${project}`);
  const row = page.locator('tr[data-service="cart"]');
  await afterProjection(page, async () => {
    await expect(row).toBeVisible({ timeout: 1_000 });
  });
  await expect(row).toContainText("cart:1.0.0");
  await expect(row).toContainText(target.kind === "fake" ? "Ready" : "Updating");
  // A control plane connected to no cluster reports what it last knew: nothing running yet.
  await expect(row).toContainText(target.kind === "fake" ? "1 of 1" : /\d+ of \d+/);
  await expect(row).toContainText("Not exposed");
  await audit(page);
});

test("US3-2 a service shows every status field and its attributed history", async ({ page, target, signIn, unique, audit }) => {
  const { project } = await tenancy(page, target, signIn, unique);
  await apply(page, target, project, { name: "orders", service: { image: "orders:2" } });
  await page.waitForURL(`${target.url}/projects/${project}/services/orders`);
  for (const label of ["Instances", "Image", "Generation", "Address", "Database", "Runs as", "Stopped by", "Report"]) {
    await expect(page.locator("dt", { hasText: label })).toBeVisible();
  }
  await sections(page).getByRole("link", { name: "History" }).click();
  await page.waitForURL(`${target.url}/projects/${project}/services/orders/history`);
  const history = page.getByRole("table");
  await expect(history).toContainText("Applied");
  await expect(history).toContainText(target.kind === "fake" ? "Olive Owner" : "dev");
  await audit(page);
});

test("a web-hosted service shows where it runs, whom it admits, its mounts and no database", async ({ page, target, signIn, unique, audit }) => {
  test.skip(target.kind !== "fake", "mount states are seeded on the fake");
  const org = unique("web-org");
  const project = unique("web-proj");
  seedTenancy(target, { org, project });
  target.controlPlane!.seed({
    services: [
      {
        projectId: project,
        name: "web",
        hosting: "web",
        processPort: 3000,
        callers: ["orders", "*"],
        mounts: [
          { path: "/api/cart", service: "cart", state: "ok" },
          { path: "/api/ledger", service: "ledger", state: "no service" },
        ],
      },
    ],
  });
  await signIn(page, "owner", `/projects/${project}/services/web`);
  const fact = (label: string) => page.locator("dt", { hasText: label }).locator("xpath=following-sibling::dd[1]");
  await expect(fact("Runs as")).toHaveText("Your program beside the platform's proxy, on port 3000");
  await expect(fact("Database")).toHaveText("None");
  await expect(fact("Admits")).toHaveText(`The internet, orders, Every service in Project ${project}`);
  await expect(page.locator('li[data-mount="/api/cart"]')).toHaveText("/api/cart → cart");
  await expect(page.locator('li[data-mount="/api/ledger"]')).toHaveText("/api/ledger → ledger (no service)");
  await audit(page);
});

test("a service known to the installation's broker shows its broker and the topics it declares", async ({ page, target, signIn, unique, audit }) => {
  test.skip(target.kind !== "fake", "a broker's report is seeded on the fake");
  const org = unique("broker-org");
  const project = unique("broker-proj");
  seedTenancy(target, { org, project });
  target.controlPlane!.seed({
    services: [{ projectId: project, name: "wallet", broker: "provisioned", topics: [`${project}.transactions`] }],
  });
  await signIn(page, "owner", `/projects/${project}/services/wallet`);
  const fact = (label: string) => page.locator("dt", { hasText: label }).locator("xpath=following-sibling::dd[1]");
  await expect(fact("Broker")).toHaveText("provisioned");
  await expect(page.locator(`li[data-topic="${project}.transactions"]`)).toHaveText(`${project}.transactions`);
  await audit(page);
});

test("US3-3 a descriptor is applied, and a refused one shows every problem", async ({ page, target, signIn, unique }) => {
  const { project } = await tenancy(page, target, signIn, unique);
  await page.goto(`${target.url}/projects/${project}/services/apply`);
  await page.getByLabel("Descriptor").fill("{ not json");
  await page.getByRole("button", { name: "Apply" }).click();
  await expect(page.getByRole("alert")).toContainText("not valid JSON");

  await apply(page, target, project, { name: "Bad_Name", service: { image: "" } });
  const alert = page.getByRole("alert");
  await expect(alert).toContainText("service name 'Bad_Name' is invalid");
  await expect(alert.getByRole("listitem")).toHaveCount(2);
  await expect(page.getByLabel("Descriptor")).toHaveValue(/Bad_Name/);

  await apply(page, target, project, { name: "good", service: { image: "good:1" } });
  await page.waitForURL(`${target.url}/projects/${project}/services/good`);
  await expect(page.locator("dd", { hasText: "good:1" })).toBeVisible();
  await apply(page, target, project, { name: "good", service: { image: "good:2" } });
  await page.waitForURL(`${target.url}/projects/${project}/services/good`);
  await expect(page.locator("dt", { hasText: "Generation" }).locator("+ dd")).toHaveText("2");
});

test("US3-4 pause, resume, restart, expose and unexpose take effect and show the control plane's state", async ({ page, target, signIn, unique }) => {
  const { project } = await tenancy(page, target, signIn, unique);
  await apply(page, target, project, { name: "ops", service: { image: "ops:1" } });
  await page.waitForURL(`${target.url}/projects/${project}/services/ops`);
  await ops(page).getByRole("button", { name: "Pause" }).click();
  await expect(page.locator(".ac-state-line")).toContainText("Paused");
  await expect(page.locator("dt", { hasText: "Stopped by" }).locator("+ dd")).toHaveText("Its members paused it");
  await ops(page).getByRole("button", { name: "Resume" }).click();
  await expect(page.locator(".ac-state-line")).toContainText(target.kind === "fake" ? "Updating" : /Updating|Ready|Not deployed/);
  await ops(page).getByRole("button", { name: "Restart" }).click();
  await expect(page.locator("dt", { hasText: "Generation" }).locator("+ dd")).toHaveText("2");
  if (target.kind === "fake") {
    // Exposing needs a base domain, which a control plane connected to no cluster does not have.
    await page.getByRole("button", { name: "Expose" }).click();
  await expect(page.getByRole("button", { name: "Unexpose" })).toBeVisible();
  await page.getByRole("button", { name: "Unexpose" }).click();
  await expect(page.getByRole("button", { name: "Expose" })).toBeVisible();
  }
  await sections(page).getByRole("link", { name: "History" }).click();
  await expect(page.getByRole("table")).toContainText("Restarted");
});

test("US3-5 an exposed service's hostname is a link", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "a control plane with no base domain has no hostname to show");
  const { project } = await tenancy(page, target, signIn, unique);
  await apply(page, target, project, { name: "web", service: { image: "web:1" } });
  await page.waitForURL(`${target.url}/projects/${project}/services/web`);
  await page.getByRole("button", { name: "Expose" }).click();
  const link = page.getByRole("link", { name: `https://web-${project}.example.test` });
  await expect(link).toHaveAttribute("href", `https://web-${project}.example.test`);
});

test("US3-7 a deleted service is gone, and applying the name again continues its generation", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "the fake keeps generations across a delete the way the control plane does only on a real journal");
  const { project } = await tenancy(page, target, signIn, unique);
  await apply(page, target, project, { name: "temp", service: { image: "temp:1" } });
  await page.waitForURL(`${target.url}/projects/${project}/services/temp`);
  await page.getByText("Delete", { exact: true }).click();
  await page.getByRole("button", { name: "Delete service" }).click();
  await page.waitForURL(`${target.url}/projects/${project}`);
  await afterProjection(page, async () => {
    await expect(page.locator('tr[data-service="temp"]')).toHaveCount(0, { timeout: 1_000 });
  });
  await apply(page, target, project, { name: "temp", service: { image: "temp:2" } });
  await page.waitForURL(`${target.url}/projects/${project}/services/temp`);
  await expect(page.locator("dd", { hasText: "temp:2" })).toBeVisible();
});

test("US3-8 a suspended service reads Suspended and its operations are refused, not hidden", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "disabling needs an administrator and a seeded tenancy");
  const org = unique("sus-org");
  const project = unique("sus-proj");
  seedTenancy(target, { org, project, service: "held" });
  target.controlPlane!.state.organizations.get(org)!.disabled = true;
  await signIn(page, "owner", `/projects/${project}/services/held`);
  await expect(page.locator(".ac-state-line")).toContainText("Suspended");
  await expect(page.locator("dt", { hasText: "Stopped by" }).locator("+ dd")).toHaveText("Its organization is disabled");
  await ops(page).getByRole("button", { name: "Restart" }).click();
  await expect(page.getByRole("alert")).toContainText("is disabled");
});
