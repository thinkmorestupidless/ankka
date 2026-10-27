# Research: Design and build

Decisions made while planning, numbered; what to verify at implementation; what implementation
found; measurements at the end.

## Decisions

### R1. Two skills in the main session, the model set by each skill's frontmatter

**Decision**: `/speckit-design` and `/speckit-build` are ordinary Claude Code skills that run in the
main session. Each carries `model:` in its SKILL.md frontmatter, which Claude Code applies for the
rest of the turn the skill is invoked in. Every question the design asks goes through the
AskUserQuestion tool, which keeps the turn open, so the model override holds from the first question
to the last.

**Rationale**: Nothing inside a session can change the session model: no tool, hook or skill
instruction (Claude Code docs, model-config and hooks pages, read 2026-10-27). The only per-run
switches are skill frontmatter, the Agent tool's `model` parameter and an agent definition's
`model`. A subagent cannot ask the user anything (AskUserQuestion is removed from every subagent),
so the design half, which exists to ask, must run in the main session. The build half could run as
a subagent, but then its blocking questions could not reach the person and the live view of a long
implement would be lost; a second skill with its own `model:` gives the model switch with neither
cost.

**Alternatives considered**: one orchestrating skill that spawns the implementer as an Opus subagent
(proposed first; rejected by the user for the lost live view and the relay of questions); the
Workflow tool (disabled in the user's settings, and cannot ask the user); a `Stop` hook that
re-prompts to continue (cannot change the model).

### R2. A spec-kit extension `design-build`, installed by copying, its skills hand-written

**Decision**: The extension lives at `.specify/extensions/design-build/` with `extension.yml`,
`commands/speckit.design.md`, `commands/speckit.build.md` and a `README.md`. The rendered skills
`.claude/skills/speckit-design/SKILL.md` and `.claude/skills/speckit-build/SKILL.md` are written by
hand and committed, with the command file and the skill holding the same body. A sibling repository
installs it by copying the extension directory and the two skill directories and adding the
`installed` entry to `.specify/extensions.yml`, exactly as `review` is installed. `specify
extension add` is not the install path.

**Rationale**: spec-kit 0.14.1's skill renderer (`build_skill_frontmatter` in `agents.py`) writes
only `name`, `description`, `compatibility` and `metadata`, plus `argument-hint`; a `model:` key in
the command's frontmatter is dropped. `review` already follows the copy path and is not in
`.specify/extensions/.registry` (only `bdd` is, installed through the CLI). The model line must be in
the SKILL.md the tool reads, so the SKILL.md is the definition and is committed.

**Alternatives considered**: `specify extension add --dev <path>` followed by patching the rendered
SKILL.md (two steps, one of which the renderer undoes on `extension update`); a render script in the
extension (a second thing to keep working across spec-kit versions; the user declined install
scripts).

### R3. The model lines: `fable` for design, `opus` for build

**Decision**: `model: fable` in the design skill, `model: opus` in the build skill. Nothing else in
the extension names a model; the extension's `README.md` and the manifest description say why the
halves differ and that the lines are in the two SKILL.md files.

**Rationale**: The user's principle: the deeper-thinking model for everything that decides, a
cheaper one once everything is decided. Claude Code accepts the aliases `fable`, `opus`, `sonnet`,
`haiku` in frontmatter. A configuration file recording the choice would be a second copy that can
drift from the lines that act (CLAUDE.md: "a recipe that reimplements a step becomes a second copy
to keep in step"); the user chose the definitions as the only record.

### R4. Questions from the stages are converted, not re-asked

**Decision**: The design skill wraps each stage it invokes with one rule: wherever a stage's
instructions say to present a question, a table of options or a `NEEDS CLARIFICATION` and wait for
the person, the session asks it through AskUserQuestion instead, with the stage's options and
their implications as the options, then continues the stage with the answer. The stage's own
encoding of the answer (clarify writes it into the spec; specify replaces the marker) is unchanged.

**Rationale**: `speckit-specify` presents its clarification tables and waits; `speckit-clarify`
presents one question at a time and waits. Both end the turn, which drops the model override. The
conversion keeps the stages untouched (FR-003) and the model in force (FR-016). This session's own
run of the workflow did exactly this for 061's seven questions.

**Alternatives considered**: editing the two stages to use AskUserQuestion (changes spec-kit's
commands, out of scope); accepting the turn end and asking the person to run the design again
(defeats the feature).

### R5. Stages and resuming

**Decision**: The stages, their inputs and their outputs:

| Stage | Runs | Needs | Produces |
|---|---|---|---|
| `specify` | `/speckit-specify <description>`, then its `after_specify` hook (`bdd` features) | a description, a branch that is not the default | `spec.md`, `checklists/requirements.md`, `.specify/feature.json`, the living features and glossary entries |
| `clarify` | the `before_clarify` hook (`bdd` check), then `/speckit-clarify` | `spec.md` | the Clarifications section, settled glossary terms |
| `check` | the checker through the repository's recipe (`just features` in ankka, `just check` elsewhere) | the features and glossary | nothing; green or a finding to settle |
| `plan` | `/speckit-plan` | `spec.md` | `plan.md`, `research.md`, `data-model.md`, `contracts/`, `quickstart.md` |
| `tasks` | `/speckit-tasks` | `plan.md` | `tasks.md` |
| `analyze` | `/speckit-analyze`, remediations applied, repeated | `tasks.md` | an analyze report with nothing above low; `research.md` gains "Declined remediations" |
| `implement` | `/speckit-implement`, whose `after_implement` hook runs `review` | `tasks.md` | commits, ticks, `review.md` |
| `pull-request` | `gh pr create --draft`, then polling, then `gh pr ready` | a clean tree with the review recorded | the pull request, ready when green |

`/speckit-design --from <stage>` checks the stage's "needs" column exists and runs from there;
`/speckit-build` has no flag because its resume is read from the tree: unticked tasks → implement;
all ticked and `review.md` present and no pull request for the branch → pull request; a pull request
exists → polling.

**Rationale**: The stage names are spec-kit's own, so the person can map them onto the individual
commands they already know. The build's state is fully visible in the tree and `gh pr view`, so a
flag would only be a second source of truth.

### R6. The analyze loop

**Decision**: `/speckit-analyze` is read-only and proposes remediations. The design skill applies
each remediation that changes neither a decision in the spec's Clarifications nor a scenario in the
living features, by editing `spec.md`, `plan.md` or `tasks.md` as the report says; one that would
change a decision or a scenario is put to the person first; one the person declines is recorded
under a `## Declined remediations` heading in `research.md` and not proposed or applied again. A
remediation that changes a scenario goes through the feature file and the checker before analyze
runs again. At most three rounds; what remains after the third is shown to the person.

**Rationale**: The conventions (2026-10-22) already say every remediation is applied and analyze run
again; this writes the rule down where the tool follows it. The decline record lives in
`research.md` because that is the per-feature decision record and the next analyze run reads it.

### R7. The build's checks before it starts

**Decision**: Before implement, the build checks that `.specify/feature.json` names a directory with
`tasks.md`, that `review` is in `.specify/extensions.yml`, and that the `pr-review` skill is listed
(as `pr-review:pr-review` from the plugin or `pr-review` from a skills directory). Any failure stops
the build with the fix: the design command to run, or the plugin install lines from
`speckit-review`.

**Rationale**: The review hook already refuses without `pr-review`, but only after implement has
run for hours; FR-002 moves the check to the front. The hook's own check stays, so a session that
loses the plugin mid-run still refuses to report a review it did not run.

### R8. The pull request and its checks

**Decision**: The base is `main` unless the branch was cut from an unmerged feature branch
(`git merge-base` against each candidate, the nearest wins; `--base` as an override). The title is
the Conventional Commits header with the spec number as scope; the body is written from
`.github/pull_request_template.md` (never `--body-file` of the template itself), with the three
review lines under "How it is tested". `gh pr create --draft`, then `gh pr checks --watch
--fail-fast` (polling until every check finishes), then on all green `gh pr ready` and the report.
A red check is read with `gh run view <id> --log-failed`; it is fixed on the branch and the watch
restarts, or it is shown to be a flake of `main` by a run of the same workflow on `main` failing
the same job (`gh run list --branch main --workflow <name>`), which is cited in the PR body and does
not hold the PR. A check the build cannot make green stops the build with the PR still a draft.

**Rationale**: All of this is already the convention ("CI before a human", 2026-10-22) and the
commit-and-PR conventions; the build writes them into one procedure. `gh pr checks --watch` is the
documented way to wait, and returns non-zero on a failure so the skill can branch on it.

### R9. The documentation page and how it is proven

**Decision**: `docs/get-started/design-and-build.md`, title "Design, then build", `kind: guide`,
first in `Get started` in `mkdocs.yml`, first bullet of the opening page's "Where to start", and in
the orientation skill's `pages:` list. It is proven by `DesignAndBuildDocumentationSuite` in
`testkit`'s tests, in the shape of `TimersDocumentationSuite`: one test per scenario of
`features/documentation/design-and-build.feature`, reading the page and asserting the statements,
plus one that reads the two SKILL.md names and asserts the page uses them, so a renamed skill fails
the suite.

**Rationale**: The docs rule file requires a new page in `nav` and in a skill's `pages:` list, and
the `features/documentation/` scenarios in this repository are held to the pages by munit suites in
`testkit`. The name check is the cheapest proof that the page and the extension agree.

### R10. How the extension's own scenarios are proven

**Decision**: `features/design-and-build/installing.feature` and `models.feature` are proven
structurally by `DesignAndBuildExtensionSuite` in `testkit`'s tests: the manifest requires `bdd` and
`review`, the two SKILL.md files exist with `model:` lines naming different models, nothing else in
the extension names a model, the extension is registered in `extensions.yml`, the command files and
the skills share a body. `design.feature` and `build.feature` describe a coding agent's behaviour
and no suite can drive one; they are proven by a recorded run: this feature's own branch is the
first run of `/speckit-build` (the skill exists on the branch once implemented), and the pull
request body records what it did, stage by stage. `quickstart.md` is that procedure.

**Rationale**: `testing.md`: a scenario no suite can reach is a test named after it, and the cheapest
proof a check can fail is to watch it go red once. Running the build on its own branch is the
honest end-to-end test available.

### R11. The sibling repositories

**Decision**: Fourteen checkouts have spec-kit. Each gets a branch `design-and-build`, one commit
`chore(speckit): install review and design and build` (or `... bdd, review and design and build`),
and a draft pull request left for the person. What each needs, from the survey of 2026-10-27:

| Repository | Has | Gets |
|---|---|---|
| voice, ankka-flow, ankka-reasoning, ankka-predictions | `bdd`, Justfile with the checker, CI (ankka-predictions: no CI) | `review`, `design-build` |
| ankka-projects, ankka-for-trades, programme | `bdd`, CI with the checker, no Justfile | `review`, `design-build` |
| ankka-for-plumbers | `bdd`, Justfile without the checker, CI | `review`, `design-build`, the checker recipe |
| ankka-for-plumbers-android, ankka-for-plumbers-ios | `bdd`, no Justfile, no CI | `review`, `design-build` |
| ankka-modules (eitheror's checkout) | `bdd`, Justfile, CI | `review`, `design-build` |
| satisfactory, ankka-vendor | spec-kit, specs, skills, no extension, no glossary | `bdd` (extension and preset from the v0.2.0 release, `GLOSSARY.md`, `features/`, `bdd-config.yml` with `specs-from` at the next spec number), the checker recipe, `review`, `design-build` |
| ankka-replication | spec-kit, no specs, no skills, no Justfile | the Claude integration's skills (`specify init --here --ai claude --ai-skills`), then as satisfactory |

Each copy of `review` and `design-build` is ankka's at the commit this feature merges;
`pr-review` is installed per session, not per repository, and each sibling's README or CLAUDE.md
gains the install line where ankka's conventions already say it.

**Rationale**: The user chose "everything everywhere". The survey is the basis; implement re-checks
each repository before touching it, since a sibling may have moved. A sibling's pull request cannot
be reviewed by `/speckit-build` until this feature merges, so they are raised as drafts and listed.

### R12. The conventions

**Decision**: `ankka-brain/conventions.md`'s eight-row table becomes two rows (design, build) with
the stages each runs, where each asks, and the models; the rules that follow the table are kept
where they still hold and the ones the commands now enforce are folded into the rows. `voice-brain`
is checked for a copy of the workflow and changed the same way if it has one. The brain commits are
pushed to `origin main` in the same step (the brains are the sync channel).

## Verify at implementation

- That `model:` in SKILL.md frontmatter is honoured for a skill invoked by name, and that
  AskUserQuestion keeps the override through several questions: run `/speckit-design` from a
  session started on another model and read the model the turn ran on.
- That `/speckit-specify` and `/speckit-clarify` invoked from inside a skill still fire their hooks
  (this session did; confirm it from the design skill).
- That `gh pr checks --watch --fail-fast` returns non-zero on a failed check and zero on all green,
  and how it reports a pending required check that never starts.
- That `gh pr ready` on a draft whose checks are green succeeds without `--undo` semantics.
- The survey of the siblings: re-run `survey.sh`'s checks per repository before changing it.
- ankka-replication's spec-kit state: whether `specify init --here` with the Claude integration is
  the right repair or the skills are elsewhere.
- Whether `voice-brain/conventions.md` holds a copy of the workflow table.

## Found during implementation

- **T005, the structural suite before the skills.** `DesignAndBuildExtensionSuite`: 13 tests, 11 red
  (every one that reads a skill, the README or a command file), 2 green on what already existed: the
  manifest registers no hook and requires `bdd` and `review`, and the individual stage skills exist.

## Declined remediations

(empty)

## Measurements

(empty)
