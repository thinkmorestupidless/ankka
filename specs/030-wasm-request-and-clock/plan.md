# Implementation Plan: WebAssembly Request and Clock — Three Imports a Module Lacks

**Branch**: `030-wasm-request-and-clock` | **Date**: 2026-10-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/030-wasm-request-and-clock/spec.md`

## Summary

A module gains three imports. `request` calls another service through the runtime's service
client, as the module's service, and is refused by the runtime from any handler that must not
wait: a command, an event applied to state, a view. `now` answers the runtime's clock and `random`
fills a buffer, from anywhere. The Rust crate exposes all three on its context.

Technically: the host records which export it is running in a thread-local call site, and
`request` proceeds only from a list of permitted exports, so the rule fails closed (R1). A refused
call is an exception thrown by the import, and the instance replaced and the state kept are what
any trap already gets (R2). The call itself is 025's `ClientLogic.request` with 025's two
messages, so nothing about certificates, resolution, headers, bounds, spans or counts is written
here; the host supplies the metadata, so none of it depends on the guest (R3). `now` and `random`
take no message and no call site (R6, R8). In the crate each import is linked only by its caller,
so a module that uses none still runs on an older runtime (R9).

**This plan is written before its dependency exists.** 025-polyglot-service-client is fourteen
tasks into sixty-one on its own branch; `ServiceRequest`, `ServiceReply` and `ClientLogic.request`
are in its contract and not in code. By the user's decision nothing here is implemented until 025
is on `main` and this branch is rebased onto it, and the first task then is to read what 025
built against what this plan assumed of it (R14).

Planning found four things the spec had not said.

- **The rule is not the pool.** A view's handler runs on the blocking pool and the spec has it
  trap. The rule is a list of exports; the pool is why the listed ones are safe. An agent's `plan`
  export, which the spec's lists left out, is permitted (R1).
- **`ankka.now` is not on every call today.** A step, a tool call, a guardrail check and a result
  check carry metadata the host does not stamp, and the crate panics in a module when the entry is
  missing. FR-003 keeps the entry for older modules, so the four are stamped (R7).
- **An abandoned call could go on calling other services.** A handler past its deadline has
  already been answered with a fault and its thread runs on. Its next `request` now traps, so a
  consumer's redelivery is not raced by the attempt it replaced (R4).
- **025's protocol version is taken.** Its contract says 1.7; `main` reached 1.7 with replayable
  topic sources. 025 lands at a later minor and this feature takes the one after, with no message
  changed (R12).

And one consequence for developers, stated because it is easy to miss: `Context::now()` becomes
the time it is read and not the time the call began, and a module that reads it needs a runtime
with the import (R6, R9).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`sidecar`, and one constant each in `runtime` and
`controlplane-api`); Rust, edition 2024, target `wasm32-unknown-unknown` (`sdks/rust`);
WebAssembly text for test guests; Markdown (`protocol`, `docs`)

**Primary Dependencies**: none added to any module. Chicory 1.7.5 (runtime, compiler and wabt)
is already `sidecar`'s. The crate gains `getrandom` for the native target only, so no module's
dependency graph changes (R11).

**Storage**: none. No table, no DDL file, no journal or snapshot form, no `.proto` message.

**Testing**: munit — `sidecar`'s new `WasmImportsSuite` (hand-written guests and a stand-in for
the client: no cargo, no Docker), `WasmHostSuite` (its end-to-end case, with cargo), `ConformanceSuite` against the module
target, `SidecarClusterSuite` (k3s). `cargo test` with clippy for the crate and the example. The
docs build and the bdd checker. **No new suite starts a container.**

**Target Platform**: the runtime's image in module mode (`ANKKA_WASM_MODULE`), locally and in a
cluster.

**Project Type**: a platform runtime, a guest library, a protocol document, documentation

**Performance Goals**: an export call gains one thread-local set and clear. `now` is a clock read
with no memory access; `random` is one `SecureRandom` fill of at most 65,536 bytes. A fresh
instance costs what it did: the imports are still built once (R1).

**Constraints**: `runtime` and `sidecar`'s process path do not change; a module built before the
feature loads and runs (SC-004); a module that calls none of the three imports none of them (R9);
`ModuleLoader.Imports` equals what `HostImports.values` provides; the crate's copy of `protocol/`
stays identical to the canonical one; no deadline of any export changes (R5); warning-free in
both languages; `Test / parallelExecution := false` stays; no suite binds a fixed port

**Scale/Scope**: about 2 new Scala files and 6 changed in `sidecar`, 1 new suite and 3 changed;
2 version constants; in the crate 1 new source file, about 8 changed, 2 test files; the example's
conformance module; 2 protocol documents and their copies; 4 documentation pages changed and none
added; 4 feature files holding 19 scenarios, one more row of an existing outline, and 2 proposed
glossary terms, already written

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` states as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; the runtime interprets | pass | no effect is added; an import is the runtime acting for a handler, as `invoke` is |
| Where two interpreters reduce the same thing, they share one function | pass | a process's call and a module's are both `ClientLogic.request` (R3); the clock that stamps `ankka.now` is the one `now` answers (R6) |
| Module dependency direction | pass | everything is in `sidecar/wasm` and the crate; `runtime` gains a version string |
| `runtime` never sees the generated protocol | pass | the import parses and answers protocol bytes inside `sidecar`, as the other imports do |
| `runtime` and `sidecar` hold no graph code; no classpath scanning | pass | untouched |
| Wire names are a versioning boundary | pass | three import names are added and none changed; an added import is the ABI's own minor change (R12) |
| The protocol directory is copied whole, and CI proves it | pass | `README.md` and `WASM-ABI.md` change; the three copy scripts run |
| A Rust import referenced from a shared `match` is imported by every module | pass | three `extern` blocks, each reached by one function; `wasm-objdump` is the check (R9) |
| The module loader's list of imports equals what the host provides | pass | both gain the same three; `WasmHostSuite` already holds them equal |
| A `val` listing functions defined below it lists nulls | pass | `values` stays lazy; the new functions are `def`s like the rest |
| A trace set on one thread is invisible to another | pass | the call site is set on the thread that runs the export and read by an import on that thread; a missing one refuses, so the failure is loud (R1) |
| Anything the runtime asks a process to do carries the handler's metadata | pass | the host writes the call's metadata from what it sent (R3) |
| A fault is not a refusal, and the recorder is told which | pass | a callee's refusal is its status in the reply; an unresolvable name is `failure`; a forbidden call site is a trap, answered `Internal` (R2) |
| Never intern anything unbounded into the recorder's name table | pass | the span and count are 025's, named by declared handlers and service names |
| Tests are serialised; a test never binds a fixed port | pass | the scripted service is on loopback, port 0 |
| A test never names an image by a literal tag | pass | the cluster case uses the images the suite already builds, by `BuildInfo.imageTag` |
| Forked tests do not inherit sbt's `-D` | pass | no new switch |
| Could this check pass while the thing it checks is false? | pass | R13 names four checks that could and what replaces each; the rule is broken once in the quickstart to watch it fail |
| Each acceptance scenario ends as a test that fails without the feature | pass | 16 scenarios of `features/wasm/` and one row of its loading outline end as cases named for them; the 3 documentation scenarios are read off the pages, where only included samples and the generated tables are checked by the build |
| Docs: pages stand alone, samples from tested code, reference facts generated | pass | no page added; the sample is a marked region of the reference module |
| Every tracked file claimed by a CI path filter | pass | `features/wasm/` is under a claimed tree; the new suite is under `sidecar/` |

