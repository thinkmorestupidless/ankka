import { test, expect, seedTenancy } from "../fixtures.ts";

test("US4-1 an owner sees members and pending invitations", async ({ page, target, signIn, unique, audit }) => {
  test.skip(target.kind !== "fake", "seeded memberships need the fake");
  const org = unique("mem-org");
  seedTenancy(target, { org, project: unique("p") });
  target.controlPlane!.state.organizations.get(org)!.invitations.set("new@example.test", { role: "member", invitedAt: new Date().toISOString(), invitedBy: "Olive Owner" });
  await signIn(page, "owner", `/organizations/${org}/members`);
  await expect(page.locator('tr[data-member="owner"]')).toContainText("Owner");
  await expect(page.locator('tr[data-member="member"]')).toContainText("Member");
  await expect(page.locator('tr[data-invitation="new@example.test"]')).toContainText("Olive Owner");
  await audit(page);
  await page.locator('tr[data-invitation="new@example.test"]').getByRole("button", { name: "Withdraw" }).click();
  await expect(page.locator('tr[data-invitation="new@example.test"]')).toHaveCount(0);
  await expect(page.getByText("No invitations are waiting.")).toBeVisible();
});

test("US4-2 an invitation is claimed when the invited person signs in", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "a second person needs the fake's users");
  const org = unique("inv-org");
  target.controlPlane!.seed({ organizations: [{ id: org, name: `Org ${org}`, owners: ["owner"] }] });
  await signIn(page, "owner", `/organizations/${org}/members`);
  await page.getByLabel("Email").fill("outsider@example.test");
  await page.getByRole("button", { name: "Invite" }).click();
  await expect(page.locator('tr[data-invitation="outsider@example.test"]')).toBeVisible();

  await page.context().clearCookies();
  await signIn(page, "outsider");
  await expect(page.getByRole("row", { name: new RegExp(`Org ${org}`) })).toContainText("Member");
});

test("US4-3 roles change and members are removed; the last owner cannot be", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "seeded memberships need the fake");
  const org = unique("role-org");
  seedTenancy(target, { org, project: unique("p") });
  await signIn(page, "owner", `/organizations/${org}/members`);
  await page.locator('tr[data-member="owner"]').getByRole("button", { name: "Make member" }).click();
  await expect(page.getByRole("alert")).toContainText("last owner");

  await page.locator('tr[data-member="member"]').getByRole("button", { name: "Make owner" }).click();
  await expect(page.locator('tr[data-member="member"]')).toContainText("Owner");
  await page.locator('tr[data-member="member"]').getByRole("button", { name: "Remove" }).click();
  await expect(page.locator('tr[data-member="member"]')).toHaveCount(0);
});

test("US4-4 a member sees the list and no controls; a machine is shown as one", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "seeded memberships need the fake");
  const org = unique("view-org");
  seedTenancy(target, { org, project: unique("p") });
  target.controlPlane!.state.organizations.get(org)!.members.set("token:abc123", { role: "member", display: "ci", since: new Date().toISOString() });
  await signIn(page, "member", `/organizations/${org}/members`);
  await expect(page.locator('tr[data-member="owner"]')).toBeVisible();
  await expect(page.getByRole("button", { name: /Make|Remove|Invite/ })).toHaveCount(0);
  await expect(page.locator('tr[data-member="token:abc123"]')).toContainText("Deploy token");
});
