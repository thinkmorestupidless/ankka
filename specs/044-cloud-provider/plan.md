# Implementation Plan: The Cloud Provider — What Touches a Cloud Account Runs Outside the Operator

**Branch**: `044-cloud-provider-impl` | **Date**: 2026-10-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/044-cloud-provider/spec.md`, clarified 2026-10-09.

## Summary

The operator stops short of any cloud. For a need a cloud must meet it writes a `CloudResource`
(one new CRD in `crd`, six request kinds in the platform's words) owned by the service or project
it serves, and reads back a status a **provider** writes: a process in its own namespace, under its
own ServiceAccount and the ClusterRole ankka ships, holding the owner-equivalent cloud power the
operator never will. A credential goes from the provider into a Secret by `create` once and `patch`
on a generation bump, and nobody reads it back. Six `ANKKA_CLOUD_*` variables declared once in
`PlatformVariables` name the provider, the account, the location, the wrapping key, the
acknowledgement bound and the rotation grace. This feature delivers the contract, the operator's
and the control plane's halves, the bucket path (feature 034's `provisionObjectStorage` on an
installation whose object store is its cloud account's), a scripted provider in the operator's test
sources that fulfils all six kinds with no cloud, and the k3s suite that runs
`features/cloud-provider/` against it under the shipped identities. `ankka-gcp` is its own
repository and is not here.

## Technical Context

**Language/Version**: Scala 3 on the JVM, as the rest of the tree; fabric8 for the resource and
the two clients; no new library anywhere (FR-014).

**Primary Dependencies**: `crd` (fabric8, Jackson Scala) for the resource; the operator for the
rendering, observing and deciding; `controlplane-api`, `controlplane` and `cli` for the
installation route; kustomize for the component and the overlays.

**Storage**: the cluster's API server (the `CloudResource` and the Secrets). No database table,
no journal event, no DDL.

**Testing**: munit offline suites in `crd`, `operator`, `controlplane-api`, `controlplane` and
`cli`; `RenderingGoldenSuite` and `RenderingUnchangedSuite` for the renders; `OperatorClusterSuite`
and a new `CloudProviderClusterFeatures` (`GherkinSuite("../features/cloud-provider")`) on k3s;
`RemoteOverlaySuite` for the overlays; `just features` for the living features.

**Target Platform**: Kubernetes, k3s in tests, kind locally, GKE for `ankka-gcp`.

**Project Type**: platform (operator, CRD, control plane, CLI, kustomization, docs).

**Performance Goals**: SC-001 `Provisioned` within 60s of apply with the fake; SC-004 the absence
reported within bound + 30s and recovered within 30s of a provider starting (hence the informer and
the `enqueueAfter`, research R5); SC-006 a rotation complete within grace + 5 minutes.

**Constraints**: the operator depends on `crd` only and compiles `PlatformVariables` in; no cloud
client library and no cloud-specific code anywhere in the repository but the list of known provider
names; neither process may `get` or `list` a Secret; every Garage render byte for byte unchanged;
every new test switch forwarded in `Test / javaOptions`; no test binds a fixed port.

**Scale/Scope**: two `CloudResource`s per service with a bucket, a handful per project later; one
provider per installation; one CRD, one component, one route, one CLI command, six variables.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template: no principles are declared, so no gate
is derived from it. The repository's standing rules (`CLAUDE.md`, `.claude/rules/kubernetes.md`,
`cluster-and-tls.md`, `control-plane.md`, `testing.md`) are applied as the gates instead:

| Gate | Pre-research | Post-design |
|---|---|---|
| Effects inert, one executor: an `Action` describes, `Fabric8Executor` performs | pass: `EnsureCloudResource` is an `Action`; the decision is pure | pass |
| The operator depends on `crd` only; `PlatformVariables` said once | pass: the resource is in `crd`; the names and the provider list are in `PlatformVariables` | pass |
| No `get` on Secrets, for either process | pass by design | pass: both ClusterRoles, both proven under minted tokens |
| A test switch is forwarded to the forked JVM | n/a | pass: `ankka.cloud.external` added to `Test / javaOptions` |
| A render that changes a Garage object is refused | n/a | pass: the cloud path adds objects and an annotation only on the cloud path (R6) |
| Every acceptance scenario ends as a test that fails without the feature | n/a | pass with two named exceptions, both honest: the four rows of `absent.feature`'s outline and `kinds.feature`'s five consumer scenarios are tested at the helper and the renderer, because their triggers are settings features 038, 039, 041 and 042 have not yet added (R12); `providers.feature`'s real-cloud scenario is `ankka-gcp`'s |
| Could this check pass while the thing it checks is false? | | the k3s suite asserts the Secret's absence from both processes by the API server's 403, the fake's `reached` counter is asserted zero, and the rendered variables are read from the Deployment, not from the plan |

## Project Structure

### Documentation (this feature)

```text
specs/044-cloud-provider/
├── plan.md              # this file
├── research.md          # R1–R16, verify-first list
├── data-model.md        # the resource, the settings, the plans, the fake's records
├── quickstart.md        # how to see it work, pure to k3s to kind
├── contracts/
│   ├── cloud-resource.md    # the CRD and the six kinds, keys and outputs
│   ├── provider.md          # what a provider must do; the grant; the scripted provider
│   ├── operator.md          # settings, names, rendering, observing, deciding, actions, status
│   ├── installation.md      # variables, ConfigMap, the component, overlays, k3s suites
│   └── control-plane.md     # CloudConfig, GET /installation, ankka installation, CloudProviderNeeded
└── tasks.md             # /speckit-tasks output, not created here
```

### Source Code (repository root)

```text
modules/core/src/main/scala/com/thinkmorestupidless/ankka/core/
└── PlatformVariables.scala          # + Cloud* names, CloudProviders, CloudProviderNone; PlatformOnly grows by six
modules/core/src/test/.../PlatformVariablesSuite.scala

