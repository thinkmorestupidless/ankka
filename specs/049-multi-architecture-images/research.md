# Research: Images for Both Architectures

Decisions, each with what was chosen, why, and what else was weighed. The spec settles the shape
(one tag, both platforms; the local build untouched; a refused release rather than a one-platform
one; the proof on arm64; the operator names the architecture an image lacks); these settle how that
lands in the build, the workflows and the code that exist. File references are to the tree as it is
before this feature.

## R1. `dockerBuildxPlatforms` from one environment variable, and `publish` is already two builds

**Decision**: `dockerSettings` in `build.sbt` gains

```scala
dockerBuildxPlatforms := sys.env.get("ANKKA_IMAGE_PLATFORMS").toSeq.flatMap(_.split(',').map(_.trim)).filter(_.nonEmpty)
```

and a `val` beside it that calls `sys.error` when `ANKKA_IMAGE_PLATFORMS` is set and
`DOCKER_REPOSITORY` is not, naming both. The release job sets
`ANKKA_IMAGE_PLATFORMS=linux/amd64,linux/arm64` beside `DOCKER_REPOSITORY`. Nothing else sets it.

**Rationale**: read from sbt-native-packager 1.11.7's `DockerPlugin.scala` (fetched at the tag, not
remembered): `publishTask` is `val _ = publishLocal.value` first, which is the ordinary
`docker build` of the host's architecture with every alias, and then, only when
`dockerBuildxPlatforms` is non-empty, `docker buildx build --platform=<list> --push <options> .`
where the options already carry every `-t` alias (`dockerBuildOptions` lists them), so the
version tag and `latest` both become the index. `publishLocal` never reads the setting. So the
spec's FR-005, "`docker:publishLocal` and every build the suites make MUST build the host's
architecture only", holds by the plugin's design, and the refusal it asks for reduces to the one
case that can go wrong: a multi-platform `publish` with no registry would push to nowhere, and
`DOCKER_REPOSITORY` unset is exactly the local developer's state. The variable is named for the
platform rather than for Docker because the template's build (R6) reads the same one.

**Cost noted**: `publish` builds the host image once with `docker build` and then both platforms
again with buildx under a `docker-container` builder, which shares no layer cache with the daemon.
For a JVM image that is a `COPY` of jars and a `chmod`, measured in the job (see Measurements). It
is accepted rather than fought: the alternative, overriding `publish` to skip `publishLocal`, is a
second copy of a plugin task to keep in step.

**Alternatives**: a `-D` system property, rejected because `sbt -D` does not reach everything the
way an environment variable reaches `sys.env` in the build definition and the template's build
alike; setting the platforms unconditionally, rejected because every developer's `Docker/publish`
would then need buildx and QEMU, and the template's developer would inherit that on day one.

## R2. The console by buildx, with the build stage on the build platform

**Decision**: the release job builds the console with

```bash
docker buildx build --platform linux/amd64,linux/arm64 --push \
  -t "$DOCKER_REPOSITORY/ankka-console:$version" -t "$DOCKER_REPOSITORY/ankka-console:latest" console
```

and `console/Dockerfile`'s first stage becomes `FROM --platform=$BUILDPLATFORM node:24-bookworm-slim
AS build`, so `npm ci` and both `npm run build`s run natively once, and only the final stage, which
copies files and sets `USER`, is produced per target platform.

**Rationale**: `node:24-bookworm-slim` publishes `linux/arm64` (checked on the registry,
2026-10-11). Without `$BUILDPLATFORM` the whole first stage would run twice, the arm64 one under
QEMU, and `npm ci` plus a React Router build under emulation is minutes of a job that otherwise
copies files. The one thing this must not do is leave an amd64-only native module in
`node_modules`: `npm prune --omit=dev` keeps what the host needs, and a platform-specific optional
dependency (esbuild's binaries are the usual one) installed on the build platform would be the
wrong binary on arm64. Verify-first (V2): read `console/package/package.json` and the host's for
any dependency with per-platform binaries; if one is in `dependencies` rather than `devDependencies`,
the final stage runs `npm ci --omit=dev` itself on the target platform instead of copying
`node_modules`, and the cost is paid where it is real.

**Alternatives**: `docker build --platform` twice and `docker manifest create`, rejected as three
commands and a manifest the job assembles by hand where buildx does it in one.

## R3. The release refuses a one-platform image, and warms the cache per platform

**Decision**: the step "Every image is publicly pullable" gains, after the 200 check, a read of the
manifest list:

```bash
platforms="$(docker buildx imagetools inspect "ghcr.io/thinkmorestupidless/$image:$version" --format '{{range .Manifest.Manifests}}{{.Platform.OS}}/{{.Platform.Architecture}} {{end}}')"
for want in linux/amd64 linux/arm64; do
  case " $platforms " in *" $want "*) ;; *) echo "::error::ghcr.io/thinkmorestupidless/$image:$version has no $want build (has: $platforms)"; exit 1 ;; esac
