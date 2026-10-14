# Feature Specification: Images for Both Architectures — the Platform Runs on arm64 Clusters

**Feature Branch**: `049-multi-architecture-images`

**Created**: 2026-10-11

**Status**: Draft

**Input**: User description: "I'd like to get ankka working on a Raspberry Pi cluster I have on my
local network: k3s with each Pi as a node, deployed with a new overlay. The overlay is the private
infrastructure repository's; what the platform itself lacks is an image a Pi can pull. Every image the
release publishes is built for one architecture, the runner's, so an arm64 node cannot start the
operator, the control plane, the sidecar, the proxy, the storage mover, the console or the sample.
Publish every image for both architectures, prove the platform on an arm64 cluster the way it is
proved on amd64 today, and say in the documentation what a cluster of one's own machines needs."

## Context

The release workflow (`.github/workflows/release.yml`, job `images`) publishes seven images on every
`v*` tag: `sbt operator/Docker/publish controlPlane/Docker/publish shoppingCart/Docker/publish
sidecar/Docker/publish proxy/Docker/publish storageMover/Docker/publish`, and `docker build` of
`console/`. Every one is built on an `ubuntu-24.04` runner by sbt-native-packager 1.11.7 over
`eclipse-temurin:21-jre`, or by Docker over a Node base, and neither names a platform: each tag on
`ghcr.io/thinkmorestupidless/<image>` is one manifest for `linux/amd64`. The job's last two steps ask
ghcr.io whether each image is publicly pullable and warm the installations' pull-through cache by
pulling each image once, which fetches the runner's architecture alone.

A cluster whose nodes are arm64 — a Raspberry Pi, a Graviton or Ampere instance, GKE's Tau T2A —
cannot run any of them. Every one of ankka's own processes is a JVM or Node program with no native
code of its own, and the runtime the sidecar loads a WebAssembly module into is Chicory, pure JVM; the
only thing that keeps the platform off arm64 is that no arm64 image is published. That it runs there
is already proved every day in a way nobody counts: `sbt docker:publishLocal` on an Apple silicon
laptop builds the host's architecture, `kind load` puts those images on an arm64 kind node, and
`deploy-local.sh` brings the whole platform up on them.

The four controllers an installation stands on, CloudNativePG, cert-manager, Envoy Gateway and
Keycloak with its operator, publish both architectures, as do Strimzi, Garage, trust-manager, the
OpenTelemetry collectors and Grafana's `otel-lgtm`; the plan verifies each at the pinned release
rather than taking it as read. The native CLI is already built per platform, `linux-arm64` among
them, and the GitHub Action already picks the runner's.

Three things follow from "one tag, both architectures".

- **One tag is one manifest list.** A tag names one release for both platforms, never two tags or a
  suffix. A cluster pulls `ghcr.io/thinkmorestupidless/ankka-operator:0.12.0` and the registry serves
  the node's architecture. The installations' cache, which stores what it has served, has to be
  warmed for both, or an arm64 node's first pull during a ghcr.io outage finds nothing.
- **The local flow is untouched.** sbt-native-packager builds a multi-platform image only with
  buildx and only by pushing; it cannot load one into the local daemon. `docker:publishLocal` keeps
  building the host's architecture for `kind load` and the test suites; publishing for both is the
  release job's setting, gated the way `DOCKER_REPOSITORY` already is.
- **A release is refused rather than published for one architecture.** The check that every image is
  publicly pullable gains a second question: does the manifest list hold both platforms. A base image
  that one day drops a platform, or a build step that quietly fell back to one, fails the job and
  names the image.

The platform's own proof that it runs on a cluster is `.github/workflows/cluster.yml`: every k3s suite,
nightly, on `ubuntu-24.04`. GitHub hosts arm64 runners (`ubuntu-24.04-arm`), and the release workflow
already uses one for the native CLI. The k3s suites gain the arm64 runner as a second leg of the
matrix, so the platform is proved on arm64 where it is proved on amd64 and a regression that only an
arm64 node sees fails there first.

