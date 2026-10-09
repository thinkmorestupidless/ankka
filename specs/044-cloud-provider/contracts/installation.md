# Contract: the installation

What an installation declares, what the components ship, and what the k3s suites install.

## The platform's variables

Declared in `core`'s `PlatformVariables` (compiled into the operator) and nowhere else:

| Name | Values | Read by |
|---|---|---|
| `ANKKA_CLOUD_PROVIDER` | `none` (as shipped) or a name in `PlatformVariables.CloudProviders` (`gcp`) | operator, control plane, provider |
| `ANKKA_CLOUD_ACCOUNT` | the account the installation's cloud resources are made in | operator, control plane, provider |
| `ANKKA_CLOUD_LOCATION` | the default location, in the installation's words | operator, control plane, provider |
| `ANKKA_CLOUD_KMS_KEY` | the one wrapping key, or empty | operator, control plane, provider |
| `ANKKA_CLOUD_ACKNOWLEDGEMENT_BOUND` | a duration; `2m` | operator |
| `ANKKA_CLOUD_ROTATION_GRACE` | a duration; `1h` | provider (the operator validates it) |

All six are `PlatformOnly`: a descriptor may not set them. `PlatformVariablesSuite` pins the set.

## The platform ConfigMap

`ankka-platform` (namespace `ankka-gateway`) carries `cloudProvider`, `cloudAccount`,
`cloudLocation`, `cloudKmsKey`, `cloudAcknowledgementBound`, `cloudRotationGrace`. The local
overlay sets `cloudProvider: none` and the rest empty or default; the cloud overlay carries
`# SET` placeholders with `none` as the provider.

`replacements` in both overlays copy each key onto the operator's and the control plane's
containers (the literal defaults in the components are overwritten, as `ANKKA_OTLP_ENDPOINT` is),
and onto ConfigMap `ankka-cloud` in `ankka-cloud-provider`.

## The `cloud-provider` component

`kustomization/components/cloud-provider/`:

| File | Holds |
|---|---|
| `kustomization.yaml` | a `Component` listing the files |
| `namespace.yaml` | Namespace `ankka-cloud-provider` |
| `rbac.yaml` | ServiceAccount, ClusterRole and ClusterRoleBinding `ankka-cloud-provider` ([provider.md](provider.md)) |
| `settings.yaml` | ConfigMap `ankka-cloud` with the six keys, replaced by the overlay |

Listed in both overlays after `operator`. It ships no Deployment: a provider's install adds one.
An installation whose provider is `none` still carries the component; nothing watches, nothing is
written, and the identity sits unused.

The `crd` component gains `cloudresource.yaml`; `deploy-local.sh` pre-applies it beside
`ankkaservice.yaml`.

## Overlays

`RemoteOverlaySuite` asserts, for both overlays: each `ANKKA_CLOUD_*` appears exactly once on the
operator's single container and once on the control plane's; `ankka-cloud` carries the six keys;
the provider's ClusterRole has no `delete` and no `get` on Secrets; the cloud overlay's provider
placeholder is `none`.

## The k3s suites

| Suite | Installs | Proves |
|---|---|---|
| `OperatorClusterSuite` | `cloudresource.yaml`, the operator's RBAC, the `cloud-provider` RBAC | the operator's verbs on `cloudresources` and no `delete`; the provider's `editStatus` and no Secret `get`, both under minted tokens |
| `CloudProviderClusterFeatures` (controlplane tests) | the three CRDs, PKI, CNPG, the Gateway API CRDs, the `cloud-provider` RBAC; the operator in-process with `cloud` settings and no Garage; the scripted provider in-process under the provider's token | `features/cloud-provider/` |

`CloudProviderClusterFeatures` sets `ANKKA_CLOUD_ACKNOWLEDGEMENT_BOUND=30s` and
`ANKKA_CLOUD_ROTATION_GRACE=20s` through the operator's and the scripted provider's settings, so
the bound and the grace are seen to pass. Every service is `pause` with `"http": false`, and the
variables are read from the Deployment the operator rendered. The suite is listed by
`.github/cluster-suites.py` unprompted, since it reads `ankka.cluster.tests`.

## `ankka-gcp`

Out of this repository. Its install is the `cloud-provider` component plus its own Deployment in
`ankka-cloud-provider` and, on GKE, the Workload Identity annotation on the ServiceAccount; it pins
the `crd` artefact's version and reports its own in every status. Its README states that its cloud
roles are owner-equivalent on the account, and why that is not the operator.
