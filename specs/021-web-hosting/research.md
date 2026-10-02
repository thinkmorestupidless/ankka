# Research: Web Hosting

Each item is a decision the plan rests on, what it was decided against, and whether it is
**verified** (read in the code on this branch, with the place), **measured**, or **a task** (a claim
the implementation must prove first, and what happens if it turns out false).

## R1. What the proxy is: a JDK-only engine, in a module of its own

**Decision**: the proxy is new code, not a mode of the sidecar. Its engine (the listeners, admission,
mounts, the calling address, the headers, the answers of its own) lives in a new module,
`proxy-core`, that depends on nothing of ankka's and nothing of Pekko's: the JDK's
`com.sun.net.httpserver` for the listeners and `java.net.http` for what it sends on. A second new
project, `proxy`, is the image: `proxy-core` plus `runtime` and `http`, from which it takes
`RotatingTls` and `Caller.fromCertificate` and nothing else. The CLI depends on `proxy-core` for the
local command (R13). One engine, two exposures, chosen by where it runs, as observability is.

**Rationale**:

- *The sidecar cannot start without a cluster and a database* (verified).
  `ServiceBuilder.host` (`modules/runtime/.../Ankka.scala:138-241`) initialises sharding, forms the
  cluster, constructs the r2dbc connection factory eagerly (`:211`), and blocks in `awaitReady` until
  the node is `Up` (`:219`), before any extension starts; `HttpServer.start` runs only from there. The
  Kubernetes overlay's substitutions are `${X}`, so the process does not even load its configuration
  without `POD_IP` and the three cluster variables. A third sidecar mode would mean a second start
  path through `runtime`, for a program that uses none of what `runtime` is for.
- *The same reasoning as the operator*: a program whose whole job is to pass requests on should
  depend on as little as possible. It has no entities, no journal and no sharding.
- *The local command must be the same code.* The CLI is a native image with no actor system
  (`cli` depends on `controlplane-api` only, `build.sbt:517`); it already serves HTTP with the JDK's
  server, in the native image (`cli/.../console/ConsoleServer.scala:3`, proven by
  `cli/native-smoke.sh:31-45`), and `java.net.http` is its only client. An engine on those two is
  usable by both, so a mount cannot mean one thing on a laptop and another in a cluster.

**Alternatives considered**:

- *Envoy as the proxy*, configured by the operator. It does every in-cluster job well (file-watched
  certificates, RBAC on the URI SAN, streaming, even upgraded connections). Rejected because the
  local command would be a second implementation of every rule, and because the calling address
  resolves any service name at request time through an SRV record and a per-callee identity check
  (R10), which is custom code today and would be a second, different mechanism in Envoy.
- *pekko-http in a plain actor system.* Proven with `RotatingTls` (`HttpServer.scala:121`), streams
  well. Rejected for the same reason: the CLI cannot carry it.
- *A Node proxy*, sharing the console's `create-console-server.ts`. Rejected: the platform's
  transport rules live in Scala, and the console's hand-written copy of them is the thing this
  feature exists to stop people writing.

**Cost, stated**: the JDK's server speaks HTTP/1.1 and cannot upgrade a connection, so WebSockets
are out for as long as the engine is the JDK's. The spec already says they are not promised; this
is why. The gateway routes HTTP/1.1 only, so nothing else is lost.

## R2. The JDK's HTTPS server with rotating certificates and client certificates — a task

**Decision**: the `proxy` project wraps `RotatingTls` in an `SSLContext` whose engine factory
delegates to `RotatingTls.serverEngine()` (which already sets `needClientAuth` and TLS 1.3,
`RotatingTls.scala:87-92`), and gives that to `HttpsServer`. The caller is read from
`HttpsExchange.getSSLSession.getPeerCertificates`. Handlers run on virtual threads
(`server.setExecutor`).