The developer's own images are the developer's. A Scala service's image is built by the template's
sbt build on the developer's machine or in CI; a Python or TypeScript service's by their Dockerfile;
a Rust service's holds only a module, which has no architecture. A developer on an amd64 laptop
deploying to an arm64 cluster builds for the cluster, and the documentation and the template say
how; the platform's part is to say clearly when an image has no build for the node it was scheduled
on, which today is a `CrashLoopBackOff` whose reason is three words in a container's termination
message.

**Rejected: a second set of tags, `<version>-arm64`.** It makes a version two artifacts, puts the
architecture in every overlay's `images:` block, and breaks the installations' rule that one commit
moves one tag.

**Rejected: building the arm64 images under emulation on the amd64 runner alone.** It is how the
plan may well do it, since a JVM image copies jars and compiles nothing; the rejection is of having
no arm64 runner anywhere. Without one, nothing proves an arm64 image starts, and the first node to
find out is a customer's.

## Clarifications

### Session 2026-10-11

- Q: Should the Raspberry Pi cluster's overlay live in ankka, in a new repository, or in the private
  infrastructure repository? → A: The private infrastructure repository, as every installation's
  overlay does (its decision D14, and this repository's rule that no installation's overlay lives
  here). This feature is the platform's part only: the images, the proof and the documentation.
- Q: Does an installation on one's own machines need a feature of its own? → A: No. The example
  production overlay already assumes nothing about a cloud; what such a cluster needs is said on the
  install page (FR-010) and nothing in the platform changes for it.
- Q: Are the arm64 cluster suites required on a pull request? → A: No. The k3s suites never run on a
  pull request today; the arm64 leg joins the nightly and on-demand run.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A cluster of arm64 nodes pulls a release's images (Priority: P1)

A person installs the platform on a k3s cluster whose nodes are arm64, with an overlay naming the
release's images at their tag. Every node pulls the image it needs and every pod starts: the operator,
the control plane, the console, and, when a service is applied, the sidecar beside a process and the
proxy beside a web-hosted program. Nothing in the overlay names an architecture.

**Why this priority**: Without it no other part of this feature matters; this is the one thing that
keeps the platform off arm64.

**Independent Test**: After a release, `docker buildx imagetools inspect` of each of the seven images
at the release's tag lists `linux/amd64` and `linux/arm64`; an arm64 k3s cluster given the example
production overlay at that tag reaches a `Ready` control plane and operator.

**Acceptance Scenarios**:

- added `features/images/architectures.feature`: a release publishes every image of the platform for both architectures under one tag
- added `features/images/architectures.feature`: a cluster of arm64 nodes starts every image of the platform
- added `features/images/architectures.feature`: a release is refused when an image of the platform is built for one architecture

---

### User Story 2 - The platform is proved on arm64 where it is proved on amd64 (Priority: P1)

The nightly cluster run executes every k3s suite twice, once on an amd64 runner and once on an arm64
runner, each building that leg's images on its own architecture. A failure names the suite and the
architecture, and a suite that passes on one and fails on the other is visible as exactly that.

**Why this priority**: An image that is published for arm64 and never run there is a promise nobody
checked. The cluster suites are the platform's proof of itself on a cluster, and arm64 joins it.

**Independent Test**: `gh workflow run cluster` produces one job per suite per architecture; the
sidecar suite's leg builds the Rust module on the arm64 runner; both legs are green on `main`.

**Acceptance Scenarios**:

- added `features/images/architectures.feature`: the platform's cluster suites run on an arm64 runner as they run on an amd64 runner

---

### User Story 3 - A developer's image is built for the cluster it is deployed to (Priority: P2)

A developer on an amd64 laptop deploys a Scala service to an arm64 cluster. The template's build
says in one place which platforms the service's image is built for, the documentation says how to
build for both or for the cluster's one, and the GitHub Action's documented job does the same. When
a developer gets it wrong and applies an image with no build for the cluster's architecture, the
service's report says so in words rather than reporting a crash loop with no reason.

**Why this priority**: Every image the developer builds is theirs, and a platform that runs on arm64
while every guide builds for amd64 moves the problem rather than solving it.

**Independent Test**: `ankka init` expands a Scala project whose build names the platforms its
image is built for; `docs/deploy/images.md` says how to build for a cluster's architecture for each
hosting; in a k3s suite an image built for the other architecture is applied and the report reads
`Failed` with a detail naming the architecture the image lacks.

