# Implementation Plan: WebAssembly Hosting

**Branch**: `design/wasm-hosting` (the spec directory is `015-wasm-hosting`) | **Date**: 2026-09-28 |
**Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/015-wasm-hosting/spec.md`, the design treatment it was
written from, `docs/design/wasm-hosting.md`, and the spike that treatment records (`WasmHostSpike` in
`sidecar`, with a Rust and a Go guest), whose numbers this plan is held to.

## Summary

Add a third hosting mode. The sidecar image, started with `ANKKA_WASM_MODULE` naming a file, loads
one WebAssembly module into its own JVM through Chicory's runtime compiler, reads discovery from the
module's exports, and hosts what the module declares with the runtime code that already hosts a
process: a second implementation of `Conversation` and nothing in `runtime` changes. The ABI is a
core-module convention, `ankka1_`-prefixed exports and one `ankka1` import module, carrying the
protocol's existing messages across linear memory plus a small additive envelope file; a guest is
stateless (handed its state on every call) or stateful (handed it once per loaded instance), chosen
per component in discovery, and the host holds the encoded state either way. The first guest
library is a Rust crate, `ankka` on crates.io, in `sdks/rust`: serde-derived codec, effects as
values, handlers with wire names, both shapes, a native unit testkit and a Docker integration
testkit, the shopping cart example and the conformance reference. The descriptor gains
`"hosting": "wasm"`; the operator renders one runtime container, with the developer's module image
run once as an init container that copies the module into a shared volume, because the cluster
runtimes ankka targets today cannot mount an image as a volume (R5). The conformance suite gains a
module target and runs the reference in both shapes. CI, release, the template, documentation and a
skill follow the TypeScript SDK's trail.

## Technical Context

**Language/Version**: Scala 3 for the host (the `sidecar` project, plus the descriptor, CRD and
operator); Rust stable (1.98 today, edition 2024) for the guest library, built to
`wasm32-unknown-unknown` with `panic = "abort"` (R6). No WASI: the ABI is the module's only imports.

**Primary Dependencies**: host: `com.dylibso.chicory:runtime` and `:compiler` 1.7.5 (R1), moved
from the sidecar's test scope to its compile scope; nothing new in `runtime`. Guest library:
`prost` 0.14 with `protox` for build-time stubs from the crate's copy of `protocol/`, `serde` and
`serde_json` for the codec; the `testkit` feature adds `testcontainers` for the integration testkit
(R8). No proc-macro crate of the library's own (R7).

**Storage**: none of the library's own. The runtime writes the same tables; the codec makes a Rust
service's payloads indistinguishable from the other SDKs' (R6), and the integration testkit takes the
platform's DDL out of the sidecar image as the Python testkit does.

**Testing**: `cargo test` for the library and the example (unit testkit, the encoding fixtures both
ways, and Docker-backed integration tests behind a feature); the platform's `ConformanceSuite`
against the reference module through `-Dankka.conformance.target=wasm:<path>` in both shapes (R9);
`WasmHostSuite` in `sidecar` for the host itself (discovery, faults, pools, the config import)
against a small test module; `RenderingSuite`, `CrdSchemaSuite` and `DescriptorSuite` for the
platform side; a k3s case deploying the Rust cart's module image; `RustTemplateSuite` for the CLI's
template. The spike stays as the benchmark.

**Target Platform**: the sidecar image on Linux, one container per pod on the platform, or beside
the compose Postgres locally with the module bind-mounted. The module is any Kubernetes the platform
runs on today, since delivery needs no image volume (R5).

**Project Type**: one new mode in an existing sbt project (`sidecar`), one new directory outside sbt
(`sdks/rust`, a cargo workspace), an additive protocol file, descriptor and CRD and operator changes,
a conformance target, a CLI template, one CI job, one release job, documentation pages and a skill.

**Performance Goals**: SC-003, a module-handled command in under half the time of the same command
through a process, for a 1 KB state: the spike measured 31 µs against a 94 µs hop for Rust (R2).
SC-005, sixty-four concurrent blocking host calls costing one wait: measured at 232 ms for 200 ms
holds (R4).

**Constraints**: the protocol's existing messages, the encoding, the fixtures and the conformance
cases unchanged (FR-038); the runtime's hosts unchanged (FR-039); the module reaches nothing but the
ABI's imports (FR-007); every reserved variable withheld from the module (FR-026, R10); no tracked
file written by a build; the first crate publish is manual (R12); a streaming route refused at
discovery (FR-013).

**Scale/Scope**: the host is a few files in `sidecar` (the module loader, the conversation, the
pools, the config import, discovery from a module) plus the operator's rendering; the library is the
cost, as the SDKs were: the Python SDK's 2,900 lines is the yardstick, with the protocol server's
share replaced by an ABI shim of a few hundred lines.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so there are no constitutional gates.
The gates applied instead are the repository's own, from `CLAUDE.md` and the two polyglot
features:

- **Effects are inert data; one reduction shared** — the module returns `Reply` messages the
  existing `RemoteEffect.materialise` reduces; nothing in the module performs I/O. Pass.
- **Registration is explicit** — discovery from the module is the same `Discovery.validate`. Pass.
- **`runtime` does not see the protocol** — the host lives in `sidecar`, over `Conversation`. Pass.
- **No platform-side jar reaches a repository by accident** — `sidecar` keeps `publish / skip`.
  Pass.
- **The wire name is declared separately from the method** — the crate's handler table. Pass.
- **A test must never name an image by a literal tag** — the k3s case tags by `BuildInfo.imageTag`.
  Pass.
- **Nothing in the build writes a tracked file** — the crate's version is `0.0.0`, rewritten by
  the release job. Pass.

Re-checked after Phase 1: unchanged. See Complexity Tracking for the two places this plan adds
something that could look like scope.

## Project Structure

### Documentation (this feature)

```text
specs/015-wasm-hosting/
├── plan.md              # This file
├── research.md          # Phase 0: R1–R15, each verified or named as a task
├── data-model.md        # Phase 1: module, spec envelope, shapes, instances, held state, descriptor
├── quickstart.md        # Phase 1: how each story is proven
├── contracts/
│   ├── wasm-abi.md              # the exports, imports, memory convention, envelopes, versioning
│   ├── descriptor-and-rendering.md  # hosting "wasm", refusals, the pod shape, the module image contract
│   ├── rust-sdk.md              # the crate's public surface, testkits, conformance and example
│   └── ci-and-release.md        # the sdk-rust jobs, the template suite, the manual first publish
└── tasks.md             # Phase 2 (/speckit-tasks), not created here
```

### Source Code (repository root)

```text
protocol/src/main/protobuf/ankka/protocol/v1/wasm.proto   # NEW, additive: WasmSpec, HandleRequest/Reply, FoldRequest, StepRequest (contracts/wasm-abi.md)
protocol/WASM-ABI.md                                       # NEW: the ABI contract beside ENCODING.md; copied into every guest library
protocol/README.md                                         # the module mode's rules beside the stream's

