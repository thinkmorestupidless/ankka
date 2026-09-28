# Design treatment: a WebAssembly hosting mode

**Status**: treatment, spike done. Not yet fed to `speckit-specify`.
**Date**: 2026-09-28

## The proposition

Rather than an SDK per language, host a service compiled to WebAssembly. Rust, Go, C# and
anything else with a WebAssembly target become supportable through one host, and the platform
never learns which language produced the module.

The proposition is sound, and ankka is unusually well shaped for it. But "one host, any
language, immediately" claims more than WebAssembly delivers, and the version of this idea worth
building is not the obvious one. This treatment separates what WebAssembly changes from what it
leaves exactly where it is, names the shape that earns its place, and lists the questions a spike
must answer before a specification is written.

## What WebAssembly does and does not remove

A language SDK today is five things, listed in `docs/contributing/language-sdks.md`. Against the
Python SDK, roughly 2,900 lines of source, they divide like this:

| SDK piece | Today | With a WebAssembly host |
|---|---|---|
| serving the protocol: a gRPC server, bidirectional streams, discovery | per language; `server.py` and `client.py` are about a third of the SDK | one exported-function ABI, implemented once per language in a few hundred lines |
| the deployment shape | two containers per pod | one container: the runtime loads the module |
| the component model in the language's idiom | per language | unchanged: still per language |
| a codec that matches `protocol/ENCODING.md` and passes the fixtures | per language; the subtle part, where a one-event union and a `Long` past 2⁵³ hide | unchanged: still per language |
| unit and integration testkits | per language | unchanged: still per language |

So a Rust or Go service still needs a Rust or Go library: the effects as values, wire names
declared beside handlers, queries that cannot persist, and a codec that writes what the Scala
codecs write. The library is smaller than an SDK because its plumbing is an ABI shim rather
than a gRPC server, and the platform side of "a new language" shrinks to nothing. But the
codec is where compatibility bugs live and WebAssembly does nothing for it. The honest name
for the per-language piece is what the Extism project calls it, a **plugin development kit**, a
PDK; this treatment uses that word for the guest side and keeps "SDK" for what exists today.

"Immediately supportable" also varies by language, as of this writing:

- **Rust** targets `wasm32-wasip1` and `wasm32-wasip2` with the mainline toolchain, and serde's
  internally tagged enums produce the encoding's `"type"` discriminator with the case's simple
  name without ceremony. This is the language to prove the model with.
- **Go** has a `wasip1` target since 1.21, with binaries in the megabytes and a garbage collector
  in the module; TinyGo produces small modules but its reflection support is partial, and the
  mainline protobuf library depends on reflection. The envelope codec is the risk for Go.
- **C#** reaches WebAssembly through an experimental WASI workload and NativeAOT-LLVM. The runtime
  is large and the toolchain is still moving. Not a first or second guest.
- **Python** through `componentize-py` bundles an interpreter into every module, slow and tens of
  megabytes. The Python SDK already exists and is the better answer.

## The version worth building is in-process

A WebAssembly runtime in its own container, speaking the existing gRPC protocol to the sidecar,
is the Python SDK with an extra layer. It inherits the two-container pod, the per-handler
loopback hop and the local development story the sidecar model already pays for, and adds a
sandbox nobody asked for on top. It is not worth building.

The interesting design loads the module **inside the runtime's own JVM**. The sidecar image
starts with `ANKKA_WASM_MODULE` naming a file instead of `ANKKA_PROCESS_ADDRESS` naming a
port, reads discovery from the module's exports, and calls into it directly. Three things follow
that the sidecar model cannot offer:

- **The hop disappears.** `docs/design/polyglot-runtimes.md` names the loopback round trip as a
  cost that workflow steps and tool loops multiply. A call into a compiled module is a method
  call with a memory copy each way. `LoopbackLatencySpike` in `sidecar` is the existing
  measurement of the hop; the spike below measures the replacement against it.
- **Local development is one image and one file.** The runtime image with the module mounted,
  beside the compose Postgres, is the whole loop. That is the objection that pushed Kalix's
  successor back in-process, answered without giving up the language.
