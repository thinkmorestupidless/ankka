# Contract: control plane configuration

Two new settings, both on the control plane only, under `ankka.controlplane.organizations` in
`controlplane/src/main/resources/reference.conf`.

| Variable | Key | Values | Default | Effect |
|---|---|---|---|---|
| `ANKKA_ORGANIZATION_CREATION` | `organizations.creation` | `open`, `platform-admin` | `open` | who may `POST /organizations/{id}`: anyone logged in, or holders of the `platform-admin` realm role only |
| `ANKKA_SIGNUP_URL` | `organizations.signup-url` | a URL, or empty | empty | appended to the creation refusal as `; sign up at <url>`; unused when creation is `open` |

- Any other value for `creation` is a startup failure whose message names
  `ankka.controlplane.organizations.creation`, `ANKKA_ORGANIZATION_CREATION` and both values.
- Neither setting affects any route but organization creation.
- Neither is set by any overlay in this repository; a hosted installation's cluster directory sets
  both on the control plane Deployment.

## Spoke shape (existing settings, newly documented and proven)

| Variable | Set to |
|---|---|
| `ANKKA_AUTH_ISSUER` | the hub realm's issuer, `https://auth.<hub base domain>/realms/ankka` |
| `ANKKA_AUTH_JWKS_URL` | `<that issuer>/protocol/openid-connect/certs`, over the public address |

With both set, the spoke deploys no Keycloak component; `ANKKA_BASE_DOMAIN` remains its own, for
exposed services' hostnames.