**Status**: **verified 2026-10-02** by `ProxyTlsSpike` (`sbt -Dankka.spikes=on 'proxy/testOnly
*ProxyTlsSpike'`, five of five), on JDK 21 with TLS 1.3. `HttpsServer` keeps the one `SSLContext` it
is given, so `RotatingServerTls` gives it a context whose every engine is `RotatingTls.serverEngine()`,
made when the connection arrives; and an `HttpsConfigurator` whose `configure` restates the client
certificate requirement, since the default replaces the engine's parameters with the context's
defaults, which do not require one. The spike showed, before anything else was built on it:

1. a rotated `tls.crt` is served to the next connection without a restart;
2. a connection with no client certificate, or one from another authority, fails the handshake;
3. the peer's certificate is readable in the handler;
4. a 1 MB request body and a three-part response, one part a second, both pass through without
   being held (the first part is read by the client before the last is written);
5. `-Djdk.httpclient.allowRestrictedHeaders=host` lets the client send the `Host` the proxy chooses.
   The JDK reads that property once, when its client classes load, so it is given as a JVM option:
   in the forked tests' `javaOptions`, in the proxy image's, and for the native CLI both as a build
   argument and as the first statement of `main`. A static initialiser in the engine would be too
   late in any JVM that had already built a client.

**If false**: the `proxy` project takes its listeners from pekko-http instead (the proven path),
`proxy-core` keeps the rules as pure functions over a small request/response interface, and the CLI
keeps the JDK listener for plain HTTP only. The rules stay shared; the I/O shell is then two. This
fallback changes no contract in `contracts/`.

## R3. A request under a mount arrives under a second certificate

**Decision**: the operator asks cert-manager for a second certificate for a web-hosted service that
has mounts: `<service>-mount`, issued by the `ankka-service` authority, client authentication only,
with the single URI `ankka://<project>/<service>/mount`. The proxy presents the service certificate
for the process's own calls and the mount certificate for requests under a mount. The runtime's
`Caller.fromCertificate` learns one rule: a URI of that shape, of the callee's own project, is
`Caller.Gateway`; of any other project, it is refused.

**Rationale**:

- *No service author sees anything new* (FR-041): the value is the existing `Caller.Gateway`,
  admitted by the existing `Callers.internet`.
- *An older runtime refuses, with no change to it* (FR-042, **verified**).
  `RotatingTls.parseServiceUri` (`RotatingTls.scala:297-305`) accepts a URI only when its path has no
  `/` in it, so `ankka://shop/web/mount` parses to nothing, `Caller.fromCertificate`
  (`Caller.scala:41-47`) answers `Left("unrecognised caller certificate")`, and `Router.handle`
  answers 403 before any access rule runs (`HttpServer.scala:292-293`). That is the code every
  released runtime with mutual TLS runs.
- *The identity is honoured only inside its project.* A runtime reads a mount URI as the internet
  only when the URI's project is its own; from any other project it is refused. A mount can only
  name a service of its own project, so nothing legitimate is lost, and a mount key that escaped
  would be worth nothing outside the project it was issued in.
- *The key must not reach the process.* The internet is admitted where a service of another
  project is not, so this identity is not simply a lower one. A descriptor's `secretKeyRef` can
  name any Secret in the namespace today (`descriptors.scala:290-306`, `Rendering.scala:936-943`,
  **verified**), which would let a descriptor hand its own process `<service>-mount-tls`. The same
  hole exists now for a sibling's `-service-tls` and `-database-tls`. So the descriptor's rules
  refuse, for every hosting, a variable taken from a Secret the platform issues (FR-045): any name
  ending `-service-tls`, `-mount-tls`, `-cluster-tls` or `-database-tls`, and any name that is, or
  starts with, the project's database cluster's (`ankka-db`, `CnpgRendering.scala:26`), which covers
  its server authority with its key (`ankka-db-ca`), its client authority (`ankka-db-client-ca`,
  `:83`) and its replication certificate (`:84`). Not covered: `<service>-db`, the credential
  Secret, because a descriptor that supplies its own database may well keep its credential in a
  Secret named that way, and the platform's own authenticates nothing once the service connects by
  certificate. That is stated as a limitation. Worth making on `main`
  as a change of its own, ahead of this feature.

