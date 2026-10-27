# Data model: Design and build

Nothing here is stored by a service; these are the files and the states the two skills read and
write.

## The extension

| Entity | Where | Fields | Rules |
|---|---|---|---|
| Extension | `.specify/extensions/design-build/extension.yml` | `id: design-build`, `version`, `description`, `requires.speckit_version >=0.14.0`, `requires.extensions: [bdd, review]`, `provides.commands: [speckit.design, speckit.build]`, no `hooks`, no `config` | the description says why the halves run on different models and that each skill's `model:` line is the only record; `requires.extensions` is informative (spec-kit does not enforce it), so each command checks `extensions.yml` itself |
| Command file | `commands/speckit.design.md`, `commands/speckit.build.md` | frontmatter `description`, `argument-hint` (design only); body | the body is identical to the skill's body; `DesignAndBuildExtensionSuite` compares them |
| Skill | `.claude/skills/speckit-design/SKILL.md`, `.claude/skills/speckit-build/SKILL.md` | frontmatter `name`, `description`, `model`, `argument-hint` (design), `compatibility`, `metadata.author: thinkmorestupidless`, `metadata.source: design-build:commands/speckit.<x>.md`; body | `model` names a Claude Code alias; the two differ; nothing else under the extension or the two skill directories contains a `model:` key |
| Registration | `.specify/extensions.yml` | `installed: [..., design-build]` | no hook entries: the commands are invoked by the person, never by a hook |

## The stages

| Entity | Fields | Rules |
|---|---|---|
| Stage | `name` (one of `specify`, `clarify`, `check`, `plan`, `tasks`, `analyze`, `implement`, `pull-request`), `needs` (files or state that must exist), `runs` (the command or procedure), `produces` | listed in `contracts/stages.md`; a stage's hooks fire inside it as when run alone |
| Design run | `description` (text after the command, or asked for), `from` (a stage name, default `specify`), `rounds` (analyze rounds so far, at most 3), `declined` (remediations the person declined, written to `research.md`) | refuses on the default branch; refuses without `bdd` or `review` registered; every question via AskUserQuestion |
| Build run | `feature_directory` (from `.specify/feature.json`), `base` (computed or `--base`), `state` (derived: `implementing`, `reviewing`, `raising`, `watching`, `ready`, `stopped`) | refuses without `tasks.md`, `review`, or the `pr-review` skill; stops on the four conditions of FR-020 and on a check it cannot make green |

### The build run's state, derived from the tree

```text
tasks.md has an unticked task             → implementing   (runs /speckit-implement; its hook reviews)
all ticked, review.md absent              → reviewing      (runs /speckit-review: implement was done without the hook)
all ticked, review.md present, no PR      → raising        (gh pr create --draft)
PR exists, a check pending or running     → watching       (gh pr checks --watch)
PR exists, all checks green, PR is draft  → ready          (gh pr ready, report)
PR exists, a check red                    → fix on branch, or flake evidence, or stopped
```

The state is never written down; it is read from `tasks.md`, the feature directory and `gh pr view`
each time the build starts, which is what makes a cut-short run resumable.

## The feature directory

| File | Written by | Read by |
|---|---|---|
| `spec.md` | specify, clarifications, clarify, analyze remediations | plan, tasks, analyze, implement, the review |
| `checklists/requirements.md` | specify, clarify | the person |
| `plan.md`, `research.md`, `data-model.md`, `contracts/`, `quickstart.md` | plan, analyze remediations; `research.md` also by the design's declined list and by implement's findings | tasks, analyze, implement, the review |
| `tasks.md` | tasks, analyze remediations, implement (ticks) | analyze, implement, the build's state |
| `review.md` | the review | the build's state, the pull request body |
| `.specify/feature.json` (repository root) | specify | every stage |

## The page

| Field | Value |
|---|---|
| path | `docs/get-started/design-and-build.md` |
| `title` | Design, then build |
| `kind` | guide |
| `related` | `get-started/coding-agents.md`, `get-started/install.md`, `contributing/documentation.md` |
| nav | first under Get started |
| skills | `tools/docs/skill/ankka/SKILL.md` `pages:` |
| proven by | `DesignAndBuildDocumentationSuite`, one test per scenario of `features/documentation/design-and-build.feature`, plus the name check |

## The sibling install

| Field | Value |
|---|---|
| repositories | the fourteen in research R11 |
| branch | `design-and-build` in each |
| commit | `chore(speckit): install review and design and build` or `chore(speckit): install bdd, review and design and build` |
| pull request | draft, base `main`, body from that repository's template where it has one, otherwise the five sections |
| what is copied | `.specify/extensions/review/`, `.specify/extensions/design-build/`, `.claude/skills/speckit-review/`, `.claude/skills/speckit-design/`, `.claude/skills/speckit-build/`, the `installed` and `hooks.after_implement` entries |
| for a repository without `bdd` | `specify extension add bdd --from <v0.2.0 zip>`, `specify preset add bdd --from <v0.2.0 zip>`, `GLOSSARY.md` with the Everyday words heading, `features/.gitkeep`, `bdd-config.yml` with `specs-from` at the next spec number, a `check` recipe (voice's) or CI job |
