# Progress: Web Hosting

Where implementation stands, for whoever picks it up next. `tasks.md` is the source of truth for
which tasks are done (`[X]`); this note records what the task list cannot.

## State on 2026-10-03

- **Done: T001–T023 and T025–T029.** Phase 3 (User Story 1) is done except T024, and Phase 4
  (User Story 2) is done up to T029. Each task, or small group, is its own commit on
  `021-web-hosting`, and every suite each touched was green when it was committed.
- **In progress: T024**, `deploying.feature` on k3s. `controlplane/src/test/.../WebHostingClusterFeatures.scala`
  (`DeployingWebHostingClusterFeatures`) is written and compiles but is **not committed**, nor is the
  `@ignore` it puts on the feature's last scenario, because it has not yet passed on k3s. Its first
  run failed its first scenario; the cause was being diagnosed when this note was written (see
  below).
- **Next after T024: T030** (the k3s isolation feature, which extends the same suite), then
  Phase 5 (mounts, T031–T035).

## What is established

- **The gate passed.** `ProxyTlsSpike` (T005) showed all five points of research R2, so the proxy is
  built on the JDK's `HttpsServer` as planned. `RotatingServerTls` gives it a context whose every
  engine comes from `RotatingTls` per connection; `TlsTransport` builds on it.
- **Rendering is pinned** (T002): `operator/src/test/resources/unchanged/*.json.txt`, compared by
  `RenderingUnchangedSuite`, unchanged through T016. Rewrite only with `-Dankka.rendering.pin=true`.
- **Certificate reading is pinned** (T003): `CallerIdentitySuite`, with `PreFeatureCaller`. Never
  edit `PreFeatureCaller`.
- **Feature 019's plumbing was copied, not merged** (T001). When 019 merges, those hunks should be
  identical; resolve `GLOSSARY.md` by keeping 019's text and re-adding this feature's sections.
- **The proxy's engine** (`proxy-core`) has three listeners: public (the transport's), the probe
  (plain, every address) and the calling address (plain, loopback only). Every listener a test
  starts must be given port 0 for all three, `callingPort = 0` included, or it binds 7630.
- **The operator's proxy variables are `Rendering.ProxyEnv`**, a copy of `ProxySettings.Variables`;
  `ProxyEnvironmentSuite` in the control plane's tests holds the two together.
- **Logs name a container** through `PodLogReader`; `ControlPlane.endpoints` takes `logs = Some(…)`,
  which a cluster suite must pass (`new PodLogs(k8s, prefix)`), or the route reads whatever cluster
  the developer's kubeconfig names.
- **`GherkinSuite` takes five values** per step now (T021 needed it).
- **`TlsServing`** (http's test sources) serves real endpoints over the platform's TLS with no
  service behind them; its actor system is `local`, or several on one machine collide on remoting.

## Things learned that the documents do not say

- **Always pass `-Dankka.cluster.tests=off` for offline runs that include `operator` or
  `controlPlane` tests**, or their test tasks first build images.
- **sbt buffers a suite's report until the suite ends.** A k3s Gherkin suite that fails early says
  nothing for many minutes. To see failures as they happen:
  `sbt 'set controlPlane / Test / logBuffered := false' 'controlPlane/testOnly *WebHostingClusterFeatures -- *<scenario words>*'`.
- `given` is a keyword in Scala 3, as `export` is; a helper named `given` is a syntax error.
- `scalafmt` (the coursier CLI) is at `~/Library/Application Support/Coursier/bin/scalafmt`.
- The `ServiceRecordingCostSuite` and `KeycloakStack` compiler warnings are already on `main`.
- Choices made within the plan:
  - T006's cases live in `WebHostingDescriptorSuite`.
  - The 503 for a call to a service nobody can find says `no service '<name>'` (and the project
    when it is another's); a service found and not listening is `the service <p>/<s> is not
    listening`; a callee with the wrong identity is `'<service>' is not the service that answered`.
  - The logs refusal is `--platform applies to a service with process or web hosting`, and the
    process-hosting logs limitation is gone from the documentation.

## Resuming elsewhere

```bash
git fetch origin && git switch 021-web-hosting
python3 .github/ci-coverage.py && .github/features-check.sh
sbt -Dankka.cluster.tests=off 'proxyCore/test' 'proxy/test' 'operator/test' 'controlPlaneApi/test'
caffeinate -i sbt 'controlPlane/testOnly *WebHostingClusterFeatures'     # T024, k3s
```

Then `/speckit-implement` continues from T024.