**Alternatives considered**:

- *A header on the request*, with the service certificate. An older runtime ignores the header and
  reads the caller as the web-hosted service: exactly the trap the clarification closed. Rejected.
- *`ankka://shop/web?mount` or `#mount`.* `java.net.URI` drops the query and fragment from `getPath`,
  so today's parser reads both as `Service(shop, web)`. Rejected, and pinned by a test so nobody
  reaches for it later.
- *The gateway's own URI.* Only the gateway may hold `ankka://gateway`; a service that can present it
  is one the network policies can no longer tell from the gateway.

**Pinned by tests** (none of these shapes is tested today; `TlsServerSuite` covers a service URI,
the gateway, a DNS-only certificate and a foreign authority): the mount URI of the callee's own
project reads as `Gateway`; an
extra segment that is not `mount` is refused; the query and fragment forms read as the service; a
certificate carrying `ankka://gateway` among several URIs reads as `Gateway`; a mount URI of another project is refused; and a
frozen copy of today's parser, kept as a test fixture, refuses the mount URI. That last one is the
stand-in for "a runtime from before this feature": the honest proof is the previous release's
image as a mounted service, which is `quickstart.md`'s manual step, not a suite's, because a suite
may not name an image by a literal tag.

## R4. Admission is the proxy's, from three kinds of entry

**Decision**: the descriptor's `callers` is a list of strings: `"<service>"` (a service of this
project), `"<project>/<service>"`, and `"*"` (every service of this project). They mirror
`Callers.service(name)`, `Callers.service(project, name)` and `Callers.anyInProject`. The internet is
always admitted and is not written, and so is the web-hosted service itself, as `Callers.self`
admits a service's own instances. The proxy decides from the caller it read from the certificate,
before the mount table or the process is consulted; a refusal is 403 with the proxy's own marker
(R18).

**Rationale**: strings are the same three shapes the operator hands the proxy in its environment
and the CLI prints; an object per entry would be three shapes of object for three shapes of string.

## R5. The address a request was sent to is derived, never read

**Decision**: for a request from the gateway the proxy tells the process
`X-Forwarded-Proto: https`, `X-Forwarded-Host: <service>-<project>.<base>[:port]` and
`X-Forwarded-Port`, computed from what the operator gave it at start, and sets `Host` to the same
authority. Nothing of these is taken from the request. For a request from a service it states the
in-cluster address. For a request under another web-hosted service's mount (a mount identity of
its own project), it passes on the `X-Forwarded-*` that proxy wrote, since only the platform's
proxy holds that certificate (R3, FR-045). `Caller.fromCertificate` answers `Gateway` for both the
gateway and a mount, so the proxy asks `RotatingTls.parseMountUri` as well, and its own type for
who sent a request says which: `Sender.Internet(stated)` carries the address a mounting proxy
stated, and is empty for the gateway. When the operator has no base domain and nothing was stated,
the forwarded headers are left out and `Host` is passed as it arrived.

**Rationale**: the operator already derives the hostname (`Hostnames.of`) and knows the base domain
(`operator/.../Settings.scala:32`, **verified**). It does not know the HTTPS port (**verified**: no
such setting), and Envoy forwards the scheme but not the port, which is the trap the identity
provider's route already works around. So the operator gains `ANKKA_HTTPS_PORT`, from the same
`ankka-platform` value the control plane reads, and the proxy's public authority is rendered
whether or not the service is exposed, so exposing never changes the pod template and never rolls
a pod.

## R6. No database

**Decision**: the control plane projects `provisionDatabase = false` for web hosting. The operator's
plan gains a case, `NotNeeded`, chosen for web hosting before any CNPG object is read; it renders
nothing and reports no `status.database` at all. The control plane's `databasePhrase` answers
`none` for a web-hosted service, from its hosting, and `services get` prints the line.

