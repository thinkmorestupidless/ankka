---
paths:
  - "sdks/rust/**"
  - "sidecar/**"
  - "protocol/WASM-ABI.md"
  - "protocol/**/wasm*"
---

# WebAssembly modules and the Rust crate

## A service can be a WebAssembly module the runtime loads

The sidecar image has a second mode (feature 016). Given `ANKKA_WASM_MODULE`, it loads that module
into its own JVM through Chicory's compiler, discovers what the module declares from its
`ankka1_discover` export, and hosts it with the same remote hosts a process gets: `sidecar/wasm` is
a second `Conversation` (`WasmConversation`), and nothing in `runtime` changes. The ABI is
`protocol/WASM-ABI.md` — `ankka1_`-prefixed exports, one `ankka1` import module, the protocol's own
messages across linear memory, and the envelopes in `wasm.proto`. The runtime holds each instance's
encoded state (`HeldState`) in both guest shapes, so a trapped guest instance is discarded and
replaced and loses nothing; commands run on a pool of reused instances (`CommandPool`, a stateful
component pinned to one by its key) and anything that may wait on a fresh instance per call
(`BlockingPool`), so a step blocked in an import never holds an instance a command needs. The
`config` import answers the descriptor's variables and withholds the platform's own — the read-time
version of the split the operator makes for a process. On the platform a wasm service is one
container, the runtime's image, with the module copied into an `emptyDir` by the service's own image
run as an init container. `sdks/rust` is the first guest library, the crate `ankka`. An autonomous
agent in a module is the runtime's loop as for a process; the module answers only its tools,
guardrails and `ankka1_check_task_result`, on fresh instances — which is why a Rust rule takes a
`&Context`: a fresh instance remembers nothing between checks. Notifications are a stream, so a
module cannot forward them.

## Traps

- **A Rust import referenced from a shared `match` is imported by every module.** `abi::imports::call`
  dispatches every import in one function, so all of them are in any module that makes one call — a new
  import there would make every module built with the new crate need a runtime that provides it. The
  secret imports go through `call_secret`, a function of their own, and a module that never keeps a
  secret imports none of them (checked with `wasm-objdump -j Import`).
- **The module loader keeps its own list of the imports it admits** (`ModuleLoader.Imports`), apart from
  what `HostImports.values` provides. Adding an import to one only fails at a module's start, naming the
  import "the runtime does not provide". `WasmHostSuite` now holds the two equal.
- **A `val` that lists functions defined below it lists nulls.** `HostImports.values` was an eager
  `val` naming the `log` import declared after it, so the `ImportValues` held `null` for `log` and
  Chicory failed building any instance of a module that imported it — with a
  `NullPointerException` in `mapHostImports` that names nothing of ankka's. The spike guest never
  imported `log`, so every host test passed until the first real crate module arrived. It is a
  `lazy val`.
- **`export` is a keyword in Scala 3**, and the ABI is all exports. A parameter or a helper named
  `export` is a syntax error that scalafmt reports before the compiler does; the host says
  `function` and `fn`.
- **cargo reads `.cargo/config.toml` from the directory it is run in, upward — not from the package
  it builds.** The Rust example's config sets its target; run from `sdks/rust`, `cargo build -p
  shopping-cart --release` ignores it and builds for the host. Every build of a module from the
  workspace says `--target wasm32-unknown-unknown`, and the stack size a module needs lives in the
  *workspace's* `.cargo/config.toml`, where both directories see it.
- **A bind mount of a file that does not exist yet makes Docker create a directory in its place.**
  Starting the `wasm` compose profile before `cargo module` had built the module left a directory
  named `….wasm` where the module goes, and the next build failed `Operation not permitted`; OrbStack
  then kept that path's directory view even after the file existed. Both compose files mount the
  module with `bind.create_host_path: false`, so a missing module is refused (or, on OrbStack, the
  runtime refuses `no module at /module/service.wasm`) and nothing is created on the host.
