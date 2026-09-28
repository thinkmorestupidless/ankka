/**
 * An OpenID Connect provider in one process, shaped like the platform's Keycloak realm as far as a
 * client can tell: discovery under `/realms/ankka`, a login form with `username` and `password`
 * fields, the authorization code grant with PKCE, refresh tokens that rotate but stay valid until
 * they expire (Keycloak's default), revocation, RP-initiated logout, and an SSO session in a cookie
 * so a second sign-in in the same browser needs no form.
 *
 * Users are `dev` (a platform administrator), `owner`, `member` and `outsider`; each one's password
 * is its name and its email is `<name>@example.test`, verified. Tests steer it with
 * `expireSession()` (the next refresh is refused as Keycloak refuses an ended session),
 * `failNext()` (the next backchannel call fails as an unreachable issuer would) and `sessions`.
 */
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import { createHash, randomBytes } from "node:crypto";
import { exportJWK, generateKeyPair, jwtVerify, SignJWT, type JWK, type CryptoKey } from "jose";

export interface FakeUser {
  username: string;
  name: string;
  email: string;
  roles: string[];
}

export const fakeUsers: Record<string, FakeUser> = {
  dev: { username: "dev", name: "Dev User", email: "dev@example.test", roles: ["platform-admin"] },
  owner: { username: "owner", name: "Olive Owner", email: "owner@example.test", roles: [] },
  member: { username: "member", name: "Max Member", email: "member@example.test", roles: [] },
  outsider: { username: "outsider", name: "Otto Outsider", email: "outsider@example.test", roles: [] },
};

export interface FakeIssuerOptions {
  clientId?: string;
  clientSecret?: string;
  audience?: string;
  accessTokenSeconds?: number;
  sessionIdleSeconds?: number;
  port?: number;
  /**
   * The origin the issuer names in its tokens and discovery document, when it is not the address it
   * listens on — the cluster's situation, where Keycloak is reached in-cluster and named externally.
   */
  publicUrl?: string;
}

interface Session {
  id: string;
  sub: string;
  ended: boolean;
}

interface Code {
  sub: string;
  sessionId: string;
  redirectUri: string;
  nonce?: string;
  challenge?: string;
  expires: number;
}

interface Refresh {
  sessionId: string;
  sub: string;
  exp: number;
  revoked: boolean;
}

export interface FakeIssuer {
  /** The realm's issuer, e.g. `http://127.0.0.1:1234/realms/ankka`. */
  issuer: string;
  url: string;
  server: Server;
  /** Verifies an access token as the control plane would, offline. */
  verifyAccessToken(token: string): Promise<Record<string, unknown> | null>;
  /** End every SSO session of this user: their next refresh is refused with `invalid_grant`. */
  expireSession(sub: string): void;
  /** The next token, revocation or discovery request is answered as an unreachable issuer would be. */
  failNext(): void;
  sessions: Map<string, Session>;
  /** Mints a token pair for a user without a browser, for tests that need a bearer. */
  mint(username: string): Promise<{ accessToken: string; refreshToken: string; idToken: string }>;
  close(): Promise<void>;
}

const sha256b64 = (s: string) => createHash("sha256").update(s).digest("base64url");

function html(res: ServerResponse, status: number, body: string) {
  res.writeHead(status, { "content-type": "text/html; charset=utf-8" }).end(body);
}

function json(res: ServerResponse, status: number, body: unknown) {
  res.writeHead(status, { "content-type": "application/json", "cache-control": "no-store" }).end(JSON.stringify(body));
}

async function formBody(req: IncomingMessage): Promise<URLSearchParams> {
  const chunks: Buffer[] = [];
  for await (const c of req) chunks.push(c as Buffer);
  return new URLSearchParams(Buffer.concat(chunks).toString("utf8"));
}

function cookie(req: IncomingMessage, name: string): string | undefined {
  for (const part of (req.headers.cookie ?? "").split(";")) {
    const [k, ...v] = part.trim().split("=");
    if (k === name) return v.join("=");
  }
  return undefined;
}

