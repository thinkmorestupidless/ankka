# Review: Every client reads a workflow's standing and how a copy stands (spec 065, main...HEAD)

**Verdict:** Request changes
**What it does:** Protocol 1.24 adds `Lifecycle` and `Replication` rpcs on `Client` and two module imports of the same names. The sidecar answers them with the runtime's reserved queries. The Python, TypeScript, Go and Rust clients each gain `lifecycle` and `replication`, with a too-old error or a load refusal, component test kit stubs, conformance cases compared with the Scala client, and the four missing tabs on the three docs sections.
**Should it exist:** yes. It removes a limitation that sent four languages to a workaround the platform already makes unnecessary. It reuses the reserved queries, so it adds no second way to answer the reads, and its layout mirrors 1.18's `AwaitEnd`.
**Read:** `protocol/.../client.proto`, `ClientLogic.scala`, `ClientService.scala`, `HostImports.scala`, `ModuleLoader.scala`, the conformance and `WasmHostSuite` hunks, `sdks/python/src/ankka/{client.py,testkit/unit.py}`, `sdks/typescript/src/{client.ts,testkit/unit.ts}`, `sdks/go/{reads.go,testkit/kinds.go,testkit/components.go,testkit/reads_test.go}`, `sdks/rust/ankka/src/{reads.rs,client.rs,abi/imports.rs,testkit/unit.rs,testkit/kinds.rs}`, every docs sample hunk, the docs diff in full, the three rule-file additions, and spec, plan, tasks and data-model. Skimmed: the generated `client.pb.go` and `client_grpc.pb.go`, the proto copies (CI diffs them), `Cargo.lock`, and the skill copies under `marketplace/` and `ankka.g8/` (rendered by `docs sync`). Opened outside the diff: `docs/reference/{python,typescript,rust,go,java}-sdk.md` (client and kit tables), `docs/build/testing.md`, Python's `_NoClient` and `_await_unimplemented`, and Rust's `WorkflowTestKit::run_step`. Not read line by line: `ClientReadsSuite.scala` beyond its fixture, `ReplicationSteps.scala`, and the four SDKs' integration tests.
**Size:** 144 hand-written files, +5761 -123 (171 files, +7555 -367 in all). That is past the point where a review stays reliable. It could have been split into protocol plus sidecar, then one change per SDK. The commits already follow that seam, one per layer, so I accept it as one change, but the per-SDK test files got less scrutiny than the core.

## Blocking

- `docs/reference/python-sdk.md`, `docs/reference/typescript-sdk.md`, `docs/reference/rust-sdk.md`, `docs/reference/go-sdk.md`, `docs/build/testing.md`: issue (blocking): the SDK reference pages do not document the new calls or kit stubs. Each reference page's "calling components" table still lists `await_end`/`awaitEnd`/`AwaitEnd` and nothing for `lifecycle` or `replication`: Python :264, TypeScript :302, Rust :429, Go :874. Java's page names `lifecycle(Workflow.class)` at :1153. None of the kit tables names the new test surface: Python `ClientStub`, TypeScript `stubClient`, Go `testkit.AnswerLifecycle`/`AnswerReplication` (the Go options row at :1179 lists only `Calling` and `AnswerService`), Rust `EndpointTestKit::with_lifecycle`/`with_replication`/`workflow`. `docs/build/testing.md` does not show stubbing either read in any language. REVIEW.md says "A feature is not done until its documentation is", and a PR that "leaves a sibling SDK's sample, its page … behind is Request changes". Someone reading `python-sdk.md` is told today that the call does not exist, and US3 (testing the reads without a cluster) has no page in any of the four languages. Suggestion: add the two calls, with "since protocol 1.24" and the too-old behaviour, to each of the four reference tables, and add the kit options to each kit table. Add a short "a component that reads a standing" section to `testing.md` with one tab per language, included from the kit tests that already exist (`sdks/python/tests/test_testkit_reads.py`, `sdks/typescript/test/testkit-reads.test.ts`, `sdks/go/testkit/reads_test.go`, `sdks/rust/ankka/tests/unit_testkit.rs`), plus the Scala/Java/Kotlin equivalent or an exclusion that names a stated limitation.
  Resolved: the Python, TypeScript, Rust and Go reference pages name both calls with the 1.24 gate and their kit stubs; testing.md gains "Testing a component that reads a standing" with four tabs from the samples' tests, and a stated limitation for the Scala, Java and Kotlin kits, which have no stub for either read, e772c9085
