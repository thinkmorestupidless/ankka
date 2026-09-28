# Contract: configuration and environment

## Environment variables read by the runtime (new)

| Variable | Set by | Meaning |
|---|---|---|
| `ANKKA_DB_SSL_MODE` | credential Secret (provisioned) or the descriptor (supplied database) | `disable` (default when unset), `require`, `verify-ca`, `verify-full` |
| `ANKKA_DB_SSL_ROOT_CERT` | same | path of the CA to verify the server against |
| `ANKKA_DB_SSL_CERT` / `ANKKA_DB_SSL_KEY` | same | client certificate and PKCS#8 key; when set, no password is sent |
| `ANKKA_NAMESPACE_PREFIX` | the operator, never a descriptor | how a project id becomes a namespace, for `ServiceClient` |
| `ANKKA_LOCAL_CALLER_TOKEN` | the Python/TypeScript integration testkits, for the sidecar | the local-mode impersonation token (see `acl-and-caller.md`) |
| `ANKKA_AUTH_JWKS_CA` | the control plane manifest | CA file to verify the identity provider's certificate on the key fetch |

`ANKKA_DB_PASSWORD` stays optional; the credential Secret no longer carries it.

## Descriptor rules (`ServiceSpec.problems`)

Refused in `env`: the existing five cluster variables, `ANKKA_HTTP_PORT`, and now
`ANKKA_NAMESPACE_PREFIX`. Allowed: every `ANKKA_DB_*` (the supplied-database escape hatch).

## Configuration keys (`reference.conf`, generated into `docs/reference/configuration.md`)

```
ankka.http.tls.enabled            = off       # on in the Kubernetes overlay
ankka.http.tls.directory          = ""        # tls.key, tls.crt, ca.crt
ankka.tls.cluster-directory       = ""        # management server + bootstrap client
ankka.tls.service-directory       = ""        # ServiceClient
ankka.tls.reload-interval         = 1m        # how often RotatingTls checks file mtimes
ankka.probe.enabled               = off       # on in the Kubernetes overlay
ankka.probe.port                  = 7627
```

Kubernetes overlay (`ankka-cluster-kubernetes.conf`) additions:

```
pekko.remote.artery.transport = tls-tcp
pekko.remote.artery.ssl.ssl-engine-provider = org.apache.pekko.remote.artery.tcp.ssl.RotatingKeysSSLEngineProvider
pekko.remote.artery.ssl.rotating-keys-engine.secret-mount-point = /var/run/secrets/ankka/cluster
pekko.http.server.parsing.tls-session-info-header = on
ankka.http.tls { enabled = on, directory = /var/run/secrets/ankka/service }
ankka.tls.cluster-directory = /var/run/secrets/ankka/cluster
ankka.tls.service-directory = /var/run/secrets/ankka/service
ankka.probe.enabled = on
pekko.management.cluster.bootstrap.contact-point.http-client.ca-path = ""   # stays empty on purpose (research R2)
```

Every directory is a fixed convention, not a variable: there is nothing a descriptor could set,
and nothing an image needs to be told.

## Ports (`docs/reference/runtime-endpoints.md`, `docs/platform/networking.md`)

| Port | Name | Transport | Reachable from |
|---|---|---|---|
| descriptor `port` (9000) | `http` | mutual TLS | the gateway, ankka workloads |
| 17355 | `remoting` | mutual TLS | the service's own pods |
| 7626 | `management` | mutual TLS (client certificate required) | the service's own pods |
| 7627 | `probe` | plain HTTP, `GET /ready` only | anywhere (the kubelet) |

## Control plane refusals (`Compatibility`)

`runtime <declared> predates mutual TLS; this platform requires 0.8.0 or later` — via the existing
`Unavailable` detail path, before any resource is written.
