# Contract: Cluster State → `AnkkaServiceStatus`

**Satisfies**: FR-019 to FR-023

`LifecycleRules.observe(spec, observed): AnkkaServiceStatus` is pure, total and clock-free (the one
timestamp is supplied, not read). Every row is a unit test with no cluster.

## Rules, in order — first match wins

| # | Condition | `lifecycle` | `ready` | `desired` |
|---|---|---|---|---|
| 1 | `spec.paused` | `Paused` | snapshot ready, else 0 | `0` |
| 2 | rendering failed | `Failed` | `0` | `0` |
| 3 | no Deployment yet | `UpdateInProgress` | `0` | `1` |
| 4 | `progressing.status == false && reason == "ProgressDeadlineExceeded"` | `Failed` | snapshot | `specReplicas` |
| 5 | `observedGeneration < k8sGeneration` | `UpdateInProgress` | snapshot | `specReplicas` |
| 6 | `updatedReplicas < specReplicas` | `UpdateInProgress` | snapshot | `specReplicas` |
| 7 | `readyReplicas == specReplicas && specReplicas > 0` | `Ready` | = desired | `specReplicas` |
| 8 | `0 < readyReplicas < specReplicas` | `PartiallyReady` | snapshot | `specReplicas` |
| 9 | `readyReplicas == 0 && specReplicas > 0` | `Unavailable` | `0` | `specReplicas` |
| 10 | `specReplicas == 0` and not paused | `NotDeployed` | `0` | `0` |

Two orderings are load-bearing:

- **Rule 1 before rule 4.** Pause is desired state. A paused service whose pod is still terminating
  is `Paused`, not `Failed`.
- **Rule 5 before rule 6.** `updatedReplicas` is stale until the API server has observed the new
  spec. Reversed, a rollout reports `Ready` for the *previous* generation — the exact "right answer
  about the wrong generation" the guards exist to prevent.

Rule 8 is unreachable at one replica today. It is implemented and tested anyway so multi-replica
support does not have to retrofit it.

## `detail`

| Situation | `detail` |
|---|---|
| Ready, or progressing with no problem | `null` |
| Rendering failed (rule 2) | every problem at once, `"; "`-joined |
| Progress deadline exceeded (rule 4) | first `PodProblem` as `"<reason>: <message>"`, else the condition's message |
| Not ready with pod problems | first `PodProblem` as `"<reason>: <message>"` |
| Write rejected by the API server | the cluster's stated reason (FR-033) |
| `apiVersion` not understood | `"unsupported resource version <v>; this operator understands <known>"` (FR-004) |

The four container waiting reasons that matter:

| Reason | What the operator did |
|---|---|
| `ImagePullBackOff` / `ErrImagePull` | wrong tag, wrong registry, missing pull credentials |
| `CreateContainerConfigError` | referenced a secret or key that does not exist — the spec's named edge case, and the one an operator hits when wiring database credentials |
| `CrashLoopBackOff` | the image starts and exits |

**`detail` never contains a secret value** (FR-023). Only a secret's *name* and *key* appear, and
both come from the descriptor the operator wrote. This is structural, not a discipline: the
operator has no RBAC verb on `secrets` at all (see [configuration.md](./configuration.md)), so it
cannot read one.

## Generations

`status.generation` echoes `spec.generation` — the ankka generation the operator acted on, never
one inferred from the cluster. `status.observedGeneration` echoes the `metadata.generation` the
operator saw. See [custom-resource.md](./custom-resource.md) for why both exist.

## Not writing

The operator must compare the status it computed against the one already on the resource and skip
the write when they match, ignoring `lastTransitionTime` (FR-022). Without that, the periodic
resync rewrites every status on every pass, which turns SC-003 ("zero writes in steady state") into
a continuous write load against the API server — the same failure the control plane's
identical-observation guard already prevents on its side.