**Acceptance Scenarios**:

- added `features/images/architectures.feature`: a service whose image has no build for the cluster's architecture is reported with that reason
- added `features/images/architectures.feature`: a template's build says which architectures the service's image is built for

---

### User Story 4 - The local platform is unchanged (Priority: P2)

A developer runs `deploy-local.sh` on a laptop of either architecture and the images built on it
load into kind as they always have. Nothing about publishing for two platforms reaches the local
build, the suites or their image tags.

**Why this priority**: The multi-platform build cannot load into a local daemon; a change that
reached `docker:publishLocal` would break every developer's cluster and every k3s suite.

**Independent Test**: `sbt docker:publishLocal` on a laptop builds the host's architecture and
`kind load docker-image` accepts it; `RemoteOverlaySuite` and the k3s suites run unchanged.

**Acceptance Scenarios**:

- added `features/images/architectures.feature`: building the images on a developer's machine builds for that machine alone

---

### User Story 5 - The documentation says what a cluster of one's own machines needs (Priority: P3)

A person with a cluster that no cloud made, k3s on machines in a cupboard, reads the install page and
learns what the platform needs of it that a cloud would otherwise provide: a way to give the gateway
an address, a default storage class for the databases, nodes of either architecture, and a DNS-01
solver reachable from inside, which needs no inbound connection. The page says the platform runs on
arm64 and amd64 and names nothing else about hardware.

**Why this priority**: The install page is written for a cloud cluster, and a reader with a different
kind of cluster has to guess which of its assumptions are the platform's and which are the cloud's.

**Independent Test**: `docs/platform/install-cloud.md` has a section for a cluster of one's own
machines; `docs/deploy/images.md` names both architectures; `just docs` passes.

**Acceptance Scenarios**:

- added `features/images/architectures.feature`: the documentation says which architectures the platform runs on and what a cluster of one's own machines needs

---

### Edge Cases

- A base image whose next pinned version drops `linux/arm64`: the release's check fails naming the
  image, and nothing is published for either architecture under that tag (FR-003).
- The console image's build installs a Node dependency with a native part: the build for arm64 must
  produce it for arm64 or fail; the check in FR-003 catches an image that silently became one
  platform's.
- The installations' pull-through cache: a pull on the amd64 runner warms the amd64 layers only. The
  warm step pulls each image once per platform (FR-004), and the digest it compares is the manifest
  list's.
- A mixed cluster, amd64 and arm64 nodes together: every pod gets the node's image from the one tag;
  nothing in the platform pins a pod to an architecture, and nothing needs to.
- A developer's image built for one architecture, applied to a mixed cluster: the instances on the
  wrong nodes crash-loop and the ones on the right nodes serve. The report says which instances are
  not ready and why (FR-008); the fix is the developer's build.
- A k3s suite that passes on amd64 and fails on arm64: the nightly run reports it as one red job
  named by architecture; nothing merges on it, since the cluster suites never gate a pull request.
- `sbt docker:publishLocal` on a machine where the release setting is accidentally set: the build
  refuses with a message naming the variable, rather than attempting a multi-platform build that
  cannot load (FR-005).

## Requirements *(mandatory)*

### Functional Requirements

**Publishing**

- **FR-001**: Every image the release publishes — the operator, the control plane, the sidecar, the
  proxy, the storage mover, the sample and the console — MUST be published under each of its tags as
  one manifest list holding `linux/amd64` and `linux/arm64`.
- **FR-002**: A tag MUST name one release for both platforms; no tag, label or image name MUST carry an
  architecture.
- **FR-003**: The release MUST refuse to complete when any image at the release's tag lacks either
  platform, naming the image and the platform it lacks, and MUST NOT leave an image published for one
  platform under a release's tag without the job failing.
- **FR-004**: The step that warms the installations' pull-through cache MUST pull each image once per
  platform, and MUST compare the cache's manifest list digest with ghcr.io's.
- **FR-005**: `docker:publishLocal` and every build the suites make MUST build the host's architecture
  only, as today. The multi-platform build MUST be enabled by one setting the release job sets, and
  a local build with that setting present MUST refuse, naming it.

**Proof**