- **The guest can reach nothing except through host functions.** No database, no network, no
  filesystem, no clock unless the host offers one. That is the first credible story for running
  a customer's code inside a hosted installation's own process. It is a sandbox, not resource
  isolation: see *Sandboxing* below before reading it as multi-tenancy.

There are costs the sidecar model does not have, and they go in the same list:

- **Debugging is worse.** No JVM WebAssembly runtime steps through DWARF. A developer debugs the
  Rust code natively against the unit testkit, and reads logs through the host once it is
  deployed. The sidecar model lets a developer attach a debugger to their process.
- **A module instance is single-threaded.** Linear memory is not shared between threads, so one
  instance serves one call at a time. The host owns the concurrency; see *Instances* below.
- **A blocking host call blocks its instance.** A workflow step that invokes another component
  and waits holds its module instance for the duration. The same is true of the Python process
  today, per event loop rather than per instance, and the answer is the same: pools, and steps
  and tools never sharing an instance with entities.

## What already lines up

**`Conversation` is the seam, and it is the whole seam.** The remote hosts in `runtime/remote`
(`RemoteEventSourcedHost`, `RemoteKeyValueHost`, `RemoteWorkflowHost`, `RemoteProjection`) speak
to the developer's code through one trait, `Conversation`, in plain Scala values: `open` an
`InstanceSession`, `handleView`, `handleConsumer`, `invokeTimedAction`, `plan`, `invokeTool`,
`checkGuardrail`, `handleHttp`, `handleHttpStream`, `reachable`. The gRPC messages are one
transport of that trait, translated in `sidecar`'s `GrpcConversation`, and `runtime` never sees
them. A WebAssembly host is a second implementation of `Conversation` and nothing in `runtime`
changes. `Main.build` in `sidecar` already takes a `Discovered` spec and a `Conversation`; the
new mode is a second way of producing both.

That corrects a first impression. Because a WebAssembly guest holds no long-lived stream, it is
tempting to say it needs a second protocol. It does not: the protocol, as far as the runtime is
concerned, is `Conversation`, and the per-instance stream is a property of the gRPC transport.
The ABI below satisfies `InstanceSession` with a host-held state map and a stateless guest.

**The messages already exist.** The protobuf messages in `protocol/src/main/protobuf` describe
every request and reply: `Spec`, `EventSourcedIn` and `Out`, `ViewRequest` and `ViewEffect`,
`HttpRequest` and `HttpReply`, `PlanRequest`, `ToolRequest` and the rest. The ABI reuses them
byte for byte as the values crossing linear memory, so a guest needs a protobuf codec and
nothing new to learn about shapes. Only the `service` definitions, which name the gRPC calls,
are replaced by exported functions.

**The encoding and the conformance suite are the definition of compatible.** `ENCODING.md`, its
fixtures and the 49 cases of `ConformanceSuite` were written so that a divergence between
hosting modes is a failing test. `ConformanceTarget` already chooses between the Scala reference
in-process and a process at an address; a third form, a module on disk, makes the suite the
acceptance test for the host and for every PDK, exactly as it is for the Python and TypeScript
SDKs.

**The runtime image is already the node.** For `hosting: "process"` the operator renders the
sidecar image as the container that carries every port, probe, cluster variable and credential,
and the developer's image beside it with none. For a WebAssembly service the first container is
the same one, with one more variable and a volume, and the second container is gone.

## The ABI

Two facts about WebAssembly decide it. A core module exports functions over numbers only, so
bytes cross the boundary as a pointer and a length into the guest's linear memory, with the
guest exporting an allocator so the host can write into it. And the Component Model with WIT,
the standard that would make all of that go away, is not yet runnable by a pure-JVM runtime.
The ABI is therefore the Extism shape, a core-module convention, versioned so that a Component
Model world can replace it when a JVM runtime runs one.

**Exports the guest provides**, each prefixed with the ABI's major version, `ankka1_`:

