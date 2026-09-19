# Implementation Plan: A nakka Application Built Outside This Repository

**Branch**: `006-external-applications` | **Date**: 2026-09-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-external-applications/spec.md`

## Summary

Publish the six application-facing modules as versioned artifacts — tag-derived version via
sbt-dynver, signed release via sbt-ci-release to the Central Portal, `publish / skip` on every
platform-side module so the boundary is structural — and carry that one version in code with
sbt-buildinfo. Add a Giter8 template in this repository that `sbt new` (by git URL with
`--directory=template`, or `file://` locally) and a new `nakka init` (which shells out to the same
`sbt new`) expand into a working service: one entity, endpoint and view, three test levels, an
`sbt schema` task that extracts the runtime's DDL for the local Postgres, image packaging, and a
descriptor that declares the runtime version it was built against. The control plane checks that
declaration against its own version at projection — same major, minor within one below — and an
unsupported one is reported `Unavailable` naming both, never started. Prove it with a suite that
expands the template into a temp directory against a local `publishLocal` and runs its build, and
by hand through the whole chain on the kind cluster: test, run, image, apply, `Ready`, expose,
`curl` by hostname.

The user's three answers shaped it: one version for everything; both `sbt new` and `nakka init`
over one template; the schema extracted by a build task, never applied by the runtime.

## Technical Context

**Language/Version**: Scala 3.9.0, JDK 21, sbt 1.12.9 (unchanged)

**Primary Dependencies**: two sbt plugins — `sbt-ci-release` (bundling dynver, pgp, sonatype;
version to confirm, R1) and `sbt-buildinfo`. No new library dependency in any module. The
template's expanded project depends on the published artifacts only.

**Storage**: none new. The DDL is unchanged; FR-011 becomes a documented constraint on future
DDL changes.

**Testing**: munit. New: `CompatibilitySuite` (`controlplane-api`), cases in `DescriptorSuite`,
`ServiceProjectionSuite`, `MainSuite`/`SettingsSuite` (`cli`), and `TemplateSuite` (`cli`, gated
by `-Dnakka.template.tests` and `sbt` on `PATH`, ~4 minutes: publishLocal + an external sbt
build). No k3s suite changes.

**Target Platform**: unchanged. CI runs the non-cluster suites; the cluster suites and the
template proof stay local.

**Project Type**: build/release wiring, a template directory, small changes in `controlplane-api`,
`controlplane`, `core`, `cli`, and documentation.

**Performance Goals**: SC-001 (15 minutes to a built image from nothing), SC-002 (10 more to a
hostname answering).

**Constraints**: the runtime never applies schema; the CLI carries no template engine; the
samples keep project references; the platform-side modules are unpublishable by construction.

**Scale/Scope**: ~30 files including the template's ~15; one new suite.

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template; `CLAUDE.md` governs:

| Principle | How this plan keeps it |
|---|---|
| Single copy (DDL, manifests) | the template's `schema` task extracts from the jar — no second copy; `nakka init` runs the template, carries none |
| Module dependency direction | `cli` still depends on `controlPlaneApi` alone (it spawns `sbt`; no Giter8 library); `BuildInfo` lives in `core`, which everything already sees |
| Explicit registration | the template's `Main.scala` is the explicit `register` list, with the comment that says why |
| Effects/actions are data | untouched |
| Verify on a real cluster | the whole-chain proof on kind (Tier 5); the version negative by hand |
| Withhold verbs rather than promise restraint | `publish / skip := true` on every non-library — a platform jar cannot be published by accident |

No violations.

## Project Structure

### Documentation (this feature)

```text
specs/006-external-applications/
├── plan.md
├── research.md          # R1–R9
├── data-model.md        # artifacts, version, Compatibility, the template's shape, CLI
├── quickstart.md        # Tiers 1–5 and the reviewer's checklist
├── contracts/
│   ├── build-contract.md    # what an application's build.sbt sees and is promised
│   └── template.md          # expansion, output, README commands, nakka init behaviour
└── tasks.md
```

### Source Code (repository root)

```text
project/plugins.sbt                                  # + sbt-ci-release, sbt-buildinfo
build.sbt                                            # POM metadata; publish/skip; buildinfo on core; cli JavaAppPackaging; version line removed
.github/workflows/{ci,release}.yml                   # NEW
modules/core/…                                       # BuildInfo (generated)
controlplane-api/src/main/scala/nakka/controlplane/api/
├── Compatibility.scala                              # NEW: Version, supports, describe
└── descriptors.scala                                # ServiceSpec.runtime; problems() parses it
controlplane/src/main/scala/nakka/controlplane/deploy/ServiceProjection.scala   # the runtime check → Left
cli/src/main/scala/nakka/cli/
├── Main.scala                                       # version; init
└── Init.scala                                       # NEW: the sbt new invocation
cli/src/test/scala/nakka/cli/TemplateSuite.scala     # NEW
nakka.g8/                                            # NEW: src/main/g8/{default.properties,…}
README.md, CLAUDE.md
```

**Structure Decision**: the template is a directory of this repository so that it is versioned
with the libraries it names and tested by this build; `--directory=template` makes it reachable
by `sbt new` without a mirror. `Compatibility` sits in `controlplane-api` beside the descriptor
rules, because the CLI will want to print the platform's range one day and it must not need the
control plane to do so.

## Design notes that tasks must respect

1. **`ThisBuild / version` is deleted, not set.** dynver owns it. A build that sets it silently
   overrides the tag.
2. **Absent `runtime` is not an error.** Every descriptor written before this feature stays
   valid and silent; only a declared version is checked.
3. **The check is at projection, not at apply.** An apply records intent; the projector's "cannot
   project" path (feature 001) is how the platform says "I will not run this", and it already
   produces `Unavailable` with a detail. Do not add a lifecycle.
4. **The template's two version sites are one parameter.** `nakka_version` writes both
   `build.sbt`'s `nakkaVersion` and `service.json`'s `runtime`.
5. **`nakka init` shells out; it does not embed.** If `sbt` is absent the message says so.
   `--template` accepts any Giter8 reference, which is how the suite points it at `file://`.
6. **The template's endpoint declares `AllowAll` with the sentence from feature 005** — exposure
   changes who can reach it, not who is allowed to.
7. **`TemplateSuite` publishes locally first**, as a build-level task dependency, the same shape
   as `sampleImageForClusterTests`; it never reads this repository's `target/`.

## Phase 0 — research

Done: [research.md](./research.md). Five "verify first" items become the first tasks.

## Phase 1 — design

Done: [data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md).

## Post-design Constitution Check

Unchanged. One spec nuance is recorded: FR-009 ("discoverable without running application code")
is met by the descriptor's declaration, and research R3 states plainly that a declaration can lie
and what the release does about it (the runtime also serves its version; verification against the
running pod is future work named in the README).

## Complexity Tracking

None.
