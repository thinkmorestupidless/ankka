import { defineConfig, devices } from "@playwright/test";

/**
 * Two projects run every test: once with scripts, once without, because every operation must work
 * as a plain form (FR-026). The target is the in-process fakes by default and the compose stack with
 * CONSOLE_E2E_TARGET=compose; `fixtures.ts` starts or points at whichever it is.
 */
export default defineConfig({
  testDir: "tests",
  outputDir: "test-results",
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [["list"], ["html", { outputFolder: "report", open: "never" }]],
  use: {
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    { name: "scripts-on", use: { ...devices["Desktop Chrome"], javaScriptEnabled: true } },
    { name: "scripts-off", use: { ...devices["Desktop Chrome"], javaScriptEnabled: false } },
  ],
});
