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
read no spec or no scenario. **In CI it reports without failing for now** (`continue-on-error`, with a
warning): specs 025 onwards keep their scenarios in the spec. Two specs share the number 019 until a
renumbering; remove `continue-on-error` once every spec's scenarios live in `features/`. `just features` still fails. A feature file that one suite can run whole is run by `GherkinSuite`,
which takes a directory or one file; a scenario no suite can reach is a test named after it. The
shopping cart sample has features and a glossary of its own, under
`samples/shopping-cart/`, which `GherkinSuite` runs as tests; those describe the sample, and the
root ones the platform.

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
