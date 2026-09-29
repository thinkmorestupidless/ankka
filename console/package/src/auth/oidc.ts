/**
 * Signing in through the installation's realm: the authorization code flow with PKCE, as a
 * confidential client, over two addresses.
 *
 * The browser is sent to the realm's external address; the console's own calls — discovery, keys,
 * token, revocation, logout — go to an address the installation names (in a cluster, Keycloak's own
 * service, verified against the service authority), because the external name may not resolve
 * inside the cluster at all. Keycloak answers discovery with its external URLs whichever address
 * asked, so the document is fetched here and its backchannel endpoints re-pointed, rather than handed
 * to `discovery()`, which rightly refuses a document whose issuer is not the address it asked.
 */
import * as client from "openid-client";
import type { FetchLike } from "../server/transport.ts";

export interface IssuerOptions {
  /** The realm's issuer exactly as tokens name it: the control plane's `GET /auth` says what it is. */
  issuer: string;
  clientId: string;
  clientSecret: string;
  /** Where server-side calls go instead of the issuer's origin, e.g. `https://ankka-keycloak-service.ankka-auth.svc:8443`. */
  backchannelUrl?: string;
  /** How server-side calls are made: trusts the installation's authority in a cluster. */
  fetch?: FetchLike;
  /** Permits a plain-HTTP issuer: the development Keycloak. */
  allowInsecure?: boolean;
}

export class IdentityProviderUnavailable extends Error {
  constructor(cause: unknown) {
    super(`the identity provider cannot be reached: ${cause instanceof Error ? cause.message : String(cause)}`, { cause });
    this.name = "IdentityProviderUnavailable";
  }
}

/** The identity provider refused a session: sign in again. */
export class SessionEnded extends Error {
  constructor(message = "the identity provider no longer honours this session") {
    super(message);
    this.name = "SessionEnded";
  }
}

export interface Tokens {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
}

export interface BeganSignIn {
  url: URL;
  state: string;
  nonce: string;
  verifier: string;
}

function repoint(endpoint: string | undefined, backchannel: URL | undefined): string | undefined {
  if (!endpoint || !backchannel) return endpoint;
  const url = new URL(endpoint);
  url.protocol = backchannel.protocol;
  url.host = backchannel.host;
  return url.toString();
}

const isNetworkFailure = (e: unknown) =>
  e instanceof TypeError ||
  (e instanceof Error && /ECONNREFUSED|ECONNRESET|ENOTFOUND|EAI_AGAIN|ETIMEDOUT|socket hang up|fetch failed|other side closed/i.test(e.message + String((e as { cause?: unknown }).cause ?? "")));

function isInvalidGrant(e: unknown): boolean {
  if (!(e instanceof Error)) return false;
  const err = e as Error & { error?: string; code?: string };
  return err.error === "invalid_grant" || /invalid_grant/.test(err.message);
}

export class Issuer {
  readonly #options: IssuerOptions;
  #config: Promise<client.Configuration> | undefined;

  constructor(options: IssuerOptions) {
    this.#options = options;
  }

