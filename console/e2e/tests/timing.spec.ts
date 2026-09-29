import { test, expect } from "../fixtures.ts";

const median = (xs: number[]) => {
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.floor(s.length / 2)];
};

// SC-004, against the real stack: the fakes answer in microseconds and would measure nothing.
test("a signed-in page renders in under 500 ms and a navigation shows in under 300 ms, at the median", async ({ page, target, signIn, unique, scriptsOff }) => {
  test.skip(target.kind !== "compose", "timings mean something only against the real control plane");
  test.skip(scriptsOff, "client-side navigation needs scripts");
  test.setTimeout(180_000);
  await signIn(page, "owner");
  const org = unique("timing");
  await page.goto(`${target.url}/organizations/new`);
  await page.getByLabel("Id").fill(org);
  await page.getByLabel("Name").fill("Timing");
  await page.getByRole("button", { name: "Create organization" }).click();
  await page.waitForURL(`${target.url}/organizations/${org}`);

  // Server time: from the request leaving the browser to the first byte of the rendered page.
  const renders: number[] = [];
  for (let i = 0; i < 20; i++) {
    await page.goto(`${target.url}/`);
    renders.push(await page.evaluate(() => {
      const n = performance.getEntriesByType("navigation")[0] as PerformanceNavigationTiming;
      return n.responseStart - n.requestStart;
    }));
  }

  // A client-side navigation: from the click to the next page's heading.
  const navigations: number[] = [];
  for (let i = 0; i < 20; i++) {
    await page.goto(`${target.url}/`);
    const link = page.getByRole("link", { name: "Timing", exact: true }).first();
    await link.waitFor();
    const started = Date.now();
    await link.click();
    await expect(page.getByRole("heading", { level: 1, name: "Timing" })).toBeVisible();
    navigations.push(Date.now() - started);
  }

  const render = median(renders);
  const navigation = median(navigations);
  test.info().annotations.push({ type: "render-median-ms", description: render.toFixed(0) }, { type: "navigation-median-ms", description: String(navigation) });
  process.stdout.write(`timing: render median ${render.toFixed(0)} ms, navigation median ${navigation} ms\n`);
  expect(render).toBeLessThan(500);
  expect(navigation).toBeLessThan(300);
});
