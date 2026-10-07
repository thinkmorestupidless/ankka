# Quickstart: validating Service Rollback

The runs that prove the feature, cheapest first. Each names what it would look like if the feature
were missing, because a run that cannot fail proves nothing. Contracts are in
[contracts/](contracts/), the stored forms in [data-model.md](data-model.md).

Docker is needed from step 3. The k3s step takes minutes and should run under `caffeinate -i`.

## 1. The digest and the wire types (seconds)

```bash
sbt 'controlPlaneApi/testOnly *DescriptorSuite *ControlPlaneFixturesSuite'
```

Expect: equal descriptors share a digest, reordered labels share one, a changed variable does not.
The fixtures suite fails until the console's fixture files for `HistoryEntry`, `RollbackRequest`
and `RolledBack` exist; write them with `-Dankka.docs.update=true`.

Without the feature: no `digest`, and the fixtures suite has no such types.

## 2. The fold, the entity and the stored forms (seconds, no runtime)

```bash
sbt 'controlPlane/testOnly *ServiceEntitySuite *EventCompatibilitySuite'
```

Expect, in `ServiceEntitySuite`: a rollback with no generation named lands on the last different
descriptor and passes over a restart and an identical apply; rolling back twice returns; each of
the five refusals with its own words; paused and exposed untouched; `desiredState` carries no kept
descriptor and stays small with fifty kept.

Expect, in `EventCompatibilitySuite`: the pinned pre-feature snapshot decodes, shows entries with
no image, and after one apply can be rolled back to the descriptor it held.

Break it once to see it fail: remove the seeding accessor and the last case goes red with
`has no earlier generation with a different descriptor`.

## 3. The HTTP API, the organization and the CLI (about a minute)

```bash
sbt -Dankka.cluster.tests=off 'controlPlane/testOnly *ControlPlaneHttpSuite *QuotaSuite *SuspensionSuite *DeployTokensSuite *CliEndToEndSuite *ControlPlaneRoutesReferenceSuite'
sbt 'cli/testOnly *OutputSuite *CliReferenceSuite'
```

Expect: `POST …/rollback` and `GET …/descriptor?generation=` answer as
[contracts/control-plane.md](contracts/control-plane.md) says, refusal by refusal; a rollback over
quota is refused and the organization's usage is what it was before; two rollbacks to one
generation sent together move the generation once; the CLI prints `rolled back to generation 1`,
and `services history cart --generation 1` prints a descriptor `services apply -f` accepts.

The two reference suites fail until `just docs-reference` has rewritten the pages and the
hand-written sections exist.

## 4. The console (a few minutes)

```bash
just test-console
just test-console-compose      # against compose's Keycloak and `sbt controlPlane/run`
```

Expect: the history shows Image and Digest; a rollback from a row lands on the service page with
the rollback first in its history; no control on a restart's row or on a row whose descriptor the
service already has; a refused rollback shows the control plane's words.

The compose run matters: the fake control plane is a second implementation of these routes, and
only the compose run compares it with the real one.

## 5. A real cluster (minutes)

```bash
caffeinate -i sbt 'controlPlane/testOnly *EndToEndClusterSuite'
```

Expect, after the case that changes the image: a rollback, and the pod's image read from the
cluster is the first one again; a second rollback returns the second image, which leaves the
service as the cases after it expect.

Without the feature there is no route to call.

## 6. Documentation and the whole build

```bash
just docs-reference && just docs-sync && just docs
just features
sbt buildAll
```

`just features` reports nothing for this spec; it still lists the inline scenarios of the specs
not yet built.

## 7. By hand, on the local kind cluster

```bash
just up
ankka services apply -f service.json                 # generation 1
ankka services deploy cart sample-shopping-cart:bad  # generation 2, never ready
ankka services history cart                          # two lines, two images, two digests
ankka services rollback cart                         # "rolled back to generation 1"
ankka services get cart                              # generation 3, the first image, Ready
ankka services history cart --generation 2           # the bad descriptor, as JSON
ankka services rollback cart --to-generation 1       # refused: already has that descriptor
```

Then open the service in the console and roll back from its history.
