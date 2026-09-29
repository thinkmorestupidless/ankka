# Research: WebAssembly Hosting

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Each finding says whether it was verified — against this repository, by the spike
(`sidecar/src/test/.../WasmHostSpike.scala`, run 2026-09-28), or against upstream sources fetched the
same day — or is an assumption a named task settles at implementation. The design treatment
(`docs/design/wasm-hosting.md`) made the recommendations; this document checks them.

## R1 — Runtime: Chicory 1.7.5, compiler required, in `sidecar`'s compile scope

**Verified by the spike and against Maven Central.** `com.dylibso.chicory:runtime` and `:compiler`
1.7.5 are the current release, pure JVM, Apache 2.0. `MachineFactoryCompiler.compile(module)`
returns a factory reused by every instance; compiling the spike's modules took 26 ms (Rust) and 61 ms
(Go), once. The interpreter was 20× to 50× slower on every measurement. `Instance.builder(module)
.withImportValues(...).withMachineFactory(factory).withMemoryLimits(...)` is the whole construction;
`HostFunction(module, name, FunctionType, WasmFunctionHandle)` registers an import;
`ExportFunction.apply(long...)` calls an export; `Memory.write/readBytes` move bytes. A trap surfaces
as `TrapException`, an unsatisfied import as an exception at build. Instruction metering is in
development upstream and not shipped.

**Decision**: Chicory 1.7.5 in `sidecar`'s compile dependencies (it is already in its test scope
for the spike), never in `runtime`. Compile once at start, before discovery; refuse to start on a
compile failure. `MemoryLimits` per instance with a default maximum of 4,096 pages (256 MiB), settable
by `ANKKA_WASM_MAX_MEMORY_PAGES`. An `_initialize` export is called once after build when present,
because TinyGo exports one and Rust does not (both proven in the spike).

**Alternatives considered**: GraalWasm (interpreter speed on the JDK the images run), wasmtime through
JNI (a native library per platform in every image). Both rejected in the treatment; the spike's
numbers remove the reason to revisit.

## R2 — ABI: `ankka1_` exports, one `ankka1` import module, packed returns, an additive proto file

