# Research: A nakka Application Built Outside This Repository

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Each finding says whether it was verified or is an assumption a named task settles. The pattern
this feature breaks is different from 002–005's: nothing here is a Kubernetes API that might lie,
but the *first external build* is the first time the platform's assumptions about itself
("everything is one build") stop being true, and every finding below is about which of those
assumptions survive.

## R1 — Publishing: sbt-ci-release, Central Portal, and `publish / skip` as the module boundary

**Decision**: `sbt-ci-release` (which bundles `sbt-dynver`, `sbt-pgp`, `sbt-sonatype`), targeting
the Sonatype **Central Portal** — the OSSRH endpoint it replaced was sunset in mid-2025.
`ThisBuild / version` is removed; `sbt-dynver` derives the version from the tag (`v0.2.0` →
`0.2.0`; an untagged commit → `0.2.0+3-abc1234-SNAPSHOT`). The six application-facing modules
publish; every other module and the root carry `publish / skip := true`, so a platform-side jar
cannot reach a repository by accident — the boundary is in the build, not in documentation.

**Rationale**: this is the way every Scala library on Central is released; the tag-derived
version is what makes FR-004 ("never edited by hand", "untagged is not releasable") mechanical.
Sonatype's namespace for `com.thinkmorestupidless` must be claimed by the maintainer through the
portal (DNS TXT record or the GitHub-based claim) — outside this feature, as the spec says.

**Alternatives considered**: `sbt-release` (interactive, edits version files — exactly what FR-004
forbids); GitHub Packages as the public repository (needs a token to *consume*; not a public
repository in the sense a `build.sbt` elsewhere expects); no plugin, hand-written `publishTo`
(reimplements signing and staging badly).

**Verified**: sbt-ci-release exists and targets the Central Portal from 1.11.0 on; sbt is 1.12.9
here (the portal path needs ≥ 1.11).

**Not verified, settled by T00x**: the current plugin version (the release page fetched showed
1.12.x — confirm before pinning); that `publishLocal` of exactly six artifacts works from a clean
checkout with the skips in place; that `publishSigned` to a **local file repository** with a
throwaway GPG key runs end to end — the spec's "proven against a local repository before the
namespace is claimed" (FR-005, SC-006).

## R2 — One version for everything, carried in code by sbt-buildinfo

**Decision**: one tag versions the libraries, the two images and the CLI (the user's choice, FR-007).
`sbt-buildinfo` on `core` generates `nakka.core.BuildInfo.version`, so the platform's own version
is a value every module can read: the CLI prints it (`nakka version`), the control plane compares
against it, the runtime logs it at start and serves it on its management endpoint.

**Rationale**: the compatibility rule (R3) needs the platform to *know* its version at runtime;
`BuildInfo` is the standard way and costs one plugin. Images are tagged with the same dynver
version by sbt-native-packager already.

**Alternative considered**: reading `getClass.getPackage.getImplementationVersion` from the jar
manifest — works for jars, not for `sbt run` or tests, where it is null.

## R3 — The compatibility contract: a declared runtime version, checked at projection

**Decision**: the descriptor gains an optional `runtime` field — the nakka version the image was
built against (`"runtime": "0.2.0"`). The template writes it at expansion, the same value as its
build's `nakkaVersion`. The **control plane** checks it when it *projects* the service: a
declared runtime outside the platform's supported range makes `ServiceProjection.project` return
a problem, which already surfaces as `lifecycle: Unavailable` with the problem as `detail` and
never reaches the operator — no pod starts, `services get` names both versions, nothing is
`Ready` (FR-010). Absent, the field is not checked: every descriptor written before this feature
is silent and stays so.

**The rule** (FR-008): supported means *same major, and minor within one below the platform's*.
Platform `0.3.x` supports runtimes `0.2.x` and `0.3.x`. Stated in one place (`Compatibility.
supports(platform, runtime)` in `controlplane-api`, next to the descriptor rules) and in the
README. Coarse on purpose: a matrix is the second release's problem.

**Rationale**: the spec's FR-009 asks for the version "without running application code", which
rules out asking the pod. Reading it from the image needs a registry client — and the local
cluster has no registry (`kind load`). The descriptor is what the platform already validates, and
a field the developer's build writes is the one place both sides agree on. The projection path is
the existing "cannot project" mechanism (feature 001), so no new lifecycle or status shape.

**The honesty gap, stated**: a descriptor can lie. So the runtime *also* serves its version on the
management endpoint (`/nakka/version`), and the operator's pod-level status could compare it in a
later feature. For this release, the README says the declared version is what is checked.

**Additive schema** (FR-011): the DDL is versioned files (`10-journal`, `20-projection`,
`30-timers`); the rule that a supported range never removes a table is documented in `CLAUDE.md`
as a constraint on future DDL changes, and the operator's schema init is unchanged — it already
applies `CREATE ... IF NOT EXISTS`.

## R4 — The template: Giter8 in this repository, `sbt new` by git URL, `nakka init` shelling out

**Decision**: the template lives at `nakka.g8/` in this repository, as a Giter8 template
(`src/main/g8/...`, `default.properties`). It is expanded three ways, all the same expansion:

- `sbt new file:///path/to/nakka/nakka.g8 --name=orders` — local development and the suites;
- `sbt new thinkmorestupidless/nakka.g8 --name=orders` — the public form, a repository the release
  workflow fills by subtree push (see the correction below);
- `nakka init orders` — runs the second command via `ProcessBuilder` (needs `sbt` on `PATH`; a
  clear message if not), with `--template <ref>` to point at a file URL. The CLI carries no
  template engine and no copy of the template (the user's choice, FR-012, on the condition the
  spec set).

**Rationale**: a template inside the repository is versioned with the libraries it names — the
`nakka_version` default in `default.properties` is the tag's version, kept in step by the release
workflow — and is tested by this repository's own suites. A separate `nakka.g8` mirror is a later
nicety for the shorter `sbt new thinkmorestupidless/nakka.g8` form.

**Verified, and corrected (T007)**: `sbt new`'s Giter8 resolver (sbt-giter8-resolver 0.18.0, read
from its bytecode) matches only `^owner/repo.g8$`, `^file://…\.g8/?$` and `….g8.git` — a plain git
URL is rejected before `--directory` could reach Giter8. So the template directory is **`nakka.g8/`**
(the `.g8` suffix is what the resolver keys on, for `file://` too), the local form is
`sbt new file:///path/to/nakka/nakka.g8 --name=orders`, and the public form is the conventional
`sbt new thinkmorestupidless/nakka.g8` — a repository the release workflow populates with a
subtree push of `nakka.g8/` on every tag. `default.properties` lives in `src/main/g8/`, not the
template root (Giter8 ignores it there — "Ignoring unrecognized parameter: name"). Name formatting
(T008): `$name;format="norm"$` turns `My Orders` into `my-orders`, keeps `orders-2`, lowercases
`Orders` — all valid service names; the package uses `format="word,lower"` (`myorders`). Edge
cases `norm` cannot fix (a leading hyphen, over 63 characters) are caught by `nakka init`'s
validation and the generated `build.sbt`'s `require`.

