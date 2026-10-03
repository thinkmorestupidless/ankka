# Implementation Plan: Web Hosting — A User Interface Deployed Beside Its Services

**Branch**: `021-web-hosting` | **Date**: 2026-10-02 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/021-web-hosting/spec.md`, its clarifications of
2026-10-02, the living features under `features/web-hosting/` and
`features/documentation/web-hosting.feature`, and the root `GLOSSARY.md`.

## Summary

Add a fourth hosting mode, `"hosting": "web"`: the image is any program that serves HTTP, and the
operator runs it beside a platform proxy in the same pod. The proxy is new code, not a mode of the
sidecar, because the sidecar cannot start without a cluster and a database (R1). Its engine is a
module with no dependency on ankka or Pekko, `proxy-core`, on the JDK's own HTTP server and client;
the image, `proxy`, adds mutual TLS from `runtime`'s `RotatingTls`, and the CLI embeds the same
engine for `ankka local web`, so a mount means one thing on a laptop and in a cluster.

The proxy accepts every connection, admits the internet, the service itself and the services the
descriptor names,
tells the process who called and what address was used (derived, never read from the request),
passes requests under a mount to the mounted service, and serves a loopback address at which the
process calls services by name, with `HttpServiceClients`' own resolution and identity check. A
request under a mount is sent under a second certificate, `ankka://<project>/<service>/mount`,
which a runtime in the same project reads as the internet and any other refuses; a runtime from
before this feature already refuses that shape, with no change to it (R3, verified in the code).
The internet therefore reaches a web-hosted service through the gateway or under a mount by a
sibling, and no descriptor can read a certificate's key (FR-045).

The descriptor gains `mounts`, `callers` and `processPort`; the resource and its CRD follow; the
operator renders two containers, no database, no cluster certificate, no peers role, and a probe
policy of its own. `ankka services logs` learns to name a container, which also makes it work for
process hosting. The CLI gains `ankka local web` and a `web` template; the shopping cart gains an
interface; the documentation gains three pages and a skill.

## Technical Context

**Language/Version**: Scala 3 on JDK 21 for the proxy, the descriptor, the CRD, the operator, the
control plane and the CLI. TypeScript on Node 24 for the template and the sample, run by Node
directly, as the console's host is.

**Primary Dependencies**: none new on the JVM. `proxy-core` uses `com.sun.net.httpserver` and
`java.net.http`, both the JDK's; `proxy` takes `RotatingTls` from `ankka-runtime` and
`Caller.fromCertificate` from `ankka-http`. The template and the sample use Vite and React for the
app and `node:http` for the server, with no server framework.

**Storage**: none. A web-hosted service has no database; the descriptor's new fields travel in the
control plane's existing events and state, with defaults that replay old journals (R20).

**Testing**: munit throughout. `GherkinSuite` runs six of the feature files whole, each by the one
suite that can (R16): three against the real proxy on loopback with certificates from `test-pki`,
one against the control plane's apply route, two over k3s with the control plane and the operator.
The other four are tests named after their scenarios. Rendering, descriptor, CRD and caller-identity
suites are offline. `WebTemplateSuite` renders and runs the template through the real local
command. `node --test` in the template and the sample. Two spikes come first (R2, R3).

**Target Platform**: the proxy image on Linux in a pod beside the developer's container, and the
same engine inside the CLI's native image on macOS and Linux.

**Project Type**: two new sbt projects (`proxy-core`, `proxy`), one new image, changes to
`controlplane-api`, `crd`, `operator`, `controlplane`, `http`, `runtime` and `cli`, one CLI
template, one sample outside sbt, one CI job, one release image, documentation and a skill.

**Performance Goals**: SC-007, under 5 ms added at the median against a request made to the
process directly (R19 measures it, with that denominator). SC-003, `Ready` within 30 seconds of
apply with the image on the node. SC-005, no refused request across a rolling replacement.

**Constraints**: every existing service rendered byte for byte as before (FR-037); the gateway
does nothing but route (FR-038); nothing a request says about its caller or its address reaches
the process (FR-017, FR-018); `proxy-core` depends on nothing of ankka's or Pekko's, so the CLI
stays free of an actor system; HTTP/1.1 only and no upgraded connections, which is the JDK server's
limit and the spec's assumption (R1); no tracked file written by a build; a test never names an
image by a literal tag.

