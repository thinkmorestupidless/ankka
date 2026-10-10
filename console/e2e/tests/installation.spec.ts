import { test, expect, body, seedTenancy } from "../fixtures.ts";
import type { Page } from "@playwright/test";
import type { Target } from "../target.ts";

// Feature 044: what the installation is, on the front page — its version, and its cloud provider,
// account and location when it names one. The wrapping key's name is the control plane's to show,
// and it shows it only to an owner of an organization or a platform administrator.

const fact = (page: Page, label: string) =>
  body(page).locator("dt", { hasText: label }).locator("xpath=following-sibling::dd[1]");

const gcp = { provider: "gcp", account: "acme-production", location: "europe-west2", kmsKey: "keys/ankka" };

/** An installation on the fake whose cloud is `cloud`, given back to none afterwards. */
async function withCloud(target: Target, cloud: typeof gcp | null, run: () => Promise<void>) {
  target.controlPlane!.seed({ cloud });
  try {
    await run();
  } finally {
    target.controlPlane!.seed({ cloud: null });
  }
}

test("a member reads the installation's version, cloud account and location on the front page, and not its wrapping key", async ({
  page,
  target,
  signIn,
  audit,
}) => {
  test.skip(target.kind !== "fake", "the installation's cloud is seeded on the fake");
  await withCloud(target, gcp, async () => {
    // A person no test makes an owner of anything: the key's name is not theirs to read.
    await signIn(page, "outsider");
    await expect(fact(page, "Platform version")).toHaveText("0.0.0-fake");
    await expect(fact(page, "Cloud")).toHaveText("gcp, account acme-production");
    await expect(fact(page, "Location")).toHaveText("europe-west2");
    await expect(body(page).locator("dt", { hasText: "Wrapping key" })).toHaveCount(0);
    await audit(page);
  });
});

test("an owner of an organization reads the installation's wrapping key", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "the installation's cloud is seeded on the fake");
  seedTenancy(target, { org: unique("inst-org"), project: unique("inst-proj") });
  await withCloud(target, gcp, async () => {
    await signIn(page, "owner");
    await expect(fact(page, "Wrapping key")).toHaveText("keys/ankka");
  });
});

test("an installation that names no cloud says everything is served by the installation itself", async ({ page, target, signIn }) => {
  test.skip(target.kind !== "fake", "the installation's cloud is seeded on the fake");
  await withCloud(target, null, async () => {
    await signIn(page, "owner");
    await expect(fact(page, "Cloud")).toHaveText("None: everything is served by the installation itself");
    await expect(body(page).locator("dt", { hasText: "Location" })).toHaveCount(0);
  });
});
