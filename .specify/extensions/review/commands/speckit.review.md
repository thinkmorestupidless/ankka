---
description: "Review the implemented branch before its pull request, with a fresh context, and act on the findings"
---

# Review the branch before the pull request

The code `/speckit-implement` wrote is reviewed here, in the worktree, before anything is pushed: by
a reviewer with a fresh context, against the repository's own rules and the `pr-review` checklists.
Every finding is fixed or answered, the fixes are committed, and the outcome is written down for the
pull request. A pull request raised after this already carries a review, so the time between push
and merge is CI's alone.

The reviewer is not this session. A session that wrote the code knows what it meant and reads that
instead of what is there; so the review runs in a subagent that is given the spec, the plan and the
branch, and nothing of this conversation.

## User input

```text
$ARGUMENTS
```

Consider the user input before proceeding, if it is not empty. `--base <branch>` names the base when
the rule below would pick the wrong one. `--skip <reason>` records in the completion report that the
review was skipped and why, and stops here.

## Setup

1. Run `.specify/scripts/bash/check-prerequisites.sh --json` from the repository root and take
   `FEATURE_DIR` from it. All paths absolute.
2. **The branch must be committed.** `git status --porcelain` must print nothing. If it does, stop
   and say what is uncommitted: the review reads commits, and the pull request will carry only
   commits, so an uncommitted change is either unfinished work or a stray file, and either is the
   implementer's to settle first.
3. **The base** is the branch the pull request will target: `main`, unless this branch was cut from
   a feature branch that is not yet merged, in which case that branch. Take `--base` when given.
   `git merge-base <base> HEAD` must resolve, and `<base>...HEAD` must not be empty; an empty range
   means there is nothing to review, so say so and stop.
4. **The reviewer must exist.** The `pr-review` skill is listed as `pr-review:pr-review` when the
   plugin is installed, or as `pr-review` from a project or personal skill directory. When neither
   is listed, stop and say how to install it:

   ```text
   /plugin marketplace add thinkmorestupidless/ankka-marketplace
   /plugin install pr-review@ankka
   ```

   or, before a release carries it, start the session with
   `claude --plugin-dir <ankka checkout>/marketplace/plugins/pr-review`. Do not substitute a lesser
   review silently: a hook that reports a review it did not run is worse than no hook.

## The review

Spawn one subagent (the Agent tool, a general-purpose agent, a fresh context) with this brief, the
placeholders filled in:

```text
Load the skill pr-review (listed as pr-review:pr-review when installed as a plugin) and follow it
to review the commit range <base>...HEAD in the repository at <worktree path>. You are the reviewer,
not the author: nothing of the implementation session is available to you, on purpose. The change
has no pull request yet; its description is <FEATURE_DIR>/spec.md and <FEATURE_DIR>/plan.md, and
<FEATURE_DIR>/tasks.md says what was meant to be done. Review what the diff does against what they
say, as well as against the skill's checklists. Write the report, in the skill's template, to
<FEATURE_DIR>/review.md, and reply with the verdict line and the number of findings at each
severity. Post nothing anywhere.
```

Wait for it. Do not review in parallel yourself, and do not answer its findings before reading them.

## Acting on the findings

Read `review.md`. Then, in this order:

1. **Blocking.** Fix every one. If a blocking finding is wrong in your judgement, do not argue it
   away: stop, show the finding and your reason, and let the user decide.
2. **Should fix.** Fix each, or decline it with a reason that would satisfy the reviewer. "Out of
   scope" is a reason only when the spec says so.
3. **Questions.** Answer each under the finding. An answer that amounts to "yes, that is a defect"
   becomes a fix.
4. **Nits.** Fix when a minute's work; otherwise leave them, noted.
5. **Out of scope.** Change nothing; leave the note for the pull request.

Fixes are commits, grouped by concern (the foundation first: types and shared code, then the code
that uses them, then tests), each commit's message naming what it resolves. Never one commit for
everything; never a fix without its test when the finding was about behaviour. Run the checks
implement ran on the files the fixes touch, and the formatter.

Under each finding in `review.md`, append one line: `Resolved: <what changed>, <short sha>`,
`Declined: <reason>`, `Answered: <answer>` or `Left: nit`. Commit `review.md` with the last fix, or
alone when nothing was fixed; the message is "Review before the pull request: N findings, M fixed".

**One more round.** When any blocking finding was fixed, spawn the reviewer again on the fix commits
only (`<sha before the fixes>...HEAD`) with the same brief plus: "This is a second round. The
previous report is at <FEATURE_DIR>/review.md; review the fixes, and say for each resolution whether
it holds." Act on what it finds the same way, once. A third round is a conversation for the user:
stop and show both reports.

## Report

Print, for the implement session's completion report and for the pull request body:

```text
Review before push: pr-review on <base>...HEAD, <verdict after the fixes>.
Found: B blocking, S should-fix, N nits, Q questions. Fixed: F in K commits. Declined: D (reasons in review.md). Left: L nits.
Report: <FEATURE_DIR>/review.md
```

The pull request body carries these lines under its "Checks run". The verdict there is the
reviewer's after the fixes, never the implementer's own.

## Changing or replacing this review

This hook is one entry in `.specify/extensions.yml` under `hooks.after_implement`, and its only
coupling to `pr-review` is the brief above.

- **No review:** set `enabled: false` on the entry, and say so in the pull request.
- **Another reviewer:** point the entry's `command` at your own command or skill. This file is the
  shape to copy: a fresh context, every finding acted on, the outcome recorded.
- **The same reviewer, your rules:** `pr-review` reads the repository's `CLAUDE.md` and rule files
  first and lets them overrule its checklists; applies a `REVIEW.md` as the repository's own review
  checklist, whose `## pr-review` section can skip bundled checklists or load further skills; and
  loads a project or personal skill named `pr-review-<language>` in place of the bundled one.
  Nothing in this file needs to change for any of that.

## Done when

- [ ] `review.md` in the feature directory holds the report with a resolution line under every finding
- [ ] Every blocking and should-fix finding is fixed, or declined with its reason; the fixes are committed and checked
- [ ] The tree is clean and the three report lines were printed
