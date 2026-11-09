# Implementation Plan: A notice when a newer release exists

**Branch**: `063-new-version-notice` | **Date**: 2026-11-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/063-new-version-notice/spec.md`

## Summary

Each release writes `latest.json` beside its documentation. The command line reads it at most once a
day on a background thread that never holds a command up, remembers it beside its settings, and
gives one fixed line on standard error at most once a day, with `ankka version` reading at once.
`ankka mcp` reads it at start and daily and says so in its instructions, in `whoami` and
`installation_status`, and before every documentation answer. Pages that name an ankka release keep
a real one, which the docs tool rewrites to the release being built (site and bundled pages) and
refuses anywhere it does not recognise.

## Technical Context

**Language/Version**: Scala 3.9.0 on JDK 21 (the CLI, also a GraalVM native image); Python 3.12 (the
docs tool, `tools/docs`).

**Primary Dependencies**: none new. `java.net.http` (already the CLI's client), decline, jsoniter
(`Codecs`); MkDocs (already the tool's), with a hook file.

**Storage**: one JSON file, `release-check.json`, beside the CLI's settings.

**Testing**: munit in `cli` (`ReleaseCheckSuite`, `McpServerSuite`, `DocsBundleSuite`), pytest in
`tools/docs/tests`, the features checker.

**Target Platform**: macOS and Linux, JVM and native image; GitHub Actions for the release job.

**Project Type**: CLI plus a documentation tool and a release workflow step.

**Performance Goals**: a command with the latest release unreachable costs ≤ 100 ms more (SC-002); a
command waits for the read no more than 100 ms beyond its own work (FR-006).

**Constraints**: the native image (no reflection-only code paths; a daemon thread is fine); no change
to any command's output or exit code; the docs tool stays repository-neutral (other repos take it from
`main`); a build never writes a tracked file.

**Scale/Scope**: one read a day per person; ~10 pages naming a release today.

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template, so the gates are the repository's own
rules (`CLAUDE.md`, `.claude/rules/`):

| Rule | How this plan holds it |
|---|---|
| `$HOME` reads overridable by a system property | the remembered file follows `Settings.path` (`-Dankka.config`); the address takes `-Dankka.latest.url` |
| `Main.main` a one-line wrapper; `run` returns the code | the notice is given inside `run`, which gains an `env` parameter defaulting to `sys.env` |
| Forked tests do not inherit `-D` | every switch is a property the suite sets in its own JVM, not an sbt `-D` |
| CI maps every tracked file to a job (`ci-coverage.py`) | `docs/release-patterns.txt` falls under the `docs` filter; `tools/docs/` changes run `docs` |
| Docs sources read without a renderer | real versions stay in the text (R6) |
| Every acceptance scenario ends as a test that fails without the feature | R8 names each scenario's test |

Re-checked after design: no violation.

## Project Structure

### Documentation (this feature)

```text
specs/063-new-version-notice/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── latest-release.md
│   ├── cli-notice.md
│   ├── mcp-tools.md
│   └── docs-tool.md
└── tasks.md
```

### Source Code (repository root)

```text
cli/src/main/scala/com/thinkmorestupidless/ankka/cli/
├── ReleaseCheck.scala          # new: Release, LatestRelease, RememberedCheck, the rules, read, file
├── Main.run                    # start the read, give the notice; `version` reads at once
└── mcp/
    ├── McpServer.scala         # instructions take a ReleaseStatus
    ├── AnkkaTools.scala        # whoami, installation_status, search_docs, read_doc
    └── ReleaseStatus.scala     # new: the server's view, refreshed daily
cli/src/test/scala/com/thinkmorestupidless/ankka/cli/
├── ReleaseCheckSuite.scala     # new
├── DocsBundleSuite.scala       # new
└── McpServerSuite.scala        # extended
build.sbt                        # ankka/docs/ generator applies docs/release-patterns.txt
tools/docs/src/ankka_docs/
├── release.py                  # new: patterns, rewrite, check, latest.json
├── hooks.py                    # new: MkDocs on_page_markdown
├── check.py, cli.py, render.py # wired in
tools/docs/tests/test_release.py # new
docs/release-patterns.txt        # new
docs/get-started/install.md, first-service-go.md, docs/reference/go-sdk.md, docs/reference/cli.md
mkdocs.yml                       # extra.docs.release, hooks:
.github/workflows/release.yml    # docs job: `docs build --release ${GITHUB_REF_NAME#v}`
.claude/rules/build-and-release.md, docs.md   # traps: R3's JDK 22 change; the patterns file
```

**Structure Decision**: the existing CLI module and docs tool; no new module.

## Complexity Tracking

None.
