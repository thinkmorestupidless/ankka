import { test, expect, afterProjection } from "../fixtures.ts";

test("US2-7 projects are created, renamed and deleted; one with services cannot be deleted", async ({ page, target, signIn, unique, audit }) => {
  await signIn(page, "owner");
  const org = unique("org");
  await page.goto(`${target.url}/organizations/new`);
  await page.getByLabel("Id").fill(org);
  await page.getByLabel("Name").fill("Projects Org");
  await page.getByRole("button", { name: "Create organization" }).click();
  await page.getByRole("link", { name: "Create a project" }).click();
  await audit(page);
  const project = unique("shop");
  await page.getByLabel("Id").fill(project);
  await page.getByLabel("Name").fill("Shop");
  await page.getByRole("button", { name: "Create project" }).click();
  await page.waitForURL(`${target.url}/projects/${project}`);
  await expect(page.getByRole("heading", { level: 1, name: "Shop" })).toBeVisible();
  await audit(page);

  await page.getByText("Rename or delete").click();
  await page.getByLabel("New name").fill("Storefront");
  await page.getByRole("button", { name: "Rename" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Storefront" })).toBeVisible();

  // With a service, the project cannot be deleted.
  await page.getByRole("link", { name: "Apply a descriptor" }).click();
  await page.getByLabel("Descriptor").fill(JSON.stringify({ name: "cart", service: { image: "cart:1" } }));
  await page.getByRole("button", { name: "Apply" }).click();
  await page.waitForURL(`${target.url}/projects/${project}/services/cart`);
  await page.goto(`${target.url}/projects/${project}`);
  await afterProjection(page, async () => {
    await expect(page.locator(`tr[data-service="cart"]`)).toBeVisible({ timeout: 1_000 });
  });
  await page.getByText("Rename or delete").click();
  await page.getByRole("button", { name: "Delete project" }).click();
  await expect(page.getByRole("alert")).toContainText(`project '${project}' still has 1 service(s)`);

  // Without one, it can.
  await page.goto(`${target.url}/projects/${project}/services/cart`);
  await page.getByText("Delete", { exact: true }).click();
  await page.getByRole("button", { name: "Delete service" }).click();
  await page.waitForURL(`${target.url}/projects/${project}`);
  await afterProjection(page, async () => {
    await expect(page.locator(`tr[data-service="cart"]`)).toHaveCount(0, { timeout: 1_000 });
  });
  await page.getByText("Rename or delete").click();
  await page.getByRole("button", { name: "Delete project" }).click();
  await page.waitForURL(`${target.url}/organizations/${org}`);
  await afterProjection(page, async () => {
    await expect(page.getByRole("link", { name: "Storefront" })).toHaveCount(0, { timeout: 1_000 });
  });
});

test("a registry credential is set and cleared, and its password is never shown", async ({ page, target, signIn, unique }) => {
  await signIn(page, "owner");
  const org = unique("reg");
  await page.goto(`${target.url}/organizations/new`);
  await page.getByLabel("Id").fill(org);
  await page.getByLabel("Name").fill("Registry Org");
  await page.getByRole("button", { name: "Create organization" }).click();
  await page.getByRole("link", { name: "Create a project" }).click();
  const project = unique("private");
  await page.getByLabel("Id").fill(project);
  await page.getByLabel("Name").fill("Private");
  await page.getByRole("button", { name: "Create project" }).click();
  await page.waitForURL(`${target.url}/projects/${project}`);

  const password = `s3cret-${unique("pw")}`;
  const bodies: string[] = [];
  page.on("response", async (r) => bodies.push(await r.text().catch(() => "")));
  await page.getByText("Set a registry credential").click();
  await page.getByLabel("Registry server").fill("ghcr.io");
  await page.getByLabel("Username").fill("robot");
  await page.getByLabel("Password or token").fill(password);
  await page.getByRole("button", { name: "Save credential" }).click();
  await expect(page.getByText("Private images are pulled from")).toContainText("ghcr.io");
  await page.reload();
  for (const b of bodies) expect(b).not.toContain(password);

  if (target.kind === "fake") {
    await page.getByRole("button", { name: "Clear credential" }).click();
    await expect(page.getByText("pull only public images")).toBeVisible();
  }
});

test("a project secret's entries are set and removed, and no value is ever shown", async ({ page, target, signIn, unique }) => {
  // The fakes stand in for a cluster; a control plane run without one answers a write unavailable.
  test.skip(target.kind !== "fake", "writes a project secret, which needs a cluster behind the control plane");
  await signIn(page, "owner");
  const org = unique("sec");
  await page.goto(`${target.url}/organizations/new`);
  await page.getByLabel("Id").fill(org);
  await page.getByLabel("Name").fill("Secrets Org");
  await page.getByRole("button", { name: "Create organization" }).click();
  await page.getByRole("link", { name: "Create a project" }).click();
  const project = unique("billing");
  await page.getByLabel("Id").fill(project);
  await page.getByLabel("Name").fill("Billing");
  await page.getByRole("button", { name: "Create project" }).click();
  await page.waitForURL(`${target.url}/projects/${project}`);
  await expect(page.getByText("No project secrets.")).toBeVisible();

  const value = `sk_live_${unique("v")}`;
  const bodies: string[] = [];
  page.on("response", async (r) => bodies.push(await r.text().catch(() => "")));
  const setEntry = async (name: string, entry: string, v: string) => {
    // With scripts on, a submission keeps the page and the disclosure stays open; clicking its summary
    // again would close it.
    const field = page.getByLabel("Project secret", { exact: true });
    if (!(await field.isVisible())) await page.getByText("Set a project secret entry").click();
    await field.fill(name);
    await page.getByLabel("Entry", { exact: true }).fill(entry);
    await page.getByLabel("Value", { exact: true }).fill(v);
    await page.getByRole("button", { name: "Save entry" }).click();
  };

  await setEntry("checkout", "STRIPE_KEY", value);
  await expect(page.locator('tr[data-secret="checkout"][data-entry="STRIPE_KEY"]')).toBeVisible();
  // A second entry is merged in beside the first.
  await setEntry("checkout", "WEBHOOK_KEY", "whsec_1");
  await expect(page.locator('tr[data-secret="checkout"][data-entry="WEBHOOK_KEY"]')).toBeVisible();
  await expect(page.locator('tr[data-secret="checkout"][data-entry="STRIPE_KEY"]')).toBeVisible();

  // A name the platform uses for its own Secrets is refused, in the control plane's words.
  await setEntry("billing-secret-key", "key", "x");
  await expect(page.getByText("one the platform uses")).toBeVisible();

  await page.getByRole("button", { name: "Remove WEBHOOK_KEY" }).click();
  await expect(page.locator('tr[data-entry="WEBHOOK_KEY"]')).toHaveCount(0);
  await page.reload();
  for (const b of bodies) expect(b).not.toContain(value);
});

test("a project declares a topic once, gives it more partitions, never fewer, and stops declaring it", async ({ page, target, signIn, unique, audit }) => {
  await signIn(page, "owner");
  const org = unique("top");
  await page.goto(`${target.url}/organizations/new`);
  await page.getByLabel("Id").fill(org);
  await page.getByLabel("Name").fill("Topics Org");
  await page.getByRole("button", { name: "Create organization" }).click();
  await page.getByRole("link", { name: "Create a project" }).click();
  const project = unique("money");
  await page.getByLabel("Id").fill(project);
  await page.getByLabel("Name").fill("Money");
  await page.getByRole("button", { name: "Create project" }).click();
  await page.waitForURL(`${target.url}/projects/${project}`);
  await expect(page.getByText("No topics declared.")).toBeVisible();

  const declare = async (name: string, partitions: string) => {
    const field = page.getByLabel("Topic", { exact: true });
    if (!(await field.isVisible())) await page.getByText("Declare a topic").click();
    await field.fill(name);
    await page.getByLabel("Partitions", { exact: true }).fill(partitions);
    await page.getByRole("button", { name: "Declare topic" }).click();
  };
  const row = page.locator('tr[data-topic="transactions"]');

  await declare("transactions", "12");
  await expect(row).toContainText("12");
  await declare("transactions", "24");
  await expect(row).toContainText("24");
  // Never fewer, in the control plane's words.
  await declare("transactions", "6");
  await expect(page.getByText("cannot have fewer")).toBeVisible();
  await expect(row).toContainText("24");
  await audit(page);

  await page.getByRole("button", { name: "Stop declaring transactions" }).click();
  await expect(row).toHaveCount(0);
});