- `sdks/go/testkit/kinds.go:40`: issue (blocking): Go's endpoint kit cannot answer either read, so US3's independent test is not met in Go. `testkit.Endpoint(e, components...)` calls `serveStandIn(v, options{})`, and `Endpoint` takes no `Option`, so `AnswerLifecycle` and `AnswerReplication` reach the workflow, entity, view and consumer kits but not an endpoint. The spec's US3 independent test is "in each language, a unit test of an endpoint that reads a standing passes with the kit answering a failed standing", and FR-009 requires each SDK's component test kits to let a test say what each read answers. Python (`ClientStub` handed to `EndpointTestKit`), TypeScript (`stubClient`) and Rust (`with_lifecycle`) all do this. In Go, the docs sample route `/quotes/{id}/lifecycle` (`samples/awaiting.go`) can be tested only in the slow integration suite. Suggestion: give `Endpoint` the same answers. Either add an `EndpointWith(e, opts ...Option, components...)` or move the components into a `testkit.Calling` option, so `Endpoint(e, testkit.Calling(...), testkit.AnswerLifecycle(...))` reads like the other kits. Add a unit test of the sample route that answers a failed standing.
  Resolved: `testkit.EndpointWith(e, opts...)` takes `Calling` and the `Answer*` options (`Endpoint` delegates to it); a kit test and a unit test of the sample route answering a failed standing, 61db18e6b

## Should fix

- `sdks/typescript/examples/shopping-cart/planner.test.ts:112-125`: issue (non-blocking): the TypeScript tab of "Observing a run" asserts a constant it set itself. It stubs `{ status: "completed" }` and then asserts `lifecycle.status === "completed"`, so the read half of the sample cannot fail and only exercises the stub. The page teaches readers to read the engine's state, and this tab teaches them to stub it, unlike the Scala, Kotlin, Python and Go tabs, which read a real run. Rust's tab (`sdks/rust/examples/shopping-cart/tests/planner.rs`, the `Ended` host) also answers `Completed` by hand, even though the new Rust kit can answer from the workflow it runs (`EndpointTestKit::workflow`). Suggestion: for TypeScript, read through `AnkkaTestKit`'s `service.client` after the plan ends, as the Python tab does in `test_planner.py`. For Rust, drive the planner through a shared kit and let the kit's recorded standing answer.
  Resolved for TypeScript: the tab reads the plan and its standing through `AnkkaTestKit` after a scripted run, 447d1ead4. Declined for Rust: the Rust unit kit answers queries of event sourced entities only ("the unit testkit answers event sourced entities, not 'planner'"), and the plain module does not host the planner, so a real read needs new kit work beyond this spec; the sample's kit-run read is shown by the testing page's Rust tab, where `workflow::<W>` answers the standing from what the kit runs.
- `docs/build/workflows.md:1631` (the paragraph after the tabs): suggestion (if-minor): the prose now covers seven languages but uses Scala's names. It names `WorkflowLifecycle`, `status` with `Running`/`Paused`/`Completed`/`Failed`, and `isTerminal`, and leaves out "not started", which every new SDK answers. Python's type is `Lifecycle` with `is_terminal`, TypeScript's `status` is `"failed"`/`"not-started"`, Go's is `ankka.NotStarted` and so on. Suggestion: describe the fields in words (status, pending step, retries per step, failure, and a terminal test) and say each language names them in its own idiom, or add the per-language names in one line.
  Resolved: the paragraph describes the fields in words, includes not started, and names each language's types, e772c9085

## Nits

- `sdks/rust/examples/shopping-cart/src/transfer.rs` (`#lifecycle`): nitpick (non-blocking): the route renders the status with `format!("{:?}", lifecycle.status)`, using `Debug` output as user-facing text in a docs sample. A `Display` impl on `WorkflowStatus`, or a match to words, would show readers the intended way.
  Resolved: `Display` for `WorkflowStatus` with a test; the sample uses it, d36d239b0
- `specs/065-client-lifecycle-replication/data-model.md:33-36`: nitpick (non-blocking): "Rust in-memory standing" says the kit keeps "the retry count per step". `WorkflowTestKit::run_step` applies no recovery ("the kit applies no recovery"), and `record` always writes empty retries. Correct the data model, or the next reader will expect retries from the Rust kit.
  Resolved: the data model says the kit applies no recovery and its retries are empty, e772c9085

## Questions

