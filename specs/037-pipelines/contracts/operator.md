# Contract: what the operator renders

Held by `RenderingGoldenSuite` (unchanged goldens for every existing resource), `ProjectRenderingSuite`,
`TopicProvisioningSuite`, `features/broker/compaction.feature` and `features/deploying/process-resources.feature`.

## From `AnkkaProject`

- `KafkaTopic <project>.<name>` with `spec.partitions` and, when `compacted`, `spec.config:
  {cleanup.policy: compact}`; no `config` field otherwise. A topic observed uncompacted whose
  declaration says compacted is applied again with the config; the reverse too.
- ConfigMap `ankka-project` in the project's namespace, key `topics.json` (see data-model.md),
  applied server-side by `Action.EnsureProjectConfig`, no owner reference, re-applied on every
  project reconcile.

## From `AnkkaService`

- The platform container (embedded: the service's; process and module: the sidecar's) mounts
  `ankka-project` read-only at `/var/run/ankka/project`, `optional: true`, and gets
  `ANKKA_PROJECT_DECLARATIONS=/var/run/ankka/project/topics.json`.
- For each `AnkkaProject.spec.brokers` entry: a Secret volume of its `secretName` mounted
  read-only at `/var/run/secrets/ankka/brokers/<name>` on the platform container, and the three
  `ANKKA_TOPIC_BROKER_<NAME>_*` variables there. Never on the process container.
- `terminationMessagePolicy: FallbackToLogsOnError` on the platform container.
- The process container's resources are `processCpuMillis`/`processMemoryMiB`, requests equal to
  limits; absent, 100m and 128Mi as today.
- `database: none` → `Provisioning.decide` is `NotNeeded`: no schema-init container, no DB
  credential, no DB certificates, no `ANKKA_DB_*`; `ANKKA_DATABASE=none` on the platform
  container; status has no `database` phase.

## RBAC

- Operator: `configmaps` get/list/watch/create/patch (present).
- Control plane: `configmaps` get/create/patch in project namespaces (new), for
  `ankka-project-schemas`.
