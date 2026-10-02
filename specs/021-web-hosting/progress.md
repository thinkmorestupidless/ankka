# Progress: Web Hosting

Where implementation stands, for whoever picks it up next. `tasks.md` is the source of truth for
which tasks are done (`[X]`); this note records what the task list cannot.

## State on 2026-10-02

- **Done: T001–T009**, Phases 1 (setup) and 2 (foundational). Each is its own commit on
  `021-web-hosting`, and every suite each touched was green when it was committed.
- **Next: T010**, the first task of Phase 3 (User Story 1, the MVP): the proxy's pure rules for
  requests in, in `proxy-core`.
- Nothing is uncommitted or half-written.

## What is established

- **The gate passed.** `ProxyTlsSpike` (T005) showed all five points of research R2, so the proxy is
  built on the JDK's `HttpsServer` as planned; no fallback. `HttpsServer` keeps the one `SSLContext`
  it is given, so `proxy/…/RotatingServerTls.scala` gives it one that makes every engine from
  `RotatingTls` per connection, and an `HttpsConfigurator` that restates the client-certificate
  requirement (the default drops it). T012's `TlsTransport` builds on that file.
- **Rendering is pinned** (T002): `operator/src/test/resources/unchanged/*.json.txt`, compared by
  `RenderingUnchangedSuite`. Rewrite only with `-Dankka.rendering.pin=true`, and only on purpose.
  Feature 020's `RenderingGoldenSuite` was not on `main`, so this is the suite later tasks name.
- **Certificate reading is pinned** (T003): `CallerIdentitySuite`, with `PreFeatureCaller`, the
  frozen copy of today's parser. Never edit `PreFeatureCaller`.
- **Feature 019's plumbing was copied, not merged** (T001): the one-file `GherkinSuite`,
  `.github/features-check.sh` and the CI `features` job. When 019 merges, those hunks should be
  identical; resolve `GLOSSARY.md` by keeping 019's text and re-adding this feature's sections.

## Things learned that the documents do not say

- **Always pass `-Dankka.cluster.tests=off` for offline runs that include `operator` or
  `controlPlane` tests.** Without it their test tasks first build the shopping cart's Docker image
  (`sampleImageForClusterTests`), and on this machine that hung twice for fifteen minutes before any
  test ran.
- `scalafmt` (the coursier CLI) is on PATH and formats a file in about a second; sbt's
  `scalafmtAll` is much slower.
- The `ServiceRecordingCostSuite` and `KeycloakStack` compiler warnings are already on `main`; they
  are not this feature's.
- Two choices made while implementing, both within the plan:
  - T006's cases live in a new `WebHostingDescriptorSuite` rather than in `HostingSuite` and
    `DescriptorSuite`.
  - No separate `Wire` codec was added for `Mount`. It is derived inside `ServiceSpec`'s codec, and
    a `Wire` entry would have needed a fixture of its own in `ControlPlaneFixturesSuite`. T018 may
    do the same for `MountStatus`.

## Resuming elsewhere

```bash
git fetch origin && git switch 021-web-hosting        # or: git worktree add ../ankka-021 021-web-hosting
python3 .github/ci-coverage.py && .github/features-check.sh
sbt -Dankka.cluster.tests=off 'controlPlaneApi/test' 'proxyCore/test' \
  'operator/testOnly *RenderingUnchangedSuite *CrdSchemaSuite' 'http/testOnly *CallerIdentitySuite'
sbt -Dankka.spikes=on 'proxy/testOnly *ProxyTlsSpike'
```

Then `/speckit-implement` continues from T010.
