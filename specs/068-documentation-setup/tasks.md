# Tasks: Setting up a repository's documentation with one command, and a skill for writing it

**Input**: Design documents from `specs/068-documentation-setup/`

**Prerequisites**: plan.md, spec.md, research.md (R1 to R10), data-model.md, contracts/docs-init.md,
contracts/kept-parts.md, contracts/settings.md, quickstart.md

**Tests**: required. Every acceptance scenario ends as a pytest test whose name is the scenario's,
in `TESTS/test_setup.py` (`features/documentation/setting-up.feature`) or
`TESTS/test_writing_skill.py` (`writing-documentation.feature`), and fails without the feature
(research R9). Within a task, write the test first and see it fail.

**Paths**: `TOOL` is `tools/docs/src/ankka_docs`, `TESTS` is `tools/docs/tests`, `TMPL` is
`tools/docs/src/ankka_docs/templates`.

**Messages**: every line the command or the check prints is the contract's (`contracts/docs-init.md`,
`contracts/kept-parts.md`); a test asserts it as written there.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: the user story the task serves (US1 to US3)

---

## Phase 1: Setup

- [ ] T001 In `tools/docs/pyproject.toml`, make the wheel carry `TMPL` (hatch includes package files; assert it with a case in `TESTS/test_setup.py`, created here, that `importlib.resources.files("ankka_docs") / "templates"` lists the nine names of data-model.md once they exist) and write `TESTS/conftest.py` with a fixture `repository(name)`: a temporary directory, `git init`, `git remote add origin git@github.com:example/<name>.git`
- [ ] T002 [P] Verify research R2's open point and record the answer under `## Found during implementation` in `research.md`: run `uvx --from "git+https://github.com/thinkmorestupidless/ankka@v0.18.0#subdirectory=tools/docs" python -c "..."` printing the `direct_url.json` of the `ankka-docs` distribution, and confirm `vcs_info.requested_revision` is `v0.18.0`. Where it is the commit, R2's fallback is `--release` always on a first run; correct R2 and `contracts/docs-init.md` before T005
- [ ] T003 [P] Verify research R8's open point and record it the same way: copy `docs/contributing/documentation.md` to a scratch page of kind `reference` and run the check on it; list every line the history and positional patterns refuse, for T017 to fence or reword

---

## Phase 2: Foundational (facts, templates, the plan)

**Blocks every story.**

