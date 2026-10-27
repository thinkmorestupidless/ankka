# Design, then build

A spec-kit extension of two commands that run the whole feature workflow:

- **`/speckit-design <description>`** takes a feature from a description to an analysed task list.
  It runs `/speckit-specify`, puts the spec's clarifications to you, runs `/speckit-clarify`, holds
  the living features and the glossary to the checker, runs `/speckit-plan` and `/speckit-tasks`,
  then `/speckit-analyze`, applying every remediation that changes no decision and no scenario and
  running it again until nothing above low remains. It ends by printing the command that starts the
  build.
- **`/speckit-build`** takes the task list to a pull request ready for review. It runs
  `/speckit-implement`, task by task, committed as it goes; the review before the pull request
  (`/speckit-review`, a reviewer with a fresh context); the draft pull request on the right base;
  the wait for its checks; and marks it ready when they are green. It never merges, tags, publishes
  or deploys.

## Where you are asked

The design asks you each clarification the spec raises, clarify's questions one at a time, each
checker finding that is a question, whether the proposed glossary terms stand, and every
remediation that would change a decision or a scenario. It does not ask about the plan, the tasks
or a remediation that changes neither.

The build asks only about a decision the spec and plan left open, a change to a contract, a check it
cannot make pass, or a blocking review finding it disagrees with.

Every question goes through Claude Code's question tool, which keeps the run in one turn.

## Two models

Each command runs on the model named by the `model:` line in its skill's frontmatter, which Claude
Code applies for the turn the command is invoked in:

| Command | Line | As shipped |
|---|---|---|
| `/speckit-design` | `.claude/skills/speckit-design/SKILL.md` | `fable` |
| `/speckit-build` | `.claude/skills/speckit-build/SKILL.md` | `opus` |

They differ on purpose: the deeper-thinking model makes every decision, from the description to the
analysed tasks, and a cheaper one writes the code once everything is decided. Those two lines are
the only record of the choice. To change either, edit its line; nothing else in this extension
names a model.

## What it needs

- spec-kit 0.14 or later, initialised with the Claude integration and skills.
- The `bdd` extension: the living features, the glossary and the checker the design writes and runs.
- The `review` extension: the review the build runs before the pull request.
- The `pr-review` plugin in the session, for the review's reviewer:

  ```text
  /plugin marketplace add thinkmorestupidless/ankka-marketplace
  /plugin install pr-review@ankka
  ```

- `gh`, logged in, for the pull request and its checks.

## Installing

Copy, from the ankka repository, these to the same paths in your repository:

```text
.specify/extensions/design-build/
.claude/skills/speckit-design/
.claude/skills/speckit-build/
```

and add `design-build` under `installed:` in `.specify/extensions.yml`. It registers no hook.

Do not install it with `specify extension add`: spec-kit renders an extension's commands into
skills with a fixed set of frontmatter keys, and the `model:` line is not one of them, so both
commands would run on whatever model the session is on.

The command files under `commands/` and the skills hold the same text below their frontmatter.
Change the command file, then copy its body into the skill.

## Repairing one stage

Every stage is still its own command: `/speckit-specify`, `/speckit-clarify`, `/speckit-plan`,
`/speckit-tasks`, `/speckit-analyze`, `/speckit-implement` and `/speckit-review`. Run one alone to
repair it. `/speckit-design --from <stage>` resumes a design at `clarify`, `check`, `plan`, `tasks` or
`analyze`; `/speckit-build` resumes wherever the tree says it stands.
