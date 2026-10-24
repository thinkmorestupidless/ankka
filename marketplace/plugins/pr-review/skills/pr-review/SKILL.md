---
name: pr-review
description: Review a pull request, a branch, a commit range or the working tree the way a careful senior engineer would — read the description, judge whether the change should exist, read every line with the general checklist (correctness, design and scope, error handling, concurrency, security, data and migrations, performance, tests, observability, API compatibility, dependencies, docs, commit hygiene), then detect the languages in the diff and apply each language's own checklist (pr-review-scala, pr-review-java, pr-review-kotlin, pr-review-go, pr-review-python, pr-review-rust, pr-review-typescript), and report findings labelled by severity with a verdict. The repository's own rules come first: its CLAUDE.md and rule files, a REVIEW.md as its review checklist, and a pr-review-<language> skill of its own in place of the bundled one. Use this whenever asked to review, check, critique, audit or give feedback on a PR, a diff, a branch, "my changes", a merge request or someone's commit, even when the word "review" is not used, and before approving or merging anything. Do not use it to fix code or to review a design document with no diff.
---

# Reviewing a pull request

A review has one purpose: decide whether this change makes the codebase better and is safe to
merge, and tell the author exactly what stands in the way. Everything below serves that. The
reviewer reads; the author fixes. Findings that the reviewer has not confirmed in the code are
questions, not issues.

## Procedure

Work through the steps in order. Steps 1 to 3 are cheap and shape everything after them; skipping
them is how reviews end up commenting line by line on code that should not exist.

### 1. Gather the change

Run the bundled script from the repository root; it collects everything the later steps need in
one pass and prints a language and size summary:

```bash
bash <skill-dir>/scripts/gather.sh 123            # a GitHub pull request number
bash <skill-dir>/scripts/gather.sh feature/x      # a branch, compared with the default branch
bash <skill-dir>/scripts/gather.sh main..HEAD     # a commit range
bash <skill-dir>/scripts/gather.sh --working-tree # staged and unstaged changes
```

It writes `description.md`, `commits.txt`, `files.txt`, `numstat.txt`, `diff.patch`, `ci.txt`
(PR mode) and `summary.md` into the directory it names (the scratchpad when one exists;
`PR_REVIEW_OUT` overrides). The summary carries the size verdict, the language table, the
project's instruction and rule files with their `paths:` and section headings, and the tooling
configuration present. Without `gh` or outside a GitHub repository it falls back to git alone and
says so. If the script cannot run, gather the same things by hand: the PR description and
discussion (`gh pr view N --comments`), the commit list, `git diff --stat` and the full diff against
the merge base, and CI status (`gh pr checks N`).

For a pull request the script fetches the head into `refs/pr-review/N` so you can read whole files
at the PR's head (`git show refs/pr-review/N:path/to/file`). The summary ends with the command that
removes that ref; run it when the review is done.

**Small changes get a lighter procedure.** When the summary reports under about 150 hand-written
lines, read the matching rule files by their headings and by grepping them for the identifiers the
diff touches rather than in full, apply the checklist groups from this file's summaries without
opening the reference, and open a language skill's reference only for an item you are about to
report. The procedure's cost should scale with the change; for a small fix the overhead must not
become the review.

### 2. Read the project's rules before the code

The repository knows things about itself that no general checklist does. Read, in this order, and
keep a short list of what applies to the files in the diff:

- `CLAUDE.md` at the root and any `AGENTS.md`, `CONTRIBUTING.md` or `docs/contributing/`.
- `.claude/rules/*.md` whose `paths:` frontmatter matches a changed file. These are the project's
  own traps, written the day they cost someone time; a finding they predict is the most valuable
  one a review can make. A matching file can be long and mostly about other subjects: read the
  sections whose headings concern what the diff changes, and grep the file for the identifiers,
  settings and component names in the diff; skim the rest. If the project asks that new traps be
  written down (many do), ask whether this change learned one and whether it was recorded.
