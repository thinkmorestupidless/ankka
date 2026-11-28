# Implementation Plan: Setting up a repository's documentation with one command, and a skill for writing it

**Branch**: `068-docs-init` | **Date**: 2026-11-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/068-documentation-setup/spec.md`

## Summary

The documentation tool gains one command, `docs init`, which works out a repository's name and
address from its git remote, renders the set-up from templates the tool carries, and writes it: two
kept parts (`tools/docs/pyproject.toml`, naming the tool at the release the command ran from, and
`.github/workflows/docs.yml`, a ten-line caller of a reusable workflow in ankka at the same release)
and the parts the repository owns (`mkdocs.yml`, four pages, a skill, a plugin description), written
only when absent. `docs check` renders the kept parts again and fails when either differs. The
reusable workflow checks on every pull request and publishes to GitHub Pages on a `v*` tag with no
hyphen. The rules every page follows move from ankka's contributing page to a public reference page,
a new public guide says how a service is documented, and a new skill `ankka-docs` carries both, in
the plugin and in the template as the platform's other skills are.

## Technical Context

**Language/Version**: Python 3.12 (`tools/docs`); YAML (two workflows); Markdown (three pages, one
skill, the template's README).

**Primary Dependencies**: none new. `mkdocs`, `mkdocs-material`, `pymdown-extensions` and `pyyaml`
as now; `importlib.metadata` and `importlib.resources` from the standard library; `git` on the path,
which the tool already calls for release tags.

**Storage**: none. Everything is files in the repository being set up.

**Testing**: pytest in `tools/docs/tests`, one test per scenario of
`features/documentation/setting-up.feature` and `writing-documentation.feature`, named by the
scenario, against temporary git repositories; the existing `test_project.py` pattern (the tool
against a repository that is not ankka). The reusable workflow is proved by ankka-surface adopting it
(quickstart), since only GitHub runs a workflow.

**Target Platform**: a developer's machine and GitHub Actions on `ubuntu-24.04`.

**Project Type**: a command line tool, a reusable workflow, and documentation.

**Languages reached**: none of the SDK languages; the feature is the documentation tool's. The two
new pages carry no `languages` and need no tabs.

**Performance Goals**: `docs init` in under two seconds on an empty repository; the first
`docs build` after it is bounded by installing the tool, as now.

**Constraints**: a kept part is byte-identical across repositories but for the repository's name
(SC-003); nothing written names a branch (SC-002); the kept workflow holds no secret (FR-014); a
repository already using the tool keeps building until it raises the tool's release.

**Scale/Scope**: two new modules and one edited in `tools/docs/src/ankka_docs`, a template directory
of nine files, one reusable workflow, two feature files with 17 scenarios, two new pages, three
edited, one new skill, the template's README, four glossary terms.

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template; ankka's `CLAUDE.md` stands in.

| Principle | Holds? | How |
|---|---|---|
| Effects are inert data | yes | `setup.plan` is pure: a repository's facts in, a list of writes out, each marked kept or owned. One executor writes them (R4) |
| Explicit registration, never scanning | yes | the set-up is the nine templates the tool lists by name; nothing is discovered |
| Only a tag publishes | yes | the kept workflow publishes on a `v*` tag and never from a branch; the written dependency names a tag (R2, R5) |
| No second copy to keep in step | yes | the rules are one public page the skill carries; the kept parts are rendered, never copied; action versions live in one reusable workflow (R5, R7) |
| Nothing installation-specific in a public repo | yes | the templates name a repository's own name and address and nothing else |
| Could this check pass while the thing is false? | yes | each refusal is asserted by breaking the thing: a hand edit to each kept part, a foreign file at a kept path, a branch install (R9) |
| Same conventions across repos | yes | this feature is that principle for the documentation set-up |
| A behaviour change is a docs change | yes | the pages are part of the feature (R7, R8) |

No gate fails. Re-checked after the design: unchanged.

## Project Structure

### Documentation (this feature)

```text
specs/068-documentation-setup/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── docs-init.md           the command: arguments, report, exit codes
│   ├── kept-parts.md          the two kept files, byte for byte, and the check
│   └── settings.md            `extra.docs.kept`
├── checklists/requirements.md
└── tasks.md                   (/speckit-tasks)
```

### Source Code (repository root)

```text
tools/docs/
├── src/ankka_docs/
│   ├── cli.py                 + `init`, handled before a project is looked for
│   ├── setup.py               new: facts of a repository, the plan of writes, the executor, the report
│   ├── kept.py                new: render the kept parts; the check that holds them
│   ├── pages.py               + `Settings.kept` (`extra.docs.kept`)
│   └── templates/             new, package data
│       ├── pyproject.toml.tmpl            kept
│       ├── docs.yml.tmpl                  kept
│       ├── mkdocs.yml.tmpl                owned
│       ├── index.md.tmpl                  owned
│       ├── writing-documentation.md.tmpl  owned
│       ├── limitations.md.tmpl            owned
│       ├── glossary.md.tmpl               owned
│       ├── SKILL.md.tmpl                  owned
│       └── plugin.json.tmpl               owned
├── skill/ankka-docs/SKILL.md  new: the skill for writing documentation
└── tests/
    ├── test_setup.py          new: setting-up.feature
    └── test_writing_skill.py  new: writing-documentation.feature

.github/workflows/documentation.yml   new: the reusable workflow the kept caller names
docs/
├── build/documentation.md             new guide: Document a service
├── reference/documentation-rules.md   new reference: the rules, moved from the contributing page
├── contributing/documentation.md      trimmed to what is ankka's own; links to the rules
└── reference/limitations.md           + what a set-up does not do
ankka.g8/src/main/g8/README.md         + how the service's documentation is set up
marketplace/plugins/ankka/skills/ankka-docs/           rendered
ankka.g8/src/main/g8/.claude/skills/ankka-docs/        rendered
mkdocs.yml                             + two nav entries; `extra.docs.kept: {settings: false, job: false}`
```

**Structure Decision**: everything is in `tools/docs` and `docs/`, with one workflow beside the
others. No JVM module changes.

## Complexity Tracking

No violation to justify.