sidecar/src/main/scala/com/thinkmorestupidless/ankka/sidecar/
├── Main.scala                       # ANKKA_WASM_MODULE chooses the module mode; build() takes a Conversation and a Discovered from either
├── Settings.scala                   # wasmModule: Option[Path], wasmInstances, wasmMaxMemoryPages
├── Discovery.scala                  # validate() split from the gRPC dial; a wasm refusal for streaming handlers and routes
└── wasm/
    ├── ModuleLoader.scala           # parse, compile once, check the ankka1 exports and imports, call _initialize
    ├── GuestInstance.scala          # one instance: alloc/write/call/read/free, the packed return, traps as faults
    ├── InstancePools.scala          # the command pool (reused, pinned for stateful) and the blocking pool (instance per call)
    ├── HostImports.scala            # ankka1::{invoke, invoke_stream, query, schedule, cancel, log, config} over the ClientService's logic
    ├── WasmDiscovery.scala          # SidecarInfo in, WasmSpec out; the per-component shape
    ├── WasmConversation.scala       # Conversation: open() → a stateless or stateful InstanceSession over the held state; the unary calls
    └── HeldState.scala              # the encoded state per loaded instance, both shapes
sidecar/src/test/scala/com/thinkmorestupidless/ankka/sidecar/
├── WasmHostSuite.scala              # NEW: discovery, faults, pools, config filtering, ABI version refusal, against test modules
├── WasmHostSpike.scala              # unchanged: the benchmark
├── conformance/ConformanceTarget.scala   # + Module(path, shape): wasm:<path>
└── SidecarClusterSuite.scala        # + the wasm cases: one container, init container, refusals
sidecar/src/test/resources/wasm/     # the spike's guests, plus WasmHostSuite's small modules

controlplane-api/src/main/scala/.../descriptors.scala      # ServiceSpec.Wasm; problems for hosting "wasm"; protocol required
crd/src/main/scala/.../AnkkaService.scala                  # hosting: "embedded" | "process" | "wasm" (a String already)
kustomization/components/crd/ankkaservice.yaml             # enum gains wasm
operator/src/main/scala/.../Rendering.scala                # containersFor: the wasm shape; the init container and the ankka-module volume
operator/src/test/scala/.../RenderingSuite.scala           # the wasm pod shape pinned

