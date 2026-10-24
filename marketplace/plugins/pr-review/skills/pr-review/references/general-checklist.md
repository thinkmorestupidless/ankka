# Code review practice: research for a language-agnostic review checklist

*Compiled 2026-10-23 from the primary sources listed below. Every URL in "Sources" was fetched and
read; claims carry the source in brackets. Where a source gives a number, the number is quoted
as the source gives it.*

## 1. Sources

### Google
- https://google.github.io/eng-practices/review/index.html — what reviewers look for (design, functionality, complexity, tests, naming, comments, style, documentation); who the best reviewer is.
- https://google.github.io/eng-practices/review/reviewer/standard.html — the standard: approve once the CL "definitely improves the overall code health", not when perfect; data over opinion; style guide is final authority; `Nit:` prefix; escalation path.
- https://google.github.io/eng-practices/review/reviewer/looking-for.html — the per-area checklist (design, functionality, complexity, tests, naming, comments, style, consistency, documentation, every line, context, good things).
- https://google.github.io/eng-practices/review/reviewer/navigate.html — reading order: description, then the main files, then the rest; send design comments immediately.
- https://google.github.io/eng-practices/review/reviewer/speed.html — one business day maximum; don't interrupt focus; LGTM with comments; splitting large CLs.
- https://google.github.io/eng-practices/review/reviewer/comments.html — comment on the code not the developer; explain why; labels `Nit:`, `Optional:`/`Consider:`, `FYI:`; explanations in the review tool don't help future readers.
- https://google.github.io/eng-practices/review/reviewer/pushback.html — who is right; "clean it up later" rarely happens; insist before landing.
- https://google.github.io/eng-practices/review/developer/small-cls.html — 100 lines reasonable, 1000 too large; one self-contained change; refactorings separate; tests in the same CL; each CL keeps the build green.
- https://google.github.io/eng-practices/review/developer/cl-descriptions.html — first line as an imperative sentence; body says the problem and why this approach; re-check description before submit.
- https://google.github.io/eng-practices/review/developer/handling-comments.html — fix the code, not the review thread; never answer in anger.
- https://google.github.io/eng-practices/review/emergencies.html — what is and is not an emergency.
- https://abseil.io/resources/swe-book/html/ch09.html — *Software Engineering at Google* ch. 9: three approvals (LGTM, owner, readability); ~200-line changes; one reviewer usually enough; 35% of changes touch one file; automate the mechanical.
- https://abseil.io/resources/swe-book/html/ch11.html — ch. 11: test sizes (small tests: no sleep, I/O, network); hermetic tests; flakiness "approaching 1%" destroys value; 80/15/5 mix; coverage caveats.
- https://abseil.io/resources/swe-book/html/ch12.html — ch. 12: unchanging tests, public APIs, state not interactions, behaviours not methods, no logic in tests, DAMP over DRY, brittle tests.
- https://google.aip.dev/180 — Google API Improvement Proposal 180, Backwards compatibility: the enumerated list of compatible and breaking changes.
- https://sre.google/sre-book/monitoring-distributed-systems/ — four golden signals; symptoms vs causes; "every page should be actionable".
- https://www.hyrumslaw.com/ — Hyrum's Law: all observable behaviours become depended upon.
- Fetched but body not captured (only reader comments came back), so not relied on: https://testing.googleblog.com/2015/01/testing-on-toilet-change-detector-tests.html, https://testing.googleblog.com/2013/08/testing-on-toilet-test-behavior-not.html, https://testing.googleblog.com/2017/04/where-do-our-flaky-tests-come-from.html.

### Microsoft
- https://microsoft.github.io/code-with-engineering-playbook/code-reviews/ — goals: improve quality, learn, build shared understanding; review every PR.
- https://microsoft.github.io/code-with-engineering-playbook/code-reviews/process-guidance/ — SLA in the working agreement; measure time-to-merge; linters and CI handle nits.
- https://microsoft.github.io/code-with-engineering-playbook/code-reviews/process-guidance/reviewer-guidance/ — read every line; design pass then quality pass; the concrete question lists (complexity, error handling, race conditions, PII in logs, tests in the same PR, ">3 arguments").
- https://microsoft.github.io/code-with-engineering-playbook/code-reviews/process-guidance/author-guidance/ — description, reviewers, "I'll do it later" not acceptable, out of scope becomes a work item, checklists.
- https://microsoft.github.io/code-with-engineering-playbook/code-reviews/pull-requests/ — size, one goal per PR, don't break the build, Conventional Commits.
- https://microsoft.github.io/code-with-engineering-playbook/code-reviews/pull-request-template/ — template sections incl. "Does this introduce a breaking change?".
- https://microsoft.github.io/code-with-engineering-playbook/code-reviews/evidence-and-measures/ — branch policies, bug-to-PR traceability, "would review have caught this", checklist upkeep.
- https://microsoft.github.io/code-with-engineering-playbook/code-reviews/faq/ — too-large PRs, speeding reviews, pair/mob review.
- https://microsoft.com/en-us/research/wp-content/uploads/2016/05/MS-Code-Review-Tech-Report-MSR-TR-2016-27.pdf — MacLeod, Greiler, Storey, Bird, Czerwonka, *Code Reviewing in the Trenches* (MSR-TR-2016-27): challenges (timely feedback, large reviews, understanding), author/reviewer/organisation best practices; only 26% of authors wrote descriptions.
- https://zenodo.org/records/1401198 (paper.pdf) — Bacchelli & Bird, *Expectations, Outcomes, and Challenges of Modern Code Review* (ICSE 2013): 570 comments; code improvements 29%, defects 14% (65 of 78 logical); "understanding is their main challenge".

### SmartBear / Cisco
- https://smartbear.com/learn/code-review/best-practices-for-peer-code-review/ — 200–400 LOC per review; under 500 LOC/hour; no more than 60 minutes; author annotation; checklists; "ego effect" of 20–33% spot checks; lightweight reviews take <20% of formal time and find as many defects.

### OWASP
- https://owasp.org/www-project-code-review-guide/ and https://github.com/OWASP/www-project-code-review-guide — project pages (lab project, CC BY-SA, where the PDF lives).
- https://owasp.github.io/www-project-code-review-guide/assets/OWASP_Code_Review_Guide_v2.pdf — Code Review Guide v2.0 (text extracted locally): checklist areas; the Appendix checklist (authorization placement, "Keys are not held in code", logs not logging passwords/PII, validation applied to all inputs, output encoding, session rules); Reviewing Logging Code; Error Handling ("catching an exception is not automatically handling it"); Race Conditions (what to grep for).
- https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html — events to log; when/where/who/what; data to exclude (session ids, tokens, passwords, connection strings, keys, PII); CR/LF log injection; structured logs.
- https://cheatsheetseries.owasp.org/cheatsheets/Error_Handling_Cheat_Sheet.html — generic messages to users, details server-side, global handler, 4xx vs 5xx, RFC 9457.
- https://cheatsheetseries.owasp.org/cheatsheets/Input_Validation_Cheat_Sheet.html — allowlist, server-side, syntactic+semantic, trust boundaries, regex anchoring and backtracking, file upload metadata untrusted, validation is not the injection defence.
- https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html — deny by default, every request, server-side, IDOR, static resources, tests for authz logic.
- https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html — no custom crypto, AES-GCM/CCM, RSA ≥2048 with OAEP, CSPRNG, keys not in code or VCS, KEK/DEK separation, rotation.
- https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html — never logged, revoke on exposure, not in Docker ENV/ARG, least privilege, pre-commit detection.

