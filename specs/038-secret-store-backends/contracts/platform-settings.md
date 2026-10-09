# Contract: platform settings this feature adds

Declared once, in `core`'s `PlatformVariables` (the operator compiles the same file;
`PlatformDeclarationSuite` fails on a second copy). The table in `data-model.md` says who sets and
reads each; this file says what the operator and the overlays do with them.

## On the `ankka-platform` ConfigMap (both overlays)

| Key | Variable | Local overlay value | Cloud overlay placeholder |
|---|---|---|---|
| `secretBackend` | `ANKKA_SECRET_BACKEND` | `postgres` | `SET: postgres or secret-manager` |
| `secretMove` | `ANKKA_SECRET_MOVE` | `""` | `""` |
| `secretVersionsKept` | `ANKKA_SECRET_VERSIONS_KEPT` | `2` | `2` |
| `secretRecordRetention` | `ANKKA_SECRET_RECORD_RETENTION` | `365d` | `365d` |
| `cloudProvider` | `ANKKA_CLOUD_PROVIDER` | `none` | `SET` (044 owns the rest) |
| `cloudAccount` | `ANKKA_CLOUD_ACCOUNT` | `""` | `SET` |
| `cloudLocation` | `ANKKA_CLOUD_LOCATION` | `""` | `SET` |

Kustomize `replacements` copy each into the operator's env (`ANKKA_SECRET_BACKEND`, `_MOVE`,
`_VERSIONS_KEPT`, `ANKKA_CLOUD_PROVIDER`, `_ACCOUNT`) and the control plane's (`ANKKA_SECRET_BACKEND`,
`ANKKA_SECRET_RECORD_RETENTION`, `ANKKA_CLOUD_PROVIDER`, `_ACCOUNT`). `RemoteOverlaySuite` asserts
the shape (the variable set once, the placeholder gone), not the presence of a string.

## What the operator renders on every platform container

Only when the setting is set (an installation that sets nothing renders what it rendered before —
`RenderingUnchangedSuite` must repin with no object changed):

- `ANKKA_SECRET_BACKEND=<value>` when not `postgres`
- `ANKKA_SECRET_MOVE=<phase>` when set
- `ANKKA_SECRET_VERSIONS_KEPT=<n>` when not `2`
- `ANKKA_CLOUD_ACCOUNT=<account>` when set
- `ANKKA_CLOUD_LOCATION=<location>` when set
- never `ANKKA_SECRET_RECORDS_URL`: the runtime's Kubernetes cluster overlay carries the control
  plane's in-cluster address (`https://ankka-controlplane.ankka-controlplane.svc:9000`) as the
  default, so no line is rendered and no pod template changes on upgrade. The variable is for an
  installation whose control plane is reached elsewhere, set on the ConfigMap like the others.

Never on the developer's process container, the proxy, or the module (`RuntimeOnly`). A descriptor
that sets any of them is refused (`PlatformOnly`, `ServiceSpec.problems`).

## What the control plane reads at start

`SecretBackend` (choose the writer; refuse `secret-manager` under provider `none`), the retention,
the record database's `ANKKA_SECRET_RECORDS_DB_*` (from `ankka-secret-reads-db-app`, rendered by
`components/secret-reads` and read by the control plane Deployment's env), and the cloud account
(for the writer's REST calls).

## Service-side config keys (reference.conf), generated into `docs/reference/configuration.md`

`ankka.secrets.backend`, `ankka.secrets.timeout`, `ankka.secrets.record-timeout`,
`ankka.secrets.records-url`, `ankka.secrets.move`, `ankka.secrets.versions-kept`,
`ankka.secrets.secret-manager.endpoint`, `.token`, `.identity`, `ankka.cloud.provider`,
`ankka.cloud.account`, `ankka.cloud.location`. Each needs a sentence in the prose beside the table
or the docs check fails.