**Verified by the spike**, which is the contract's first implementation in two languages. Rust's C
ABI returns an aggregate through memory, so a two-value return is packed into one `u64`, pointer in
the high half and length in the low half, as Extism does; TinyGo returns the same. Requests are
written into memory the guest allocates (`ankka1_alloc`), which the guest frees when it takes the
request; replies are allocated by the guest and freed by the host through `ankka1_free` after
reading. A host import that returns bytes allocates them through the guest's `ankka1_alloc` from
inside the host function, which Chicory allows (re-entry into the same instance on the same thread,
exercised by the spike's `call_out`). Crossing the boundary with an empty-state command cost 9 µs
(Rust) and 40 µs (Go) against the 94 µs loopback hop.

**Decision**: the contract is `protocol/WASM-ABI.md` ([contracts/wasm-abi.md](./contracts/wasm-abi.md)),
with the envelopes in a new file `protocol/src/main/protobuf/ankka/protocol/v1/wasm.proto`:
`WasmSpec` wrapping `Spec` with the per-component shape, `HandleRequest`/`HandleReply` for entities
and workflow commands, `FoldRequest`, `StepRequest`, and `Config`. Every other value crossing the
boundary is an existing message. The file is additive: no existing message gains a field, which
is what FR-038 protects. Export names carry the ABI's major version (`ankka1_`), and the runtime
refuses a module whose exports carry another prefix, naming both.

**Alternatives considered**: the Component Model with a WIT world — no pure-JVM runtime runs
components yet; recorded as the replacement under `ankka2_` when one does. Multi-value returns —
supported by Chicory and the toolchains but not by Rust's stable C ABI, so the packed form is what a
guest library can rely on. JSON envelopes — rejected; the spike proved protowire under TinyGo, and
prost under Rust, decode the protocol's messages without reflection.

## R3 — Two guest shapes, the host holds the encoded state in both

**Verified by the spike.** The stateless shape costs one state decode and one encode per call:
22 µs per KB in Rust, 450 µs per KB in Go. For a 1 KB cart the Rust guest answered in 31 µs and the Go
guest in 475 µs — five times the hop the mode replaces. The stateful shape decodes the state once per
loaded instance, so a Go command drops to the cost of its command and event alone (40 µs measured
with an empty state).

**Decision**: both shapes, declared per component in `WasmSpec` (a component absent from the list is
stateless). In both, `HeldState` in the host keeps each loaded instance's encoded state and sequence,
updated from every reply, so a trap loses nothing and passivation is a map removal. A stateful
component's loaded instance is pinned to one guest instance from the command pool (R4) for its loaded
life; `open` hands the snapshot and the replayed events to that instance, and `close` tells it to
drop the entry. The Rust library's default is stateless; the reference module declares the shape
from the `ANKKA_CONFORMANCE_SHAPE` variable through the `config` import (R10), which is how the
suite runs both.

**Alternatives considered**: stateless only — right for Rust, wrong for the second guest on record;
stateful only — pins every entity and makes the pool a hashing problem for components that do not
need it.

## R4 — Pools: reused instances for commands, an instance per call for work that may wait

**Verified by the spike.** An instance from a compiled module costs 31 µs (Go) to 47–200 µs (Rust,
dominated by zero-filling its 1 MB default stack) and 260 KiB to 1.2 MiB of heap. Sixty-four guests
each blocking 200 ms inside a host function on virtual threads finished in 232 ms on ten carriers:
nothing in Chicory's compiled call path pins a carrier around a host call.

**Decision**: two pools. The *command pool* serves entity and workflow commands, folds and steps'
state transitions: a fixed number of reused instances, `ANKKA_WASM_INSTANCES`, default the carrier
count; stateless calls take any free instance, stateful components pin an entity to one by hash of
its id. The *blocking pool* serves everything that may call back and wait (steps, tools, guardrails,
endpoints, views, consumers, timed actions): an instance per call, discarded after. A call never
waits for the other pool's instance, which is FR-005 and SC-005. The Rust template sets a 256 KB
stack (R6) so an instance per call is a quarter of the spike's cost.

**Alternatives considered**: one pool — a step waiting on an entity would hold an instance a command
needs; instance-per-call everywhere — 200 µs on every command is a fifth of the hop for nothing.

## R5 — Module delivery: an init container copies it into a shared volume, not an image volume

**Verified upstream** (the Kubernetes image-volumes task page, the k3s issue tracker and a
January 2026 survey of image volume support). The kubelet's `ImageVolume` gate is on by default from
1.35 and stable in 1.36, but the container runtime must support the CRI mount too, and containerd
does so only from 2.3.2. k3s bundles containerd 2.2.3 through 1.36.1 and supports image volumes from
1.36.2; the k3s test image is `rancher/k3s:v1.35.1-k3s1`. The local kind node and the first
production cluster (GKE) were not checked and do not need to be: a delivery that depends on the
node's containerd is one the platform cannot promise this year.

**Decision**: the descriptor's image is run once as an **init container** whose job is to copy its
module to `/ankka/module/service.wasm` on an `emptyDir` volume the runtime container mounts read-only
([contracts/descriptor-and-rendering.md](./contracts/descriptor-and-rendering.md)). The module
image's contract is therefore "when run with `/ankka/module` mounted, put the module there and exit
0", which the template's `Dockerfile` satisfies with `busybox` and a `cp`; a scratch image cannot.
The spec's FR-025, FR-029, one edge case, one assumption and one out-of-scope line are amended to
say this; the treatment records the finding. The image volume is the future simplification once
every target cluster's containerd is 2.3.2 or later, and the runtime container's side of the
contract (`ANKKA_WASM_MODULE` naming a path) does not change when it arrives.

**Alternatives considered**: image volumes with the k3s image bumped to 1.36.2 — leaves kind and
production unverified and makes a Kubernetes minor a hard floor for one feature; a developer image
built `FROM` the runtime image — pins the platform's version in the developer's build, the coupling
the sidecar model exists to remove; downloading the module at start — a registry credential inside
the runtime, refused for the same reason the process mode never had one.

## R6 — The Rust crate: `ankka`, `wasm32-unknown-unknown`, serde for the codec, own time types

**Verified against crates.io** (`ankka`, `ankka-sdk` and `ankka-pdk` do not exist, checked
2026-09-28) **and by the spike** (Rust 1.98 stable, `wasm32-unknown-unknown`, prost 0.14 with
protox for stubs without `protoc`, serde_json rendering the encoding's sum type byte for byte with
`#[serde(tag = "type")]`). serde writes every field including `None` as `null`, reads an absent
`Option` as `None`, ignores unknown fields, and carries `i64` exactly beyond 2⁵³; `serde_json` writes
doubles by the shortest round-trip, which is jsoniter's rule too, but the exponent forms may differ
(`1e21` against `1.0E21`), and the fixtures decide.

**Decision**: the crate is named `ankka`, matching PyPI and npm. Target `wasm32-unknown-unknown`,
`panic = "abort"`, with a panic hook that sends the message through `ankka1::log` before the trap so a
fault names itself in the runtime's log. The template's `.cargo/config.toml` sets
`-C link-arg=-zstack-size=262144`, a quarter of Rust's default, for the instance cost in R4. The
codec is `serde` with the library's own `Instant` and `Duration` value types (the encoding's
`Z`-suffixed ISO-8601 with 0, 3, 6 or 9 fractional digits, and ISO-8601 durations), formatted by
the library; conversions from `std::time` are provided. Sum types are Rust enums with struct or
newtype-of-struct variants under `#[serde(tag = "type")]`; a tuple variant is refused by the codec's
tests, not silently misrendered. The encoding-fixture test runs every fixture both ways and
**fails**, never skips, on a fixture with no matching codec; a double whose exponent form differs is
found there, and the library's JSON writer renders it as jsoniter does if it must.