crd/src/main/scala/com/thinkmorestupidless/ankka/crd/
├── CloudResource.scala              # NEW: CloudResourceSpec, CloudSubject, CloudResourceStatus, CloudResource, CloudKinds
└── AnkkaService.scala               # + storageCredentialGeneration
crd/src/test/.../CloudResourceCodecSuite.scala   # NEW

kustomization/components/crd/
├── cloudresource.yaml               # NEW: the schema, closed; status subresource; printer columns kind/phase/provider
└── kustomization.yaml               # + cloudresource.yaml
kustomization/components/operator/operator.yaml  # + cloudresources verbs; + six ANKKA_CLOUD_* env defaults
kustomization/components/controlplane/deployment.yaml   # + four ANKKA_CLOUD_* env defaults
kustomization/components/cloud-provider/        # NEW: kustomization.yaml, namespace.yaml, rbac.yaml, settings.yaml
kustomization/overlays/local/{platform-configmap,kustomization}.yaml   # + six keys, replacements, the component
kustomization/overlays/cloud/{platform-configmap,kustomization}.yaml   # + six keys (# SET), replacements, the component
kustomization/deploy-local.sh        # pre-applies cloudresource.yaml beside ankkaservice.yaml

operator/src/main/resources/ankka/crd/cloudresource.yaml   # symlink into kustomization/components/crd/
operator/src/main/scala/com/thinkmorestupidless/ankka/operator/
├── Settings.scala                   # + cloud: Option[CloudSettings]; CloudSettings.read
├── Names.scala                      # + cloudRequest; ReservedProjectIds + "cloud-provider"
├── CloudRequests.scala              # NEW: the six renderers, Keys, the owner reference
├── CloudProvisioning.scala          # NEW: CloudObservation, CloudPlan, decide
├── ObjectStorage.scala              # decide takes the cloud plans; Ready carries CloudBucket
├── Rendering.scala                  # the cloud bucket path: two EnsureCloudResource, env from outputs, the generation annotation, no Deployment while Waiting
├── Action.scala                     # + EnsureCloudResource
├── Executor.scala                   # + observeCloudResource; Fabric8Executor: apply, observe through ifTypeExists
├── ServiceReconciler.scala          # decides the cloud plans, enqueueAfter on an unacknowledged request
├── Operator.scala                   # + the CloudResource informer, enqueuing the controller owner
└── WorkQueue.scala                  # unchanged (enqueueAfter exists)
operator/src/test/scala/com/thinkmorestupidless/ankka/operator/
├── cloud/ScriptedFulfilment.scala   # NEW: pure
├── cloud/ScriptedCloudProvider.scala   # NEW: the informer and the three writes
├── cloud/CloudProviderStack.scala   # NEW: applies the component's RBAC into k3s, mints the provider's token
├── CloudRequestsSuite.scala         # NEW
├── CloudProvisioningSuite.scala     # NEW
├── ScriptedCloudProviderSuite.scala # NEW
├── CrdSchemaSuite.scala             # + cloudresource.yaml
├── SettingsSuite.scala              # + cloud settings
├── ObjectStorageSuite.scala, ObjectStorageRenderingSuite.scala   # + the cloud path
├── RenderingGoldenSuite.scala       # + cloud-bucket case and golden file; objectOf knows EnsureCloudResource
└── OperatorClusterSuite.scala       # + the two identities' cloud verbs

