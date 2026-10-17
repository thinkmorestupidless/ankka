# Data Model: Installation Models

What exists, who writes it, and what each state means. Decisions are in
[research.md](research.md); the wire and the rendered objects are in [contracts/](contracts/).

## The declaration — ConfigMap `models-declaration`, key `models.json`

| | |
|---|---|
| Namespace | `models-system` |
| Written by | the platform administrator, in the installation's overlay; never the platform |
| Shape | `{"models": ["qwen3:0.6b", "llama3.2:1b"]}`: an ordered list of Ollama tags |
| Read by | the keeper, at start and on each change of the mounted file's modification time |
| Rules | a tag appears once; order is fetch order; an empty list is a server that serves nothing |

## A declared model, as the keeper sees it

One row per tag in the declaration, plus one per tag Ollama still holds that the declaration no
longer names (until it is removed).

| State | Meaning | How it is left |
|---|---|---|
| `fetching` | declared; Ollama does not hold it, or holds it and the pull has not reported `success` | `served` when pulled and loaded; `failed` when the pull ends without `success` |
| `served` | fetched, loaded with `keep_alive: -1`, digest recorded; the only state a service naming it starts in | `removing` when the tag leaves the declaration; `fetching` when a restart finds it held but not loaded (loading, not pulling) |
| `failed` | the pull ended with an error; `detail` is Ollama's last status line | `fetching` on the next retry (backoff one minute to one hour) or at once on a declaration change |
| `removing` | held by Ollama, no longer declared | gone when `/api/delete` succeeds |

Fields per row: `name` (the tag), `state`, `digest` (once `served`; the `digest` `/api/tags`
reports, 64 hex characters), `capabilities` (once fetched; from `/api/show`), `detail` (when
`failed`), `since` (when the state was entered).

A served model is **never fetched again** by the keeper. A restart reloads what the volume holds and
fetches nothing for it. A platform administrator re-fetches by removing the tag from the declaration
and adding it back, which deletes and pulls; the documentation says so.

## The status document — `ModelServerDocument`

One case class in `modules/core`, compiled by the operator too (as `PlatformVariables` is), written
by the keeper and read by the operator, the sidecar and the control plane.

```json
{
  "server": "https://models.models-system.svc:8443",
  "ready": true,
  "pass": "2026-10-17T15:04:05Z",
  "models": [
    {"name": "qwen3:0.6b", "state": "served", "digest": "7d4f…", "capabilities": ["completion", "tools", "thinking"], "since": "…"},
    {"name": "no-such-model:1b", "state": "failed", "detail": "pull model manifest: file does not exist", "since": "…"}
  ],
  "calls": {"answered": 1240, "failed": 3, "inFlight": 1, "waiting": 0}
}
```

`ready` is true when Ollama answers and the loop has completed one pass. `calls` are counters since
the keeper started (`answered`, `failed`, `inFlight`, and `waiting`, which is in flight beyond
what the server runs at once, `ANKKA_KEEPER_PARALLEL`), for the suite's egress check and the
status line; the telemetry store holds the
same as metrics.

## What a service names — read by the operator from the resource's `env`

For every `ANKKA_MODEL_<NAME>_PRESET` whose value is `installation` (feature 052's family):

| Field | From | Rule |
|---|---|---|
| provider name | `<NAME>`, lowercased | 052's naming rules |
| model | `ANKKA_MODEL_<NAME>_MODEL` | required; refused absent |
| address | `ANKKA_MODEL_<NAME>_URL` | refused present: "an installation model takes no address" |
| key | `ANKKA_MODEL_<NAME>_KEY` | refused present: "an installation model takes no key" |

`InstallationModels.named(spec)` returns the list; empty means `NotNeeded`.

## The operator's plan — `InstallationModelsPlan`

| Plan | When | Lifecycle | Deployment |
|---|---|---|---|
| `NotNeeded` | the service names no installation model | as before | applied |
| `Ready(models)` | every named model is `served` on this cluster's server | as before | applied, with `ANKKA_MODELS_ADDRESS` |
| `Waiting(detail)` | a named model is `fetching`, or the status cannot be read | `UpdateInProgress`, detail | withheld |
| `Failed(detail)` | no settings on this operator; a named model is not declared, or `failed`, or `removing`; an address or key given beside the preset | `Failed`, detail | withheld |

Details, exactly: `this cluster has no model server`; `installation model "<m>" is not served;
served: a, b` (or `served: none`); `installation model "<m>" is still being fetched`; `installation
model "<m>" failed: <reason>`; `an installation model takes no address (ANKKA_MODEL_<NAME>_URL)`;
`an installation model takes no key (ANKKA_MODEL_<NAME>_KEY)`; `the model server is not answering`.

## The resource's status block — `AnkkaServiceStatus.installationModels`

```scala
final case class InstallationModelsStatus(
    /** "Ready", "Waiting" or "Failed". */
    phase: String = "",
    /** Each named model and the digest served, once Ready. */
    models: Vector[ServedModel] = Vector.empty,
    /** Why it is waiting or failed. Never a secret. */
    detail: Option[String] = None
)
final case class ServedModel(name: String, digest: String = "")
```

Absent when the service names no installation model, so no existing resource's status changes.
Declared in `ankkaservice.yaml`; `CrdSchemaSuite` compares both directions.

## The operator's settings — `ModelServerSettings`

| Field | Variable | Value in the component |
|---|---|---|
| `address` | `ANKKA_MODELS_ADDRESS` | `https://models.models-system.svc:8443` |
| `statusUrl` | `ANKKA_MODELS_STATUS_URL` | `http://models.models-system.svc:8080/ankka/models` |
| `namespace` | `ANKKA_MODELS_NAMESPACE` | `models-system` |

All three or none; some and not others refuses the operator's start, naming the missing.

## The platform setting every platform container gets

`ANKKA_MODELS_ADDRESS`: platform-only (a descriptor may not give it; `ServiceSpec.problems` refuses
it as it refuses `ANKKA_CLUSTER_MODE`), rendered by the operator on the platform container of every
embedded and process-hosted service when the settings are present, absent otherwise. Never on the
developer's container, never on the proxy. Not under the `ANKKA_MODEL_` prefix, by design (R4).

## The installation wire — `Installation.models`

```scala
final case class InstallationModels(clusters: Vector[ClusterModels])
final case class ClusterModels(cluster: String, server: String, ready: Boolean, models: Vector[ModelLine])
final case class ModelLine(name: String, state: String, digest: Option[String] = None, detail: Option[String] = None)
```

Absent when the control plane has no `ANKKA_MODELS_ADDRESS`. One cluster, `home`, until feature 050.
Read live; nothing journaled.

## In the cluster

| Object | Namespace | Written by | Outlives |
|---|---|---|---|
| PersistentVolumeClaim `models-models-0` | `models-system` | the StatefulSet | the pod; never deleted by the platform |
| Secret `ankka-models-tls` | `models-system` | cert-manager | renewed in place |
| the pod's two containers | `models-system` | the StatefulSet | replaced on an image or patch change; the volume stays |
