# Tasks: Design and build

**Input**: Design documents from `specs/061-design-and-build/`

**Prerequisites**: plan.md, spec.md, research.md (R1 to R12), data-model.md, contracts/ (extension.md,
skills.md, stages.md, page.md), quickstart.md

**Tests**: The spec's scenarios are proven by two munit suites (research R9, R10) and by recorded
runs (quickstart §4, §5); the suite tasks are listed and are not optional.

**Organization**: Tasks are grouped by user story. Every path is relative to the repository root
unless it starts with `../`, which is a sibling checkout.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1 the design, US2 the build, US3 the models, US4 installing, US5 the page

## Phase 1: Setup

**Purpose**: the extension's skeleton, so every later task has a place to write to

- [X] T001 Create `.specify/extensions/design-build/extension.yml` exactly as `contracts/extension.md` gives it (id `design-build`, requires `bdd` and `review`, provides `speckit.design` and `speckit.build`, no hooks, no config)
- [X] T002 Register the extension: add `design-build` under `installed` in `.specify/extensions.yml`, nothing under `hooks`
- [X] T003 [P] Create the directories `.specify/extensions/design-build/commands/`, `.claude/skills/speckit-design/`, `.claude/skills/speckit-build/`

---

## Phase 2: Foundational

**Purpose**: the structural suite, written first so every later task has a check to go green

- [X] T004 Write `modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/DesignAndBuildExtensionSuite.scala` in the shape of `TimersDocumentationSuite` (find the repository root by walking up, read files, no actor system): one test per scenario of `features/design-and-build/installing.feature` and `features/design-and-build/models.feature` that a file read can prove, named after the scenario: the manifest requires `bdd` and `review`; `design-build` is in `extensions.yml`'s `installed` and absent from its `hooks`; each of the two SKILL.md files exists with a `model:` line; the two models differ; no other file under `.specify/extensions/design-build/` or the two skill directories contains a `model:` key; each command file's body (after the frontmatter) equals its skill's body; the README says why the halves differ and names both SKILL.md paths; the manifest description names `bdd` and `review`. Every test is red at this point.
- [X] T005 Run `sbt 'testkit/testOnly *DesignAndBuildExtensionSuite'` and record which tests are red (all but the registration ones) in `specs/061-design-and-build/research.md` under "Found during implementation"

---

## Phase 3: User Story 1 - A feature designed with one command (Priority: P1) 🎯 MVP

**Goal**: `/speckit-design <description>` runs specify to analyze on the design model, asking only
through AskUserQuestion, and ends by printing the build command.

**Independent Test**: quickstart §4 in a scratch repository: every question arrives as an
AskUserQuestion prompt, the turn stays on fable, the artifacts exist, `--from tasks` leaves the
spec and plan untouched.

