# Review: feat(068): docs init, the kept parts and the ankka-docs skill

**Verdict:** Request changes
**What it does:** It adds `docs init` to the documentation tool. The command plans a repository's documentation set-up as data and writes it from templates the tool carries. Two of the files it writes are kept parts that `docs check` holds byte for byte: the build settings, and a caller of a new reusable `documentation.yml`. The rules for writing a page move to a public reference page, a new guide explains how to document a service, and a new `ankka-docs` skill carries both.
**Should it exist:** Yes. Each sibling's set-up was copied by hand from another and has since drifted. Rendering the set-up from one template and having the check hold it to that template removes the copying and stops the drift. The change also reuses the tool's existing check, sync and skill machinery rather than adding a second mechanism.
**Read:** In full: `tools/docs/src/ankka_docs/setup.py`, `kept.py`, the `cli.py` and `pages.py` diffs, all nine templates, `.github/workflows/documentation.yml`, `tests/test_setup.py` (fixtures, US2 and the workflow cases), `tests/test_writing_skill.py`, `docs/build/documentation.md`, `docs/reference/documentation-rules.md`, the trimmed `docs/contributing/documentation.md` (head), the `limitations.md`, `release-patterns.txt`, `mkdocs.yml`, `ci.yml`, `GLOSSARY.md`, `README.md`, `CLAUDE.md`, `.claude/rules/docs.md` and template README diffs, `tools/docs/skill/ankka-docs/SKILL.md`, both feature files, the spec, the plan, the tasks, the three contracts, and research.md's Measurements. Skimmed as rendered copies: `marketplace/…/ankka-docs/**`, `ankka.g8/…/ankka-docs/**`, and the limitations copies in the other skills. Opened outside the diff: `release.yml` (the docs and marketplace jobs), `build.sbt`'s `ReleasePatterns` bundle, `pages.Project.label`, `snippets.py`'s drift message, and the `mkdocs.yml` of the four siblings. I also read the `github-pages` environment policies of ankka, ankka-flow and ankka-surface through `gh api`, read-only. No area needs another reviewer.
**Size:** 62 files, +4448 −305. About 1130 lines are rendered skill copies and about 1100 are spec artifacts. The hand-written code and pages come to roughly 1900 lines. That is large, but it is one feature, and its rendered copies cannot be split from their source, so no split is asked for.

## Blocking