**Rationale**: `Supplied` is the nearest existing plan and is wrong: it means the descriptor brought
a database, hands `ANKKA_DB_*` to a container and reports the phase `Supplied`
(`Provisioning.scala:77`, `LifecycleRules.scala:127-147`, **verified**). Reusing it would print
`supplied` for a service that has none. The CRD's phase enum is unchanged, because nothing is
reported.

## R7. The pod, and what an existing service keeps

**Decision** (the whole shape is `contracts/descriptor-and-rendering.md`):

- two containers, named as process hosting names them: `<service>` is the platform's (the proxy)
  and `<service>-app` is the developer's;
- the instance type sizes `<service>-app` (FR-011); the proxy has a fixed allotment of its own (R19).
  Process hosting does the opposite today (`AppQuantities`, `Rendering.scala:138-139`), which is
  right there, where the sidecar does the work;
- certificates: the service certificate always, the mount certificate when there are mounts, never
  the cluster certificate;
- network policies: `<service>-http` as for every service, and a new `<service>-probe` admitting
  7627 from anywhere, since that rule lives in the cluster policy today (`ZeroTrust.scala:169`) and a
  web-hosted service has no cluster policy;
- the `ServiceAccount` is rendered (the pod names it) with the token not mounted; no `Role`, no
  `RoleBinding`;
- the pod template keeps `transport=tls` and drops `formation=bootstrap`. `Transition.needed` reads
  the transport label (`Transition.scala:33-34`): without it the operator would stop the Deployment
  on every pass. The formation label marks a pod as a cluster contact point, which this is not.

**A service that changes hosting** to or from `web` is allowed. The operator has no action that
removes a certificate, a `Role` or a `RoleBinding` (`Action.scala`, **verified**), so a service that
was embedded keeps its cluster certificate, its cluster policy and its peers role until it is
deleted; all are owned by the resource. The kept cluster policy admits ports 17355 and 7626 from
the service's own pods, so `processPort` may be neither (R9), and then nothing kept admits anything
the web-hosted pod listens on. The same holds for a mount certificate after a descriptor's last
mount is removed: it is renewed until the service is deleted and nothing presents it. Its database
is kept, as every database is. Refusing the change was considered and is not worth a rule.

**An operator that predates this feature** renders an unknown hosting as embedded
(`Rendering.scala:710`, **verified**): the fall-through is `else`. This feature makes that a render
problem, `unknown hosting "<value>"`, so the next mode added cannot be silently rendered as the
wrong thing; and `CrdSchemaSuite`, which compares only property names today, gains a check that the
CRD's `hosting` enum is exactly the operator's constants.

**The order of an upgrade** follows from that: the operator is upgraded before, or with, the
control plane and the CRD, as a release does. A control plane that accepted a web descriptor in
front of an older operator would get an embedded pod with a database. `docs/deploy/upgrading.md`
says so.

## R8. Ready, and what `Failed` says

**Decision**: the proxy serves `GET /ready` on 7627, plain, and answers 200 when a TCP connection to
the process's port on loopback succeeds (checked per probe, with a 500 ms connect timeout) and 503
otherwise. The pod is ready when the proxy's probe passes; the developer's container has no probe.
When the rollout's deadline passes, the operator's detail for a web-hosted service whose newest
`Unhealthy` event reports a 503 from the probe is
`the process is not listening on port <n>`, followed by the kubelet's own message.

**Status**: the wording of the kubelet's event is **a task** to read on k3s
(`HTTP probe failed with statuscode: 503` is what Kubernetes' prober writes; the response body is
not in it). If it differs, the rule matches what k3s writes, and the test that pins it is the k3s
case, not a unit test of a string nobody observed.

**Rationale**: a 503 from the probe can only mean the proxy is up and the process is not listening;
if the proxy itself is down the kubelet reports a refused connection instead, and the existing
detail stands. FR-009 says the platform calls none of the process's routes, and a TCP connection
calls none.

## R9. The process's port

