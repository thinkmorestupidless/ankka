# Research: gRPC Endpoints

Decisions for [plan.md](plan.md), each with the evidence it rests on. File references are to this
repository at the branch's base; external references were read at the versions named and **not
run** unless a line says so. Three decisions rest on behaviour nobody has observed here yet, and
each names the spike that proves it and the fallback if the spike fails (R4, R8, R12).

## R1 — grpc-java on a port of its own, not HTTP/2 on the HTTP port

**Decision**: a new module, `modules/grpc` (`ankka-grpc`), serves gRPC with grpc-java on its own
port. The HTTP server is untouched.

**Rationale**: grpc-java and ScalaPB are already in the build for the sidecar protocol
(`project/Dependencies.scala:131-142`, `build.sbt:434-455`). grpc-java hands a server interceptor
the TLS session, so the caller is read from the client certificate exactly as `Caller.fromCertificate`
already does (`modules/http/.../Caller.scala:41-47`); it takes an executor, so handlers run on
`AnkkaExecutors.virtual`; and it has the two things long-lived connections need, a maximum
connection age and a graceful shutdown.

**Alternatives considered**:
- *HTTP/2 on the existing pekko-http listener.* One port, so almost no platform change. Declined:
  it needs pekko-grpc (declined once already for the family pin, `Dependencies.scala:132-135`) or a
  hand-written gRPC framing; the caller would have to be read from `Tls-Session-Info` on an HTTP/2
  connection, a path nothing here exercises; and every refusal in `Router` would need a second
  rendering.
- *pekko-grpc on a second pekko-http listener.* Keeps one HTTP stack and gains nothing over
  grpc-java, while adding a code generator and a `Future`-and-`Source` trait that is not ankka's
  handler shape.

## R2 — grpc-java 1.84.0, pinned by the build; ScalaPB compiler plugin 0.11.20

**Decision**: `Dependencies.V.grpc = "1.84.0"` names grpc-java directly, for `protocol`, `sidecar`
and `ankka-grpc` alike. The ScalaPB compiler plugin moves from 0.11.11 to 0.11.20. One version of
each in the build.

**Rationale**: the build resolves grpc-java 1.46.0 today (the version ScalaPB 0.11.11 declares), a
2022 release that predates the HTTP/2 rapid-reset fixes. That is tolerable on a pod's loopback and
not for a published module that listens on the network. `ProtoReflectionServiceV1` exists from
1.66.0, so reflection (R15) needs a newer line regardless. ScalaPB 0.11.20 declares 1.62.2;
grpc-java 1.84.0 is still on protobuf-java 3.25.x, the line ScalaPB 0.11 builds against, and the
generated code uses only descriptor builders, `ServerCalls`/`ClientCalls` and the proto descriptor
suppliers.

**Not confirmed**: no statement that ScalaPB 0.11.x code runs against grpc-java 1.84 was found.
**Proof**: the existing sidecar suites and the three SDK conformance runs, green on the bumped
versions, before anything else is built (task 1 of the plan). **Fallback**: pin grpc-java at the
newest line those suites pass on, no lower than 1.66.0.

**Alternatives considered**: *ScalaPB 1.0* — not released (`1.0.0-alpha.6`, protobuf 4.x, a
breaking change to proto3 extensions). *Leaving the sidecar on 1.46.0 and using 1.84.0 only in the
new module* — two grpc-java versions in one build, and the sample's generated code would be built
by one and run on the other.

## R3 — `ankka-grpc` depends on grpc-java, not on ScalaPB

**Decision**: the module's API is written against `io.grpc.ServiceDescriptor` and
`io.grpc.MethodDescriptor[Req, Res]`. Its dependencies are `grpc-netty-shaded`, `grpc-stub`,
`grpc-protobuf` and `grpc-services`. ScalaPB is the developer's build's, and this repository's only
in a fixtures project and the sample.