sdks/rust/                                                 # NEW cargo workspace
├── Cargo.toml                       # members: ankka, examples/shopping-cart; workspace lints and profile
├── rust-toolchain.toml              # stable, targets: wasm32-unknown-unknown
├── protocol/                        # the copy: src/main/protobuf, ENCODING.md, WASM-ABI.md, fixtures/ (CI diffs it)
├── conformance.sh                   # builds the reference module, runs the suite in both shapes
├── ankka/
│   ├── Cargo.toml                   # name = "ankka", version = "0.0.0", features = ["testkit"]
│   ├── build.rs                     # prost + protox over protocol/
│   └── src/
│       ├── lib.rs                   # the public surface (contracts/rust-sdk.md)
│       ├── abi/                     # memory.rs (alloc/free/packed), exports.rs (the ankka1_ functions), imports.rs (the ankka1 module), panic.rs
│       ├── codec/                   # json.rs (serde_json over the encoding's rules), text.rs, time.rs (Instant, Duration), manifest.rs
│       ├── effects/                 # event_sourced.rs, key_value.rs, workflow.rs, agent.rs, view.rs, consumer.rs, timed_action.rs, http.rs
│       ├── components/              # the traits and handler tables per kind; Shape
│       ├── client.rs                # ComponentClient over the imports
│       ├── service.rs               # Service: the explicit registry; discovery; dispatch by kind and shape
│       └── testkit/                 # unit.rs (native), integration.rs (testcontainers; feature "testkit")
├── ankka/tests/                     # encoding_fixtures.rs (every fixture both ways), registration.rs, kinds.rs
└── examples/shopping-cart/
    ├── Cargo.toml                   # crate-type = ["cdylib"], publish = false
    ├── .cargo/config.toml           # target and stack size
    ├── src/                         # domain.rs, entity.rs, cart_rows.rs, checkout_*.rs, assistant.rs, endpoint.rs, conformance.rs, lib.rs
    ├── tests/cart.rs                # unit, and integration through the runtime image behind a feature
    ├── service.json                 # {"image": "sample-shopping-cart-rust:latest", "hosting": "wasm", "protocol": "1.0"}
    └── Dockerfile                   # FROM busybox; COPY the module; CMD copies it into /ankka/module (contracts/descriptor-and-rendering.md)

cli/src/main/templates/rust/         # NEW: Cargo.toml, .cargo/config.toml, src/{domain,item_entity,item_rows,api,lib}.rs, tests/, Dockerfile, service.json, README.md, .github/workflows/
cli/src/main/scala/.../Scaffold.scala          # Language.Rust
cli/src/test/scala/.../RustTemplateSuite.scala # NEW, via PolyglotTemplateSuite
cli/src/main/scala/.../mcp/AnkkaTools.scala    # "Scala, Python, TypeScript or Rust"

.github/workflows/ci.yml             # + sdk-rust (changes filter, job)
.github/workflows/release.yml        # + sdk-rust, needs: publish, environment crates-io
docker-compose.yml                   # comment: the polyglot profile is for a process; a module is bind-mounted (the template's compose says how)

docs/get-started/first-service-rust.md, docs/reference/rust-sdk.md, docs/reference/wasm-abi.md   # NEW
docs/concepts/polyglot.md            # the module mode: who owns what, the two shapes, when to choose each
docs/reference/{limitations,service-descriptor,sidecar-protocol}.md, docs/deploy/{images,run-locally}.md, docs/platform/install-local.md
docs/contributing/language-sdks.md   # the guest-library route beside the SDK route
mkdocs.yml                           # nav, languages: [scala, python, typescript, rust], site description
tools/docs/skill/ankka-rust/SKILL.md # NEW; Rust differences sections in the shared skills; committed copies regenerated
README.md, CLAUDE.md                 # commands, the module map, the manual first publish
```

**Structure Decision**: the host is a package inside `sidecar` rather than a new sbt project, because
it is the sidecar's second mode: it shares `Main`, `Settings`, `Discovery.validate`, `ClientService`'s
logic and `SidecarExtension`, and a project boundary would only force those into a third place.
Chicory joins `sidecar`'s compile dependencies and stays out of `runtime`, which keeps the published
libraries free of it. The ABI's envelopes are a new file in `protocol/` because every guest library
must generate them from one source, and the contract document sits beside `ENCODING.md` for the same
reason. The Rust workspace lives under `sdks/` beside Python and TypeScript because the protocol, the
fixtures, the conformance suite and the library must move together and a tag must prove them
consistent; it is one publishable crate with a `testkit` feature rather than two crates, so a
service's dependencies never include Docker tooling unless asked. The reference lives inside the
example, as it does for Python and TypeScript, so the conformance run exercises the code a reader
sees in the docs.

## Complexity Tracking

No constitution violations to justify. Two additions that could look like scope and are not:

| Addition | Why it is in this feature |
|---|---|
| An init container and a shared volume instead of an image volume (R5) | the treatment chose image volumes on the kubelet's default, but the container runtime has to support them too, and containerd does only from 2.3.2 — which k3s ships from 1.36.2 and the test image (1.35.1) does not. A pod shape that works on every cluster the platform runs on today is the honest one; the image volume is a later simplification, recorded in the treatment. The cost is one `cp` in the developer's image, which the template supplies |
| A `config` import (R10) | one container means the descriptor's variables are the runtime's process environment, so the render-time split that keeps a model's key from a process cannot apply; the split moves to read time, in the host, and is pinned by a test that asks for every reserved name and gets nothing |