const escape = (s: string) => s.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;");

export async function fakeIssuer(options: FakeIssuerOptions = {}): Promise<FakeIssuer> {
  const clientId = options.clientId ?? "ankka-console";
  const clientSecret = options.clientSecret ?? "dev";
  const audience = options.audience ?? "ankka-controlplane";
  const accessSeconds = options.accessTokenSeconds ?? 300;
  const idleSeconds = options.sessionIdleSeconds ?? 1800;
  const { publicKey, privateKey } = await generateKeyPair("RS256");
  const jwk: JWK = { ...(await exportJWK(publicKey)), kid: "fake-1", alg: "RS256", use: "sig" };
  const sessions = new Map<string, Session>();
  const codes = new Map<string, Code>();
  const refreshes = new Map<string, Refresh>();
  let failures = 0;
  let issuer = "";

  const sign = (claims: Record<string, unknown>, seconds: number, key: CryptoKey = privateKey) =>
    new SignJWT(claims)
      .setProtectedHeader({ alg: "RS256", kid: "fake-1", typ: "JWT" })
      .setIssuer(issuer)
      .setIssuedAt()
      .setExpirationTime(Math.floor(Date.now() / 1000) + seconds)
      .sign(key);

  async function tokensFor(sub: string, sessionId: string, nonce?: string) {
    const user = fakeUsers[sub];
    const identity = { name: user.name, email: user.email, email_verified: true, preferred_username: user.username };
    const accessToken = await sign(
      { ...identity, sub, aud: [audience], azp: clientId, sid: sessionId, realm_access: { roles: user.roles }, typ: "Bearer" },
      accessSeconds,
    );
    const idToken = await sign({ ...identity, sub, aud: clientId, azp: clientId, sid: sessionId, ...(nonce ? { nonce } : {}) }, accessSeconds);
    // Keycloak's refresh tokens are signed JWTs of several hundred bytes; so are these, so a cookie
    // that carries one is measured at a realistic size.
    const refreshToken = await sign({ sub, sid: sessionId, typ: "Refresh", aud: issuer, jti: randomBytes(16).toString("hex") }, idleSeconds);
    refreshes.set(refreshToken, { sessionId, sub, exp: Date.now() + idleSeconds * 1000, revoked: false });
    return { accessToken, idToken, refreshToken };
  }

  const tokenResponse = (t: { accessToken: string; idToken: string; refreshToken: string }) => ({
    access_token: t.accessToken,
    id_token: t.idToken,
    refresh_token: t.refreshToken,
    token_type: "Bearer",
    expires_in: accessSeconds,
    refresh_expires_in: idleSeconds,
    scope: "openid",
  });

  function clientAuthenticated(req: IncomingMessage, form: URLSearchParams): boolean {
    const header = req.headers.authorization ?? "";
    if (header.startsWith("Basic ")) {
      const [id, secret] = Buffer.from(header.slice(6), "base64").toString("utf8").split(":").map(decodeURIComponent);
      return id === clientId && secret === clientSecret;
    }
    return form.get("client_id") === clientId && form.get("client_secret") === clientSecret;
  }

  function loginPage(params: URLSearchParams, error?: string) {
    const hidden = [...params.entries()]
      .map(([k, v]) => `<input type="hidden" name="${escape(k)}" value="${escape(v)}">`)
      .join("");
    return `<!doctype html><html lang="en"><head><title>Sign in to ankka</title></head><body>
<main><h1>Sign in to your account</h1>${error ? `<p id="input-error" role="alert">${escape(error)}</p>` : ""}
<form id="kc-form-login" method="post" action="${escape(issuer)}/protocol/openid-connect/auth">${hidden}
<label for="username">Username or email</label><input id="username" name="username" autocomplete="username">
<label for="password">Password</label><input id="password" name="password" type="password" autocomplete="current-password">
<button id="kc-login" type="submit">Sign In</button></form></main></body></html>`;
  }

  function redirectWithCode(res: ServerResponse, params: URLSearchParams, session: Session) {
    const code = randomBytes(24).toString("base64url");
    codes.set(code, {
      sub: session.sub,
      sessionId: session.id,
      redirectUri: params.get("redirect_uri") ?? "",
      nonce: params.get("nonce") ?? undefined,
      challenge: params.get("code_challenge") ?? undefined,
      expires: Date.now() + 60_000,
    });
    const target = new URL(params.get("redirect_uri")!);
    target.searchParams.set("code", code);
    if (params.get("state")) target.searchParams.set("state", params.get("state")!);
    target.searchParams.set("iss", issuer);
    res
      .writeHead(302, {
        location: target.toString(),
        "set-cookie": `FAKE_SSO=${session.id}; Path=/realms/ankka; HttpOnly; SameSite=Lax`,
      })
      .end();
  }

  const realm = "/realms/ankka";

  const server = createServer(async (req, res) => {
    const url = new URL(req.url ?? "/", "http://fake");
    const path = url.pathname;
    try {
      if (path === `${realm}/.well-known/openid-configuration`) {
        if (failures > 0) return (failures--, void req.socket.destroy());
        return json(res, 200, {
          issuer,
          authorization_endpoint: `${issuer}/protocol/openid-connect/auth`,
          token_endpoint: `${issuer}/protocol/openid-connect/token`,
          jwks_uri: `${issuer}/protocol/openid-connect/certs`,
          revocation_endpoint: `${issuer}/protocol/openid-connect/revoke`,
          end_session_endpoint: `${issuer}/protocol/openid-connect/logout`,
          response_types_supported: ["code"],
          subject_types_supported: ["public"],
          id_token_signing_alg_values_supported: ["RS256"],
          code_challenge_methods_supported: ["S256"],
          token_endpoint_auth_methods_supported: ["client_secret_basic", "client_secret_post"],
          grant_types_supported: ["authorization_code", "refresh_token"],
        });
      }
      if (path === `${realm}/protocol/openid-connect/certs`) return json(res, 200, { keys: [jwk] });

      if (path === `${realm}/protocol/openid-connect/auth` && req.method === "GET") {
        const params = url.searchParams;
        if (params.get("client_id") !== clientId) return html(res, 400, "<p>Client not found.</p>");
        if (!params.get("redirect_uri")) return html(res, 400, "<p>Invalid parameter: redirect_uri</p>");
        const sso = sessions.get(cookie(req, "FAKE_SSO") ?? "");
        if (sso && !sso.ended) return redirectWithCode(res, params, sso);
        return html(res, 200, loginPage(params));
      }

      if (path === `${realm}/protocol/openid-connect/auth` && req.method === "POST") {
        const form = await formBody(req);
        const username = form.get("username") ?? "";
        const user = fakeUsers[username] ?? Object.values(fakeUsers).find((u) => u.email === username);
        const params = new URLSearchParams([...form.entries()].filter(([k]) => k !== "username" && k !== "password"));
        if (!user || form.get("password") !== user.username) return html(res, 200, loginPage(params, "Invalid username or password."));
        const session: Session = { id: randomBytes(12).toString("hex"), sub: user.username, ended: false };
        sessions.set(session.id, session);
        return redirectWithCode(res, params, session);
      }

      if (path === `${realm}/protocol/openid-connect/token` && req.method === "POST") {
        if (failures > 0) return (failures--, void req.socket.destroy());
        const form = await formBody(req);
        if (!clientAuthenticated(req, form)) return json(res, 401, { error: "unauthorized_client", error_description: "Invalid client or Invalid client credentials" });
        const grant = form.get("grant_type");
        if (grant === "authorization_code") {
          const code = codes.get(form.get("code") ?? "");
          codes.delete(form.get("code") ?? "");
          if (!code || code.expires < Date.now()) return json(res, 400, { error: "invalid_grant", error_description: "Code not valid" });
          if (code.redirectUri !== form.get("redirect_uri")) return json(res, 400, { error: "invalid_grant", error_description: "Incorrect redirect_uri" });
          if (code.challenge && sha256b64(form.get("code_verifier") ?? "") !== code.challenge)
            return json(res, 400, { error: "invalid_grant", error_description: "PKCE verification failed" });
          const session = sessions.get(code.sessionId);
          if (!session || session.ended) return json(res, 400, { error: "invalid_grant", error_description: "Session not active" });
          return json(res, 200, tokenResponse(await tokensFor(code.sub, code.sessionId, code.nonce)));
        }
        if (grant === "refresh_token") {
          const token = form.get("refresh_token") ?? "";
          const record = refreshes.get(token);
          const session = record ? sessions.get(record.sessionId) : undefined;
          if (!record || record.revoked || record.exp < Date.now() || !session || session.ended)
            return json(res, 400, { error: "invalid_grant", error_description: "Session not active" });
          return json(res, 200, tokenResponse(await tokensFor(record.sub, record.sessionId)));
        }
        return json(res, 400, { error: "unsupported_grant_type" });
      }

      if (path === `${realm}/protocol/openid-connect/revoke` && req.method === "POST") {
        if (failures > 0) return (failures--, void req.socket.destroy());
        const form = await formBody(req);
        if (!clientAuthenticated(req, form)) return json(res, 401, { error: "unauthorized_client" });
        const record = refreshes.get(form.get("token") ?? "");
        if (record) record.revoked = true;
        return void res.writeHead(200).end();
      }

      // Keycloak's server-to-server logout: a confidential client posts the session's refresh token
      // and the user session ends, with no browser and no identity token.
      if (path === `${realm}/protocol/openid-connect/logout` && req.method === "POST") {
        if (failures > 0) return (failures--, void req.socket.destroy());
        const form = await formBody(req);
        if (!clientAuthenticated(req, form)) return json(res, 401, { error: "unauthorized_client" });
        const record = refreshes.get(form.get("refresh_token") ?? "");
        if (!record) return json(res, 400, { error: "invalid_grant", error_description: "Invalid refresh token" });
        const session = sessions.get(record.sessionId);
        if (session) session.ended = true;
        return void res.writeHead(204).end();
      }

      if (path === `${realm}/protocol/openid-connect/logout`) {
        const hint = url.searchParams.get("id_token_hint");
        let sid: string | undefined = cookie(req, "FAKE_SSO");
        if (hint) {
          try {
            const { payload } = await jwtVerify(hint, publicKey, { issuer });
            sid = String(payload.sid);
          } catch {
            // An expired hint still names the session in Keycloak; decode without verifying time.
            sid = String(JSON.parse(Buffer.from(hint.split(".")[1], "base64url").toString()).sid);
          }
        }
        const session = sessions.get(sid ?? "");
        if (session) session.ended = true;
        const after = url.searchParams.get("post_logout_redirect_uri");
        res.setHeader("set-cookie", "FAKE_SSO=; Path=/realms/ankka; Max-Age=0");
        if (after) return void res.writeHead(302, { location: after }).end();
        return html(res, 200, "<p>You are logged out.</p>");
      }

      html(res, 404, "<p>Not found</p>");
    } catch (e) {
      json(res, 500, { error: "server_error", error_description: String(e) });
    }
  });

  await new Promise<void>((resolve) => server.listen(options.port ?? 0, "127.0.0.1", resolve));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  issuer = `${options.publicUrl ?? base}${realm}`;

  return {
    issuer,
    url: base,
    server,
    sessions,
    async verifyAccessToken(token) {
      try {
        const { payload } = await jwtVerify(token, publicKey, { issuer, audience });
        return payload as Record<string, unknown>;
      } catch {
        return null;
      }
    },
    expireSession(sub) {
      for (const s of sessions.values()) if (s.sub === sub) s.ended = true;
    },
    failNext() {
      failures += 1;
    },
    async mint(username) {
      const session: Session = { id: randomBytes(12).toString("hex"), sub: username, ended: false };
      sessions.set(session.id, session);
      return tokensFor(username, session.id);
    },
    close: () =>
      new Promise((resolve) => {
        server.closeAllConnections();
        server.close(() => resolve());
      }),
  };
}