**Rationale**: ScalaPB generates `FooGrpc.SERVICE` and `FooGrpc.METHOD_X` as exactly those grpc-java
types, so a handler declared against them needs no generator of ankka's own and no ScalaPB runtime
in the published POM. The "generated code and the runtime can never disagree" problem
(`Dependencies.scala:133-134`) then has one moving part instead of two: the developer's
`scalapb-runtime-grpc` brings `grpc-stub`/`grpc-protobuf` at its version, `ankka-grpc` brings them
at 1.84.0, and eviction picks the higher.

**Generated code and the compiler's flags**: `commonSettings` compiles with `-Wunused:all` and
`-source:3.7` (`build.sbt:111-121`), and the `protocol` project replaces both for generated code
(`build.sbt:440-444`). A service's protos therefore go in a subproject of their own. The sample
does that (`samples/shopping-cart-api`), the fixtures do (`modules/grpc-fixtures`), and the
documentation page tells a developer to.

## R4 — mutual TLS through grpc-java's credentials API, with rotating managers

**Decision**: `RotatingTls` gains two delegating views, `keyManager: X509ExtendedKeyManager` and
`trustManager: X509ExtendedTrustManager` (and `trustManagerRequiring(uri)`), each of which asks the
currently loaded material on every call. The server is
`Grpc.newServerBuilderForPort(port, TlsServerCredentials.newBuilder().keyManager(km).trustManager(tm).clientAuth(REQUIRE).build())`;
the client is `TlsChannelCredentials` with the same key manager and the requiring trust manager.

**Rationale**: `RotatingTls` exposes only `SSLContext` and `SSLEngine` today; its managers are in a
private `Loaded` (`RotatingTls.scala:240-247`). grpc-netty-shaded takes neither. The credentials
API is what grpc-java's `SECURITY.md` shows, it is not marked experimental, and it needs no shaded
Netty class name in ankka's code. A delegating manager is what makes rotation work: the server's
credentials are built once, and each handshake reads the certificate cert-manager wrote most
recently.

**Not confirmed**: grpc-netty-shaded picks BoringSSL (netty-tcnative) on its own, and how a custom
`X509ExtendedKeyManager` behaves under it was not tested. **Proof**: `GrpcTlsSpike` under
`-Dankka.spikes`, in-process with `testPki` certificates — handshake, client certificate required,
the peer certificate readable from `Grpc.TRANSPORT_ATTR_SSL_SESSION`, and a rotated certificate
served without a restart. **Fallback**: force the JDK provider
(`GrpcSslContexts.configure(builder, SslProvider.JDK)` on `NettyServerBuilder`), which costs the
shaded class names and nothing else.

## R5 — the handler surface: four declarations against generated descriptors

**Decision**: a `GrpcEndpoint(service: ServiceDescriptor)` declares handlers in its constructor
body, as `HttpEndpoint` declares routes:

| Declaration | Handler |
|---|---|
| `unary(METHOD)` | `Req => Res` |
| `serverStream(METHOD)` | `Req => Source[Res, ?]` |
| `clientStream(METHOD)` | `Requests[Req] => Res` |
| `bidiStream(METHOD)` | `Requests[Req] => Source[Res, ?]` |

Handlers block, on a virtual thread. `Requests[Req]` is a blocking iterator that asks the caller
for one more message each time it is advanced, with `asSource` for the streams API. Full signatures
are in [contracts/scala-api.md](contracts/scala-api.md).

**Rationale**: ScalaPB's own generated trait returns `Future`s and takes `StreamObserver`s, which is
not how any ankka handler is written. Declaring against the descriptors keeps the house shape
(blocking, explicit, checked at startup) with no code generator. The wire name is the proto's
method name, which is already the versioning boundary the project insists on being separate from
the Scala name (CLAUDE.md, *Registration and handler identity*). `Source` out matches
`HttpEndpoint.sse` (`HttpEndpoint.scala:304-323`).