| export | takes | returns |
|---|---|---|
| `alloc(len) -> ptr`, `free(ptr, len)` | | the host writes a request into memory it asked for |
| `discover(ptr, len) -> (ptr, len)` | `SidecarInfo` | `Spec` |
| `fold(ptr, len) -> (ptr, len)` | state, event, both as `Payload` | the new state |
| `handle(ptr, len) -> (ptr, len)` | kind, component, entity, state, `Command` | `Reply`, always carrying the new state |
| `run_step(ptr, len) -> (ptr, len)` | state, step, input | `StepReply` with the new state |
| `view`, `consumer`, `timed_action`, `plan`, `invoke_tool`, `check_guardrail`, `http` | the existing request message | the existing reply message |

A function that returns two values needs multi-value returns, which every current toolchain
supports; a packed 64-bit return is the fallback and Extism's choice.

**Imports the guest may call**, the `Client` service of the protocol as host functions:
`invoke`, `invoke_stream`, `query`, `schedule`, `cancel`, plus `log`. A host function runs on
the calling thread, which in ankka is a virtual thread, so a guest that invokes another
component and waits parks a carrier and nothing else. An agent's tool that calls an entity
works exactly as a Python tool does.

**The guest is stateless.** This is the one decision that departs from the gRPC protocol. There,
the process holds each loaded instance's folded state in memory and the sidecar replays events
into it once. Here the host holds each loaded instance's *encoded* state, calls `fold` per
event on replay and passes the state into every `handle`. The guest decodes state and command,
computes, and encodes events and the new state. What it costs is one decode and one encode of
the state per call, on top of what the Python process pays already, which is the decode of each
event on replay. What it buys: any instance of the module can serve any entity, so a pool needs
no pinning; memory per loaded entity is bounded by the host, not by the guest; a trap in the
module loses nothing, because the host still has the state; and passivation is a map removal.
The stateful alternative, one module instance holding a state table with `open` and `close`,
keeps the gRPC shape and pins each entity to an instance for its lifetime. The spike measured
the encode cost, and the number was surprising for one of the two guests: see *What the spike
said*. Both shapes are in the ABI, chosen per component.

**The encoding is untouched.** A `Payload` crossing the ABI carries the same content type,
manifest and bytes it does over gRPC, and a Rust service's journal is readable by its Scala
port. The PDK's codec is measured by the same fixtures as every SDK.

## Instances

One compiled module, a pool of instances, and the host decides which call goes where:

- **Entities and workflows** take an instance from a pool for the duration of one `fold` or
  `handle`. With a stateless guest the pool is a plain queue. Its size is a setting, defaulting
  to the number of carriers.
- **Steps, tools, guardrails and endpoints** run on a second pool, so a step waiting on a host
  call never holds an instance an entity command needs. Same shape as the runtime's own
  executors: work that may block gets its own capacity.
- **Instantiation cost** decides whether the second pool can be "an instance per call", as
  Extism does, which would remove the ceiling entirely for work that blocks. The spike measures
  it; a module with a few megabytes of initial memory makes it a memory zero-fill, and the
  answer may differ between a Rust module and a Go one.

## The runtime

Three candidates run WebAssembly on the JVM:

- **Chicory** (Apache 2.0, pure Java, no native code). An interpreter, with an ahead-of-time
  compiler to JVM bytecode for speed, WASI preview 1, host functions as plain Java lambdas, and
  a memory limit per instance. `runtime` gains one ordinary dependency and the image gains no
  platform-specific binary, which is the same rule that put the local observability endpoint on
  the JDK's own HTTP server. No Component Model yet, and instruction metering is in development
  rather than shipped.
- **GraalWasm**, part of the GraalVM polyglot family, runnable as a library on a standard JDK 21
  but at interpreter speed without the Truffle compiler in the JVM. Faster on GraalVM, which
  the images do not run.
- **wasmtime through JNI bindings**: the fastest and the most complete, including components,
  at the price of a native library per platform inside every image and a second runtime with its
  own memory to reason about.

