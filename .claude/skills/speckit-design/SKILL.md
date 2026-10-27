---
name: speckit-design
description: "Design a feature in one run on the design model: specify, its clarifications, clarify, the checker, plan, tasks, and analyze with every remediation applied; asks the person only what they must decide, through AskUserQuestion, and ends by printing the build command."
model: fable
argument-hint: "<feature description>, or --from <stage>"
compatibility: "Requires spec-kit project structure with .specify/ directory, the bdd and review extensions"
metadata:
  author: thinkmorestupidless
  source: "design-build:commands/speckit.design.md"
---

# Design a feature

One run takes a feature from a description to an analysed task list: the spec, its scenarios in the
living features and the glossary, the plan and its design artifacts, and `tasks.md`, with every
remediation analyze proposes applied. The person is asked only what they must decide. The build is a
separate command, `/speckit-build`, on its own model, started in a new session when this one ends.

This command runs on the model its skill's `model:` line names, for as long as this turn lasts. A
question asked by ending the turn would hand the rest of the run to the session's own model, so the
whole run is one turn.

## User input

```text
$ARGUMENTS
```

- `--from <stage>`: resume at a stage of an existing feature (below). Everything else in the input
  is ignored when it is given.
- Anything else: the feature description, passed whole to `/speckit-specify`.
- Nothing: ask for a description through AskUserQuestion (one option, "I'll describe it", whose
  "Other" text is the description). An answer with no description stops the run, having written
  nothing.

## The question rule

Hold this for every stage, including the stages' own instructions and their hooks:

**Every question to the person goes through the AskUserQuestion tool. Never end the turn to ask.**
Wherever a stage says to present a question, a table of options, a `[NEEDS CLARIFICATION]` marker,
a checker finding or a remediation and to wait for the person's reply, ask it through
AskUserQuestion instead: the stage's options become the options (at most four; a recommended one
first, labelled "(Recommended)"), each option's description carries its implication, and the
person's free-text answer arrives through "Other". Up to four independent questions may go in one
call; a question whose options depend on an earlier answer waits for it. Then continue the stage
with the answer exactly as the stage says to use it: specify writes it in place of the marker,
clarify records it under `## Clarifications` and in the section it touches.

Never print a question and stop. A run that ends with a question in its last message has failed.

## Before anything runs

Stop, having written nothing, and say why, when:

1. **The branch is the default branch.** Read it from `git symbolic-ref --short
   refs/remotes/origin/HEAD` (falling back to `main`); if `git branch --show-current` is the same,
   say: make a branch for the feature first, `git switch -c NNN-short-name`, with the next spec
   number.
2. **`bdd` or `review` is not installed.** Both must be listed under `installed:` in
   `.specify/extensions.yml`. Name the one missing; the design writes scenarios through `bdd`, and
   the build it hands over to cannot review without `review`.
3. **`--from` names a stage whose inputs are missing** (the table below). Name the earliest stage
   that produces them, and the command that resumes there.
4. **AskUserQuestion is not among this session's tools** (a headless `claude -p` run, a subagent).
   Say the design needs an interactive Claude Code session and stop. Offer no way round it: a design
   whose decisions were defaulted is not a design.

## The stages

Run them in order, from `specify` or from the stage `--from` names. Each stage is the existing
command, invoked through the Skill tool exactly as the person would type it, so its own hooks fire
inside it as they do when it is run alone.

| Stage | Needs | Runs | Leaves |
|---|---|---|---|
| `specify` | a description; a branch that is not the default | `/speckit-specify <description>`; its `after_specify` hook writes the scenarios | `specs/NNN-name/spec.md`, `checklists/requirements.md`, `.specify/feature.json`, `features/`, `GLOSSARY.md` |
| `clarify` | `spec.md` | `/speckit-clarify`; its `before_clarify` hook runs the checker first | `## Clarifications` in the spec; proposed glossary terms settled |
| `check` | `features/`, `GLOSSARY.md` | the repository's checker | nothing when green |
| `plan` | `spec.md` with no `[NEEDS CLARIFICATION]`; the checker green | `/speckit-plan` | `plan.md`, `research.md`, `data-model.md`, `contracts/`, `quickstart.md` |
| `tasks` | `plan.md` | `/speckit-tasks` | `tasks.md` |
| `analyze` | `tasks.md` | `/speckit-analyze`, then the remediation loop | edits to the spec, plan and tasks; a last report with nothing above low |