**Startup checks** (FR-004), all reported at once as `HttpServer.validate` does
(`HttpServer.scala:197-218`): a method of the descriptor with no handler; a handler declared with
the wrong kind for its method (`unary` on a streaming method); a method declared twice; two
endpoints with one service name.

## R6 — flow control and cancellation use grpc-java's raw call API

**Decision**: handlers are bound with `ServerCallHandler`/`ServerCall.Listener`, not
`ServerCalls`' `StreamObserver` adapters. Outbound, a part is taken from the handler's `Source`
only while `call.isReady`, resumed by `onReady`. Inbound, `call.request(1)` is issued when
`Requests.next()` is called and not before. `onCancel` fails the `Requests` iterator with
`CallCancelled` and cancels the outbound stream.

**Rationale**: FR-011, FR-012 and SC-008 require that neither side makes the service hold what the
other has not read. `ServerCallStreamObserver` can do this (`disableAutoRequest`, `setOnReadyHandler`)
but its parent is `@ExperimentalApi` and its rules about when each may be called are awkward from a
handler that blocks; the raw API has the same controls with no adapter.

## R7 — refusals are statuses; a malformed message is ankka's to answer

**Decision**: one mapping, both directions, in one place (`GrpcStatus`):

| `ErrorCode` | gRPC status |
|---|---|
| `BadRequest` | `INVALID_ARGUMENT` |
| `Unauthorized` | `UNAUTHENTICATED` |
| `Forbidden` | `PERMISSION_DENIED` |
| `NotFound` | `NOT_FOUND` |
| `Conflict` | `FAILED_PRECONDITION` |
| `Timeout` | `DEADLINE_EXCEEDED` |
| `Unavailable` | `UNAVAILABLE` |
| `Internal` | `INTERNAL` |

An ACL's answers: denied → `PERMISSION_DENIED`; `Unauthenticated(challenge)` → `UNAUTHENTICATED`
with a `www-authenticate: Bearer <challenge>` trailer; `Forbidden` → `PERMISSION_DENIED`;
`Unavailable` → `UNAVAILABLE`. Anything else a handler throws → `INTERNAL`, description
`internal error`, the failure logged (FR-008). A handler may throw a `StatusRuntimeException` of
its own choosing and it is sent as thrown.

Each method is bound with a **pass-through byte marshaller** for requests, and ankka parses with
the method's real marshaller inside its own handler, so a message that does not parse is answered
`INVALID_ARGUMENT` and no handler runs.

**Rationale**: `ErrorCode` has exactly those eight cases (`CommandError.scala:20-28`), and
`HttpProblem.from` is the HTTP half of the same idea (`HttpEndpoint.scala:25-35`). The byte
marshaller is needed because grpc-java answers a marshaller that throws with
`UNKNOWN: Application error processing RPC` (`ServerImpl.internalClose`), which tells a caller
nothing and contradicts the spec. Rebuilding the `ServiceDescriptor` with re-marshalled methods and
the original schema descriptor is what grpc-java's own `ServerInterceptors.useMarshalledMessages`
does, and `ServerServiceDefinition.build()` insists the bound methods are the descriptor's own
instances, so the descriptor is rebuilt, not patched.

## R8 — one HTTPRoute, two rules; no GRPCRoute

**Decision**: an exposed service that declares gRPC keeps its single `HTTPRoute`. It gains a rule,
ahead of the existing one:

```yaml
- matches:
    - headers:
        - name: content-type
          type: RegularExpression
          value: 'application/grpc(\+.+)?'
  timeouts:
    request: "0s"
  backendRefs:
    - name: <service>
      port: <grpcPort>
```

The existing rule (no match, the HTTP port) is rendered exactly as today, and only when the service
declares HTTP. The Service's `grpc` port carries `appProtocol: kubernetes.io/h2c`, and the
`BackendTLSPolicy` gains a second `targetRef` for the section `grpc`.