Chicory is the recommendation, for the reason `runtime` has never taken a native dependency. The
spike is what confirms it is fast enough, and the `Conversation` seam means changing runtime
later touches one class.

## Sandboxing

A module reaches nothing the host does not import for it, which is a real property. It is not
isolation from resource exhaustion: no JVM runtime preempts a guest, so a hot loop holds its
carrier thread until the process is restarted, and instruction metering, which wasmtime has and
Chicory does not, is what would stop it. For a service running its own code in its own pod this
is the same situation as today, where a Scala handler in a loop does the same thing. For a
hosted installation running several customers' modules in one process it is disqualifying until
metering ships. The treatment records the property and claims nothing for it in the first
feature.

## The deployment shape

`hosting: "wasm"` in the descriptor. The operator renders one container, the sidecar image, with
`ANKKA_WASM_MODULE` naming a path, and the descriptor's `image` is an OCI image whose content is
the module and nothing else. The module reaches the pod through an **image volume**
(`volumes[].image`), which mounts an image's filesystem read-only. It went beta in Kubernetes
1.33 and is enabled by default only from 1.35, once containerd and CRI-O both supported it; the k3s
test image is 1.35.1, and an installation on an older cluster would need the `ImageVolume` gate on
by hand, which is a fact for `docs/platform/` and a check the operator can make once and report. That keeps the descriptor's `image`, the project's
registry credential and `imagePullSecret` doing exactly what they do today, and the platform's
runtime version out of the developer's build.

The alternative, a developer's image built `FROM` the sidecar image with the module copied in,
pins the runtime version in the developer's image. That is the coupling the sidecar model was
built to remove, and it is refused for the same reason the descriptor cannot name the sidecar
image.

Everything else a process-hosted service has, the WebAssembly one has unchanged: the environment
split by prefix, with the model's key and the database credential never reaching the guest; the
readiness probe on the management port; scaling, restart, expose and pause. The compatibility
check gains nothing: discovery carries the protocol version as it does now, and the ABI's major
version is in the export names, refused by name when the host does not speak it.

## Local development and testkits

The CLI is a native binary with no JVM and cannot host the runtime, so `ankka run service.wasm`
is not available. The loop is the one the Python and TypeScript templates use: a compose file
starting Postgres and the sidecar image with the module bind-mounted at the path
`ANKKA_WASM_MODULE` names. `ankka init --language rust` renders it.

The unit testkit needs no host at all. Effects are values in every language, and a Rust test
runs a handler against an in-memory state and asserts on the effect, round-tripping through the
codec as the Python testkit does. The integration testkit starts Postgres and the sidecar image
with the module under the language's own test runner, as `sdks/python`'s does with
testcontainers.

## Streaming

Two calls on `Conversation` return streams: `handleHttpStream`, an endpoint's server-sent
events, and the client's `invoke_stream`. A guest cannot return a stream from a synchronous
export. A host function `emit` called repeatedly from inside one `http` call produces the
stream while holding the instance for the response's whole duration, which is a poor trade for
a long-lived stream. An agent's token stream is unaffected, because the agent loop runs in the
host and the guest only plans and runs tools.

The first feature refuses a streaming route in discovery for a WebAssembly service, naming the
route, and lists the gap in `docs/reference/limitations.md`. Component Model async, in WASI
0.3, is the eventual answer and not one to reimplement on a core-module ABI.

## The audience, decided

The Python SDK was chosen for the audience writing agents, and the TypeScript SDK for developers
who run services on Node. A Rust-first hosting mode serves a third crowd: systems people, and
teams whose services are already Rust or Go and who want durable entities and workflows without
changing language. **Decided 2026-09-28: Rust is the first guest and Go the second.** Rust
because its toolchain and serde make it the cheapest proof of the host; Go because it is the
language the second guest has to be for the ABI to count as language-neutral, and because its
protobuf story under TinyGo is the open risk the spike names. The host is the same whoever the
guest is, so the decision costs nothing if the order later reverses.

## A caution from the lineage