done
```

The job is named in the `needs` of every job that publishes something downstream of the images
(`template`, `marketplace`, `action`, `homebrew` as today), so a refused image stops them. The warm
step pulls each image twice, `docker pull --platform linux/amd64` and `--platform linux/arm64`,
through the cache, and keeps the digest comparison it has, which for an index compares the index
digest on both sides.

**Rationale**: FR-003 asks that no release complete with an image for one platform under its tag.
The image is already pushed by then; what the check buys is that the job fails loudly, the tag's
run is red, and nothing downstream is published naming it. That is the same contract the
public-package check already has. An attestation manifest (`unknown/unknown`) may appear in the list
when provenance is on; the check looks for the two it wants and ignores the rest. The cache stores
what it has served, so a pull of the amd64 image through it warms amd64 only; the arm64 node's first
pull during a ghcr.io outage would otherwise find nothing.

**Alternatives**: checking before push by inspecting the local buildx output, rejected because the
registry is the thing a node reads and the check should read the same thing.

## R4. The cluster workflow's matrix gains an architecture axis

**Decision**: `.github/cluster-suites.py` emits each suite twice, with `arch` (`amd64`, `arm64`)
and `runner` (`ubuntu-24.04`, `ubuntu-24.04-arm`) fields; `cluster.yml`'s `suite` job takes
`runs-on: ${{ matrix.runner }}`, is named `${{ matrix.suite }} (${{ matrix.arch }})`, and names
its artifacts with the architecture. The `suite` input still narrows by suite name and a second
input, `arch`, narrows to one leg. Each leg builds its own images with `testOnly` as today, so
nothing is emulated anywhere in the cluster run. The sidecar leg's Rust toolchain and `uv` install
on arm64 unchanged.

**Rationale**: FR-006. GitHub hosts `ubuntu-24.04-arm` for public repositories at no cost, and the
release workflow already runs on `ubuntu-22.04-arm` for the native CLI. The matrix is derived
from the source so a new suite runs on both legs unlisted, which is the property the script exists
for. The sampler and the test-report upload are per job and need only the name change.

**Verify-first (V3)**: one suite on demand on the arm64 leg before anything else is built on it:
`gh workflow run cluster --ref 049-multi-architecture-images -f suite=SampleDeploymentClusterSuite
-f arch=arm64`. What can be wrong only there: Docker on the arm64 runner, the `rancher/k3s` image
(publishes arm64, checked), testcontainers' Ryuk image, and the sbt launcher. The nightly keeps both
legs (FR-007); a red arm64 leg is one job named by architecture.

**Alternatives**: a separate `cluster-arm64.yml`, rejected as a second workflow to keep in step; a
single arm64 job running every suite serially, rejected because the point of one runner per suite
is the two hours it saves.

## R5. The operator names the architecture an image lacks

**Decision**: `PodProblem` gains `architecture: Option[String]`. When a container's waiting reason
is `CrashLoopBackOff` (or `RunContainerError`, added to `Actionable`) and its last termination
message contains `exec format error`, the reconciler reads the pod's node once (`get` on `nodes`,
the `kubernetes.io/arch` label) and the problem carries it. `LifecycleRules.problemDetail` renders
such a problem as

```
image cart:2 has no build for this node's architecture (arm64)
```

or, when the node could not be read, the same sentence without the parenthesis. The ClusterRole in
`kustomization/components/operator/operator.yaml` gains `get` on `nodes`, with a comment naming
this feature; `OperatorClusterSuite` proves the grant under the real identity as it proves the
others, and `ReservedProjectIdsSuite`-style pinning is not needed since no name is involved.

**Rationale**: FR-008. Under containerd (k3s, kind, GKE) an image for the wrong architecture fails
at container start: the kubelet reports the container `waiting` with reason `RunContainerError`
and then `CrashLoopBackOff`, and `lastState.terminated` with reason `StartError` and a message
ending `exec format error`. `PodProblem.waiting` already prefers the last termination message for a
`CrashLoopBackOff`, so the words reach `problemDetail` today; what is missing is the translation and
the architecture. The node's architecture is the one fact nothing in the pod carries, and
`kubernetes.io/arch` is the standard label every kubelet sets. A read-only, cluster-scoped `get`
is the narrowest grant that answers it.

**Verify-first (V1)**: the exact `reason` and `message` k3s produces, captured by the k3s case
before the translation is written to a string; the case builds its image from a two-line
Dockerfile, `FROM --platform=linux/<other> eclipse-temurin:21-jre` and the sample's entrypoint
copied in, tagged `sample-shopping-cart:<BuildInfo.imageTag>-other-arch`, loaded into the k3s
container as the sample is, and applied by descriptor. No emulation is needed to build it: the
Dockerfile has no `RUN`.

**Alternatives**: the operator's own `os.arch`, rejected because a mixed cluster would name the
wrong one; a `list` on nodes, rejected as more than needed.

## R6. The template and the action carry the same setting

**Decision**: `ankka.g8/src/main/g8/build.sbt` gains the same `dockerBuildxPlatforms` line as
R1 with a comment ("which platforms the image is built for; unset, this machine's, which is what
`kind load` wants; the deploy workflow sets both"), and the template's `deploy.yml` gains
`docker/setup-qemu-action@v3`, `docker/setup-buildx-action@v3` and `ANKKA_IMAGE_PLATFORMS:
linux/amd64,linux/arm64` in its env, with the `\${{` escaping the Giter8 trap demands.
`TemplateSuite` asserts the expansion's build names the variable and its workflow the two actions.
`docs/deploy/ci.md`'s "Use the action" section notes that the job builds for both.

**Rationale**: FR-009. A developer's image is theirs, and the template is where the platform says
how. The variable is the platform's name so the docs can say one thing for the platform's own
images and the developer's.

**Alternatives**: the action building the image, rejected because the action installs the CLI and
nothing else, deliberately.

## R7. Documentation

**Decision**: three pages, no new page, no nav change.

- `docs/deploy/images.md`: a section "Build for the cluster's architecture" after "A web-hosted
  service" and before "Get the image onto the cluster": the platform's own images are published for
  `linux/amd64` and `linux/arm64`; a Scala service sets `ANKKA_IMAGE_PLATFORMS` (the template's
  setting) and needs buildx and QEMU for the one it is not on; a Python, TypeScript or web image is
  `docker buildx build --platform linux/amd64,linux/arm64 --push`; a Rust module has no
  architecture; and what a wrong one looks like: the report's sentence from R5.
- `docs/platform/install-cloud.md`: the prerequisites gain "Nodes of either architecture, amd64 or
  arm64, or both", and a new section "A cluster of your own machines" before "Check that the
  network enforces policy": what a cloud provides that such a cluster must: an address for the
  gateway's `LoadBalancer` Service (k3s's ServiceLB or MetalLB), a default storage class for the
  databases (k3s's `local-path` pins a volume to one node), and a DNS-01 solver, which needs no
  inbound connection; nodes may be amd64 or arm64.
- `docs/deploy/ci.md`: one sentence in "Use the action".
- `docs/reference/limitations.md`: nothing there says one architecture; no change.

Each page stands alone and names no feature. `just docs` is the check; the skill's `pages:` list
is unchanged since no page is added.

## R8. Where each scenario is tested

| Scenario (`features/images/architectures.feature`) | Where |
|---|---|
| a release publishes every image for both architectures under one tag | the release job's platform check (R3), on the next tag; `WorkflowShapeSuite` pins that the check and both platforms are in `release.yml` |
| a cluster of arm64 nodes starts every image of the platform | SC-002 on tupile, recorded in Measurements; the cluster workflow's arm64 leg for the operator, control plane and sample |
| a release is refused when an image is built for one architecture | the same check; `WorkflowShapeSuite` asserts the `exit 1` branch names the platform |
| the cluster suites run on an arm64 runner as on amd64 | `WorkflowShapeSuite` runs `cluster-suites.py` and asserts each suite appears once per architecture with its runner; the workflow on demand (V3) |
| a service whose image has no build for the cluster's architecture is reported with that reason | `LifecycleRulesSuite` (the sentence, with and without an architecture), `PodProblemSuite` (the extraction from a `CrashLoopBackOff` with those last words), and the k3s case in `SampleDeploymentClusterSuite` (the real words, the real report, the CLI's `services get`) |
| a template's build says which architectures the service's image is built for | `TemplateSuite` |
| building the images on a developer's machine builds for that machine alone | `WorkflowShapeSuite`'s sibling case reads `build.sbt` and asserts the setting is read from the variable and nothing else sets it; `sbt docker:publishLocal` on a laptop (quickstart) |
| the documentation says which architectures the platform runs on and what a cluster of one's own machines needs | `just docs`; a `DocsSuite`-style grep is not worth a test, the page is read |

## R9. What is deliberately not here

- No change to any overlay, `deploy-local.sh`, `kind.yaml` or `RemoteOverlaySuite`: an overlay
  names a tag and the registry serves the architecture.
- No `--platform` on any manifest, no node selector, no affinity: a mixed cluster schedules freely.
- No arm64 leg for the `ci` workflow's unit jobs: they build no image a node runs, and the
  platform's JVM code is architecture-free; the cluster run is where arm64 is proved.
- No change to the installations' cache module in the infrastructure repository: a remote
  repository caches whatever is pulled through it, per platform.

## Verify first, gathered

- **V1**: the kubelet's `reason` and `message` for an image of the wrong architecture under k3s's
  containerd (R5). Done: see "Found during implementation"; the pull-time refusal is the common
  case and the detection matches both.
- **V2**: whether any dependency in the console's final `node_modules` carries a per-platform binary
  (R2). Read both `package.json`s; if so the final stage installs on the target platform.
- **V3**: the arm64 runner runs a k3s suite at all (R4). Done: see Measurements.
- **V4**: `docker buildx imagetools inspect --format` with `.Manifest.Manifests` prints the
  platforms for an index as written in R3; checked against a known multi-platform image on the
  laptop before the step is written.
- **V5**: the release job's duration with the second build (R1); measured on the first tag and
  recorded below, with the decision whether to drop `publishLocal` from `publish` revisited only if
  it is worse than ten minutes.

Already checked on the registries, 2026-10-11, with `docker buildx imagetools inspect`: every base
and add-on image the components pin publishes `linux/arm64`: `eclipse-temurin:21-jre`,
`node:24-bookworm-slim`, `cloudnative-pg:1.30.0` and `postgresql:17`, cert-manager v1.21.2 (three
images), Envoy Gateway v1.9.1 and its ratelimit, trust-manager v0.25.0, Keycloak and its operator
26.7.4, Strimzi 1.2.0 and its Kafka, Garage v2.3.0, both OpenTelemetry collectors 0.161.0,
`otel-lgtm:0.35.0`, `postgres:17-alpine`, `curlimages/curl:8.11.0`, `rclone:1.68.2`, and
`rancher/k3s`. Envoy Gateway's default proxy image is set by the gateway, not the install manifest;
the proxy image it names at v1.9.1 is checked by the k3s suite's arm64 leg running at all.

## Found during implementation

- **V1, on GitHub's runners (both legs), the other way round.** There k3s's containerd ran the
  foreign image and the kubelet reported the container *terminated*, reason `Error`, message
  `exec /__cacert_entrypoint.sh: exec format error` (the base image's entrypoint script is the
  first binary), on amd64 and arm64 alike; the operator read the node and reported `image
  sample-shopping-cart:…-other-arch has no build for this node's architecture (amd64)` and
  `(arm64)` respectively (cluster run 38150083552, 2026-10-11). So both refusals are real and
  depend on the containerd and the way the image arrived; the detection matches both, and
  `PodProblem.image` is carried on the terminated paths as well as the waiting one.
- **V1, answered differently from the guess, on the laptop.** An image published for one platform is refused by
  containerd *at pull*, not at start: the kubelet reports `ImagePullBackOff` with `no match for
  platform in manifest: not found`, because the image's manifest list has nothing for the node. That
  is the common case — a developer's amd64-only image on tupile — and R5's `exec format error` is
  the rarer one, an image whose manifest claims the node's platform and holds another's binary.
  `PodProblem.wrongArchitecture` matches either phrase and the detail sentence is the same. Found on
  a k3s node (`rancher/k3s:v1.35.1-k3s1`) on 2026-10-11 with the image the suite builds.
- **`ctr images import` drops a foreign-platform tar silently.** Without `--all-platforms` it
  imports only the node's platform's manifests, exits 0, and the pod reports a plain `ErrImagePull`
  ("failed to resolve reference", then a pull from Docker Hub that does not exist). `ClusterImages.
  importInto` now passes the flag; for every ordinary, single-platform, host-built image it changes
  nothing.
- **The local k3s run was evicting every pod for disk pressure** (the laptop's Docker held 152 GB of
  images and 125 GB of build cache with 8 GB free), which is why case 10b is proved on the cluster
  workflow rather than on the laptop. Not the feature's; noted so the next local run checks
  `docker system df` first.

## Measurements

- **A multi-platform `operator/Docker/publish` on the laptop** (Apple silicon, OrbStack, a
  `docker-container` builder with `network=host`, a throwaway `registry:2`): 30 s wall-clock for the
  host build, the buildx build of both platforms and the push; the index at the version tag and at
  `latest` lists `linux/amd64 linux/arm64` plus two attestation entries (2026-10-11, T011). Two
  traps found: the default `docker` driver refuses a multi-platform push, and a container builder
  cannot reach a registry on the host's `localhost` without `--driver-opt network=host`.
- The release job's `images` step before and after: on the first tag after this feature.
- SC-002 on tupile: after that tag.
- **The whole cluster run on both legs** (T018, run on this branch, 2026-10-11): 66 jobs, every
  suite on `ubuntu-24.04` and on `ubuntu-24.04-arm`, the sidecar suite's Rust module built on both.
  Two arm64 jobs red, neither this feature's: `CloudProviderClusterFeatures (arm64)` on "a service
  deleted and applied again under the same name is given the bucket it had" (the scripted provider
  offered the credential twice, 1.1 s apart), which the nightly on `main` of the same morning
  (09:50, run on amd64 only) also failed — a race in feature 044's suite, not an architecture; and
  `ObjectStorageGcsClusterFeatures (arm64)` on "a storage credential issued again is a new one, and
  the old one is refused once the rotation grace has passed" (a 200 s wait expired), green on the
  amd64 leg of the same run and on `main`'s nightly, so either timing on the arm64 runner or a
  race the faster leg exposes; noted for feature 039's owner, and the first thing to re-run
  on the nightly's arm64 leg. The amd64 leg matched `main`'s nightly. Durations are in the run.
- **`OperatorClusterSuite` green on both legs, 44 of 44** (run 38151986074, 2026-10-11): amd64
  11 min 49 s, arm64 11 min 17 s; case 10b's report on each names its own architecture. The first
  two runs of the branch failed on the case's own assertions (the lifecycle read before the
  rollout's deadline; the kubelet's normalised image name), never on the feature.
- **`OperatorClusterSuite` on the cluster workflow, both legs, first run** (run 38150083552, 2026-10-11):
  amd64 on `ubuntu-24.04` 11 min 34 s; arm64 on `ubuntu-24.04-arm` 10 min 48 s, including the
  image build, k3s, CNPG, cert-manager and 44 cases. V3 answered: the arm64 runner, Docker, k3s
  and the sbt launcher all work there with no change.
