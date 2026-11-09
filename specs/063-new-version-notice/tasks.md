# Tasks: A notice when a newer release exists

**Input**: Design documents from `specs/063-new-version-notice/`

**Prerequisites**: plan.md, spec.md, research.md (R1 to R8), data-model.md, contracts/
(latest-release.md, cli-notice.md, mcp-tools.md, docs-tool.md), quickstart.md

**Tests**: every acceptance scenario is a living feature (`features/command-line/new-release-notice.feature`,
`features/documentation/release-named.feature`) and ends as a test named after it, written before or
beside the code it checks. Ask of each: could it pass while the thing is false? Break the behaviour
once and watch it go red.

**Organization**: by user story. US3 (the latest release file) is listed first in the build order
because US1 and US2 read what it publishes, but each story's tests stand alone: US1 and US2 read a
loopback fake.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1 to US4 from spec.md
- Paths are from the repository root

## Phase 1: Setup

- [X] T001 Create `docs/release-patterns.txt` in the shape of data-model.md "The patterns file", with `release` lines for every form a page uses today (`ankka-go@v{version}` and any others `grep -rnE '\b0\.[0-9]+\.[0-9]+\b' docs/` shows naming ankka) and `other spec-kit.git@v`; add `extra.docs.release: {patterns: docs/release-patterns.txt, tags: "v*"}` to `mkdocs.yml` under the existing `extra.docs` block with a comment saying what it is for

## Phase 2: Foundational

- [X] T002 Create `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/ReleaseCheck.scala` with the values of data-model.md: `Release` (parse `^v?\d+\.\d+\.\d+$`, ordering, `0.0.0` not a release, `render` without `v`), `LatestRelease(version, released)`, `RememberedCheck(checkedAt, latest, noticeAt)` with explicit jsoniter codecs in their companions (`Codecs`), and `ReleaseCheck.notice(latest, current): String` returning exactly "ankka version Y is available (you're currently using version X)" (contracts/cli-notice.md); pure, no I/O
- [X] T003 In `ReleaseCheck.scala` add the effects: `read(url, timeout = 2.seconds): Option[LatestRelease]` over `java.net.http.HttpClient` (connect and request timeouts, any failure or unparsable body is `None`), `url` from `-Dankka.latest.url`, else `ANKKA_LATEST_URL`, else `https://docs.ankka.cloud/latest.json`; `remembered(): Option[RememberedCheck]` and `remember(check)` on `release-check.json` in the directory of `Settings.path`, written to a temp file in the same directory and moved with `ATOMIC_MOVE`, every read or write failure swallowed (R7)
- [X] T004 In `ReleaseCheck.scala` add the rules as pure functions of (arguments, environment map, current version, remembered check, now, terminal flag): `enabled` (false for `ANKKA_NO_UPDATE_CHECK=1`, `CI` non-empty and not `false`, a development build per R2, `-o json`/`-ojson`/`--output json`/`--output=json` in the arguments, the first argument `mcp`, error output not a terminal), `shouldRead` (no `checkedAt` or 24 h old), `shouldNotify` (latest newer and `noticeAt` absent or 24 h old); `terminal` from `-Dankka.terminal` else `System.console() != null` (R3), with a comment naming the JDK 22 change
- [X] T005 Add an `env: Map[String, String] = sys.env` parameter to `Main.run` in `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/Main.scala` and pass it to `parse` (which already reads `sys.env` for decline), so a test can set `CI` and `ANKKA_NO_UPDATE_CHECK` without touching the JVM's environment; every existing caller keeps compiling

**Checkpoint**: `sbt cli/compile` is warning-free.

## Phase 3: User Story 3 - The release says which release is the latest (Priority: P1)

**Goal**: each release publishes `latest.json` beside its site, only after its libraries are published.

**Independent Test**: `docs build --release 0.17.0` writes `target/docs-site/latest.json`; the release workflow passes the tag and the `docs` job needs `publish`.