**Decision**: `processPort` on the descriptor, an integer, optional. The default is 8080. The
platform sets `PORT` to it for the process. It is refused when it is outside 1–65535, equals the
service's `port`, or is a port the platform uses in a pod: 7626, 7627, 7628, 7630 or 17355. For a
web-hosted service `port` itself may not be 7627 or 7630, which the proxy listens on for other
things. On a developer's machine the default is not used when the local command runs the process
(R13): 8080 is the port the local installation's gateway is published on. A descriptor whose `port` is 8080 and which states no `processPort` is therefore refused,
naming both, rather than given a second default nobody could guess.

**Rationale**: `port` keeps the meaning it has in every mode: the service's port in the cluster,
which the proxy listens on, as the sidecar does for a process.

## R10. The calling address

**Decision**: the proxy listens on `127.0.0.1:7630` and the process is given
`ANKKA_SERVICES_URL=http://127.0.0.1:7630`. A call is `<url>/<service>/<path>` for a service of the
same project and `<url>/<service>.<project>/<path>` for another project's. Names are DNS labels, so
a dot cannot occur in one and the two forms cannot be confused; the order is the in-cluster
address's own.

Resolution, verification and the certificate are `HttpServiceClients`' (**verified**,
`modules/runtime/.../HttpServiceClients.scala`): the host `<name>.<prefix>-<project>.svc.cluster.local`,
the port from the `_http._tcp` SRV record, a client context from
`RotatingTls.contextRequiring("ankka://<project>/<name>")`. Its lookup is already a public
function, `HttpServiceClients.kubernetes`, and the proxy calls it unchanged, so in a cluster the
two cannot disagree about where a service is. On a developer's machine there are already two
readers of the running directory, the runtime's `ServiceRegistration` and the CLI's `LocalSource`;
the local command uses the CLI's. The sending is the proxy's own, because
`HttpServiceClients.send` buffers both bodies whole (`:138-161`) and a mount must not.

**Not sent, answered**: a name that resolves to nothing is 503 naming the service; an empty first
segment is 400; a callee whose certificate is not the service asked for is 502; a service that
does not begin to answer within the proxy's bound is 504, as for the process. No retries and no
redirects, as for the service client.

## R11. Logs for a pod with two containers

**Decision**: `PodLogs.read` names a container. The default is the developer's: `<service>-app`
when the service's hosting is `process` or `web`, `<service>` otherwise. `--platform` on
`ankka services logs` (and `platform=true` on the route, the MCP tool's argument, and the console's
logs page) reads `<service>` instead: the sidecar or the proxy.

**Rationale**: the failure today is the API server's, for any pod with more than one container
(`PodLogs.scala:41-74` names none, **verified**). The same change makes the command work for a
process-hosted service, which removes a documented limitation; the docs say so.

## R12. Mounts in the status, decided when it is read

**Decision**: `ServiceStatus` gains `mounts` (each `path`, `service` and a `state`: `ok`,
`no service`, `serves no HTTP` or `paused`), `callers` and `processPort`. For `GET` of one service
the endpoint fills each mount's state by asking the target's entity for its desired state, as
`OrganizationEndpoint.existing` already does per service. The listing's rows carry the mounts with
no state.

**Rationale**: a mount's target is another entity, and cross-entity checks live in the endpoint.
The view's row has no `http` field (**verified**), and a row can lag; the entity cannot.

## R13. The local command

**Decision**: `ankka local web [-f service.json] [--port 3000] [--service name=url]... [-- command]`.
It reads the descriptor, starts `proxy-core`'s engine with no TLS and every caller the local
machine, listens on loopback at `--port`, serves the calling address on a free loopback port, and
passes everything outside a mount to the process. With a command after `--` it chooses a free port
for the process and runs the command with `PORT` set to it, `ANKKA_SERVICES_URL` and the
descriptor's literal variables, and ends when it ends. Without a command the process is the
developer's to start, at the descriptor's `processPort`, and a `--port` equal to it is refused; a variable from a secret is named and left unset. A service is found as the
local console finds one (`LocalSource`, `~/.ankka/running`), or at `--service name=url`.

**Rationale**: the CLI already has both halves: a loopback server and a reader of running services.
`--service` is `ankka.local-services.<name>` for a program that has no configuration file.