### Comment conventions and etiquette
- https://conventionalcomments.org/ — `<label> [decorations]: <subject>` + discussion; labels praise, nitpick, suggestion, issue, todo, question, thought, chore, note (optional typo, polish, quibble); decorations `(blocking)`, `(non-blocking)`, `(if-minor)`.
- https://conventionalcomments.org/communication/ — be curious, mentor patiently, actionable comments, combine similar comments, "we" not "you", tone comes only from words.
- https://github.com/thoughtbot/guides/tree/main/code-review — ask not demand; no "just"/"simply", no hyperbole; one comment per repeated style issue; author has final editorial control; isolated fix-up commits; merge only when CI green.
- https://docs.gitlab.com/development/code_review/ — reviewer vs maintainer; ~200-line target; acceptance checklist (backwards compatible, flaky tests fixed, instrumentation, feature flag for risk); database/security review triggers; new dependencies need scrutiny; Conventional Comments with non-blocking.
- https://raw.githubusercontent.com/github/how-engineering-communicates/main/how-github-engineering-communicates.md — "changes requested" only when you intend to block; inline over document-wide comments; "I intend to" not "I decided"; one business day response.
- https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/getting-started/best-practices-for-pull-requests — small focused PRs; review your own PR first; point reviewers to the main files; extra care on dependencies, auth, permissions, workflows.
- https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/reviewing-changes-in-pull-requests/about-pull-request-reviews — Comment / Approve / Request changes semantics.
- https://www.michaelagreiler.com/code-review-checklist/ — a general checklist (implementation, logic, error handling, testing, dependencies, security, performance, readability, experts, ethics).

### Topic checklists
- https://docs.kernel.org/process/submitting-patches.html — one logical change per patch; each patch builds and runs; quantify optimisations with numbers; imperative mood; `Fixes:` tags; respond to every reviewer.
- https://cbea.ms/git-commit/ — the seven rules of a commit message; "what and why, not how".
- https://www.conventionalcommits.org/en/v1.0.0/ — `type(scope): description`; `BREAKING CHANGE:` footer or `!`; SemVer mapping.
- https://semver.org/ — MAJOR/MINOR/PATCH criteria; must declare a public API; deprecate in a minor before removing in a major; 0.y.z is unstable.
- https://docs.gitlab.com/development/migration_style_guide/ — time limits (3 min regular, 10 min post-deploy); never require downtime; reversible with tested `down`; `with_lock_retries`; concurrent indexes; FK `validate: false` then validate; one FK per transaction; batching; no application models in migrations.
- https://docs.gitlab.com/development/database_review/ — reviewer checklist for migrations and queries: structure.sql only has this MR's changes; queries <100 ms; SQL and plan for each query, run on real-sized data; N+1.
- https://github.com/ankane/strong_migrations — the catalogue of dangerous DDL and the safe recipe for each (remove column, change type, rename, non-concurrent index, FK, check constraint, NOT NULL, volatile default, `json` vs `jsonb`, renaming enum values).
- https://docs.gitlab.com/development/merge_request_concepts/performance/ — "performant by default"; must not increase query count; no SQL in loops; eager load; timeouts on external calls; batch external calls; pagination mandatory; memory must not grow; no external calls inside DB transactions.
- https://best.openssf.org/Concise-Guide-for-Developing-More-Secure-Software — evaluate before adding a dependency; verify the name (typosquatting); package managers; SCA scanning; ~3-day cooldown on updates; no secrets in repos; negative tests; review before merge via protected branches; semver and deprecation.
- https://best.openssf.org/Concise-Guide-for-Evaluating-Open-Source-Software — necessity, authenticity, activity in 12 months, more than one maintainer, licence present and OSI, secure defaults, install scripts and obfuscation, dev-only deps shipping to production.
- https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/about-dependency-review — what dependency review surfaces (added/removed/updated incl. transitive, vulnerabilities, licence, age) and enforces (severity threshold, licence allow/deny).
- https://apache.googlesource.com/druid/+show/HEAD/dev/code-review/concurrency.md — Apache Druid's concurrency code-review checklist (Java-flavoured but general): rationalised in the description, documented thread-safety, check-then-act, lock ordering, blocking inside locks, lazy init, interruption, time units, named daemon threads, multi-threaded tests.

## 2. Review process rules

Each rule: the rule, why, source.

1. **Read the description first and decide whether the change should exist at all before reading code.** If the change should not happen, say so immediately with the reason and what to do instead; otherwise every later comment is wasted. [Google navigate; Google looking-for "Design"]
2. **Understanding is the review.** Reviewers at Microsoft named understanding the change's purpose, motivation and implementation as their main challenge; a reviewer who cannot say what the change is for cannot find its defects. Ask the author before commenting on details. [Bacchelli & Bird 2013; MSR-TR-2016-27 §2.2]
3. **Find the main files (most logical change) and review them first; send design-level findings at once, before finishing the rest.** Authors build on in-flight work and a redesign invalidates line comments. [Google navigate]
4. **Then read every remaining line, in a logical order; reading tests first is a good way to learn intent.** Data files and generated code may be skimmed; human-written code must be understood. If you reviewed only part, say which part. [Google looking-for "Every line"; Microsoft reviewer-guidance; Google navigate]
5. **Look beyond the diff.** Open the whole file or surrounding code when the diff is not enough to judge the change; a change correct in isolation can degrade the system. [Google looking-for "Context"; Microsoft reviewer-guidance]
6. **Size the review before you start: 200–400 lines at a time, under 500 lines per hour, no more than about 60 minutes in one sitting.** Defect discovery falls off beyond these; the reviewer's attention is the constrained resource. [SmartBear/Cisco items 1–3; MSR-TR-2016-27 "dedicated but bounded time"]
7. **A reviewer may reject a change for being too large, and ask for it to be split into sequential, individually green changes.** 100 lines is "usually reasonable", 1000 "usually too large"; Google and GitLab both target about 200. Exceptions: whole-file deletions and trusted tool-generated refactors. [Google small-cls; swe-book ch. 9; GitLab code_review]
8. **Verify the description still matches the diff.** Descriptions written before review drift as the CL changes; the description becomes permanent history. [Google cl-descriptions "Review the description before submitting"]
9. **Confirm the change does one thing: unrelated edits, drive-by refactors and mixed bug-fix-plus-cleanup are grounds to ask for a split.** One reason to change per PR makes review, revert and bisect possible. [Google small-cls "Separate out refactorings"; kernel submitting-patches "Separate your changes"; Microsoft pull-requests]
10. **Approve once the change definitely improves overall code health, even if imperfect.** Perfection is not the bar; continuous improvement is. Do not block for days over minor imperfections. [Google standard]
11. **Do not approve a change that worsens code health, except in a real emergency; and "clean it up later" is not acceptable for complexity this change adds.** Later cleanups rarely happen. Pre-existing problems nearby may be deferred with a filed bug and a TODO naming it. [Google standard; Google pushback "Cleaning it up later"]
12. **Emergencies are narrow: a small change that keeps a launch from rolling back, fixes a production bug significantly affecting users, closes a major security hole or handles a pressing legal issue.** "End of day Friday", soft deadlines, a long-worked-on feature and reviewers asleep in another time zone are not emergencies. Emergency changes get a thorough review afterwards. [Google emergencies]
13. **Technical facts and data beat opinions and personal preference; the style guide is the final authority on style; where the guide is silent, follow the surrounding code or accept the author's choice.** If the author shows several approaches are equally valid, the reviewer accepts the author's. [Google standard "Principles"]
14. **Comment on the code, not the developer; say why; mark severity on every comment.** Unlabelled comments are read as mandatory and the author cannot prioritise. [Google comments; Conventional Comments]
15. **Prefer questions and "we" over demands and "you"; avoid "just", "simply", "always", "never" and sarcasm; be explicit because intent is lost in text.** [thoughtbot; Conventional Comments communication; GitLab code_review]
16. **Every comment must say how it is resolved; batch repeated instances of the same issue into one comment, ideally with a patch or a suggested change.** [Conventional Comments communication; thoughtbot "Style comments"]
17. **If you asked for an explanation, the normal outcome is clearer code or a code comment, not a reply in the review tool.** Review-thread explanations are invisible to future readers. [Google comments "Accepting explanations"; Google handling-comments]
18. **Out-of-scope findings do not block this PR; file them as separate work.** [Microsoft reviewer-guidance "Keep focus on scope"; Microsoft author-guidance; thoughtbot "since you're at it"]
19. **"LGTM with comments" is legitimate when the remaining comments are minor, optional, or the author will reliably address them; say which applies.** Especially across time zones. [Google speed "LGTM With Comments"]
20. **"Request changes" only when you intend to block the merge until you approve; otherwise "Comment".** [GitHub how-engineering-communicates; GitHub about-pull-request-reviews]
21. **Respond within one business day at most; a typical change should get several rounds in a day; do not interrupt focused work to do it.** Review latency, not review thoroughness, is the top complaint of authors. [Google speed; swe-book ch. 9; MSR-TR-2016-27 §2.1; GitHub how-engineering-communicates]
22. **If you lack expertise for part of a change (security, concurrency, privacy, accessibility, database, i18n), make sure someone qualified also reviews it, and say so.** [Google looking-for "Every line"; GitLab review triggers]
23. **Approving means sharing responsibility for the change.** A GitLab maintainer who approves may be paged for the incident. The reviewer takes enough time that LGTM means "meets our standards". [GitLab code_review "Maintainer"; Google speed "Fast responses"]
24. **Point out what was done well, specifically, and say why it is good.** Praise teaches as much as criticism; leave at least one sincere praise per review. [Google looking-for "Good things"; Google comments; Conventional Comments "praise"]
25. **Automate the mechanical before humans review: formatters, linters, static analysis, dependency and secret scanning.** Reviewer time then goes to design, correctness and tests. [swe-book ch. 9; Microsoft process-guidance; GitLab code_review; OpenSSF developing guide §3]
26. **When a thread stalls after a few rounds, move to a synchronous conversation and write the outcome back into the PR.** Do not let a change sit because author and reviewer cannot agree; escalate to a lead or maintainer. [Google standard "Resolving conflicts"; Microsoft reviewer-guidance; thoughtbot]

