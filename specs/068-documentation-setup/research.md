# Research: Setting up a repository's documentation

The decisions the plan rests on. Each is numbered; `tasks.md` names them.

## What the tree already gives

- `tools/docs` finds its project by the nearest `mkdocs.yml` (`pages.project`), reads `extra.docs`
  into `Settings`, and refuses an unknown key. Four commands in `cli.py`; `check` is
  `checks.run(tree) + skill.check(tree)`.
- The tool's `pyproject.toml` says version `0.0.0` at every tag: release jobs rewrite placeholders in
  their own working tree, not in the tag. A tool installed from `git+…@v0.18.0` cannot learn its
  release from its version.
- `skill.py` excludes pages of kind `contributing` from every skill (`EXCLUDED_KINDS`) and requires
  every other page to be in one.
- ankka publishes its site inside `release.yml`, after Maven Central, with `docs build --release`.
  ankka-reasoning and ankka-surface each have a `docs.yml` that publishes from `main`.
- `tests/test_project.py` runs the tool against a temporary repository that is not ankka.
- Dependabot watches `/tools/docs` in ankka with the `uv` ecosystem, and `github-actions` everywhere.

## R1. One command, in the tool, run before the repository depends on the tool

**Decision**: `docs init --description <sentence> --site-url <url> [--release <x.y.z>] [--force]`,
a fifth word of the same entry point. The first run in a repository is

```bash
uvx --from "git+https://github.com/thinkmorestupidless/ankka@v<release>#subdirectory=tools/docs" docs init …
```

and every later one is `uv run --project tools/docs docs init`, which needs no arguments because
the description and address are then in `mkdocs.yml`.

**Rationale**: the tool already carries the rules; the templates belong beside them, so a release of
the tool is one statement of both. `uvx` from the tag needs nothing installed but `uv`.

**Alternatives**: a word of the `ankka` CLI (`ankka docs init`): lost, the CLI is a JVM program and
the tool is Python; it could only shell out to `uvx`, and the family's repositories that are not
services have no reason to hold the CLI. A giter8 template: lost, it runs once and can hold nothing
afterwards.

## R2. The release the tool ran from is read from how it was installed

**Decision**: `setup.tool_release()` reads the distribution's `direct_url.json` (PEP 610, written by
`uv` and `pip` for a git install) and takes `vcs_info.requested_revision` when it is `v` and a
version. Anything else (a branch, a commit, a local path, an editable install) is not a release:
the command refuses and names `--release`. Run inside a set-up repository with no `--release`, the
release is the one its `tools/docs/pyproject.toml` already names.

**Rationale**: the tag is the only statement of the release that exists at the tag (the version is
`0.0.0` there). Refusing a branch is FR-007.

**Verify at implementation**: that `uvx --from git+…@v…#subdirectory=…` records
`requested_revision` as the tag and not the resolved commit, on the `uv` the workflows install.

## R3. A repository's facts come from git

**Decision**: the root is `git rev-parse --show-toplevel`; the name is the last segment of
`remote.origin.url` without `.git`; the address is that URL as `https://github.com/<owner>/<name>`
(an `ssh` form is rewritten). No remote, or one not on GitHub: nothing is written and the message
says a remote on GitHub is needed. The name must be a skill's name (`SKILL_NAME`), since it becomes
one.

## R4. The set-up is a pure plan and one executor

**Decision**: `setup.plan(facts, existing) -> list[Write]`, where a `Write` is a path, its content,
whether it is kept or owned, and what will happen (`write`, `leave`, `rewrite`, `refuse`).
`setup.apply(plan)` is the only function that touches the file system, and it writes nothing when
any entry is `refuse`. The report is printed from the plan.

**Rationale**: CLAUDE.md's first principle; and every scenario of `setting-up.feature` is an
assertion on a plan before it is one on a directory.

## R5. The kept job is a caller of a reusable workflow in ankka

**Decision**: the kept `.github/workflows/docs.yml` is

```yaml
# Kept by ankka's documentation tool (`docs init`). Edits are written over; see docs.ankka.cloud.
name: docs
on:
  pull_request:
  push:
    tags: ['v*']
  workflow_dispatch:
jobs:
  docs:
    uses: thinkmorestupidless/ankka/.github/workflows/documentation.yml@v<release>
    permissions:
      contents: read
      pages: write
      id-token: write
```

and `documentation.yml` in ankka (`on: workflow_call`) holds the steps: checkout with tags, `uv`,
`uv run --project tools/docs docs build` on a pull request or a dispatch, and on a tag with no
hyphen `docs build --release "${GITHUB_REF_NAME#v}"`, the Pages artifact and the deploy.

**Rationale**: the kept file has no action versions in it, so each repository's own Dependabot has
nothing to raise in it but the one release, and the steps are in one place for every repository
(SC-003). A workflow in a public repository may be called from any repository.

**Alternatives**: the steps inline in the kept file: lost, each repository's Dependabot would raise
`actions/checkout` there and fail the check weekly. A path filter on the pull request trigger, as the
copies have today: lost, the paths differ per repository (where samples come from), which would make
the kept file differ; the build takes under twenty seconds, so it runs on every pull request. FR-012
is reworded to say so.

**Consequence**: Dependabot may still offer to raise the `uses:` tag alone. That pull request fails
the check (the caller no longer matches what the pinned tool writes), and the message says to run
`docs init` at the new release instead, which moves both kept parts together.

## R6. The check holds the kept parts to the running tool