- [ ] T006 [P] [US3] Write `tools/docs/tests/test_release.py` cases "a published release is the latest release beside its documentation" (build with `--release 0.17.0` on a fixture project writes `latest.json` with that version and today's UTC date, and no `latest.json` without `--release`) and "a release whose libraries were not published leaves the latest release where it was" (parse `.github/workflows/release.yml` and assert the job that runs `docs build --release` has `needs: publish` and only runs on `refs/tags/v`)
- [ ] T007 [US3] Create `tools/docs/src/ankka_docs/release.py` with `write_latest(site, version)` and wire `--release <version>` into `run_build` in `tools/docs/src/ankka_docs/cli.py` (argument parsing in `main`, the usage text in the module docstring), writing `latest.json` after `render.write` per contracts/latest-release.md
- [ ] T008 [US3] In `.github/workflows/release.yml`'s `docs` job change `uv run --project tools/docs docs build` to pass `--release "${GITHUB_REF_NAME#v}"`, keeping `needs: publish` and the comment, extended to say the job also publishes `latest.json`, which the command line reads

## Phase 4: User Story 1 - A person at the command line is told a newer release exists (Priority: P1)

**Goal**: one fixed line on stderr at most once a day, never in a command's way; `ankka version` reads at once.

**Independent Test**: `sbt 'cli/testOnly *ReleaseCheckSuite'`.

- [ ] T009 [P] [US1] Write `cli/src/test/scala/com/thinkmorestupidless/ankka/cli/ReleaseCheckSuite.scala`: each test owns a directory through `-Dankka.config` (clean up before the property goes, as `PluginSuite` does), sets `-Dankka.terminal=true` unless the case says otherwise, a fixed release version injected for the CLI's own (a test-only parameter of the hook, since `BuildInfo.version` is a development build here), and a loopback `com.sun.net.httpserver.HttpServer` on port 0 serving `latest.json` through `-Dankka.latest.url`; one test per scenario of User Story 1 by its exact name in `features/command-line/new-release-notice.feature`, the outlines one test per row, a scenario whose Given is a known latest release writing it as the remembered check (read less
than a day ago, no `noticeAt`) so no test races the background read, asserting the exact line, the
unchanged stdout and exit code against a run with `ANKKA_NO_UPDATE_CHECK=1`, and for "a command ends when its own work ends" a server that sleeps 5 s and a command that returns in under 1 s; plus the edge cases of spec.md (unreadable, malformed, older latest, read-only directory, two runs at once) named for them
- [ ] T010 [US1] In `Main.run`, for a built-in command other than `mcp` and when `ReleaseCheck.enabled`, start the read on a daemon thread before the action when `shouldRead`, and after the action (any exit code but the usage error 2) give the notice on `err` when `shouldNotify` from the remembered check, or from the read if it has finished, never waiting for it; record `noticeAt` and a successful read through `remember`
- [ ] T011 [US1] Make `versionCommand` in `Main.scala` read synchronously under the same timeout past the once-a-day limit, give the notice whenever the latest release is newer (FR-007a), and record both; its stdout stays the version alone; the scenario "the version command reads the latest release at once and gives the notice whenever a newer release exists" passes in `ReleaseCheckSuite`
- [ ] T012 [US1] Measure SC-002 in `ReleaseCheckSuite`: `ankka version` against an address that refuses connections, 20 runs with the check on and off, assert the median difference is under 100 ms, and say in the test why the median

## Phase 5: User Story 2 - A model driving `ankka mcp` is told the release it uses is old (Priority: P1)

**Goal**: the instructions, `whoami`, `installation_status`, `search_docs` and `read_doc` carry what contracts/mcp-tools.md says.

**Independent Test**: `sbt 'cli/testOnly *McpServerSuite'`.

- [ ] T013 [P] [US2] Extend `cli/src/test/scala/com/thinkmorestupidless/ankka/cli/McpServerSuite.scala` with one test per scenario of User Story 2 by its exact name, driving the server with a `ReleaseStatus` built over the loopback fake of T009 and a fixed CLI release; "the command line's MCP server gives the notice in its instructions" starts with no remembered
check and a fake that answers after 300 ms, so it fails unless `initialize` waits; "an MCP server that
runs for more than a day reads the latest release again" uses an injected clock and refresh trigger rather than waiting; "a latest release that cannot be read changes nothing the MCP server answers" compares the whole `whoami` answer with the check-off answer
- [ ] T014 [US2] Create `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/mcp/ReleaseStatus.scala`: the CLI's release, the latest when known (`AtomicReference`), `available`, `notice`, built from the remembered check at start, a read started at once and every 24 h on a daemon scheduled executor (the clock and the scheduler injectable for the suite), under `ReleaseCheck.enabled` minus the JSON and terminal rules (FR-009); `awaitStart()` waits for
the start read, up to its timeout, when one is in flight, and each successful read is remembered
through `ReleaseCheck.remember`
- [ ] T015 [US2] Thread `ReleaseStatus` from `mcpCommand` in `Main.scala` into `McpServer` (`cli/src/main/scala/com/thinkmorestupidless/ankka/cli/mcp/McpServer.scala`), whose `initialize` calls `ReleaseStatus.awaitStart()` and then appends the paragraph of contracts/mcp-tools.md to `instructions` when `available`
- [ ] T016 [US2] In `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/mcp/AnkkaTools.scala` add the `release` field to `whoami` and `installation_status` answers (with `installation` and `installationNewer` from `platformVersion`), and the one-line note before `search_docs` and `read_doc` text when `available`, per contracts/mcp-tools.md; update the tools' descriptions to mention `release`
- [ ] T017 [US2] Add `ReleaseStatus` and the new answer fields to the native image's metadata in `cli/src/main/resources/META-INF/native-image/com.thinkmorestupidless/ankka-cli/reachability-metadata.json` if jsoniter's codecs need it (they are compile-time, so likely nothing), and run `cli/native-smoke.sh` with `ANKKA_LATEST_URL` pointed at a refusing address to prove the native `ankka version` still exits 0

## Phase 6: User Story 4 - The documentation names the release it was built from (Priority: P2)

**Goal**: pages keep real versions, rewritten to the release being built; a version written by hand is refused.

