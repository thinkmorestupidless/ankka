# Contract: configuration, manifests and the realm client

## The host's environment

| Variable | In a cluster | Locally | Meaning |
|---|---|---|---|
| `ANKKA_CONSOLE_CONTROL_PLANE_URL` | `https://ankka-controlplane.ankka-controlplane.svc:9000` | `http://localhost:9000` | The API |
| `ANKKA_CONSOLE_AUTHORITY` | from `ankka-platform.consoleAuthority` | `localhost:3000` | The console's own host and port; the public origin is `https://` (cluster) or `http://` (local) plus this |
| `ANKKA_CONSOLE_CLIENT_ID` | `ankka-console` | same | |
| `ANKKA_CONSOLE_CLIENT_SECRET` | Secret `ankka-console-secrets/clientSecret` | `dev` | |
| `ANKKA_CONSOLE_SESSION_SECRET` | Secret `ankka-console-secrets/sessionSecret` | `dev-session-secret` | Rotating it signs everyone out |
| `ANKKA_CONSOLE_AUTH_BACKCHANNEL_URL` | `https://ankka-keycloak-service.ankka-auth.svc:8443` | unset (the issuer) | Where token, key, revocation and end-session calls go |
| `ANKKA_CONSOLE_AUTH_CA` | `/var/run/secrets/ankka/service/ca.crt` | unset | Trust for the backchannel |
| `ANKKA_CONSOLE_TLS_DIR` | `/var/run/secrets/ankka/service` (`tls.crt`, `tls.key`, `ca.crt`) | unset → plain HTTP | The console's serving certificate and the client certificate it presents |
| `ANKKA_CONSOLE_PORT` | `9000` | `3000` | |
| `ANKKA_CONSOLE_PROBE_PORT` | `7627` | unset → no probe listener | |
| `ANKKA_CONSOLE_ALLOW_INSECURE_ISSUER` | unset | `true` | Permits a plain-HTTP issuer (the compose Keycloak) |

The issuer and audience are read from `GET /auth` on the control plane at startup; a console that
cannot read them refuses to start, naming the URL.

## Kubernetes component `kustomization/components/console/`

| File | Resource |
|---|---|
| `namespace.yaml` | `ankka-console`, `app.kubernetes.io/managed-by: ankka` |
| `serviceaccount.yaml` | `ankka-console`, no bindings |
| `secrets.yaml` | `ankka-console-secrets` with development `clientSecret` and `sessionSecret` |
| `deployment.yaml` | 2 replicas, `RollingUpdate` 1/0, `preStop.sleep: 5s`, ports `http` 9000 and `probe` 7627, readiness `httpGet /ready` on `probe`, `fsGroup: 1000`, `runAsNonRoot`, the certificate Secret mounted at `/var/run/secrets/ankka/service` with `defaultMode: 0440`, env as above, labels `formation`-free (not an ankka cluster) |
| `service.yaml` | `ankka-console`, port `http` 9000 |
| `httproute.yaml` | `console.BASE_DOMAIN` → `ankka-console:9000` on the `ankka` Gateway's `https` listener |
| `zero-trust.yaml` | `Certificate` `ankka-console-service` (issuer `ankka-service`, URI `ankka://platform/console`, DNS names of the Service, `server auth, client auth`, 24h/16h, RSA 2048 PKCS8, rotate always); `NetworkPolicy` ingress 9000 from `envoy-gateway-system` proxy pods owning Gateway `ankka`, 7627 from anyone; `BackendTLSPolicy` on the Service with `ankka-service-ca` and the Service's FQDN |

The control plane's `ankka-controlplane-http` policy already admits pods labelled and namespaced
`managed-by: ankka`; the console's pod template carries that label.

## Overlays

`ankka-platform` ConfigMap, both overlays, one new key:

```yaml
data:
  baseDomain: 127.0.0.1.sslip.io
  httpsPort: "8443"
  consoleAuthority: console.127.0.0.1.sslip.io:8443   # console.<baseDomain>[:<httpsPort>]
```

Replacements: `baseDomain` → `HTTPRoute/ankka-console` `spec.hostnames.0` (delimiter `.`, index 1);
`consoleAuthority` → `Deployment/ankka-console` env `ANKKA_CONSOLE_AUTHORITY`, and
`KeycloakRealmImport/ankka-realm` `spec.realm.clients.[clientId=ankka-console].redirectUris.0`
(delimiter `/`, index 2). `RemoteOverlaySuite` asserts the key equals its derivation in both overlays.

The cloud overlay additionally: `$patch: delete` on `ankka-console-secrets`; a patch setting the
realm client's `secret` to `SET` and its `redirectUris` to `["https://CONSOLE_AUTHORITY/*"]` (no
`localhost`); `images:` entry `ankka-console` → `ghcr.io/thinkmorestupidless/ankka-console:0.0.0 # SET`.

## The realm client (`realm-import.json`)

```json
{
  "clientId": "ankka-console",
  "name": "ankka console",
  "description": "The installation's console. Authorization code flow with PKCE; confidential.",
  "enabled": true,
  "protocol": "openid-connect",
  "publicClient": false,
  "secret": "dev",
  "standardFlowEnabled": true,
  "implicitFlowEnabled": false,
  "directAccessGrantsEnabled": false,
  "serviceAccountsEnabled": false,
  "fullScopeAllowed": true,
  "redirectUris": ["https://CONSOLE_AUTHORITY/*", "http://localhost:3000/*"],
  "webOrigins": ["+"],
  "attributes": {
    "pkce.code.challenge.method": "S256",
    "post.logout.redirect.uris": "+"
  },
  "defaultClientScopes": ["ankka-controlplane"],
  "optionalClientScopes": []
}
```

Compose takes the realm out of the same file, so the local console at `localhost:3000` signs in with
no further step. An installation whose realm predates the client adds it with the command
`platform/console.md` gives (`kcadm.sh create clients -r ankka …`, the deploy script's smoke-client
shape), then creates the console's Secret.

## Local development

```bash
docker compose up -d                                    # Postgres, Keycloak on :8081 with the realm and user dev/dev
ANKKA_AUTH_ISSUER=http://localhost:8081/realms/ankka sbt controlPlane/run   # :9000
cd console && npm ci && npm run dev                     # the host on :3000, against both
```

`npm run dev` sets the local defaults above; nothing else is configured.
