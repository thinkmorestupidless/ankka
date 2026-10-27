# Contract: the page

`docs/get-started/design-and-build.md`.

## Frontmatter

```yaml
---
title: Design, then build
description: Build a feature on ankka by describing it, answering the design's questions, and approving the pull request the build raises; what each half produces and where you are asked.
kind: guide
related: [get-started/coding-agents.md, get-started/install.md, contributing/documentation.md]
---
```

## Sections, in order, and what each must say

1. `# Design, then build`: a feature on ankka is built by describing it, answering questions, and
   approving a pull request; two commands, `/speckit-design` and `/speckit-build`; this is how you
   build with ankka, with the coding agent holding ankka's skills (link to the coding-agents page for
   the skills and the MCP server).
2. `## Describe the feature`: `/speckit-design <description>`; what a good description holds (who,
   what, what is out); that the design asks for one when none is typed.
3. `## What the design produces`: the spec directory (`specs/NNN-name/`: `spec.md`, `plan.md`,
   `research.md`, `tasks.md`); the living features (`features/`, Gherkin, what the system does now,
   the spec names its scenarios); the glossary (`GLOSSARY.md`, one sense per word); the checker that
   holds them together.
4. `## Where you are asked`: the spec's clarifications as options with implications; clarify's
   questions one at a time; a remediation that would change a decision or a scenario; nothing else.
   Where you are not: plan, tasks, remediations that change no decision.
5. `## Build it`: `/speckit-build`; implement task by task, committed as it goes; the review before
   the pull request with a fresh context; the draft pull request on the right base; the wait for
   checks; marked ready when green; where the build stops for you (a decision the design left open,
   a contract change, a failing check it cannot fix, a blocking finding it disagrees with); that it
   never merges.
6. `## Two models`: design on the deeper-thinking model, build on a cheaper one; why; where the
   lines are (`model:` in each skill's `SKILL.md`) and that a project may change them.
7. `## Install`: in a project made from the ankka template, what is already there (to be true
   after the follow-up; until then this section says the template does not yet carry the extension
   and points at the steps); in any other project, the steps by hand: install spec-kit (`uv tool
   install specify-cli`), `specify init --here --ai claude --ai-skills`, add `bdd` (extension and
   preset from the speckit-bdd release), copy `review` and `design-build` from the ankka repository
   with their skills, register them in `.specify/extensions.yml`, install `pr-review`
   (`/plugin marketplace add thinkmorestupidless/ankka-marketplace`, `/plugin install pr-review@ankka`).
8. `## Repairing one stage`: the individual commands, named, for when one stage needs redoing;
   `/speckit-design --from <stage>`.

## Positions

- `mkdocs.yml`: `nav` → `Get started` → first entry `Design, then build: get-started/design-and-build.md`.
- `docs/index.md`: "Where to start" → first bullet `**Building a feature.** [Design, then build](get-started/design-and-build.md) …`, before "New to ankka".
- `tools/docs/skill/ankka/SKILL.md`: `pages:` gains `get-started/design-and-build.md` after `get-started/coding-agents.md`.

## Rules

- Standalone, no history, no positional references; `just docs` green.
- Every skill name on the page is the `name:` of a SKILL.md under `.claude/skills/`;
  `DesignAndBuildDocumentationSuite` reads both and fails on a mismatch.
- The page never promises what `docs/reference/limitations.md` denies; the template's install
  section says plainly what the template does not yet carry.
