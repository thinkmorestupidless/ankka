import { test, expect, seedTenancy } from "../fixtures.ts";

// A cart instance's document, as the control plane merges it: the endpoint calls the entity.
function cartTopology(ok: number, missing?: { pod: string; status: "unreachable" | "unsupported" | "failed"; problem: string }) {
  return {
    instances: [{ pod: "cart-0", status: "ok" as const }, missing ?? { pod: "cart-1", status: "ok" as const }],
    nodes: [
      { id: "endpoint:/carts", kind: "Endpoint", layer: 0, platform: false, handlers: [{ name: "POST /carts/{cartId}/items", type: "route", streaming: false }] },
      { id: "cart", kind: "EventSourcedEntity", layer: 1, platform: false, handlers: [{ name: "add-item", type: "command" }] },
      { id: "carts-by-customer", kind: "View", layer: 2, platform: false, handlers: [] },
    ],
    declared: [{ from: "cart", to: "carts-by-customer", kind: "events" }],
    calls: [
      {
        from: "endpoint:/carts",
        to: "cart",
        pairs: [
          {
            caller: "POST /carts/{cartId}/items",
            callee: "add-item",
            handled: { ok, refused: 1, failed: 0 },
            unanswered: { timedOut: 0, undelivered: 0 },
            durationMillis: { p50: 1, p99: 2, max: 4, bucketed: true },
            streaming: false,
          },
        ],
      },
    ],
    differences: [{ node: "carts-by-customer", presentOn: ["cart-0"] }],
  };
}

test("TOPO-1 a member reads a deployed service's topology, merged across its instances, followed live", async ({ page, target, signIn, unique, scriptsOff }) => {
  test.skip(target.kind !== "fake", "scripting what instances report needs the fake");
  const org = unique("topo-org");
  const project = unique("topo-proj");
  seedTenancy(target, { org, project });
  target.controlPlane!.seed({ services: [{ projectId: project, name: "cart", instances: 2 }] });
  target.controlPlane!.scriptTopology(project, "cart", cartTopology(5));

  // From the service page, as a member would.
  await signIn(page, "member", `/projects/${project}/services/cart`);
  await page.getByRole("link", { name: "Topology" }).click();
  await expect(page.getByRole("heading", { name: "Topology of cart" })).toBeVisible();
  await expect(page.locator("[data-instances]")).toHaveText("2 of 2 instances answered");
  await expect(page.getByRole("group", { name: /3 components, 1 declared connection, 1 observed call/ })).toBeVisible();
  const row = page.locator('tr[data-pair="POST /carts/{cartId}/items -> add-item"]');
  await expect(row).toContainText("5");
  await expect(page.locator('[data-difference="carts-by-customer"]')).toContainText("cart-0");
  await expect(page.getByText("Calls are observed, not complete.")).toBeVisible();

  if (scriptsOff) return;
  // A count that changes arrives without a reload: the row is waited for, not a timeout.
  const navigations = await page.evaluate(() => performance.getEntriesByType("navigation").length);
  target.controlPlane!.scriptTopology(project, "cart", cartTopology(9));
  await expect(row.locator("td").nth(3)).toHaveText("9", { timeout: 5_000 });
  expect(await page.evaluate(() => performance.getEntriesByType("navigation").length)).toBe(navigations);
});

test("TOPO-2 an instance that did not answer leaves the topology partial and is named", async ({ page, target, signIn, unique }) => {
  test.skip(target.kind !== "fake", "scripting what instances report needs the fake");
  const org = unique("topo-org");
  const project = unique("topo-proj");
  seedTenancy(target, { org, project });
  target.controlPlane!.seed({ services: [{ projectId: project, name: "cart", instances: 2 }] });
  target.controlPlane!.scriptTopology(project, "cart", cartTopology(3, { pod: "cart-1", status: "unreachable", problem: "no answer within 2s" }));

  const responses: string[] = [];
  page.on("response", async (r) => {
    try {
      responses.push(await r.text());
    } catch {
      // a redirect or a stream has no body to read
    }
  });
  await signIn(page, "owner", `/projects/${project}/services/cart/topology`);
  await expect(page.locator("[data-instances]")).toHaveText("1 of 2 instances answered");
  await expect(page.locator(".ac-topology-partial")).toHaveText("Partial");
  await expect(page.locator('[data-missing="cart-1"]')).toContainText("unreachable (no answer within 2s)");
  for (const body of responses) expect(body).not.toMatch(/eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}/);

  // A stranger is shown what a service that does not exist shows.
  await page.context().clearCookies();
  await signIn(page, "outsider", `/projects/${project}/services/cart/topology`);
  const refused = await page.locator("main").innerText();
  await page.goto(`${target.url}/projects/${project}/services/cart`);
  expect(await page.locator("main").innerText()).toBe(refused);
});