- `REVIEW.md` at the root and in any directory above a changed file (the summary lists them).
  This is the repository's own review checklist, written for whoever reviews: a person, this
  skill, or a hosted review service that reads the same file. Apply its items as you apply the
  groups below, and cite it as the source of a finding it predicts. A `## pr-review` section in
  it, when present, controls this skill: `- skip: pr-review-<language>` drops a bundled language
  skill, `- skip: general/<Group>` drops one group of step 5, `- load: <skill>` loads a skill of the
  project's or the user's own as well. See "Customising the review" below.
- Formatter and linter configuration (`.scalafmt.conf`, `.editorconfig`, `ruff`/`mypy` sections of
  `pyproject.toml`, `.golangci.yml`, `eslint.config.*`, `tsconfig.json`, `clippy.toml`, Gradle or
  Maven plugin blocks). Note what is enforced mechanically so the review never comments on it.
- A workspace-level `CLAUDE.md` one directory up and any shared conventions it imports.

A rule the project wrote beats a rule in this skill when they disagree.

### 3. Detect the languages and load their skills

Classify every changed file. The summary from step 1 does this by extension; check it against the
build files it found.

| Files | Skill to load |
|---|---|
| `.scala`, `.sbt`, `.sc`, `project/*.scala` | `pr-review-scala` |
| `.java`, `module-info.java` | `pr-review-java` |
| `.kt`, `.kts` | `pr-review-kotlin` |
| `.go`, `go.mod`, `go.sum` | `pr-review-go` |
| `.py`, `.pyi`, `pyproject.toml`, `uv.lock`, `requirements*.txt` | `pr-review-python` |
| `.rs`, `Cargo.toml`, `Cargo.lock` | `pr-review-rust` |
| `.ts`, `.tsx`, `.mts`, `.cts`, `.d.ts`, `.js`, `.mjs`, `.cjs`, `package.json`, `tsconfig*.json` | `pr-review-typescript` |
| `.sql`, migration directories | the Data and migrations group below, with extra care |
| `.github/workflows/*`, `Dockerfile`, `Justfile`, `Makefile`, shell scripts | the Security and Dependencies groups below, as code with production access |
| `.proto`, OpenAPI, JSON schema, `.avsc` | the API and compatibility group below |
| `.md`, `.yaml`/`.yml` config, generated code, lockfiles | read for consistency with the code; skim generated files, never review them line by line |

If the user names a language, use that skill whether or not the extensions agree, and say so in
the report. A diff in several languages gets every matching skill; the general checklist runs once.
Load a language skill with the Skill tool (`pr-review-scala` and so on; installed from a
marketplace the bundled skills are listed as `pr-review:pr-review-scala`) before reading that
language's files, so its rules are in front of you while you read, not recalled afterwards.

