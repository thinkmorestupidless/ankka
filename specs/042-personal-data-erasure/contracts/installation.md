# Contract: installing the keyring

Held by `ErasureClusterFeatures` (k3s), `RenderingGoldenSuite`, `RemoteOverlaySuite`,
`ReservedProjectIdsSuite`, the template suites and the compose smoke in `quickstart.md`.

## `kustomization/components/keyring/`

- `deployment.yaml`: `ankka-keyring`, 2 replicas, image `ankka-keyring`, `ANKKA_CLUSTER_MODE=kubernetes`
  with the five bootstrap variables, `ANKKA_HTTP_PORT=9020`, `ANKKA_ERASURE_LOG_URL`, `ANKKA_S3_*` by
  reference to `ankka-platform-erasure-log` (optional), `ANKKA_SECRET_KEY` from `ankka-keyring-secret-key`
  (a Secret the overlay carries, as the control plane's), `preStop` sleep, the probe port 7627.
- `cluster.yaml`: a CNPG `Cluster` `ankka-keyring-db` in `ankka-keyring`, `bootstrap.initdb` with the
  DDL ConfigMap and the `99-grants.sql` fragment, exactly as `components/postgres`; backups are 041's.
- `zero-trust.yaml`: certificates `ankka://platform/keyring` from both authorities; a network policy
  admitting every `ankka-managed` namespace's pods and `ankka-controlplane` to 9020, and the gateway's
  proxy pods to 9020 for `/decrypt`; the control plane's policy gains the keyring's identity on 9000
  for `/erasures/log`.
- `route.yaml`: `HTTPRoute` `keyring.<base>` → `/decrypt` only.
- Listed in `overlays/local` and `overlays/cloud` after `controlplane`; the platform ConfigMap gains
  nothing (the keyring's address is a Service name).

## The operator

- Reads its own `ANKKA_KEYRING_URL`, an operator setting the keyring component patches onto the
  operator's Deployment exactly as the `garage` component patches `ANKKA_OBJECT_STORE_*` and the
  `broker` component its three settings; with it set, renders `ANKKA_KEYRING_URL` on the platform
  container of every hosting but web (as `ANKKA_OTLP_ENDPOINT` is). An installation without the
  component renders exactly what it rendered before (`RenderingGoldenSuite` unchanged); one with it
  renders one more variable (`RenderingUnchangedSuite` repinned for that variable only). A service
  with the variable opens its channel at start and is not ready until the keyring's log is applied,
  whether or not it has a personal field yet.
- Renders `ANKKA_S3_*` on the platform container too when `provisionObjectStorage` is set
  (`PlatformVariables.ObjectStoragePrefix` shared).
- At start, with object store settings, ensures the bucket `ankka-platform-erasure-log` and the
  credential Secret `ankka-platform-erasure-log` in `ankka-controlplane` and in `ankka-keyring`
  (create-only; 409 is success), through `Action.EnsurePlatformBucket` and `EnsureStorageCredential`.
- A descriptor that sets `ANKKA_KEYRING_URL` is refused (`ServiceSpec.problems`); the variable is
  routed to the platform container only and withheld from a module's `config`.

## Compose

```yaml
keyring-db:  # postgres:17-alpine, no host port, the runtime's DDL in initdb.d, volume ankka-keyring-pgdata
keyring:     # ankka-keyring:latest (templates: ghcr.io/thinkmorestupidless/ankka-keyring:<version>)
             # ports 127.0.0.1:9020:9020; ANKKA_CLUSTER_MODE=local; ANKKA_DB_HOST=keyring-db;
             # ANKKA_SECRET_KEY=${ANKKA_KEYRING_SECRET_KEY:?set ANKKA_KEYRING_SECRET_KEY}
```

`sbt shoppingCart/run` with `ANKKA_KEYRING_URL=http://localhost:9020`; the `sidecar` and `runtime`
compose profiles get `ANKKA_KEYRING_URL=http://keyring:9020`. The templates' compose files carry the
pair, and `PythonTemplateSuite`, `TypeScriptTemplateSuite`, `RustTemplateSuite` and `TemplateSuite`
assert the generated service starts with them and writes one personal field.

## DDL and the seven lists

`kustomization/components/postgres/ddl/50-erasure-postgres.sql` (`ankka_erasures_applied`), named in
`SharedPostgres.DdlResources`, `CnpgRendering.SchemaFiles`, `SchemaResourceSuite`,
`CnpgRenderingSuite`, `SidecarClusterSuite`, the postgres component's `kustomization.yaml` and
`cluster.yaml`; the golden render files gain its text.

## CI

`.github/ci-coverage.py`: `keyring/**` under `build`; `kustomization/components/keyring/**` under
`build`; `cluster-suites.py` picks `ErasureClusterFeatures` up by its `ankka.cluster.tests` read.
The `images` job and `docker:publishLocal` gain `ankka-keyring`; the release's registry check lists it.