**Not done**: the web-hosted service is not announced in `~/.ankka/running`, so a Scala service on
the same machine calling it by name needs `ankka.local-services`. The registry's entries are
observability addresses, which a plain HTTP program has not got.

## R14. The template

**Decision**: `ankka init --language web <name>`, a new case of `Language`. It renders a Vite and
React single-page app in TypeScript, and a server of about eighty lines on `node:http`, run by Node
directly as the console's host is. The server serves the built files (a path that is not a file and
has no extension answers the index; a missing file with an extension answers 404), answers one
route of its own by calling a service through `ANKKA_SERVICES_URL`, and in development hands
everything else to Vite as middleware, so one program listens on `PORT` in both. The descriptor
mounts a service named `backend` at `/api`. Node 24.

- `templates/common` is split: `.mcp.json` stays for every template; `docker-compose.yml`, which
  starts the sidecar, moves to `templates/common-service`, merged for Python, TypeScript and Rust
  only.
- `PolyglotTemplateSuite` asserts the descriptor is process- or module-hosted with the platform's
  protocol version (`:119-131`); the web template gets its own suite.

**FR-033 as measured**: the project's own tests cover the server's call against a stand-in calling
address and the app's fetch against a stand-in backend. A mount is the proxy's behaviour, not the
project's, so it is the platform's `WebTemplateSuite` that renders the project, builds it, starts a
stand-in backend and the real `ankka local web`, and requests both paths through it.

**Alternative considered**: a separate flag (`--kind web`), since web is not a language. Rejected:
one flag chooses the template today, and the help text can say what `web` is.

## R15. The sample

**Decision**: `samples/shopping-cart-web/`, the template's shape, mounting `cart` at `/api/cart`
and calling it once from the server. Built with `docker build`, as the console's image is. Loaded by
`deploy-local.sh`. **Not** pushed to ghcr.io by a release: every new public package is a manual step
and the sample is for a local installation.

**Verified**: the cart's endpoint is `Acl.AllowAll` (`ShoppingCartEndpoint.scala:24`), so it admits
the internet (the mount) and the web-hosted service (the call) as it stands. It also admits every
other service, so the sample cannot show a backend refusing everyone but its interface; SC-004 is
stated as what it can show, and the refusal is `calling-services.feature`'s scenario. The Scala sample has
no `service.json`; the web sample ships one, and so does the walkthrough.

## R16. Which test runs which feature

| Feature file | Run by | How |
|---|---|---|
| `requests.feature`, `calling-services.feature`, `mounts.feature` | `ProxyFeatures` in `proxy` | `GherkinSuite`, each file whole: the real proxy with TLS on loopback, certificates from `test-pki`, a stand-in process, and callees that are the real `HttpServer` |
| `descriptor.feature` | `DescriptorFeatures` in `controlplane` | `GherkinSuite`: the real apply route |
| `deploying.feature`, `isolation.feature` | `WebHostingClusterFeatures` in `controlplane` | `GherkinSuite` over k3s with the control plane and the operator, as `EndToEndClusterSuite` runs both halves; `isolation.feature` joins once the calling address exists |
| `unchanged.feature` | `RenderingUnchangedSuite` in `operator` | tests named after its rows: what is rendered for each existing hosting, compared with what was rendered before |
| `local.feature` | `LocalWebSuite` in `cli` | tests named after the scenarios, through `Main.run` |
| `template.feature` | `WebTemplateSuite` in `cli` for the first two scenarios, and two cases in `SampleDeploymentClusterSuite` for the last two | named tests |
| `documentation/web-hosting.feature` | `WebHostingDocumentationSuite` in `controlplane-api`, beside `docs check` and `DocumentationDescriptorsSuite`; the guide's steps are `WebTemplateSuite`'s | named tests |

Three scenarios were moved out of `requests.feature` and `calling-services.feature` into
`isolation.feature` during planning: they are about what the network refuses, which only a cluster
can show, and a feature file is run whole by one suite. For the loopback suite the reverse care
applies: no step there says a service "has no hostname", which nothing on loopback could make
false; the sample's scenario on a cluster says it.

