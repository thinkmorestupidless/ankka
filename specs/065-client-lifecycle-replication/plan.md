# Implementation Plan: Every client reads a workflow's standing and how a copy stands

**Branch**: `065-client-lifecycle-replication` | **Date**: 2026-11-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/065-client-lifecycle-replication/spec.md`

## Summary

Protocol 1.24 adds two typed callback rpcs, `Lifecycle` and `Replication`, and two module imports of
the same names. The sidecar answers each with the runtime's own reserved query, which already serves
the Scala, Java and Kotlin clients, and returns the answer as a protocol message (R1, R2). Python,
TypeScript, Go and Rust each gain `lifecycle` and `replication` calls, with a too-old error naming
1.24 in the three processes and a load refusal for a module (R4). Each component test kit gains a
way to set the answers, and Rust's also answers from what it runs (R5). Each test kit reads through
the running service (R6). Four conformance cases cover all seven languages (R7). The three docs
sections gain their four missing tabs and lose their exclusion, and the limitation goes (R8).

## Technical Context

**Language/Version**: Scala 3.9.0 on JDK 21 (protocol, sidecar, conformance); Python 3.12;
TypeScript on Node 22; Go 1.24; Rust stable (wasm32-wasip1 and native for tests).

**Primary Dependencies**: none new. ScalaPB (protocol), grpcio and protobuf (Python), Connect and
protobuf-es (TypeScript), grpc-go and buf (Go), prost (Rust).

**Storage**: none. Both reads come from state the runtime already holds.

**Testing**: munit (sidecar: a new `ClientReadsSuite`, `WasmHostSuite`, `ConformanceSuite`); pytest;
`node --test`; `go test`; `cargo test`; `just features`; `just docs`.

**Target Platform**: the sidecar beside a process; the sidecar's wasm host for a module.

**Project Type**: platform: protocol plus four SDKs.

**Languages reached**: all seven. Scala, Java and Kotlin already read both and are unchanged
(FR-014). Python, TypeScript, Rust and Go gain them, each with a tested sample and a tab on the three
pages. Rust's version refusal is at load, not at the call (R4).

**Performance Goals**: one read costs one ask of the entity, as the Scala read does; no new load.

**Constraints**:
- The reads run no handler and journal nothing (FR-005), which the reserved queries already ensure.
- Each new import is its own extern block in Rust, so a module that does not call it does not import
  it and still loads on an older runtime.
- The proto copies stay byte-identical to `protocol/` (CI diffs them).

**Scale/Scope**: 2 rpcs, 2 imports, 4 SDK calls each, 4 kit stubs, 4 conformance cases × 7
references, 3 pages.

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template, so the gates are the repository's own
rules (`CLAUDE.md`, `.claude/rules/`):

| Rule | How this plan holds it |
|---|---|
| A protocol change bumps the minor and is listed in `Compatibility.scala`'s history and `sidecar-protocol.md` | 1.24 in both, with the stale "1.22" in `sidecar-protocol.md:236` and `protocol/README.md:16` corrected |
| A refusal travels in the reply, never as a gRPC status, so a module's import carries it the same way | both replies are a `oneof` with `Error` (contract) |
| `ModuleLoader.Imports` equals what `HostImports` provides (`WasmHostSuite`) | both imports added to both, and the suite's set |
| Every SDK says which protocol a call needs when the runtime is too old | R4 |
| The docs check demands every language a section does not exclude; samples come from tested code | R8: includes from tested files; exclusions removed |
| Every acceptance scenario ends as a test that fails without the feature | the scenario-to-test map below |
| The Scala, Java and Kotlin surfaces do not change (FR-014) | no file under `modules/sdk`, `modules/sdk-java` or `sdks/kotlin` changes except the conformance and docs samples |

Re-checked after design: no violation.

## Project Structure

### Documentation (this feature)

```text
specs/065-client-lifecycle-replication/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/client-reads.md
└── tasks.md
```

### Source Code (repository root)

```text
protocol/src/main/protobuf/ankka/protocol/v1/client.proto   # the two rpcs and messages
protocol/README.md, protocol/WASM-ABI.md                     # 1.24, the two imports
controlplane-api/.../controlplane/api/Compatibility.scala     # ProtocolVersion(1, 24)
sidecar/src/main/scala/.../sidecar/
  ClientService.scala, ClientLogic.scala                      # serve and answer
  wasm/HostImports.scala, wasm/ModuleLoader.scala             # the two imports