controlplane-api/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api/
├── descriptors.scala                # ProjectId.Reserved + "cloud-provider"; Installation, CloudInstallation
└── CloudProviderNeeded.scala        # NEW
controlplane-api/src/test/.../CloudProviderNeededSuite.scala   # NEW
controlplane/src/main/resources/reference.conf   # + ankka.controlplane.cloud
controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/
├── CloudConfig.scala                # NEW
├── InstallationEndpoint.scala       # NEW: GET /installation
└── ControlPlane.scala               # registers it
controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/
├── InstallationRouteSuite.scala     # NEW
├── CloudProviderClusterFeatures.scala   # NEW: k3s, features/cloud-provider
├── RemoteOverlaySuite.scala         # + the six variables, the component, the grants
└── ReservedProjectIdsSuite.scala    # sees the new id in both lists
cli/src/main/scala/com/thinkmorestupidless/ankka/cli/   # + `ankka installation`, Output.installation
cli/src/test/.../InstallationCommandSuite.scala        # NEW

build.sbt                            # Test / javaOptions forwards ankka.cloud.external
docs/platform/cloud-provider.md      # NEW: the contract, for a provider's author and an installer
docs/platform/object-storage.md, install-cloud.md, docs/reference/configuration.md, limitations.md
docs/reference/control-plane-api.md, cli.md   # regenerated
mkdocs.yml                           # nav entry
```

**Structure Decision**: no new module and no new image. The resource sits in `crd` because both
the operator and the control plane's suites need the type and `crd` depends on nothing; the
renderers, the decision and the fake sit in `operator` beside the Garage path they parallel; the
scripted provider is test-source only (FR-020). The one new kustomization component ships identity
and settings without a workload, as `crd` ships a definition without one.

## Order of work

Each step leaves the tree green, and the earlier steps are the ones later ones are tested against.

1. **The words.** `PlatformVariables` (six names, `CloudProviders`, `PlatformOnly`), its suite,
   `PlatformDeclarationSuite` still passing; `cloud-provider` reserved in both lists.
2. **The resource.** `CloudResource` in `crd`, its codec suite, `cloudresource.yaml`, the symlink,
   `CrdSchemaSuite` both ways, `AnkkaServiceSpec.storageCredentialGeneration` in class and schema.
3. **The operator, offline.** `Settings.cloud`; `Names.cloudRequest`; `CloudRequests` and its
   suite (kinds 1–6); `CloudProvisioning.decide` and its suite; `ObjectStorage.decide`'s cloud
   input; `Rendering`'s cloud bucket path with the golden `cloud-bucket` case and
   `RenderingUnchangedSuite` untouched; `EnsureCloudResource` in `Action`, `Executor` and
   `RenderingGoldenSuite.objectOf`.
4. **The operator, wired.** `Fabric8Executor.observeCloudResource` and the apply;
   `ServiceReconciler` deciding the plans and re-queueing; the `CloudResource` informer in
   `Operator`.
5. **The scripted provider.** `ScriptedFulfilment` and its suite (the credential table, recovery,
   a scripted failure, an unknown kind); `ScriptedCloudProvider` on a client; `CloudProviderStack`.
6. **The grants and the component.** `operator.yaml`'s new verbs and env; the `cloud-provider`
   component; both overlays' keys and replacements; `deploy-local.sh`; `RemoteOverlaySuite`.
7. **The control plane and the CLI.** `CloudConfig`, `Installation`, `GET /installation`,
   `ankka installation`, `CloudProviderNeeded` and the four worded refusals; `just docs-reference`.
8. **k3s.** `OperatorClusterSuite`'s two-identity cases; `CloudProviderClusterFeatures` with
   `ranElsewhere` naming every scenario not run there; `ankka.cloud.external` forwarded in
   `build.sbt`; a `cluster` workflow run on the branch before merge.
9. **Documentation.** `docs/platform/cloud-provider.md`, the four amended pages, `mkdocs.yml`,
   `just docs`, `just features`; `.claude/rules/kubernetes.md` gains the cloud request section and
   any trap the k3s runs taught.
10. **Verify first.** Each item of research's "Verify first, gathered" moved to "Verified during
    implementation" with what a real API server said.

## Complexity Tracking

> Fill ONLY if Constitution Check has violations that must be justified

No gate is violated. Two things are larger than the smallest possible and are noted, not
justified as violations:

| Choice | Why | Simpler alternative rejected because |
|---|---|---|
| A third informer in `Operator` (on `CloudResource`) | SC-004's 30-second recovery; the resync is five minutes | polling the status on the service queue would wake every service every few seconds, or miss the bound |
| The Deployment withheld while a cloud bucket waits (R6) | the endpoint and region are the fulfilment's; rendering placeholders would start a service with wrong variables | rendering the Garage way (apply, let the pod wait on its Secret) has nothing truthful to put in `ANKKA_S3_ENDPOINT` |
