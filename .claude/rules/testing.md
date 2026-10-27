---
paths:
  - "features/**"
  - "specs/**"
  - "GLOSSARY.md"
  - ".specify/**"
  - "**/src/test/**"
---

# Testing: living features, log capture and long suites

## Living features

**Every spec's acceptance scenarios live in living features**, not in the
spec. The [speckit-bdd](https://github.com/thinkmorestupidless/speckit-bdd) extension and preset are
installed under `.specify/`: `/speckit-specify` writes a spec whose acceptance scenarios *name*
scenarios, the `after_specify` hook runs `/speckit-bdd-features` to write them as Gherkin under
`features/` with every word they use in the root `GLOSSARY.md`, and the `before_clarify` hook runs
`/speckit-bdd-check`, which turns undefined words, refused synonyms, contradictions and untraced
requirements into clarification questions. `.specify/extensions/bdd/bdd-config.yml` sets no
`specs-from`, so the checker reads every spec. Specs 001-019 were written before the hook existed and
their scenarios were moved afterwards, written as the platform behaves *now*: where later work changed
or removed what a spec described, the feature says what happens today and the spec carries a
`*Superseded:*` note in place of the scenario (a plain line, not a list item, which the checker
reads as prose). A spec naming a scenario another spec added uses a bare reference, with no
`added`/`changed`/`removed` marker. Its
report says how many specs it read and how many it did not, so a setting that skipped everything
cannot read as a clean project. The checker runs through `uvx` from the release tag the config names,
so `uv` must be on `PATH`. CI's `features` job runs the same check through
`.github/features-check.sh` (`just features` locally), which reads that config and fails when it
read no spec or no scenario. It fails the pull request, as `just features` fails locally (it
reported without failing until 2026-10-24, while specs 025 onwards kept their scenarios inline). Two
specs share the number 019 until a renumbering. A feature file that one suite can run whole is run by `GherkinSuite`,
which takes a directory or one file; a scenario no suite can reach is a test named after it. The
shopping cart sample has features and a glossary of its own, under
`samples/shopping-cart/`, which `GherkinSuite` runs as tests; those describe the sample, and the
root ones the platform.

**spec-kit's renderer drops a skill's `model:` line** (2026-10-27, feature 061). `specify extension
add` writes each command as a skill with name, description, compatibility, metadata and
argument-hint only, so an extension installed that way runs on the session's model whatever its
command says. `design-build` is therefore installed by copying its directory and its two hand-written
skills (`.claude/skills/speckit-design`, `speckit-build`), as `review` is, and
`DesignAndBuildExtensionSuite` holds each command file's body equal to its skill's and the design's
model deeper than the build's. A copied extension is not in `.specify/extensions/.registry`, so
`specify extension list` omits it; `installed:` in `extensions.yml` is what the commands read. A
skill's `model:` lasts the turn: the design asks only through AskUserQuestion, which keeps the turn,
and refuses a session without it (a headless `claude -p`), where it would otherwise decide for the
person.

## Log capture

**Every suite that can see `testkit` mixes in `LogCapturing`** (testkit's own tests, the control
plane, the sidecar, the samples): its log is held in memory and printed only for a failing test, a
test whose `beforeEach` failed, or a suite whose `beforeAll` failed. `ANKKA_TEST_LOGS=all` (or
`-Dankka.test.logs=all`) turns it off. A new suite should mix it in too; `runtime`, `http` and
`agent` sit below `testkit` and cannot, and rely on their own `logback-test.xml` instead. Capture
is one per JVM, starts when munit constructs a suite (just before running it) and ends in a
fixture's `afterAll`, which munit runs *before* the suite's own `afterAll` — so teardown logs are
printed, deliberately: keeping capture on past the suite could swallow the next suite's log. A
suite that overrides `munitFixtures` must include `super.munitFixtures`, or capture never ends.
`LogCapturingSuite` itself does not mix it in, because its assertions need the root logger's real
appenders.

## Traps

- **A plain `ActorSystem` in a test binds Pekko's fixed default remoting port, 17355**, because the runtime's
  reference configuration makes the provider `cluster`. Any other test JVM on the machine — another
  worktree's, say — then fails the suite with `Address already in use`. A suite that needs no cluster
  builds its system with `pekko.actor.provider = local`.

- **Several installations in one JVM share `LocalInstallations`.** `AnkkaTestKit.installations` reaches
  a sibling's sharding directly, by name, so two groups in one JVM must not reuse a name. To test the
  loopback routes a separate process would use, `TestReplicationTransport.loopbackOnly(true)` (the
  transport's `inJvm = false`). Two kits in one JVM are not two installations on a network: the
  partition cases block and pause through the test transport, not a network.
- **A takeover of another kit's database must update the group's journals map**, or siblings keep
  reading the old installation's database for its name (`Installations.start(…, database = …)`).

- **An image tag every session builds is shared by every session on the machine.** `ankka-sidecar:latest`
  and `sample-shopping-cart:latest` are rebuilt by any worktree's `docker:publishLocal`. The Python and
  TypeScript test kits start `ankka-sidecar:latest` for an unreleased SDK, so point `ANKKA_SIDECAR_IMAGE`
  at this session's commit-tagged image when a run must test this branch's sidecar; and a cluster suite
  names the sample by `BuildInfo.imageTag` (the operator's, which cannot see `core`, is passed
  `-Dankka.sample.image` by the build), never `:latest` — four ZeroTrust cases once deployed another
  branch's sample and failed against routes it did not have.

- **sbt buffers a suite's report until the suite ends.** A long k3s Gherkin suite says nothing for many
  minutes, failures included; `sbt 'set controlPlane / Test / logBuffered := false' …` reports each
  scenario as it ends. A munit glob filter matches `.` literally: quote the scenario's words.
- **`http`'s tests cannot see `testkit`, and `testkit`'s cannot see `auth-oidc`'s test issuer.** The
  server's own socket suites are in `testkit`'s tests, the ones that need `TlsServing` in `http`'s, and
  `features/sockets/access.feature`, which needs the real verifier, runs from `sidecar`'s tests.
- **A socket suite's unread bound is a limit the suite can trip.** With the bound at 4 a case that sends
  ten frames at once passed alone and, under a loaded machine, was closed "unread" after three — correct
  behaviour, wrong test. The socket suites hold 16, and only the unread cases send more.

- **`StreamedBodySuite` proves nothing on a large heap** (2026-10-19, feature 055). It sends bodies of
  2 GiB through stream routes and the storage client, which only shows they were never held whole
  when the heap is smaller than they are; `http / Test / javaOptions` holds it at 512 MiB, and the
  suite's `beforeAll` fails on a heap over 1 GiB rather than passing on one that could hold them.
- **`GherkinSuite` reads `ranElsewhere` and `ranOutside` in its constructor**, before a subclass's
  fields are set. A map that names a `val` of the subclass sees it `null` there, and the suite fails
  at construction on every runner (`StorageClientClusterFeatures`, the first cluster run of feature
  055). Fields those maps read are `lazy val`s.
- **`-Dankka.features.only=<selectors>` holds a `GherkinSuite` to some of its scenarios**: a feature
  file by the end of its path, or a scenario by `<file>:<line>` as its test name shows it. The cluster
  workflow shards the longest suites with it (`.github/cluster-suites.py`'s `SHARDS`); a selection
  that names nothing fails the suite. It is forwarded to the forked JVM like every test switch.
