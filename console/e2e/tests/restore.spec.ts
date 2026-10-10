import { test, expect, seedTenancy } from "../fixtures.ts";

test("an owner restores a project to a moment, reads the report, and switches one service to it", async ({ page, target, signIn, unique, audit }) => {
  test.skip(target.kind !== "fake", "a backed-up project with two services is seeded on the fake");
  const org = unique("restore-org");
  const project = unique("restore-proj");
  seedTenancy(target, { org, project });
  target.controlPlane!.seed({
    services: [
      { projectId: project, name: "wallet", image: "wallet:1", database: "ready" },
      { projectId: project, name: "rewards", image: "rewards:1", database: "ready" },
    ],
  });
  await signIn(page, "owner", `/projects/${project}`);
  await expect(page.locator('[data-backed-up="yes"]')).toBeVisible();
  await expect(page.locator('tr[data-line="ankka-db"]')).toContainText("backing up");

  // A moment an hour ago, inside the window.
  const hourAgo = new Date(Date.now() - 3_600_000);
  const local = new Date(hourAgo.getTime() - hourAgo.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
  await page.getByText("Restore to a moment").click();
  await page.getByLabel("Moment").fill(local);
  await page.getByRole("button", { name: "Restore", exact: true }).click();
  const restore = page.locator("[data-restore]").first();
  await expect(restore).toHaveAttribute("data-phase", "Verified");
  await expect(restore.locator('[data-verified="wallet"]')).toContainText("journal rows");
  await audit(page);

  // One service moves; the other stays, and the status names both clusters.
  await restore.getByRole("button", { name: /^Switch rewards to / }).click();
  await expect(page.locator("[data-restore]").first()).toHaveAttribute("data-phase", "InUse");
  await expect(page.locator('[data-cluster="ankka-db"]')).toContainText("wallet");
  await expect(page.locator('[data-cluster][data-phase="restore"]')).toContainText("rewards");
});

test("a member rehearses a restore, and the rehearsal is listed with how long it took", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "the fake completes a rehearsal at once");
  const org = unique("rehearse-org");
  const project = unique("rehearse-proj");
  seedTenancy(target, { org, project });
  await signIn(page, "member", `/projects/${project}`);
  await expect(page.getByText("No rehearsals yet")).toBeVisible();
  await page.getByRole("button", { name: "Rehearse a restore" }).click();
  await expect(page.locator("[data-rehearsal]").first()).toContainText("in 118s");
});

test("a member sets what the project asks of its database, and it is in the database's history", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "the fake keeps the setting");
  const org = unique("database-org");
  const project = unique("database-proj");
  seedTenancy(target, { org, project });
  await signIn(page, "member", `/projects/${project}`);
  await expect(page.locator("[data-setting]")).toContainText("a primary alone");
  await page.getByText("Replicas, retention and rehearsals").click();
  await page.getByLabel("Replicas").fill("0");
  await page.getByLabel("Synchronous").check();
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByText("a synchronous database needs at least one replica to wait for")).toBeVisible();
  await page.getByLabel("Replicas").fill("2");
  await page.getByLabel("Synchronous").check();
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.locator("[data-setting]")).toContainText("2 replicas, synchronous");
  await expect(page.locator('[data-history="database-set"]')).toContainText("2 replicas, synchronous");
});

test("an owner issues the backup credential again, and the database's history says so", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "the fake counts the generations");
  const org = unique("credential-org");
  const project = unique("credential-proj");
  seedTenancy(target, { org, project });
  await signIn(page, "owner", `/projects/${project}`);
  await page.getByRole("button", { name: "Issue the backup credential again" }).click();
  await expect(page.locator('[data-history="backup-credential-reissued"]')).toContainText("generation 1");
});

test("a held control plane lists what differs, and a platform administrator releases it", async ({ page, target, signIn }) => {
  test.skip(target.kind !== "fake", "the fake is seeded held");
  target.controlPlane!.seed({
    held: { targetTime: "2026-10-08T09:20:00Z", services: [{ project: "shop", service: "cart", recordedImage: "cart:1", clusterImage: "cart:2" }] },
  });
  await signIn(page, "dev", "/");
  await expect(page.locator('[data-held="yes"]')).toBeVisible();
  await expect(page.locator('[data-differs="shop/cart"]')).toContainText("the cluster runs cart:2");
  await page.getByRole("button", { name: "Release the control plane" }).click();
  await expect(page.locator('[data-held="released"]')).toBeVisible();
});
