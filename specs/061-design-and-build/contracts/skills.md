# Contract: the two skills

## `/speckit-design`

Frontmatter of `.claude/skills/speckit-design/SKILL.md`:

```yaml
---
name: speckit-design
description: Design a feature in one run on the design model: specify, its clarifications, clarify, the checker, plan, tasks, and analyze with every remediation applied; asks the person only what they must decide, through AskUserQuestion, and ends by printing the build command.
model: fable
argument-hint: "<feature description>, or --from <stage>"
compatibility: Requires spec-kit project structure with .specify/ directory, the bdd and review extensions
metadata:
  author: thinkmorestupidless
  source: design-build:commands/speckit.design.md
---
```

Procedure (the body; the command file carries the same):

1. **Input**: `$ARGUMENTS`. `--from <stage>` names a stage of `contracts/stages.md`; anything else
   is the feature description. No description and no `--from`: ask for the description through
   AskUserQuestion (one option, "Describe the feature", with "Other" for the text) and stop if none
   is given.
2. **Refuse early**: on the default branch (`git symbolic-ref --short refs/remotes/origin/HEAD`
   without its `origin/`, or `main`),
   say which branch to make and stop; `bdd` or `review` missing from `installed` in
   `.specify/extensions.yml`, say which and stop; `--from` naming a stage whose `needs` do not
   exist, say which earlier stage must run and stop.
3. **The question rule**, stated once and held for every stage: wherever a stage's instructions say
   to present a question, a table of options or a `NEEDS CLARIFICATION` and wait for the person,
   ask through AskUserQuestion with the stage's options and implications as the options and
   continue the stage with the answer. Never end the turn to ask.
4. **Stages**, from `specify` or `--from`, each as `contracts/stages.md` says: `specify` (invoke
   `/speckit-specify <description>`; its hook writes the features; resolve its clarifications),
   `clarify` (invoke `/speckit-clarify`; its hook runs the checker first), `check` (run the
   repository's checker recipe; a finding is settled as clarify settles one), `plan`
   (`/speckit-plan`), `tasks` (`/speckit-tasks`), `analyze` (the loop of research R6: invoke
   `/speckit-analyze`; apply each remediation that changes neither a Clarifications decision nor a
   scenario; put the others to the person; record declines under `## Declined remediations` in
   `research.md`; a scenario change goes through the feature file and the checker; run again; stop
   when nothing above low remains or after three rounds, showing what remains).
5. **Commit** after each stage that wrote files, Conventional Commits with the spec number as scope
   (`docs(NNN): specify …`, `clarify …`, `plan …`, `tasks …`, `analyze …`).
6. **End**: print the feature directory, the tick count (0 of N), the checker's result, the last
   analyze summary, and exactly:

   ```text
   The design is ready. Start the build with:
     /speckit-build
   ```

## `/speckit-build`

Frontmatter of `.claude/skills/speckit-build/SKILL.md`:

```yaml
---
name: speckit-build
description: Build a designed feature in one run on the build model: implement every task, the review before the pull request, the draft pull request on the right base, the wait for its checks, and marking it ready; stops only for what the design left open.
model: opus
argument-hint: "optional --base <branch>"
compatibility: Requires spec-kit project structure with .specify/ directory, the bdd and review extensions, the pr-review skill
metadata:
  author: thinkmorestupidless
  source: design-build:commands/speckit.build.md
---
```

Procedure:

1. **Read**: `.specify/feature.json`, the feature directory's `plan.md` and `tasks.md`. Nothing from
   any earlier conversation is assumed.
2. **Refuse early**: no `tasks.md`, say "the design is not finished; run `/speckit-design --from
   tasks`" (or the earliest missing stage) and stop; `review` not in `installed`, stop; the
   `pr-review` skill not listed (`pr-review:pr-review` or `pr-review`), print the install lines from
   `speckit-review` and stop; the tree dirty when the state is not `implementing`, say what is
   uncommitted and stop.
3. **State** (data-model.md): derive it from the tree and `gh pr view --json isDraft,statusCheckRollup`
   and start there.
4. **Implementing**: invoke `/speckit-implement`. Commit each task, print one line per task, go on;
   its `after_implement` hook runs the review. Stop only for: a decision the spec and plan left
   open (ask through AskUserQuestion with options and implications, record the answer in
   `research.md` under "Found during implementation", go on), a change that would alter a contract
   in `contracts/` (ask), a task whose check cannot be made to pass (stop with the failure), a
   blocking review finding the implementer disagrees with (stop, show it).
5. **Reviewing** (when implement ran without its hook, or `review.md` has a finding with no
   resolution line under it): invoke `/speckit-review`.
6. **Raising**: base = `--base`, else the nearest unmerged feature branch by `git merge-base`, else
   `main`. Title `<type>(<NNN>): <description>` from the spec's name; body written from
   `.github/pull_request_template.md`'s five sections, the three review lines under "How it is
   tested", the dependency on an unmerged base in the first line; `gh pr create --draft`.
7. **Watching**: wait up to five minutes for a check to be registered (none → the repository runs
   none; say so and mark ready), then `gh pr checks --watch` without `--fail-fast` until every check
   has finished. Each red check → `gh run view --log-failed`; fix on the branch, commit, push, watch
   again; or show it is a flake of the default branch (the same job failing on a `main` run, cited
   by run id in the PR body). Every check green or a shown flake → `gh pr ready` and the report; a
   red check neither fixed nor shown → stop, PR still a draft, say which and what was tried. Only
   an `OPEN` pull request is watched; a merged or closed one is reported and the run stops.
8. **End**: print the pull request URL, its state (ready or draft and why), the tick count, the
   three review lines, and what the person does next (approve and merge). Never merge, tag,
   publish or deploy.
