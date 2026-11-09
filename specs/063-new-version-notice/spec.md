# Feature Specification: A notice when a newer release exists

**Feature Branch**: `063-new-version-notice`

**Created**: 2026-11-09

**Status**: Draft

**Input**: User description: "Tell a person when a newer ankka release exists than the CLI they are
using, in the CLI and through `ankka mcp`. Wording, fixed by Trevor: \"ankka version Y is available
(you're currently using version X)\". (1) The source of the latest version: the release's docs job
publishes a small `latest.json` (version, release date) to docs.ankka.cloud beside the site, only
after the release reached Maven Central. (2) The CLI: at most one check a day, its result remembered
in a file under ~/.ankka/ (overridable), the network read in the background with a short timeout so
it never slows, blocks or fails a command; the notice is one line on stderr; nothing under CI, with
--json output, or when ANKKA_NO_UPDATE_CHECK=1. (3) `ankka mcp`: check at start and daily; tell the
model through the server instructions, the results of whoami and installation_status, and a short
note on search_docs and read_doc results. Also in scope: the docs pages that hardcode a release
version take the release's version at build instead, with a check that refuses a hardcoded one."

## Context

The command line carries the documentation of the release it was built from, on purpose: a model
talking to `ankka mcp` reads pages that describe the commands this command line has, offline, and
never a newer site describing commands it lacks. The cost is that nothing tells anyone the release is
old. A person on 0.13.0 read 0.13.0's pages through `ankka mcp` long after 0.16.0 was published,
and took the site to be cached: neither the command line nor the MCP server says a newer release
exists.

The site itself is current: it is published by each release and cached for ten minutes. But some of
its pages name a release in the text they show (`go get ...@v0.14.0`), written by hand, and nothing
moved them when 0.16.0 was published, so a reader of the current site is told to install an old
release.

This feature has the release say which release is the latest, in one small published file beside
the site, and has the command line and its MCP server read it: at most once a day, never in the way
of a command, and in exactly Trevor's words, "ankka version Y is available (you're currently using
version X)". The pages that name a release take it from the release that builds them.

## Clarifications

### Session 2026-11-09

- Q: Once the command line knows a newer release exists, how often does it give the notice? → A:
  At most once a day: the first command after the newer release is known gives it, and later
  commands that day give none.
- Q: Is the notice given when the error output is not a terminal (piped, redirected, a script)? →
  A: No. The command line neither reads the latest release nor gives the notice unless its error
  output is a terminal.
- Q: Does `ankka version` read the latest release at once, past the once-a-day limit? → A: Yes. It
  reads now, under the same short timeout, remembers what it read, and gives the notice when a newer
  release exists, whenever the notice was last given.
- Q: Do the proposed terms stand? → A: Yes: latest release, development build and notice, with
  upgrade notice, newest version and dev version refused (update and snapshot already mean other things in the features).
- Q (at planning): How does a page that names a release get the right one? → A: The page keeps a
  real release in its text, so it reads correctly with no renderer. Building the documentation, for
  the site and for the pages the command line carries, rewrites every occurrence the documentation
  recognises as naming a release of ankka to the release being built; `docs sync` rewrites them to
  the newest release tag; the check refuses an ankka release named anywhere else. No step is added
  to the release.
- Q (at analyze): On a fresh machine, does `ankka mcp` wait for its first read before answering the
  model's `initialize`? → A: Yes, up to the read's 2 s timeout, and only when nothing is remembered
  or the remembered read is a day old; the instructions then carry the notice from the first
  connection (SC-004).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A person at the command line is told a newer release exists (Priority: P1)

A person runs any command of an older command line. After the command's own output, they see one
line saying which release is available and which they are using. The command is no slower, its
output and its exit code are what they were, and they are told at most once a day. `ankka version`
reads the latest release at once and always says when a newer one exists.

**Why this priority**: the command line is what every person on ankka runs, and it is where an old
release is cheapest to notice and fix.

