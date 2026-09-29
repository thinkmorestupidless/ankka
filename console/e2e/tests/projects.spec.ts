import { test, expect } from "../fixtures.ts";

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
  await page.getByText("Rename or delete").click();
  await page.getByRole("button", { name: "Delete project" }).click();
  await expect(page.getByRole("alert")).toContainText(`project '${project}' still has 1 service(s)`);

  // Without one, it can.
  await page.goto(`${target.url}/projects/${project}/services/cart`);
  await page.getByText("Delete", { exact: true }).click();
  await page.getByRole("button", { name: "Delete service" }).click();
  await page.waitForURL(`${target.url}/projects/${project}`);
  await page.getByText("Rename or delete").click();
  await page.getByRole("button", { name: "Delete project" }).click();
  await page.waitForURL(`${target.url}/organizations/${org}`);
  await expect(page.getByRole("link", { name: "Storefront" })).toHaveCount(0);
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