sidecar/src/test/scala/.../sidecar/
  ClientReadsSuite.scala                                      # new: parity with the Scala client
  wasm/WasmHostSuite.scala                                    # the import set; a module importing them
  conformance/ConformanceSuite.scala, ConformanceReference.scala
modules/sdk-java/src/test/java/.../reference/ConformanceEndpoint.java   # conformance routes only
sdks/kotlin/samples/reference/.../ConformanceEndpoint.kt               # conformance routes only
sdks/python/
  proto/ (regenerated), src/ankka/{service.py,client.py,__init__.py}
  src/ankka/testkit/{unit.py,__init__.py}                     # ClientStub
  examples/shopping_cart/conformance.py, tests/…              # routes, tests, docs samples
sdks/typescript/
  proto/ (regenerated), src/{spec.ts,client.ts,index.ts}
  src/testkit/{unit.ts,index.ts}                              # stubClient
  examples/shopping-cart/conformance.ts, test/…
sdks/go/
  protocol/ and internal/pb/ (regenerated), ankka.go, client.go (or reads.go), errors.go
  testkit/kinds.go                                            # standIn methods, Answer* options
  examples/shopping-cart/conformance/{endpoints.go,components.go}, samples/…
sdks/rust/ankka/
  protocol/ (regenerated), src/{service.rs,client.rs,lib.rs,prelude.rs}
  src/abi/imports.rs                                          # Import variants, extern blocks
  src/testkit/unit.rs                                         # InMemory standing, with_* answers
sdks/rust/examples/shopping-cart/src/{conformance.rs,transfer.rs,planner.rs}
docs/build/{workflows.md,multi-agent-orchestration.md,event-sourced-entities.md,component-client.md}
docs/reference/{limitations.md,sidecar-protocol.md,wasm-abi.md}
features/polyglot/standing.feature, features/replication/status.feature   # written by specify
```

**Structure Decision**: the existing layout. Each layer's change goes beside the 1.18 `AwaitEnd`
addition it mirrors.

## Scenario to test

| Scenario | Test |
|---|---|
| a handler reads a running workflow's standing in every language | conformance `wf.lifecycle-running`, seven references |
| a handler reads a failed workflow's standing with the reason and the retries in every language | conformance `wf.lifecycle-failed`, seven references |
| a handler reads the standing the platform records for a workflow in every language | `ClientReadsSuite` (paused, ended, never started through the rpc) plus each SDK's decode test of each status from a fake sidecar or stand-in |
| the component test kit answers a workflow's standing it was told | each SDK's kit test with the stub |
| the Rust component test kit answers the standing of a workflow it runs | `sdks/rust/ankka/tests/unit_testkit.rs` |
| an answer a Rust component test kit was told wins over the workflow it runs | the same file |
| the component test kit refuses a standing it was not told | each SDK's kit test |
| the test kit reads a workflow's standing from the running service | each SDK's integration test (Rust through the sample's route) |
| a runtime from before the read refuses a workflow's standing, naming the protocol version | Python, TypeScript and Go client tests against a fake sidecar answering `UNIMPLEMENTED`; Rust: `WasmHostSuite`'s load refusal |
| a handler reads the home, the replication set and the position of a copy in every language | conformance `es.replication-status`, seven references |
| a handler reads a handover waiting / a conflict / an installation outside the replication set answers… | the Scala replication suites (`ReplicationSmokeSuite` and the features' step bindings), unchanged or extended to bind these scenarios |
| a handler reading how a copy of an entity that is not replicated stands is refused in every language | conformance `es.replication-refused` |
| the component test kit answers a handover waiting and a conflict as it was told | each SDK's kit test |
| the Rust component test kit answers how a copy stands from the entity it runs | `unit_testkit.rs` |
| the test kit reads how a copy stands from the running service | each SDK's integration test |
| a runtime from before the read refuses how a copy stands, naming the protocol version | as for the standing |
| the documentation shows … in every language | `just docs` (the docs check) |

## Complexity Tracking

No violation to justify.