  /**
   * Server-side calls, carrying the public address as forwarded headers when they go to the
   * backchannel. Keycloak keeps only a host name and takes the scheme and port of its issuer from
   * each request, trusting the forwarded headers the gateway adds; asked directly on its in-cluster
   * TLS port, it would name itself `https://auth.<base>:8443` — a different issuer from the one every
   * browser-obtained token carries, so discovery would be refused and every token it issued here
   * would be refused by the control plane. The headers make the backchannel look, to Keycloak, like
   * the front door.
   */
  #fetch(): FetchLike {
    const base = this.#options.fetch ?? ((url, init) => fetch(url, init));
    if (!this.#options.backchannelUrl) return base;
    const issuer = new URL(this.#options.issuer);
    const proto = issuer.protocol.replace(":", "");
    const port = issuer.port || (proto === "https" ? "443" : "80");
    return (url, init = {}) => {
      const headers = new Headers(init.headers);
      headers.set("x-forwarded-proto", proto);
      headers.set("x-forwarded-host", issuer.host);
      headers.set("x-forwarded-port", port);
      return base(url, { ...init, headers });
    };
  }

  /** The configuration, discovered on first use and kept; a failed discovery is tried again next time. */
  configuration(): Promise<client.Configuration> {
    if (!this.#config) {
      this.#config = this.#discover().catch((e) => {
        this.#config = undefined;
        throw e;
      });
    }
    return this.#config;
  }

  async #discover(): Promise<client.Configuration> {
    const { issuer, backchannelUrl, clientId, clientSecret, allowInsecure } = this.#options;
    const backchannel = backchannelUrl ? new URL(backchannelUrl) : undefined;
    const discoveryUrl = repoint(`${issuer.replace(/\/+$/, "")}/.well-known/openid-configuration`, backchannel)!;
    let response: Response;
    try {
      response = await this.#fetch()(discoveryUrl, { headers: { accept: "application/json" } });
    } catch (e) {
      throw new IdentityProviderUnavailable(e);
    }
    if (!response.ok) throw new IdentityProviderUnavailable(new Error(`discovery answered ${response.status} at ${discoveryUrl}`));
    const document = (await response.json()) as client.ServerMetadata;
    if (document.issuer !== issuer) {
      throw new Error(
        `the identity provider names its issuer '${document.issuer}' but the control plane expects '${issuer}'; ` +
          "they must be the same string, port included",
      );
    }
    const metadata: client.ServerMetadata = {
      ...document,
      token_endpoint: repoint(document.token_endpoint, backchannel),
      jwks_uri: repoint(document.jwks_uri, backchannel),
      revocation_endpoint: repoint(document.revocation_endpoint, backchannel),
      end_session_endpoint: repoint(document.end_session_endpoint, backchannel),
    };
    const config = new client.Configuration(metadata, clientId, clientSecret, client.ClientSecretBasic(clientSecret));
    const outbound = this.#fetch();
    config[client.customFetch] = (url, options) => outbound(url, options as RequestInit) as ReturnType<client.CustomFetch>;
    if (allowInsecure) client.allowInsecureRequests(config);
    return config;
  }

  /** Where to send the browser, and what the callback must check. */
  async beginSignIn(redirectUri: string): Promise<BeganSignIn> {
    const config = await this.#guard(() => this.configuration());
    const verifier = client.randomPKCECodeVerifier();
    const state = client.randomState();
    const nonce = client.randomNonce();
    const url = client.buildAuthorizationUrl(config, {
      redirect_uri: redirectUri,
      // The realm declares its own scopes and none of Keycloak's built-ins; `openid` is the one asked
      // for, and the control plane's scope is a default of the client, so the access token carries
      // the audience and claims the control plane reads.
      scope: "openid",
      code_challenge: await client.calculatePKCECodeChallenge(verifier),
      code_challenge_method: "S256",
      state,
      nonce,
    });
    return { url, state, nonce, verifier };
  }

  /** Exchanges the callback's code, having checked state, nonce, PKCE and the identity token. */
  async finishSignIn(callbackUrl: URL, checks: { state: string; nonce: string; verifier: string }): Promise<Tokens> {
    const config = await this.#guard(() => this.configuration());
    const response = await this.#guard(() =>
      client.authorizationCodeGrant(config, callbackUrl, {
        pkceCodeVerifier: checks.verifier,
        expectedState: checks.state,
        expectedNonce: checks.nonce,
        idTokenExpected: true,
      }),
    );
    return this.#tokens(response, undefined);
  }

  async refresh(refreshToken: string): Promise<Tokens> {
    const config = await this.#guard(() => this.configuration());
    try {
      const response = await client.refreshTokenGrant(config, refreshToken);
      return this.#tokens(response, refreshToken);
    } catch (e) {
      if (isInvalidGrant(e)) throw new SessionEnded();
      if (isNetworkFailure(e)) throw new IdentityProviderUnavailable(e);
      throw e;
    }
  }

  /**
   * Ends the person's session at the identity provider, server to server: Keycloak's logout
   * endpoint accepts a confidential client's refresh token and ends the user session it belongs to,
   * so no identity token has to be kept for sign-out and no confirmation page is shown.
   */
  async endSession(refreshToken: string): Promise<void> {
    const config = await this.#guard(() => this.configuration());
    const endpoint = config.serverMetadata().end_session_endpoint;
    if (endpoint) {
      const body = new URLSearchParams({ refresh_token: refreshToken });
      const credentials = Buffer.from(
        `${encodeURIComponent(this.#options.clientId)}:${encodeURIComponent(this.#options.clientSecret)}`,
      ).toString("base64");
      const response = await this.#guard(() =>
        this.#fetch()(endpoint, {
          method: "POST",
          headers: { "content-type": "application/x-www-form-urlencoded", authorization: `Basic ${credentials}` },
          body,
        }),
      );
      // 400 means the session had already ended, which is what was asked for.
      if (!response.ok && response.status !== 400) throw new IdentityProviderUnavailable(new Error(`logout answered ${response.status}`));
      await response.body?.cancel();
    }
    await this.#guard(() => client.tokenRevocation(config, refreshToken, { token_type_hint: "refresh_token" })).catch(() => undefined);
  }

  #tokens(response: client.TokenEndpointResponse, previousRefresh: string | undefined): Tokens {
    const refreshToken = response.refresh_token ?? previousRefresh;
    if (!refreshToken) throw new Error("the identity provider issued no refresh token");
    return { accessToken: response.access_token, refreshToken, expiresIn: response.expires_in ?? 60 };
  }

  async #guard<T>(f: () => Promise<T>): Promise<T> {
    try {
      return await f();
    } catch (e) {
      if (e instanceof IdentityProviderUnavailable || e instanceof SessionEnded) throw e;
      if (isNetworkFailure(e)) throw new IdentityProviderUnavailable(e);
      throw e;
    }
  }
}