`--from` never rewrites what an earlier stage left. `--from specify` on a feature directory that
already holds a spec is refused: edit the spec by hand, or resume at `clarify`.

### specify

Invoke `/speckit-specify` with the description. When it would present its clarification tables and
wait, ask them through AskUserQuestion (the question rule) and let it write each answer into the
spec. When its `after_specify` hook has run, `.specify/feature.json` names the feature directory;
every later stage reads it.

### clarify

Invoke `/speckit-clarify`. Its `before_clarify` hook runs the checker and turns each finding into a
question, and it then asks up to five of its own, one at a time; each goes through AskUserQuestion,
and each answer is encoded before the next is asked. Every `*Proposed.*` glossary term the spec
introduced is settled here: ask whether the terms stand as one question listing them, with "accept
all" recommended when no term collides with an existing one.

### check

Run the checker the way the repository runs it: `just features` when the Justfile has that recipe,
else `just check`, else the `checker` line of `.specify/extensions/bdd/bdd-config.yml` with `check
--root . --glossary <glossary> --features <features> --specs <specs>`. Green is `0 findings`. A
finding is settled as `/speckit-bdd-check` settles one: a refused synonym or a repeated scenario is
fixed without asking; a contradiction or an undefined word is a question. Run the checker again
until it is green. `plan` does not run before it is.

### plan, tasks

Invoke `/speckit-plan`, then `/speckit-tasks`. When the plan finds a decision the spec left open, it
is a question, not a guess.

### analyze

`/speckit-analyze` reports and changes nothing. After each report:

1. **Read `## Declined remediations` in `research.md`.** A finding whose remediation is listed there
   is neither applied nor asked about again.
2. **Apply every remediation that changes neither a decision nor a scenario**: a decision is an
   answer under `## Clarifications` or a decision in `research.md`; a scenario is a scenario in
   `features/`. Edit the spec, plan or tasks as the report says. Do not ask.
3. **Ask about every other remediation**, through AskUserQuestion, with "apply" and "decline" as the
   options and what each would change as their descriptions. A declined one is added under
   `## Declined remediations` in `research.md` (create the heading if it is missing), one line each:
   the finding's summary and the person's reason.
4. **A remediation that changes a scenario** is made in the feature file first; then the `check`
   stage runs again before analyze does.
5. **Run `/speckit-analyze` again.** Stop when its report holds no finding above LOW. After the
   third round, stop anyway and show what remains, through AskUserQuestion: "proceed to build with
   these" or "stop here".

## Commits

After each stage that wrote files, commit what it wrote, with a Conventional Commits header whose
scope is the feature's number: `docs(NNN): specify <short name>`, `docs(NNN): clarify …`,
`docs(NNN): plan …`, `docs(NNN): tasks for …`, `docs(NNN): analyze …, remediations applied`. Use the
repository's commit hooks; a hook that refuses a commit is fixed, never skipped.

## The end

Print, in this order: the feature directory; the task count (`0 of N`); the checker's result; the
last analyze report's findings by severity; the decisions the person made in this run, one line
each; then exactly:

```text
The design is ready. Start the build with:
  /speckit-build
```

Nothing is pushed. The build is started by the person, in a new session in the same checkout, so
it reads only what this run wrote.

## Repairing one stage

Each stage is still its own command (`/speckit-specify`, `/speckit-clarify`, `/speckit-plan`,
`/speckit-tasks`, `/speckit-analyze`). Run one alone to repair it, or resume here with `--from`.
