# Design, then build

> Build a feature on ankka by describing it, answering the design's questions, and approving the pull request the build raises; what each half produces and where you are asked.

Source: https://docs.ankka.cloud/get-started/design-and-build/
A feature on ankka is built in two commands, typed in Claude Code in the service's repository. You
describe the feature to `/speckit-design`, which asks you what it needs to know and writes the
specification, the scenarios, the plan and the work list. Then `/speckit-build` writes the code and
its tests, has it reviewed, and raises a pull request for you to approve. This is how you build with
ankka: you decide what the service does, and the coding agent, holding ankka's documentation as
skills, writes it. The skills and the MCP server that give the agent ankka's documentation and its
hands are described in [Work with a coding agent](coding-agents.md).

## Describe the feature

Make a branch for the feature, then start the design with a description:

```text
git switch -c 004-recurring-reminders
/speckit-design A customer can ask to be reminded of an unpaid order once a day until it is paid or cancelled
```

A good description says who does what, what they get, and what is out of scope. It does not need to
be complete: anything it leaves open becomes a question. Typed with no description, the design asks
for one and writes nothing until it has it. On the repository's default branch it refuses and says
which branch to make.

## What the design produces

The design writes, and commits stage by stage:

- **The change's directory**, `specs/NNN-name/`: `spec.md` (what the feature does and why, its
  requirements and how success is measured), `plan.md` and `research.md` (how it is built and the
  decisions behind it), `data-model.md`, `contracts/` and `quickstart.md` where the feature has them,
  and `tasks.md` (the work, in order, each item a commit).
- **The living features**, under `features/`: Gherkin scenarios describing what the service does
  now. A spec does not repeat its scenarios; it names the ones it adds, changes or removes, so each
  is written once and the features stay the single statement of behaviour. A Scala service runs
  them as its tests.
- **The glossary**, `GLOSSARY.md`: every word a scenario uses, each in exactly one sense, with the
  synonyms it refuses.

A checker holds the three together: an undefined word, a refused synonym, a contradiction between
two scenarios, or a requirement no scenario shows. The design does not plan until the checker
reports nothing, and ends by printing the command that starts the build.

## Where you are asked

The design asks you:

- each clarification the specification raises, as options with what each implies;
- the clarification questions, one at a time, each answer written into the specification before
  the next;
- each checker finding that is a question about behaviour, and whether each new glossary word is
  right;
- any change the final analysis proposes that would alter a decision you made or a scenario.

You are not asked about the plan, the work list, or a change the analysis proposes that alters
neither a decision nor a scenario: those are applied, and the analysis runs again until nothing of
consequence remains. Every question arrives as a choice in Claude Code, and the design carries on
when you answer. It needs an interactive session for that: in a headless run it stops before doing
anything.

## Build it

```text
/speckit-build
```

The build reads only what the design wrote, so it can run in a fresh session. It implements the
work list item by item, committing each and going on without stopping to report. Then a reviewer
with a fresh context, which knows the specification and the plan but nothing of how the code was
written, reviews the branch, and every finding is fixed or answered in the change's `review.md`.
The build raises a draft pull request on the right base (the default branch, or the unmerged branch
this one was cut from), waits for every check to finish, and leaves a pull request marked ready for
review when each check is green, or red only in the way the same job also fails on the default
branch, which the pull request then says with that run as evidence.

It stops for you only on a decision the design left open, a change to a contract, a check it cannot
make pass, or a blocking review finding it disagrees with. It never merges, tags, publishes or
deploys: you review, approve and merge. Typed again after a run was cut short, it reads where the
branch stands and carries on from there.

## Two models

The design runs on the deeper-thinking model and the build on a cheaper one. Every decision is made
in the design, from the description to the analysed work list, so that is where the stronger model
pays; once everything is decided, writing the code is work a cheaper model does well, at a fraction
of the cost.

Each command's model is the `model:` line in its skill's frontmatter,
`.claude/skills/speckit-design/SKILL.md` and `.claude/skills/speckit-build/SKILL.md`, which Claude
Code applies for as long as the command runs, whatever model the session started on. A project may
change either line; nothing else names the models.

## Install

A service made from the ankka template does not yet carry design and build. In it, as in any other
project, install it with these steps, from the project's root:

1. Install spec-kit and initialise it for Claude Code:

   ```text
   uv tool install specify-cli --from git+https://github.com/github/spec-kit.git@v0.14.1
   specify init --here --ai claude --ai-skills
   ```

2. Add [speckit-bdd](https://github.com/thinkmorestupidless/speckit-bdd), which keeps the living
   features and the glossary, from its release, then copy its configuration into place:

   ```text
   specify extension add bdd --from https://github.com/thinkmorestupidless/speckit-bdd/releases/download/v0.2.0/speckit-bdd-extension-0.2.0.zip
   specify preset add bdd --from https://github.com/thinkmorestupidless/speckit-bdd/releases/download/v0.2.0/speckit-bdd-preset-0.2.0.zip
   cp .specify/extensions/bdd/config-template.yml .specify/extensions/bdd/bdd-config.yml
   ```

   Create `GLOSSARY.md` with a `# Glossary` heading and an empty `## Everyday words` section, and a
   `features/` directory.

3. Copy the `review` and `design-build` extensions and their skills from the ankka repository, at
   the same paths:

   ```text
   git clone --depth 1 https://github.com/thinkmorestupidless/ankka /tmp/ankka
   cp -R /tmp/ankka/.specify/extensions/review /tmp/ankka/.specify/extensions/design-build .specify/extensions/
   cp -R /tmp/ankka/.claude/skills/speckit-review /tmp/ankka/.claude/skills/speckit-design /tmp/ankka/.claude/skills/speckit-build .claude/skills/
   ```

   They are copied rather than added with `specify extension add`, which would write the skills
   without their `model:` lines.

4. Register both in `.specify/extensions.yml`, keeping everything `bdd` wrote there. Add two lines
   under `installed:`, after `- bdd`:

   ```yaml
   - review
   - design-build
   ```

   and, under `hooks:`, beside the two hooks `bdd` registered, the review after implementing:

   ```yaml
     after_implement:
     - extension: review
       command: speckit.review
       enabled: true
       optional: false
   ```

5. Install the reviewer the build uses, once per machine, in Claude Code:

   ```text
   /plugin marketplace add thinkmorestupidless/ankka-marketplace
   /plugin install pr-review@ankka
   ```

The build also needs `gh`, logged in, for the pull request and its checks.

## Repairing one stage

Every stage is still a command of its own: `/speckit-specify`, `/speckit-clarify`, `/speckit-plan`,
`/speckit-tasks`, `/speckit-analyze`, `/speckit-implement` and `/speckit-review`. Run one alone to
redo that stage. `/speckit-design --from <stage>` resumes a design at `clarify`, `check`, `plan`,
`tasks` or `analyze`, leaving what the earlier stages wrote as it was; `/speckit-build` resumes from
whatever the branch holds.
