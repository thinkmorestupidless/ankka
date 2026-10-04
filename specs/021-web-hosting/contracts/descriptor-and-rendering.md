# Contract: the descriptor's refusals, and what the operator renders

## Refusals

`ServiceSpec.problems` in `controlplane-api`, so the CLI and the control plane say the same words.
Each is pinned by `DescriptorSuite` or `HostingSuite`, and each is in
`docs/reference/service-descriptor.md`, which `docs check` holds to the page.

| Problem | Message |
|---|---|
| unknown hosting | `hosting must be "embedded", "process", "wasm" or "web", not "<value>"` |
| `"http": false` with web | `a web-hosted service's proxy serves HTTP; remove "http": false` |
| `protocol` with web | `protocol is meaningful only for process or wasm hosting` (the existing message) |
| `runtime` with web | `runtime is meaningful only for a service built on ankka; a web-hosted service declares none` |
| `PORT` or `ANKKA_SERVICES_URL` in `env`, with web | `env var '<name>' is set by the platform and cannot be declared` (the existing message) |
| a variable starting `ANKKA_DB_`, with web | `env var '<name>' supplies a database, and a web-hosted service has none` |
| `processPort` out of range | `processPort <n> is outside the range 1-65535` |
| `processPort` equal to `port` | `processPort <n> is the service's own port; the process and the proxy cannot both listen on it` |
| `processPort` 7626, 7627, 7628, 7630 or 17355 | `processPort <n> is used by the platform` |
| `port` 7627 or 7630, with web | `service port <n> is used by the platform's proxy` |
| a variable taken from a Secret the platform issues, **any hosting** | `env var '<name>': secret '<secret>' is issued by the platform and cannot be read by a service` |
| a mount path with no leading `/` | `mount '<path>': a path starts with "/"` |
| a mount at `/` | `mount '/': a mount cannot be every path; the process serves what no mount does` |
| a malformed mount path | `mount '<path>': a path is whole segments of letters, digits, "-", ".", "_" and "~", with no trailing "/"` |
| the same path twice | `mount '<path>' is declared more than once` |
| one path inside another | `mount '<inner>' is inside mount '<outer>'` |
| a mount's service not a name | `mount '<path>': '<service>' is not a service name` |
| a mount of itself | `mount '<path>': a web-hosted service cannot mount itself` |
| a caller entry of no known shape | `caller '<entry>' is not "<service>", "<project>/<service>" or "*"` |
| a caller entry twice | `caller '<entry>' is declared more than once` |
| `mounts`, `callers` or `processPort` without web | `<field> is meaningful only for web hosting` |

`PORT` is refused only for web hosting. An embedded service may have set it for its own reasons,
and a rule that refused it everywhere would refuse a descriptor that is applied today.

A Secret the platform issues is one whose name ends `-service-tls`, `-mount-tls`, `-cluster-tls` or
`-database-tls`, or is the project's database cluster's own: `ankka-db`, or any name starting
`ankka-db-`. The operator's names for these are constants (`ZeroTrust`, `CnpgRendering`); a suite
with both on its classpath holds the descriptor's list to them, so a new kind of certificate cannot
be added without the rule. That rule is for every hosting, and it does refuse a descriptor that could be
applied today: one that reads a certificate's key, which no descriptor has a reason to do.

## What is rendered for `hosting: "web"`

In `Rendering.render`'s order. Everything not listed is as for any service.

1. **Namespace**: unchanged.
2. **Database**: nothing. The plan is `NotNeeded`; no CNPG object is read or written.
3. **Identity**: the `ServiceAccount` only. No `Role`, no `RoleBinding`.
4. **Zero trust**:
   - `Certificate` `<service>-service`, unchanged;
   - `Certificate` `<service>-mount`, only when `mounts` is not empty: secret `<service>-mount-tls`,
     issuer `ankka-service`, `uris: [ankka://<project>/<service>/mount]`, no DNS names,
     `usages: [client auth]`, the durations every certificate has;
   - `NetworkPolicy` `<service>-http`, unchanged;
   - `NetworkPolicy` `<service>-probe`: ingress on 7627 from anywhere, selecting the service's pods;
   - no cluster certificate and no cluster policy.
