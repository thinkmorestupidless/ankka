import { test, expect, seedTenancy, ops, sections } from "../fixtures.ts";
import type { Page } from "@playwright/test";
import type { Target } from "../target.ts";

// Feature 039: a service's bucket from the console — its credential issued again, a move from Garage to
// Google Cloud Storage, the installation's settings reapplied, and where a project's new buckets are made.
// Every one is the control plane's to allow, and its refusal is shown as it gave it.

const fact = (page: Page, label: string) => page.locator("dt", { hasText: label }).locator("xpath=following-sibling::dd[1]");

/** Posts a form by clicking `button`, and waits for the console to have posted it. */
async function post(page: Page, button: ReturnType<Page["getByRole"]>) {
  await Promise.all([page.waitForResponse((r) => r.request().method() === "POST"), button.click()]);
}

/** Opens a service's history section from its overview. */
async function history(page: Page, target: Target, project: string, name: string) {
  await sections(page).getByRole("link", { name: "History" }).click();
  await page.waitForURL(`${target.url}/projects/${project}/services/${name}/history`);
}

/** A tenancy on the fake whose installation keeps new buckets in `store`, given back to Garage afterwards. */
async function withStore(target: Target, store: "garage" | "gcs", run: () => Promise<void>) {
  target.controlPlane!.seed({ objectStore: store });
  try {
    await run();
  } finally {
    target.controlPlane!.seed({ objectStore: "garage" });
  }
}

test("a member issues a service's storage credential again, and its history records it", async ({ page, target, signIn, unique, audit }) => {
  test.skip(target.kind !== "fake", "object storage is seeded on the fake");
  const org = unique("cred-org");
  const project = unique("cred-proj");
  seedTenancy(target, { org, project });
  target.controlPlane!.seed({ services: [{ projectId: project, name: "reports", provisionObjectStorage: true }] });
  await signIn(page, "owner", `/projects/${project}/services/reports`);
  await expect(fact(page, "Object store")).toHaveText("Garage");
  await audit(page);
  await post(page, ops(page).getByRole("button", { name: "Issue credential again" }));
  await page.waitForURL(`${target.url}/projects/${project}/services/reports`);
  await history(page, target, project, "reports");
  await expect(page.getByRole("table")).toContainText("Storage credential issued again");
});

test("a service with no bucket is offered nothing to do about one", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "object storage is seeded on the fake");
  const org = unique("none-org");
  const project = unique("none-proj");
  seedTenancy(target, { org, project, service: "plain" });
  await signIn(page, "owner", `/projects/${project}/services/plain`);
  await expect(ops(page).getByRole("button", { name: "Restart" })).toBeVisible();
  await expect(ops(page).getByRole("button", { name: "Issue credential again" })).toHaveCount(0);
  await expect(fact(page, "Object store")).toHaveCount(0);
});

test("where the installation keeps buckets in Garage, a move and a location are refused, saying why", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "object storage is seeded on the fake");
  const org = unique("garage-org");
  const project = unique("garage-proj");
  seedTenancy(target, { org, project });
  target.controlPlane!.seed({ services: [{ projectId: project, name: "reports", provisionObjectStorage: true }] });
  await signIn(page, "owner", `/projects/${project}/services/reports`);
  await ops(page).getByText("Move to Google Cloud Storage").click();
  await ops(page).getByRole("button", { name: "Move bucket" }).click();
  await expect(page.getByText("the installation keeps new buckets in Garage; there is nowhere to move a bucket to")).toBeVisible();

  await page.goto(`${target.url}/projects/${project}`);
  await page.getByText("Choose where new buckets are made").click();
  await page.getByRole("textbox", { name: "Location" }).fill("europe-west6");
  await page.getByRole("button", { name: "Set location" }).click();
  await expect(page.getByText("the installation keeps new buckets in Garage, which has no location to choose").first()).toBeVisible();
  await page.goto(`${target.url}/projects/${project}`);
  await page.getByRole("button", { name: "Use the installation's location" }).click();
  await expect(page.getByText("the installation keeps new buckets in Garage, which has no location to choose")).toBeVisible();
  expect(target.controlPlane!.state.projects.get(project)!.location).toBeUndefined();
});

