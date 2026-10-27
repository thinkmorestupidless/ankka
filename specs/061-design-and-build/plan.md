# Implementation Plan: Design and build

**Branch**: `061-design-and-build` | **Date**: 2026-10-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/061-design-and-build/spec.md`

## Summary

Two spec-kit skills replace the eight-step feature workflow: `/speckit-design` runs specify,
clarifications, clarify, the checker, plan, tasks and the analyze loop in the main session on the
design model, asking the person only through AskUserQuestion so the model holds; `/speckit-build`
runs implement (with its review hook), the pull request and the wait for its checks on the build
model. They are a third spec-kit extension, `design-build`, kept in ankka beside `bdd` and `review`,
hand-rendered to `.claude/skills/` because spec-kit's renderer drops the `model:` line, and copied
into every family repository that has spec-kit, together with whatever of `bdd` and `review` each
lacks. A documentation page, first in Get started, presents design then build as the way to build
with ankka. The family's conventions table becomes two rows.

## Technical Context

**Language/Version**: Markdown (SKILL.md, extension manifest, docs page), YAML (`extension.yml`,
`extensions.yml`, `mkdocs.yml`), Scala 3.9.0 for the two proving suites (munit, in `testkit`'s
tests, no runtime), bash for nothing new (the Justfile stays thin).

**Primary Dependencies**: Claude Code skills (`model:` frontmatter, AskUserQuestion, Skill tool);
spec-kit 0.14.1 and its extension protocol (`extensions.yml` hooks, `commands/*.md`); speckit-bdd
v0.2.0 (the checker through `uvx`); the `review` extension and the `pr-review` plugin; `gh` for the
pull request and its checks; the docs tool (`tools/docs`) for the page.

**Storage**: the repository tree only.

**Testing**: `DesignAndBuildExtensionSuite` and `DesignAndBuildDocumentationSuite` (munit, file
reads, in `modules/testkit/src/test`); `just docs` for the page; `just features` for the living
features; the first run of `/speckit-build` on this branch as the end-to-end proof (R10).

**Target Platform**: a developer's machine running Claude Code in a spec-kit repository.

**Project Type**: tooling and documentation; no service, no runtime change.

**Performance Goals**: a design run asks the person no more than the stages already ask (specify's
up to three clarifications, clarify's up to five, the checker's findings, analyze's decision-changing
remediations); a build run adds nothing to implement's time but the wait for CI.

**Constraints**: no existing spec-kit command, extension or the checker changes (FR-003); the skills
run in the main session, never a subagent (spec Out of Scope); every question through
AskUserQuestion (FR-016); nothing merged, tagged, published or deployed by the build (FR-022a); each
sibling's change is its own branch and draft pull request (FR-027a).

**Scale/Scope**: one extension (manifest, two commands, README), two skills, two suites, one docs
page plus three edits (index, nav, orientation skill), one conventions rewrite, fourteen sibling
installs.

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template; ankka's `CLAUDE.md` principles stand in.

| Principle | Holds? | How |
|---|---|---|
| The Justfile is thin; logic lives in scripts | yes | no new recipe in ankka; the siblings that need the checker get voice's one-line `check` recipe |
| No second copy to keep in step | yes | the model lines live in the SKILL.md files only (R3); the command file and the SKILL.md share one body, which `DesignAndBuildExtensionSuite` checks |
| Ask of every check: could it pass while the thing is false? | yes | the suites assert the model lines name two different models and the page names the skills by their frontmatter names; the end-to-end proof is a real run, recorded |
| Every spec's scenarios live in living features; the checker is green | yes | 50 scenarios in five files; `just features` green at specify and clarify |
| A page stands alone, tells no history, is in `nav` and a skill | yes | R9 |
| Conventional Commits, PR template, draft until green | yes | the build enforces them (R8) |
| Nothing committed to `main`, tagged, published or deployed by an agent | yes | FR-022a; the siblings' pull requests are drafts left for the person |

No violations; Complexity Tracking is empty.

## Project Structure

### Documentation (this feature)

```text
specs/061-design-and-build/
├── plan.md              # this file
├── research.md          # R1 to R12, verify-at-implementation list, declined remediations
├── data-model.md        # the extension, the skills, the stages, the artifacts, the page
├── quickstart.md        # how the feature is proven: the suites, the docs check, the first build run
├── contracts/
│   ├── extension.md     # extension.yml, extensions.yml entry, the two command files' shape
│   ├── skills.md        # the two SKILL.md frontmatters and the stage procedures each follows
│   ├── stages.md        # the stage table: name, needs, produces, resume rule
│   └── page.md          # the page's frontmatter, sections, nav and skill positions
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
.specify/extensions/design-build/
├── extension.yml                    # id, requires bdd and review, provides speckit.design and speckit.build, no hooks
├── README.md                        # what it is, why two models, where the model lines are, how to install by copy
└── commands/
    ├── speckit.design.md            # the design procedure (body shared with the skill)
    └── speckit.build.md             # the build procedure (body shared with the skill)
.specify/extensions.yml              # installed: + design-build (no hook entries)
.claude/skills/
├── speckit-design/SKILL.md          # model: fable; argument-hint; the design procedure
└── speckit-build/SKILL.md           # model: opus; the build procedure
docs/
├── index.md                         # "Where to start": first bullet points at the page
└── get-started/design-and-build.md  # the page
mkdocs.yml                           # nav: Get started: Design, then build first
tools/docs/skill/ankka/SKILL.md      # pages: + get-started/design-and-build.md
marketplace/plugins/ankka/skills/ankka/   # rendered by `just docs-sync`
ankka.g8/src/main/g8/.claude/skills/ankka/   # rendered by `just docs-sync`, `$` escaped
modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/
├── DesignAndBuildExtensionSuite.scala       # installing.feature and models.feature, structurally
└── DesignAndBuildDocumentationSuite.scala   # documentation/design-and-build.feature
../ankka-brain/conventions.md        # the workflow section: two rows
../<sibling>/                        # fourteen repositories: the copies, one branch and draft PR each (R11)
```

**Structure Decision**: Everything the extension is lives under `.specify/extensions/design-build/`
and `.claude/skills/`, the shape `review` set; the proof lives beside the other documentation suites
in `testkit`'s tests, which already read `features/documentation/` scenarios against pages. The
siblings are changed in their own checkouts, never from this tree.

## Phase 0 and Phase 1 outputs

`research.md` resolves every unknown (R1 to R12). `data-model.md`, `contracts/` and `quickstart.md`
are beside this file. The Constitution Check above was re-evaluated after the design: unchanged.

## Complexity Tracking

None.
