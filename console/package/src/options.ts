import type { SessionStore } from "./session/cookie-store.ts";
import type { TokenSource } from "./session/token-source.ts";
import type { ConsoleExtensions } from "./extensions/types.ts";
import type { TlsFiles } from "./server/transport.ts";

/** Everything the console needs from its host. `consoleOptionsFromEnv` builds it from the environment. */
export interface ConsoleOptions {
  controlPlane: {
    url: string;
    /** The console's certificate, presented to the control plane, and the authority to verify it by. */
    tls?: TlsFiles;
  };
  auth: {
    clientId: string;
    clientSecret: string;
    /** Where server-side calls to the identity provider go when its external name does not resolve here. */
    backchannelUrl?: string;
    /** The authority the backchannel is verified by. */
    ca?: string;
    /** Permits a plain-HTTP issuer: the development Keycloak. */
    allowInsecure?: boolean;
    /** The issuer; read from the control plane's `GET /auth` when absent, so the two cannot disagree. */
    issuer?: string;
  };
  /** The console's own origin as a browser sees it, e.g. `https://console.example.com`. */
  publicOrigin: string;
  /** The path the package's routes are mounted under; `/` unless the host prefixed them. */
  mount?: string;
  /** Seals the default session store and the one-time values; required unless both are supplied. */
  sessionSecret?: string;
  session?: SessionStore;
  /** A host that signs people in itself supplies this; the package's `/auth` routes are then not mounted. */
  tokens?: TokenSource;
  /** Server-side halves of the host's extensions: the panels' `load` functions. */
  extensions?: ConsoleExtensions;
  /** One structured line per request. Defaults to stdout. Never given a cookie, header or token. */
  log?: (line: Record<string, unknown>) => void;
}

type Env = Record<string, string | undefined>;

function required(env: Env, name: string): string {
  const value = env[name]?.trim();
  if (!value) throw new Error(`${name} is not set`);
  return value;
}

/**
 * The options, from the variables the installation's manifests set (see `platform/console.md`).
 * `ANKKA_CONSOLE_TLS_DIR` is what makes this a cluster console: HTTPS on the public origin, a
 * `__Host-` cookie, and the certificate in it presented to the control plane.
 */
export function consoleOptionsFromEnv(env: Env = process.env, extensions?: ConsoleExtensions): ConsoleOptions {
  const tlsDir = env.ANKKA_CONSOLE_TLS_DIR?.trim();
  // In a cluster the console's own address is not guessable, and a wrong one is a sign-in the
  // identity provider refuses to return from; only a laptop's console defaults to localhost.
  const authority = tlsDir
    ? required(env, "ANKKA_CONSOLE_AUTHORITY")
    : env.ANKKA_CONSOLE_AUTHORITY?.trim() || `localhost:${env.ANKKA_CONSOLE_PORT?.trim() || "3000"}`;
  if (/[A-Z_]{6,}/.test(authority)) throw new Error(`ANKKA_CONSOLE_AUTHORITY is '${authority}', an unfilled placeholder`);
  return {
    controlPlane: {
      url: env.ANKKA_CONSOLE_CONTROL_PLANE_URL?.trim() || "http://localhost:9000",
      tls: tlsDir ? { ca: `${tlsDir}/ca.crt`, cert: `${tlsDir}/tls.crt`, key: `${tlsDir}/tls.key` } : undefined,
    },
    auth: {
      clientId: env.ANKKA_CONSOLE_CLIENT_ID?.trim() || "ankka-console",
      clientSecret: required(env, "ANKKA_CONSOLE_CLIENT_SECRET"),
      backchannelUrl: env.ANKKA_CONSOLE_AUTH_BACKCHANNEL_URL?.trim() || undefined,
      ca: env.ANKKA_CONSOLE_AUTH_CA?.trim() || undefined,
      allowInsecure: env.ANKKA_CONSOLE_ALLOW_INSECURE_ISSUER === "true",
      issuer: env.ANKKA_CONSOLE_AUTH_ISSUER?.trim() || undefined,
    },
    publicOrigin: `${tlsDir ? "https" : "http"}://${authority}`,
    mount: env.ANKKA_CONSOLE_MOUNT?.trim() || "/",
    sessionSecret: required(env, "ANKKA_CONSOLE_SESSION_SECRET"),
    extensions,
  };
}