**A skill of the repository's own replaces a bundled one.** Before loading `pr-review-<language>`
from this plugin, look for `.claude/skills/pr-review-<language>/SKILL.md` in the repository, then in
`~/.claude/skills/`; the summary from step 1 lists any it found. When one exists, load that instead
(the Skill tool lists it under its plain name, the bundled one under the plugin's) and say so in the
report's Checks applied. A `- skip:` line in `REVIEW.md` drops a bundled skill with no replacement;
a `- load:` line adds a skill beside the bundled ones.

### 4. Understand the change before judging it

- Read the description, the linked issue or spec, and the discussion so far. Write one sentence
  saying what the change is for. If you cannot, the first finding is a question to the author, and
  the rest of the review waits for it. Understanding the change is the review's main difficulty,
  not its preamble.
- Decide whether the change should happen at all, and here, now. A change that duplicates an
  existing mechanism, belongs in another layer or service, or solves a problem nobody has, is
  rejected at the design level in one comment, before any line comment is written.
- Act on the size verdict the summary printed. Above about 400 changed lines of hand-written code,
  say that defect-finding falls off and name the split you would accept. Above 1000, ask for the
  split unless the change is a deletion, a mechanical rename or a tool-generated refactor whose
  generator is named.
- Find the main files, the ones with the most logical change, and read them first; read the new
  or changed tests early, since they state the intended behaviour. If the design of the main files
  is wrong, send that finding at once and stop; line comments on code that will be rewritten waste
  both sides' time.
- Then read every remaining hand-written line. Open surrounding code whenever the diff is not
  enough to judge: the caller two screens up, the other implementation of the same interface, the
  second copy of the function. Note what you opened; it goes in the report's `Read` line.
- Compare the description with the diff both ways: a hunk no claim covers, and a claim no hunk
  implements, are both findings.
- For a bug fix, ask three more things. Does the explanation account for every recorded failure
  in the issue it closes, or only the one reproduced (a flaky test often has two causes)? Does the
  description state the fix's cost on the common path, not only its effect on the broken one? Does
  the regression test fail without the fix?

### 5. Apply the general checklist

Read the diff against every group below. The full, sourced version with the concrete signals for
each item is `references/general-checklist.md`; open a group there when a hunk touches its subject
and the summary here leaves you unsure what to look for. Read its **Tests** group for any change
that adds or edits a test, and its **Security** group when the diff touches an entry point, input
handling, authentication or authorisation, a credential, configuration, a CI workflow or a
dependency; a change to internal arithmetic and its test needs neither. Groups that cannot apply to
this diff (no schema change, no new dependency, no new thread) are skipped and named as skipped in
the report. Each item is phrased as the question to ask of the diff.

**Correctness.** Does each hunk do what the description claims, and nothing else? Are boundaries
right (`<` vs `<=`, empty, zero, negative, maximum, missing, Unicode)? Does every branch of a new
conditional, including the default, leave state whole? Is every acquire paired with a release on
every path, including the error path? Is deleted code really dead, with no reflective, string-keyed
or persisted reference (wire names, event types, timers)? Are two near-identical blocks meant to
differ where they differ? Has a UI change been seen, not just read?

**Design and scope.** Does this logic live where a reader would look for it? Does it reuse what
exists rather than add a second way? Is anything speculative: parameters, interfaces, flags nobody
uses in this change? Are refactoring and behaviour change mixed in one diff, hiding the behaviour
change inside the move? Can the change be rolled back on its own? Where no rule decides, does it
match the surrounding file?

**Error handling.** Is every caught exception handled, not merely caught, logged or swallowed? Does
failure leave the system in a safe state, with no half-applied write or leaked lock? Are details
kept out of responses and put in the server log? Are status codes right? Does every external call
have a timeout, and every retry a cap, a backoff and an idempotent target? Is cancellation and
interruption respected? Is input at every trust boundary rejected on failure rather than patched?

**Concurrency and state.** Is the threading model stated in the description when the diff adds
threads, tasks, locks, atomics or caches? Is shared mutable state guarded or confined? Is there a
check-then-act race (`if (!contains) put`, `if (!exists) create`, read-modify-write)? Do nested
locks always take the same order? Is blocking I/O done while holding a lock or on a pool that
forbids it? Is lazy initialisation thread-safe? Are executors named and shut down? Does thread-local
context (request, trace, caller) survive a hand-off to another thread? Are durations measured with
a monotonic clock, with the unit in the name?

**Security.** Is there any secret in the diff, including fixtures, Docker `ENV`/`ARG` and CI
output? Is every new entry point authorised server-side, for the specific object, deny by default,
and does execution halt on failure? Is untrusted input validated by allowlist with bounded length
and depth, and is injection prevented by construction (parameters, argument arrays, encoding),
never by validation alone? Are file names, types and contents treated as untrusted? Is the
cryptography standard (no MD5/SHA-1 for security, no ECB, CSPRNG for tokens, password hashes for
passwords)? Does anything log a token, session id, password, key, connection string or personal
data? Can user text forge a log line? Are redirects, deserialisation and outbound requests fed raw
user input? Is a CI workflow or build script changed, and reviewed as code with production access?

**Data and migrations.** Does the old application version work against the new schema and the new
against the old, since both run during a rolling deploy? Is there table-rewriting or long-locking
DDL on a live table (non-concurrent index, unvalidated constraint, type change, volatile default)?
Are backfills batched and outside the DDL transaction? Is a dropped column still read by the
running code? Are stored formats (events, snapshots, messages, wire names) evolved rather than
edited? Would existing rows fail a new validation? Does a new query come with its plan, and a new
table with indexes for its filters and joins?

**Performance.** Does query count grow with collection size (a query in a loop)? Are external
calls made inside a database transaction or on the request thread without a timeout? Is a new list
unbounded and unpaginated? Does memory grow with input where streaming exists? Does a claimed
optimisation carry numbers? Is there new allocation, logging or locking per record in a hot loop?
Does a new cache have a bound, an expiry and a scope?

**Tests.** Does a behaviour change ship with tests in the same change? Of every new test, ask:
could this pass while the thing it checks is false? Signals: no assertion, an assertion on a
constant or on the mock's own return, the expectation computed by the code under test. Do tests go
through public APIs and assert state rather than interactions? Is there a negative case for each
error path and a regression test that failed before the fix? Is the test hermetic (no sleep, real
clock, network, shared static, fixed port)? Does an `eventually` wrap the value that changes, with
the assertion inside the retry? Was a flaky test retried or skipped into silence instead of fixed?
Does test-only code or a test dependency ship in the production artifact?

**Observability and operability.** Could a failure in the new path be diagnosed from what it logs,
with identifiers? Are levels right and volume bounded? Do new request paths and jobs emit latency,
traffic, errors and saturation? Does trace context cross async and outbound boundaries? Is metric
cardinality bounded (no ids or free text in labels)? Is a new setting validated, documented and
given a default that preserves existing behaviour? Is a new background loop stopped on shutdown
and a new dependency reflected in readiness?

**API and compatibility.** Is the change declared compatible or breaking, and is that true? Is
anything public removed, renamed or made required within a major version (fields, methods,
endpoints, enum values, config keys, flags, wire names, event types)? Do new fields default to the
old behaviour? Do existing fields keep their type, format and default serialisation? Is observable
behaviour (ordering, limits, error shapes) treated as contract? Does deprecation precede removal?
Are generated files regenerated with their source? Do in-flight requests and persisted references
survive two versions running at once?

**Dependencies and build.** Is a new dependency justified against the standard library and the
existing ones, its name checked for typosquatting, its project alive, its licence compatible (a
copyleft or source-available licence such as BSL arriving on the path is a design change, not a
bump)? Is the lockfile
diff read, transitive additions included? Is a bump to a release younger than about three days
justified? Were install scripts and build plugins read? Do dev-only dependencies ship? Is a base
image, action or download pinned by digest or checksum? Can anything publish without a tag? Were
new warnings suppressed rather than fixed?

**Documentation, naming and readability.** Do names say what the thing is, fully, with no
customer-specific information? Do comments say why, not what, and is a `TODO` owned? Are touched
comments and doc strings still true? Does a new public symbol have a doc comment where the codebase
documents exports? Do user-facing docs and the limitations page change with behaviour? Is
formatting left to the formatter, with at most one comment if it did not run?

**Commit and PR hygiene.** Is the title an imperative or declarative sentence that stands alone in
`git log`? Does the body say the problem and why this approach? Is it one logical change, each
commit green? Does it link its issue or spec? Is there debug output, a stray file, formatting churn
or an unrelated edit the author would have caught by reading their own diff? Is CI green on a head
up to date with the base, with any skipped job explained? Where the project uses a trailer or a
commit style, is it present? Where it has a pull request template (the summary names it), does the
description follow the template's sections and keep to its length rule, with the detail linked
rather than pasted? Where it declares Conventional Commits (a `commit-msg` hook, a commitlint
configuration, its instructions), does every commit header and the PR title parse as
`type(scope)!: description`, is the type right for what the diff does, and is a breaking change
marked with `!` and a `BREAKING CHANGE:` footer?

### 6. Apply the language checklists

For each language skill loaded in step 3, read its files with that skill's checklist, in its order:
the groups that cause data loss or unsoundness first (nulls and totality, error handling,
concurrency, unsafe), then API and tests, then idioms. Skip every item the skill marks as already
enforced by tooling the repository runs, and every item the project's own rules overrule (a
convention the repository follows deliberately is not a finding, however many style guides
disagree). Note which groups you applied and which you skipped; they go in the report.