**Scale/Scope**: the engine is small, a few hundred lines: two listeners, an admission rule, a
mount table, a streaming pass-through and five answers of its own. The spread is the cost: the
descriptor's rules, the CRD, the operator's rendering, the status, the logs route, the console's
mirror, a template, a sample, and the documentation each change a little.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so there are no constitutional gates.
The gates applied are the repository's own, from `CLAUDE.md`:

- **Module dependency direction** — `proxy-core` depends on nothing; `proxy` on `runtime` and
  `http`; `cli` on `controlplane-api` and `proxy-core`; `operator` still on `crd` alone;
  `controlplane-api` still on `core` alone. `runtime` gains nothing. Pass.
- **One implementation of a rule both ends apply** — the descriptor's rules are in
  `controlplane-api`; the proxy's rules are in `proxy-core`, used in the cluster and on a laptop;
  where a service is in a cluster is `HttpServiceClients`' own lookup (R10); and what the operator
  writes for the proxy is held to what the proxy reads by a suite with both on its classpath. Pass.
- **A resource field is declared in the CRD** — the three fields and the enum value, held by
  `CrdSchemaSuite`, which gains the enum check it lacks (R7). Pass.
- **The hostname is derived, never written** — a mount names a service, never an address; the
  process is told an address the operator derived (R5). Pass.
- **Nothing the request says is trusted** — the caller is the certificate's; the forwarded address
  is derived; copies in the request are removed; and no descriptor can read a certificate's key
  (FR-045), so the process cannot speak as the platform. Pass.
- **A service's own identity cannot read routes or the cluster** — a web-hosted pod mounts no
  token and has no role. Pass.
- **No secret in the journal** — the descriptor names a secret by reference, as today. Pass.
- **No platform-side jar reaches a repository by accident** — both new projects carry
  `publish / skip`. Pass.
- **A test never names an image by a literal tag** — images are tagged `BuildInfo.imageTag`; the
  one case that needs a previous release is a manual step in `quickstart.md`, and says why. Pass.
- **Tests that start from an empty cluster cannot see a migration bug** — FR-037 is pinned by a
  fixture written before `Rendering` changes and by the k3s reconcile case (contracts). Pass.
- **Could this check pass while the thing is false?** — asked of each proof in `quickstart.md`;
  the two answered "yes" were sharpened there (a page through the gateway, not `Ready`; the
  frozen parser, broken once). Pass.
- **An overlay that only works from the script is not an overlay** — the operator's new settings
  are in the component and replaced by the overlays. Pass.
- **Nothing in the build writes a tracked file** — nothing does. Pass.

Re-checked after Phase 1: unchanged. Complexity Tracking names the three additions that could look
like scope.

## Project Structure

### Documentation (this feature)

```text
specs/021-web-hosting/
├── plan.md              # This file
├── research.md          # Phase 0: R1–R21, each verified, measured, or named as a task
├── data-model.md        # Phase 1: the descriptor, the resource, the status, the proxy's settings, identities, ports
├── quickstart.md        # Phase 1: how each story and each success criterion is proven
├── contracts/
│   ├── descriptor-and-rendering.md  # every refusal's message; the pod; what must not change; the mount identity
│   ├── process-contract.md          # what the proxy gives a process and asks of it (becomes a docs page)
│   ├── cli-and-control-plane.md     # services get and logs, local web, init, the wire, the console
│   └── ci-and-release.md            # projects, images, overlays, the CI job, the release, the docs
├── checklists/requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks), not created here
```

### Source Code (repository root)