**Independent Test**: publish a latest release newer than the command line's, run any command, and
read the line on the error output; run it with the network unreachable and see the command succeed
as fast as before with no notice.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/command-line/new-release-notice.feature`: a command of a command line older than the latest release ends with the notice
- added `features/command-line/new-release-notice.feature`: a command line at the latest release gives no notice
- added `features/command-line/new-release-notice.feature`: a latest release that is not newer than the command line gives no notice
- added `features/command-line/new-release-notice.feature`: the latest release is read at most once a day
- added `features/command-line/new-release-notice.feature`: the notice is given at most once a day
- added `features/command-line/new-release-notice.feature`: the version command reads the latest release at once and gives the notice whenever a newer release exists
- added `features/command-line/new-release-notice.feature`: a latest release that cannot be read changes nothing about the command
- added `features/command-line/new-release-notice.feature`: a command ends when its own work ends, not when the read of the latest release does
- added `features/command-line/new-release-notice.feature`: the latest release is not read and no notice is given where a notice would be in the way
- added `features/command-line/new-release-notice.feature`: a development build of the command line never reads the latest release

---

### User Story 2 - A model driving `ankka mcp` is told the release it uses is old (Priority: P1)

A model talks to the command line's MCP server for hours. It reads, where it already looks, that a
newer release exists: in the server's instructions when it connects, in the answer of the tools that
say who and where it is, and on every page of the documentation it reads, which says the page is
this command line's release's and a newer one exists. When the installation it drives runs a newer
release than the command line, it is told that too.

**Why this priority**: the report that started this was a model reading old documentation through
the MCP server; a person never sees the server's error output.

**Independent Test**: start the MCP server of an older command line against a published newer
release, and read the instructions, a `whoami` answer, an `installation_status` answer and a page.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/command-line/new-release-notice.feature`: the command line's MCP server gives the notice in its instructions
- added `features/command-line/new-release-notice.feature`: every page of documentation the model reads says it is the command line's release's and a newer one exists
- added `features/command-line/new-release-notice.feature`: the pages the model finds by keywords say they are the command line's release's and a newer one exists
- added `features/command-line/new-release-notice.feature`: the model asking who it is is told the command line's release and the latest release
- added `features/command-line/new-release-notice.feature`: the model asking for the installation is told when the installation is at a newer release than the command line
- added `features/command-line/new-release-notice.feature`: an MCP server that runs for more than a day reads the latest release again
- added `features/command-line/new-release-notice.feature`: a latest release that cannot be read changes nothing the MCP server answers

---

### User Story 3 - The release says which release is the latest (Priority: P1)

Each release publishes, beside the documentation site, a small file naming itself and the date it
was released, and publishes it only once its libraries can be fetched. Everything that tells a
person or a model about a newer release reads that one file.

**Why this priority**: the two stories above have nothing to read without it.

**Independent Test**: tag a release, and read the file from the site once the release job ends.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/documentation/release-named.feature`: a published release is the latest release beside its documentation
- added `features/documentation/release-named.feature`: a release whose libraries were not published leaves the latest release where it was

---

### User Story 4 - The documentation names the release it was built from (Priority: P2)

A reader of the documentation sees, wherever a page names a release (an install line, a dependency
to add), the release that page was built from. A page that names a release by hand is refused by the
documentation's check, so the next release cannot leave it behind.

**Why this priority**: the site is current and still tells readers to install 0.14.0; this is the
same confusion as the old command line, from the other side.

**Independent Test**: build the documentation for a release and read the install pages; add a
hand-written release to a page and run the check.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/documentation/release-named.feature`: a page that names a release of ankka names the release the documentation was built for
- added `features/documentation/release-named.feature`: a page that names a release of ankka by hand fails the documentation's check
- added `features/documentation/release-named.feature`: a page that names a release of another project is not refused
- added `features/command-line/new-release-notice.feature`: the documentation of the command line says when the notice is given and how to turn the check off

---

### Edge Cases

- The latest release file is unreadable, malformed, or names a release older than the command line
  (a person on a build ahead of the latest): no notice, and nothing fails.
- The remembered result cannot be written (a read-only home): the command still succeeds; the check
  may run again next time.
- Two commands run at once: each may read; neither fails, and the remembered result is whole.
- A plugin's command: the plugin is its own program and may own the terminal; it gives no notice.
- A person on the newest release whose installation is older than the command line: nothing is said
  about the installation (the command line works against older installations within its
  compatibility rule).
- A page that names another project's release (a tool such as spec-kit pinned at its own version)
  is not a release of ankka and is not refused.

## Requirements *(mandatory)*

