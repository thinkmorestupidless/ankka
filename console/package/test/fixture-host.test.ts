/**
 * A second host, built from the package as it is published: another prefix, another layout, sessions
 * in a store of its own, a panel, an action and a hidden operation. Every operation a person performs
 * in the stories for organizations, projects, services, members and tokens is driven through it,
 * with no change to the package — the boundary a product like ankka-cloud crosses.
 */
import { after, before, describe, test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createServer, type AddressInfo } from "node:net";
import { fileURLToPath } from "node:url";
import { fakeControlPlane, fakeIssuer, type FakeControlPlane, type FakeIssuer } from "ankka-console/testing";
import { createConsoleServer, type ConsoleServer } from "../src/server/create-console-server.ts";

const packageDir = fileURLToPath(new URL("..", import.meta.url));
const hostDir = fileURLToPath(new URL("./fixture-host/", import.meta.url));

async function freePort(): Promise<number> {
  const s = createServer();
  await new Promise<void>((r) => s.listen(0, "127.0.0.1", r));
  const port = (s.address() as AddressInfo).port;
  await new Promise<void>((r) => s.close(() => r()));
  return port;
}

/** A browser without scripts: follows nothing on its own, keeps cookies, sends the origin. */
class Browser {
  readonly cookies = new Map<string, string>();
  readonly origin: string;
  constructor(origin: string) {
    this.origin = origin;
  }

  async request(url: string, init: RequestInit = {}): Promise<Response> {
    const u = new URL(url, this.origin);
    const headers = new Headers(init.headers);
    const jar = [...this.cookies.entries()].map(([k, v]) => `${k}=${v}`).join("; ");
    if (jar) headers.set("cookie", jar);
    if (init.method === "POST") headers.set("origin", this.origin);
    const res = await fetch(u, { ...init, headers, redirect: "manual" });
    for (const c of res.headers.getSetCookie()) {
      const [pair, ...attrs] = c.split(";");
      const [name, ...value] = pair.split("=");
      if (attrs.some((a) => /max-age=0/i.test(a.trim()))) this.cookies.delete(name.trim());
      else this.cookies.set(name.trim(), value.join("="));
    }
    return res;
  }

  /** GET, following redirects the way a browser does, across origins; returns the final page. */
  async follow(url: string): Promise<{ url: string; status: number; html: string }> {
    let current = new URL(url, this.origin).toString();
    for (let i = 0; i < 10; i++) {
      const res = await this.request(current);
      const location = res.headers.get("location");
      if (res.status >= 300 && res.status < 400 && location) {
        await res.body?.cancel();
        current = new URL(location, current).toString();
        continue;
      }
      return { url: current, status: res.status, html: await res.text() };
    }
    throw new Error(`too many redirects from ${url}`);
  }

  /** Posts a form the way a page with scripts off does, then follows where it leads. */
  async submit(path: string, fields: Record<string, string>): Promise<{ url: string; status: number; html: string }> {
    const res = await this.request(path, { method: "POST", body: new URLSearchParams(fields), headers: { "content-type": "application/x-www-form-urlencoded" } });
    const location = res.headers.get("location");
    if (res.status >= 300 && res.status < 400 && location) {
      await res.body?.cancel();
      return this.follow(new URL(location, new URL(path, this.origin)).toString());
    }
    return { url: new URL(path, this.origin).toString(), status: res.status, html: await res.text() };
  }
}