**Violations to justify**: none. What widens the platform's surface is under *Complexity
Tracking*.

**Re-check after Phase 1**: unchanged. The design added no module, no port, no stored form and no
dependency to anything a service ships.

## Project Structure

### Documentation (this feature)

```text
specs/030-wasm-request-and-clock/
├── plan.md              # this file
├── research.md          # R1–R15: decisions with file-level evidence; four things to verify first
├── data-model.md        # the call site, the imports' state, the crate's types, validation
├── quickstart.md        # the validation runs, by user story
├── contracts/
│   ├── wasm-imports.md      # the three imports, the table of exports, the trap, what a test must show
│   └── rust-api.md          # the context, the client, the errors, the native host and the test kit
└── tasks.md             # /speckit-tasks output
```

### Source Code (repository root)

```text
features/wasm/{calling-services,handlers-that-may-not-call,time-and-random-bytes}.feature   # written; 16 scenarios
features/wasm/loading.feature                                                                   # one more row of its outline
features/documentation/modules.feature                                                          # written; 3 scenarios
GLOSSARY.md                                                                                     # written; 2 terms proposed

protocol/
├── README.md                                 # the version, and what it added
└── WASM-ABI.md                               # three imports, the table of exports, the time

sidecar/src/main/scala/…/sidecar/
├── Main.scala                                # HostImports is given the client's timeout
├── ClientLogic.scala                         # the one-method seam the import calls, which it extends
└── wasm/
    ├── CallSite.scala                        # new: the value, the thread-local, Permitted, ImportRefused
    ├── GuestInstance.scala                   # call takes a call site and holds it for the export
    ├── HostImports.scala                     # request, now, random; the clock; the secure source
    ├── WasmConversation.scala                # a call site at every ask; ankka.now on the four that lacked it
    └── ModuleLoader.scala                    # Imports gains three names

modules/runtime/…/remote/Conversation.scala   # Version
controlplane-api/…/Compatibility.scala        # the version, and what it added

sidecar/src/test/scala/…/sidecar/
├── WasmImportsSuite.scala                    # new: hand-written guests, every export, the clock, the fills
├── WasmHostSuite.scala                       # the end-to-end case: relay, the command's trap, the time
├── SidecarClusterSuite.scala                 # the module admitted by name
└── conformance/                              # service.* run against a module

sdks/rust/
├── ankka/Cargo.toml                          # getrandom, native target only
├── ankka/src/abi/imports.rs                  # three extern blocks; NativeHost.now, .random
├── ankka/src/services.rs                     # new: Services, ServiceClient, ServiceResponse, ServiceError
├── ankka/src/context.rs                      # services(), now(), random()
├── ankka/src/components/*.rs                 # the kinds that set the services flag
├── ankka/src/testkit/unit.rs                 # ScriptedServices, fixed_clock, fixed_random
├── ankka/src/{lib,prelude,service}.rs        # exports; PROTOCOL_VERSION
├── ankka/src/codec/time.rs                   # the "No clock" note
├── ankka/protocol/                           # copied
├── ankka/tests/services.rs                   # new
└── examples/shopping-cart/src/conformance.rs # the route, service-asks, service-relay, the time and the fills

sdks/python/proto/, sdks/typescript/proto/    # copied; nothing else

docs/reference/{wasm-abi,rust-sdk,limitations}.md, docs/concepts/polyglot.md,
025's page on calling another service         # changed; none added
```

