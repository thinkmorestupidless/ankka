# Contract: the Keycloak component, the realm, the overlays and compose

## `kustomization/components/keycloak/`

| file | contents |
|---|---|
| `kustomization.yaml` | `Component`; resources: `namespace.yaml`, the operator by remote reference `github.com/keycloak/keycloak-k8s-resources/kubernetes?ref=26.7.4`, `postgres.yaml`, `admin-secret.yaml`, `keycloak.yaml`, `httproute.yaml` |
| `namespace.yaml` | `ankka-auth`, labelled for the gateway's namespace selector like `ankka-controlplane` |
| `postgres.yaml` | CNPG `Cluster` `ankka-keycloak-db`, 1 instance, 1Gi, `bootstrap.initdb` database `keycloak` owner `keycloak`, **no** `postInitApplicationSQLRefs` — Keycloak owns its schema |
| `admin-secret.yaml` | `ankka-keycloak-admin` with `username: admin`, `password: admin`; comment mirrors `token-secret.yaml`'s |
| `keycloak.yaml` | the `Keycloak` CR (below) |
| `httproute.yaml` | `auth.BASE_DOMAIN` → service `ankka-keycloak-service:8080`, same `parentRefs` as the control plane's route |
| `realm.json` | the realm (below); JSON, not YAML, because compose imports it as a file |

The operator's manifests are namespaced and carry no namespace; the component's `namespace:
ankka-auth` transformer places them. (`deploy-local.sh` applies this component in the same
ordered step as CNPG, cert-manager and Envoy Gateway, before the overlay — its CRDs must exist
before `keycloak.yaml` is an instance of one.)

### The `Keycloak` resource

```yaml
apiVersion: k8s.keycloak.org/v2alpha1
kind: Keycloak
metadata: { name: ankka-keycloak, namespace: ankka-auth }
spec:
  instances: 1
  db:
    vendor: postgres
    host: ankka-keycloak-db-rw
    port: 5432
    database: keycloak
    usernameSecret: { name: ankka-keycloak-db-app, key: username }
    passwordSecret: { name: ankka-keycloak-db-app, key: password }
  hostname:
    hostname: https://auth.BASE_DOMAIN:8443     # replaced by the overlay: base domain and port
    strict: false
  http:
    httpEnabled: true
  proxy:
    headers: xforwarded                          # or additionalOptions proxy-headers (research, verify 1)
  ingress:
    enabled: false
  bootstrapAdmin:
    user:
      secret: ankka-keycloak-admin
```

The realm import resource is **generated** by `deploy-local.sh` from `realm.json`:

```yaml
apiVersion: k8s.keycloak.org/v2alpha1
kind: KeycloakRealmImport
metadata: { name: ankka-realm, namespace: ankka-auth }
spec:
  keycloakCRName: ankka-keycloak
  realm: <realm.json, indented>
```

and applied after the `Keycloak` CR is `Ready`. Re-applying it on an existing installation is a
no-op (the realm exists and is not overwritten). The README documents that changing `realm.json`
after first install is a console operation.

### `realm.json`

| item | value |
|---|---|
| realm | `ankka`, enabled, `registrationAllowed: false`, `verifyEmail: true`, `accessTokenLifespan: 300`, `ssoSessionIdleTimeout: 1800`, `offlineSessionIdleTimeout: 2592000` |
| roles | realm role `platform-admin` |
| client scope `ankka-controlplane` | mappers: audience (`included.custom.audience: ankka-controlplane`), `email`, `email verified`, realm roles (`realm_access.roles`) |
| client `ankka-cli` | public, standard flow off, direct grants **off**, attribute `oauth2.device.authorization.grant.enabled: "true"`, default scopes `openid`, `ankka-controlplane`, optional `offline_access` |
| users | **none** |

A CI client is created by an administrator in the console: confidential, service accounts on,
`ankka-controlplane` scope assigned, and — if it is to be invited by email — an email set and
marked verified on its service-account user.

## Overlays

**`overlays/local`**: adds `../../components/keycloak`; `replacements` from `ankka-platform` copy
the base domain into the `HTTPRoute` hostname, the `Keycloak` hostname (label after the first
dot), and the control plane's `ANKKA_AUTH_ISSUER`; `httpsPort` into the `Keycloak` hostname port
and the issuer. The dev user is not here — the script creates it.

**`overlays/remote`**: the same component; **deletes** `ankka-keycloak-admin` with a `$patch:
delete` and the comment pattern from the token secret; the real one is created out of band:

```
kubectl -n ankka-auth create secret generic ankka-keycloak-admin \
  --from-literal=username=admin --from-literal=password="$(openssl rand -base64 32)"
```

Until it exists the operator cannot create the instance (research, verify 7).

**`components/controlplane`**: `token-secret.yaml` removed; `deployment.yaml` gains
`ANKKA_AUTH_ISSUER` (replaced by the overlay) and
`ANKKA_AUTH_JWKS_URL=http://ankka-keycloak-service.ankka-auth.svc:8080/realms/ankka/protocol/openid-connect/certs`,
and loses `ANKKA_CONTROLPLANE_TOKEN`.

## `deploy-local.sh` changes, in order

1. Install the Keycloak operator with the other three controllers (server-side apply of the
   pinned remote kustomization into `ankka-auth`).
2. Apply the overlay as today (now including the CNPG cluster, admin secret, `Keycloak` CR and
   route).
3. Wait: `cluster/ankka-keycloak-db` ready; `keycloak/ankka-keycloak` condition `Ready`.
4. Render and apply the `KeycloakRealmImport` from `realm.json`; wait for its `Done` condition.
5. With the bootstrap admin from the cluster, through the gateway with `--cacert`, create user
   `dev` (password `dev`, email `dev@<base domain>`, verified, role `platform-admin`) and a
   confidential client `ankka-local-smoke` (service account, `ankka-controlplane` scope,
   `platform-admin` on its service account) — idempotently, so a re-run passes.
6. Smoke test: client-credentials token for `ankka-local-smoke`, then `GET /organizations` with
   it through the gateway, requiring 200. A 401 here means the issuer and the control plane
   disagree — the message says to compare `ANKKA_AUTH_ISSUER` with the discovery document.
7. Print, alongside the existing `config set url/ca` lines: `ankka login   # user dev, password dev`.

## `docker-compose.yml`

Adds `keycloak` (`quay.io/keycloak/keycloak:26.7.4`, `start-dev --import-realm`, realm mounted
from `kustomization/components/keycloak/realm.json` into `/opt/keycloak/data/import/`,
`KC_BOOTSTRAP_ADMIN_USERNAME/PASSWORD=admin/admin`, port 8081→8080, healthcheck on
`/realms/ankka`) and `keycloak-init` (same image, runs `kcadm.sh` once to create `dev` with
`platform-admin`, depends on `keycloak` healthy). `README.md`'s local walkthrough becomes:

```
docker compose up -d
ANKKA_AUTH_ISSUER=http://localhost:8081/realms/ankka sbt controlPlane/run
ankka config set url http://localhost:9000
ankka login                # dev / dev
```

## Images and versions in one place

`26.7.4` appears in the component's operator reference, the `Keycloak` CR (the operator picks the
image; the CR pins nothing else), compose, and the test suites — through one constant in each of
the two places code can share it (`build.sbt` for the suites, the component for manifests), and
`RemoteOverlaySuite` asserts the rendered manifests and compose agree on it.
