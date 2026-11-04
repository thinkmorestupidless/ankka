# Contract: the installation, the operator and the keyring component

## `kustomization/components/keyring/`

- `Deployment ankka-keyring` in namespace `ankka-keyring`, replicas from `ANKKA_KEYRING_INSTANCES`
  (replacement from `ankka-platform`), image `ankka-keyring`, probe `/ready` (holds until the log is
  applied).
- CNPG `Cluster keyring-db` (`bootstrap.initdb`, no schema ConfigMap; tables made by code), backed up
  to `platform.backups-keyring` (041; `Buckets.platformBackup`).
- `Certificate` with URI `ankka://platform/keyring` from `ankka-service`; the keyring refuses to start
  on any other identity.
- `NetworkPolicy`: ingress from namespaces labelled `ankka.cloud/personal=true` (the operator labels
  a personal service's namespace), from `ankka-controlplane`, and from the gateway for `Decrypt`.
- The patch that sets `ANKKA_ERASURE=on` on the control plane and the operator.
- Its own secret store (038 `postgres` backend) for the root key on a Postgres installation.
- Read access to the `ankka-project` ConfigMaps (grants) and to `platform.erasure-log`.

The `local` overlay keeps the component out; the `cloud` overlay documents adding it.

## The operator

- Renders `ANKKA_KEYRING_ADDRESS` and the namespace label for a service with `spec.personal`.
- Ensures `platform.erasure-log` (Garage: append-only by key; GCS: versioning and a retention lock)
  when erasure is on, beside `PlatformBackups.actions`.
- On Google Cloud, renders `CloudRequests.wrappingKey` with requester `Platform("keyring")` (R18).
- `RenderingUnchangedSuite`: nothing rendered for a service without `personal` differs.

## The control plane

- Reads `ANKKA_ERASURE` and `ANKKA_KEYRING_INSTANCES` (PlatformVariables).
- Reaches the keyring as `ankka://platform/controlplane`.
- `ErasureSweep` singleton; the `RestoreHold` reconciler for the erasure log (R22).

## Restores (041)

- A service switched to a restore: `ExtensionsReadiness` holds `erasure-replay` until every log entry
  of its project has had R14's redaction applied to its views and its agent sessions marked; the
  restore's status reports `erasureReplayedAt`.
- The keyring's database restored: the keyring's startup order (`keyring-and-protocol.md`).
- The control plane's database restored: `RestoreHold` reconciles `erasure_log` from the bucket
  before release.

## Documentation

New: `docs/build/personal-data.md` (marking, reading erased, the handler, objects and the prefix,
agents, lookup tokens and what they leak, filing from code, the checklist of what to mark, the
subject id as the accepted residual, events written before the feature),
`docs/platform/erasure.md` (turning erasure on, the keyring, root keys, restores, the 60-second
bound, outside machines and what reaches a partner). Changed: `docs/reference/limitations.md`
(erasure stated; what it is not), `docs/reference/service-descriptor.md`,
`docs/reference/configuration.md`, `docs/operate/backups.md`, `docs/build/views.md`,
`docs/build/agents.md`, `docs/build/object-storage.md`, `mkdocs.yml`, the skills
(`ankka-entities`, `ankka-views-consumers`, `ankka-agents`, `ankka-platform`, `ankka-deploy`).
