# Contract: Expose and Unexpose

**Satisfies**: FR-001 to FR-005, FR-011, FR-019a

## Control plane HTTP API

```
POST /services/{projectId}/{name}/expose      Authorization: Bearer <token>
POST /services/{projectId}/{name}/unexpose    Authorization: Bearer <token>
```

Both return `200` with the service's `ServiceStatus`, which now carries `hostname`:

```json
{
  "name": "cart",
  "projectId": "checkout",
  "lifecycle": "Ready",
  "generation": 3,
  "image": "sample-shopping-cart:latest",
  "readyInstances": 3,
  "desiredInstances": 3,
  "confirmed": true,
  "database": "provisioned",
  "hostname": "https://cart-checkout.127.0.0.1.sslip.io"
}
```

`hostname` is absent when the service is not exposed. It is a full URL with scheme, so a client
can use it verbatim; the scheme is always `https`.

### Refusals (`409 Conflict`, problem body naming the cause)

| Cause | Message |
|---|---|
| descriptor has `"http": false` | `service 'cart' serves no HTTP ("http": false); there is nothing to expose` |
| label too long | `hostname label 'cart-…' is 71 characters, over the 63 character limit for a DNS label` |
| hostname held | `hostname cart-checkout.<base> is already exposed by service 'cart' in project 'checkout'` |
| no base domain | `the control plane has no base domain configured (ANKKA_BASE_DOMAIN); nothing can be exposed` |

`404` for an unknown service or project, as every other service route.

### What does not change

`PUT /services/{projectId}/{name}` (apply) neither reads nor writes exposure. `restart`, `pause`,
`resume` leave it alone. `GET` and the listing include `hostname`.

## CLI

```
ankka services expose cart            # prints the hostname
ankka services unexpose cart
ankka services get cart               # HOSTNAME row: the URL, or "-"
ankka services list                   # HOSTNAME column
ankka config set ca ~/.ankka/local-ca.crt
ankka config unset ca
```

`services list` columns become: `NAME  STATUS  READY  IMAGE  HOSTNAME`.

Exit codes as today: 0 success, 1 the server refused (message on stderr), 2 usage.

## CLI transport

- `config.ca`, when set, is a PEM file whose certificates are added to the trust store the CLI's
  HTTP client uses. The platform's default roots stay trusted.
- There is no option to skip verification. A URL whose certificate is not trusted fails with the
  JDK's message plus one line: `if this is a local ankka cluster, run: ankka config set ca
  ~/.ankka/local-ca.crt`.
