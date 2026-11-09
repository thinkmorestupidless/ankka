# Research: A notice when a newer release exists

## R1. Where the latest release is read from

**Decision**: `https://docs.ankka.cloud/latest.json`, written into the site by `docs build --release
<version>` in the release workflow's `docs` job, which already runs only on a `v*` tag and only after
`publish` (Maven Central). Shape in `contracts/latest-release.md`.

**Rationale**: the site is published by the same job, so the file and the pages always name the
same release (FR-002). GitHub Pages caches it ten minutes, which is well inside a day. No GitHub API
rate limit, no token, nothing new to host.

**Alternatives considered**: the GitHub releases API (`/releases/latest`): rate-limited to 60 an
hour per address unauthenticated, and it names a tag the moment it is created, before Maven Central
has the libraries. Maven Central's metadata: knows the libraries, not the release job's end; a
release whose images failed would still be "latest".

## R2. The command line's check

**Decision**: one object, `ReleaseCheck`, in `cli/`, holding the pure rules (whether to read, whether
to give the notice, version order, the notice's text) apart from the two effects (the read, the
remembered file). `Main.run` starts the read on a daemon thread before the action and, after it, asks
`ReleaseCheck` whether to give the notice from what is known by then. It never waits for the read
(FR-006, SC-002): a read that ends after the command is lost with the process, and the next command
reads again. `ankka version` alone reads synchronously, under the same timeout.

- Timeout: 2 s for connect and response (`java.net.http`, which the CLI already uses and the native
  image already carries).
- Address: `https://docs.ankka.cloud/latest.json`, overridable by `-Dankka.latest.url` (tests, a
  loopback fake) and `ANKKA_LATEST_URL` (a shell).
- Not read and no notice (FR-008): `-o json`/`--output json` among the arguments; `CI` set non-empty
  and not `false`; `ANKKA_NO_UPDATE_CHECK=1`; a development build; error output not a terminal; the
  `mcp` command (it has its own, R4); a plugin (it may own the terminal, and runs as its own program).
- Development build: `BuildInfo.version` not matching `^\d+\.\d+\.\d+$`, or `0.0.0`. dynver gives a
  commit past a tag `0.16.0+3-abcdef12-SNAPSHOT`.
- Version order: numeric major, minor, patch; anything else in the file is unreadable and gives no
  notice.

**Rationale**: the rules are the tested part; isolating them from the network and the file makes
every scenario of the feature a unit test in milliseconds, with two cases against a loopback server
for the real read and the timeout.

