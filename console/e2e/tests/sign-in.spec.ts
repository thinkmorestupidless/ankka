import { test, expect } from "../fixtures.ts";

const jwt = /eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/;

test("US1-1 a deep link sends a stranger to sign in and back to it", async ({ page, target, signIn, audit }) => {
  await signIn(page, "owner", "/?from=deep-link");
  expect(new URL(page.url()).search).toBe("?from=deep-link");
  await expect(page.getByRole("heading", { name: "Organizations" })).toBeVisible();
  await expect(page.getByText("Signed in as")).toContainText(target.kind === "fake" ? "Olive Owner" : "");
  await audit(page);
});

test("US1-2 no token reaches the browser, and the cookie is HttpOnly, same-site and small", async ({ page, context, target, signIn }) => {
  const bodies: string[] = [];
  page.on("response", async (res) => {
    const type = res.headers()["content-type"] ?? "";
    if (/json|html|javascript|text/.test(type) && new URL(res.url()).origin === new URL(target.url).origin) {
      bodies.push(await res.text().catch(() => ""));
    }
  });
  await signIn(page, "owner");
  await page.reload();
  const cookies = await context.cookies(target.url);
  const session = cookies.find((c) => c.name.endsWith("ankka_console"));
  expect(session, "a session cookie").toBeTruthy();
  expect(session!.httpOnly).toBe(true);
  expect(session!.sameSite).toBe("Lax");
  expect(session!.secure).toBe(target.url.startsWith("https:"));
  expect(session!.value.length).toBeLessThan(2048);
  for (const c of cookies) expect(c.value, `cookie ${c.name}`).not.toMatch(jwt);
  const storage = await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage })).catch(() => "");
  expect(storage).not.toMatch(jwt);
  expect(bodies.length).toBeGreaterThan(0);
  for (const b of bodies) expect(b).not.toMatch(jwt);
  expect(await page.evaluate(() => document.cookie).catch(() => "")).toBe("");
});

test("US1-3 sign-out ends the console's session and the identity provider's", async ({ page, signIn, audit }) => {
  await signIn(page, "owner");
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByRole("heading", { name: "You are signed out" })).toBeVisible();
  await audit(page);
  await page.getByRole("link", { name: "Sign in" }).click();
  // The identity provider asks again: its session ended too, so single sign-on does not pass silently.
  await expect(page.locator("#username")).toBeVisible();
});

test("US1-4 an ended session sends the person to sign in and back", async ({ page, target, signIn }) => {
  test.skip(target.kind !== "fake", "ending a session needs the fake identity provider");
  await signIn(page, "member", "/?page=before");
  target.issuer!.expireSession("member");
  // The access token this instance holds is honoured until it expires, as the control plane honours it.
  await page.waitForTimeout(7_000);
  await page.reload();
  await expect(page.locator("#username")).toBeVisible();
  await page.locator("#username").fill("member");
  await page.locator("#password").fill("member");
  await page.locator("#kc-login").click();
  await page.waitForURL((u) => u.search === "?page=before");
});

test("US1-5 a callback with a state the console did not issue is refused", async ({ page, context, target }) => {
  const res = await page.goto(`${target.url}/auth/callback?code=forged&state=forged`);
  expect(res!.status()).toBe(400);
  await expect(page.getByText("was not started here")).toBeVisible();
  // A sign-in this browser did start, answered with someone else's state.
  await page.goto(`${target.url}/auth/sign-in`);
  await expect(page.locator("#username")).toBeVisible();
  const forged = await page.goto(`${target.url}/auth/callback?code=forged&state=not-the-one-issued`);
  expect(forged!.status()).toBe(400);
  const cookies = await context.cookies(target.url);
  expect(cookies.some((c) => c.name.endsWith("ankka_console") && c.value.length > 0)).toBe(false);
});

test("US1-6 a return-to naming another site lands on the front page", async ({ page, target }) => {
  await page.goto(`${target.url}/auth/sign-in?returnTo=${encodeURIComponent("https://evil.example/steal")}`);
  await page.locator("#username").fill(target.userFor("owner"));
  await page.locator("#password").fill(target.passwordFor("owner"));
  await page.locator("#kc-login").click();
  await page.waitForURL((u) => u.origin === new URL(target.url).origin && u.pathname === "/");
});

test("US1-7 a session works on every instance and survives one restarting", async ({ page, target, signIn }) => {
  test.skip(target.kind !== "fake", "two instances behind a proxy are the fake target's");
  await signIn(page, "owner");
  for (let i = 0; i < 4; i++) {
    await page.reload();
    await expect(page.getByRole("heading", { name: "Organizations" })).toBeVisible();
  }
  await target.restartInstance(0);
  for (let i = 0; i < 4; i++) {
    await page.reload();
    await expect(page.getByRole("heading", { name: "Organizations" })).toBeVisible();
  }
});

test("US1-8 an unreachable identity provider is explained and the session is kept", async ({ page, target, signIn, context }) => {
  test.skip(target.kind !== "fake", "failing the identity provider needs the fake");
  await signIn(page, "owner");
  // Let the cached access token lapse so the next page must refresh.
  await page.waitForTimeout(7_000);
  target.issuer!.failNext();
  target.issuer!.failNext();
  const res = await page.reload();
  expect(res!.status()).toBe(503);
  await expect(page.getByRole("heading", { name: "The identity provider is not answering" })).toBeVisible();
  expect((await context.cookies(target.url)).some((c) => c.name.endsWith("ankka_console"))).toBe(true);
  await page.reload();
  await page.reload();
  await expect(page.getByRole("heading", { name: "Organizations" })).toBeVisible();
});