- [ ] T006 [US1] Write the body of `.specify/extensions/design-build/commands/speckit.design.md` per `contracts/skills.md`: frontmatter `description` and `argument-hint`; sections for the input (`$ARGUMENTS`, `--from <stage>`, the ask-for-a-description case), the early refusals (default branch, `bdd` or `review` missing, `--from` whose needs are missing, per `contracts/stages.md`), and the question rule (research R4) stated once
- [ ] T007 [US1] Write the stages section of `commands/speckit.design.md`: `specify` (invoke `/speckit-specify <description>` with its hook; convert each clarification marker's option table to AskUserQuestion; write the answer into the spec), `clarify` (invoke `/speckit-clarify`; convert each question), `check` (the repository's checker recipe; a finding settled as clarify settles one), `plan`, `tasks`, and the `analyze` loop of research R6 (apply, ask, record declines under `## Declined remediations` in `research.md`, scenario changes through the feature file and the checker, three rounds at most)
- [ ] T008 [US1] Write the commit and ending sections of `commands/speckit.design.md`: a Conventional Commits commit after each stage that wrote files (`docs(NNN): specify …` and so on, the scope from the feature directory's number), and the exact closing lines `The design is ready. Start the build with:` / `  /speckit-build`, preceded by the feature directory, the tick count, the checker's result and the last analyze summary
- [ ] T009 [US1] Write `.claude/skills/speckit-design/SKILL.md`: the frontmatter from `contracts/skills.md` (`name`, `description`, `model: fable`, `argument-hint`, `compatibility`, `metadata`) and the body copied from `commands/speckit.design.md` byte for byte; run `sbt 'testkit/testOnly *DesignAndBuildExtensionSuite'` and confirm the body-equality and design-model tests are green
- [ ] T010 [US1] Prove the design end to end per quickstart §4: in a scratch spec-kit repository with `bdd`, `review` and this extension copied in, from `claude --model opus`, run `/speckit-design` with the greeting description, then `/speckit-design --from tasks`; record in `research.md` under "Found during implementation" the model the turn ran on, how each question arrived, which hooks fired, and anything the procedure had to say differently; fix the command file and the skill for what it found

---

## Phase 4: User Story 2 - A feature built with one command (Priority: P1)

**Goal**: `/speckit-build` implements, reviews, raises the pull request, waits for its checks and
marks it ready, on the build model, stopping only for what the design left open.

**Independent Test**: quickstart §5: this feature's own branch, built by the skill once its tasks are
done.

- [ ] T011 [US2] Write the body of `.specify/extensions/design-build/commands/speckit.build.md` per `contracts/skills.md`: frontmatter `description` and `argument-hint`; sections for what it reads (`.specify/feature.json`, `plan.md`, `tasks.md`, nothing from an earlier conversation), the early refusals (no `tasks.md` with the design command to run; `review` not installed; the `pr-review` skill not listed, with the install lines from `speckit-review`; a dirty tree outside the implementing state), and the state derivation of `data-model.md` from `tasks.md`, `review.md` and `gh pr view --json isDraft,statusCheckRollup`
- [ ] T012 [US2] Write the implementing, reviewing and raising sections of `commands/speckit.build.md`: invoke `/speckit-implement` (one progress line per task, commit each, go on; the four stop conditions of FR-020 each with how it is put to the person and recorded in `research.md`); `/speckit-review` only when `review.md` is absent after implement; the base rule (`--base`, else the nearest unmerged feature branch by `git merge-base`, else `main`), the title `<type>(<NNN>): <description>`, the body written from `.github/pull_request_template.md`'s five sections with the three review lines and the dependency line first when the base is not `main`, `gh pr create --draft`
- [ ] T013 [US2] Write the watching and ending sections of `commands/speckit.build.md`: `gh pr checks --watch --fail-fast`; on all green `gh pr ready` and the report; on a red check `gh run view <id> --log-failed`, then a fix on the branch committed and pushed and the watch restarted, or evidence of a flake of `main` (`gh run list --branch main --workflow <name>`, the same job failing, cited by run id in the body) that does not hold the pull request; a check that cannot be made green stops with the pull request a draft; the closing report (URL, state and why, tick count, the three review lines, "approve and merge" as the person's next step); never merge, tag, publish or deploy
- [ ] T014 [US2] Write `.claude/skills/speckit-build/SKILL.md`: the frontmatter from `contracts/skills.md` (`model: opus`) and the body copied from `commands/speckit.build.md`; run `sbt 'testkit/testOnly *DesignAndBuildExtensionSuite'` and confirm every structural test is green
- [ ] T015 [US2] Verify the `gh` behaviours in research's "Verify at implementation" list against a real draft pull request (this branch's, raised by hand as a draft if the build is not yet usable): `gh pr checks --watch --fail-fast` exit codes on red and green, `gh pr ready` on a green draft; record the findings in `research.md` and adjust T013's wording where `gh` differs

---

## Phase 5: User Story 3 - Each half on its own model, chosen per repository (Priority: P2)

**Goal**: the model lines are the only record; the reason is readable beside them.

**Independent Test**: `DesignAndBuildExtensionSuite`'s models tests; editing one line changes one
command's model.

- [ ] T016 [US3] Write `.specify/extensions/design-build/README.md` per `contracts/extension.md`: what the two commands do, the stages each runs and where each asks, that the design model is `fable` and the build model `opus` as shipped and why they differ, that the only place to change either is the `model:` line of its SKILL.md (both paths named), the install-by-copy procedure and why `specify extension add` is not used (research R2), the individual `/speckit-*` commands for repairing one stage, and that nothing merges, tags, publishes or deploys
- [ ] T017 [US3] Run `sbt 'testkit/testOnly *DesignAndBuildExtensionSuite'` and break it once: change `model: opus` to `model: fable` in the build skill, confirm the "different models" test goes red, restore it, confirm green; note the result in `research.md` under "Measurements"

---

## Phase 6: User Story 4 - The extension installed as review is (Priority: P2)

**Goal**: every family repository with spec-kit has `bdd`, `review` and design and build; the
conventions describe the workflow as two commands.

**Independent Test**: in each sibling, `specify extension list` shows the three and the checker is
green; `ankka-brain/conventions.md` has two rows.

- [ ] T018 [US4] Rewrite the "The feature workflow" section of `../ankka-brain/conventions.md`: two rows (design, build) with the stages each runs, where each asks, the models and what each produces; keep the rules that still hold (the base branch, the PR shape, CI before a human, nothing merged by an agent, speckit-bdd required) and fold into the rows the ones the commands now enforce; name the individual commands for repairing one stage; commit on `main` and push to `origin main` in the same step
- [ ] T019 [P] [US4] Check `../voice-brain/conventions.md` for a copy of the workflow table; change it the same way if it has one, commit and push; otherwise record "none" in `research.md`
- [ ] T020 [P] [US4] Install in `../voice`: branch `design-and-build`; copy `.specify/extensions/review/`, `.specify/extensions/design-build/`, `.claude/skills/speckit-review/`, `.claude/skills/speckit-design/`, `.claude/skills/speckit-build/`; add `review` and `design-build` to `installed` and the `after_implement` hook entry to `.specify/extensions.yml`; `just check` green; commit `chore(speckit): install review and design and build`; `gh pr create --draft`
- [ ] T021 [P] [US4] Install in `../ankka-flow` as T020
- [ ] T022 [P] [US4] Install in `../ankka-reasoning` as T020
- [ ] T023 [P] [US4] Install in `../ankka-predictions` as T020
- [ ] T024 [P] [US4] Install in `../ankka-projects` as T020 (no Justfile: run the checker through the `uvx` line from its `bdd-config.yml`)
- [ ] T025 [P] [US4] Install in `../ankka-for-trades` as T024
- [ ] T026 [P] [US4] Install in `../programme` as T024
- [ ] T027 [P] [US4] Install in `../ankka-for-plumbers` as T020, and add voice's one-line `check` recipe to its `Justfile`
- [ ] T028 [P] [US4] Install in `../ankka-for-plumbers-android` as T024
- [ ] T029 [P] [US4] Install in `../ankka-for-plumbers-ios` as T024
- [ ] T030 [P] [US4] Install in `~/Documents/projects/eitheror/ankka-modules` as T020, and note the change in that company's brain (`~/Documents/projects/eitheror/brain/projects/ankka-modules.md`) in one line
- [ ] T031 [P] [US4] Install in `../satisfactory`: branch `design-and-build`; `specify extension add bdd --from https://github.com/thinkmorestupidless/speckit-bdd/releases/download/v0.2.0/speckit-bdd-extension-0.2.0.zip` and `specify preset add bdd --from .../speckit-bdd-preset-0.2.0.zip`; `GLOSSARY.md` with the heading and an empty `## Everyday words`; `features/.gitkeep`; `.specify/extensions/bdd/bdd-config.yml` from the template with `specs-from` at the next spec number; voice's `check` recipe in its `Justfile`, and a CI step running the same line where the repository has a CI workflow; then `review` and `design-build` as T020; checker green; commit `chore(speckit): install bdd, review and design and build`; draft PR
- [ ] T032 [P] [US4] Install in `../ankka-vendor` as T031
- [ ] T033 [P] [US4] Install in `../ankka-replication`: first `specify init --here --ai claude --ai-skills` (verify this is the right repair: it has `.specify` but no skills, no specs, no Justfile; record what was found in `research.md`), a `Justfile` with voice's `check` recipe, then as T031
- [ ] T034 [US4] Record every sibling's branch and draft pull request URL, and the repositories without spec-kit (ankka-contrib, ankka-deployments, cloudflow, ankka-forge, forge-application, voice-website), in `specs/061-design-and-build/research.md` under "Found during implementation", for this feature's pull request body

---

## Phase 7: User Story 5 - The way to build with ankka, in the documentation (Priority: P2)

**Goal**: the page opens Get started and is proven against its feature.

**Independent Test**: `just docs` and `DesignAndBuildDocumentationSuite` green; the opening page's
first link leads to the page.

- [ ] T035 [US5] Write `modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/DesignAndBuildDocumentationSuite.scala` in the shape of `TimersDocumentationSuite`: one test per scenario of `features/documentation/design-and-build.feature` asserting the statements of `contracts/page.md`'s sections (what the design produces, what the build produces, where the person is asked and not, the two models and why, the install steps for the template and for any other project, what the spec directory, living features and glossary are), one test that `docs/index.md`'s "Where to start" first bullet links to `get-started/design-and-build.md` and that `mkdocs.yml`'s `Get started` section lists it first, and one test that every `/speckit-` name on the page is the `name:` of a SKILL.md under `.claude/skills/`; all red
- [ ] T036 [US5] Write `docs/get-started/design-and-build.md` per `contracts/page.md`: the frontmatter and the eight sections in order, standalone, no history, in the voice of `get-started/coding-agents.md`; the install section says plainly that the template does not yet carry the extension and gives the steps by hand
- [ ] T037 [US5] Add the page first under `Get started` in `mkdocs.yml`'s `nav`, add `get-started/design-and-build.md` to `tools/docs/skill/ankka/SKILL.md`'s `pages:`, and add the "Building a feature" bullet first under "Where to start" in `docs/index.md`
- [ ] T038 [US5] Run `just docs` and `just docs-sync`; commit the rendered skill copies under `marketplace/plugins/ankka/skills/ankka/` and `ankka.g8/src/main/g8/.claude/skills/ankka/` (the g8 copy with `$` escaped; `git diff` for lines not meant); run `sbt 'testkit/testOnly *DesignAndBuildDocumentationSuite'` until green
- [ ] T039 [US5] Break the documentation suite once: rename `/speckit-build` to `/speckit-ship` on the page, confirm the names test goes red, restore; note it in `research.md` under "Measurements"

---

## Phase 8: Polish

- [ ] T040 Run `python3 .github/ci-coverage.py` and `python3 .github/cluster-suites.py`; if a new file is unclaimed, extend the right filter in `.github/workflows/ci.yml` (the extension and skills under `.specify/` and `.claude/` are read by no job and may belong under `unchecked` with the reason; the two suites run in `build (testkit)`)
- [ ] T041 Run `just features`, `sbt 'testkit/testOnly *DesignAndBuild*'`, `just docs` and `sbt scalafmtCheckAll`; record the results in `research.md` under "Measurements"
- [ ] T042 Add the trap learned about spec-kit's renderer dropping `model:` (research R2) to the "Living features" section of `.claude/rules/testing.md`, which holds the spec-kit rules, dated
- [ ] T043 Fill `research.md`'s "Found during implementation" with everything the "Verify at implementation" list settled, and update the tick count and the PR body lines ready for `/speckit-build` (quickstart §5)

---

## Dependencies & Execution Order

### Phase Dependencies

- Phase 1 (T001 to T003) first; T003 parallel with T001 and T002.
- Phase 2 (T004, T005) after Phase 1; T004 can be written while T001 is written, since it reads paths only.
- Phase 3 (US1) and Phase 4 (US2) after Phase 2; they touch different files and can run in parallel, except T015, which needs a pull request to exist.
- Phase 5 (US3) after T009 and T014 (the model lines exist).
- Phase 6 (US4) after T016 (the README is copied) and T009, T014 (the skills are copied); T020 to T033 are parallel with each other; T018 and T019 any time after the design and build commands are final.
- Phase 7 (US5) after T014 (the page names both skills); T035 before T036.
- Phase 8 last; T040 after every file exists.

### User Story Dependencies

- US1 and US2 are independent of each other and of US3 to US5.
- US3 depends on US1 and US2 (the lines it describes).
- US4 depends on US1, US2 and US3 (what is copied).
- US5 depends on US1 and US2 (the names), not on US4.

### Parallel Opportunities

- T006 to T008 (design) with T011 to T013 (build).
- T020 to T033: fourteen sibling installs, each in its own checkout.
- T035 with T016; T018 with T019.

## Parallel Example: User Story 4

```text
T020 ../voice | T021 ../ankka-flow | T022 ../ankka-reasoning | T023 ../ankka-predictions
T024 ../ankka-projects | T025 ../ankka-for-trades | T026 ../programme | T027 ../ankka-for-plumbers
T028 ../ankka-for-plumbers-android | T029 ../ankka-for-plumbers-ios | T030 ankka-modules
T031 ../satisfactory | T032 ../ankka-vendor | T033 ../ankka-replication
```

## Implementation Strategy

### MVP First (User Story 1)

T001 to T010: the extension exists, the design command runs end to end in a scratch repository on
the design model. Deliverable on its own: one command replaces five.

### Incremental Delivery

1. US2 (T011 to T015): the build command; from here the branch itself can be built by the skill.
2. US3 (T016, T017): the README and the broken-once proof.
3. US5 (T035 to T039): the page, so the pull request documents what it ships.
4. US4 (T018 to T034): the conventions and the fourteen siblings, last because they copy the final
   files.
5. Polish (T040 to T043).

## Notes

- The two command files and the two SKILL.md files share bodies; edit the command file and copy,
  never the skill alone. `DesignAndBuildExtensionSuite` fails on a difference.
- Each sibling's pull request is a draft left for the person; none is reviewed by `/speckit-build`
  until this feature merges.
- Commit after each task with `<type>(061): …`; the first run of `/speckit-build` on this branch is
  the proof of US2 (quickstart §5).
