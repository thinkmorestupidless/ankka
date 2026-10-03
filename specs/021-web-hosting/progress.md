# Progress: Web Hosting

Where implementation stands, for whoever picks it up next. `tasks.md` is the source of truth for
which tasks are done (`[X]`); this note records what the task list cannot.

## State on 2026-10-03

- **Done: T001–T035.** User Stories 1, 2 and 3 are done: the proxy, the operator's rendering,
  the status, logs, the console, the descriptor feature, and three k3s features
  (`DeployingWebHostingClusterFeatures`, 19 scenarios in about six minutes;
  `IsolationWebHostingClusterFeatures`, 4 including a real call to the shopping cart) and three
  loopback features (`RequestsFeature`, `CallingServicesFeature`, `MountsFeature`).
- **Next: T036**, `ankka local web` (Phase 6), then Phase 7 (template, sample, CI, documentation).
- Nothing is uncommitted.

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
- **The proxy's allotment is 250m and 192Mi** with lean JVM flags in its image (research R19): at
  100m its JVM took about ten seconds to start, and on a busy k3s node it missed its readiness
  deadline, which is what made `deploying.feature` fail only when run whole.
- **The kubelet's wording** for a probe answered 503 is confirmed on k3s (research R8).
- **The operator's proxy variables are `Rendering.ProxyEnv`**, a copy of `ProxySettings.Variables`;
  `ProxyEnvironmentSuite` in the control plane's tests holds the two together.
- **Logs name a container** through `PodLogReader`; `ControlPlane.endpoints` takes `logs = Some(…)`,
  which a cluster suite must pass (`new PodLogs(k8s, prefix)`), or the route reads whatever cluster
  the developer's kubeconfig names.
- **`GherkinSuite` takes five values** per step now (T021 needed it).
- **`TlsServing`** (http's test sources) serves real endpoints over the platform's TLS with no
  service behind them; its actor system is `local`, or several on one machine collide on remoting.
- **`Caller.fromCertificate` takes the callee's own identity** (T031): a mount URI of its project
  is `Gateway`, of another project refused. `PreFeatureCaller` stays the frozen reading.
- **`ProxySteps`** runs all three loopback features: callees are real runtime HTTP servers
  (`ProxySteps.Callee`), a pre-feature callee reads with `PreFeatureCaller` (`OldCallee`), and a
  second web-hosted service is a second proxy (`others`, started when first reached).

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
caffeinate -i sbt 'controlPlane/testOnly *WebHostingClusterFeatures'     # both k3s features
```

Then `/speckit-implement` continues from T036.
