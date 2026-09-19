# Feature Specification: A nakka Application Built Outside This Repository

**Feature Branch**: `006-external-applications`

**Created**: 2026-09-18

**Status**: Draft

**Input**: User description: "A nakka application built outside this repository. Today the only
nakka services that exist are the samples inside this build [...] Nobody but this repository can
write a nakka service, which makes the platform a demo of itself. The work: publish the library
modules an application needs [...] provide a project template that `sbt new` expands into a
working service [...] and prove it end to end by expanding the template in an empty directory
outside this repository, running its tests, building its image, deploying it through the CLI to
the local cluster, exposing it and calling it by hostname — the first time an application that is
not in this build goes through the whole chain."

## Where this starts from

Every nakka service that has ever run is a sample inside this repository's own build. A sample
depends on the platform's library modules as build-internal references; it shares the
repository's Postgres definition, whose schema is mounted from a source directory; and its image
is built by the same command that builds the platform's. None of that is available to anyone
else. There is no published artifact, no version other than a placeholder, and no starting point
for a new service except copying a sample out of this tree and untangling it.

Two things make this more than "publish some jars":

- **The platform and an application are versioned separately once they are separate.** The
  operator applies the database schema from *its own* image (feature 002); the application brings
  its *own* runtime in its image. Today they cannot disagree because they are one build. After
  this feature they can, and nothing yet says what an application may assume about the platform
  it is deployed to, or what the platform may assume about the application.
- **The first hour decides whether anyone continues.** A developer who has to discover the
  registration lambda, the port convention, the descriptor field names, the database
  configuration and the image plugin before writing a line of their domain will not write one.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Depend on nakka from another build (Priority: P1)

A developer with an empty project adds the nakka libraries to their build definition by name and
version, and their build resolves them — locally during development of the platform itself, and
from a public repository once the platform is released — without any reference to this
repository's sources.

**Why this priority**: without it nothing else in this feature exists. It is also the smallest
piece.

**Independent Test**: on a machine with the platform's libraries published locally, a project
outside this repository that names them resolves, compiles a component against them and runs a
unit test through the test kit.

**Acceptance Scenarios**:

1. **Given** the platform's libraries are published to the developer's local repository, **When**
   a build outside this repository names the application-facing modules at that version, **Then**
   they resolve and a component compiles against them.
2. **Given** the same, **When** the project's tests use the test kit, **Then** the test kit's
   throwaway database is created with the platform's schema — the schema shipped *inside* the
   published runtime, with nothing copied into the project.
3. **Given** a build that names a platform-side module (control plane, operator, CLI, resource
   definitions), **When** it resolves, **Then** it fails: those are not libraries, and the
   distinction is enforced by them not being published rather than by documentation asking nicely.
4. **Given** a release is cut, **When** the release workflow runs, **Then** the same set of
   modules is published to the public repository at that version, signed, with sources and
   documentation — and the workflow exists and is proven against a local repository even before
   the public namespace is claimed.

---

### User Story 2 - Start a service from a template (Priority: P1)

A developer runs one command in an empty directory and gets a working nakka service: one entity,
one endpoint, one view, tests at both levels, a local database definition, image packaging and a
deployment descriptor — all named after their project. They run its tests, run it locally and
call it, then replace the stub domain with their own.

**Why this priority**: it is what turns "the libraries exist" into "a developer can start".

**Independent Test**: expand the template in an empty directory outside this repository; its
tests pass, `sbt run` serves the stub endpoint against the local database with no configuration
edited, and the image builds.

**Acceptance Scenarios**:

1. **Given** an empty directory and the template's name, **When** the developer runs the template
   command with a project name, **Then** a project exists whose package, service name, image name
   and descriptor all carry that name, and nothing in it mentions the stub name where the
   developer's name belongs.
2. **Given** the expanded project, **When** its tests run, **Then** an entity test (no runtime), an
   endpoint test and an integration test against a throwaway database all pass, unmodified.
3. **Given** the expanded project and a running local database started from the file the template
   provides, **When** `sbt run` starts, **Then** the service is serving on its documented port and
   a request to the stub endpoint writes and reads state — with no file edited between expanding
   and running.
4. **Given** the expanded project, **When** the image is built, **Then** it is tagged with the
   project's name and is the image the descriptor names.
5. **Given** the expanded project, **When** the developer reads its `README`, **Then** it says, in
   order, how to run the tests, run locally, build the image, and deploy it to a nakka platform —
   and each command in it is one that was executed in this feature's proof.

---

### User Story 3 - The whole chain, from outside (Priority: P1)

The expanded project's image is deployed to the local cluster through the CLI, becomes `Ready`
on a platform-provisioned database, is exposed, and answers by hostname — an application that is
not in this repository's build, going through every feature so far.

**Why this priority**: it is the proof the other two exist for, and it is the first time the
platform runs something it did not build.

**Independent Test**: from the expanded project: build and load the image, `nakka services apply`
the descriptor, `Ready`, `nakka services expose`, `curl` the stub endpoint by hostname with the
certificate verified.

