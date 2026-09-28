import { readFileSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { test, expect } from "@playwright/test";
import { scenarios } from "ankka-console/testing";

// Every acceptance scenario has a test whose title starts with its id. This reads the suite's own
// sources rather than a list kept beside them, so a scenario cannot be ticked off without a test.
test("every acceptance scenario has a test", () => {
  const dir = fileURLToPath(new URL(".", import.meta.url));
  const sources = readdirSync(dir)
    .filter((f) => f.endsWith(".spec.ts") && f !== "scenarios.spec.ts")
    .map((f) => readFileSync(dir + f, "utf8"))
    .join("\n");
  const missing = Object.keys(scenarios).filter((id) => !new RegExp(`test\\(\\s*["'\`]${id.replace("-", "\\-")} `).test(sources));
  expect(missing, "scenarios with no test").toEqual([]);
});