5. **Deployment**: as below.
6. **Service, HTTPRoute, BackendTLSPolicy**: unchanged. The gateway reaches the proxy, which serves
   the service certificate under the names the policy expects.

### The pod

| | Proxy | Process |
|---|---|---|
| Container name | `<service>` | `<service>-app` |
| Image | the operator's `ANKKA_PROXY_IMAGE` | the descriptor's |
| `imagePullPolicy` | `IfNotPresent` | `IfNotPresent` |
| Ports | `http` (the descriptor's port), `probe` 7627 | none declared |
| Environment | the proxy's settings (`data-model.md`) | the descriptor's `env`, `PORT`, `ANKKA_SERVICES_URL` |
| Mounts | `/var/run/secrets/ankka/service`; `/var/run/secrets/ankka/mount` when there are mounts | none |
| Resources | 250m, 192Mi, requests equal limits | the instance type's, requests equal limits |
| Readiness | `httpGet /ready` on `probe`, every 5 s | none |
| Liveness | none | none |
| `preStop` | sleep 5 s | sleep 5 s |

Pod spec: `serviceAccountName` as for every service, `automountServiceAccountToken: false`, the
pull secret when the project has one, no init containers, no `fsGroup`.

The `HTTPRoute` of an exposed web-hosted service is the one every exposed service has: one rule,
one backend, no filter, whatever its mounts. Mounts are the proxy's, never the gateway's.

Pod template labels: the identity labels and `ankka.thinkmorestupidless.com/transport=tls`.
Not `…/formation=bootstrap`.

Deployment: the selector, the strategy (`RollingUpdate`, surge 1, unavailable 0), the `restarts`
counter and the generation annotation are exactly an embedded service's.

### Render problems

| Problem | When |
|---|---|
| `operator has no proxy image` | hosting is `web` and `ANKKA_PROXY_IMAGE` is empty |
| `unknown hosting "<value>"` | hosting is none of the four; today this renders as embedded |

### What must not change (FR-037)

For `embedded`, `process` and `wasm`, `Rendering.render` produces the objects it produces today,
byte for byte. Pinned three ways:

- `RenderingUnchangedSuite`: a fixture of the rendered output for one service of each existing
  mode, written before any change to `Rendering` and compared after. It is rewritten only under
  its own switch, `-Dankka.rendering.pin=true`, never under `ankka.docs.update`, which other
  suites share;
- the existing "unchanged by hosting" cases in `ProcessHostingRenderingSuite` and
  `WasmHostingRenderingSuite`;
- on k3s, the existing case that ten reconciles of an unchanged service write nothing.

Identical objects are what "nothing restarts" rests on: an apply that changes nothing Kubernetes
sees changes nothing Kubernetes does. A cluster running the operator as it was before this feature
cannot be built inside a test, so no test claims to.

A service that changes hosting to or from `web` is rendered as any service of its new hosting.
What it had before and no longer needs is left, owned by the resource.

## The operator's own settings

| Variable | Meaning | Default |
|---|---|---|
| `ANKKA_PROXY_IMAGE` | the image run beside a web-hosted service's process | `ankka-proxy:latest` |
| `ANKKA_HTTPS_PORT` | the port the gateway's HTTPS listener is reached on | 443 |

Both in `kustomization/components/operator/operator.yaml`. The port is replaced from
`ankka-platform.httpsPort` by the overlays, as the control plane's is. The cloud overlay names the
proxy's image with a patch on the container `ankka-operator`, beside the sidecar's.

## The mount certificate in the runtime

`RotatingTls.parseMountUri(text)`: `ankka://<project>/<service>/mount` and nothing else.
`Caller.fromCertificate`:

1. any URI equal to `ankka://gateway`: `Gateway` (unchanged);
2. otherwise the first URI that is a service's: `Service(project, service)` (unchanged);
3. otherwise any URI that is a mount's, **when its project is this service's own**: `Gateway`;
   a mount's URI of another project is refused;
4. otherwise refused (unchanged).

A service URI wins over a mount URI on one certificate, so a certificate cannot be both. The
operator never writes both on one. `fromCertificate` therefore takes this service's own identity,
which `CallerSource` already holds.