**Rationale**:
- *It needs no knowledge of a service's methods* (FR-046), and no new grant: the operator may
  manage `httproutes` and `backendtlspolicies` and nothing else
  (`kustomization/components/operator/operator.yaml:102-109`).
- *Route status comes for free* (FR-044): the operator already folds this route's conditions into
  `status.route`, and `services get` already prints them.
- *Precedence is right*: Envoy Gateway sorts rules by path match, then by number of header matches,
  so the header rule outranks the match-all rule (`internal/gatewayapi/sort.go` at v1.9.1).
- *Regex header matching is implemented* by Envoy Gateway (`route.go:1264-1271` at v1.9.1) though
  Gateway API calls it implementation-specific. Envoy's regex is a full match, so the pattern above
  admits `application/grpc` and `application/grpc+proto` and refuses `application/grpc-web`.
- *Upstream protocol*: for an HTTPRoute backend, `appProtocol: kubernetes.io/h2c` makes Envoy
  Gateway emit explicit HTTP/2 options, and TLS comes separately from the `BackendTLSPolicy`, so
  the upstream is HTTP/2 over TLS despite the name. The gateway's own client certificate
  (`EnvoyProxy.spec.backendTLS.clientCertificateRef`, `gateway/envoyproxy.yaml`) is in the
  cluster's TLS context whatever the HTTP protocol, so a call through the gateway reads as
  `Caller.Gateway` exactly as a request does.
- *The listener already speaks HTTP/2 to clients*: Envoy Gateway's HTTPS listeners offer `h2` and
  `http/1.1` by default, and nothing under `kustomization/` overrides it.
- *`timeouts.request: "0s"`* is required: without it Envoy's default 15-second route timeout cuts
  every stream, and with it Envoy Gateway also disables the route's stream idle timeout
  (`internal/xds/translator/route.go:458-492` at v1.9.1).

**Not confirmed**: all of the above is read from source and docs at v1.9.1; none of it was observed
on a cluster. **Proof**: `GatewayGrpcSpike`, a k3s case under `-Dankka.spikes` that deploys a
grpc-java server behind the installation's gateway and, from the host, makes a unary call, a
bidirectional call and a server stream that outlives 15 seconds at one hostname, beside an HTTP
request at the same hostname.

**Alternatives considered**:
- *A `GRPCRoute` beside the `HTTPRoute`.* Gateway API's released text says an implementation MUST
  accept exactly one of the two when their hostnames intersect (relaxed to MAY on `main`,
  unreleased). Envoy Gateway v1.9.1 merges both, but a match-less GRPCRoute rule has the same
  precedence as the HTTPRoute's, so it would need a `method.service` match per service definition —
  which the platform does not know. It also needs a new grant, a `BackendTrafficPolicy` for the
  timeout (GRPCRoute has no `timeouts`), and it turns gRPC-Web on for the whole listener.
  **This is the fallback** if the spike shows the header rule does not hold.
- *A path-prefix rule per service definition.* Core conformance, and it needs the descriptor to
  list a service's definitions. Declined by FR-046.
- *A second hostname.* Declined in clarification.

## R9 — an existing fault this finding exposes, left alone

Envoy's 15-second default applies to today's HTTP rule too, since the rendered route sets no
timeout (`Rendering.scala:363-396`). A server-sent-event stream through the gateway that runs past
15 seconds is therefore cut. Nothing in this feature changes that rule — FR-023 and SC-006 forbid
changing what is rendered for a service that declares no gRPC — so it is recorded here and raised
with the plan, not fixed by it.

## R10 — descriptor: a positive `grpc` and a `grpcPort`

**Decision**: `ServiceSpec` gains `grpc: Boolean = false` and
`grpcPort: Int = ServiceSpec.DefaultGrpcPort` (9090), with `resolvedGrpcPort: Option[Int]`. New
problems, beside the existing ones (`descriptors.scala:171-223`):