### 7. Confirm every finding

Before a finding goes in the report:

- Read the surrounding code and confirm the problem is real here, not merely a pattern that is
  often a problem. A pattern you could not confirm is a `question`, and says what would settle it.
- Decide whether the project's rules or tooling already cover it. If the formatter or linter owns
  it, drop it.
- Check it is in scope. A pre-existing problem the diff did not introduce is a `note` with a
  suggested issue, never a blocker.
- Write the fix or the next step with it. A finding with no resolution path is rewritten until it
  has one.
- Where a finding needs the code to be run to be sure (a race, a performance claim, a migration's
  lock), say so; a reviewer that only reads must not assert what only a run can show. Run the suite
  the diff touches when it is cheap: roughly a minute or less, no containers or external services,
  and the user has not said otherwise. Otherwise write "not run" and why. Either way the result is
  evidence for a finding, not the review.

### 8. Write the report

Use this template exactly, dropping an empty section. Lead with the verdict; the reader may stop
there.

```markdown
# Review: <title of the change>

**Verdict:** Request changes | Approve with comments | Approve
**What it does:** one or two sentences in your own words.
**Should it exist:** yes, because … | no, because … (then stop after the design finding)
**Read:** files read in full; files skimmed and why (generated, lockfile, data); surrounding code
opened outside the diff; anything not reviewed and needing another reviewer (security, database,
a language you have no skill for).
**Size:** N files, +A -D hand-written lines. (Say so if it should be split, and how.)

## Blocking
- `path/to/file.ext:LINE` — issue (blocking): what is wrong, in one sentence. Why it matters.
  Suggestion: the fix, as code when the fix is mechanical.

## Should fix
- `path:LINE` — issue (non-blocking) | suggestion | todo: … Add `(if-minor)` when the author may
  skip it if the fix grows.

## Nits
- `path:LINE` — nitpick (non-blocking): … (one entry per repeated pattern, not per instance)

## Questions
- `path:LINE` — question: … and what answer would resolve it. `thought:` for a consideration
  that needs no answer.

## Praise
- `path:LINE` — praise: something specific and why it is good. At least one when deserved.

## Out of scope
- note: a pre-existing problem seen on the way, with the issue to file.

## Checks applied
General checklist groups applied and skipped; language skills and their groups applied; project
rules read; enforced by tooling and therefore not reviewed; tests run (or "not run" and why).
```