**The k3s suites do not run in CI** (`ci.yml:1-4`); what CI holds this feature to is the loopback
suite, the rendering suites, the descriptor and the template. That is the repository's existing
position, not a choice made here.

**The stand-in process** for the cluster suite is an image built by the suite, as
`SidecarClusterSuite` builds the Python cart: a forty-line Node server that echoes what it was
given, streams, stalls and calls through `ANKKA_SERVICES_URL`.

## R17. This branch needs feature 019 on `main` first

**Verified** against the `019-service-topology` branch: `GherkinSuite` running one named `.feature`
file, the `features` job, `.github/features-check.sh`, and the `features/**` and `GLOSSARY.md`
entries in `ci.yml`'s filters are all added there and are not on `main`. This branch commits
`features/` and `GLOSSARY.md`, which `ci-coverage.py` refuses until a filter claims them, and runs
feature files one at a time.

**Decision**: 019 merges first and this branch is rebased onto it; the glossary, which this branch
seeded from 019's copy, then merges with one small hunk. If 019 is delayed, the first task
cherry-picks those four pieces from it, unchanged, so the later merge is a no-op for them.

## R18. What the proxy answers by itself

**Decision**: every answer the proxy gives without the process or a service having answered
carries `X-Ankka-Answered-By: proxy` and a one-line JSON body, `{"error": "<reason>"}`:

| Status | When |
|---|---|
| 403 | the caller is not admitted |
| 400 | a call at the calling address names no service |
| 502 | the process, or a service, closed the connection before answering; or a callee's certificate is not the service asked for |
| 503 | the process is not listening; a mount's or a call's service cannot be found |
| 504 | the process, or a service, did not begin a response within 60 seconds |

The timeout never cuts a response that has begun, and it bounds a mounted or called service
as it bounds the process. It is one setting of the proxy's, not the descriptor's. On `SIGTERM` the
proxy stops accepting, lets requests in flight finish for up to ten seconds, closes what remains,
and exits; the `preStop` sleep every workload has runs before that.

## R19. The proxy's footprint — measured by a task

**Decision**: the image is a JVM image like the others (`eclipse-temurin:21-jre`), started with a
small fixed heap. The pod's proxy container asks for 100m and 192Mi, requests equal to limits.

**Status**: **a task.** The numbers are an estimate. A benchmark in `proxy`'s tests measures resident
memory after 10,000 requests and the median added latency against a real process on loopback
(SC-007's denominator is a request to the process directly, not an empty loop). If 192Mi is not
enough the allotment changes; if the JVM's footprint is judged too high for a pod per interface, a
native image of `proxy` is the next step, and R1's JDK-only engine is what keeps that open.

## R20. Persisted descriptors replay

**Verified**: the descriptor is inside `ServiceApplied` and the entity's state, and every field
added since feature 008 was added with a default. `mounts` and `callers` default to empty and
`processPort` to `None`, so an old event decodes, and a descriptor that uses none of them is
written without them. `processPort` is an `Option` whose default is `None`, which is the one shape
of `Option` the null trap does not bite.

## R21. The build, CI and the release

Recorded in `contracts/ci-and-release.md`. The facts it rests on, all **verified**:

- an sbt project with an image is the `sidecar` shape (`build.sbt:463-512`); root's
  `docker:publishLocal` picks a new one up by aggregation;
- `-D` switches reach forked tests only through the list at `build.sbt:135-147`;
- the template resource generator's languages are a literal list (`build.sbt:580`);
- `ClusterImages.importInto` chooses its build hint by image prefix and needs a branch per image;
- the cloud overlay tells the operator its sidecar image with a strategic-merge patch that must
  name the container `ankka-operator` exactly, or it adds a container; `RemoteOverlaySuite` asserts
  the variable appears once and the default is gone. The proxy's image is told the same way and
  asserted the same way;
- a package ghcr.io has not seen is created private; `ankka-proxy` needs making public once.