- `grpcPort` outside 1–65535 (checked whether or not `grpc` is set, as `port` is);
- `grpc` and `http` both set with `grpcPort == port`;
- an env var named `ANKKA_GRPC_PORT` — "declare the gRPC port instead";
- `grpc` on a `process` or `wasm` service — "only an embedded service serves gRPC".

`ExposureRules` stops refusing a service with `"http": false` when it declares gRPC
(`controlplane/.../ExposureRules.scala:22-40`).

A fifth problem (FR-052): `grpc` with a declared `runtime` older than `Compatibility.GrpcSince`,
the first version that serves gRPC. An older runtime ignores `ANKKA_GRPC_PORT`, binds nothing and
is never ready, and R16's check is not in it to say why. The constant is the version of the release
that first carries this feature and is set in that release's change; the rule and its test read
the constant, never a literal.

**Rationale**: the same shape as `http`/`port`, for the same reason — jsoniter reads a `null` on an
`Option` as absent and applies the default (`descriptors.scala:104-110`). 9090 is free of every
port the platform uses (9000, 9010, 9011, 7626, 7627, 17355) and of the 9001 the documentation
gives a second local instance. `ServiceSpec` is opaque to the console's client
(`console/package/test/fixtures.test.ts:15`), so no schema there changes.

## R11 — the resource and what the operator renders

**Decision**: `AnkkaServiceSpec` gains `grpcPort: Option[Int] = None`, declared in
`ankkaservice.yaml`. Everything below is rendered **only when it is set**, so a service without it
renders byte-identically to today (FR-023, SC-006):

- a container port named `grpc` and `ANKKA_GRPC_PORT`;
- a second port, `grpc`, on the service's `ClusterIP` Service, with the `appProtocol` of R8 — and
  the Service now exists when either port does;
- a headless Service `<service>-grpc` (R12);
- a network policy `<service>-grpc`, admitting the same peers as `httpPolicy`
  (`ZeroTrust.scala:194-237`): any workload of the installation, and the gateway's proxies;
- when exposed: the rule and the second `targetRef` of R8.

A new policy object, not a second port on `<service>-http`, so that no existing object changes.
Each new object is removed by the read-first removal the route already uses when `grpcPort` goes
away.

**Rationale**: `CrdSchemaSuite` compares the case class to the schema in both directions
(`operator/src/test/.../CrdSchemaSuite.scala:21`), which is the trap a new field falls into
(CLAUDE.md). No certificate changes: the service certificate is issued for every service and
already carries its DNS names (`ZeroTrust.scala:118-139`).

## R12 — a service's own calls are balanced per call, through a headless Service

**Decision**: the platform's gRPC client resolves `dns:///<service>-grpc.<namespace>.svc.cluster.local:<port>`
(the headless Service, so one address per ready pod) with the `round_robin` policy, and
`overrideAuthority` set to the service's ordinary name so the certificate is checked against the
name it carries. The port is read from the `_grpc._tcp` SRV record of the ordinary Service, as the
HTTP client reads `_http._tcp` (`HttpServiceClients.scala:180-195`). The server sets
`maxConnectionAge` to two minutes (grpc-java adds ±10% jitter) and `maxConnectionAgeGrace` to the
shutdown grace.

**Rationale**: a `ClusterIP` balances connections, not calls, and a channel holds one connection:
a caller would send everything to one pod and a new pod would see nothing. Lowering the connection
age alone makes "every instance has answered within five minutes" (SC-005) a matter of chance —
with three pods and a re-roll every two minutes it fails often. Round-robin over pod addresses is
deterministic, and the connection age is what makes grpc-java resolve again, so a new pod is in the
rotation within about two minutes. Kubernetes publishes an SRV record for every named port of a
`ClusterIP` Service (DNS specification §2.3.2).

Through the gateway nothing is needed: Envoy Gateway routes to endpoints, not to the `ClusterIP`.