test("a bucket in Garage is moved to Google Cloud Storage with a write pause bound, and a bound out of range is refused", async ({ page, target, signIn, unique, audit }) => {
  test.skip(target.kind !== "fake", "object storage is seeded on the fake");
  await withStore(target, "gcs", async () => {
    const org = unique("move-org");
    const project = unique("move-proj");
    seedTenancy(target, { org, project });
    target.controlPlane!.seed({ services: [{ projectId: project, name: "reports", provisionObjectStorage: true, objectStore: "garage" }] });
    await signIn(page, "owner", `/projects/${project}/services/reports`);
    await ops(page).getByText("Move to Google Cloud Storage").click();
    await ops(page).getByLabel("Write pause bound").fill("30s");
    await ops(page).getByRole("button", { name: "Move bucket" }).click();
    await expect(page.getByText("writePauseBound '30s' is outside one minute to 24 hours")).toBeVisible();
    // The refused bound is kept in the form, to be corrected rather than typed again.
    await expect(ops(page).getByLabel("Write pause bound")).toHaveValue("30s");
    await ops(page).getByLabel("Write pause bound").fill("30m");
    await post(page, ops(page).getByRole("button", { name: "Move bucket" }));
    await expect(fact(page, "Bucket move")).toHaveText(
      "waiting for its bucket in Google Cloud Storage; its write pause may last 30 minutes at most",
    );
    await audit(page);
    await history(page, target, project, "reports");
    await expect(page.getByRole("table")).toContainText("Bucket move asked for");
  });
});

test("a bucket in Google Cloud Storage shows where it is, and has the installation's settings reapplied", async ({ page, target, signIn, unique, audit }) => {
  test.skip(target.kind !== "fake", "object storage is seeded on the fake");
  await withStore(target, "gcs", async () => {
    const org = unique("gcs-org");
    const project = unique("gcs-proj");
    seedTenancy(target, { org, project });
    target.controlPlane!.seed({
      services: [{ projectId: project, name: "files", provisionObjectStorage: true, objectStore: "gcs", bucketLocation: "europe-west6" }],
    });
    await signIn(page, "owner", `/projects/${project}/services/files`);
    await expect(fact(page, "Object store")).toHaveText("Google Cloud Storage");
    await expect(fact(page, "Bucket location")).toHaveText("europe-west6");
    await expect(fact(page, "Deleted objects")).toHaveText("Recoverable for 7 days");
    // A bucket already there has nothing to move.
    await expect(ops(page).getByText("Move to Google Cloud Storage")).toHaveCount(0);
    await audit(page);
    await post(page, ops(page).getByRole("button", { name: "Reapply bucket settings" }));
    await page.waitForURL(`${target.url}/projects/${project}/services/files`);
    await history(page, target, project, "files");
    await expect(page.getByRole("table")).toContainText("Bucket settings reapplied");
  });
});

test("a project's location for new buckets is chosen, and the installation's is used again when cleared", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "the installation's object store is set on the fake");
  await withStore(target, "gcs", async () => {
    const org = unique("loc-org");
    const project = unique("loc-proj");
    seedTenancy(target, { org, project });
    await signIn(page, "owner", `/projects/${project}`);
    await page.getByText("Choose where new buckets are made").click();
    await page.getByRole("textbox", { name: "Location" }).fill("europe-west6");
    await post(page, page.getByRole("button", { name: "Set location" }));
    await page.waitForURL(`${target.url}/projects/${project}`);
    await expect.poll(() => target.controlPlane!.state.projects.get(project)!.location).toBe("europe-west6");
    await post(page, page.getByRole("button", { name: "Use the installation's location" }));
    await expect.poll(() => target.controlPlane!.state.projects.get(project)!.location).toBeUndefined();
  });
});