**Acceptance Scenarios**:

1. **Given** the expanded project's image loaded into the local cluster, **When** its descriptor
   is applied through the CLI, **Then** the service reaches `Ready` on a provisioned database
   within the platform's normal time, with no change to the descriptor the template produced.
2. **Given** it is `Ready`, **When** it is exposed and the stub endpoint is called by hostname,
   **Then** state written is read back, and it survives a restart of the service.
3. **Given** the platform's documentation, **When** a reader follows it from "I have nothing" to
   "my service answers by hostname", **Then** every step is there and none refers to this
   repository's sources.

---

### User Story 4 - The platform and the application can disagree, and say so (Priority: P2)

An application built against one version of the runtime is deployed to a platform whose operator
and control plane are another version. The platform states what it supports; an application
outside that is refused or reported, never silently run against a schema it does not match.

**Why this priority**: today nothing can disagree; the first external application makes it
possible, and a silent mismatch corrupts data. P2 because the *first* release ships one version of
everything, so the contract matters before the second release, not before the first.

**Independent Test**: the operator's reported compatibility and an image's runtime version are
both visible; a deliberately mismatched pair is reported as such on `services get`.

**Acceptance Scenarios**:

1. **Given** a running platform, **When** an operator asks what runtime versions it supports,
   **Then** the answer is one visible value with a stated rule for reading it.
2. **Given** an application image whose runtime is within that range, **When** it is deployed,
   **Then** nothing about versions is mentioned — the common case is silent.
3. **Given** an application image whose runtime is outside it, **When** it is deployed, **Then**
   `services get` says which version the application carries and which the platform supports, and
   the service does not report `Ready` while that holds.
4. **Given** the schema the operator applies, **When** it changes between platform versions,
   **Then** the change is additive or the supported range is narrowed — a running application never
   finds a table it needs gone.

### Edge Cases

- **The developer's local repository has no nakka artifacts** — the template's build fails to
  resolve with a message naming the version it wanted and the command that publishes it, not a
  wall of resolver output.
- **The project name is not a valid service name** (uppercase, spaces, too long) — the template
  refuses at expansion with the rule, since the service name is also a hostname label (feature
  005) and a namespace component (feature 001).
- **The template is expanded inside this repository** — it must still work; nothing about it may
  depend on being outside, only on the libraries resolving.
- **A snapshot version** — publishing a snapshot locally is the normal development loop and must
  work; a release to the public repository from an unclean or untagged state must be refused by
  the workflow.
- **The application declares no HTTP** — the template's default declares it (the stub has an
  endpoint); the descriptor's `http`/`port` fields are documented so removing the endpoint is a
  one-line change.
- **An older application on a newer platform** — covered by US4; the reverse (newer application on
  an older platform) is the same rule read the other way and is reported the same way.

## Requirements *(mandatory)*

### Functional Requirements

**Publishing**

- **FR-001**: The application-facing modules — the core, SDK, runtime, HTTP, agent and test kit —
  MUST be publishable as versioned artifacts, with sources and documentation, under one
  organization and one version.
- **FR-002**: The platform-side modules — the control plane, its API types, the resource
  definitions, the operator and the CLI — MUST NOT be published as libraries. The CLI is
  distributed as a program, not a dependency.
- **FR-003**: Publishing to the developer's local repository MUST work from a clean checkout with
  one command, and is the development loop this feature's own proof uses.
- **FR-004**: A release MUST be cut from a tag, and the version MUST be derived from it — never
  edited by hand. An untagged or unclean state MUST NOT be releasable to the public repository.
- **FR-005**: The release workflow MUST be complete and proven against a local repository before
  the public namespace is claimed, so that claiming it is the only remaining step.
- **FR-006**: The published runtime MUST carry the platform's database schema inside it, so the
  test kit of any application creates the same schema this repository's does, from one copy.

**Versioning and compatibility**

- **FR-007**: The platform MUST have one version, shared by the libraries, the operator image, the
  control plane image and the CLI, cut from one tag per FR-004. Libraries and platform images are
  not versioned apart: the compatibility contract (FR-008) is a statement one release makes about
  the runtime versions it accepts, not a matrix maintained across two cadences.
- **FR-008**: The platform MUST state the range of application runtime versions it supports, as
  one visible value, and the rule for reading it MUST be documented.
- **FR-009**: An application image MUST make its runtime version discoverable to the platform
  without running application code.
- **FR-010**: A deployed application whose runtime version is outside the platform's supported
  range MUST be reported as such on `services get`, naming both versions, and MUST NOT report
  `Ready` while that holds. Within the range, nothing is said.
- **FR-011**: A change to the schema the operator applies MUST be additive within a supported
  range: a running application never loses a table or column it needs.

**The template**

- **FR-012**: A template MUST expand, from one command in an empty directory, into a project that
  builds, tests and runs with no file edited. Two commands reach it: `sbt new` with the template's
  name (the convention every Scala framework uses; needs nothing installed but sbt), and
  `nakka init`, which runs the same expansion and carries no template of its own — one template,
  two front doors. Both are proven; the `README` leads with `sbt new` and mentions `nakka init`.
