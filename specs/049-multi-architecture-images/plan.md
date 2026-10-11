# Implementation Plan: Images for Both Architectures — the Platform Runs on arm64 Clusters

**Branch**: `049-multi-architecture-images` | **Date**: 2026-10-11 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/049-multi-architecture-images/spec.md`.

## Summary

Every image the release publishes becomes one manifest list for `linux/amd64` and `linux/arm64`
under each of its tags. sbt-native-packager 1.11.7 already does this when `dockerBuildxPlatforms` is
set: `Docker/publish` runs the ordinary single-platform `publishLocal` first and then
`docker buildx build --platform=… --push` with every alias, so the local build is untouched by the
plugin's own design. The setting is read from one environment variable, `ANKKA_IMAGE_PLATFORMS`,
which only the release job sets, and a build with it set and no `DOCKER_REPOSITORY` refuses. The
console is built with the same buildx command. The release job gains QEMU and a buildx builder,
asks ghcr.io whether each image holds both platforms before it goes on, and warms the installations'
cache once per platform. The cluster workflow's matrix gains an architecture axis so every k3s suite
runs on `ubuntu-24.04-arm` beside `ubuntu-24.04`. The operator turns the kubelet's `exec format
error` into a report that names the image and the architecture it lacks, reading the node's
architecture with a new read-only grant. The template's build and its deploy workflow carry the same
setting, and the documentation says which architectures the platform runs on, how to build a
service's image for a cluster's architecture, and what a cluster of one's own machines needs.
Nothing in any overlay changes.

## Technical Context

**Language/Version**: Scala 3 on the JVM for the operator's detail and the suites; YAML for the two
workflows; Python for `.github/cluster-suites.py`; sbt for the build setting; Markdown for the docs.

**Primary Dependencies**: sbt-native-packager 1.11.7 (`dockerBuildxPlatforms`, already in the
build); `docker/setup-qemu-action` and `docker/setup-buildx-action` in the release and cluster
workflows; GitHub's hosted `ubuntu-24.04-arm` runner. No new library in any module.

**Storage**: none.

**Testing**: munit offline suites in `operator` (`LifecycleRulesSuite`, a new `PodProblemSuite`
case), `cli` (`HomebrewFormulaSuite`'s sibling, a new `WorkflowShapeSuite` pinning the two
workflows and the matrix script, and `TemplateSuite` for the template's setting); one k3s scenario
in `SampleDeploymentClusterSuite` with an image built for the other architecture; `just docs`; the
cluster workflow on demand with `-f suite=…` on both legs; the release workflow on the next tag.

**Target Platform**: Kubernetes on amd64 and arm64 nodes; kind locally on either; k3s in tests.

**Project Type**: platform (build, release workflow, cluster workflow, operator, template, docs).

**Performance Goals**: SC-001 both platforms on every image at the tag; SC-002 an arm64 cluster to
`Ready` within 10 minutes; SC-004 the local build unchanged within noise; SC-005 the architecture
reported within 2 minutes of apply.

**Constraints**: `publishLocal` and every suite's image build stay single-platform; no test names an
image by a literal tag; a new test switch is forwarded in `Test / javaOptions`; the operator's grant
grows by `get` on `nodes` and nothing else; the installations' cache check still compares digests;
the sidecar suite's Rust module builds on the arm64 leg; a docs page tells no history.

**Scale/Scope**: seven images, two workflows, one build setting in two builds, one operator detail,
one new ClusterRole rule, three docs pages.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the repository's standing rules
(`CLAUDE.md`, `.claude/rules/build-and-release.md`, `kubernetes.md`, `testing.md`, `docs.md`) are the
gates:

| Gate | Pre-research | Post-design |
|---|---|---|
| Only a tag publishes; nothing writes a tracked file during publish | pass: the setting is an environment variable the release job sets; no file is written | pass |
| No test names an image by a literal tag | n/a | pass: the k3s case builds its other-architecture image from a Dockerfile in the suite and tags it by `BuildInfo.imageTag` with a suffix (R5) |
| A new test switch is forwarded to the forked JVM | n/a | pass: no new switch; the k3s case runs under `ankka.cluster.tests` as its siblings do |
| The operator depends on `crd` only; its grant is the narrowest that works | pass | pass: `get` on `nodes`, cluster-scoped, read-only; proved under the real identity by `OperatorClusterSuite` (R5) |
| A docs page stands alone and tells no history | n/a | pass: the new section and the two notes name no feature and no date (R7) |
| Could this check pass while the thing it checks is false? | | the release's platform check reads the registry's manifest list, not the build log; the cache warm pulls each platform rather than inspecting; the k3s case asserts the report's words, not the pod's state; `WorkflowShapeSuite` asserts the steps by their `uses:` lines and the platforms by the literal both workflows must carry (R8) |

## Project Structure

### Documentation (this feature)

```text
specs/049-multi-architecture-images/
├── plan.md              # this file
├── research.md          # R1–R9, verify-first list, found during implementation, measurements
├── quickstart.md        # how to see it work: offline suites, a kind cluster on either laptop, the workflows on demand
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks output
```

No `data-model.md`: the feature has no entity, no event and no resource field. No `contracts/`:
the one interface is the environment variable `ANKKA_IMAGE_PLATFORMS`, described in research R1 and
in `docs/deploy/images.md`.

### Source Code (repository root)

```text
build.sbt                                    # dockerSettings: dockerBuildxPlatforms from ANKKA_IMAGE_PLATFORMS; the refusal
.github/workflows/release.yml                # images job: QEMU, buildx, the console by buildx, the platform check, the cache warm per platform
.github/workflows/cluster.yml                # matrix × architecture; runs-on from the matrix; QEMU not needed (each leg is native)
.github/cluster-suites.py                    # emits each suite once per architecture with its runner
.github/ci-coverage.py                       # unchanged unless a new file needs a claim
operator/src/main/scala/…/operator/
├── ClusterSnapshot.scala                    # PodProblem gains the node's architecture when the problem is an exec format error
├── LifecycleRules.scala                     # problemDetail: the architecture wording
├── Executor.scala / Reconciler              # reading the pod's node (one get) when a problem's message says exec format error
kustomization/components/operator/operator.yaml   # ClusterRole: get on nodes
operator/src/test/scala/…/operator/
├── LifecycleRulesSuite.scala                # the detail for an exec format error, with and without an architecture
├── PodProblemSuite.scala (new or existing)  # a CrashLoopBackOff whose last words are an exec format error
controlplane/src/test/scala/…/              # SampleDeploymentClusterSuite: the other-architecture image scenario (k3s)
cli/src/test/scala/…/cli/
├── WorkflowShapeSuite.scala (new)           # release.yml, cluster.yml and cluster-suites.py pinned to the shape this feature needs
├── TemplateSuite.scala                      # the expansion's build.sbt carries the platforms setting
ankka.g8/src/main/g8/build.sbt               # dockerBuildxPlatforms from ANKKA_IMAGE_PLATFORMS, commented
ankka.g8/src/main/g8/.github/workflows/deploy.yml   # QEMU, buildx, ANKKA_IMAGE_PLATFORMS on the build step
docs/deploy/images.md                        # "Build for the cluster's architecture", per hosting; the platform's images' architectures
docs/platform/install-cloud.md               # prerequisites: nodes of either architecture; "A cluster of your own machines"
docs/deploy/ci.md                            # the action's documented job builds for both
features/images/architectures.feature        # the eight scenarios (already written)
```

**Structure Decision**: no new module or directory. The feature touches the build, the two
workflows, the operator's reporting, the template, and three docs pages, each where that concern
already lives.

## Complexity Tracking

No violation to justify.