- **FR-006**: The cluster workflow MUST run every k3s suite on an arm64 runner as well as the amd64
  one, each leg building its own images, each job named by suite and architecture, with the Rust
  module built on the leg's own runner for the sidecar suite.
- **FR-007**: The k3s suites MUST pass on arm64 before a release is cut with this feature, and the
  nightly run MUST keep both legs.

**The developer's image**

- **FR-008**: When an instance's container cannot start because its image has no build for the node's
  architecture, the operator MUST report the service `Failed` with a detail naming the architecture
  the image lacks and the image, and the control plane MUST show that detail in `services get` and in
  the service's history.
- **FR-009**: The Scala template's build MUST say in one setting which platforms a service's image is
  built for, with the local build still loading into kind, and `docs/deploy/images.md` MUST say, for
  each hosting, how to build for the cluster's architecture. The GitHub Action's documented job MUST
  build the service image for both platforms.

**Documentation**

- **FR-010**: `docs/platform/install-cloud.md` MUST gain a section for a cluster of one's own machines
  that says what the platform needs of it: an address for the gateway's `LoadBalancer` Service, a
  default storage class, and a DNS-01 solver for the wildcard certificate; and MUST say that nodes may
  be amd64 or arm64. `docs/deploy/images.md` MUST name both architectures for the platform's images.
  `docs/reference/limitations.md` MUST say nothing that this feature makes false.

### Key Entities

- **Image**: what a service or a platform process is run from; after this feature, one tag for both
  architectures.
- **Architecture**: the kind of processor a node has, `amd64` or `arm64`; the platform runs on both
  and nothing in it names either except the build that makes an image.
- **Cluster run**: the nightly execution of every k3s suite, now one leg per architecture.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On the first release after this feature, each of the seven images at the release's tag
  and at `latest` holds exactly the two platforms, checked by the job and again by hand.
- **SC-002**: An arm64 k3s cluster given the example production overlay at that tag reaches a `Ready`
  control plane and operator within 10 minutes of apply, and a Scala, a Python and a Rust sample each
  reach `Ready` on it; recorded in the plan's research as measured.
- **SC-003**: The cluster workflow's arm64 leg is green on `main` for every suite before the release,
  and stays in the nightly run.
- **SC-004**: `sbt docker:publishLocal` and every suite that builds an image behave exactly as before:
  same tags, same duration within noise, no buildx in the local path.
- **SC-005**: An image with no build for a node's architecture is reported `Failed` with the
  architecture named within 2 minutes of apply in a k3s suite; the detail is the same words in the
  CLI and the console.

## Assumptions

- The build for arm64 may run under emulation on the amd64 runner: a JVM image copies jars and a Node
  image copies a built package, and neither compiles anything under emulation. The plan measures the
  job's duration and moves a build to a native runner if emulation makes the job slower than it is
  worth.
- The arm64 cluster suites run on GitHub's hosted arm64 runners; the plan confirms the label and that
  Docker and k3s in testcontainers work on it as they do on amd64.
- The four controllers and the add-ons the components pin publish both platforms at the pinned
  releases; the plan verifies each with `imagetools inspect` and records it.
- The private infrastructure repository's overlay for an arm64 cluster, its storage class, its
  gateway address and its DNS are that repository's; this feature changes nothing in any overlay
  of ankka's.
- A developer's Python or TypeScript image is built by their Dockerfile with whatever base they chose;
  the documentation says how to build it for both platforms and the platform does not check it.

## Dependencies

- **006 (external applications)**: the template whose build gains the platform setting.
- **013 (deploy from GitHub)**: the Action whose documented job builds for both platforms.
- **009 (polyglot runtimes)** and **016 (wasm hosting)**: the sidecar image, which carries the
  runtime a process or a module is hosted by, is one of the seven.
- **021 (web hosting)**: the proxy image.
- **034 (object storage)**: the storage mover image.
- **050 (remote clusters)**: an arm64 cluster joined to an installation whose control plane runs
  elsewhere needs the operator's image for arm64 before anything else.

## Open Questions

- Whether `latest` should keep being published at all. It is multi-platform under this feature like
  every other tag; whether anything should pull it is a separate question nobody has asked yet.
