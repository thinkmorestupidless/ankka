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
  for (const label of ["Instances", "Image", "Generation", "Address", "Database", "Object storage", "Runs as", "Stopped by", "Report"]) {
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

test("a service known to the installation's broker shows its broker and the topics it uses undeclared", async ({ page, target, signIn, unique, audit }) => {
  test.skip(target.kind !== "fake", "a broker's report is seeded on the fake");
  const org = unique("broker-org");
  const project = unique("broker-proj");
  seedTenancy(target, { org, project });
  target.controlPlane!.seed({
    services: [{ projectId: project, name: "wallet", broker: "provisioned", undeclaredTopics: ["entries"] }],
  });
  await signIn(page, "owner", `/projects/${project}/services/wallet`);
  const fact = (label: string) => page.locator("dt", { hasText: label }).locator("xpath=following-sibling::dd[1]");
  await expect(fact("Broker")).toHaveText("provisioned");
  await expect(page.locator('li[data-topic="entries"]')).toHaveText("entries");
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

// ── Rollbacks (feature 033) ───────────────────────────────────────────────────

/** Opens a service's history section from its overview. */
async function openHistory(page: Page, target: Target, project: string) {
  await sections(page).getByRole("link", { name: "History" }).click();
  await page.waitForURL(`${target.url}/projects/${project}/services/cart/history`);
}

/** A service applied twice, `cart:1` then `cart:2`, with its history open. */
async function twoGenerations(page: Page, target: Target, signIn: (p: Page, u: string, path?: string) => Promise<void>, unique: (p: string) => string, history = true) {
  const { project } = await tenancy(page, target, signIn, unique);
  for (const image of ["cart:1", "cart:2"]) {
    await apply(page, target, project, { name: "cart", service: { image } });
    await page.waitForURL(`${target.url}/projects/${project}/services/cart`);
  }
  if (history) await openHistory(page, target, project);
  return project;
}

/** The history's row for a generation. */
function generationRow(page: Page, generation: number) {
  return page.getByRole("table").getByRole("row").filter({ has: page.getByRole("cell", { name: String(generation), exact: true }) });
}

/** Opens the row's rollback control and submits it, waiting for the submission to land. */
async function rollBackTo(page: Page, generation: number) {
  const row = generationRow(page, generation);
  await row.getByText("Roll back", { exact: true }).click();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST"),
    row.getByRole("button", { name: `Roll back to generation ${generation}` }).click(),
  ]);
}

test("RB-1 a member rolls a service back from the console", async ({ page, target, signIn, unique, audit }) => {
  await twoGenerations(page, target, signIn, unique);
  const row = generationRow(page, 1);
  await row.getByText("Roll back", { exact: true }).click();
  // The confirmation names the generation and its image before anything is sent.
  await expect(row).toContainText("Apply generation 1's descriptor again (image cart:1) as a new generation.");
  await Promise.all([page.waitForResponse((r) => r.request().method() === "POST"), row.getByRole("button", { name: "Roll back to generation 1" }).click()]);
  await expect(generationRow(page, 3)).toContainText("Rolled back to generation 1");
  await expect(generationRow(page, 3)).toContainText("cart:1");
  await audit(page);
});

test("RB-2 the console offers a roll back only to a generation that can be rolled back to", async ({ page, target, signIn, unique }) => {
  const project = await twoGenerations(page, target, signIn, unique, false);
  await Promise.all([page.waitForResponse((r) => r.request().method() === "POST"), ops(page).getByRole("button", { name: "Restart" }).click()]);
  await openHistory(page, target, project);
  await expect(page.getByRole("table")).toContainText("Restarted");
  await expect(generationRow(page, 1).getByText("Roll back", { exact: true })).toHaveCount(1);
  // Generation 2 is the descriptor the service has; generation 3 was a restart and recorded none.
  await expect(generationRow(page, 2).getByText("Roll back", { exact: true })).toHaveCount(0);
  await expect(generationRow(page, 3).getByText("Roll back", { exact: true })).toHaveCount(0);
});

test("RB-3 the console shows a refused roll back as the control plane refused it", async ({ page, target, signIn, unique }) => {
  const project = await twoGenerations(page, target, signIn, unique);
  // Another tab gives the service generation 1's descriptor again, so this page's offer is stale.
  const other = await page.context().newPage();
  await apply(other, target, project, { name: "cart", service: { image: "cart:1" } });
  await other.waitForURL(`${target.url}/projects/${project}/services/cart`);
  await other.close();
  await rollBackTo(page, 1);
  await expect(page.getByText("service 'cart' already has the descriptor of generation 1")).toBeVisible();
});

test("RB-4 the console shows the image of each generation that was applied", async ({ page, target, signIn, unique }) => {
  await twoGenerations(page, target, signIn, unique);
  await expect(generationRow(page, 1)).toContainText("cart:1");
  await expect(generationRow(page, 2)).toContainText("cart:2");
  await expect(generationRow(page, 1).locator("code")).toHaveText(/^[0-9a-f]{12}$/);
  // The digest opens the apply page with that generation's descriptor, to read, change and apply.
  await generationRow(page, 1).getByRole("link", { name: "Generation 1's descriptor" }).click();
  await page.waitForURL(/\/services\/apply\?name=cart&generation=1$/);
  await expect(page.getByLabel("Descriptor", { exact: true })).toHaveValue(/"image": "cart:1"/);
});

// features/object-storage/console.feature: the console shows what a service has for object storage
// beside its database, one test per row of the outline.
for (const [state, seed, shown] of [
  ["with a bucket", { provisionObjectStorage: true }, (project: string) => `${project}.reports`],
  ["with an object store of its own", { ownObjectStore: true }, () => "Its own"],
  ["that asked for no bucket", {}, () => "None"],
] as const) {
  test(`the console shows what a service has for object storage beside its database: ${state}`, async ({ page, target, signIn, unique, audit }) => {
    test.skip(target.kind !== "fake", "object storage is seeded on the fake");
    const org = unique("store-org");
    const project = unique("store-proj");
    seedTenancy(target, { org, project });
    target.controlPlane!.seed({ services: [{ projectId: project, name: "reports", ...seed }] });
    await signIn(page, "owner", `/projects/${project}/services/reports`);
    const fact = (label: string) => page.locator("dt", { hasText: label }).locator("xpath=following-sibling::dd[1]");
    await expect(fact("Object storage")).toHaveText(shown(project));
    await expect(fact("Database")).toBeVisible();
    await audit(page);
  });
}

test("a bucket reachable from the internet shows its address beneath its name", async ({ page, target, signIn, unique, audit }) => {
  test.skip(target.kind !== "fake", "object storage is seeded on the fake");
  const org = unique("store-org");
  const project = unique("store-proj");
  seedTenancy(target, { org, project });
  target.controlPlane!.seed({
    services: [{ projectId: project, name: "reports", provisionObjectStorage: true, exposeObjectStorage: true }],
  });
  await signIn(page, "owner", `/projects/${project}/services/reports`);
  await expect(page.locator("[data-bucket-address]")).toHaveText(new RegExp(`/${project}\\.reports$`));
  await audit(page);
});