## 3. Checklist: what to check in any diff

Format: **Rule.** Concrete signal in the diff. Why. [source]

### Correctness

- **The change does what the description says and nothing else.** Compare each claim in the description with a hunk that implements it; flag hunks no claim covers and claims no hunk covers. The description is the contract the reviewer verifies against. [Google looking-for "Functionality"; kernel submitting-patches "Describe your changes"; Greiler checklist]
- **Boundary and edge inputs are handled.** Look at every comparison and loop bound (`<` vs `<=`), empty collections, zero, negative, maximum sizes, missing optionals, empty strings, Unicode. Logical errors in conditions were 65 of the 78 defects Bacchelli & Bird found in review comments. [Bacchelli & Bird 2013 §V; Google looking-for "Functionality"]
- **Every branch of a new conditional does the right thing, including `default`/`else`.** A `switch`/`match` without a default, an `if` with a missing `else` that leaves state half-set, early returns that skip cleanup. "Not all errors are exceptions." [OWASP Code Review Guide, Error Handling]
- **Null/absent handling follows the codebase's convention.** New nullable returns, `Option`/`Optional` unwrapped without a check, JSON `null` versus absent field semantics. [Google looking-for "Functionality"]
- **Off-by-default behaviour is preserved for existing callers.** A changed default argument, a changed sort order, a changed default page size. Clients depend on observable behaviour whether documented or not. [AIP-180 "Changing default values"; Hyrum's Law]
- **Resource lifetimes are balanced.** Every open/acquire/lock/start in the diff has a close/release/unlock/stop on every path, including the error path (`finally`, RAII, `using`, `defer`). [OWASP Code Review Guide, Error Handling ("resources should be released")]
- **Numeric and time arithmetic is unit-safe.** Mixed units in one expression, `currentTimeMillis` used for intervals, monotonic time compared without overflow awareness, negative timeouts not clamped. [Druid concurrency checklist "Time"]
- **Copy-paste divergence is intentional.** Two near-identical blocks in the diff where one has an edit the other lacks. [Google looking-for "Functionality" (bugs visible by reading)]
- **Deleted code was dead.** For each removal, no remaining caller, no reflective or string-keyed use, no persisted reference (wire names, event types, timers). Deleting dead code is a valued improvement, deleting live code is a defect. [swe-book ch. 9 "Behavioral changes"]
- **UI changes have been seen.** A screenshot, GIF or demo for any user-visible change; the reviewer cannot judge pixels from a diff. [Google looking-for "Functionality"; Microsoft reviewer-guidance; thoughtbot]

### Design and scope

- **The change belongs here.** Does this logic live in the right module/layer/service, or in a library? Would a user of the codebase look for it here? [Google looking-for "Design"; Greiler "right file/folder/package"]
- **Interactions between the new pieces make sense and fit the existing architecture and patterns.** New abstractions that duplicate an existing one; a second way to do something the codebase already does one way. [Google looking-for "Design"; Microsoft reviewer-guidance "Design"]
- **No speculative generality.** Parameters, interfaces, plugin points or configuration nobody uses in this change. "Solve the problem they know needs to be solved now." [Google looking-for "Complexity"]
- **Functions and classes stay understandable: one responsibility, few arguments.** Microsoft's heuristic: more than three arguments is a complexity smell; a function that needs a comment to explain its sections should be split. [Microsoft reviewer-guidance "Complexity"]
- **Existing functionality is reused rather than reimplemented.** A new helper with the same shape as an existing one; a hand-rolled version of something the standard library or an existing dependency does. [Greiler "Does similar functionality already exist?"; OpenSSF evaluating guide "Necessity"]
- **A new API comes with a use in the same change.** An interface, endpoint or function added without a caller cannot be judged for fit. [Google small-cls "What is small?"]
- **Refactoring and behaviour change are separate.** A diff that both moves code and changes it hides the behaviour change inside the move. Ask for the refactor first, behaviour second, each green on its own. [Google small-cls "Separate out refactorings"; kernel submitting-patches]
- **The change is reversible.** Can it be rolled back on its own? Is risky behaviour behind a feature flag that can be turned off without a deploy? [GitLab code_review acceptance checklist "Deployment"; Microsoft faq]
- **Consistency with the surrounding code where no rule decides.** If the style guide is silent, match the file; do not introduce a second convention. [Google standard "Principles"; looking-for "Consistency"]
- **Design-level objections go first and alone.** If the overall shape is wrong, say so immediately and hold the line-level comments. [Google navigate step two]

### Error handling and edge cases

- **Every caught exception is handled, not merely caught.** A `catch` that logs and continues as if nothing happened, a swallowed exception with an empty body, `except Exception: pass`. "Catching an exception is not automatically handling it." [OWASP Code Review Guide, Error Handling; Microsoft reviewer-guidance "Error handling"]
- **Failure leaves the system in a safe state.** Partial writes without rollback, a lock or session left open on error, a half-initialised object published. Fail securely: halt the operation, release resources, do not proceed with partial validation. [OWASP Code Review Guide, Error Handling; OWASP Input Validation "Structured data"]
- **Errors returned to callers are generic; details go to the server log.** Stack traces, framework versions, SQL, file paths or internal hostnames in a response body or API error. [OWASP Error Handling Cheat Sheet]
- **Status codes are correct.** 4xx for client-caused errors, 5xx only when the server cannot fulfil; a structured error format (RFC 9457 Problem Details) where the codebase has one. [OWASP Error Handling Cheat Sheet]
- **Interrupt and cancellation are respected.** `InterruptedException` swallowed outside a task boundary without restoring the flag; a `Future` not cancelled on timeout; a cancellation token ignored in a loop. [Druid concurrency checklist "Thread interruption"]
- **Timeouts exist on every external call.** New HTTP/database/queue client calls with no timeout, or with an unbounded retry. [GitLab performance guidelines "Timeouts"]
- **Retries are bounded and idempotent.** A retry around a non-idempotent write; a retry with no backoff or cap. [GitLab performance guidelines; SRE book (retries hide failures)]
- **Input at every trust boundary is validated, and the failure is rejected not patched.** Data from internal services, queues and stored rows counts as crossing a boundary too. [OWASP Input Validation "Trust boundaries"]
- **Error messages to users are accurate and do not over-promise.** The message describes what happened and what the user can do; it does not claim an action that did not occur. [Greiler "Are error messages user-friendly?"]
- **Negative tests exist for the error paths.** See Tests. [OpenSSF developing guide §6]

### Concurrency and state

- **Concurrency is rationalised in the description.** A diff that introduces threads, async tasks, locks, atomics or shared caches with no sentence about the threading model. [Druid concurrency checklist "Design"]
- **Shared mutable state is guarded or confined.** A field written from one thread and read from another with no lock, atomic or `volatile`/memory-barrier; a static mutable field; a non-thread-safe collection reached from a handler that runs per request. [Druid "Insufficient synchronization"; OWASP Code Review Guide, Race Conditions ("static methods and variables")]
- **No check-then-act races.** `if (!map.containsKey(k)) map.put(k, v)`, `if (!exists(path)) create(path)`, read-modify-write such as `counter++` on shared state; `get()` followed by `put()` on a concurrent map. Use `computeIfAbsent`/`putIfAbsent`/CAS. [Druid "Race conditions"; OWASP Code Review Guide, Race Conditions]
- **Lock ordering is consistent and documented when locks nest.** Two locks acquired in different orders on two paths; a lock held while calling into an extension point, a callback or user code. [Druid "Avoiding deadlocks"]
- **No blocking or I/O while holding a lock, inside a `compute()` lambda, or on a thread pool that forbids it.** Network calls inside `synchronized`; blocking inside a `ForkJoinPool`/parallel stream; a `CachedThreadPool` doing network I/O. [Druid "Avoiding deadlocks", "Threads and Executors"]
- **Critical sections are as small as possible and getters do not leak mutable internals.** Returning a live collection from a thread-safe class; iterating a synchronised collection outside its lock. [Druid "Race conditions", "Improving scalability"]
- **Lazy initialisation is thread-safe or provably single-threaded.** Double-checked locking without the correct pattern; a lazily-built cache raced by invalidation. [Druid "Lazy initialization"]
- **Threads and executors are named, daemon where appropriate, and shut down.** A `new Thread` per call instead of an executor; an executor never shut down; an unnamed pool that defeats thread dumps. [Druid "Threads and Executors"]
- **Thread-local or request-scoped context survives hand-off.** Work submitted to another thread or executor that reads request context, trace or caller identity from a thread-local. [Druid "Documentation" (threading model and concurrent control flow described), "Thread/Task/Serial thread confinement"]
- **Time measurement uses a monotonic clock, with units in names.** `timeoutMs` vs a bare `timeout`; wall-clock differences used as durations. [Druid "Time"]
- **Thread-safe classes have multi-threaded tests, and tests do not share a `Random` or uncoordinated start.** [Druid "Testing"]
- **Benign races are labelled.** Deliberately unsynchronised reads carry a comment saying why they are safe. [Druid "Documentation"]

### Security

- **No secrets in the diff.** API keys, passwords, tokens, private keys, connection strings, `.env` contents, Docker `ENV`/`ARG` secrets, test fixtures with real credentials. "Keys are not held in code." Anything that landed must be revoked, not just removed. [OWASP Code Review Guide checklist; OWASP Secrets Management; OpenSSF developing guide §9; OWASP Cryptographic Storage "Key storage"]
- **Authorization is checked server-side on every new entry point, before processing, for the specific object.** A new route/handler/RPC without an ACL; an object looked up by client-supplied id with no ownership check (IDOR); a check skipped for an "internal" path; execution not halted after a failed check. Deny by default. [OWASP Authorization Cheat Sheet; OWASP Code Review Guide checklist "placement of authentication and authorization check"]
- **Authentication changes get an expert look.** Token verification, session handling, cookie flags, password handling touched: route to a security reviewer and label the change. [GitLab code_review "Security" triggers; GitHub best-practices "Review for security"]
- **Untrusted input is validated by allowlist, on the server, syntactically and semantically, before use.** Client-only validation; denylist regexes; unanchored regexes (`^...$` missing) or patterns prone to backtracking; unbounded lengths; nested-depth or size limits missing on parsers. [OWASP Input Validation Cheat Sheet]
- **Injection is prevented by construction, not by validation.** String-built SQL, shell commands, LDAP, XPath, log lines or HTML with interpolated input; use parameterised queries, argument arrays, output encoding, a maintained sanitiser for rich HTML. [OWASP Input Validation ("validation is not the primary defence"); OWASP Code Review Guide A1 and checklist "correct type of encoding"]
- **File handling treats name, type and content as untrusted.** Path built from user input (traversal); extension or declared content type trusted as proof of content. [OWASP Input Validation "File uploads"]
- **Cryptography is standard and parameterised correctly.** Any hand-written cipher/hash/random; MD5/SHA-1 for security; ECB mode; RSA without OAEP; AES key under 128 bits; `Math.random`/non-CSPRNG for tokens, ids, IVs, salts; UUID as a secret. [OWASP Cryptographic Storage Cheat Sheet; OWASP Code Review Guide checklist "custom schemes for hashing"]
- **Passwords are hashed with a password hash, never encrypted or logged.** Reversible encryption of passwords; password in a log, console, URL or GET parameter. [OWASP Cryptographic Storage "Password storage"; OWASP Code Review Guide checklist]
- **Sensitive data is not logged.** Session ids, access tokens, passwords, connection strings, keys, card data, health/government identifiers, and personal data beyond what is sanctioned; names/emails/paths/internal addresses handled deliberately. [OWASP Logging Cheat Sheet "Data to exclude"; Microsoft reviewer-guidance "Are we logging any PII?"]
- **Logs cannot be forged.** User-controlled text written into logs without CR/LF and delimiter sanitisation; prefer structured logging. [OWASP Logging Cheat Sheet; OWASP Code Review Guide, Reviewing Logging Code]
- **Security-relevant events are logged.** New authentication, authorization-failure, validation-failure, admin or sensitive-data paths with no audit event. [OWASP Logging Cheat Sheet "Events to log"]
- **Privileges are minimal.** A new credential, role, IAM grant, container capability or DB account broader than the operation needs; elevated OS privileges for external commands. [OWASP Code Review Guide checklist "Are privileges reduced whenever possible?"; OWASP Secrets Management "least privilege"]
- **Security misconfiguration is not introduced.** Debug/developer error pages, permissive CORS, disabled TLS verification, `Access-ALL` style defaults, verbose server banners. [OWASP Error Handling ("developer error pages in development only"); OWASP Code Review Guide checklist "default configuration like Access-ALL"]
- **Redirects, deserialisation and SSRF sinks take no raw user input.** Open redirect to a user-supplied URL; deserialising untrusted bytes; an outbound request to a user-supplied host. [OWASP Code Review Guide A10 and A1 topics; OWASP Logging "deserialization failures" as a loggable event]
- **CI workflow and build-script changes are reviewed as code with production access.** A changed GitHub Actions workflow, new third-party action, curl-pipe-sh, secrets echoed in pipeline output. [GitHub best-practices "Review for security" (workflows); OWASP Secrets Management "pipeline output"]

### Data and migrations

- **Every schema change is reversible, and the rollback is written and tested (or explicitly a documented no-op).** A migration with no `down`; a data migration with no recovery plan. [GitLab migration_style_guide "Reversibility"; GitLab database_review]
- **The old application version must work against the new schema and the new version against the old.** Rolling deploys run both at once; a column rename, type change or NOT NULL in one step breaks whichever version is still running. Expand first, migrate, contract later, in separate releases. [GitLab migration_style_guide (no downtime); strong_migrations (rename/type change recipes); GitLab acceptance checklist "backwards compatible across updates"]
- **No table-rewriting or long-locking DDL on a live table.** Non-concurrent index creation; adding a FK or check constraint without `validate: false` then validate; `SET NOT NULL` without a validated check constraint first; a column type change; a volatile default (`gen_random_uuid()`) added with the column. [strong_migrations; GitLab migration_style_guide "Indexes", "Foreign keys"]
- **DDL on busy tables uses lock timeouts and retries; one lock per transaction.** Two FKs in one transaction; no `lock_timeout`; statement timeouts disabled without a reviewer's agreement. [GitLab migration_style_guide "Transactions and locks"]
- **Migrations stay within time budgets and never block deploy.** GitLab's budget: regular migrations ≤3 minutes, post-deploy ≤10, anything longer batched in the background with each query under 1 second. [GitLab migration_style_guide "Timing"]
- **Backfills are batched, throttled and outside the DDL transaction.** An `UPDATE ... SET` over a whole table inside the migration. [strong_migrations "Backfilling data"; GitLab migration_style_guide "Data migrations and batching"]
- **Migrations do not import application models or code that will change.** A migration that calls a domain class; use plain SQL or a local model with explicit table name. [GitLab migration_style_guide "Application code and models"]
- **Removing a column or table follows the ignore-then-drop sequence.** Drop in the same release as the code that stops reading it; ORM column caches then error until restart. [strong_migrations "Removing a column"; GitLab migration_style_guide "Dropping tables"]
- **Enum values are only added, never renamed or removed in one step.** [strong_migrations "Renaming an enum value"]
- **New queries come with their SQL and plan, run against realistic data volumes; new tables come with indexes for FK, WHERE, ORDER BY, GROUP BY and JOIN columns and a growth estimate.** Individual queries target under 100 ms. [GitLab database_review author and reviewer checklists]
- **Persisted formats (events, snapshots, messages, wire names) are evolved, not edited.** A renamed field in a stored event type with no upcaster; a changed serialised enum name. Stored data is an API with the past. [AIP-180 (renames are remove-and-add); semver "public API"]
- **Existing data variety is considered.** A new validation that existing rows would fail; make it optional or backfill first. [GitLab acceptance checklist "Existing data variety"]
- **Deletes of data are labelled and justified.** A migration or job that deletes rows carries the impact and recovery statement. [GitLab database_review "Data migrations" (~data-deletion)]

### Performance

- **Query count does not grow with the size of a collection (no N+1).** A query inside a loop; a per-item lookup where a batch fetch exists; associations not eager-loaded when more than one row is read. [GitLab performance guidelines "Query counts", "Loops and eager loading"; Microsoft reviewer-guidance "redundant database calls"]
- **The change does not increase the total number of SQL queries or duplicated cached queries "unless absolutely necessary", and a test pins the count.** [GitLab performance guidelines "QueryRecorder"]
- **Calls to external services are batched and never made inside a database transaction.** [GitLab performance guidelines "Batching", "Database transactions"]
- **Lists are paginated; counts above a threshold are truncated or asynchronous.** A new endpoint returning an unbounded list; an exact `COUNT(*)` on a large table in a request path. [GitLab performance guidelines "Pagination", "Badge counters"]
- **Memory does not grow with input size unnecessarily.** Whole-file or whole-response reads into memory where streaming exists; large temporary files. [GitLab performance guidelines "Memory usage", "Storage"]
- **Slow or external work is off the request thread.** Synchronous third-party calls in a request handler; use background jobs with timeouts. [GitLab performance guidelines "Timeouts and background jobs"]
- **Claimed optimisations carry numbers.** A description that says "faster" with no measurement, or omits the cost (memory, readability). [kernel submitting-patches "Quantify optimizations"; GitLab code_review (benchmark results as diff comments)]
- **Hot-path work is proportionate.** New allocation, logging, serialisation or locking in a loop that runs per request or per record; parallel streams for sub-100µs work. [Druid "Parallel Streams"; Google looking-for "Complexity"]
- **Caching has an invalidation story.** A new cache with no expiry, no key scoping (per-tenant), or no size bound. [GitLab performance guidelines "Caching"]
- **Performance-critical features have a kill switch.** A feature flag that can disable a known-heavy path. [GitLab performance guidelines "Feature flags"]
- **Indexes are justified, named, and no wider than needed.** A non-unique index with more than three columns; an index whose use no query in the diff explains. [strong_migrations "best practice"; GitLab migration_style_guide "Indexes"]

### Tests

- **Behavioural changes ship with tests in the same change; "I'll add tests next" is rejected.** A diff touching logic with no test file touched. Pure refactors are covered by existing tests or get them first. [Google looking-for "Tests"; Google small-cls "Keep related tests in the same CL"; Microsoft reviewer-guidance "Tests"]
- **Each test would fail if the behaviour it names were broken.** Ask of every new test: could it pass while the thing it checks is false? Signals: no assertion; assertion on a constant or on the mock's own return; `assert result is not None` as the only check; the expected value computed by the same code under test. [Google looking-for "Tests" ("will the tests actually fail when the code is broken?"); swe-book ch. 12]
- **Tests exercise behaviour through public APIs and assert state, not interactions.** Heavy `verify(mock).called(...)` chains, asserting the order of internal calls, mocking the class under test. These are brittle and pass when the real code is broken. [swe-book ch. 12 "Test via public APIs", "Test state, not interactions"]
- **One behaviour per test, named for the behaviour.** A test name containing "and"; a test with several unrelated given/when/then blocks. [swe-book ch. 12 "Test behaviors, not methods"]
- **No logic in tests.** Loops, conditionals, string concatenation to build the expectation; tests must be correct by inspection. [swe-book ch. 12 "Don't put logic in tests"]
- **Negative cases and edge cases are present.** Tests for rejection, invalid input, authorization denial, empty/boundary values, and the specific bug a fix addresses (a regression test that failed before the fix). [OpenSSF developing guide §6; swe-book ch. 9 "Bug fixes"; Microsoft reviewer-guidance "edge cases"]
- **Authorization logic has its own tests, including default-deny.** [OWASP Authorization Cheat Sheet "Unit and integration tests"]
- **Tests are hermetic and deterministic.** Signals of flakiness: `sleep`, real clocks, real network, shared global/static state, order dependence, a shared `Random`, hard-coded ports, a timeout as the assertion. Flakiness near 1% destroys a suite's value. [swe-book ch. 11 "Test sizes", "Flaky tests"; Druid "Testing"]
- **An `eventually`/retry asserts the thing that changes.** An assertion outside the retry reads a stale value; the retry must wrap the value under test. [swe-book ch. 11 (hermetic, deterministic tests); Druid "Testing" ("assertions in parallel threads and asynchronous code are handled properly")]
- **Related flaky tests are fixed or explained, not retried into silence or deleted.** A `@retry`, `@flaky` or skip annotation added in the diff. [GitLab acceptance checklist "flaky tests"; swe-book ch. 11 (reruns delay the root cause)]
- **Tests are readable: complete and concise, DAMP over DRY.** Helpers that hide the inputs that matter; a fixture so shared that the test cannot be understood alone. [swe-book ch. 12 "Make tests complete and concise"]
- **Failure messages say expected, actual and the parameters.** A bare `assert ok`. [swe-book ch. 12 "Write clear failure messages"]
- **Coverage is a floor, not evidence.** A diff that adds "coverage" tests with weak or no assertions to hit a number. [swe-book ch. 11 "Code coverage caveats"]
- **The right test size.** A unit-testable change tested only end-to-end (slow, flaky); or an integration seam with no integration test. Aim roughly 80/15/5. [swe-book ch. 11 "Test scope"]
- **Test-only code does not ship.** Test doubles, fixtures or `if (testing)` branches in production modules; dev-only dependencies in the production artifact. [OpenSSF evaluating guide "Dependency impact"; swe-book ch. 12]

### Observability and operability

- **A production failure in this code path could be diagnosed from what it logs.** New error branches with no log, or a log with no identifiers (which entity, which request). Logs record when, where, who, what. [OWASP Logging Cheat Sheet "Event attributes"; Greiler "enough log events"; GitLab acceptance checklist "Observability"]
- **Log levels are right and volume is bounded.** `error` for expected conditions; a log line inside a hot loop; unbounded user input in a message. Logging must not be able to exhaust disk or budget. [OWASP Code Review Guide, Reviewing Logging Code; OWASP Logging "limit the size"]
- **Logs are structured and sanitised.** String-concatenated messages; CR/LF from input; secrets or PII (see Security). [OWASP Logging Cheat Sheet]
- **New request paths and background work emit the golden signals.** Latency (success and failure separately), traffic, errors, saturation of the constrained resource. A new endpoint, consumer or job with no metric. [SRE book "Four golden signals"]
- **Trace and correlation context propagates across async boundaries and outbound calls.** Work handed to another thread or queue loses the trace id; an outbound HTTP call without context headers. [OWASP Logging "interaction identifier"; SRE book (white-box monitoring for debugging)]
- **Metric cardinality is bounded.** A label populated from user input, ids or free text. [GitLab acceptance checklist "Observability" (instrumentation that is usable); SRE book]
- **New alerts page only on actionable, user-visible symptoms.** An alert on a cause (e.g. a queue depth) with no symptom; an alert that needs a rote response and could be automated. [SRE book "Alerting", "Symptoms vs causes"]
- **Health, readiness and shutdown behave with the new component.** A new dependency not reflected in readiness; a new background loop not stopped on shutdown. [Druid "ExecutorService is shutdown explicitly"; SRE book (saturation)]
- **Configuration is explicit, validated and documented, with a safe default.** A new environment variable or setting read in code with no default, no validation, no documentation entry, or a default that changes behaviour for existing deployments. [AIP-180 "Changing default values"; Google looking-for "Documentation"; GitLab acceptance checklist "Infrastructure informed of settings changes"]
- **Feature flags for risky changes, with a rollout plan.** High-risk behaviour not behind a flag; a flag added with no removal plan. [GitLab acceptance checklist "Deployment"]
- **Operational docs change with operational behaviour.** Runbooks, dashboards, alert docs when a failure mode, metric or process changes. [Google looking-for "Documentation"]

### API and compatibility

- **Classify the change: compatible or breaking, and say so in the description.** The PR template asks "Does this introduce a breaking change?"; Conventional Commits mark it `!` / `BREAKING CHANGE:`; SemVer requires a major bump. [Microsoft pull-request-template; Conventional Commits; semver]
- **Nothing public is removed or renamed within a major version.** A rename is a remove plus an add; keep the old name and deprecate. Fields, methods, endpoints, enum values, config keys, CLI flags, wire names, event types. [AIP-180 "Removing components", "Renaming components"; OpenSSF developing guide §12 ("keep old names")]
- **No new required inputs on existing operations.** A new required field, header, parameter or config value that existing callers do not send. [AIP-180 "Adding new required fields"]
- **New fields have defaults that reproduce the previous behaviour.** Adding pagination with a default page size smaller than the old unbounded response is breaking. [AIP-180 "Changing defaults for new fields"]
- **Types, formats and value construction do not change on existing fields.** Wire-compatible type changes still break generated code; an `ip_address` field that starts carrying IPv6; a stricter or looser id format. [AIP-180 "Changing field types", "Changing value format"]
- **Defaults and their serialisation do not change.** A field at its default that was omitted and is now emitted (or vice versa); a changed default value. [AIP-180 "Changing default values", "Changing how defaults serialize"]
- **Server-populated fields keep being populated.** [AIP-180 "Stopping server-populated fields"]
- **Enum values added to response enums are documented as a risk; adding to request-only enums is fine.** Older clients may not handle a new value. [AIP-180 "Adding enum values"]
- **Observable behaviour (ordering, timing, error shapes, limits) is treated as part of the contract.** Changing sort order, error payload shape, rate limits, size limits. With enough users every observable behaviour is depended upon. [Hyrum's Law; AIP-180 "Changing visible behavior"]
- **Deprecation precedes removal by at least one minor release and is documented and logged.** A removal with no prior deprecation warning. [semver FAQ; OpenSSF developing guide §12]
- **Generated code and schemas are regenerated and committed together with their source.** A `.proto`/OpenAPI/schema change with stale generated files, or vice versa. [AIP-180 (generated-code breakage); Google small-cls "Splitting by files" (proto first)]
- **Rolling-deploy compatibility holds for in-flight requests and persisted references.** Two versions run at once: a renamed handler wire name breaks in-flight calls and persisted timers; a new message version must be readable by the old consumer or gated. [GitLab acceptance checklist "backwards compatible across updates"; strong_migrations (dual-write pattern)]
- **Pre-1.0 is not a licence to break silently.** 0.y.z may change, but the change is still declared in the description and changelog. [semver "0.y.z"]

### Dependencies and build

- **A new dependency is justified in the description: what it does, why the standard library or an existing dependency cannot.** Each dependency is attack surface and maintenance. [OpenSSF developing guide §4; OpenSSF evaluating guide "Necessity"; GitLab code_review "New libraries" as diff comments]
- **The name and source are verified against typosquatting.** A package one character from a popular one; a personal fork where the canonical project exists; a new registry or mirror URL. [OpenSSF developing guide §4; OpenSSF evaluating guide "Authenticity", "Name verification"]
- **The project is alive and not a single point of failure.** No commits or releases in 12 months; one maintainer; version `0.x`/alpha/beta on a critical path. [OpenSSF evaluating guide "Maintenance & sustainability"]
- **Licence is present, OSI, and compatible with the project's licence and distribution.** A missing licence; GPL/AGPL/SSPL-family in a permissive or proprietary artifact; a licence the project's allow-list (dependency review config) rejects. For this family, anything reintroducing a BSL licence is a design change, not a version bump. [OpenSSF evaluating guide "License clarity"; GitHub about-dependency-review; ankka-brain principles #1]
- **Versions are pinned and the lockfile is updated and reviewed, including transitive changes.** A floating range or `latest`; a lockfile diff with hundreds of lines nobody read; new transitive dependencies with vulnerabilities or odd licences. Scan the lockfile, not just the manifest. [OpenSSF developing guide §5, §7; GitHub about-dependency-review]
- **Known vulnerabilities are checked for the exact version; updates to very fresh releases wait a cooldown of about three days.** A dependency bumped to a version released yesterday. [OpenSSF developing guide §7, §8]
- **Install scripts, build plugins and postinstall hooks are read.** Encoded or obfuscated strings, network calls, reads of environment or SSH keys at install time. [OpenSSF evaluating guide "Malicious code check"]
- **Dev- and test-only dependencies do not ship to production.** A test library in the runtime dependency set; a build tool in the image. [OpenSSF evaluating guide "Dependency impact"]
- **Dependency updates are separate from feature changes.** A lockfile churn mixed into a behaviour PR hides both. [Google small-cls; Microsoft pull-requests (one goal)]
- **Build and CI changes are reviewed for determinism and reproducibility.** A new unpinned base image or action (`@main`, `:latest`); a build that downloads at build time without a checksum; a release step that can publish without a tag. [OpenSSF developing guide §17 (SLSA), §27; GitHub best-practices "Review for security"; ankka-brain principles #7]
- **The build stays warning-free and the formatter/linter passes before human review.** New warnings suppressed rather than fixed. [swe-book ch. 9 "Automate where possible"; Microsoft pull-requests "compiles and runs without errors or warnings"]
- **Vendored or copied code carries its origin, licence and version.** A pasted file with no header saying where it came from. [OpenSSF developing guide §11 (vendored dependencies disclosure); OpenSSF evaluating guide "License clarity"]

### Documentation, naming and readability

- **Names say what the thing is or does, fully, and no longer than that.** Abbreviations only the author knows; `data`, `info`, `tmp`, `flag`; a boolean whose name does not read as a predicate; a name that reveals customer-specific information. [Google looking-for "Naming"; Microsoft reviewer-guidance "Does a variable name reveal any customer specific information?"]
- **Comments explain why, not what; code that needs a what-comment is simplified instead.** A comment restating the line below; a comment excusing complexity; a commented-out block; a TODO with no owner or issue. [Google looking-for "Comments"; Microsoft reviewer-guidance "Style"; Greiler "commented-out code"]
- **Existing comments and TODOs touched by the diff are still true.** A TODO the change resolves but leaves; a doc comment describing the old behaviour. [Google looking-for "Comments" ("maybe there's a TODO for removing this code now")]
- **Public functions, classes and modules have documentation stating purpose, usage and behaviour.** A new exported symbol with no doc comment where the codebase documents exports. [Google looking-for "Comments" (documentation of classes/functions)]
- **User-facing docs change when build, test, usage, configuration, deploy or release behaviour changes; deleted features lose their docs.** A new flag, env var, endpoint or error without a docs entry; a limitations page not updated for a new not-implemented edge. [Google looking-for "Documentation"; Microsoft reviewer-guidance "First design pass"; ankka-brain principles #8]
- **Control flow and data flow read top to bottom.** Deep nesting, early returns mixed with late ones, a value mutated in three places. [Greiler "intuitive control flow", "data flow understandable"; Google looking-for "Complexity"]
- **Formatting is the formatter's; the reviewer does not comment on it.** If the formatter did not run, say so once. Style points the guide does not cover are `Nit:` at most. [Google looking-for "Style"; thoughtbot "Style comments"; GitLab code_review "enforce code style through automation"]
- **Consistency with the surrounding file wins over personal preference.** Two naming schemes or two error-handling idioms in one file after the change. [Google looking-for "Consistency"]
- **If the reviewer could not understand a part, the code changes, not the review thread.** [Google comments "Accepting explanations"; GitLab code_review "if you don't understand, say so"]
- **The honest not-implemented list is kept honest.** New behaviour the docs promise but the code does not do, or a limitation lifted but still listed. [ankka-brain principles #8; Google looking-for "Documentation"]

### Commit and PR hygiene

- **Title: an imperative sentence, specific enough to understand from `git log` alone, about 50 characters, no trailing period, capitalised.** "Fix bug", "Phase 1", "Add patch" fail. [Google cl-descriptions "First line"; cbea.ms rules 2–5; kernel submitting-patches "imperative mood"]
- **Body: the problem, why this approach, its shortcomings, and context (bug ids, benchmarks, design links) so it stands alone when links die.** The diff shows how; only the message can say why. [Google cl-descriptions "Body"; cbea.ms rule 7; kernel "Describe your changes"]
- **Blank line between subject and body; body wrapped at 72.** Tools misread messages without the blank line. [cbea.ms rules 1, 6]
- **Where the repository has a pull request template, the description follows its sections and its length rule, with detail linked rather than pasted.** A section left as the template's placeholder, a missing section, or a body several screens long when the template asks for a page. Templates exist so the reader finds the why, the how, what is left out and how it is tested in the same place every time; a description that does not follow it costs every reviewer the search. [GitHub docs "Creating a pull request template for your repository"; Google cl-descriptions "Keep the description short and focused"; GitHub best-practices "Write a clear description"]
- **Where the repository declares Conventional Commits, the PR title parses too, because a squash merge makes it the commit on the default branch.** `type(scope)!: description`; a title in prose on a repository whose history is conventional breaks changelog and version tooling and the reader's scan of `git log`. [Conventional Commits 1.0.0 "Why Use Conventional Commits", FAQ "What do I do if the commit conforms to more than one type"]
- **One logical change per commit and per PR; each commit builds and passes on its own.** Bisect and revert depend on it; a PR fixing two issues or mixing refactor with behaviour is split. [kernel "Separate your changes"; Google small-cls "Don't break the build"; GitLab code_review author responsibilities]
- **Where the project uses Conventional Commits, the type is right and breaking changes carry `!` or `BREAKING CHANGE:`.** `fix` for a feature, `feat` for a refactor; a breaking change marked `fix`. [Conventional Commits 1.0.0; Microsoft pull-requests]
- **The PR links its issue/spec and says what it closes.** `closes #123`; a spec or feature id where the project uses them. [GitHub how-engineering-communicates; GitHub best-practices "Link related work"; Microsoft author-guidance "Link work items"]
- **The author reviewed their own diff first: no debug code, stray files, accidental formatting churn, unrelated changes.** `console.log`, `println`, commented tests, `.DS_Store`, a formatter run over untouched files. [GitHub best-practices "Review your own pull request first"; GitLab code_review "read the full diff before the first push"; MSR-TR-2016-27 §3.1]
- **For a large or unusual change, the description gives reviewers a reading order and says where to look hardest.** [GitHub best-practices "Provide context and guidance"; Microsoft author-guidance "Annotate large PRs"; GitLab (diff comments on new libs, insecure code, benchmarks)]
- **Fix-ups during review are pushed as separate commits; squash only when ready to merge (or per project convention).** Reviewers see what changed since their last pass. [thoughtbot "Having your code reviewed"; GitLab code_review]
- **Every reviewer comment gets a response; threads are resolved only when addressed; "won't fix" carries a reason.** Silent resolution and "later" are not responses. [GitLab code_review "Everyone"; Microsoft author-guidance; kernel "Respond to reviewers"]
- **Attribution is correct.** `Co-Authored-By` trailers for pairs or tools; the description still true after the review changed the code. [thoughtbot; Google cl-descriptions "Review the description before submitting"]
- **Generated or tool-made changes still get the same description discipline and a human review limited to the parts the tool could get wrong.** [Google cl-descriptions "Generated CL descriptions"; swe-book ch. 9 "Large-scale changes"]
- **Merge only when CI is green on a head up to date with the base; a failing or skipped job is explained in a comment, never ignored.** [thoughtbot; GitLab code_review "Merging a merge request"]

## 4. Severity model

A five-level scheme that composes Conventional Comments labels/decorations with Google's labels, chosen so that a comment's first word tells the author what to do with it.

| Level | Write it as | Criterion | Effect on approval |
|---|---|---|---|
| **blocking** | `issue (blocking): …` plus a `suggestion` of the fix where possible | A defect, a security or data-loss risk, a breaking change undeclared, a test that cannot fail, a change that worsens code health, or scope the change should not contain. Must be resolved (or shown not to be a problem) before merge. | Request changes. |
| **should-fix** | `issue (non-blocking): …` or `suggestion: …` or `todo: …` | A real improvement the standard asks for but whose absence does not make the change worse than the status quo: a missing negative test on a secondary path, a name that misleads, a doc entry. The author is expected to address it in this change or say why not. | Approve with comments, stating the expectation ("LGTM once the todos are in"). |
| **nit** | `nitpick (non-blocking): …` or Google's `Nit:` | Minor, preference-based, or polish; technically should be done, "won't hugely impact things". Never blocks. One comment per repeated instance. | None. |
| **question** | `question: …` / `thought: …` | The reviewer is unsure a problem exists, or wants to understand; may become an issue once answered. Prefer to an assertion when uncertain. | None until answered; the answer may upgrade it. |
| **praise** / **note** | `praise: …` / `note: …` / Google's `FYI:` | Specific, sincere praise; or context the author should know for later, not for this change. Always non-blocking. | None. |

Rules of the scheme:
- Every comment carries a label; an unlabelled comment is read as mandatory and robs the author of prioritisation. [Google comments "Label comment severity"; Conventional Comments]
- `issue` is paired with a `suggestion` or a concrete next step; a comment with no resolution path is rewritten. [Conventional Comments "issue", communication "Leave actionable comments"]
- `(if-minor)` marks a should-fix the author may skip if the fix turns out to be large. [Conventional Comments decorations]
- Out-of-scope findings are `note`s with a filed issue, never blocking. [Microsoft reviewer-guidance]
- Style the formatter owns is not commented at all; style the guide covers is a `todo`; style the guide does not cover is at most a `nitpick`. [Google looking-for "Style"; thoughtbot]
- "LGTM with comments" is the normal outcome when only should-fix and below remain; the reviewer states which of Google's three justifications applies (author reliable, comments optional, or trivial). [Google speed]
- "Request changes" is used only for blocking findings, since it means "I intend to block until I approve". [GitHub how-engineering-communicates]
- Blocking findings are sent immediately, before the rest of the review, when they are design-level. [Google navigate]
- Emergencies relax the bar to "does it resolve the emergency, safely" and schedule a full review afterwards. [Google emergencies]

## 5. Anti-patterns in reviewing

- **Rubber stamping.** LGTM without reading every line; approving because CI is green. A reviewer's approval shares responsibility for the change. [Google looking-for "Every line"; GitLab code_review "Maintainer"]
- **Bikeshedding style the formatter owns, or personal preference not in the style guide.** Authors name "a lot of style comments" as what makes feedback uninsightful; the guide is the authority and the rest is `Nit:` at most. [MSR-TR-2016-27 §2.1; Google standard; swe-book ch. 9]
- **Reviewing the author, not the code.** "You wrote this wrong"; adjectives about the person; assumptions about their diligence. Use "this line" or "we". [Google comments "Courtesy"; thoughtbot; Conventional Comments communication]
- **Asking for unrelated work.** "While you're at it…" scope creep; blocking a PR on pre-existing debt. File it instead. [Microsoft reviewer-guidance; thoughtbot]
- **Demanding perfection.** Blocking for days over imperfections in a change that already improves the codebase. [Google standard "No perfect code"]
- **Accepting "clean it up later".** The cleanup rarely happens; new complexity is fixed now or tracked by a bug with an owner. [Google pushback]
- **Unlabelled comments.** The author treats everything as mandatory or, worse, nothing. [Google comments; Conventional Comments]
- **Commenting line by line before judging the design.** Line comments on code a redesign will delete waste both sides' time; design first. [Google navigate]
- **Slow first response.** Beyond one business day, authors stack more work on the unreviewed branch and start pinging; speed of individual responses matters more than total time. [Google speed; MSR-TR-2016-27 §2.1]
- **Interrupting deep work to review instantly.** Context switches cost more than a short wait; review at natural break points. [Google speed "Speed vs interruption"]
- **Marathon reviews.** Reviewing more than 400 lines or more than an hour at a sitting; defect yield drops. [SmartBear/Cisco]
- **Explaining in the thread what should be in the code.** Review-tool explanations help nobody later. [Google comments "Accepting explanations"]
- **Reviewing only the diff.** Missing the caller two screens up, or the second copy of the function. [Google looking-for "Context"]
- **Treating coverage or a passing test as proof.** Tests that cannot fail, or tests of mock wiring, pass while the behaviour is broken. [swe-book ch. 11, 12; Google looking-for "Tests"]
- **Counting defects per person as a performance metric.** It poisons the culture and games the metric; measure the process, not the people. [SmartBear/Cisco item 8; MSR-TR-2016-27 §3.3]
- **Piling on reviewers.** More than one or two adds little and diffuses responsibility. [swe-book ch. 9 "Keep reviewers to a minimum"; MSR-TR-2016-27 §3.1 ("two reviewers was their ideal")]
- **Repeating the same nit on every instance.** One comment naming the pattern, or "run the linter". [thoughtbot "Style comments"; Conventional Comments communication]
- **Letting a disagreement stall the change.** Escalate or talk, then record the outcome in the PR. [Google standard "Resolving conflicts"; thoughtbot]
- **Hiding "I didn't understand this".** Parroting the author's explanation adds nothing; say what is unclear and ask for the code to be made clear. [MSR-TR-2016-27 §2.2 (Participant 13); GitLab code_review]
- **Hyperbole, sarcasm and diminishers.** "Always", "never", "just", "simply", "obviously". [thoughtbot "Everyone"]
- **Relying on review as the quality gate.** Bacchelli & Bird found defects were only 14% of review comments and "deep, subtle, or macro level issues" rarer still; review complements tests and analysis, it does not replace them. [Bacchelli & Bird 2013 §VII]

## 6. Open questions

Where the sources disagree, or leave a choice to the team:

1. **How small is small?** Google's reviewer guide: "100 lines is usually a reasonable size, and 1000 lines is usually too large"; Google's SWE book and GitLab both target about 200 lines; SmartBear/Cisco put the reviewer's ceiling at 200–400 lines per session; Microsoft's playbook declines to set a number ("there is no right size"). A reviewer tool needs one threshold for "ask to split" and one for "warn"; 400 and 1000 respectively are the only numbers all sources would accept.
2. **Blocking by default, or non-blocking by default?** Conventional Comments offers both `(blocking)` and `(non-blocking)` because teams differ; GitLab treats comments as blocking unless marked non-blocking; GitHub's engineering guidance uses "Request changes" only when the reviewer intends to block, implying comments are non-blocking by default. The scheme in §4 makes `issue` blocking only when decorated, which follows GitHub rather than GitLab.
3. **Must tests always be in the same change?** Google and Microsoft: yes, except emergencies ("'I'll add tests next' is not acceptable"). Google also allows refactor-only and test-only CLs first. GitLab allows "testing gaps documented in an issue". A tool should block on missing tests for behaviour changes and allow a documented exception only with a linked issue.
4. **Should reviewers run the code?** GitLab: check out and test locally. Microsoft: not necessary with a stakeholder review unless it aids understanding. Google: reading, plus a demo for UI. An automated reviewer can only read; it should say when a finding needs a run to confirm.
5. **Who fixes it?** Google: "it is the developer's responsibility to fix a CL, not the reviewer's", balanced with giving guidance; thoughtbot and GitLab lean toward suggested-change patches; Conventional Comments prefers "preferably with a patch". A reviewer tool should offer the patch for mechanical fixes and leave design fixes to the author.
6. **Refactors in feature changes.** Google allows "small cleanups such as fixing a local variable name" inside a feature CL and leaves the threshold to developer and reviewer; the kernel wants every logical change separate. No source gives a line count for "small cleanup".
7. **Style enforcement in review.** thoughtbot says reviewers should comment on missed style guidelines and authors should fix them; GitLab says enforce style through automation, not comments; Google says the guide is authoritative but suggestions outside it are nits. Agreement: never comment on what the formatter owns; disagreement: whether to comment on what the guide covers and the linter does not.
8. **Number of reviewers.** Google's SWE book: one is usually enough; Microsoft's trenches study: two leads said two was ideal; GitLab: reviewer plus maintainer, plus domain experts per area touched. The family's convention (one approver, Trevor) is at Google's end.
9. **Deprecation period.** SemVer: at least one minor release with the deprecation before a major removes it; AIP-180 forbids removal within a major at all; neither says how long in time. GitLab's example of a safe-to-skip-review change is "removing a feature flag default-enabled for over one month", a calendar heuristic no API source gives.
10. **Reversibility of migrations.** GitLab: every migration has a `down`, but production rolls forward, never back, so the `down` is for development and a documented no-op is acceptable; strong_migrations frames the same operations as expand/contract with no `down` mentioned. Whether a reviewer should block on a missing `down` depends on whether the project ever rolls back.
11. **Query-count discipline.** GitLab's "must not increase the number of SQL queries unless absolutely necessary" is enforceable with a counting test; no other source asks for it, and it is a GitLab-scale rule. A tool should flag query growth as should-fix, not blocking, unless the project has adopted the rule.
12. **Cooldown on dependency updates.** OpenSSF's ~3-day cooldown before adopting a fresh release conflicts with "update vulnerable dependencies quickly" when the fresh release is the security fix; OpenSSF resolves it by exempting components with known vulnerabilities. A tool needs the vulnerability data to apply the exemption.
13. **What review is for.** Managers and developers expect defect finding first; Bacchelli & Bird measured code-improvement comments at twice the rate of defect comments (29% vs 14%), and MSR's trenches study found authors annoyed by exactly those "insignificant" comments. The checklist above weights correctness, security and tests first on purpose, and demotes readability findings to should-fix or nit.
