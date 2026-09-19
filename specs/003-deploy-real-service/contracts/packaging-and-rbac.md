# Contract: Packaging, Image Delivery and RBAC

**Satisfies**: FR-014 to FR-021, and the RBAC gap research R3 found

## The operator's new permission

Added to the operator's `ClusterRole` in `kustomization/components/operator/operator.yaml`:

| API group | Resource | Verbs | Note |
|---|---|---|---|
| `""` | `services` | get, list, watch, create, patch, delete | `delete` is used only by the guarded `RemoveService` |

`patch` is not optional: every ensure in this operator writes by server-side apply, which is a PATCH
even for an object that does not exist yet. `create` alone 403s on the very first apply — the trap
already recorded in `CLAUDE.md`, and the reason `namespaces` carries `patch` too.

**`delete` is granted, deliberately.** Feature 002 withholds `delete` on databases and credentials
because they hold data. A Service holds none; the operator already has `delete` on `deployments`.
It is exercised by exactly one action, which refuses to touch a Service the resource does not own
([service-object.md](./service-object.md)).

### This grant will not be caught by the new suite

The k3s suites hand fabric8 the testcontainer's **admin kubeconfig**; the operator under test never
runs as its own ServiceAccount. A missing verb therefore fails on a real deploy and nowhere in CI —
which is exactly how feature 002's missing CNPG and `secrets` rules escaped.

Mitigation, at almost no cost: feature 002 already built a client bound to the operator's real
ServiceAccount (`OperatorClusterSuite`, the withheld-`delete` test). Extend that same client with the
**positive** assertion — the operator's ServiceAccount *can* create a Service — so the grant is
proven rather than assumed. One token, already minted, checked in both directions.

## Packaging the sample

| Setting | Value | Why |
|---|---|---|
| plugins | `JavaAppPackaging, DockerPlugin` | the same two the operator and control plane enable |
| settings | the shared `dockerSettings` | one base image, `dockerUpdateLatest`, `DOCKER_REPOSITORY` — no new conventions |
| `Compile / mainClass` | `Some("runShoppingCart")` | `@main def runShoppingCart()` compiles to a top-level class of exactly that name (verified). Explicit, for the reason already on the operator's setting: discovery is one new `@main` away from an ambiguous-main failure |

Produces `sample-shopping-cart:0.1.0-SNAPSHOT` and `sample-shopping-cart:latest`.

`docker:publishLocal` at the root already aggregates to every project with `DockerPlugin` enabled, so
building every image stays one command with nothing to add (FR-015). `kustomization/deploy-local.sh`
names its images explicitly and gains a third `kind load docker-image` line (FR-016).

## Getting the image into the k3s test container

`kind load docker-image` has no equivalent for a testcontainers k3s node. The mechanism, **verified
end to end** (research R1):

```
1.  docker save <image> -o <tar>                     on the host
2.  copyFileToContainer(<tar>, /tmp/<tar>)           testcontainers
3.  ctr -a /run/k3s/containerd/containerd.sock \      inside the container
        -n k8s.io images import /tmp/<tar>
```

Each part of step 3 is load-bearing, and getting any of them wrong fails quietly:

| Detail | What happens if wrong |
|---|---|
| `ctr`, **not** `k3s ctr` | `No help topic for 'ctr'` |
| `-a /run/k3s/containerd/containerd.sock` | `ctr` talks to a socket that is not there |
| `-n k8s.io` | imports fine, lists fine under `ctr images ls`, and **kubelet still cannot see it** |

No `--platform` flag. It is needed only for multi-architecture *remote* images; a locally built one
is single-architecture already, and hardcoding a platform would break the other architecture
(verified both ways).

## The `imagePullPolicy` requirement

Kubernetes defaults `imagePullPolicy` to **`Always` for a `:latest` tag**. An image imported into the
node's containerd is then ignored and the pod fails with `ErrImagePull` — verified, on the same
cluster, with the same image, differing only in that field.

The operator renders `IfNotPresent` on every workload container. Without it this feature cannot work
at all, and the reason it has never mattered before is that every test to date used
`registry.k8s.io/pause:3.9`: a pullable image on a non-`latest` tag, which sidesteps both halves.

## Build ordering

`controlPlane`'s `Test / test` depends on `shoppingCart / Docker / publishLocal`, **only when cluster
tests are enabled**:

| Invocation | Image built? | Suite runs? |
|---|---|---|
| `sbt test` | yes | yes |
| `sbt -Dankka.cluster.tests=off test` | **no** | no |
| `sbt 'controlPlane/testOnly …SampleDeploymentClusterSuite'` | no — `testOnly` bypasses `Test / test` | yes, and fails with an actionable message if the image is absent |

A switch that skips the suite but still spends a minute building an image it will not use is not
skipping it, which is why the branch exists. sbt reads `-Dankka.cluster.tests` from its own JVM, so
the build can branch on it at task-graph time.

This is a **build-level task dependency, not a classpath one**: `controlPlane` gains no dependency on
`shoppingCart` in any compilation scope and the documented module direction is unchanged. It still
earns a line in `CLAUDE.md`, because "the control plane's tests build a sample image" is surprising
until written down.

## Two things to check before trusting the packaging

- **`publish / skip := true` is set on `shoppingCart`.** `Docker / publishLocal / skip` delegates to
  it, and some sbt-native-packager versions honour it — enabling the plugin would then appear to work
  and build nothing. **Unverified during planning**; it is the first task of US2: run
  `sbt shoppingCart/Docker/publishLocal`, confirm with `docker images`, and set
  `Docker / publish / skip := false` if nothing appeared (research R13).
- **The suite reaches the service from the k3s node by `clusterIP`, not by port-forward and not by
  DNS name.** Port-forward bypasses the Service, so it cannot catch a wrong selector; the node does
  not resolve cluster DNS (both verified, research R11).
