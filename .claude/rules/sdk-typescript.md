---
paths:
  - "sdks/typescript/**"
  - "cli/src/main/templates/typescript/**"
---

# The TypeScript SDK

## Traps

- **Node's type stripping runs only erasable TypeScript, and codegen does not know that.** The TypeScript
  SDK (`sdks/typescript`) runs its sources, tests and examples directly under `node`, which refuses `enum`,
  parameter properties (`constructor(private x)`) and decorators. protoc-gen-es emits a TypeScript `enum`
  for every proto enum unless `erasable_syntax=true` is set in `buf.gen.yaml`; the first typecheck of the
  generated stubs failed on exactly that. `erasableSyntaxOnly` in `tsconfig.json` keeps hand-written code
  honest, and it caught four parameter properties written from habit on the first day.
- **Generated imports say `.ts`, and `tsc` rewrites them.** `import_extension=ts` in `buf.gen.yaml` with
  `rewriteRelativeImportExtensions` in the tsconfig is what lets the same generated file run from source
  under Node and resolve as `.js` in `dist/`. The two options are a pair; drop either and one of the two
  paths breaks.
- **`exports` conditions are matched in order, and TypeScript honours `types` first.** The package's
  `exports` carry an `ankka-source` condition pointing at `src/` so the examples can `import "ankka"` in
  the repository (`node --conditions=ankka-source`, `customConditions` in the tsconfig). Listed after
  `types`, it was never reached once `dist/` existed and the typecheck quietly resolved against a stale
  build. `ankka-source` comes first.
- **`files` in `package.json` overrides `.gitignore` for packing.** `src/_proto/` is gitignored and
  `dist/_proto/` ships, because `files: ["dist"]` is the whole rule. The Python wheel needed hatchling's
  `artifacts` for the same thing; npm needs nothing.
- **`npm pack --pack-destination` does not create the directory.** `enoent` with no path in the message.
- **A Connect bidi client needs the request iterable to implement `throw`.** An `AsyncIterable` built by
  hand as a queue failed every conversation test with `[internal] AsyncIterable does not implement throw`;
  the queue's iterator has `return` and `throw` for this reason.
- **`http2.Server.close()` waits for every session to end, and a client keeps an idle one open for
  minutes.** `Server.stop()` closes the sessions it has seen (tracked from the `session` event) and destroys
  the stragglers after a grace period, or the test process never exits and `after` hooks hang. It looked
  like a hanging test; it was a hanging listener. Node 24 eventually times the idle session out; Node 22
  never does, so a test that stopped a raw `http2.createServer` with a bare `close()` passed on 24 and hung
  the `sdk-typescript (22)` CI job at `npm test` until it was cancelled. Every server a test starts, the
  SDK's or a fake sidecar's, destroys its sessions before `close()` — and that difference is why the
  matrix runs both lines.
- **Connect speaks gRPC to grpc-java over plain HTTP/2 on loopback**, verified against the sidecar image on
  2026-09-26: discovery, the entity stream with init, replay and snapshot requests, a graceful stop ending
  the stream cleanly and a kill surfacing as `Premature close`. The gRPC protocol needs `http2.createServer`;
  Connect's HTTP/1.1 path cannot carry bidirectional streams.
- **npm's trusted publishing cannot create a package.** A trusted publisher is configured on a package
  that already exists, and npm/cli#8544 (a PyPI-style pending publisher) is open. The first publish of the
  TypeScript SDK is by hand, after the tag's `publish` job; see the publishing rules in `.claude/rules/build-and-release.md`.
- **`npm publish dist-pack/x.tgz` is a GitHub clone, not a file.** npm-package-arg treats a bare
  `a/b` as the `owner/repo` shorthand whatever its suffix, so the release's publish step ran
  `git ls-remote ssh://git@github.com/dist-pack/ankka-0.6.0.tgz.git` and failed with `Permission
  denied (publickey)` — a failure that reads like a missing SSH key on the runner and is a spelling.
  A spec is a file only when it starts with `./`, `../`, `/` or `~/`; the step and the manual
  first-publish command both say `./dist-pack/…`.
- **`await using` is Node 24; Node 22 refuses it with a syntax error.** The integration testkit offers
  `Symbol.asyncDispose` and `stop()`, and the docs show `try`/`finally`, because the package's floor is 22.22.
