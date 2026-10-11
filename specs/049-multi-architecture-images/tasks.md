# Tasks: Images for Both Architectures — the Platform Runs on arm64 Clusters

**Input**: Design documents from `/specs/049-multi-architecture-images/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[quickstart.md](./quickstart.md)

**Tests**: included, and first where a test can exist before the change. Two things here can only
be proved by a workflow run: the release's platform check (on the next tag) and the arm64 leg of
the cluster run (on demand, then nightly). Each is named as a task with the command that proves it,
and the task is ticked only once the run is green. Where a task says "case", it means a `test(...)`
in the named suite, named for the scenario or the rule it holds.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a cluster of arm64 nodes pulls a release's images), US2 (the platform is proved
  on arm64 where it is proved on amd64), US3 (a developer's image is built for the cluster it is
  deployed to), US4 (the local platform is unchanged), US5 (the documentation says what a cluster
  of one's own machines needs)

Paths are repository-relative. `OP`/`OPT` = `operator/src/{main,test}/scala/com/thinkmorestupidless/ankka/operator`;
`CLIT` = `cli/src/test/scala/com/thinkmorestupidless/ankka/cli`; `G8` = `ankka.g8/src/main/g8`;
`WF` = `.github/workflows`; `DOCS` = `docs`; `FEAT` = `features/images`. "R*n*" and "V*n*" are
sections of `research.md`.

The branch `049-multi-architecture-images` is in the worktree
`.claude/worktrees/049-multi-architecture-images`. Every `sbt` command takes
`-Dankka.cluster.tests=off` unless the task names a k3s suite; a k3s suite is run on demand with
`gh workflow run cluster --ref 049-multi-architecture-images -f suite=<Name>` or locally under
`caffeinate -i`.

---

## Phase 1: Setup — the setting and its refusal

**Purpose**: the one setting everything else reads, in the platform's build first.

- [X] T001 [US4] Add to `dockerSettings` in `build.sbt`: `dockerBuildxPlatforms` read from `ANKKA_IMAGE_PLATFORMS` (comma-separated, trimmed, empty when unset), with a comment saying `publish` is two builds in native-packager 1.11.7 and `publishLocal` never reads it (R1)
- [X] T002 [US4] Add the refusal beside it: a `val` in `build.sbt` that calls `sys.error` naming both variables when `ANKKA_IMAGE_PLATFORMS` is set and `DOCKER_REPOSITORY` is not (the "top-level `require`" trap: a `val`, not a bare call) (R1)
- [X] T003 [US4] Prove the local build unchanged: `sbt docker:publishLocal` on this laptop builds the host's architecture with the same tags as before, and `ANKKA_IMAGE_PLATFORMS=linux/amd64 sbt operator/Docker/publish` with no registry fails before any build with the message; record in research "Found during implementation"
- [X] T004 Verify V4: `docker buildx imagetools inspect <known multi-platform image> --format '{{range .Manifest.Manifests}}{{.Platform.OS}}/{{.Platform.Architecture}} {{end}}'` prints the platforms as R3 writes it; note the exact format string that worked

---

## Phase 2: User Story 1 — A cluster of arm64 nodes pulls a release's images (Priority: P1) 🎯 MVP

**Goal**: every image under one tag for both platforms; a release refused for one; the cache warm
per platform.

**Independent Test**: `WorkflowShapeSuite` green; then the next tag's `images` job green with both
platforms on all seven images (quickstart §6).

- [X] T005 [P] [US1] Write `CLIT/WorkflowShapeSuite.scala` first, modelled on `HomebrewFormulaSuite`: reads `WF/release.yml` and asserts the `images` job has `docker/setup-qemu-action` and `docker/setup-buildx-action` steps, sets `ANKKA_IMAGE_PLATFORMS: linux/amd64,linux/arm64` in its env, builds the console with `docker buildx build --platform linux/amd64,linux/arm64 --push`, and that the pullable check names both `linux/amd64` and `linux/arm64` with an `exit 1` on absence; and the cache warm pulls with `--platform` for each. Red until T006–T009
- [X] T006 [US1] `WF/release.yml`, job `images`: add `docker/setup-qemu-action@v3` and `docker/setup-buildx-action@v3` before the sbt publish; add `ANKKA_IMAGE_PLATFORMS: linux/amd64,linux/arm64` to the job's `env`; update the job's header comment (seven images, both platforms, why QEMU) (R1)
- [X] T007 [US1] `WF/release.yml`: replace the console's `docker build` + `docker push` with one `docker buildx build --platform linux/amd64,linux/arm64 --push` naming both tags; `console/Dockerfile`'s build stage becomes `FROM --platform=$BUILDPLATFORM …` with a comment (R2). Do V2 first: read `console/package/package.json` and `console/host/package.json` for a per-platform binary in `dependencies`; if one exists, the final stage runs `npm ci --omit=dev` on the target platform instead of copying `node_modules`, and research records which
- [X] T008 [US1] `WF/release.yml`, step "Every image is publicly pullable": after the 200 check, read the manifest list with `imagetools inspect` and fail naming the image and the missing platform (R3, the format from T004); rename the step to say it checks both platforms
- [X] T009 [US1] `WF/release.yml`, step "Warm the clusters' cache": pull each image once per platform (`--platform linux/amd64`, `--platform linux/arm64`) through the cache; keep the digest comparison; update the comment (R3)
- [X] T010 [US1] Run `sbt -Dankka.cluster.tests=off 'cli/testOnly *WorkflowShapeSuite'` green; `.github/ci-coverage.py` needs no change unless it refuses the new test file (`cli/**` claims it); run `python3 .github/ci-coverage.py` to confirm
- [ ] T011 [US1] Quickstart §3 on the laptop against a throwaway registry: `operator/Docker/publish` with the two variables produces an index with both platforms; record the duration of the buildx build in research Measurements (V5's local half)
- [ ] T012 [US1] On the next tag (not this branch's to cut): the `images` job green, seven images × two tags × two platforms; record in research Measurements the job's duration before and after. Ticked when the run is green

---

## Phase 3: User Story 2 — The platform is proved on arm64 where it is proved on amd64 (Priority: P1)

**Goal**: every k3s suite runs on `ubuntu-24.04-arm` beside `ubuntu-24.04`.

**Independent Test**: `python3 .github/cluster-suites.py` lists each suite twice with its runner;
`gh workflow run cluster` produces one job per suite per architecture, both green.

- [X] T013 [P] [US2] Extend `CLIT/WorkflowShapeSuite.scala`: runs `.github/cluster-suites.py` as a subprocess and asserts every suite appears exactly twice, once with `arch: amd64, runner: ubuntu-24.04` and once with `arch: arm64, runner: ubuntu-24.04-arm`; asserts `WF/cluster.yml` takes `runs-on: ${{ matrix.runner }}` and names its job and artifacts with `matrix.arch`; asserts the `arch` input narrows. Red until T014–T015
- [X] T014 [US2] `.github/cluster-suites.py`: emit each suite per architecture with `arch` and `runner`; a second optional argument narrows to one architecture (`amd64`, `arm64`, or empty for both); a name that matches no suite or an unknown architecture fails as today; update the docstring (R4)
- [X] T015 [US2] `WF/cluster.yml`: `runs-on: ${{ matrix.runner }}`; job name `${{ matrix.suite }} (${{ matrix.arch }})`; artifact names `resources-<suite>-<arch>` and `test-reports-<suite>-<arch>`; a `workflow_dispatch` input `arch` passed to the script; the `suites` job passes both inputs; header comment updated (why two legs, why no emulation) (R4)
- [X] T016 [US2] Run `sbt -Dankka.cluster.tests=off 'cli/testOnly *WorkflowShapeSuite'` green
- [ ] T017 [US2] V3: `gh workflow run cluster --ref 049-multi-architecture-images -f suite=SampleDeploymentClusterSuite -f arch=arm64`; watch it; if the runner label, Docker, k3s or Ryuk fails there, fix and record in research "Found during implementation". Ticked when green
- [ ] T018 [US2] `gh workflow run cluster --ref 049-multi-architecture-images` (every suite, both legs); the sidecar's arm64 leg builds the Rust module; record each suite's arm64 duration beside amd64's in Measurements. Ticked when every job is green, or with the red ones named and understood

---

## Phase 4: User Story 3 — A developer's image is built for the cluster it is deployed to (Priority: P2)

**Goal**: the template carries the setting and its workflow builds for both; the operator says in
words when an image lacks the node's architecture.

**Independent Test**: `TemplateSuite` and `LifecycleRulesSuite` green; the k3s case reports the
architecture; `services get` shows the same words.

### The operator names the architecture

- [X] T019 [P] [US3] Write the cases first in `OPT/LifecycleRulesSuite.scala`: a `CrashLoopBackOff` problem whose message ends `exec format error` with `architecture = Some("arm64")` renders `Failed` with detail `image cart:2 has no build for this node's architecture (arm64)`; with `architecture = None` the same sentence without the parenthesis; any other `CrashLoopBackOff` message renders as today. Red until T021
- [X] T020 [P] [US3] Write `OPT/PodProblemSuite.scala` (or extend the suite that already covers `PodProblem.of`): a container `waiting` with reason `CrashLoopBackOff` and `lastState.terminated` reason `StartError`, message `… exec format error`, yields a problem flagged as an architecture mismatch; `RunContainerError` is actionable; `ImagePullBackOff` is unchanged. Red until T021
- [X] T021 [US3] `OP/ClusterSnapshot.scala`: `PodProblem` gains `architecture: Option[String] = None` and `def wrongArchitecture: Boolean` (message contains `exec format error`); `RunContainerError` joins `Actionable`. `OP/LifecycleRules.scala`: `problemDetail` renders a wrong-architecture problem with the image from the spec and the architecture when known (R5)
- [X] T022 [US3] `OP/Executor.scala` `podProblems`: for a problem that is a wrong-architecture one, read the pod's node by `spec.nodeName` (`client.nodes().withName(…).get()`, one `get`), take `kubernetes.io/arch`, and set `architecture`; a refused or failed read leaves it `None` and logs at debug. Any other problem reads nothing (R5)
- [X] T023 [US3] `kustomization/components/operator/operator.yaml`: the ClusterRole gains `get` on `nodes` (core group), with a comment naming why and that it is the operator's only cluster-scoped read; the symlinked copy under `operator/src/main/resources` follows; `OperatorClusterSuite`'s RBAC cases still pass under the real identity
- [X] T024 [US3] Run `sbt -Dankka.cluster.tests=off operator/test` green (`LifecycleRulesSuite`, `PodProblemSuite`, `RenderingGoldenSuite` unchanged since no render changes)
- [ ] T025 [US3] V1 and the k3s case: in `OPT/OperatorClusterSuite.scala` after case 10, "10b. an image for the other architecture is reported Failed naming the architecture": build a two-line image `FROM --platform=linux/<other than this host> eclipse-temurin:21-jre` with the sample's entrypoint command (no `RUN`, no emulation), tagged `sample-shopping-cart:<BuildInfo.imageTag>-other-arch`, import it with `ClusterImages.importInto`, write a spec naming it, wait up to 120s for `Failed`, print the raw `PodProblem` once, and assert the detail contains `has no build for this node's architecture` and the host's architecture name. Run locally under `caffeinate -i sbt 'operator/testOnly *OperatorClusterSuite -- *10b*'`; record the kubelet's exact reason and message in research "Found during implementation" (V1), and adjust T021's match if the words differ
- [ ] T026 [US3] `services get` and the history show the detail unchanged (the control plane folds `detail` as it is): assert in the same case through the resource's status, which is what the control plane reads; no control plane change expected — if one is needed, record why

### The template and the action

- [X] T027 [P] [US3] Extend `CLIT/TemplateSuite.scala` case 6 (or a new case 6b): the expansion's `build.sbt` contains `ANKKA_IMAGE_PLATFORMS` on a `dockerBuildxPlatforms` line, and its `deploy.yml` contains `docker/setup-qemu-action`, `docker/setup-buildx-action` and `ANKKA_IMAGE_PLATFORMS: linux/amd64,linux/arm64`. Red until T028
- [X] T028 [US3] `G8/build.sbt`: the `dockerBuildxPlatforms` line with its comment (R6); `G8/.github/workflows/deploy.yml`: the two actions before the build step and the variable in `env`, every `${{` written `\${{` (the Giter8 trap)
- [ ] T029 [US3] Run `sbt -Dankka.template.tests=scala 'cli/testOnly *TemplateSuite'` green (needs sbt and Docker; the expansion's own `Docker/publishLocal` still builds the host's image)

---

## Phase 5: User Story 4 — The local platform is unchanged (Priority: P2)

**Goal**: nothing in the local path reads the setting.

- [ ] T030 [US4] Extend `CLIT/WorkflowShapeSuite.scala`: reads `build.sbt` and asserts `dockerBuildxPlatforms` is set from `sys.env.get("ANKKA_IMAGE_PLATFORMS")` and nowhere else in the file, and that `kustomization/deploy-local.sh` and `Justfile` do not mention the variable
- [ ] T031 [US4] `./kustomization/deploy-local.sh` on the laptop's kind cluster end to end, as before this feature; `RemoteOverlaySuite` green (`sbt -Dankka.cluster.tests=off 'controlPlane/testOnly *RemoteOverlaySuite'`)

---

## Phase 6: User Story 5 — The documentation (Priority: P3)

**Goal**: the three pages say what R7 lists, and tell no history.

- [X] T032 [P] [US5] `DOCS/deploy/images.md`: a section "Build for the cluster's architecture" (R7): the platform's images' two architectures; Scala by `ANKKA_IMAGE_PLATFORMS` with buildx and QEMU for the architecture the machine is not; Python, TypeScript and web by `docker buildx build --platform … --push`; Rust needs nothing; what the report says for a wrong one. Update the Scala section's settings block to show the new line
- [X] T033 [P] [US5] `DOCS/platform/install-cloud.md`: the prerequisites gain nodes of either architecture; the new section "A cluster of your own machines" before "Check that the network enforces policy": a gateway address (k3s's ServiceLB or MetalLB), a default storage class (`local-path` pins a volume to a node), a DNS-01 solver needing no inbound connection (R7)
- [X] T034 [P] [US5] `DOCS/deploy/ci.md`, "Use the action": one sentence that the generated job builds the image for both architectures and what to remove to build one
- [X] T035 [US5] `just docs` green; `just docs-sync` if a generated table changed (none expected); confirm `docs/reference/limitations.md` needs nothing

---

## Phase 7: Polish — rules, the features check, the whole run

- [X] T036 [P] `.claude/rules/build-and-release.md`: a paragraph under "The images ship through GitHub Container Registry" on the two-platform build, `ANKKA_IMAGE_PLATFORMS`, why `publishLocal` is untouched, the per-platform cache warm, and the trap that a `docker` driver cannot push an index; `.claude/rules/kubernetes.md` traps: the kubelet's words for a wrong-architecture image and the `get` on nodes
- [ ] T037 [P] `just features` reports 0 findings (no feature file changes expected)
- [ ] T038 `sbt -Dankka.cluster.tests=off buildAll` green; `git status` clean of anything the build wrote
- [ ] T039 Research "Found during implementation" and "Measurements" filled from T003, T011, T017, T018, T025; the "Verify first" list each marked done or carried
- [ ] T040 Open the pull request against `main` after `specs-049-050` has merged (the branch carries that commit); the description names SC-001 and SC-002 as proved on the next tag and on tupile respectively

---

## Dependencies & Execution Order

- Phase 1 before everything: T001–T002 are the setting the release job (T006) and the template
  (T028) mirror.
- Phase 2 and Phase 3 are independent of each other and of Phase 4; each starts with its
  `WorkflowShapeSuite` cases (T005, T013), which share one file and are written in order.
- Phase 4's operator half (T019–T026) and template half (T027–T029) are independent.
- T012, T017, T018 are workflow runs: T017 can run as soon as T014–T015 are pushed to the branch;
  T012 waits for a tag and is the one task this branch cannot finish by itself.
- Phase 6 can be written any time after research; it is last because the words should match what
  was built.

## Parallel Example

Phase 2 and Phase 3 together: `[P]` T005 and T013 (one file, two cases), then T006–T009 and
T014–T015 in parallel, then T010/T016, then the workflow runs.