**Independent Test**: `uv run --project tools/docs pytest -q tools/docs/tests` and `sbt 'cli/testOnly *DocsBundleSuite'`.

- [ ] T018 [P] [US4] Add to `tools/docs/tests/test_release.py` the cases "a page that names a release of ankka names the release the documentation was built for" (a fixture page with `ankka-go@v0.14.0`, built with `--release 0.16.0`, the built HTML and `llms-full.txt` name 0.16.0, the source still names 0.14.0), "a page that names a release of ankka by hand fails the documentation's check" (an occurrence of a tag's version outside every pattern: the problem names page and line), "a page that names a release of another project is not refused" (`spec-kit.git@v0.14.1` with an `other` line), and that a repository without `extra.docs.release` checks and builds exactly as before
- [ ] T019 [US4] In `tools/docs/src/ankka_docs/release.py` add reading the patterns file named by `extra.docs.release.patterns` (through `pages.Project`'s config), `rewrite(text, version)`, `check(tree)` (versions equal to a `tags` git tag, `git tag --list`, outside every `release` and `other` form) and `newest_tag()`; register `check` in `check.run` in `tools/docs/src/ankka_docs/check.py`, the rewrite of page sources in `run_sync`, and in `render.write` for `llms.txt`, `llms-full.txt`, raw pages and `docs-index.json`
- [ ] T020 [US4] Create `tools/docs/src/ankka_docs/hooks.py` with `on_page_markdown` applying `release.rewrite` with the release `docs build` puts in the environment (`ANKKA_DOCS_RELEASE`, set by `run_build` from `--release` or `newest_tag()`), and list it under `hooks:` in `mkdocs.yml`; the hook does nothing when the variable is unset (`docs serve`)
- [ ] T021 [US4] Run `docs check` and fix every page it refuses: `docs/get-started/install.md`, `docs/get-started/first-service-go.md`, `docs/reference/go-sdk.md` (including the `ankka.Version()` prose, reworded to a form a pattern covers or to need no release), and any other it names, then `docs sync` so every pattern names the newest tag
- [ ] T022 [P] [US4] Write `cli/src/test/scala/com/thinkmorestupidless/ankka/cli/DocsBundleSuite.scala`: every occurrence of every `release` pattern in the pages the CLI carries (`Docs.read` over `Docs.pages`) names the CLI's documentation release (exposed by the build as a `BuildInfo`-like resource `ankka/docs/release.txt`)
- [ ] T023 [US4] In `build.sbt`'s `ankka/docs/` resource generator read `docs/release-patterns.txt`, rewrite each copied page's `release` occurrences to the CLI's release (`version.value` when `isSnapshot.value` is false, else `previousStableVersion.value.getOrElse("0.0.0")`) and write `ankka/docs/release.txt`; keep it a pure function of its inputs so the generator stays cacheable

- [ ] T027 [US4] Make `release.newest_tag()` and `release.check` fail with a problem naming the cause when no tag matches `tags` (a shallow clone, which would otherwise check nothing and pass), with a test in `tools/docs/tests/test_release.py`; give the `docs` job in `.github/workflows/ci.yml` and the release workflow's `docs` job `fetch-depth: 0` on `actions/checkout`, so both see the tags

## Phase 7: Polish & cross-cutting

- [ ] T024 [P] Document the notice in `docs/reference/cli.md` (hand-written part, not the generated reference: when it is given, when not, `ANKKA_NO_UPDATE_CHECK`, `ANKKA_LATEST_URL`, the remembered file) and in the MCP page that describes `ankka mcp`; the scenario "the documentation of the command line says when the notice is given and how to turn the check off" is a test in `tools/docs/tests/test_release.py` reading the built page; `just docs-reference` if the CLI's help changed
- [ ] T025 [P] Add the traps to `.claude/rules/build-and-release.md` (the patterns file is read by both the docs tool and `build.sbt`; `docs build --release` in the release job) and `.claude/rules/docs.md` (a version of ankka in a page goes in a form `docs/release-patterns.txt` lists), and R3's JDK 22 `System.console()` change to `.claude/rules/control-plane.md`'s CLI traps
- [ ] T026 Run `just features`, `just docs`, `sbt cli/test`, `uv run --project tools/docs pytest -q tools/docs/tests` and quickstart.md sections 2 and 4 by hand; record any difference from the contracts in the PR

## Dependencies & Execution Order

- Phase 1 → Phase 2 → stories. T005 before T010; T002 to T004 before T009 to T016.
- US3 (T006 to T008) and US4 (T018 to T023, T027) share `release.py`: T007 before T019, T019 before T027.
- US1 and US2 are independent of each other after Phase 2; US2's T014 reuses T003's read.
- Polish after the stories it documents.

## Parallel Examples

- After Phase 2: T006, T009, T013 and T018 (four test files) together.
- T022 beside T019 and T020.

## Implementation Strategy

MVP is US3 + US1: the file exists and the command line says so. US2 then carries it to the model,
which is what the report that started this needed; US4 stops the site naming an old release.
