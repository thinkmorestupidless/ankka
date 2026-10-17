# Contract: the operator, the resource's status, and what a member reads

Decisions are in [research.md](../research.md) (R4, R6, R7, R11); shapes in
[data-model.md](../data-model.md).

## The operator's settings

`ModelServerSettings.read`: `ANKKA_MODELS_ADDRESS`, `ANKKA_MODELS_STATUS_URL`,
`ANKKA_MODELS_NAMESPACE`, with system properties `ankka.operator.models-address`,
`ankka.operator.models-status-url`, `ankka.operator.models-namespace`. All three or none; otherwise
the operator refuses to start: `the model server is configured by halves: ANKKA_MODELS_STATUS_URL is
not set; set all three or none`.

## What the operator reads

Once per reconcile pass, `ModelServerDocumentReader.read(settings)`: `GET <statusUrl>` with the JDK's
client, a two-second timeout, decoded as `ModelServerDocument`. A failure or a timeout is
`Left(reason)`; the pass treats it as `Waiting("the model server is not answering")` for every
service that names an installation model, and requeues them at thirty seconds. Services that name
none are not affected and the status is not read for them.

## The plan, per service

`InstallationModels.named(spec)` then `InstallationModels.decide(named, settings, observed)`; the
table and the exact details are in data-model.md. Then:

| Plan | `Rendering` | `ServiceReconciler` | `status.installationModels` |
|---|---|---|---|
| `NotNeeded` | as before, no variable | as before | absent |
| `Ready(models)` | `ANKKA_MODELS_ADDRESS` on the platform container | Deployment applied | `Ready`, the models with digests |
| `Waiting(detail)` | — | Deployment withheld; lifecycle `UpdateInProgress`; detail | `Waiting`, detail |
| `Failed(detail)` | — | Deployment withheld; lifecycle `Failed`; detail | `Failed`, detail |

"Withheld" is what `ObjectStorage.withheld` means: the Deployment is not applied on this pass;
instances already running are left alone; the operator never deletes a Deployment to hold one back.

`ANKKA_MODELS_ADDRESS` is rendered on the platform container of every embedded and process-hosted
service whenever the settings are present, `Ready` or not needed alike, so that a service that names
no installation model today can name one with no second rollout for the variable. It is never on
the developer's container or the proxy.

## The resource

`AnkkaServiceStatus.installationModels: Option[InstallationModelsStatus]`, declared in
`ankkaservice.yaml` under `status.properties.installationModels` with `phase`, `models` (items
`name`, `digest`) and `detail`. `CrdSchemaSuite` compares both directions, insides included.

## What a member reads of a service

`ankka services get` folds the block as it folds the bucket's: the lifecycle and the detail, and a
line `installation models: qwen3:0.6b (7d4f…)` when `Ready`. The console shows the detail where it
shows every detail; no new console surface.

## The installation

`GET /installation` gains `models` (data-model.md: `InstallationModels`), read live by
`InstallationModelsReader` through the door with the control plane's certificate, when the control
plane has `ANKKA_MODELS_ADDRESS`; absent otherwise. A read that fails answers the cluster with
`ready: false` and a `detail`, never a 500: the rest of the installation's status is still true.

`ankka installation status` prints, after the backups:

```
model server (home): https://models.models-system.svc:8443, ready
  qwen3:0.6b      served   7d4f3a1c
  llama3.2:1b     fetching
  no-such-model:1b failed  pull model manifest: file does not exist
```

or `model server: none` when the field is absent. The MCP tool `installation_status` says in its
description that the served models are listed.

## Refusals a member meets

| Where | When | Text |
|---|---|---|
| `ankka services apply` | the descriptor gives `ANKKA_MODELS_ADDRESS` | `ANKKA_MODELS_ADDRESS is set by the platform and cannot be given by a descriptor` |
| `services get` detail | names an unserved model | `installation model "mistral:7b" is not served; served: qwen3:0.6b` |
| `services get` detail | no server on this cluster | `this cluster has no model server` |
| `services get` detail | address or key beside the preset | `an installation model takes no address (ANKKA_MODEL_LOCAL_URL)` |
| `services get` detail | still being fetched | `installation model "llama3.2:1b" is still being fetched` |
| `services get` detail | the status cannot be read | `the model server is not answering` |