### Functional Requirements

**The latest release**

- **FR-001**: Each release MUST publish, at a fixed address on the documentation site, which release
  is the latest and the date it was released.
- **FR-002**: That file MUST be published only after the release's libraries have reached the
  package registry, and MUST be published by the same job that publishes the documentation, so the
  site and the file always name the same release.

**The notice**

- **FR-003**: The notice MUST read exactly "ankka version Y is available (you're currently using
  version X)", Y the latest release and X the command line's, both without a leading `v`.
- **FR-004**: A notice MUST be given only when the latest release is newer than the command line's
  by version order (major, minor, patch).

**The command line**

- **FR-005**: The command line MUST read the latest release at most once in 24 hours, and MUST
  remember what it read and when in a file beside its settings, whose place follows the settings'
  own override.
- **FR-006**: Reading the latest release MUST NOT delay a command beyond its own work, MUST NOT
  change its output or its exit code, and MUST give up after a short timeout.
- **FR-007**: The notice MUST be one line on the error output, after the command's own output, and
  MUST be given at most once in 24 hours, by the first command that knows of the newer release.
- **FR-007a**: `ankka version` MUST read the latest release at once, under the same timeout, past the
  once-a-day limit, remember what it read, and give the notice whenever a newer release exists.
- **FR-008**: The command line MUST neither read the latest release nor give the notice when output
  is JSON, when its error output is not a terminal, when the environment says it runs under CI, when
  `ANKKA_NO_UPDATE_CHECK=1`, or when it is a development build (a version that is not a release).
  A plugin's command and `ankka mcp` give no notice on the error output: a plugin may own the
  terminal, and the MCP server has its own (FR-009).

**The MCP server**

- **FR-009**: The MCP server MUST read the latest release when it starts and again at least every 24
  hours while it runs, under the same conditions as FR-008 except JSON output and the terminal,
  since a model, not a terminal, reads it.
- **FR-010**: When a newer release exists at start, the server's instructions MUST carry the notice.
- **FR-011**: The answers of `whoami` and `installation_status` MUST carry the command line's release
  and, when known, the latest release.
- **FR-012**: The answer of `installation_status` MUST say when the installation's release is newer
  than the command line's.
- **FR-013**: When a newer release exists, the answers of `search_docs` and `read_doc` MUST carry a
  short note that the documentation is the command line's release's, with the notice.
- **FR-014**: When the latest release cannot be read, every tool MUST answer as it does today.

**The documentation**

- **FR-015**: Every page that names a release of ankka MUST take it from the release the
  documentation is built for, both on the site and in the pages the command line carries.
- **FR-016**: The documentation's check MUST fail on a page that names a release of ankka by hand,
  naming the page and the line.
- **FR-017**: The command line's documentation MUST describe the notice, when it is given and how to
  turn it off.

### Key Entities

- **Latest release**: the release a published file says is newest: its version and the date it was
  released.
- **Remembered check**: what the command line last read of the latest release, when, and when it last
  gave the notice; it decides whether to read again and whether to give the notice.
- **Notice**: the one sentence that says a newer release is available and which release is in use.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A person on a release older than the latest sees the notice within a day of the newer
  release being published: on the first command they run once a read has seen it, which is the
  command that read it unless that command ended first, and then the next one.
- **SC-002**: A command with the latest release unreachable takes no more than 100 ms longer than the
  same command with the check turned off.
- **SC-003**: No command's output or exit code differs between a run with the check on and a run with
  it off, other than the one line of the notice.
- **SC-004**: A model driving an older command line's MCP server is told a newer release exists in
  the first answer it reads, and on every page of the documentation it reads.
- **SC-005**: After this feature, the published site names no release of ankka other than the one it
  was built from.

## Assumptions

- The documentation site stays where it is, and each release's documentation job keeps waiting for
  the libraries to be published; the latest release file rides on both.
- The notice does not say how to upgrade: a person installs the command line several ways (Homebrew,
  a downloaded executable, the GitHub Action), and the wording is fixed.
- A release candidate or other pre-release is never the latest release: only a release tag publishes
  the documentation.
- "Under CI" means the `CI` environment variable is set to a non-empty value other than `false`, the
  convention CI services follow.
- The remembered check is not a secret and holds nothing about the person.
