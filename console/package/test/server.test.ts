import { after, before, describe, test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { copyFileSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { request } from "node:https";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { AddressInfo } from "node:net";
import { createConsoleServer, type ConsoleServer } from "../src/server/create-console-server.ts";
import { fakeControlPlane, type FakeControlPlane } from "ankka-console/testing";

// RSA, not EC: LibreSSL's `req -newkey ec` writes explicit curve parameters the platform's JVM
// refuses, and these fixtures follow the repository's one rule for test certificates.
function authority(dir: string) {
  const run = (...args: string[]) => execFileSync("openssl", args, { cwd: dir, stdio: "pipe" });
  run("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "ca.key", "-out", "ca.crt", "-days", "1", "-subj", "/CN=test-authority");
  return (name: string, san: string) => {
    writeFileSync(join(dir, `${name}.ext`), `subjectAltName=${san}\nextendedKeyUsage=serverAuth,clientAuth\n`);
    run("req", "-newkey", "rsa:2048", "-nodes", "-keyout", `${name}.key`, "-out", `${name}.csr`, "-subj", `/CN=${name}`);
    run("x509", "-req", "-in", `${name}.csr`, "-CA", "ca.crt", "-CAkey", "ca.key", "-CAcreateserial", "-out", `${name}.crt`, "-days", "1", "-extfile", `${name}.ext`);
    return { cert: join(dir, `${name}.crt`), key: join(dir, `${name}.key`) };
  };
}

function get(url: string, ca: string, client?: { cert: string; key: string }): Promise<{ status: number; body: string }> {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const req = request(
      {
        host: u.hostname,
        port: u.port,
        path: u.pathname,
        ca: execFileSync("cat", [ca]),
        cert: client ? execFileSync("cat", [client.cert]) : undefined,
        key: client ? execFileSync("cat", [client.key]) : undefined,
        servername: "localhost",
      },
      (res) => {
        let body = "";
        res.on("data", (c) => (body += c));
        res.on("end", () => resolve({ status: res.statusCode ?? 0, body }));
      },
    );
    req.on("error", reject);
    req.end();
  });
}

describe("createConsoleServer in a cluster", () => {
  let dir: string;
  let cp: FakeControlPlane;
  let server: ConsoleServer;
  let gateway: { cert: string; key: string };
  let service: { cert: string; key: string };

  before(async () => {
    dir = mkdtempSync(join(tmpdir(), "ankka-console-tls-"));
    const issue = authority(dir);
    const console_ = issue("console", "DNS:localhost,IP:127.0.0.1,URI:ankka://platform/console");
    gateway = issue("gateway", "URI:ankka://gateway");
    service = issue("service", "URI:ankka://checkout/cart");
    copyFileSync(console_.cert, join(dir, "tls.crt"));
    copyFileSync(console_.key, join(dir, "tls.key"));
    cp = await fakeControlPlane();
    server = await createConsoleServer({
      requestListener: (_req, res) => res.writeHead(200, { "content-type": "text/plain" }).end("app"),
      clientDir: dir,
      env: {
        ANKKA_CONSOLE_TLS_DIR: dir,
        ANKKA_CONSOLE_PORT: "0",
        ANKKA_CONSOLE_PROBE_PORT: "0",
        ANKKA_CONSOLE_HOST: "127.0.0.1",
        ANKKA_CONSOLE_CONTROL_PLANE_URL: cp.url,
      },
    });
  });
  after(async () => {
    await server.close();
    await cp.close();
    rmSync(dir, { recursive: true, force: true });
  });

  const appUrl = () => `https://localhost:${(server.app.address() as AddressInfo).port}/`;
  const probeUrl = () => `http://127.0.0.1:${(server.probe!.address() as AddressInfo).port}/ready`;

  test("serves the gateway, identified by its certificate", async () => {
    const res = await get(appUrl(), join(dir, "ca.crt"), gateway);
    assert.deepEqual(res, { status: 200, body: "app" });
  });

  test("refuses a caller whose certificate names anything but the gateway", async () => {
    const res = await get(appUrl(), join(dir, "ca.crt"), service);
    assert.equal(res.status, 403);
  });

  test("refuses a caller with no certificate at the handshake", async () => {
    await assert.rejects(get(appUrl(), join(dir, "ca.crt")));
  });

  test("readiness is plain HTTP on its own port, and says yes once serving", async () => {
    const res = await fetch(probeUrl());
    assert.equal(res.status, 200);
    assert.equal(server.ready(), true);
  });

  test("a file from the client build is served, and nothing outside it", async () => {
    writeFileSync(join(dir, "hello.txt"), "hi");
    assert.equal((await get(appUrl() + "hello.txt", join(dir, "ca.crt"), gateway)).body, "hi");
    const escaped = await get(appUrl() + "..%2F..%2Fetc%2Fpasswd", join(dir, "ca.crt"), gateway);
    assert.equal(escaped.body, "app");
  });
});

describe("createConsoleServer on a laptop", () => {
  test("refuses to start when the control plane never answers, naming it", async () => {
    await assert.rejects(
      createConsoleServer({
        requestListener: (_q, s) => s.end(),
        clientDir: tmpdir(),
        startupTimeoutMs: 1_500,
        env: { ANKKA_CONSOLE_PORT: "0", ANKKA_CONSOLE_CONTROL_PLANE_URL: "http://127.0.0.1:1" },
      }),
      /did not answer GET http:\/\/127\.0\.0\.1:1\/auth/,
    );
  });
});