**Alternatives considered**: a blocking read at the end of every command with a short timeout: adds
up to the timeout to a command whenever the site is slow. A separate process forked to refresh the
file (npm's approach): a second program for the native image to ship and a stray process in CI logs.

## R3. Terminal detection on JDK 21

**Decision**: error output counts as a terminal when `System.console()` is not null, overridable by
`-Dankka.terminal=true|false` for tests.

**Rationale**: JDK 21 has no API for "is stderr a terminal"; `System.console()` is non-null when
standard input and output are both the terminal, which is the interactive case. A person who pipes
standard output (`ankka services list | grep x`) gets no notice, which is the script-shaped case the
clarification asked to keep quiet. JDK 22 changed `System.console()` to return a console when
redirected; when the family moves past 21, use `Console.isTerminal()`. This is written as a trap.

**Alternatives considered**: `isatty(2)` through GraalVM's C interface or JNA: native code in a CLI
that has none, for a difference the clarification does not need.

## R4. The MCP server

**Decision**: `ankka mcp` builds a `ReleaseStatus` (the CLI's release, the latest when known) and
hands it to the server and the tools. At start it takes the remembered result and, when a read is
due, starts one; `initialize` waits for that read, up to its 2 s timeout, when it is in flight
(clarified at analyze), so the instructions, fixed at `initialize`, carry the notice from the first
connection. A scheduled daemon thread reads again every 24 h. `whoami` and `installation_status` answer
`{"…": their current fields, "release": {...}}`, adding one field; `search_docs` and `read_doc` put a
one-line note before their text when a newer release is known. Shapes in `contracts/mcp-tools.md`.
The rules of R2 apply except JSON output and the terminal; CI and `ANKKA_NO_UPDATE_CHECK=1` still
silence it.

**Rationale**: a model reads tool results and the instructions; MCP's logging notifications are not
shown to the model by every client. `release.cli` is present even with the check off, so "the answer
is what it is with the check turned off" holds for an unreadable latest release (FR-014): only
`latest` is absent.

**Alternatives considered**: a tool of its own (`version`): the model would have to think of calling
it, which is exactly what did not happen.

## R5. The installation's release

**Decision**: `installation_status` already returns `Installation.platformVersion`; the `release`
field adds `installation` and `installationNewer: true` when that version is newer than the CLI's by
R2's order. Nothing changes in the control plane.

## R6. Pages that name a release

**Decision** (clarified at planning): pages keep real versions. A list of patterns, each naming a
place a page names an ankka release (`ankka-go@v{version}`, `ankkaVersion := "{version}"`,
`"runtime": "{version}"`, …), lives in one plain-text file the documentation's config names
(`extra.docs.release.patterns` in `mkdocs.yml` → `docs/release-patterns.txt`), so the shared docs
tool stays repository-neutral and the sbt build can read the same file without YAML.

- `docs build --release <version>` rewrites every pattern's version before MkDocs runs, writes
  `latest.json` (R1), and the release workflow passes the tag's version.
- `docs build` without `--release` and `docs sync` use the newest `v*` tag (`git describe --tags
  --abbrev=0 --match 'v*'`).
- `docs check` refuses any occurrence of a string equal to one of ankka's release tags that no
  pattern covers, unless an `others` line in the same file names its context (`spec-kit.git@v`):
  FR-016, and the scenario about another project's release.
- The CLI's bundling (`build.sbt`, the `ankka/docs/` resource generator) applies the same patterns
  with the CLI's release: its version on a tag, dynver's `previousStableVersion` otherwise.

**Rationale**: the docs tool's rule, written in `snippets.py`, that a source reads correctly with no
renderer. One patterns file keeps the Python and the Scala rewrite from disagreeing about *where*;
a CLI test holds the bundled pages to the CLI's release so they cannot disagree about *what*.

**Alternatives considered**: see the clarification: a placeholder token (breaks the rule), bumping
before each tag (a release step and a red main between bump and tag).

## R7. The remembered check

**Decision**: `release-check.json` in the directory of `Settings.path` (so `-Dankka.config` and
`ANKKA_CONFIG` move it, FR-005), written to a temporary file and moved into place, so two commands at
once leave it whole. A file that cannot be read or written is treated as absent; nothing fails.
Shape in `data-model.md`.

## R8. Testing

- `ReleaseCheckSuite` (cli, munit): every scenario of User Story 1 by its name, over the pure rules,
  with a fixed clock; two cases against a loopback `HttpServer` from the JDK (`com.sun.net.httpserver`)
  for the real read and the timeout. Each test owns a directory through `-Dankka.config`, as
  `PluginSuite` does.
- `McpServerSuite` gains the scenarios of User Story 2 (it already drives the server over in-memory
  streams with a fake control plane).
- `tools/docs/tests/` gains the release rewrite, the check and `latest.json`; the two
  documentation scenarios that read the built site are tests there.
- `DocsBundleSuite` (cli): the bundled pages name only the CLI's release.
- The publishing scenarios (a published release, an unpublished one) are the release workflow's:
  the `docs` job's `needs: publish` is the proof of the second; a test of the workflow file asserts it.

## Design finished

2026-11-09: analyze reported nothing above LOW (left at LOW: T012's timing assertion may be noisy on a loaded runner; the check cannot see a hand-written version no tag yet has).

## Found during implementation

- **A page that states history, or the reader's own project, is not a release form.** FR-015 read
  alone would rewrite "a platform from version 0.8.0 on runs no runtime from before version 0.8.0" and
  `docker tag orders:0.1.0` to the release being built, which would make them false. User Story 4 scopes
  it to "an install line, a dependency to add", so those are `release` forms and everything else equal to
  a tag is either an `other` prefix (another project's release; the reader's `orders:` and `cart-module:`
  images; `from version` and `before version` for the release a rule starts from) or reworded: the
  compatibility table in `upgrading.md` uses `1.4.x`, which no tag names, the Go SDK page's
  `ankka.Version()` example uses `1.2.3`, and the Rust tutorial's crate drops its `version = "0.1.0"`
  line (optional since Cargo 1.75). The patterns file's comments say so.
- **The newest tag is the highest version `git tag --merged HEAD` lists**, not `git describe`'s: among
  several tags on one commit `describe` does not pick the highest, which the fixture repository showed.
- **An `other` prefix is compared with trailing space stripped**, so an editor that trims
  `other from version ` changes nothing.
- **The MCP server looks every hour and reads when its last successful read is a day old**, rather than
  reading on a fixed 24-hour period: the same "at least daily" (FR-009), and a read that failed is tried
  again within the hour instead of a day later.
- **Terminal detection asks `Console.isTerminal()` by reflection where the JDK has it** (R3 named the
  move past 21): the CLI's zip runs on whatever JDK is on the path, and from 22 `System.console()` is
  non-null when redirected.
- **A fast command remembers its attempt, waits 100 ms, and the read remembers itself** (decided at
  review, superseding R2's "never waits"). The first build recorded only successful reads, so a command
  that ended before its read left nothing and every fast command read again. Now the attempt is written
  before the read starts and holds the next off for an hour unless it succeeded; a command waits up to
  100 ms for a read in flight (SC-002's budget); and the read's own thread remembers what it found.
  FR-005 and FR-006 are reworded to say so.
- **The native image's read path is proved only on a release build.** `native-smoke.sh` gives it an
  unreachable address on every build, and on a tag (where it is given the expected version) serves a
  newer `latest.json` on loopback and requires the notice and the remembered file.