- `sdks/python/src/ankka/client.py` (`Status`), `sdks/go/reads.go` (`WorkflowStatus`), `sdks/typescript/src/client.ts` (`WorkflowStatus`): thought: the status words differ by SDK. Python and Go use `"NotStarted"`/`"Failed"` (Scala's words), TypeScript uses `"not-started"`/`"failed"`, and Rust uses an enum. The spec allows each language's idiom, and the conformance routes normalise to kebab case, so this is not a defect. It does mean an endpoint that returns the raw word, as each docs sample does, answers differently per language. Was that intended?
  Answered: intended. `contracts/client-reads.md` fixes each language's form: TypeScript's `status` is `"not-started" | "running" | … | "failed"`, Go's and Python's follow the wire's `NotStarted` … `Failed`, Rust's is an enum; workflows.md now names each language's forms, and the conformance routes normalise them for the comparison.

## Praise

- `sdks/rust/ankka/src/abi/imports.rs` and `sidecar/.../WasmHostSuite.scala:564-593`: praise: each read has its own link block, so a module that reads neither keeps loading on an older runtime. The suite proves both directions. The plain example imports neither and the reference imports both, and `ModuleLoader.check` gained a `provided` parameter so the "older runtime refuses, naming the import" case runs without an older runtime. The wasm trap about putting the docs sample on an unregistered endpoint is recorded the day it cost time.
- `sidecar/.../conformance/ConformanceSuite.scala` (`wf.lifecycle-running`): praise: the case holds the step on a latch, reads the standing mid-step, compares it with the Scala client's read of the same id, and then checks that no `status` span was added and the journal is unchanged. That proves FR-005 by behaviour rather than by assumption. `es.replication-status` also pins `position` to the journal's size, which found and wrote down the platform-records trap.
- `sidecar/.../ClientLogic.scala:436-468`: praise: one private `read` serves both rpcs. A refusal travels in the reply, and a status the protocol has no value for is `INTERNAL` naming it, never a silent default.

## Out of scope

- note: `docs/build/component-client.md:164` has columns for Scala, Python and TypeScript only. Java, Kotlin, Rust and Go had no column before this change. Suggested issue: give the "what a client addresses" table every language, or split it per SDK.

## Checks applied

General groups applied: Correctness, Design and scope, Error handling, Concurrency (the sidecar's futures and the parasitic maps; nothing new shared), Security (no new entry point beyond `Client` rpcs on the process's own channel, which carry the same authorisation as the other reads), API and compatibility (an additive minor, one-direction refusals, both reads gated), Tests, Documentation, Commit hygiene. Skipped: Data and migrations (no schema), Performance (one ask per read, as Scala's), Dependencies (none new; `Cargo.lock` adds one line for an existing crate), Observability (the reads are queries the runtime already traces).

Project rules read: `CLAUDE.md`, `REVIEW.md`, and `.claude/rules/{sidecar,wasm,testing,messaging,secrets}.md` (sections on protocol gating, imports and traps). The traps this work learned are recorded in `sidecar.md`, `wasm.md` and `testing.md`.

Language checklists: the language skills were not loaded as separate skills. The Scala, Python, TypeScript, Go and Rust hunks were read against this skill's summaries (totality of status mappings, error mapping, `Unimplemented` handling, test hermeticity). The Java and Kotlin changes are conformance routes only and were skimmed. Formatting (scalafmt, rustfmt, gofmt, ruff) is left to the tooling.

Tests run:
- `docs check`: 112 pages, no problems.
- `.github/features-check.sh`: 0 findings in 3306 scenarios and 65 specs.
- Python `tests/test_client_reads.py tests/test_testkit_reads.py`: 19 passed.
- Go `go test . ./testkit`: ok.
- Rust `cargo test -p ankka --test reads --test unit_testkit`: passed.
- TypeScript `node --conditions=ankka-source --test test/client-reads.test.ts test/testkit-reads.test.ts examples/shopping-cart/planner.test.ts`: 24 passed.

Not run: `sidecar/testOnly *ClientReadsSuite *WasmHostSuite *ConformanceSuite`, each SDK's slow or integration suites, and the replication Gherkin bindings. All of them need Docker and take minutes. The size of this change also makes CI's full run the right evidence for them.

## Second round

# Review: the fixes to the first round (spec 065, 74e5a5327..HEAD)

**Verdict:** Approve with comments. The one should-fix is expected before merge and is a two-cell docs edit.
**What it does:** Five commits answer the first round. Go's endpoint kit takes options through `testkit.EndpointWith`. Rust's `WorkflowStatus` gets `Display`. The TypeScript "Observing a run" tab reads a real run through `AnkkaTestKit`. The four SDK reference pages name both reads and their kit stubs. `testing.md` gains "Testing a component that reads a standing" with Python, TypeScript, Rust and Go tabs, and `limitations.md` states why Scala, Java and Kotlin are left out. The workflows prose and the data model are corrected.
**Should it exist:** yes. Each hunk answers a first-round finding, and nothing outside them was added.
**Read:** the whole hand-written diff: `sdks/go/testkit/{kinds.go,reads_test.go}`, `sdks/go/examples/shopping-cart/samples/awaiting_test.go`, `sdks/rust/ankka/{src,tests}/reads.rs`, `sdks/rust/examples/shopping-cart/{src,tests}/transfer.rs`, `sdks/typescript/examples/shopping-cart/{planner,cart}.test.ts`, `sdks/python/examples/shopping_cart/test_cart.py`, `docs/build/{testing,workflows,multi-agent-orchestration}.md`, `docs/reference/{python,typescript,rust,go}-sdk.md`, `docs/reference/limitations.md`, and `data-model.md`. Skimmed: the 56 rendered skill copies under `marketplace/` and `ankka.g8/` (`docs check` holds them to the pages). Opened outside the diff: Go `settings` in `testkit/unit.go`; TypeScript `READS_SINCE`/`readsTooOld` in `src/client.ts` and `plannerService`/`PlanState`/the agents' prompts in `planner.ts`; the sidecar's `Models.scriptedFrom` (ordered turns, then `when` rules); the `sdk-typescript` CI job, which runs `npm run test:slow` on node 24; Go `reads.go`'s `ReadsSince`; Python `Lifecycle.is_terminal`; Scala `TestTransport` and `ComponentClient.lifecycle`/`replication`, to test the new limitation; the Rust planner test's `Ended` host.
**Size:** 39 hand-written files, +299 -42 (76 files, +2667 -132 with the rendered copies). One pass covers it.

## Resolutions from the first round

- **Blocking, reference pages and testing.md: holds, with one gap.** All four reference tables now name `lifecycle` and `replication`, and all four kit tables name the stub. `testing.md` has the new section, its four tabs are included from tested code, and `docs check` is clean. The `<!-- languages: … -->` reason for leaving out Scala, Java and Kotlin is now stated in `limitations.md`, so REVIEW.md's rule holds. I checked that the limitation is true. Scala's `TestTransport` has `stubEnd`/`stubEndFailure` for a wait, but nothing public for `ankka:lifecycle` or `ReplicationStatus.Method`: `stubBytes` is `private[ankka]`, and the lifecycle handle is built inside `ComponentClient`. The gap: the first round asked for "since protocol 1.24 and the too-old behaviour" in each table, and the resolution line says it was done. It was done for Python, Rust and Go's `Lifecycle`, but not for TypeScript or for Go's `Replication` (below).
- **Blocking, Go endpoint kit: holds.** `Endpoint` delegates to `EndpointWith(e, Calling(components...))`, so existing callers behave as before. The only difference from the old `options{}` is `entityID: "test-entity"`, which the endpoint kit does not read. `TestAnEndpointKitAnswersAStubbedStandingAndAStubbedCopy` covers both reads and the refusal of a read nobody stubbed. The docs sample route `/quotes/{id}/lifecycle` now has a unit test answering a failed standing (`TestTheLifecycleRouteSaysWhyAQuoteFailed`), which is US3's independent test in Go.
- **Should fix, TypeScript observe tab: holds.** The tab now reads `plan` and `lifecycle()` from a real run, through `AnkkaTestKit.start(plannerService(), { env: { ANKKA_MODEL_SCRIPT } })` after `awaitEnd`, so its assertion can fail. The script fits the sidecar's semantics: one ordered turn for the selector, then `when` rules that match the budget and summary prompts as `planner.ts` writes them. CI runs the test (`test:slow` on node 24), and it is skipped without `ANKKA_SLOW`. I did not run it here because it needs Docker. **The Rust decline holds.** The Rust tab is the `observe` function itself, not a test, so it does not teach stubbing. Its test (`tests/planner.rs`, the `Ended` host) checks that the function composes the `plan` query with the `lifecycle` import, which is the part a unit test can see. The testing page's Rust tab shows the kit answering from a workflow it runs.
- **Suggestion, workflows.md prose: holds.** It describes the fields in words, includes not started, and names each language's types. I checked them against the code: TypeScript `interface Lifecycle`, Python's `is_terminal` property, Rust's `is_terminal()`, Go's `IsTerminal()`.
- **Nit, Rust `Debug` as user text: holds.** `Display` writes the platform's words, `a_status_is_shown_as_the_platform_names_it` pins all five, and the sample and its page use it.
- **Nit, data model retries: holds.**
- **Question, status words per SDK: answered.** The answer cites `contracts/client-reads.md`, and workflows.md now says it.

## Should fix

- `docs/reference/typescript-sdk.md:302-303` and `docs/reference/go-sdk.md:876`: issue (non-blocking): the reference does not say that the reads need protocol 1.24, or what an older sidecar answers. In TypeScript this is missing for both reads. In Go it is missing for `Replication`, though the `Lifecycle` row beside it says it. The code gates both reads: TypeScript's `readsTooOld` rejects with `CommandError` `UNAVAILABLE` naming `READS_SINCE`, and Go's `Replication` returns `*ankka.ProtocolTooOldError` as `Lifecycle` does. Someone reading the TypeScript page against a pinned older sidecar is not told why the call fails. Suggestion: end the TypeScript Workflow and replicated-copy cells with "Since protocol 1.24; an older sidecar rejects with `CommandError` `UNAVAILABLE` naming 1.24", and add "A sidecar before protocol 1.24 is a `*ankka.ProtocolTooOldError`" to Go's `Replication` row. Then `just docs-sync`.
  Resolved: TypeScript's replicated-copy cell says both reads need protocol 1.24 and an older sidecar rejects with `CommandError` `UNAVAILABLE` naming 1.24; Go's `Replication` row names `*ankka.ProtocolTooOldError`, 4a36f5084

## Questions

- `sdks/go/testkit/agent.go:418` (`testkit.Mcp`): question: the Go MCP kit still takes components only, so an MCP tool that reads a standing cannot be answered in a Go unit test. The endpoint kit now can. The spec's US3 names endpoints, so this is outside it. Is an MCP tool reading either value something the other SDKs' MCP kits support? If they do, an `McpWith` like `EndpointWith` would close the gap. If they do not, nothing is needed.
  Answered: yes, Python's and TypeScript's MCP kits take a client as every kit does, so `testkit.McpWith(e, opts...)` now takes the same options, with a test of a tool answering a stubbed standing, 332cf4407

## Praise

- `sdks/go/testkit/kinds.go:39-51`: praise: `Endpoint` became a one-line delegate to `EndpointWith`, so the endpoint kit now takes every option the other kits take (`AnswerService` as well), with no second way to build one and no change for existing callers.
- `docs/build/testing.md` (new section): praise: each tab is the sample's own existing test marked with `docs:start`, not new code written for the page. The left-out languages carry a reason that is checked and true.

## Checks applied

General groups applied: Correctness, Design and scope, Error handling (the refusal of a read nobody stubbed, the too-old gates as documented against the code), Tests (each new test asked whether it could pass while false: the Go kit test and the sample test assert texts only a stub can produce, and the TypeScript tab now reads a run), API and compatibility (`Endpoint` unchanged for callers; `EndpointWith` and `Display` are additive), Documentation (REVIEW.md "every sample in every language": the new section's languages and their limitation), Commit hygiene (five Conventional Commits headers, scope `065`, the trailer present). Skipped: Concurrency, Security, Data and migrations, Performance, Observability, Dependencies. The fix diff touches none of them.

Language checklists: the Go, Rust, TypeScript and Python hunks were read against this skill's summaries. They total under 150 lines, so the lighter procedure applied and the language skills were not loaded separately. Formatting is left to gofmt, rustfmt and the TypeScript and Python tooling.

Project rules read: `CLAUDE.md`, `REVIEW.md`, `.claude/rules/{docs,testing}.md`.

Tests run:
- `docs check`: 112 pages, no problems.
- `.github/features-check.sh`: 0 findings in 3306 scenarios and 65 specs.
- Go `go test ./testkit` and, in `examples/shopping-cart`, `go test ./samples`: ok. `go vet ./testkit`: clean.
- Rust `cargo test -p ankka --test reads` (9 passed) and `cargo test -p shopping-cart --test transfer` (6 passed).
- TypeScript `npm run typecheck`: clean. `planner.test.ts` and `cart.test.ts`: 29 passed, the sidecar describe skipped without `ANKKA_SLOW`.
- Python `pytest examples/shopping_cart/test_cart.py -k lifecycle`: 1 passed.

Not run: the TypeScript observe test against the real sidecar (`ANKKA_SLOW=1`, Docker). CI's `sdk-typescript` job runs it.
