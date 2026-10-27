---
description: "Build a designed feature: implement, review and the pull request in one run on the build model"
argument-hint: "optional --base <branch>"
---

# Build a designed feature

One run takes a designed feature from its task list to a pull request marked ready for review: every
task implemented and committed, the branch reviewed with a fresh context and the findings acted on,
the pull request raised as a draft on the right base, its checks watched until they finish, and the
pull request marked ready when they are green. The person approves and merges; this command never
does.

This command runs on the model its skill's `model:` line names, for as long as this turn lasts. It
asks the person only what the design left open, through AskUserQuestion, so the turn and the model
hold.

## User input

```text
$ARGUMENTS
```

- `--base <branch>`: the branch the pull request targets, when the rule below would pick the wrong
  one.
- Nothing else is read from the input.

## What it reads

`.specify/feature.json` names the feature directory. From it: `spec.md`, `plan.md`, `tasks.md`,
`research.md`, `data-model.md`, `contracts/`, `quickstart.md`, and `review.md` when it exists. Nothing
from any earlier conversation is assumed: what the design decided is in those files, and what is not
in them was not decided.

## Before anything runs

Stop, having changed nothing, and say why, when:

1. **There is no `tasks.md`** in the feature directory, or no feature directory. Say the design is
   not finished and name the command that finishes it: `/speckit-design --from <stage>`, with the
   earliest stage whose output is missing (`specify`, `plan` or `tasks`).
2. **`review` is not installed**: it is not listed under `installed:` in `.specify/extensions.yml`.
   The build never raises a pull request that carries no review.
3. **The reviewer's skill is not available**: neither `pr-review:pr-review` nor `pr-review` is among
   the skills this session lists. Print, and stop:

   ```text
   /plugin marketplace add thinkmorestupidless/ankka-marketplace
   /plugin install pr-review@ankka
   ```

   This is checked here, before implement, so a missing plugin costs nothing; the review's own check
   stays as a second guard.
4. **The tree is not clean** and the state below is anything but implementing: say what is
   uncommitted. During implementing, uncommitted changes are the task in hand.

## Where to start

The state is read from the tree every time the command starts, never remembered, which is what
makes a run that was cut short resumable by typing the command again:

| What the tree and GitHub say | State | What runs |
|---|---|---|
| `tasks.md` has a task not marked `[X]` | implementing | `/speckit-implement`, whose `after_implement` hook reviews |
| every task `[X]`, no `review.md` in the feature directory | reviewing | `/speckit-review` |
| every task `[X]`, `review.md` present, no pull request for this branch | raising | the pull request |
| a pull request for this branch exists | watching | the checks |

Whether a pull request exists: `gh pr view --json number,isDraft,url,baseRefName` on the branch,
which fails when there is none.

## Implementing

Invoke `/speckit-implement`. Go from task to task: mark each `[X]`, commit it (Conventional
Commits, the feature's number as scope, the repository's hooks honoured), print one line of progress,
and start the next. Never stop to report between tasks.

Stop for the person only on these, each asked through AskUserQuestion with options and their
implications where options exist:

- **A decision the spec and the plan left open.** Record the answer in `research.md` under
  `## Found during implementation`, then carry on from the same task.
- **A change that would alter a contract** in the feature directory's `contracts/`. Ask before
  writing it.
- **A task whose check cannot be made to pass.** Stop at that task, showing the failure and what was
  tried; no pull request is raised.
- **A blocking review finding you disagree with.** Stop, show the finding and the reason; no pull
  request is raised.

When implement's `after_implement` hook runs `/speckit-review`, the review is done there. When
implement ends without it (the hook disabled, or implement run alone earlier), the next start finds
the reviewing state.

## Reviewing

Invoke `/speckit-review`. It spawns the reviewer with a fresh context, acts on every finding, writes
`review.md`, commits, and prints three report lines. Keep those lines for the pull request body.

## Raising the pull request

1. **The base.** `--base` when given. Otherwise the nearest unmerged feature branch this branch was
   cut from: for each open pull request's head branch (`gh pr list --state open --json headRefName`),
   one whose head commit is an ancestor of this branch (`git merge-base --is-ancestor`) and is not on
   the default branch; the one with the most recent merge base wins. Otherwise the default branch.
2. **Push** the branch: `git push -u origin HEAD`.
3. **The title**: a Conventional Commits header under 72 characters, lower case, no trailing
   period: `feat(NNN): <what the feature adds>` (`fix` or `docs` when that is what it is), `NNN` the
   feature's number.
4. **The body**: written from the repository's `.github/pull_request_template.md` (its sections, its
   word limit; never pass the template file itself as the body). Under the testing section, the
   commands run and their results, and the three review lines. When the base is not the default
   branch, the first line says which pull request this waits on. End with the attribution line the
   session gives for pull requests.
5. `gh pr create --draft --base <base> --title <title> --body-file <file>`, the file written to a
   scratch location, not the repository.

## Watching the checks

`gh pr checks --watch --fail-fast` waits until the checks finish, with no bound of its own. Then:

- **Every check green**: `gh pr ready`, and the run ends.
- **A check red**: read it (`gh run view <run id> --log-failed`). Then either
  - **fix it on the branch**: commit, push, and watch again; or
  - **show it is a flake of the default branch**: the same job failing the same way on a recent run
    of the same workflow there (`gh run list --branch <default> --workflow <workflow>`, then
    `gh run view`). Say so in the pull request body, citing that run, and count the check as passed.
    A red check without that evidence is the branch's.
- **A check that cannot be made green**: stop. The pull request stays a draft; say which check, what
  it reported, and what was tried.

A run cut short while watching is resumed by typing the command again: it finds the pull request
and watches its checks.

## The end

Print: the pull request's URL; whether it is ready, or a draft and why; the task count (`N of N`);
the three review lines; and that the person's next step is to review, approve and merge.

Never merge, tag, publish or deploy, and never mark the pull request ready while a check is red or
still running.

## Repairing one stage

`/speckit-implement` and `/speckit-review` are still their own commands. Run one alone to repair a
stage; the next `/speckit-build` reads the tree and starts from wherever it now stands.