**Replacement** (FR-033, FR-035): the existing `preStop` sleep takes the pod out of the headless
Service's records first; then `stop()` calls `shutdown()`, which sends GOAWAY and lets calls in
progress finish for five seconds (the HTTP server's own figure, `HttpServer.scala:147`), then
`shutdownNow()`, which ends what remains as `UNAVAILABLE`. A call that races the GOAWAY was never
started, and grpc-java retries those transparently.

**A caller that vanishes** (FR-051): the server sets `keepAliveTime` to 30 seconds and
`keepAliveTimeout` to 10, so a connection whose peer has gone without closing is found within 40
seconds and its calls are cancelled. That covers a caller connected directly. A caller outside the
cluster is connected to the gateway, and the gateway's connection to the service is alive whatever
happened to the caller; when Envoy gives up on a silent client is Envoy's to decide.

**Not confirmed**: that grpc-netty-shaded carries the `round_robin` provider without `grpc-util`
declared (add it if not), and how quickly grpc-java re-resolves after a connection ages out.
**Proof**: the replacement cases of `GrpcClusterSuite`. **Fallback**: declare `grpc-util`; lower the
connection age.

**Alternatives considered**: *`ClusterIP` only, short connection age* — probabilistic, above.
*Several connections per channel* — still connection-level. *Discovery through the Kubernetes API*
— a service's identity may read its own project's pods only (`Rendering.scala:282-292`).

## R13 — the calling side: a `Channel`, handed over like everything else

**Decision**: `GrpcClients` is a `RuntimeExtension` the developer creates and registers once, and
hands to whatever needs it. `grpcClients("cart")` and `grpcClients("shop", "cart")` return an
`io.grpc.Channel`; the developer builds the generated stub on it (`CartServiceGrpc.blockingStub(channel)`,
which suits a virtual thread). `GrpcServer`'s factory lambda receives the same `EndpointClients`
an HTTP endpoint does.

**Rationale**: no component context exposes `ServiceClients` today — only `EndpointClients.services`
and `AnkkaService.services` do (`sdk/contexts.scala:12-43`, `Ankka.scala:325-326`) — and a
component gets what it needs through its own constructor, because ankka has no container. An
extension gives the channels a lifecycle (built after the system has a configuration, shut down
with the service). Returning grpc-java's `Channel` means there is no ankka client API to learn,
generate or version.

**Resolution and its failures** (FR-030), thrown when the channel is first used:

| Situation | In a cluster | On a developer's machine | Error |
|---|---|---|---|
| no such service | no DNS record for the name | not configured, not announced | `ServiceUnresolvable` (existing) |
| it serves no gRPC | no `_grpc._tcp` SRV record | announced, with no gRPC address | `ServiceServesNoGrpc` (new, in `sdk`) |
| it is not that service | handshake fails on the required identity | — | `ServiceIdentityMismatch` (existing) |

Locally: `ankka.local-grpc-services."<name>"` if set, otherwise the address the running service
announced (R16), plaintext.

**A refusal crossing a call** (FR-032): a client interceptor on every platform channel attaches a
`CommandError`, mapped back by R7's table, as the *cause* of the `StatusRuntimeException` the stub
throws. `CommandError.from(throwable)` (new, in `core`) sees through one cause, and the HTTP
server's and the gRPC server's failure handling use it, so a handler that lets the exception pass
answers its own caller with the same refusal. `CommandError` is an exception and can be a cause
(`CommandError.scala:10-14`); `http` learns nothing about gRPC.

## R14 — the ACL is the HTTP endpoint's, unchanged

**Decision**: `GrpcEndpoint.acl` is an `Acl` from `ankka-http`, with `withAcl` scoping methods as it
scopes routes. An `Acl.Authenticate` or `Acl.AllowIf` is given a `SimpleRequestContext` with
`method = "POST"`, `path = "/<service definition>/<method>"`, an empty query, and the call's text
metadata as headers. `ankka-grpc` therefore depends on `ankka-http`.

Visibility widened from `private[http]` to `private[ankka]`, with no change of behaviour:
`RequestScope` (`RequestContext.scala:124-133`), `LocalCallers.callerFrom` (`Caller.scala:129-133`)
and `Tracing` (`HttpServer.scala:587-602`).

**Rationale**: one authenticator serves both kinds of endpoint (FR-019) only if both hand it the
same type, and `SimpleRequestContext` is already `private[ankka]`. Moving `Acl`, `Caller` and
`Principal` out of `http` into a lower module would be tidier and would rename public types of a
published library for no behaviour.

**The caller**: under TLS, from the peer certificate through `Caller.fromCertificate`; without TLS,
`Caller.Local`, or the caller a test names in the `x-ankka-local-caller` metadata key with the
process's token, as the HTTP server reads its header. A method no endpoint has is answered by a
fallback registry that applies the endpoint's ACL first when the service name is one it serves
(FR-018), then `UNIMPLEMENTED`.

## R15 — reflection is grpc-java's, behind an ACL

**Decision**: `GrpcServer.of(…).withReflection(acl)` binds `ProtoReflectionServiceV1` and the
deprecated v1alpha `ProtoReflectionService`, both from `grpc-services`, and the server's ACL
interceptor judges calls to either by `acl`. Without it neither is bound, and a reflection call is
`UNIMPLEMENTED` like any method nothing serves.

**Rationale**: `grpcurl` asks for v1 and falls back to v1alpha on `UNIMPLEMENTED`; binding both
serves older tools and saves the round trip. Reflection describes a service only if its descriptor's
schema descriptor is a `ProtoFileDescriptorSupplier`, and ScalaPB's generated `SERVICE` sets one
(`GrpcServicePrinter.scala:190` at v0.11.20) — R7's rebuilt descriptor keeps it. The reflection
method is a bidirectional stream, which is one reason all four kinds are served.

## R16 — what the runtime learns: one address, one check

**Decision**: two small additions to `runtime`, and it still never depends on `ankka-grpc`.

- `RuntimeExtension.grpcAddress: Option[String] = None`. `ObservabilityEndpoint`'s inventory adds
  `"grpc":{"address":…}` to an instance, and `ServiceRegistration.grpcAddressOf(name)` reads it as
  `httpAddressOf` reads `http` (`ServiceRegistration.scala:47-77`). `GrpcServer` does **not**
  override `boundAddress`: every bound address is listed as an HTTP one
  (`ObservabilityEndpoint.scala:95-102`).
- At the top of `ServiceBuilder.host`, before any actor system exists: if `ANKKA_GRPC_PORT` is set
  and no registered extension is named `grpc-server`, write the reason to `/dev/termination-log`
  and fail startup — "the descriptor declares gRPC and this service registers no gRPC endpoint".

**Rationale**: FR-026 wants the reason shown to a member. The operator already reports a crashing
container's termination message as the service's detail (`ClusterSnapshot.scala:64-86`), and
writing the file needs no change to the rendered pod — `FallbackToLogsOnError` on every container
would change every Deployment and roll every pod, which FR-023 forbids. Failing before the actor
system starts means the process really exits.

Readiness needs nothing new: `GrpcServer.readiness` says no until it has bound, and the probe
already asks every extension (`ExtensionsReadiness.scala:13-19`).

## R17 — traces: a root per call, refused told from failed

**Decision**: a call to a declared method records one span — component `grpc`, handler
`<service definition>/<method>` — opened on the handler's own thread, as `Tracing.request` does and
for the reason it gives (`HttpServer.scala:433-441`). Its outcome is `Ok`, `Refused` (an ACL said
no, or the handler ended in a `CommandError` or a refusal status) or `Failed`. A streaming call is
one span, completed when the stream ends. A call to a method nothing declares records nothing.

**Rationale**: `SpanOutcome` has `Refused` (`Recorder.scala:16-17`), and CLAUDE.md records what it
cost to paint a refusal as a failure. Names are interned for the life of the process, so only
declared methods may be recorded; a caller can invent method names without limit.

**The local console** (FR-037): gRPC methods are reported as `ServedRoute("GRPC", "<service
definition>/<method>", streaming)`, and `cli/src/main/resources/console/app.js` lists a `GRPC` row
without making it invocable (`app.js:201-247`).

## R18 — testing

**Decision**:

- **Offline, in `modules/grpc`** (which takes `testkit % Test`; `testkit` does not depend on it):
  everything under `features/grpc/` — serving, statuses, access, streaming, request-streams,
  reflection, testing, calling and traces — runs whole through `GherkinSuite`
  (`modules/testkit/.../GherkinSuite.scala:40`). Scenarios that say "a deployed service" run
  in-process with `testPki` certificates and a `locate` override, as `ServiceClientSuite` does for
  HTTP, so the caller really is read from a certificate.
- **In k3s, in `controlplane`**: `GrpcClusterSuite`, whose tests are named after the scenarios
  under `features/grpc-deployed/`, and repeats `calling.feature`'s against real pods. From the
  host it uses a grpc-java client against the mapped NodePort with the local CA, the gRPC
  counterpart of `curl --cacert --resolve` (`ExposureClusterSuite.scala:43`); inside the cluster it
  calls from a second deployed sample.
- **Rendering**: new cases in `RenderingSuite`, `ServiceRenderingSuite` and
  `ZeroTrustRenderingSuite`, including one that renders a service without `grpcPort` and compares
  it, object for object, to a golden copy taken before this feature (SC-006).
- **No fixed ports**: every suite binds `GrpcServer.at("127.0.0.1", 0)`.
- **Benchmarks** for SC-007 and SC-008 sit behind `-Dankka.benchmarks`, measured against the real
  call path (CLAUDE.md: a benchmark's denominator must be the thing the criterion names).

**The test kit's part** (FR-038) is `GrpcServer.boundPort` and `GrpcChannels.plaintext(port)` in
`ankka-grpc` itself, so `ankka-testkit` does not carry grpc-java to every service that tests.

## R19 — CI and the living features

**Decision**: add `features/**` and `GLOSSARY.md` to the `scala` filter of
`.github/workflows/ci.yml`, since Scala suites read them.

**Rationale**: `ci-coverage.py` fails when a tracked file matches no filter, and nothing on `main`
claims either path. `modules/**` and `samples/**` are already claimed (`ci.yml:59-62`).

**A known collision**: the `019-service-topology` branch adds its own `GLOSSARY.md`, `features/`
and a `features` job. Whichever branch merges second reconciles the glossary (three definitions
were widened here: `handler`, `request`, `call`) and takes the other's CI job.

## R20 — documentation

**Decision**: one new page, `docs/build/grpc-endpoints.md`, with every sample included from the
shopping cart's tested code. Changed pages: `reference/service-descriptor.md`,
`reference/configuration.md` (the generated table gains the module's keys once its
`reference.conf` is listed in `mkdocs.yml`'s `extra.docs`), `reference/error-codes.md` (the status
table), `reference/limitations.md`, `reference/glossary.md` (an endpoint is HTTP or gRPC),
`reference/akka-divergences.md`, `platform/networking.md`, `deploy/expose.md`,
`build/http-endpoints.md` (a pointer), `build/testing.md`, `operate/local-console.md`,
`concepts/architecture.md`, `README.md` and `CLAUDE.md`. The new page goes in the nav and in a
skill's `pages:` list.

**Rationale**: `docs check` refuses a page that is in neither, and its coverage check fails until
the prose mentions every new configuration key (`tools/docs/src/ankka_docs/generate.py:229`).
`DocumentationDescriptorsSuite` validates every `service.json` block against R10's rules.
