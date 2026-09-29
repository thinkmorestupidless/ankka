# Install and configure the console

> Install the web console at console.<base domain>, give it its realm client and secrets, add it to an installation whose realm predates it, run it locally, or leave it out.

Source: https://docs.ankka.cloud/platform/console/
The console is a component of every installation, applied by both example overlays: a web application at
`https://console.<base domain>`, in its own namespace, `ankka-console`. It needs three things from the
installation: a hostname, a client in the realm, and two secrets.

## What it is made of

The console is a Node application built from the `ankka-console` image, run as two instances. It holds no
database and no state of its own: a person's session is a sealed cookie carrying their refresh token, plus
the identity provider's own session, so any instance serves any request and an instance can be replaced
without signing anyone out.

It holds no grant on the Kubernetes API, and its pods do not mount a service account token. It reaches two
things: the control plane, as the person signed in, and the identity provider.

In a cluster, every connection is mutual TLS with a certificate the installation's service authority issues
for `ankka://platform/console`. The console serves the gateway only, refusing any other caller's certificate,
and presents its certificate to the control plane. Its network admits the gateway's proxy on its serving port
and anyone on its readiness port, 7627. See [Networking and TLS](networking.md).

## The hostname

The console is served at `console.<base domain>`. Its full address, host and port together, is written once
in the `ankka-platform` ConfigMap:

```yaml
data:
  baseDomain: example.com
  httpsPort: "443"
  consoleAuthority: console.example.com
```

`consoleAuthority` is `console.<baseDomain>`, followed by `:<httpsPort>` when the port is not 443. The overlay
copies it into the console's own configuration and into the realm client's redirect address. Keep it equal to
its derivation; a mismatch is a sign-in that the identity provider refuses to return from.

## The realm client and the secrets

The console signs people in as the realm client `ankka-console`: a confidential client using the
authorization code flow with PKCE, whose default client scope is `ankka-controlplane`. The realm file declares
it.

The console reads two values from the Secret `ankka-console-secrets` in `ankka-console`:

| Key | Value |
|---|---|
| `clientSecret` | The `ankka-console` client's secret in the realm. |
| `sessionSecret` | Any long random string. It seals every session cookie. |

The local overlay ships development values. The example cloud overlay deletes that Secret and removes the
client's secret from the realm import, so Keycloak generates one and nothing secret is written into the
overlay. Once the realm exists, read the generated secret in Keycloak's administration console (Clients →
`ankka-console` → Credentials) and create the Secret:

```bash
kubectl -n ankka-console create secret generic ankka-console-secrets \
  --from-literal=clientSecret="<the ankka-console client's secret>" \
  --from-literal=sessionSecret="$(openssl rand -base64 48)"
```

**Changing `sessionSecret` signs everyone out of the console at once**, and changes nothing else. Rotate it
that way when you want every session to end.

## An installation whose realm predates the console

A realm import creates a realm once and never updates it, so an installation imported before the console
existed has no `ankka-console` client. Add it with Keycloak's administration CLI, inside the Keycloak pod:

```bash
KCADM="kubectl -n ankka-auth exec statefulset/ankka-keycloak -- /opt/keycloak/bin/kcadm.sh"
$KCADM config credentials --server http://localhost:8080 --realm master --user admin --password "<admin password>"
$KCADM create clients -r ankka -s clientId=ankka-console -s 'name=ankka console' \
  -s publicClient=false -s standardFlowEnabled=true -s directAccessGrantsEnabled=false \
  -s 'redirectUris=["https://console.example.com/*"]' -s 'webOrigins=["+"]' \
  -s 'attributes."pkce.code.challenge.method"=S256' \
  -s 'defaultClientScopes=["ankka-controlplane"]'
```

Then read the generated secret and create `ankka-console-secrets` as [the realm client and the secrets](#the-realm-client-and-the-secrets) describes. The local deploy script adds the
client this way when it is missing.

## Readiness and shutdown

An instance is ready once its certificate is loaded, its port is bound and the control plane has answered it.
A console that cannot reach the control plane for a minute at startup exits, naming the address it tried, and
is restarted. On shutdown it tells every open live-update stream to reconnect, which the browser does to
another instance, and exits within ten seconds.

Each request writes one JSON line to standard output: method, path, status, duration, and the subject of the
person signed in. No cookie, header or token is ever logged.

## Run it locally

A console on your own machine runs against the development identity provider in the repository's
`docker-compose.yml` and a control plane started from a checkout, over plain HTTP, with no certificate to set
up:

```bash
docker compose up -d
ANKKA_AUTH_ISSUER=http://localhost:8081/realms/ankka sbt controlPlane/run
cd console && npm ci && npm run dev          # http://localhost:3000, sign in as dev / dev
```

## Leave it out

Nothing else in the platform depends on the console. An installation that does not want it deletes
`../../components/console` from its overlay's components, the `consoleAuthority` replacement and key, and the
cloud overlay's two console patches.

## Configuration

The console is configured by environment variables, which the component sets:

| Variable | Meaning |
|---|---|
| `ANKKA_CONSOLE_CONTROL_PLANE_URL` | The control plane's address. The console reads the identity provider's issuer from its `GET /auth`, so the two always agree. |
| `ANKKA_CONSOLE_AUTHORITY` | The console's own host and port as a browser reaches it. |
| `ANKKA_CONSOLE_CLIENT_ID`, `ANKKA_CONSOLE_CLIENT_SECRET` | The realm client. |
| `ANKKA_CONSOLE_SESSION_SECRET` | Seals the session cookies. |
| `ANKKA_CONSOLE_AUTH_BACKCHANNEL_URL` | Where the console calls the identity provider itself, when its external name does not resolve inside the cluster. |
| `ANKKA_CONSOLE_AUTH_CA` | The authority that address is verified by. |
| `ANKKA_CONSOLE_TLS_DIR` | The console's certificate, key and trusted authority. Set, the console serves TLS and requires the gateway's certificate. |
| `ANKKA_CONSOLE_PORT`, `ANKKA_CONSOLE_PROBE_PORT` | The serving and readiness ports, 9000 and 7627 in a cluster. |
| `ANKKA_CONSOLE_ALLOW_INSECURE_ISSUER` | `true` permits a plain-HTTP identity provider: the development one only. |