## R5 — The template's content: the shopping cart, generalised, with the rules baked in

**Decision**: one event sourced entity (`Item` — a name, a count; `add-item`, `get-item`), one
endpoint (`POST /items/{id}`, `GET /items/{id}`, `GET /items`), one view (`ItemRows`, the
listing); an entity test with `EventSourcedTestKit`, an endpoint test and an integration test
with `NakkaTestKit` (including `restartService()` for durability); `Main.scala` with the explicit
`register` list; `logback.xml`; `application.conf` empty but present (with a comment saying
what goes there); `build.sbt` with `JavaAppPackaging + DockerPlugin`, `dockerBaseImage
eclipse-temurin:21-jre`, `dockerUpdateLatest`, the `schema` task; `docker-compose.yml` mounting
`target/ddl`; `service.json` with `name`, `image`, `runtime`; a `README`.

**Rationale**: the shopping cart is the one service proven in-cluster since feature 003; the
template is that shape with the domain made trivial and the platform's conventions kept —
`NAKKA_HTTP_PORT` honoured by default, `port` 9000, `http: true`, the endpoint's `acl` declared.
Everything a developer would otherwise discover by reading this repository is in the expansion.

## R6 — The first run: an `sbt schema` task that extracts the DDL from the runtime jar

**Decision**: the template's `build.sbt` defines `schema`, which locates the `nakka-runtime` jar
on the runtime classpath, copies `nakka/ddl/*.sql` out of it into `target/ddl`, and the template's
`docker-compose.yml` mounts `./target/ddl:/docker-entrypoint-initdb.d:ro`. Order in the README:
`sbt schema`, `docker compose up -d`, `sbt run`. The runtime never applies schema (the user's
choice, FR-016).

**Rationale**: still one copy of the DDL — the jar's — which is the rule since feature 001; no new
runtime capability; and the only cost is one command the README states first.

**Verified (T009)**: the task finds `nakka-runtime_3-<version>.jar` on `Compile / dependencyClasspath`,
`IO.unzip`s the `nakka/ddl/*.sql` entries and flattens them into `target/ddl`; Postgres 17's init
directory ran them and produced all seven tables. 7s cold.

## R7 — Testing the template: expand it and run its own build, gated like the k3s suites

**Decision**: a `TemplateSuite` in `cli` (where `nakka init` lives): `publishLocal` the six
artifacts (a build-level task dependency, like `sampleImageForClusterTests`), expand the template
with `nakka init --template file://…` into a temp directory, and run `sbt test` and
`sbt Docker/publishLocal` in it as subprocesses. Gated by `-Dnakka.template.tests=off` and by
`sbt` being on `PATH`. Also asserts SC-003 (no reference to this repository's paths in the
expansion) and FR-014 (the stub name appears nowhere the developer's name belongs).

**Rationale**: the only test that proves "a build outside this one resolves the artifacts" is a
build outside this one. It is slow (a cold sbt start plus a test kit Postgres, ~3 minutes) and it
is the feature.

**Verified (T010)**: an external build resolves `~/.ivy2/local` with no configuration; a cold
`sbt test` with one `NakkaTestKit` case took 10s (cached dependencies). `TemplateSuite` budgets ten
minutes for a clean cache.

## R8 — The CLI as a program: `nakka version`, `nakka init`, and staging as the distribution

**Decision**: `sbt cli/stage` (already available through `JavaAppPackaging` — to add to `cli`) is
how the CLI is run as a program; `nakka version` prints `BuildInfo.version`; `nakka init` per R4.
Publishing a native binary or a Homebrew formula is out of scope — the README says `sbt cli/stage`
and `PATH`.

## R9 — Nothing about the platform's own build changes for its own tests

**Decision**: the samples keep `.dependsOn(sdk, runtime, …)`; no suite in this repository
resolves nakka from a repository except `TemplateSuite`, which is the point. `publish / skip`
on the samples stays.

## Carried to tasks as "verify first"

1. sbt-ci-release's current version and its Central Portal settings (R1).
2. `publishLocal` yields exactly six artifacts; `publishSigned` to a local file repo with a
   throwaway key completes (R1).
3. `sbt new … --directory=template` forwards to Giter8; Giter8 name formatting vs the service
   name rule (R4).
4. The `schema` task's jar lookup and Postgres accepting the extracted files (R6).
5. The cost of `TemplateSuite` and its cache behaviour (R7).
