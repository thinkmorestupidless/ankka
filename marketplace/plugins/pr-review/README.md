# pr-review

Code review as Claude Code skills. One skill reviews a pull request, a branch, a commit range or
the working tree against a general checklist and against the repository's own rules; one skill per
language carries that language's checklist. It needs nothing from ankka.

```text
/plugin marketplace add thinkmorestupidless/ankka-marketplace
/plugin install pr-review@ankka
```

Before a release carries it, or to try a change to it, load it from a checkout for one session:
`claude --plugin-dir <ankka checkout>/marketplace/plugins/pr-review`.

## Using it

```text
/pr-review 123             a GitHub pull request (needs gh, logged in)
/pr-review feature/x       a branch, against the repository's default branch
/pr-review main..HEAD      a commit range
/pr-review --working-tree  staged and unstaged changes
```

Asking Claude to review, check or critique a change does the same. The review reads the
repository's instructions and rule files first, judges whether the change should exist, reads every
hand-written line against the general checklist (correctness, design and scope, error handling,
concurrency, security, data and migrations, performance, tests, observability, API compatibility,
dependencies, documentation, commit hygiene), loads the checklist of each language the diff touches
(Scala, Java, Kotlin, Go, Python, Rust, TypeScript), confirms each finding in the surrounding code,
and reports findings labelled by severity (blocking, should fix, nit, question, praise) with a
verdict: Request changes, Approve with comments, or Approve. Nothing is posted anywhere unless you
ask; `/pr-review 123 and post it` posts inline comments on the pull request as a `COMMENT` review.

Each skill's `SKILL.md` is the checklist in use; `references/` holds every item with its concrete
diff signal, its reason and its source.

## Shaping the review for your repository

Three layers, each beating the one below and all of them beating the bundled checklists. None needs
a fork of this plugin.

### 1. Your instruction and rule files

`CLAUDE.md`, `AGENTS.md`, `CONTRIBUTING.md` and `.claude/rules/*.md` are read before the code, and a
rule they state overrules any item in the skills. Formatter and linter configuration is read too,
and what it enforces is never commented on. A pull request template, a `commit-msg` hook or a
commitlint configuration is read as a convention the description, the title and the commits are held
to. Nothing to configure.

### 2. `REVIEW.md`: your own review checklist

A `REVIEW.md` at the repository root is applied as your checklist beside the general one; a finding
it predicts cites it. A `REVIEW.md` in a subdirectory applies to changes under that directory (a
`db/REVIEW.md` for schema rules). Write it for whoever reviews: a person, this plugin, or a hosted
review service that reads the same file.

An optional `## pr-review` section says what of this plugin not to use, or what to use as well:

```markdown
# Review checklist

- Every public handler is total: no `match` without a default, no `.get` on an `Option`.
- A migration is two deploys: add and backfill first, make it required later.

## pr-review
- skip: pr-review-typescript            # our TypeScript rules are the ones above
- skip: general/Commit and PR hygiene    # commit messages are generated here
- load: acme-review-rules               # a project or personal skill, loaded as well
```

`skip:` takes a bundled language skill's name or `general/<group>` with a group name from the list
above. `load:` takes the name of any skill in `.claude/skills/` or `~/.claude/skills/`. Lines outside
that section are checklist items like any other.

### 3. A skill of your own in place of a bundled one

A project skill (`.claude/skills/pr-review-<language>/SKILL.md`) or a personal skill
(`~/.claude/skills/pr-review-<language>/SKILL.md`) named like a bundled language skill replaces it
for that language. Keep the bundled skill's shape so the orchestrating skill's steps still apply:
how to detect the dialect or version, what the repository's tooling already enforces, the checklist
ordered by harm, and severity hints. Copy the bundled `SKILL.md` as the starting point.

A repository that wants a different procedure altogether writes its own orchestrating skill and does
not install this plugin; the language skills can still be loaded from it by name.

## Reviewing before the pull request

A review is cheapest before the push: the author still has the context, nothing waits on CI or on a
bot, and a pull request raised afterwards already carries a review. Repositories built with
[spec-kit](https://github.com/github/spec-kit) can run this plugin as a hook at the end of
`/speckit-implement`. The [ankka repository](https://github.com/thinkmorestupidless/ankka) carries
the hook as a spec-kit extension, in three pieces to copy:

- `.specify/extensions/review/`: the extension manifest and the `speckit.review` command;
- `.claude/skills/speckit-review/SKILL.md`: the same command as a skill;
- an `after_implement` entry in `.specify/extensions.yml` naming `speckit.review`, with `review` in
  its `installed` list.

The command spawns a reviewer with a fresh context (a subagent given the spec, the plan and the
branch, and nothing of the session that wrote the code), has the findings fixed in commits grouped
by concern or declined with a reason, records each resolution in `review.md` beside the spec, allows
one re-review of the fixes, and prints three lines for the pull request body.

To review with something else, point the hook entry's `command` at your own command or skill; the
command file is the shape to copy. To review nothing, set `enabled: false` on the entry. To keep the
reviewer and change its rules, use the three layers above; the hook needs no change.

## Licence

Apache-2.0, with the rest of the ankka repository, where this plugin is maintained under
`marketplace/plugins/pr-review/`. The marketplace repository is generated on each release; changes
go to the ankka repository.
