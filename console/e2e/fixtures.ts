/**
 * The suite's fixtures: the target (per worker), signing in through the identity provider's form
 * exactly as a person does, fresh ids so tests never collide, and an accessibility audit run on every
 * page a test visits.
 */
import { test as base, expect, type Page } from "@playwright/test";
import { AxeBuilder } from "@axe-core/playwright";
import { startTarget, type Target } from "./target.ts";

export { expect };

export interface Fixtures {
  signIn(page: Page, user: string, path?: string): Promise<void>;
  /** A fresh id with the given prefix: `org-3fa9`. */
  unique(prefix: string): string;
  /** Fails the test on any WCAG 2.1 AA violation on the current page. */
  audit(page: Page): Promise<void>;
  scriptsOff: boolean;
}

export const test = base.extend<Fixtures, { target: Target }>({
  target: [
    async ({}, use) => {
      const target = await startTarget();
      await use(target);
      await target.close();
    },
    { scope: "worker", timeout: 120_000 },
  ],

  scriptsOff: async ({ javaScriptEnabled }, use) => {
    await use(javaScriptEnabled === false);
  },

  signIn: async ({ target }, use) => {
    await use(async (page, user, path = "/") => {
      await page.goto(target.url + path);
      await page.locator("#username").fill(target.userFor(user));
      await page.locator("#password").fill(target.passwordFor(user));
      await page.locator("#kc-login").click();
      await page.waitForURL((url) => url.origin === new URL(target.url).origin && !url.pathname.includes("/auth/"));
    });
  },

  unique: async ({}, use) => {
    await use((prefix) => `${prefix}-${Math.random().toString(36).slice(2, 7)}`);
  },

  // axe runs inside the page, so it needs scripts; the server renders the same markup either way,
  // so the scripts-on project's audit covers both.
  audit: async ({ javaScriptEnabled }, use) => {
    await use(async (page) => {
      if (javaScriptEnabled === false) return;
      const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
      const summary = results.violations.map((v) => `${v.id}: ${v.help} (${v.nodes.map((n) => n.target.join(" ")).join(", ")})`);
      expect(summary, `accessibility violations on ${page.url()}`).toEqual([]);
    });
  },
});

/** Seeds the fake control plane with an organization owned by `owner` (and `member` as a member), a project and a service. */
export function seedTenancy(target: Target, ids: { org: string; project: string; service?: string }) {
  if (!target.controlPlane) return;
  target.controlPlane.seed({
    organizations: [{ id: ids.org, name: `Org ${ids.org}`, owners: ["owner"], members: ["member"] }],
    projects: [{ id: ids.project, name: `Project ${ids.project}`, organizationId: ids.org }],
    services: ids.service ? [{ projectId: ids.project, name: ids.service }] : [],
  });
}