- [ ] T004 Write `TOOL/setup.py`'s `Facts` and `facts(start, arguments) -> Facts` per data-model.md and research R3, with `TESTS/test_setup.py` cases: the root is the git top level from a subdirectory; an `ssh` and an `https` remote give the same `repo_url`; no checkout, no `origin`, a remote not on GitHub, and a name that cannot name a skill each raise the usage error whose words are `contracts/docs-init.md`'s; description and address fall back to an existing `mkdocs.yml`
- [ ] T005 Write `setup.tool_release()` per research R2 (as corrected by T002) with cases: a fake distribution whose `direct_url.json` names `v0.19.0` gives `0.19.0`; one naming `main`, one naming a commit and one with no `direct_url.json` give none; `Facts.release` takes `--release`, else `tool_release()`, else the release an existing `tools/docs/pyproject.toml` names, else the usage error
- [ ] T006 [P] Write the two kept templates `TMPL/pyproject.toml.tmpl` and `TMPL/docs.yml.tmpl` and `TOOL/kept.py`'s `render(name, release) -> dict[path, content]`, byte for byte `contracts/kept-parts.md`, with a `TESTS/test_setup.py` case comparing each to the contract's block and one that two names differ only where the name is
- [ ] T007 [P] Write the seven owned templates in `TMPL` per research R10 (`mkdocs.yml.tmpl` with the theme, the validation block, the plain extensions and an `extra.docs` naming the skill's source and target and the site-link files; `index.md.tmpl`; `writing-documentation.md.tmpl`; `limitations.md.tmpl`; `glossary.md.tmpl`; `SKILL.md.tmpl`; `plugin.json.tmpl`), with placeholders `@@name@@`, `@@description@@`, `@@site_url@@`, `@@repo_url@@` so a `$` or a brace in a workflow or a page is never read as one
- [ ] T008 Write `setup.plan(facts, root) -> list[Write]` and `setup.apply(plan)` per data-model.md and research R4 and R7: `Write` with its ownership and action; a kept path holding a file without the kept comment line is `refuse` unless forced; `apply` writes nothing when any entry is `refuse`. Cases: the plan for an empty repository is nine `write`s in the set-up's order; `apply` is the only function in `setup.py` that opens a file for writing (assert by a case that runs `plan` on a read-only directory)

**Checkpoint**: `uv run --project tools/docs pytest tools/docs` is green.

---

## Phase 3: User Story 1 - A repository's documentation is set up by one command (Priority: P1) 🎯 MVP

**Goal**: `docs init` in an empty repository, and a build that passes on what it wrote.

**Independent Test**: quickstart step 2.

- [ ] T009 [US1] In `TOOL/cli.py`, add `init` to the usage text and handle it before `project()` is called: parse the four arguments of `contracts/docs-init.md`, call `facts`, `plan`, `apply`, print the report, exit 0 or 2. Write `setup.report(plan, facts) -> str` exactly as the contract gives it, the "Left for a person" list included
- [ ] T010 [US1] `TESTS/test_setup.py`: "a repository with no documentation is set up by one command and its documentation builds": init into `repository("widget")`, then `load(project())`, `checks.run`, `skill.sync` then `skill.check`, and `mkdocs build --strict` report nothing and the repository holds each part the scenario names. Fix the templates until it passes (FR-004, FR-009)
- [ ] T011 [P] [US1] `TESTS/test_setup.py`: "the set-up names the release of the documentation tool that made it" (`--release 0.19.0`; the build settings name `v0.19.0`; no written file contains `@main` or names a branch) and the refusal when no release can be determined
- [ ] T012 [P] [US1] `TESTS/test_setup.py`: "setting up leaves what the repository owns as it is and names it" (a `mkdocs.yml` of the test's own is byte-identical after, the report says `left mkdocs.yml` and `wrote` for the rest) and "setting up a repository that is already set up writes only what is missing" (remove the limitations page; one `wrote`, the kept parts `current`, every other page untouched); and that a second run on a current set-up prints `docs: the set-up is current; nothing written`
- [ ] T013 [P] [US1] `TESTS/test_setup.py`: "the set-up says what is left for a person to do": the report's four statements and the three recipes, word for word the contract's, and that no `Justfile` is created or changed

**Checkpoint**: quickstart steps 1 and 2 pass.

---

## Phase 4: User Story 2 - A set-up repository is held to the set-up (Priority: P2)

**Goal**: the kept parts are checked, rewritten by `docs init`, and the job checks and publishes when it should.

**Independent Test**: quickstart step 3.

- [ ] T014 [US2] In `TOOL/pages.py`, add `kept_settings: bool = True` and `kept_job: bool = True` to `Settings`, read from `extra.docs.kept` per `contracts/settings.md` (an unknown key or a non-boolean is a `ConfigError`), and extend the `Settings` docstring's example; cases in `TESTS/test_project.py`
- [ ] T015 [US2] Write `kept.check(project) -> list[Problem]` per research R6 with the four messages of `contracts/kept-parts.md`, and add it to `run_check` in `TOOL/cli.py`. `TESTS/test_setup.py`: "a part of the set-up the documentation tool keeps that was changed by hand fails the check" (one changed byte in each kept part, each named), "a repository that says the documentation tool keeps no documentation job there is held to none", a missing kept part, and a dependency line naming a branch
- [ ] T016 [US2] `TESTS/test_setup.py`: "a part of the set-up the documentation tool keeps is put back by setting up again" (`rewrote`, then the check passes), "a repository that builds with a later release of the documentation tool takes that release's kept parts by setting up again" (`--release 0.20.0` over `0.19.0`; both kept parts name it; `mkdocs.yml` and every page byte-identical), and a file at a kept path without the kept comment line (exit 2, the directory byte-identical, then written with `--force`)
- [ ] T017 [US2] Write `.github/workflows/documentation.yml` (`on: workflow_call`, no inputs, no secrets) per the table in `contracts/kept-parts.md`: checkout with `fetch-depth: 0`, `astral-sh/setup-uv` at the version `release.yml` uses, `docs build` or `docs build --release`, and the Pages artifact and deploy in a second job whose `if` is a tag `v*` with no hyphen. `TESTS/test_setup.py`: "a set-up repository checks its documentation on a pull request and publishes nothing" and the outline "a set-up repository publishes its documentation when a release is tagged", as assertions on the parsed YAML of the caller's triggers and of this file's conditions for `pull_request`, `v0.2.0` and `v0.2.0-rc.1`; add the file to the `docs` filter in `.github/workflows/ci.yml` and to `.github/ci-coverage.py`'s claims
- [ ] T018 [US2] In `mkdocs.yml`, add `kept: {settings: false, job: false}` under `extra.docs` with a comment saying why (research R6), and run `uv run --project tools/docs docs build`

**Checkpoint**: quickstart step 3 passes; ankka's own `docs build` is green.

---

## Phase 5: User Story 3 - A coding agent is told how to write documentation (Priority: P2)

**Goal**: the rules as a public page, the procedure as a guide, and the skill that carries both.

**Independent Test**: quickstart step 5.

- [ ] T019 [US3] Write `docs/reference/documentation-rules.md` (kind `reference`) by moving, word for word, what `docs/contributing/documentation.md` says that holds for any repository (research R8's list), fencing or rewording the lines T003 found; add a section for each key of `extra.docs` including `kept`, and one for the five commands including `init` with the first-run `uvx` line; no dependency line names a branch and nothing says to copy another repository (FR-020). Trim `docs/contributing/documentation.md` to what is ankka's own, linking to the rules. Add the page to `nav` in `mkdocs.yml`
- [ ] T020 [US3] Write `docs/build/documentation.md` (kind `guide`), "Document a service", for a developer of any ankka application (FR-017, FR-020a): set up with `docs init` and what is left for a person; what to read before writing; choosing pages by kind; samples from tested code, and giving a sample a tested home; replies taken from the running service, and how to capture them; the limitations written from what the code does, including a difference from its contract; what a skill's body holds; changing a sample's source and watching the check fail. Add it to `nav`
- [ ] T021 [P] [US3] In `docs/reference/limitations.md`, add what a set-up does not do (FR-020b): it publishes through GitHub Pages only, a repository publishing elsewhere says `kept: {job: false}`, and the tool is taken from ankka's repository at a release tag
- [ ] T022 [US3] Write `tools/docs/skill/ankka-docs/SKILL.md`: a description saying when to load it in a request's words (FR-019); `pages:` the two new pages; a body of the rules an agent holds while writing, the questions to settle first and the mistakes to check for, restating no table or list either page gives (FR-018). Run `uv run --project tools/docs docs sync` and commit the rendered skill under `marketplace/plugins/ankka/skills/ankka-docs/` and `ankka.g8/src/main/g8/.claude/skills/ankka-docs/`
- [ ] T023 [P] [US3] In `ankka.g8/src/main/g8/README.md`, beside what it says of the skills, say that the service's documentation is set up with `docs init` and name the guide's address (FR-020a); run the template's suite that reads the README when one does (`-Dankka.template.tests`)
- [ ] T024 [US3] Write `TESTS/test_writing_skill.py`, one test per scenario of `features/documentation/writing-documentation.feature`, against ankka's own tree: the rendered skill exists in both targets and its description names writing a page; its `references/reference/documentation-rules.md` equals `render.page_markdown` of the page; the guide's headings cover the six statements of "the skill says how a repository is documented, in order" and the skill carries the guide; the guide describes `docs init`, addresses any developer of a service, and neither page contains "copy" beside "repository" nor `@main`; the template's README names `docs init`; the limitations say GitHub only and name `kept`

**Checkpoint**: `uv run --project tools/docs pytest tools/docs && uv run --project tools/docs docs build` is green.

---

## Phase 6: Polish

- [ ] T025 [P] In `CLAUDE.md`'s commands and its documentation guidance, add `docs init` and the kept parts in two lines; in `README.md` where the documentation tool is described, the same
- [ ] T026 Run quickstart steps 1, 2, 3 and 5 as written and record the outputs under `## Measurements` in `research.md`; run `just features` and `uv run --project tools/docs docs build`
- [ ] T027 Record under `## Follow-ups` in `research.md`, as a list the pull request's "Left out" quotes: the four siblings' adoption (quickstart step 4, after the release), `ankka-brain/conventions.md`'s documentation section (FR-021), and the three items the spec leaves out

## Dependencies

- Phase 2 blocks everything. T004 and T005 before T008; T006 and T007 before T008.
- US1 (T009 to T013) needs Phase 2. US2 needs US1's T009. US3's pages (T019, T020) need T015 and T009 only for what they describe; T024 needs T019 to T023.
- T018 needs T014 and T015, or ankka's own check fails on its missing kept job.

## Parallel examples

- T002 and T003 beside T001.
- T006 and T007.
- T011, T012 and T013 once T010 is green.
- T021 and T023 beside T019 and T020.

## Implementation strategy

US1 alone is useful: a repository can be set up, by hand-held check. Stop there for a first look
(T001 to T013). US2 makes it hold. US3 is independent of US2 but describes it, so it is written last.