- `tools/docs/src/ankka_docs/setup.py:276-280` — issue (blocking): the "Left for a person" list leaves out one step: allowing `v*` tags to deploy to the `github-pages` environment. Without it, the first release tag of every repository set up with this command fails at the deploy job.
  - When Pages is turned on with "GitHub Actions" as its source, GitHub creates `github-pages` with a deployment policy that allows the default branch only. `gh api repos/thinkmorestupidless/<repo>/environments/github-pages/deployment-branch-policies` gives:
    - ankka: `main` (branch) and `v*` (tag)
    - ankka-flow: `main` (branch) and `v*` (tag)
    - ankka-surface: `main` (branch) only. This is the repository T027 proved the job on.
  - The tags were added to ankka and ankka-flow by hand. The pull-request path T027 ran never reaches the environment, so nothing has exercised this yet.
  - This breaks FR-013 (publish on a release tag) for every new repository, and FR-008 (the report lists what a person must do).
  - Suggestion: add a line after "turn the site on", for example `  - let release tags deploy: Settings, Environments, github-pages, Deployment branches and tags, add a tag rule "v*"`.
  - Make the same change in `contracts/docs-init.md`, the guide's copy of the report (`docs/build/documentation.md:32-45`), the rules page and the owned `writing-documentation.md.tmpl`.
  - Add a sentence to the limitations entry or the rules page saying the publish fails without the rule. The spec's edge case only covers Pages never being turned on.
  - Resolved: the report, `contracts/docs-init.md` (changed with Trevor's agreement), the guide, the rules page, the written contributing page and the limitations name the `v*` tag rule of the `github-pages` environment, 64e7a96a2 and 61349ba5c
- `docs/build/documentation.md:21`, `docs/reference/documentation-rules.md:42` (and their rendered copies under `marketplace/plugins/ankka/skills/ankka-docs/references/` and `ankka.g8/src/main/g8/.claude/skills/ankka-docs/references/`) — issue (blocking): the plugin and template that ship with the next release will tell agents to run `docs init` from `@v0.18.0`, a release that has neither the command nor `documentation.yml`.
  - `docs sync` sets a `release` form (`ankka@v{version}`, added to `release-patterns.txt`) to the newest tag `git tag --merged HEAD` lists. At the commit that becomes v0.19.0, that tag is v0.18.0.
  - `docs build --release` rewrites only the site, and only for the CLI bundle `build.sbt`'s `ReleasePatterns` writes. The `marketplace` and `template` jobs in `release.yml` push the tree's copies unchanged.
  - Result: an agent holding the released `ankka-docs` skill runs `uvx --from "git+…ankka@v0.18.0#subdirectory=tools/docs" docs init` and gets the usage error. This is exactly the path SC-005 is about. SC-005 was measured with "the tool from this checkout in place of a release" (research.md, Measurements), so it did not cover the shipped plugin.
  - The template README is correct: it uses `$ankka_version$`.
  - Suggestion: in the `marketplace` and `template` jobs, rewrite the release forms of the skill copies to the tag being released before pushing. This is the same rewrite the CLI bundle gets, and it is the cheapest general fix, since any skill naming a just-added release would hit the same lag.
  - Alternatively, write the line with a placeholder release (`@v<release>`, as the owned `writing-documentation.md.tmpl` does), and say where the newest release is listed.
  - Resolved: the pages and the skill name the tool at `@v<release>` and say which release that is, and the `ankka@v{version}` release form is gone; chosen over a rewrite in the release jobs because the CLI's own templates carry the same copies and a third channel would still lag, 61349ba5c

## Should fix

- `tools/docs/src/ankka_docs/setup.py:152-161` — issue (non-blocking): in a repository that already has a `mkdocs.yml`, the description and the address are validated even though they are read from that file, and even when every owned part that uses them will be left as it is.
  - ankka-reasoning's `site_description` is 264 characters, so `docs init` refuses there: "the description is one sentence under 240 characters". The adoption the follow-ups plan for it (quickstart step 4) fails on a file the command will not touch.
  - The `https://` check on `site_url` has the same shape.
  - Suggestion: validate `--description` and `--site-url` when they are given as arguments, or when an owned part that uses them is going to be written. Take what `mkdocs.yml` already says as it is.
  - Resolved: arguments are held when given; what `mkdocs.yml` says is held only when an owned part is about to be written, with a test at 264 characters, 64e7a96a2
- `docs/reference/documentation-rules.md:318-325` — issue (non-blocking), from REVIEW.md "Every sample in every language": the include-marker sample is Scala only, and the comment before it, `<!-- languages: scala; the markers are comment lines and read the same in every language -->`, uses the leave-a-language-out escape with a reason that is not a limitation `limitations.md` states.
  - The reason is also untrue: line 339-340 of the same page says the marker is `#` in Python.
  - The passage came over word for word from the contributing page. It is now on a public rules page that the `ankka-docs` skill carries to every repository and every SDK's developer.
  - The page has no `languages` facet, so `docs check` cannot see this.
  - Suggestion: replace the block with a short `text` block showing both comment forms (`// docs:start add-item` … and `# docs:start add-item` …) and drop the `languages:` comment. Or show it as a Scala and Python tab set with the page's `languages` set to match.
  - Resolved: a `text` block and a sentence giving the `#` form; the `languages:` comment is gone, 61349ba5c
- `tools/docs/src/ankka_docs/kept.py:44-51` — issue (non-blocking) (if-minor): `fill` substitutes values first and checks for leftover `@@` afterwards. Three things go wrong:
  - A description containing `@@` raises `ValueError`, which `run_init` does not catch, so the user gets a traceback.
  - A description containing `@@name@@` is substituted depending on dict order.
  - `site_url` goes unquoted into `mkdocs.yml.tmpl` (`site_url: @@site_url@@`), so an address carrying `#` or `: ` breaks the YAML.
  - Suggestion: substitute in one pass with `re.sub(r"@@(\w+)@@", lambda m: values[m.group(1)], text)`, which raises `KeyError` for an unknown marker. In `facts`, refuse an address that is not a plain URL (`urllib.parse`, no fragment or space), or write it quoted as the description is.
  - Resolved: one-pass substitution, the address held to a plain `https://` address and written quoted, each with a test, 64e7a96a2
- `docs/build/documentation.md:32-45, 87-90` — suggestion (if-minor): the guide breaks two things the rules page says, and it can drift from the code.
  - The "Show replies the running service gave" section shows a `curl` call and no reply, against the rules page's "a command by what it prints".
  - The hand-copied "Left for a person" block is held to nothing. It is already about to be wrong: see the first blocking finding.
  - Suggestion: add the reply the call prints, in a `text` block. Either assert in `test_writing_skill.py` that the guide's block equals `setup.report(...)` for `cart`, or cut the block down to what is stable.
  - Resolved: the guide's report is asserted equal to `setup.report` in `test_writing_skill.py`; the `curl` call with no reply is replaced by the instruction to capture one, since no reply here would be the service's own, 61349ba5c

## Nits

- `tools/docs/src/ankka_docs/setup.py:214` — nitpick (non-blocking): `_disclaimed` returns whether each part is kept, which is the opposite of its name. `_kept_here` would read truer at the call site in `plan`.
  - Resolved: renamed `kept_here`, 64e7a96a2
- `tools/docs/src/ankka_docs/templates/writing-documentation.md.tmpl` (the "parts the tool keeps" section) — nitpick (non-blocking): it says `docs init` from a later release "rewrites both files and nothing else", but it also writes any owned part that is missing.
  - Resolved: it says both files are rewritten and every owned file left, 64e7a96a2
- `tools/docs/src/ankka_docs/setup.py:83-86` — nitpick (non-blocking): `docs init --description` with no value reports "`--description` is not one of them". "`--description` needs a value" would say what is wrong.
  - Resolved: "`--description` needs a value", with a test, 64e7a96a2
- `tools/docs/src/ankka_docs/setup.py:163-168` — nitpick (non-blocking): a release is required even when the repository disclaims both kept parts and none will be written. This is why `CLAUDE.md` has to tell ankka to pass `--release <v>` to a command that writes nothing there.
  - Resolved: a repository that keeps neither part needs no release, with a test; CLAUDE.md's line drops `--release`, 64e7a96a2 and 61349ba5c

## Questions

- `tools/docs/src/ankka_docs/kept.py:105` — question: the check prints `docs/../tools/docs/pyproject.toml:1: …`, while `contracts/kept-parts.md` gives `tools/docs/pyproject.toml:1: …`. Research.md records this as the tool's convention, and the suite compares suffixes. Should the contract show the printed form, so that a reader of the contract sees what the check prints? An edit to the contract's block, or a note under it, settles it.
  - Answered: yes; a note under the contract's block gives the printed prefix, 64e7a96a2
- `.github/workflows/documentation.yml:30` — thought: the kept job uploads `target/docs-site`, but `site_dir` is in the owned `mkdocs.yml`, which a repository may change. All four siblings use `target/docs-site` today. One sentence on the rules page (or in the owned contributing page) saying the job expects that directory would turn a silent empty upload into a stated rule.
  - Answered: the rules page now says the job publishes `target/docs-site` and that a repository which moves its `site_dir` publishes nothing, 61349ba5c

## Praise

- `tools/docs/src/ankka_docs/setup.py:222-255` — praise: `plan` is pure and `apply` is the only writer, and a refusal anywhere writes nothing. `test_a_file_at_a_kept_path_the_tool_did_not_write_stops_everything_until_forced` proves that with a byte snapshot of the whole directory. This is the platform's "effects are inert data" applied to a CLI, and the report is printed from the same plan, so it cannot claim what it did not do.
- `tools/docs/src/ankka_docs/kept.py:68-102` — praise: the check re-renders each kept part for the release the repository names and compares bytes, so there is no list of "important lines" that could miss an edit. Each test proves it can fail by changing one byte (`>=3.12` → `>=3.13`, `contents: read` → `write`).
- `specs/068-documentation-setup/research.md` (T027) — praise: the reusable workflow was proved by a real GitHub run in ankka-surface, not only by assertions on parsed YAML, and the tag path that no such run covered is named as unproved.

## Out of scope

- note: the release-form lag behind the second blocking finding is a general property of the rendered skills, not new in this change. Any skill page naming the release that first carries a feature ships one release behind in the marketplace and the template. It bites here because the feature's first instruction is a command at a release. Fixing it in the release workflow, as suggested above, closes it for every page; otherwise file an issue: "skill copies pushed by the marketplace and template jobs name the previous release".

## Checks applied

- **General checklist groups applied:** Correctness, Design and scope, Error handling, Security (the workflow, `subprocess` calls to git, template filling), Tests, Observability (reports and messages), API and compatibility (the kept parts and the reusable workflow are a contract for every sibling), Dependencies and build (action versions match `release.yml`; no new dependency), Documentation, Commit and PR hygiene.
- **General checklist groups skipped:** Concurrency (no threads), Data and migrations (no schema), Performance (a one-shot CLI over a handful of files).
- **Language skill:** `pr-review-python` (bundled; the repository has no skill of its own of that name) for `tools/docs`. Its groups were applied in order: security, errors and resources, collections, nulls, classes, typing, tests, packaging, idioms. Nothing beyond the findings above. `subprocess.run` takes a list with `check=False` handled explicitly; the dataclasses are frozen; no mutable defaults. The `eval` in `test_setup.py` runs on the repository's own workflow text, in a test only.
- **Project rules read:** `CLAUDE.md`; `.claude/rules/docs.md`, in full; `.claude/rules/build-and-release.md` (Publishing, and the traps about `release-patterns.txt` and the marketplace and template jobs); `REVIEW.md` (Documentation); `.github/pull_request_template.md`; the workspace `CLAUDE.md` and its brains.
- **"Every sample in every language" (REVIEW.md):** the new pages name no `languages`, and the plan states that no SDK is reached. The feature is the documentation tool's, and the guide's code blocks are shell and output, which the rules allow by hand. The rule's one hit is the Scala-only marker sample and its unsupported `languages:` reason, reported under Should fix. No SDK's page, sample or limitations entry is missing. Ankka's limitations gain a Documentation section, and every skill's limitations copy carries it.
- **Enforced by tooling, so not reviewed:** formatting. No ruff or mypy configuration exists for `tools/docs`.
- **Commit hygiene:** each commit is a Conventional Commits header scoped `068` with the attribution trailer. There is no pull request yet, so the description could not be checked against the template. Its "Risk and rollback" should say that the tag path is unproved and, once the first blocking finding is fixed, that it needs the environment's tag rule.
- **Tests run:**
  - `uv run --project tools/docs pytest tools/docs`: 79 passed in 5.0s.
  - `uv run --project tools/docs docs check`: "116 pages, no problems".
  - Not run: `just features` (research.md records 0 findings); the reusable workflow on a tag, since only GitHub runs it and it would need a release.

## Second round

**Verdict:** Approve with comments
**What it does:** The range `5a58ee1ef...HEAD` answers the first round. The report, the contract, the guide, the rules page, the written contributing page and the limitations name the `v*` tag rule of the `github-pages` environment. The pages name the tool at `@v<release>` and the `ankka@v{version}` release form is gone. `docs init` holds an existing `mkdocs.yml`'s description and address only when a page it is about to write would carry them, fills templates in one pass, writes the address quoted, and asks for no release where neither part is kept.
**Should it exist:** Yes. Every hunk traces to a first-round finding, and none adds scope beyond one.
**Read:** In full: the range's diff of `setup.py`, `kept.py`, both templates, `test_setup.py`, `test_writing_skill.py`, `docs/build/documentation.md`, `docs/reference/documentation-rules.md`, `docs/reference/limitations.md`, `release-patterns.txt`, `CLAUDE.md`, both contracts and research.md's "Found in review". `setup.py` lines 60 to 318 and `kept.py` lines 30 to 109 as they now stand. Skimmed as rendered copies: the ten files under `marketplace/` and `ankka.g8/`, which `docs check` holds to their sources. Opened outside the diff: `cli.py`'s `run_init`, the seven templates for their markers, the template `README.md`, `.claude/rules/docs.md` (the release-form and `docs init` entries), `REVIEW.md`, and the first round above. The comments are expected before the pull request is marked ready, except those marked `(if-minor)`.
**Size:** 25 files, +316 −78. 96 lines are the first report and about 110 are rendered copies; the hand-written code, tests and pages come to roughly 190 lines.

### Resolutions

Each first-round resolution, and whether it holds.

- **Blocking 1, the `v*` tag rule: holds.** `setup.report` prints the line whenever the documentation job is in the plan (`setup.py:301`). `contracts/docs-init.md`, the guide (twice: the report and the paragraph after it), the rules page, `writing-documentation.md.tmpl` and the limitations entry say it, and the limitations entry says the publish fails without it. The tag path itself is still proved by no run; research.md and the first round both say so.
- **Blocking 2, the release the pages name: holds.** No page, skill source or rendered copy names `ankka@v0.18.0` any more (`grep -rnE "ankka@v[0-9]"` over `docs`, `tools/docs/skill`, `tools/docs/src` and both rendered trees finds nothing). `docs/release-patterns.txt` is back to what `main` has, so nothing rewrites a form the pages no longer use. The template `README.md` writes the release out with `$ankka_version$`, as the guide says it does. The choice of the placeholder over a rewrite in the release jobs is reasoned in research.md. See the question below on which release "carries the command".
- **Should fix 1, validating what `mkdocs.yml` says: holds.** Run read-only against the sibling checkout, `facts` and `plan` for ankka-reasoning with `--release 0.19.0` no longer refuse the 264-character description; every owned part there is left and the two kept parts wait for `--force`, as quickstart step 4 expects. The check moved into `plan`, which still writes nothing, and `run_init` catches the `Usage` it raises. The new test proves both halves, the second with a byte snapshot.
- **Should fix 2, the marker sample: holds.** A `text` block and a sentence giving the `#` form; the unsupported `languages:` comment is gone. `docs check` passes.
- **Should fix 3, `fill`: holds.** One pass over `@@(\w+)@@`, an unknown marker is a `KeyError`, and a description holding `@@name@@` and `@@nobody@@` is written as it is, proved through `mkdocs.yml` and `docs/index.md`. The address is held to a plain `https://` address and written quoted in `mkdocs.yml`. Two edges of it are in the nits.
- **Should fix 4, the guide: holds.** `test_the_guide_shows_the_report_the_command_prints` compares the guide's block with `setup.report`'s lines, so the guide fails the suite the day the report changes. The `curl` call with no reply is gone, and the section now says where a reply comes from.
- **Nits 1 to 4: hold.** `kept_here` is the name; the contributing page says both files are rewritten and every owned file left; `--description` with no value says it needs one, with a test; a repository that keeps neither part needs no release, with a test, and `CLAUDE.md`'s command runs in this checkout as written ("the set-up is current; nothing written", by `facts` and `plan` read-only).
- **Questions 1 and 2: answered.** The contract carries the printed prefix; the rules page states `target/docs-site`.

### Blocking

None.

### Should fix

- `.claude/rules/docs.md` — issue (non-blocking), from `CLAUDE.md` ("When you learn a trap worth keeping, add it to the rule file for its area") and the workspace's principle 9: the two traps the first round found are recorded in research.md and nowhere a later session reads before touching the area.
  - The rendered skills the marketplace, the template and the CLI's templates carry are the tree's, so a `release` form in a page they include names the release before the one being tagged. The rule file's release-form entry (line 83) says `docs sync` moves every form to the newest tag and a build rewrites the site only; it does not say the skill copies ship as the tree has them. The next page whose first instruction is a command at a release walks into the same thing.
  - GitHub creates `github-pages` allowing the default branch alone, so a sibling's first tag publishes only after a person adds the `v*` rule. The rule file's `docs init` entry (line 101) does not mention it, and ankka-surface still lacks the rule.
  - Suggestion: one dated sentence on each, appended to the two entries they belong to.
  - Resolved: both traps are in `.claude/rules/docs.md`, 837119e23

### Nits

- `tools/docs/src/ankka_docs/setup.py:296` — nitpick (non-blocking): the report takes the host with `removeprefix("https://").split("/")[0]`, which was sound while `facts` refused every other scheme. An address `mkdocs.yml` already holds is now taken as it is when only kept parts are written, and for `http://docs.widget.example/` the report prints "point http: at GitHub Pages". `urlsplit(facts.site_url).netloc` gives the host for either. Confirmed by calling `setup.report`; no sibling has such an address today.
  - Resolved: the host is read with `urlsplit`, with a test, 837119e23
- `tools/docs/src/ankka_docs/templates/plugin.json.tmpl:9` — nitpick (non-blocking) (if-minor): `"homepage": "@@site_url@@"` still takes the address unquoted, beside a description that is written quoted. `validate_site_url` accepts `https://docs.widget.example/a"b/`, and the `plugin.json` filled from it does not parse. `"homepage": @@site_url_quoted@@` closes it with the value the round already added.
  - Resolved: the homepage is written quoted, 837119e23
- `docs/reference/documentation-rules.md:46-47`, `docs/build/documentation.md:56` — nitpick (non-blocking): the sentence about `<release>` was put in front of "It reads the repository's name and address", so "It" now reads as the release or the releases page; "The command reads" restores it. Both inserted passages also left a line far past the width the pages wrap at.
  - Resolved: the rules page's sentence names `docs init`, and both passages are reflowed, 837119e23

### Questions

- `docs/build/documentation.md:27-30`, `docs/reference/documentation-rules.md:46` — question: the guide says `<release>` is "a release of ankka that carries `docs init`: the one a service's `ankka` dependency names", and the rules page says the releases page lists them. A service made before the release that first carries the command has a dependency that names a release without it, and the releases page does not say which releases carry it. Should the limitations entry "The documentation tool is taken from ankka's repository at a release tag" say which release the command starts from, in the `from version` form `release-patterns.txt` already allows? If the number cannot be written before the tag exists, a sentence saying that a release without the command answers with the usage line would settle it.
  - Answered: the pages now say a release from before the command answers `docs init` with the usage text and exit code 2 and writes nothing, and to take a later one, 837119e23

### Praise

- `tools/docs/tests/test_writing_skill.py:96-104` — praise: the guide's "Left for a person" block is compared with what `setup.report` prints, not with a copy of it in the test, so the page, the rendered skills and the command cannot part without a red suite.
- `tools/docs/src/ankka_docs/setup.py:261-264` — praise: the relaxed check went into `plan`, not `apply`, so "a refusal writes nothing" still holds, and `test_what_mkdocs_yml_already_says_is_taken_as_it_is_unless_a_page_would_carry_it` proves the refusal with the same byte snapshot the first round praised.

### Checks applied

- **General checklist groups applied:** Correctness, Design and scope, Error handling (the new `Usage` paths, the `KeyError` of a template's own mistake), Security (template filling, the address), Tests, API and compatibility (the kept templates are untouched in this range, so no sibling's bytes change; `mkdocs.yml.tmpl` is owned and written once), Documentation, Commit and PR hygiene.
- **General checklist groups skipped:** Concurrency, Data and migrations, Performance, Observability, Dependencies and build (no dependency, workflow or build file in the range).
- **Language skill:** `pr-review-python`, by the lighter procedure for a small change (about 80 lines of Python and their tests): security, errors, typing and tests, from the summaries. `release or ""` keeps `Facts.release` a `str`, and `kept.render` is not called with it when neither part is kept. Nothing beyond the nits.
- **Project rules read:** `CLAUDE.md`; `.claude/rules/docs.md` (the release-form and `docs init` entries); `REVIEW.md`; the workspace `CLAUDE.md` and its brains.
- **"Every sample in every language" (REVIEW.md):** the range changes no page that names `languages` and adds no behaviour to the platform or an SDK. The one first-round hit, the Scala-only marker sample, is now a `text` block with both comment forms and no `languages:` comment. No supported language is missing a page, a sample or a limitations entry; the limitations entry for the set-up gained the tag rule, and the copies in the `ankka`, `ankka-design` and `ankka-port` skills carry it in both rendered trees.
- **Spec, plan and tasks:** the range changes no requirement. `contracts/docs-init.md` changed in two places, the report line and the `--release` row, and research.md's "Found in review" records the first as agreed. FR-008 and FR-013 are now served by the report and the pages; SC-005's measurement still used the checkout in place of a release, as research.md says.
- **Commit hygiene:** three commits, each a Conventional Commits header scoped `068` under 72 characters with the attribution trailer; the two fix commits say which findings they resolve. No pull request yet. Its "Risk and rollback" should say that the tag path is unproved and needs the environment's tag rule.
- **Tests run:**
  - `uv run --project tools/docs pytest tools/docs`: 88 passed in 5.3s.
  - `uv run --project tools/docs docs check`: "116 pages, no problems".
  - Read-only probes of `setup.facts`, `setup.plan`, `setup.report` and `kept.fill` against this checkout and the ankka-reasoning, ankka-flow and satisfactory checkouts; nothing was written.
  - Not run: `just features` (no feature file or glossary in the range); the reusable workflow on a tag, which only GitHub runs.