Locations: a finding on a file in the diff carries `path:LINE`. A finding on a file the diff should
have changed but did not (a rule file, a docs page, a changelog) carries the path alone. A finding
on the description or a commit message is located as `Description` or `Commit <short sha>`.

The verdict follows the severities: any blocking finding is **Request changes**; only should-fix
and below is **Approve with comments**, saying whether the comments are optional or expected
before merge; nothing above a nit is **Approve**. Request changes means "I intend to block until I
approve", so never use it for comments. Do not bury the verdict after the findings.

## Severity

Every finding carries a label; an unlabelled comment reads as mandatory and robs the author of
the ability to prioritise. The labels are Conventional Comments (`label (decoration): subject`).

| Level | Label | Criterion |
|---|---|---|
| blocking | `issue (blocking)` + `suggestion` | A defect, a security or data-loss risk, an undeclared breaking change, a test that cannot fail, a change that makes the codebase worse, or scope the change should not contain. |
| should fix | `issue (non-blocking)`, `suggestion`, `todo` | A real improvement the standard asks for, whose absence still leaves the codebase no worse than before. Expected in this change or answered with a reason. `(if-minor)` when the author may skip it if the fix grows. |
| nit | `nitpick (non-blocking)` | Preference or polish. Never blocks; one comment per pattern. Style the formatter owns is not even a nit. |
| question | `question`, `thought` | You are not sure a problem exists. Prefer this to an assertion when uncertain. |
| praise, note | `praise`, `note` | Specific praise; or context for later, not for this change. |