**Alternatives considered**: `wasm32-wasip1` — nothing in the ABI needs WASI, and an import the host
does not provide is a refusal at load; a proc-macro crate for handler declaration — a table of
`command("add-item", Cart::add_item)` calls is a plain function and reads as the Scala companion does
(R7); `chrono` or `jiff` for time — a dependency for two value types whose formatting the library
must own anyway.

## R7 — Crate layout: one publishable crate with a `testkit` feature, examples as workspace members

**Verified against this repository** (the Python and TypeScript layouts) **and cargo's rules**: a
module must be a `cdylib`, which an `examples/` directory inside a library crate cannot produce, so
the example and the reference are workspace members of their own with `publish = false`.

**Decision**: `sdks/rust/` is a cargo workspace: `ankka` (the library, `version = "0.0.0"`, feature
`testkit`) and `examples/shopping-cart` (a `cdylib` with the reference components inside, as the
Python and TypeScript examples carry theirs). Components are declared as a struct implementing a
kind's trait (`EventSourcedEntity` with associated `State`, `Event`, `Command` types), handlers in a
table built from plain functions with wire names, and a `Service` builder as the explicit registry;
a query handler's signature returns `ReadOnlyEffect`, so a persisting query is a type error. The
ABI's exports are emitted by one macro invocation at the crate root, `ankka::service!(build)`,
which is the only place a macro appears and is `macro_rules!`, not a proc macro.

## R8 — Testkits: native unit kit; integration kit over `testcontainers` with a bind-mounted module

**Verified against this repository**: the Python integration testkit starts Postgres and the sidecar
image and copies the DDL out of the image; the compose file's `schema` service does the same.
**Assumption, settled by task**: the `testcontainers` crate's current release supports bind mounts,
published ports and reading a container's stdout, which are the three things the kit needs; the
task pins the version.