**Decision**: `kept.check(project)` reads the release from the `ankka-docs @ git+…@v<release>` line
of `tools/docs/pyproject.toml`, renders both kept parts for the repository's name and that release,
and compares bytes. A difference, or an absent kept part, is a problem naming the file and saying
"run `docs init`". `extra.docs.kept` (`settings: false`, `job: false`, both `true` by default) says
the tool keeps that part nowhere in this repository, and the check holds it to nothing. A dependency
line that names no tag is itself a problem.

**Rationale**: the tool that runs the check was installed from that line, so it is the release the
line names and its templates are the ones to compare with. No network, no second copy.

**Consequence**: a repository already using the tool meets this check the first time it raises the
tool to a release that has it. It runs `docs init`, or states `kept: {job: false}`. ankka itself
states both false: its `pyproject.toml` is the tool's own and its site is published by `release.yml`
after Maven Central.

## R7. A file at a kept path that the tool did not write

**Decision**: both kept parts open with the comment line `# Kept by ankka's documentation tool`. A
file at a kept path without it is someone's own: the plan marks it `refuse`, nothing is written, and
the message names `--force`. With the line, the file is rewritten without asking.

## R8. The rules become a public reference page; the procedure a public guide

**Decision**:
- `docs/reference/documentation-rules.md` (reference), "Documentation rules": what today's
  contributing page says that holds for any repository, moved word for word: the build and its
  renderings, where a page goes, frontmatter, the rules for every page, samples from tested code,
  generated reference, the skills, and every key of `extra.docs` including `kept`.
- `docs/build/documentation.md` (guide), "Document a service": setting up with `docs init`, and the
  procedure in order (FR-017).
- `docs/contributing/documentation.md` keeps what is ankka's own, as each sibling's does: its
  facets, its generated blocks and the suites that own the external ones, its skill targets, its
  descriptors check. It links to the rules.
- The skill `ankka-docs` lists both new pages. `EXCLUDED_KINDS` does not change.

**Rationale**: with the audience any ankka application, the rules are about using the tool, not
about changing ankka, so they are not a contributing page. Moving them makes the skill carry them by
the rule every other skill follows, with no exception in `skill.py`. FR-016 is reworded: the skill
carries the published rules page; no skill carries a contributing page.

**Verify at implementation**: the moved text passes the checks a contributing page is exempt from
(the history pattern: the page quotes forms the check refuses, and must quote them inside code).
Siblings link to `…/contributing/documentation/`; the address still answers and points on.

## R9. Proof

| Claim | Proved by |
|---|---|
| a repository holding only the set-up builds | `test_setup`: init into an empty temporary repository with a GitHub remote, then `checks.run`, `skill.check`, `kept.check` and `mkdocs build --strict` report nothing |
| the release is the one the tool ran from | a fake distribution with a `direct_url.json` naming `v0.19.0`; one naming `main` is refused |
| owned parts are never written over | a `mkdocs.yml` of the test's own is byte-identical after |
| kept parts are always written | a hand edit to each is gone after init; the plan says `rewrite` |
| a foreign file at a kept path stops everything | the directory is byte-identical after, exit code 2 |
| the check holds each kept part | one changed byte in each fails `kept.check`, naming the file; `kept: false` passes with the file absent |
| a later release moves both | init with `--release 0.20.0` over a `0.19.0` set-up: both name `0.20.0`, pages untouched |
| pull request checks, tag publishes, pre-release does not | assertions on the rendered caller and on `documentation.yml`'s conditions; and the quickstart on ankka-surface |
| the skill holds the page as published | the rendered `references/reference/documentation-rules.md` equals `render.page_markdown` of the page, which `skill.check` already holds |
| the template says how | the giter8 README names `docs init` and the guide's address |

## R10. The written pages

**Decision**: the four pages `docs init` writes pass every rule and say they are a start. The first
page says what the project is in the description's sentence and that nothing else is written yet;
the limitations page has one entry ("Only this page and the first are written"); the glossary page
has its heading and one line; the contributing page is the sibling pattern with this repository's
name, the commands, and a link to the rules. The skill's body is three lines and a `pages:` list of
the three public pages.

## Declined alternatives, in one place

- Publishing the tool to PyPI so `uvx ankka-docs init` works: a release channel to add and keep, for
  one line of convenience. Not now.
- Moving the four siblings onto the kept parts in this feature: each is a pull request in its own
  repository after a release carries the command.
- `docs init` editing the `Justfile`: it prints the three recipes.

## Analysis

2026-11-28, first report: nothing critical or high. Five medium, each applied, none changing a
decision or a scenario: FR-009 had no assertion (T010); SC-005 had no task (T028, quickstart step
6); the spec said "kept file" where the features and the data model say "kept part" (spec); the
written `mkdocs.yml` named a `README.md` a repository might not have (T007); the reusable workflow
was proved only by assertions on its YAML (T027). Four low, left: FR-018 (the skill's body restates
no table) is held by review, not a test; FR-021 is a follow-up in another repository; a publish that
fails because hosting was never turned on has no task, being GitHub's behaviour; the feature's first
scenario does not name the plugin description, which the glossary's "plugin" (a command of the
command line) would mis-say, and T010 asserts it from the data model.

Second report: nothing above low.

## Design finished

2026-11-28: analyze reported nothing above LOW.

## Follow-ups

- ankka-flow, satisfactory, ankka-reasoning, ankka-surface: `docs init` at the release that carries
  it; ankka-reasoning and ankka-surface stop publishing from `main`.
- `ankka-brain/conventions.md`: the documentation section names `docs init` (FR-021).
- Holding a page's replies to the running service; the untested-sample check without a languages
  facet; leaving a file under `docs/` out of the pages.