**Structure Decision**: the existing modules, with no new one. The host's part is one small new
file and four changed ones in `sidecar/wasm`; the crate's is one new file beside `secrets.rs`,
which it follows in shape. `runtime`, `http`, `operator`, `controlplane`, `cli` and the Python and
TypeScript SDKs are not touched beyond a version string and a copied document.

## Slices, in the order they are built, and what is released

Nothing is built until 025 is on `main`. Then five slices, each ending green.

0. **The base, read again.** Rebase; hold R14's table against what 025 built; correct this plan's
   names; settle the version number.
1. **The version and the call site.** The protocol minor, `CallSite`, `GuestInstance.call`, a
   site at every call `WasmConversation` makes. Nothing behaves differently.
2. **The import and its rule** (User Story 2). `request` in the host, refusing by export and
   passing a permitted call to `ClientLogic.request`, proven with hand-written guests and a
   stand-in for the client; the crate's one function beneath it; a Rust entity that calls out and
   keeps its state.
3. **The call** (User Story 1). What the call carries, the crate's client, the reference module,
   025's conformance cases against a module, the cluster case.
4. **The time and random bytes** (User Story 3). `now`, `random`, the clock moved to
   `HostImports`, `ankka.now` on the four requests, the crate's two functions and their native
   fallbacks. It depends on slice 1 alone.
5. **The documentation** (User Story 4), written against what 2 to 4 built.

**Slices 2 and 3 are one pull request.** An import that refuses in the right places is not on
`main` without the call it guards. Slice 4 may merge alone, before or after.

**The rule is built before the call**, though both stories are first priority, because the import
must refuse before it may proceed; and the test that every permitted export proceeds is written
with the rule, since a rule that refused everything would pass every other test of it.

**The version is written once, in slice 1.** `now`, `random` and `request` arrive under the same
number whichever of slices 3 and 4 merges first.

## Complexity Tracking

Nothing here violates a principle. These are the places the platform's surface grows, and why
each is the smaller of the options.

| What grows | Why needed | Simpler alternative rejected because |
|-----------|------------|-------------------------------------|
| A thread-local in the wasm host | an import is handed only Chicory's instance and must know which export is running | imports built per instance put fourteen allocations on the path of every fresh instance (R1) |
| `request` ignores the metadata the guest sends | attribution and the trace must not depend on the guest library | a bare module's calls would be untraced and counted from nobody (R3) |
| `request` refuses an abandoned call | a consumer's redelivery would run beside the attempt it replaced, both calling another service | leaving it lets a failed handler keep making calls nobody is waiting for (R4) |
| A protocol minor that changes no message | imports are dated by protocol version, and a runtime with 025 and without this must not claim them | "since 025's version" would be false of any release between the two (R12) |
| `getrandom` in the crate, native target only | Rust's standard library has no source of randomness, and a native test needs one | a hand-rolled source would be the crate's own randomness to get wrong (R11) |
| `Context::now()` changes meaning | the spec makes the time an import (FR-012) | keeping the old reading under the old name leaves the crate's main clock on a metadata entry that is being withdrawn |
