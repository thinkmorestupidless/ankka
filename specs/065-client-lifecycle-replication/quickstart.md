# Quickstart: proving spec 065

Run from the repository root on JDK 21. Each step names what it proves.

1. **The protocol and its copies.** Regenerate each SDK's proto copy (`cd sdks/python && uv run
   python scripts/proto.py`, `cd sdks/typescript && npm run proto`, `sdks/go/scripts/proto.sh`,
   `sdks/rust/scripts/proto.sh`). `git diff --stat` shows each copy changed and nothing else
   unexpected; CI's `diff -r` steps pass.
2. **The sidecar.** `sbt "sidecar/testOnly *ClientReadsSuite *WasmHostSuite"`. The two rpcs answer
   a running, paused, completed, failed and never-started workflow, and a replicated, unreplicated
   and wrong-kind target, each compared with the Scala client's read. `WasmHostSuite` holds
   `ModuleLoader.Imports` equal to what `HostImports` provides, with `lifecycle` and `replication`.
3. **Each SDK's unit tests**, with a stub answering a failed standing and an unstubbed read refused:
   `cd sdks/python && uv run pytest -k "lifecycle or replication"`, `cd sdks/typescript && npm test`,
   `cd sdks/go && go test ./... -run 'Lifecycle|Replication'`, `cd sdks/rust && cargo test -p ankka
   lifecycle replication`.
4. **Too old.** Each process SDK's test against a fake sidecar answering `UNIMPLEMENTED`: the error
   names `1.24`. Rust: `WasmHostSuite` loads a module that imports `lifecycle` against a loader
   without it and is refused naming the import.
5. **Conformance**, per language: `sbt "sidecar/testOnly *ConformanceSuite -- *lifecycle* *replication*"`
   for Scala; with `-Dankka.conformance.target=...` for each process, and `wasm:` for the Rust
   module (the language runners in `sdks/*/` do this). The cases `wf.lifecycle-running`,
   `wf.lifecycle-failed`, `es.replication-status` and `es.replication-refused` pass for all seven.
6. **The docs.** `just docs-sync && just docs`. No problems; the three sections show seven tabs and
   carry no `languages:` exclusion; `grep -n "Only a Scala, Java or Kotlin client reads a workflow"
   docs/reference/limitations.md` prints nothing.
7. **The features.** `just features` reports 0 findings.
