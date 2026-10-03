# Contract: the `ANKKA_AUTH_` variables

Set in a service descriptor's `env`, or in the shell for a local run. Read by the
`ankka-auth-oidc` module in a Scala service and by the sidecar for every other language. The
control plane's own `ANKKA_AUTH_ISSUER`, `ANKKA_AUTH_JWKS_URL` and `ANKKA_AUTH_JWKS_CA` are
unchanged and are not part of this set.

| Variable | Required | Meaning |
|---|---|---|
| `ANKKA_AUTH_ISSUERS` | to authenticate anything | comma-separated issuer names, each `[A-Za-z][A-Za-z0-9-]*`, no repeats |
| `ANKKA_AUTH_<NAME>_ISSUER` | per name | the exact `iss` a token must carry |
| `ANKKA_AUTH_<NAME>_JWKS_URL` | per name | where the issuer's keys are fetched |
| `ANKKA_AUTH_<NAME>_AUDIENCE` | per name | the `aud` a token must contain |
| `ANKKA_AUTH_<NAME>_CA` | no | a PEM bundle the keys fetch trusts alone; otherwise the JVM's trust store |
| `ANKKA_AUTH_<NAME>_TYP` | no | a `typ` header value a token must carry, such as `Bearer` for Keycloak; otherwise not checked |
| `ANKKA_AUTH_<NAME>_CLOCK_SKEW` | no | tolerance on `exp` and `nbf`; default `60s` |
| `ANKKA_AUTH_REALM` | no | the `realm` named in a challenge; default `ankka` |

`<NAME>` is the name upper-cased with `-` as `_`.

Example, a service fronting a customer realm and a staff realm:

```json
"env": [
  { "name": "ANKKA_AUTH_ISSUERS", "value": "customers,staff" },
  { "name": "ANKKA_AUTH_CUSTOMERS_ISSUER",   "value": "https://auth.shop.example/realms/customers" },
  { "name": "ANKKA_AUTH_CUSTOMERS_JWKS_URL", "value": "https://auth.shop.example/realms/customers/protocol/openid-connect/certs" },
  { "name": "ANKKA_AUTH_CUSTOMERS_AUDIENCE", "value": "shop" },
  { "name": "ANKKA_AUTH_CUSTOMERS_TYP",      "value": "Bearer" },
  { "name": "ANKKA_AUTH_STAFF_ISSUER",       "value": "https://auth.shop.example/realms/staff" },
  { "name": "ANKKA_AUTH_STAFF_JWKS_URL",     "value": "https://auth.shop.example/realms/staff/protocol/openid-connect/certs" },
  { "name": "ANKKA_AUTH_STAFF_AUDIENCE",     "value": "backoffice" }
]
```

## Routing on the platform

| Hosting | Where the variables go |
|---|---|
| embedded | the one container |
| process | the sidecar container only; the app container never sees them (`SidecarEnvPrefixes`) |
| wasm | the runtime's container; the module's `config` import answers absent (`ReservedPrefixes`, already) |

`ServiceSpec.problems` refuses none of them. A descriptor may set them with `secretKeyRef`.

## Failure at start

A malformed set is refused with every problem named, by the Scala service when it builds the
ACL and by the sidecar before it dials the process. A sidecar with an `AUTHENTICATED` endpoint or
route and no `ANKKA_AUTH_ISSUERS` refuses in discovery's report, naming the route and the variable.
A set that is present and well-formed on a service with no authenticated route is read and never
asked; nothing is fetched.
