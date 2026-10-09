# Quickstart: The Cloud Provider

How to see the feature work, from milliseconds to a cluster. Each step names the suites that
prove it; the shapes are in [contracts/](contracts/) and [data-model.md](data-model.md).

## Prerequisites

Docker (OrbStack on the dev Mac), `kubectl` on `PATH` for the overlay suite, `uv` for the
features check. No cloud account, no API key: nothing here reaches a cloud.

## 1. Pure: the resource, the kinds, the decision, the fake

```bash
sbt crd/test                                                    # CloudResourceCodecSuite: round trips, defaults, null status
sbt 'operator/testOnly *CrdSchemaSuite'                         # cloudresource.yaml and the case classes, both ways
sbt 'operator/testOnly *CloudRequestsSuite'                     # six renderers, names, keys, nothing cloud-shaped
sbt 'operator/testOnly *CloudProvisioningSuite'                 # the decision table: unacknowledged, behind, Waiting, Ready, Recovered, Failed
sbt 'operator/testOnly *ObjectStorageSuite *ObjectStorageRenderingSuite *RenderingGoldenSuite *RenderingUnchangedSuite'
                                                                # the cloud bucket path; Garage byte for byte as before
sbt 'operator/testOnly *ScriptedCloudProviderSuite'             # the fulfilment: offer once, 409 ends the new one, a bump patches, the grace ends the old one
sbt 'operator/testOnly *SettingsSuite'                          # ANKKA_CLOUD_*: none, all, half-set, unknown name
sbt 'core/testOnly *PlatformVariablesSuite' 'controlPlane/testOnly *PlatformDeclarationSuite *ReservedProjectIdsSuite'
```

Expected: green, and `RenderingUnchangedSuite` with no repin. A change that alters any Garage
render is wrong.

## 2. The control plane and the CLI, offline

```bash
sbt 'controlPlane/testOnly *InstallationRouteSuite'             # GET /installation: none, gcp, the key only for an owner
sbt 'controlPlaneApi/testOnly *CloudProviderNeededSuite'        # the four refusals of absent.feature, worded
sbt 'cli/testOnly *InstallationCommandSuite'                    # ankka installation, table and --json
sbt 'controlPlane/testOnly *RemoteOverlaySuite'                 # both overlays: six variables once per container, the component, the grants
```

## 3. The living features

```bash
just features
```

Expected: every scenario of `features/cloud-provider/` still named by the spec, every word in
the glossary, no finding.

## 4. On k3s

```bash
caffeinate -i sbt 'operator/testOnly *OperatorClusterSuite'
```

Expected: the operator's token applies a `CloudResource`, is refused a `delete` and a status
`patch`; the provider's token writes a status and a Secret, and is refused a Secret `get`.

```bash
caffeinate -i sbt 'set controlPlane / Test / logBuffered := false' 'controlPlane/testOnly *CloudProviderClusterFeatures'
```

Expected, scenario by scenario as they end: `bucket.feature` (four), `credential.feature` (four
of six; two are the operator suite's), `absent.feature` (two of four), `providers.feature` (one of
three), the rest reported as run elsewhere. The suite deploys `pause` images only and finishes in
well under fifteen minutes. A `reached` count other than zero fails the run.

Or on demand in CI: `gh workflow run cluster --ref 044-cloud-provider-impl -f suite=CloudProviderClusterFeatures`.

## 5. Against a real provider (by hand, once one exists)

```bash
ANKKA_CLOUD_PROVIDER=gcp ANKKA_CLOUD_ACCOUNT=… ANKKA_CLOUD_LOCATION=… \
  sbt -Dankka.cloud.external=$HOME/.kube/gke.yaml 'controlPlane/testOnly *CloudProviderClusterFeatures'
```

The same scenarios, no k3s, no scripted provider; the cluster must run `ankka-gcp` under the
`cloud-provider` component. This repository does not run it green.

## 6. Documentation

```bash
just docs-reference        # GET /installation, ankka installation
just docs                  # every page builds; docs/platform/cloud-provider.md is linked from the nav
```

## 7. By hand, on kind

```bash
just cluster-create && just deploy
kubectl get crd cloudresources.ankka.thinkmorestupidless.com
kubectl -n ankka-cloud-provider get sa,cm                      # the identity and ankka-cloud, provider none
ankka installation                                            # provider   none
```

With the local overlay's `cloudProvider` set to `gcp` and an account and location, redeploy and
apply a descriptor with `provisionObjectStorage: true`:

```bash
kubectl -n ankka-shop get cloudresources                       # reports-bucket, reports-storage-credential, no status
ankka services get reports -p shop                             # object storage: Waiting …, then after 2m: no provider for gcp has answered
```

Nothing answers on kind: that is the absent feature, seen by hand.
