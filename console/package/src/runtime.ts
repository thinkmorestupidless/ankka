/**
 * What one console process holds for its whole life, built once from its options: the identity
 * provider's configuration, the session store, the access token cache, and the transports.
 */
import { ControlPlaneClient } from "./client/control-plane.ts";
import { Issuer } from "./auth/oidc.ts";
import { SealedCookieSessionStore, SealedFlash, type SessionStore } from "./session/cookie-store.ts";
import { TokenCache } from "./session/token-cache.ts";
import { SessionTokenSource, type TokenSource } from "./session/token-source.ts";
import { tlsFetch, type FetchLike } from "./server/transport.ts";
import type { ConsoleOptions } from "./options.ts";
import type { DeployTokenCreated, MachineRegistered } from "./client/schemas.ts";

export interface ConsoleRuntime {
  options: ConsoleOptions;
  mount: string;
  secure: boolean;
  store: SessionStore;
  tokens: TokenSource;
  /** Present when the package runs its own sign-in. */
  issuer?: () => Promise<Issuer>;
  sessionTokens?: SessionTokenSource;
  controlPlaneFetch: FetchLike;
  /** A deploy token's secret, carried across one redirect and shown once. */
  tokenFlash: SealedFlash<DeployTokenCreated>;
  /** A registered machine's client secret, carried across one redirect and shown once, as a token's is. */
  machineFlash: SealedFlash<MachineRegistered>;
  pendingLogin: SealedFlash<{ state: string; nonce: string; verifier: string; returnTo: string }>;
  log: (line: Record<string, unknown>) => void;
}

export const normaliseMount = (mount: string | undefined) => {
  const m = (mount ?? "/").trim();
  const withLead = m.startsWith("/") ? m : `/${m}`;
  return withLead.endsWith("/") ? withLead : `${withLead}/`;
};

export function createRuntime(options: ConsoleOptions): ConsoleRuntime {
  const secure = options.publicOrigin.startsWith("https:");
  const secret = options.sessionSecret;
  if (!secret && (!options.session || !options.tokens)) {
    throw new Error("sessionSecret is required unless both a session store and a token source are supplied");
  }
  const store = options.session ?? new SealedCookieSessionStore({ secret: secret!, secure });
  const controlPlaneFetch = tlsFetch(options.controlPlane.tls);
  const authFetch = tlsFetch(options.auth.ca ? { ca: options.auth.ca } : undefined);

  let issuer: (() => Promise<Issuer>) | undefined;
  let sessionTokens: SessionTokenSource | undefined;
  let tokens: TokenSource;
  if (options.tokens) {
    tokens = options.tokens;
  } else {
    // The issuer the console trusts is the one the control plane trusts: asked of the control plane
    // once, unless the host named it.
    let resolved: Promise<Issuer> | undefined;
    issuer = () => {
      if (!resolved) {
        resolved = (async () => {
          const name =
            options.auth.issuer ??
            (await new ControlPlaneClient({ baseUrl: options.controlPlane.url, bearer: async () => null, transport: controlPlaneFetch }).authDiscovery())
              .issuer;
          return new Issuer({
            issuer: name,
            clientId: options.auth.clientId,
            clientSecret: options.auth.clientSecret,
            backchannelUrl: options.auth.backchannelUrl,
            fetch: authFetch,
            allowInsecure: options.auth.allowInsecure,
          });
        })().catch((e) => {
          resolved = undefined;
          throw e;
        });
      }
      return resolved;
    };
    const getIssuer = issuer;
    sessionTokens = new SessionTokenSource({
      store,
      cache: new TokenCache(),
      issuer: { refresh: async (refreshToken) => (await getIssuer()).refresh(refreshToken) },
    });
    tokens = sessionTokens;
  }

  return {
    options,
    mount: normaliseMount(options.mount),
    secure,
    store,
    tokens,
    issuer,
    sessionTokens,
    controlPlaneFetch,
    tokenFlash: new SealedFlash({ secret: secret ?? "unused", secure, name: "ankka_console_flash", maxAgeSeconds: 60 }),
    machineFlash: new SealedFlash({ secret: secret ?? "unused", secure, name: "ankka_console_machine_flash", maxAgeSeconds: 60 }),
    pendingLogin: new SealedFlash({ secret: secret ?? "unused", secure, name: "ankka_console_login", maxAgeSeconds: 600 }),
    log: options.log ?? ((line) => process.stdout.write(JSON.stringify(line) + "\n")),
  };
}
