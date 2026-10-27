# Quickstart: proving design and build

What shows the feature works, in the order it is cheapest to run.

## Prerequisites

- The worktree on `061-design-and-build` with the implementation committed.
- `uv` on `PATH` (the checker), `gh` logged in, Docker not needed.
- The `pr-review` plugin in the session: `/plugin install pr-review@ankka`, or
  `claude --plugin-dir <ankka>/marketplace/plugins/pr-review`.

## 1. The structure: extension, skills, registration

```bash
sbt 'testkit/testOnly *DesignAndBuildExtensionSuite'
```

Green means: the manifest requires `bdd` and `review`; `design-build` is in `extensions.yml` with
no hooks; both SKILL.md files exist, each with a `model:` line, and the two differ; nothing else in
the extension or the skill directories names a model; each command file's body equals its skill's
body. Break it once: change `model: opus` to `model: fable` in the build skill and watch the
"different models" test go red.

## 2. The page

```bash
just docs                                   # every page's rules, the nav, the skill pages lists
sbt 'testkit/testOnly *DesignAndBuildDocumentationSuite'
```

Green means the page is in `nav` and the orientation skill, stands alone, and says each statement
`features/documentation/design-and-build.feature` requires, naming the skills by their frontmatter
names. Break it once: rename the page's `/speckit-build` to `/speckit-ship` and watch the names test
go red.

## 3. The living features and the glossary

```bash
just features
```

Expected: `0 findings in N scenarios and 62 specs` (the five new feature files included).

## 4. The design, end to end

In a throwaway spec-kit repository (or a scratch branch of ankka), from a session started on a model
other than fable (`claude --model opus`):

```text
/speckit-design A timed greeting: a service that says hello to each registered name once a day
```

Expected: the turn runs on fable (the status line); each clarification arrives as an AskUserQuestion
prompt, never as a message that ends the turn; the run ends with `spec.md`, `plan.md`, `tasks.md`,
scenarios under `features/`, `just features` green, an analyze summary with nothing above low, and
the line `The design is ready. Start the build with: /speckit-build`. Then:

```text
/speckit-design --from tasks
```

Expected: only tasks and analyze run; `spec.md` and `plan.md` are byte-identical to before.

## 5. The build, end to end: this feature's own branch

Once every task of `specs/061-design-and-build/tasks.md` is implemented and committed, in the
worktree, in a fresh session:

```text
/speckit-build
```

Expected, in order: the build refuses nothing (tasks ticked, `review` registered, `pr-review`
listed); the review runs through implement's hook or `/speckit-review`; `review.md` exists with a
resolution under every finding; a draft pull request on `main` titled `feat(061): design and build`;
the build polls the checks; it marks the pull request ready when every check is green, or stops and
says which check it could not make green. The pull request body records the stages the build ran,
which is the proof `features/design-and-build/build.feature` asks for.

## 6. The siblings

For each repository in research R11, on its `design-and-build` branch:

```bash
specify extension list          # bdd, review, design-build
just check                      # or the repository's checker line: 0 findings
```

and in a session there, `/speckit-design` with no description asks for one and stops.