`polyglot-runtimes.md` records that Cloudstate's successors moved the runtime back into the
developer's JVM and shipped one SDK, because SDKs never earned their upkeep. A WebAssembly host
does not reduce the number of guest libraries to maintain; it reduces what each costs, and adds
a third hosting mode to keep conformant. It also bets on toolchains that are still moving: the
Component Model, WASI 0.3, TinyGo's reflection, .NET's WASI workload. The bet is reasonable
because the seam it lands on is one trait, and the ABI is a convention over messages that
already exist, so the cost of being early is a versioned export prefix.

## What the spike must answer

Before a specification, in `sidecar`, gated on `-Dankka.benchmarks` like the loopback spike:

1. **The call cost.** A Rust module answering `discover` and one event-sourced `handle` through
   Chicory's AOT compiler, measured against `LoopbackLatencySpike` on the same machine. The
   number that matters is a `handle` including the state's decode and encode, since that is the
   stateless shape's price.
2. **Instantiation cost** for a module of realistic size, deciding whether the blocking pool is
   an instance per call.
3. **A blocking host function on a virtual thread**: a guest `invoke` that awaits a `Future`
   from inside Chicory, confirming the carrier is released and nothing in the runtime pins.
4. **Memory**: what an instance costs at rest with a Rust module and with a Go one, deciding
   the default pool size.
5. **The TinyGo envelope**: whether a small Go module can decode the `Spec` and `Command`
   messages with a reflection-free protobuf library, which decides whether Go is the second
   guest or the ABI needs a JSON envelope form beside the protobuf one. The candidate is
   `protowire`, the wire-level package under `google.golang.org/protobuf`, which reflects on
   nothing.

## What the spike said

`WasmHostSpike` in `sidecar`, with two guests loaded through Chicory 1.7.5 and run on 2026-09-28
on the development laptop (Apple silicon, 10 carriers):

- **Rust**, `sidecar/src/test/rust/spike-guest`, built to `wasm32-unknown-unknown`: 167 KB,
  prost for the envelope and serde_json for the domain.
- **Go**, `sidecar/src/test/go/spike-guest`, built by TinyGo 0.42 to `wasm-unknown` with the
  precise collector and a 64 KB stack: 327 KB, protowire for the envelope, hand-encoded, and
  `encoding/json` for the domain.

Both export the ABI sketched above, stateless, over the protocol's own messages. The correctness
case runs unconditionally against each and passes byte for byte on the JSON; the measurements
run under `-Dankka.benchmarks`, beside `LoopbackLatencySpike` for the number they are read
against.