/** Each declaration of `property` in a stylesheet, with its selector and whether a layer holds it. */
function layersOf(stylesheet: string, property: string): { selector: string; layered: boolean }[] {
  const css = stylesheet.replace(/\/\*[\s\S]*?\*\//g, "");
  const found: { selector: string; layered: boolean }[] = [];
  const stack: { layer: boolean; selector: string }[] = [];
  let start = 0;
  for (let i = 0; i < css.length; i++) {
    const c = css[i];
    if (c === "{") {
      const head = css.slice(start, i).trim();
      stack.push({ layer: head.startsWith("@layer"), selector: head });
      start = i + 1;
    } else if (c === "}") {
      stack.pop();
      start = i + 1;
    } else if (c === ";") {
      start = i + 1;
    } else if (css.startsWith(property, i) && stack.length > 0) {
      found.push({ selector: stack[stack.length - 1].selector, layered: stack.some((s) => s.layer) });
    }
  }
  return found;
}

describe("a second host built on the package", () => {
  let issuer: FakeIssuer;
  let cp: FakeControlPlane;
  let server: ConsoleServer;
  let origin: string;
  let browser: Browser;

  before(async () => {
    execFileSync(process.execPath, ["scripts/build.ts"], { cwd: packageDir, stdio: "pipe" });
    execFileSync(process.execPath, [fileURLToPath(new URL("../../node_modules/@react-router/dev/bin.cjs", import.meta.url)), "build"], {
      cwd: hostDir,
      stdio: "pipe",
    });
    issuer = await fakeIssuer({ clientId: "ankka-console", clientSecret: "dev" });
    cp = await fakeControlPlane({ verify: (t) => issuer.verifyAccessToken(t) as never, issuer: issuer.issuer });
    cp.seed({ organizations: [{ id: "broken-org", name: "Broken", owners: ["owner"] }] });
    const port = await freePort();
    origin = `http://127.0.0.1:${port}`;
    process.env.FIXTURE_CONTROL_PLANE_URL = cp.url;
    process.env.FIXTURE_PUBLIC_ORIGIN = origin;
    server = await createConsoleServer({
      build: () => import(new URL("./fixture-host/build/server/index.js", import.meta.url).href),
      clientDir: new URL("./fixture-host/build/client/", import.meta.url),
      env: { ANKKA_CONSOLE_PORT: String(port), ANKKA_CONSOLE_CONTROL_PLANE_URL: cp.url },
    });
    browser = new Browser(origin);

    // Sign in through the identity provider's form, as a person does.
    const form = await browser.follow("/x/");
    assert.match(form.url, /\/protocol\/openid-connect\/auth/);
    const action = /action="([^"]+)"/.exec(form.html)![1].replace(/&amp;/g, "&");
    const fields = new URLSearchParams();
    for (const [, name, value] of form.html.matchAll(/type="hidden" name="([^"]+)" value="([^"]*)"/g)) fields.set(name, value.replace(/&amp;/g, "&"));
    fields.set("username", "owner");
    fields.set("password", "owner");
    const posted = await browser.request(action, { method: "POST", body: fields });
    const landed = await browser.follow(posted.headers.get("location")!);
    assert.equal(new URL(landed.url).pathname, "/x/");
  });

  after(async () => {
    await server?.close();
    await cp?.close();
    await issuer?.close();
  });

  test("the pages render inside the host's layout, and every link stays under its prefix", async () => {
    const front = await browser.follow("/x/");
    assert.equal(front.status, 200);
    assert.match(front.html, /data-product-chrome/);
    const hrefs = [...front.html.matchAll(/href="(\/[^"]*)"/g)].map((m) => m[1]).filter((h) => !h.startsWith("/assets/"));
    assert.ok(hrefs.length > 0);
    for (const h of hrefs) assert.ok(h.startsWith("/x"), `a link escaped the prefix: ${h}`);
  });

  test("sessions live in the host's store, not a sealed cookie", () => {
    assert.ok(browser.cookies.has("fixture_sid"));
    assert.ok(![...browser.cookies.keys()].some((k) => k.includes("ankka_console") && !k.includes("flash") && !k.includes("login")));
  });

  test("a host restyles the console by setting its custom properties and not its rules", async () => {
    const front = await browser.follow("/x/");
    assert.match(front.html, /class="ac-root ac-shell ac-light product"/);
    const sheet = /<link rel="stylesheet" href="([^"]+)"/.exec(front.html)![1];
    const css = await (await browser.request(sheet)).text();
    // Every declaration of the property in the package is in a cascade layer; the host's is not. An
    // unlayered declaration beats a layered one whatever the specificity or the order, in either theme.
    const declarations = layersOf(css, "--ac-color-ink:");
    const host = declarations.filter((d) => d.selector.includes(".product"));
    const pkg = declarations.filter((d) => !d.selector.includes(".product"));
    assert.ok(pkg.length >= 2, "the package declares the ink for each theme");
    for (const d of pkg) assert.ok(d.layered, `the package declares the ink outside a layer on ${d.selector}`);
    assert.equal(host.length, 1);
    assert.equal(host[0].layered, false, "the host's own declaration is unlayered");
  });

  test("an organization is created and shows the host's panel, action and no hidden control", async () => {
    const page = await browser.submit("/x/organizations/new", { intent: "create", id: "acme", name: "Acme" });
    assert.equal(new URL(page.url).pathname, "/x/organizations/acme");
    assert.match(page.html, /data-plan[^>]*>Team</);
    assert.ok(!page.html.includes("Delete organization"), "the host hid deletion");
    const front = await browser.follow("/x/");
    assert.match(front.html, /href="\/x\/billing"[^>]*>Start a subscription/);
  });

  test("a failing panel is contained; the page around it works", async () => {
    const page = await browser.follow("/x/organizations/broken-org");
    assert.equal(page.status, 200);
    assert.match(page.html.replaceAll("<!-- -->", ""), /Plan could not be loaded: the billing service did not answer/);
    assert.match(page.html, /<h1>Broken<\/h1>/);
  });

  test("a hidden operation is not shown, and the control plane's rule still decides if it is posted", async () => {
    await browser.submit("/x/organizations/new", { intent: "create", id: "doomed", name: "Doomed" });
    const posted = await browser.submit("/x/organizations/doomed", { intent: "delete" });
    assert.equal(new URL(posted.url).pathname, "/x/");
    assert.equal(cp.state.organizations.has("doomed"), false);
  });

  test("projects, services, members and tokens work through the host", async () => {
    const project = await browser.submit("/x/organizations/acme/projects/new", { intent: "create", id: "shop", name: "Shop" });
    assert.equal(new URL(project.url).pathname, "/x/projects/shop");
    const renamed = await browser.submit("/x/projects/shop", { intent: "rename", name: "Storefront" });
    assert.match(renamed.html, /<h1>Storefront<\/h1>/);

    const applied = await browser.submit("/x/projects/shop/services/apply", {
      intent: "apply",
      descriptor: JSON.stringify({ name: "cart", service: { image: "cart:1" } }),
    });
    assert.equal(new URL(applied.url).pathname, "/x/projects/shop/services/cart");
    const paused = await browser.submit("/x/projects/shop/services/cart", { intent: "pause" });
    assert.match(paused.html, /data-lifecycle="Paused"/);
    const logs = await browser.follow("/x/projects/shop/services/cart/logs");
    assert.equal(logs.status, 200);

    const invited = await browser.submit("/x/organizations/acme/members", { intent: "invite", email: "new@example.test", role: "member" });
    assert.match(invited.html, /data-invitation="new@example.test"/);

    const tokens = await browser.submit("/x/organizations/acme/tokens", { intent: "create", label: "ci" });
    assert.match(tokens.html, /data-secret[^>]*>ankka_/);
    const again = await browser.follow("/x/organizations/acme/tokens");
    assert.ok(!again.html.includes("data-secret"), "the secret was shown twice");
    const tokenId = [...cp.state.tokens.values()].find((t) => t.organizationId === "acme")!.id;
    await browser.submit("/x/organizations/acme/tokens", { intent: "revoke", tokenId });
    assert.equal(cp.state.tokens.get(tokenId)!.revoked, true);
  });

  test("the host's own page sits beside the package's", async () => {
    const billing = await browser.follow("/x/billing");
    assert.match(billing.html, /Billing, a page of the host&#x27;s own|Billing, a page of the host's own/);
  });

  test("a host mounts the parts of the shell it has content for, without a change to the package", async () => {
    const billing = await browser.follow("/x/billing");
    assert.match(billing.html, /class="ac-backdrop"/);
    assert.match(billing.html, /<header class="ac-bar"/);
    assert.match(billing.html, /data-product-chrome/);
    for (const absent of ['class="ac-rail"', 'class="ac-listing"', 'aria-label="Operations"']) assert.ok(!billing.html.includes(absent), `the host did not mount ${absent}`);
    // A package page in the same host brings its own inspector.
    const org = await browser.follow("/x/organizations/broken-org");
    assert.match(org.html, /aria-label="Operations"/);
  });
});