Approve once the change definitely improves overall code health, even if imperfect; perfection is
not the bar and blocking for days over imperfections is a failure of the review. Do not approve a
change that worsens code health on a promise to clean it up later; later rarely comes. The only
exception is a real emergency (a production fix, a security hole, a legal deadline), which gets a
full review afterwards.

## Posting the review

Post to GitHub only when asked. Then:

- Inline comments for findings with a line: `gh api repos/{owner}/{repo}/pulls/N/reviews` with a
  `comments` array of `path`, `line`, `side: RIGHT`, `body`, and the verdict as `event`
  (`REQUEST_CHANGES`, `COMMENT`, `APPROVE`). Keep the summary as the review body.
- Never approve on the user's behalf unless they said to approve; a review that only comments uses
  `COMMENT` even when the verdict here is Approve.
- Say in the reply what was posted and where.

Whether or not anything was posted, remove the ref the gather script created
(`git update-ref -d refs/pr-review/N`; the summary prints the exact command).

## Customising the review

A repository shapes this review without forking it, in three layers, each of which beats the one
below and all of which beat this skill:

1. **Its instruction and rule files.** `CLAUDE.md`, `AGENTS.md`, `.claude/rules/*.md` and the rest
   of step 2 are always read, and a rule they state overrules any item here. Nothing to configure.
2. **`REVIEW.md`.** The repository's own review checklist, in prose or as a list, at the root and
   in any directory that wants more (a `migrations/REVIEW.md` for its schema rules). Its items are
   applied beside the general checklist. An optional `## pr-review` section says what of this
   plugin not to use:

   ```markdown
   ## pr-review
   - skip: pr-review-typescript        # the repository has its own TypeScript rules below
   - skip: general/Commit and PR hygiene
   - load: acme-review-rules           # a project or personal skill, loaded as well
   ```

   A hosted review service that reads `REVIEW.md` sees the same checklist and ignores the section.
3. **Same-named skills.** A project or personal skill named `pr-review-<language>` replaces the
   bundled one for that language, for a team whose rules for one language are its own. Keep the
   bundled skill's shape (detect the dialect, what tooling enforces, the checklist by harm,
   severity hints) so the orchestrator's steps still apply.

A repository that wants a different procedure altogether writes its own orchestrator skill and does
not install this one; the language skills can still be loaded from it by name.

## How a reviewer goes wrong

Hold these in mind while writing; each is a documented failure mode of human reviewers and
reviewers like you.

- Commenting on formatting or taste the formatter or style guide owns. Technical facts and the
  style guide decide; where both are silent, the author's choice stands.
- Line comments before the design verdict, on code a redesign will delete.
- Asserting a pattern is a bug without reading the context that would show it is not.
- "While you're at it": blocking on pre-existing debt or asking for unrelated work.
- Treating green CI, coverage or a passing test as proof the behaviour is right.
- Reviewing only the diff and missing the caller, the sibling implementation, the second copy.
- Repeating one nit on every instance instead of naming the pattern once.
- Writing "you" and adjectives about the author. Write about the code, as "this" or "we", say
  why, and avoid "just", "simply", "obviously", "always", "never".
- Explaining in the thread what should be made clear in the code.
- Hiding that a part was not understood. Say what is unclear and ask for the code to be clearer.
- Inventing findings to look thorough. A short review of a good change, with the praise it
  deserves, is a correct review.

## References

- `references/general-checklist.md` — the full checklist with the concrete diff signal, the
  reason and the source for every item; the process rules; the severity model; the open questions
  where the sources disagree.
- `scripts/gather.sh` — collects description, commits, files, diff and a language and size
  summary for a PR, branch, range or working tree.