| measurement | Rust | Go |
|---|---|---|
| loopback gRPC, one message each way, no work (the sidecar's hop today) | p50 94 µs, p99 1007 µs | same |
| `handle`, compiled, empty state: decode command, encode one event and the state | p50 9 µs, p99 21 µs | p50 40 µs, p99 362 µs |
| `handle`, compiled, 20-item cart (1 KB of state in and out) | p50 31 µs, p99 80 µs | p50 475 µs, p99 1215 µs |
| `handle`, compiled, 200-item cart (10 KB of state in and out) | p50 220 µs, p99 429 µs | p50 4.7 ms, p99 9.7 ms |
| `fold`, compiled, 20-item cart | p50 29 µs, p99 67 µs | p50 467 µs, p99 1139 µs |
| the same, interpreted | 20× to 50× slower | 20× to 30× slower |
| `discover` | p50 69 µs | p50 61 µs |
| one instance from a compiled module | p50 47 to 200 µs across runs | p50 31 µs |
| compiling the module to bytecode, once | 26 ms | 61 ms |
| an idle instance | 1.2 MiB (17 initial pages, a 1 MB default stack among them) | 260 KiB (2 pages) |
| 64 guests, both languages, each blocking 200 ms in a host function, on virtual threads | wall 232 ms | |

What each answers:

1. **The call cost is a property of the guest's codec, not of the boundary.** Crossing into a
   compiled module and back costs single-digit microseconds: both guests answer an empty-state
   command in tens of microseconds, a tenth of the hop, and `discover` is the same in both. What
   differs is the JSON. serde_json handles a 1 KB cart in 20 µs; TinyGo's reflection-based
   `encoding/json` takes 440 µs for the same bytes, so the Go guest's 20-item command is five
   times the hop it was meant to replace, and its 10 KB case is 4.7 ms. The stateless shape's
   price is linear in the state for both, at 22 µs per KB in Rust and 450 µs per KB in Go. Two
   consequences follow. The interpreter is out for both, and the runtime compiler's 26 to 61 ms
   per module happens once at discovery. And **the Go PDK cannot use `encoding/json`**: it needs
   a generated codec, written per type from the same schema the PDK already knows, which is what
   a Go PDK would have wanted anyway for the encoding's discriminator and its every-field rule. A
   first version of the Go guest, built with `wasm-unknown`'s default *leaking* collector, ran
   the 1 KB case in 199 µs and then trapped out of memory after a few thousand calls, so the
   collector is not optional either, and its cost is inside the 475 µs.
2. **Instantiation** at tens of microseconds makes an instance per call affordable for the
   blocking pool, as Extism does; a Rust module pays for zero-filling its default 1 MB stack, so
   a PDK should shrink it. It does not make instantiation free at the entity pool's rate; a queue
   of reused instances remains the shape there.
3. **A blocking host function on a virtual thread releases its carrier.** Sixty-four guests of
   both languages each held 200 ms inside Chicory on ten carriers and finished in 232 ms; a
   pinned carrier would have taken over a second. Nothing in the compiled call path holds a
   monitor around the host call.
4. **Memory** is 1.2 MiB per idle Rust instance and 260 KiB per Go one, most of it the guest's
   own initial memory. A pool the size of the carrier count is a few tens of megabytes at most,
   and an instance per blocking call is a stack's worth that lives for the call.
5. **The TinyGo envelope works.** A 240-line Go guest decodes `SidecarInfo` and the command,
   encodes `Spec`, the reply and the state with `protowire` alone, reflecting on nothing, and
   TinyGo compiles it with the one import the ABI names. The envelope is not the Go risk; the
   domain codec is, per item 1.

Two things the correctness case settled on the way: serde's internally tagged enum writes
`{"type":"ItemAdded","item":{…}}` with every field present, which is the encoding's sum type
exactly; and Go's `encoding/json` with a `Type` field first in the struct writes the same bytes,
so a generated Go codec has a byte-exact target to match.

**What this changes in the design.** The stateless guest stands for Rust. For Go, and for any
guest whose codec is an order of magnitude off serde's, the stateful variant is no longer a
later option: it decodes the state once per loaded instance and only the command or event per
call, and it is the difference between a 40 µs command and a 475 µs one for a cart-sized state.
The ABI should therefore carry both from the start, chosen per component in discovery, with
the host holding the encoded state in either case so a trap still loses nothing. A stateful
guest pins an entity to an instance for its loaded life, which is the pooling the sidecar
protocol already implies.

## Order of work, if the spike says yes

1. **The host.** `WasmConversation` in `sidecar` over Chicory, discovery from the module's
   export, the `ANKKA_WASM_MODULE` mode in `Main`, and a `wasm:<path>` form of
   `ConformanceTarget`. Proved against a module hand-written from the Scala
   `ConformanceReference`'s behaviour, before any PDK exists.
2. **The Rust PDK**, in `sdks/rust`: the component model, effects, the serde-based codec passing
   every fixture, the unit and integration testkits, and the conformance reference in Rust. This
   is where the cost lives, as the Python SDK was.
3. **The descriptor and the operator**: `hosting: "wasm"`, the image volume, `CrdSchemaSuite`,
   `RenderingSuite`, and a k3s case deploying a real module.
4. **The rest of a language**: the template, the CI job, a release job publishing to crates.io
   under trusted publishing, the first-service page, the reference page and the skill, following
   `docs/contributing/language-sdks.md` and the TypeScript SDK's checklist.
5. **A second guest**, Go, before anything claims "any language". Two guests through one host is
   the proof the ABI is language-neutral; one is a Rust SDK with an unusual transport.
