/**
 * Feature 042's console scenarios (`features/erasure/places.feature`): a member asks for an erasure
 * request, reads a project's erasure requests with each service's completion and each hold's
 * not-before date, fetches an erasure certificate, and only an owner overrides a hold. Each test is
 * named after its scenario, and its example where the scenario is an outline.
 */
import { readFileSync } from "node:fs";
import type { Page } from "@playwright/test";
import { test, expect, seedTenancy, body, ops } from "../fixtures.ts";
import type { Target } from "../target.ts";

/** A project "brand" (fresh per test) with the service "players", on an installation whose erasure is on. */
function brand(target: Target, unique: (prefix: string) => string, erasures: { subject: string; state: "held" | "applied"; notBefore?: string }[] = []) {
  const org = unique("erasure-org");
  const project = unique("brand");
  seedTenancy(target, { org, project, service: "players" });
  target.controlPlane!.seed({ erasure: true, erasures: erasures.map((e) => ({ projectId: project, ...e })) });
  return project;
}

/** From the project's page to one erasure request's, by the link in its row. */
async function openRequest(page: Page, subject: string) {
  await body(page).getByRole("link", { name: subject }).click();
  await expect(page.getByRole("heading", { level: 1 })).toContainText(`Erasure request for ${subject}`);
}

test("a member asks for an erasure request through the console", async ({ page, target, signIn, unique, audit }) => {
  test.skip(target.kind !== "fake", "the installation's erasure is switched on in the fake");
  const project = brand(target, unique);
  await signIn(page, "member", `/projects/${project}`);
  await ops(page).locator("summary", { hasText: "Ask for an erasure" }).click();
  await ops(page).getByLabel("Data subject").fill("player/8c1f");
  await ops(page).getByRole("button", { name: "Ask for an erasure" }).click();

  // Accepted: the console lands on the request's own page, by its id, naming who asked.
  await expect(page.getByRole("heading", { level: 1 })).toContainText("Erasure request for player/8c1f");
  const facts = body(page).locator("dl[data-erasure]");
  await expect(facts).toHaveAttribute("data-state", /^(applying|applied)$/);
  await expect(facts.locator("[data-asked-by]")).toHaveAttribute("data-asked-by", "member");
  await expect(facts.locator("[data-asked-by]")).toContainText("by member");
  await audit(page);
});

test("the console shows the erasure requests of a project with where each one stands and each service's completion", async ({ page, target, signIn, unique, audit }) => {
  test.skip(target.kind !== "fake", "the erasure requests are seeded on the fake");
  const project = brand(target, unique, [
    { subject: "player/8c1f", state: "applied" },
    { subject: "player/41d2", state: "held", notBefore: "2027-01-15T00:00:00Z" },
  ]);
  await signIn(page, "member", `/projects/${project}`);
  const applied = body(page).locator('tr[data-subject="player/8c1f"]');
  await expect(applied).toHaveAttribute("data-state", "applied");
  await expect(applied.locator('[data-completion="players"]')).toContainText("players: completed");
  const held = body(page).locator('tr[data-subject="player/41d2"]');
  await expect(held).toHaveAttribute("data-state", "held");
  await expect(held.locator("[data-not-before]")).toContainText("15 Jan 2027");
  await audit(page);
});

test("a member fetches the erasure certificate of an applied erasure request through the console", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "the applied erasure request is seeded on the fake");
  const project = brand(target, unique, [{ subject: "player/8c1f", state: "applied" }]);
  await signIn(page, "member", `/projects/${project}`);
  await openRequest(page, "player/8c1f");
  const id = await body(page).locator("dl[data-erasure]").getAttribute("data-erasure");

  const [download] = await Promise.all([page.waitForEvent("download"), body(page).getByRole("link", { name: "Download the erasure certificate" }).click()]);
  expect(download.suggestedFilename()).toBe(`erasure-certificate-${id}.json`);
  const certificate = JSON.parse(readFileSync((await download.path())!, "utf8")) as { request: string; project: string; subject: string; services: { service: string }[] };
  expect(certificate.request).toBe(id);
  expect(certificate.project).toBe(project);
  expect(certificate.subject).toBe("player/8c1f");
  expect(certificate.services.map((s) => s.service)).toEqual(["players"]);
});

test("only an owner overrides a hold, an owner through the console", async ({ page, target, signIn, unique, audit }) => {
  test.skip(target.kind !== "fake", "the held erasure request is seeded on the fake");
  const project = brand(target, unique, [{ subject: "player/8c1f", state: "held" }]);
  await signIn(page, "owner", `/projects/${project}`);
  await openRequest(page, "player/8c1f");
  await audit(page);
  await ops(page).getByLabel("Reason for the override").fill("regulator order 2026-114");
  await ops(page).getByRole("button", { name: "Override hold" }).click();

  // Applied at once, with the owner and the reason recorded.
  const facts = body(page).locator("dl[data-erasure]");
  await expect(facts).toHaveAttribute("data-state", "applied");
  await expect(facts.locator("[data-override]")).toContainText("by owner");
  await expect(facts.locator("[data-override]")).toContainText("regulator order 2026-114");
  await expect(body(page).locator('tr[data-completion="players"]')).toBeVisible();
});

test("only an owner overrides a hold, a member who is not an owner through the console", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "the held erasure request is seeded on the fake");
  const project = brand(target, unique, [{ subject: "player/8c1f", state: "held" }]);
  await signIn(page, "member", `/projects/${project}`);
  await openRequest(page, "player/8c1f");
  await ops(page).getByLabel("Reason for the override").fill("regulator order 2026-114");
  await ops(page).getByRole("button", { name: "Override hold" }).click();

  // Refused in the control plane's words, beside the form, and the request is still held.
  await expect(ops(page).getByRole("alert")).toContainText("owner role required");
  await expect(body(page).locator("dl[data-erasure]")).toHaveAttribute("data-state", "held");
  await expect(ops(page).getByLabel("Reason for the override")).toHaveValue("regulator order 2026-114");
});

test("a member changes the hold of a held erasure request, then withdraws it", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "the held erasure request is seeded on the fake");
  const project = brand(target, unique, [{ subject: "player/41d2", state: "held" }]);
  await signIn(page, "member", `/projects/${project}`);
  await openRequest(page, "player/41d2");
  await ops(page).locator("summary", { hasText: "Change the hold" }).click();
  await ops(page).getByLabel("Not-before date").fill("2027-03-01");
  await ops(page).getByLabel("Reason", { exact: true }).fill("litigation hold");
  await ops(page).getByRole("button", { name: "Change the hold" }).click();
  await expect(body(page).locator("[data-not-before]")).toContainText("1 Mar 2027");
  await expect(body(page).locator("[data-not-before]")).toContainText("litigation hold");

  await ops(page).getByRole("button", { name: "Withdraw" }).click();
  await expect(body(page).locator("dl[data-erasure]")).toHaveAttribute("data-state", "withdrawn");
  // A withdrawn request offers nothing more, and its certificate is never issued.
  await expect(ops(page).getByRole("button", { name: "Withdraw" })).toHaveCount(0);
});