```text
proxy-core/                                   # NEW sbt project `proxyCore`: no ankka, no Pekko
└── src/main/scala/com/thinkmorestupidless/ankka/proxy/core/
    ├── ProxySettings.scala                   # identity, ports, mounts, callers, public authority; parse from the environment
    ├── Admission.scala                       # Sender; who is admitted: the internet, itself, a named service, any in the project, local
    ├── Mounts.scala                          # the table; whole-segment match; the path with the mount removed
    ├── CallingAddress.scala                  # /<service>/…  and  /<service>.<project>/…
    ├── Headers.scala                         # what is set, what is removed, hop-by-hop
    ├── Answers.scala                         # the proxy's own 400/403/502/503/504, marked
    ├── Locator.scala                         # trait: a service's address and how to verify it
    ├── Transport.scala                       # trait: who the caller is; the client to send with (plain, service, mount)
    └── ProxyEngine.scala                     # the listeners, the pass-through, the timeout, readiness, draining
proxy-core/src/test/scala/…                   # the rules as pure functions; the engine in plain HTTP against stand-ins

proxy/                                        # NEW sbt project `proxy`: the image `ankka-proxy`
├── src/main/scala/com/thinkmorestupidless/ankka/proxy/
│   ├── Main.scala                            # one line over run(): settings, TLS, engine, block until stopped
│   ├── TlsTransport.scala                    # RotatingTls behind an SSLContext for HttpsServer; the caller from the peer certificate
│   └── ClusterLocator.scala                  # HttpServiceClients.kubernetes; contextRequiring the callee's identity
└── src/test/scala/…
    ├── ProxyTlsSpike.scala                   # R2, first
    ├── ProxyFeatures.scala                   # GherkinSuite × requests, calling-services, mounts
    ├── ProxySteps.scala                      # the harness: test-pki certificates, a stand-in process, real HttpServer callees
    └── ProxyBenchmark.scala                  # R19, under -Dankka.benchmarks

modules/runtime/src/main/scala/…/runtime/
├── RotatingTls.scala                         # + parseMountUri
└── HttpServiceClients.scala                  # unchanged: its public `kubernetes` lookup is what the proxy calls
modules/http/src/main/scala/…/http/Caller.scala          # a mount URI of this service's own project reads as Gateway
modules/http/src/test/scala/…/http/CallerIdentitySuite.scala   # NEW: every URI shape, and today's parser frozen

controlplane-api/src/main/scala/…/api/descriptors.scala  # Web; mounts, callers, processPort; the rules; ServiceStatus + MountStatus
controlplane-api/src/test/scala/…/api/{HostingSuite,DescriptorSuite,ControlPlaneFixturesSuite,WebHostingDocumentationSuite}.scala
crd/src/main/scala/…/crd/AnkkaService.scala              # mounts, callers, processPort
kustomization/components/crd/ankkaservice.yaml           # the three properties; hosting enum gains web

operator/src/main/scala/…/operator/
├── Rendering.scala                           # WebHosting; containers; unknown hosting is a problem; no cluster objects
├── ZeroTrust.scala                           # mountCertificate, probePolicy; volumes and mounts by what the pod holds
├── Provisioning.scala                        # ProvisioningPlan.NotNeeded
├── LifecycleRules.scala                      # the detail when the process never listens (R8)
└── Settings.scala                            # proxyImage, httpsPort
operator/src/test/scala/…/operator/
├── WebHostingRenderingSuite.scala            # NEW
├── RenderingUnchangedSuite.scala             # NEW, written before Rendering changes
├── SettingsSuite.scala                       # NEW: the proxy's image, the HTTPS port
└── CrdSchemaSuite.scala                      # + the hosting enum

controlplane/src/main/scala/…/controlplane/
├── deploy/ServiceProjection.scala            # the new fields; provisionDatabase false for web
├── deploy/PodLogs.scala                      # a container is named; a small trait in front, so a test can see which
├── api/ServiceEndpoint.scala                 # mount states on GET; platform=true on logs
├── application/ServiceRows.scala             # the new fields on the row
└── domain/model.scala                        # toStatus; databasePhrase "none"
controlplane/src/test/scala/…/controlplane/
├── DescriptorFeatures.scala                  # GherkinSuite × descriptor.feature
├── WebHostingClusterFeatures.scala           # GherkinSuite × deploying, isolation, over k3s
├── ProxyEnvironmentSuite.scala               # what the operator renders is what the proxy's settings parse
├── SampleDeploymentClusterSuite.scala        # + the cart's interface
├── ServiceLogsSuite.scala                    # NEW: which container each hosting's logs are read from
├── RemoteOverlaySuite.scala                  # + the proxy's image, one container
└── EventCompatibilitySuite.scala             # + a descriptor with the new fields, and one without

cli/src/main/scala/…/cli/
├── local/LocalWeb.scala                      # NEW: the engine in local mode; the child process; --service
├── local/LocalLocator.scala                  # NEW: over LocalSource
├── Main.scala                                # local web; logs --platform; init --language web
├── Init.scala, Scaffold.scala                # Language.Web
├── Output.scala                              # services get: process, callers, mounts, database none
├── ControlPlaneClient.scala                  # serviceLogs(platform)
└── mcp/AnkkaTools.scala                      # service_logs' argument
cli/src/main/templates/
├── common/.mcp.json                          # every template
├── common-service/docker-compose.yml         # MOVED from common: Python, TypeScript and Rust only
└── web/                                      # NEW (contracts/cli-and-control-plane.md)
cli/src/test/scala/…/cli/{LocalWebSuite,WebTemplateSuite}.scala   # NEW
cli/native-smoke.sh                           # + the web template; + local web

samples/shopping-cart-web/                    # NEW: the template's shape; service.json name "cart-web"

console/package/src/client/schemas.ts         # the status's new fields
console/package/src/routes/{service,logs}.tsx # "Runs as", mounts, callers; the platform switch
console/package/src/testing/fake-control-plane.ts
console/package/fixtures/control-plane/       # regenerated

kustomization/components/operator/operator.yaml          # ANKKA_PROXY_IMAGE, ANKKA_HTTPS_PORT
kustomization/overlays/{local,cloud}/                    # the replacement; cloud: image entry and proxy-image.yaml
kustomization/deploy-local.sh                            # build the sample's image; load both

build.sbt                                     # proxyCore, proxy; cli's dependency; image tasks; the template list
.github/workflows/ci.yml                      # filters; the web job
.github/workflows/release.yml                 # ankka-proxy

docs/get-started/first-interface.md, docs/deploy/web-hosting.md, docs/reference/web-hosting.md   # NEW
docs/…                                        # the pages contracts/ci-and-release.md lists
tools/docs/skill/ankka-web/SKILL.md           # NEW; committed copies regenerated
mkdocs.yml, README.md, CLAUDE.md
features/web-hosting/*.feature, features/documentation/web-hosting.feature, GLOSSARY.md
```