**Decision**: `ankka::testkit::unit` runs one component natively, no module, round-tripping every
value through its codecs (the same `materialise` rule as the other SDKs' unit kits, expressed as the
library's own reduction so the kit cannot disagree with the host, which reduces the same `Reply`).
`ankka::testkit::integration` (feature `testkit`) starts Postgres with the DDL read from the sidecar
image by running it once with `cat /opt/docker/ddl/*.sql`, then the sidecar image with the module
bind-mounted at `/module/service.wasm`, `ANKKA_WASM_MODULE` naming it and the HTTP port published;
`restart()` starts a new runtime container on the same database. A `Module::build()` helper runs
`cargo build --release --target wasm32-unknown-unknown` for the calling package so a test needs no
prior step; a path can be given instead.

## R9 — Conformance: a module target, run in both shapes

**Verified against this repository**: `ConformanceTarget.fromProperty` chooses `InProcess` or
`Sidecar(address)` from `-Dankka.conformance.target`; the `Sidecar` target wires `Discovery`, a
conversation, `RemoteAgent`, `RemoteEndpoint` and `SidecarExtension` onto `AnkkaTestKit`'s Postgres.

**Decision**: a third form, `wasm:<path>`, builds `Module(path, shape)`: `ModuleLoader` and
`WasmDiscovery` in place of the gRPC dial, `WasmConversation` in place of `GrpcConversation`, the same
kit. `-Dankka.conformance.shape=stateful` sets the `ANKKA_CONFORMANCE_SHAPE` variable the reference
module reads through `config`; `sdks/rust/conformance.sh` builds the reference module and runs the
suite twice. `isProcess` is true for a module target (the suite's process-only cases apply: discovery
refusals, `ReportError` becomes the runtime's log). The cases are untouched (FR-023).

## R10 — The module's environment: a `config` import that filters reserved names at read time

**Verified against this repository**: `ServiceSpec.SidecarEnvPrefixes` (`ANTHROPIC_`, `ANKKA_MODEL_`,
`ANKKA_DB_`) is the render-time split for a process-hosted service, duplicated in the operator and
pinned by `RenderingSuite`; the cluster's five variables and `ANKKA_HTTP_PORT` are refused in the
descriptor.

**Decision**: with one container, every descriptor variable is in the runtime's process environment.
The host exposes `ankka1::config(name) -> value`, answering only names that start with none of the
reserved prefixes and are not one of the platform's own (`ANKKA_*` cluster and sidecar variables,
`POD_IP`). `WasmHostSuite` asks for every reserved name and asserts absence, and for a descriptor
variable and asserts the value. The library exposes it as `ankka::config("NAME")`. The operator
renders the descriptor's variables onto the one container unsplit, and `RenderingSuite` pins that a
wasm service renders no second container.

## R11 — Descriptor, CRD and rendering

**Verified against this repository**: `ServiceSpec.hosting` is a string validated against
`Embedded` and `Process`, `protocol` is required with `process`; `AnkkaServiceSpec.hosting` is a
string with the CRD's enum `[embedded, process]`; `Rendering.containersFor` branches on
`ProcessHosting`; `SchemaInit` already renders an init container and a volume, so the pattern
exists; `CrdSchemaSuite` compares the case class to the schema.

**Decision**: `ServiceSpec.Wasm = "wasm"`, accepted with `protocol` required (the library declares
one); the same refusals as `process` for reserved variables, the runtime's image and an HTTP port.
The CRD enum gains `wasm`. `containersFor` renders for `wasm` the node container from the sidecar
image with `ANKKA_WASM_MODULE=/ankka/module/service.wasm` and the descriptor's variables unsplit, the
`ankka-module` `emptyDir` volume mounted read-only, and an init container from the descriptor's image
with the volume mounted read-write and no command override (the image's own entrypoint copies). The
pod's `imagePullSecrets` covers the init container. Readiness is the sidecar's: discovery done and
the module loaded (`reachable` is constant once loaded).

## R12 — crates.io: trusted publishing after a manual first publish

**Verified upstream** (crates.io trusted-publishing docs, the `rust-lang/crates-io-auth-action`
repository, the 2025 crates.io development update). A trusted publisher is configured per crate at
`crates.io/crates/<crate>/settings/trusted-publishing` with repository, workflow and environment; the
crate must exist first, so the first release is by hand, exactly as npm's was. The action exchanges
GitHub's OIDC token for a 30-minute token and revokes it after the job; the job needs
`id-token: write`.

**Decision**: a `sdk-rust` release job, `needs: publish`, environment `crates-io`, that writes the
tag's version into `sdks/rust/ankka/Cargo.toml`, verifies the copy of `protocol/`, builds, packages
(`cargo package`), checks the packaged crate builds alone for the WebAssembly target, and publishes
with the action's token; a `cargo info`-style guard skips the upload when the version exists so a
re-run finishes a cancelled release. The first publish is manual and recorded in `CLAUDE.md`
([contracts/ci-and-release.md](./contracts/ci-and-release.md)).

## R13 — CI: an `sdk-rust` job on the Python job's shape

**Verified against this repository**: `ci.yml`'s `changes` filter maps paths to jobs; the
`sdk-python` job diffs the protocol copy, builds the sidecar and sample images, runs the SDK's checks
and the conformance run, then the template suite with `-Dankka.template.tests=python`.

**Decision**: the same steps for Rust: `rustup` with the WebAssembly target, `diff -r` of the
protocol copy, `cargo fmt --check` and `cargo clippy`, `cargo test` (unit and fixtures), the sidecar
and Scala cart images from this commit, `cargo test --features slow` (integration and journal
portability), `./conformance.sh`, and `sbt -Dankka.template.tests=rust 'cli/testOnly *RustTemplateSuite'`.
The filter maps `sdks/rust/**`, the Rust template, the suite, and — because the host is in the Scala
tree — the Scala build already covers `sidecar/`.

## R14 — Documentation and skills

**Verified against this repository**: the TypeScript trail (`get-started/first-service-typescript.md`,
`reference/typescript-sdk.md`, `tools/docs/skill/ankka-typescript`, differences sections in five
shared skills, `languages:` frontmatter and tab sets checked by `docs check`, `mkdocs.yml`'s
`languages` list, `AnkkaTools`' language sentences).

**Decision**: the same trail for Rust plus what the mode itself needs: a section in
`concepts/polyglot.md` on the module mode and the two shapes, `reference/wasm-abi.md` carrying the
contract, the descriptor reference's `wasm` row, the images page's module image, the limitations
page's four lines, `install-local.md` saying nothing new is needed (R5). User-facing pages call the
crate the "Rust SDK": to a developer it is one, and "guest library" is this specification's word for
the distinction that matters to the platform.

## R15 — Kubernetes proof without a new node image

**Verified against this repository**: `SidecarClusterSuite` deploys the Python cart's image with
`hosting = "process"` into k3s 1.35.1 and asserts the two-container pod, the environment split, the
reachability rules, scaling and a rolling restart.

**Decision**: the same suite gains the wasm cases against the Rust cart's module image built by
`docker build` in the suite (as the Python image is): a one-container pod with one init container,
`Ready`, the cart commanded, the config filter observed from inside (a route in the example returns
what `config` answers for a reserved and an unreserved name), a rolling restart refusing nothing,
and an image whose module has the wrong ABI prefix reported `Failed` with the reason. No change to
the k3s image (R5).

## Assumptions settled by tasks

- The `testcontainers` crate's release and its bind-mount, port and stdout APIs (R8). **Settled
  (T030)**: `testcontainers` 0.28 with its `blocking` feature (`SyncRunner`): `Mount::bind_mount` with
  `AccessMode::ReadOnly` for the module and the schema, `with_exposed_port` / `get_host_port_ipv4`
  for HTTP, `stdout_to_vec` / `stderr_to_vec` to wait for Postgres's second "ready" (the image starts
  once to run the schema, then again to serve) and to attach the runtime's log to a failure. Networks
  are created by name on first use. The HTTP client is `ureq` 3.4, blocking, with every status an
  answer. The schema is copied out of the runtime image with `docker create` and `docker cp`, as the
  Python kit does; readiness is `GET /_ankka/health` answering 200 on the runtime's HTTP port.
- Whether `serde_json`'s double rendering matches every fixture, or the library needs its own writer
  for the exponent form (R6). **Settled (T019)**: it does not match — Rust's shortest form is not
  Java's `Double.toString` layout. No separate writer was needed: a custom `serde_json` formatter
  (`codec/float.rs`) turns Rust's shortest `{:e}` digits into Java's layout (plain decimal from 1e-3
  to 1e7, `d.dddE±n` outside it); `f32` uses its own shortest digits. All 23 fixtures round-trip
  byte-identically.
- The kind node image's containerd version, recorded in `install-local.md` only as "not needed" (R5).
  **Settled (T052)**: `install-local.md` says a wasm service needs nothing more of the cluster, since
  the module arrives by an init container on any container runtime kind ships; no version is named.
- The stack size that keeps an instance per call under 50 µs for the example's module (R4, R6).
  **Measured (T056)**, with the example as a third guest in `WasmHostSpike`, on 2026-09-29 at a load
  average near 20 (other sessions' clusters on the same machine), so absolute times are inflated
  several-fold and the comparisons within one run are what hold:
  - the 256 KiB stack brings an idle instance to **464 KiB** (5 pages), against the spike's Rust
    guest's 1,246 KiB with Rust's default 1 MiB stack;
  - building an instance: **175 µs** p50 against the spike guest's 310 µs in the same run (the spike
    measured the latter at 37 µs on a quiet machine, which puts the example near 21 µs there — under
    50 µs, but by proportion rather than by a quiet measurement);
  - **SC-003 holds for the crate's real codec**: a command with a 1 KB state, handle p50 **40 µs**
    (the spike's hand-written guest 78 µs in the same run), under half the 94 µs loopback hop even at
    that load; 10 KB, 250 µs.
  **Re-measured on a quiet machine (T058, 2026-09-29, load ~4)**, the same run holding every number:
  the loopback hop, one message each way with no work, is **48 µs** p50 (not the 94 µs of the
  first spike run, which was taken on a busier machine); the SDK example's 1 KB `handle` is **32 µs**
  p50 (p99 41 µs), 10 KB 214 µs; an instance from the compiled module **122 µs** p50 (the spike's
  Rust guest 24 µs — the example's module is larger, 474 KB against 201 KB, and its data segment
  is what an instance copies, not its stack); 466 KiB idle. So **SC-003 holds narrowly and by
  inference, not by a direct measurement**: the criterion compares against the same command
  through a process, which is at least the 48 µs hop plus the process's own ~20 µs of the same
  work at Rust's speed (~68 µs, half of it 34 µs); against the bare hop alone it does not hold, and
  against the Python or Go processes the platform hosts today — whose codecs take hundreds of
  microseconds for the same kilobyte — it holds by a wide margin. A direct measurement would time a
  1 KB command through a process target end to end. The 50 µs per-instance goal (R4) is not met
  by the example's module on a quiet machine; it is by the smaller spike guest.

## Decisions made during implementation

- **Which exports are required** (T008): the ABI said a module missing "any required export" is
  refused without saying which. `ankka1_alloc`, `ankka1_free`, `ankka1_discover` and `memory` are
  required of every module; the rest by what it declares, checked at discovery (`WasmDiscovery`),
  which also refuses a module declaring a view it cannot answer. `WASM-ABI.md` says so.
- **`log`'s levels** (T010): 0 trace, 1 debug, 2 info, 3 warn, 4 error, to the logger `ankka.module`.
- **`ankka.sequence` on a command** (T013): the crate's `Context::sequence()` needed the journal
  sequence and `HandleRequest` carries none, so the host sets it in the command's metadata.
- **Replay is folded on the next call's thread** (T013): the remote event sourced host delivers
  replayed events from a Pekko stream; `WasmConversation` queues them and folds before the next
  command, on its virtual thread, rather than calling the guest from a stream thread.
- **Registration by value** (T022): Rust's coherence rules allow one blanket implementation per
  trait, so the kind is inferred from a marker type parameter, which needs a value: components are
  unit structs, registered and named to the client by value.
- **Discovery's protocol version** is the one the crate's protocol copy carries (1.1 on this branch),
  as the Python SDK sends; the descriptor's `protocol` may say 1.0 — only the major is checked.
