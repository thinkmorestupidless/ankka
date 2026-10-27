# Contract: the stages

The names `/speckit-design --from <stage>` accepts, what each needs before it runs, what it runs,
and what it leaves. The build has no `--from`; its state is derived (data-model.md).

| Stage | Needs | Runs | Produces | Hooks that fire inside it |
|---|---|---|---|---|
| `specify` | a description; a branch that is not the default | `/speckit-specify <description>`; then each `NEEDS CLARIFICATION` the spec raises, through AskUserQuestion | `specs/NNN-name/spec.md`, `checklists/requirements.md`, `.specify/feature.json`, scenarios in `features/`, terms in `GLOSSARY.md` | `after_specify` → `speckit.bdd.features` |
| `clarify` | `spec.md` | `/speckit-clarify`, its questions one at a time through AskUserQuestion | `## Clarifications` in `spec.md`; `*Proposed.*` terms settled | `before_clarify` → `speckit.bdd.check` |
| `check` | `features/`, `GLOSSARY.md` | the repository's checker recipe (`just features` where it exists, else `just check`, else the `uvx` line from `bdd-config.yml`) | nothing when green; each finding settled as clarify settles one | none |
| `plan` | `spec.md` with no `NEEDS CLARIFICATION`; the checker green | `/speckit-plan` | `plan.md`, `research.md`, `data-model.md`, `contracts/`, `quickstart.md` | `before_plan`, `after_plan` if any repository registers them |
| `tasks` | `plan.md` | `/speckit-tasks` | `tasks.md` | `before_tasks`, `after_tasks` if any |
| `analyze` | `tasks.md` | `/speckit-analyze`; remediations applied; repeated; at most three rounds | edits to `spec.md`, `plan.md`, `tasks.md`; `## Declined remediations` in `research.md`; a last report with nothing above low | none |
| `implement` | `tasks.md`; `review` installed; the `pr-review` skill listed | `/speckit-implement` | commits, ticks in `tasks.md`, `review.md` | `after_implement` → `speckit.review` |
| `pull-request` | every task ticked; `review.md`; a clean tree | `gh pr create --draft`; `gh pr checks --watch --fail-fast`; `gh pr ready` | the pull request, ready when green | none |

Rules:

- A stage's hooks fire exactly as when the stage is run alone, because the stage is the same
  command invoked the same way.
- `--from <stage>` with the stage's `needs` missing: the design says which earlier stage produces
  them and stops without writing.
- `--from` never deletes or rewrites what an earlier stage produced; `specify` re-run on an existing
  feature directory is refused (the person edits the spec by hand or runs `clarify`).
- The order is fixed; a later stage never runs before an earlier one is satisfied.