**Structure Decision**: the proxy is two projects for the reason `controlplane-api` and
`controlplane` are two. `proxy-core` is what both ends hold: the image in a cluster and the CLI on
a laptop. It depends on nothing so that the CLI's native image can carry it, and so that "the local
command behaves as the cluster does" is a build fact and not a second implementation kept in step
by hand. `proxy` is the part only a cluster has: certificates, and where a service is in
Kubernetes. It takes those from `runtime` and `http` so there is one reading of a certificate and
one lookup of a service in the whole platform. The k3s features live in `controlplane`'s tests, not
`proxy`'s, because they need the control plane and the operator together and that is the one place
both are on a test classpath. The sample sits beside the Scala sample and outside sbt, built with
`docker build` as the console's image is, because its toolchain is Node's.

## Order of work

The stories are the spec's and are independently testable. What the plan adds is the order inside
them that keeps a false assumption from being built on:

1. **The two spikes** (R2, R3): the JDK's HTTPS server under `RotatingTls`, and today's parser
   frozen. Nothing else starts until both pass.
2. **`RenderingUnchangedSuite`'s fixture**, written from `main`'s `Rendering` before it is touched.
3. **Feature 019's four pieces on this branch** (R17), by rebase if it has merged, by cherry-pick
   if not.
4. Story 1: the descriptor's rules; the resource and CRD; `proxy-core` and `proxy` (requests in,
   admission, readiness, answers); the operator's rendering; the status and logs; `deploying.feature`
   on k3s.
5. Story 2: the calling address; `calling-services.feature`; `isolation.feature` on k3s, now that
   there is a calling address to isolate.
6. Story 3: the mount table; the mount certificate; the runtime's reading of it; mount states.
7. Story 4: `ankka local web`.
8. Story 5: the template, the sample, the overlays and release plumbing, the documentation.

## Complexity Tracking

No constitution violations to justify. Three additions that could look like scope and are not:

| Addition | Why it is in this feature |
|---|---|
| A new program, the proxy, where the spec's context compares it to the sidecar (R1) | the sidecar cannot start without forming a cluster and opening a database, and making it able to would be a second start path through `runtime` for a program that uses nothing `runtime` is for. A program that only passes requests on, depending on the JDK, is smaller than the change it replaces, and is the only shape the CLI can also run |
| A second certificate for requests under a mount (R3) | the clarification requires that an older runtime refuse such a request and never serve it as the web-hosted service's. A header cannot do that, because an older runtime ignores it. A certificate identity an older runtime already rejects can, and costs one more cert-manager object per service with mounts |
| `ankka services logs --platform`, which also changes process hosting | the route names no container today, so it fails for any pod with two. A web-hosted pod has two. The fix is the same line for both modes, and leaving process hosting broken beside it would need a rule to keep it broken |

Two things the plan leaves out, on purpose, that a reader might expect:

- **A native image of the proxy.** The JVM image is the one every platform image is; R19 measures
  whether its footprint is acceptable, and R1's JDK-only engine keeps a native image possible.
- **Publishing the sample's image.** A new public package is a manual step on every new name, and
  the sample is for a local installation (R15).