- **FR-013**: The expanded project MUST contain: one event sourced entity with a command and a
  query, one HTTP endpoint that exercises them, one view with a listing, an entity test, an
  endpoint test, an integration test against the throwaway database, a local database
  definition, image packaging, a deployment descriptor, and a `README`.
- **FR-014**: Every name the developer supplied MUST be carried through: package, service name,
  image name, descriptor name. Nothing in the expansion may leave the stub's name where the
  developer's belongs.
- **FR-015**: The service name MUST be validated at expansion against the same rule the platform
  applies to service names.
- **FR-016**: The expanded project's local run MUST work against the local database definition it
  ships with, and the schema that database starts with MUST come from the published runtime — a
  build task in the expanded project writes the runtime's schema out of the artifact into a
  build directory, and the local database definition initialises from there. The steps are
  `sbt schema`, `docker compose up`, `sbt run`, in the `README`, in that order. The runtime itself
  never applies schema on start: in a deployment the operator owns the schema, and a mode in
  which an application mutates whatever database it connects to is not a mode to have.
- **FR-017**: The expanded project MUST NOT reference this repository's sources, paths or
  build; it depends on published artifacts only.
- **FR-018**: The expanded project's `README` MUST take a developer from expansion to a service
  answering by hostname on a nakka platform, and every command in it MUST be one this feature's
  proof executed.

**Proof**

- **FR-019**: This feature's proof MUST expand the template in an empty directory outside this
  repository, run its tests, build its image, deploy it through the CLI to the local cluster,
  expose it and call it by hostname with the certificate verified — and the artifacts it depended
  on MUST have come from a local publish, not from this build.
- **FR-020**: The platform's documentation MUST gain a "your first service" path that is this
  proof written for a reader, and the "Not implemented" list MUST say what versioning does not yet
  cover.

### Key Entities

- **Published artifact**: a library module at a version, with sources and documentation, in a
  repository a build resolves from. Six of them; the rest of the build produces none.
- **Version**: derived from a tag; shared per the answer to FR-007's question. Carried by every
  artifact and image.
- **Supported runtime range**: one value the platform states; the rule an application's runtime
  version is checked against.
- **Template**: the expandable project skeleton — its stub domain, its tests, its local database
  definition, its packaging, its descriptor, its `README`.
- **Expanded project**: what the developer gets; the unit this feature's proof exercises.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: From an empty directory with the libraries published locally, a developer reaches a
  passing test run, a locally served endpoint and a built image in under **15 minutes** following
  the expanded project's `README`, editing no file.
- **SC-002**: From that image, a developer reaches a service answering by hostname on the local
  cluster in under **10 more minutes** following the platform's `README`, editing only the
  project name in commands.
- **SC-003**: The expanded project contains zero references to this repository — verified by
  search, not by reading.
- **SC-004**: `publishLocal` from a clean checkout produces exactly the six application-facing
  artifacts and none of the platform-side ones — verified by listing the local repository.
- **SC-005**: A deliberately mismatched runtime version is reported on `services get` naming both
  versions within one reconcile; a matching one produces no mention of versions at all.
- **SC-006**: The release workflow runs to completion against a local repository from a tag, and
  refuses from an untagged state, before any public namespace exists.
- **SC-007**: Every command in both `README`s was executed in the proof; a command that was not is
  a defect.
- **SC-008**: Every existing suite passes unchanged; this repository's own samples still build
  from project references — the platform did not have to publish itself to test itself.

## Assumptions

- **Scala developers expect `sbt new`.** Whatever FR-012's answer, the expanded project is an sbt
  project, because the libraries are Scala and the test kit is sbt-shaped; a Gradle or Maven path
  is out of scope.
- **The public repository is the standard one for JVM artifacts**, and claiming its namespace is a
  one-time action the maintainer performs outside this feature. The feature stops at "everything
  but the claim is done and proven".
- **Local development of the platform still uses project references.** Publishing does not change
  how this repository's own samples build; it adds a second way out.
- **The schema the test kit applies comes from the runtime's own resources** (features 001–002's
  single-copy rule), which is what makes FR-006 a property to preserve rather than build.
- **The compatibility rule is coarse on purpose.** A supported *range* of runtime versions, stated
  by the platform and checked at deploy, is enough for the first release; a full matrix with
  per-feature capability negotiation is not.
- **The version-skew check reads the image, not the running application** (FR-009): the platform
  must be able to refuse before anything starts. How the version is carried on the image is a
  planning decision.
- **`nakka init` is sugar over the same template**, never a second copy of it: it locates the
  template the way `sbt new` does and runs the same expansion, so the two cannot drift. Until the
  CLI is distributed as a binary it adds convenience, not capability, which is why the `README`
  leads with `sbt new`.
- **Resolved with the user before finalising**: one version for everything (FR-007); `sbt new`
  and `nakka init` both, over one template (FR-012); the schema reaches the local database by a
  build task extracting it from the published runtime, never by the runtime applying it (FR-016).
