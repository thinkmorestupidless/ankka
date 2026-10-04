# Research: Polyglot Service Client

Decisions for [plan.md](plan.md), each with the evidence in the tree at `64102d79`. The last
section lists what must be verified before the code that relies on it is written.

## R1. One client, built before the components

**Decision**: every context is given the one `ServiceClients` the endpoint has today. It is made
in `Ankka.start` before any component is instantiated, beside the secret store, and handed to
`AnkkaService` rather than built there. It stays lazy: the `HttpServiceClients` behind it is
constructed on the first call.

**Rationale**: `AnkkaService.services` is a `lazy val` on the running service
(`Ankka.scala:385-386`), and components receive their contexts while the service is still being
built (`Ankka.scala:183-200`, where `secrets` is made for exactly this reason). A context cannot
be given a member of an object that does not exist yet. Laziness is kept because in a cluster the
client reads the service's certificate (`HttpServiceClients.scala:47-55`), and a service that
calls nobody should not.

**Alternatives considered**: a second `HttpServiceClients` per host — two TLS contexts and two
client caches for one identity, and the first decision of the spec is that there is one. Passing
`AnkkaService` into contexts — it does not exist when they are built.

## R2. Who is given it, and where that is enforced

**Decision**: `services: ServiceClients` is added to `WorkflowContext`, `ConsumerContext`,
`TimedActionContext`, `AgentContext` and `AutonomousAgentContext`, at the sites `secrets` was
added. It is not added to `ComponentContext`, so an entity's and a view's context have none and
`context.services` there does not compile. A workflow's client refuses outside a step, through
`StepScope`, as its secret store does.

**Rationale**: the secret store went to the same components for the same reason — a blocking call
belongs where handlers already run sequential blocking code on a virtual thread, and not on a
single-writer path — and it left a complete map of the sites: `contexts.scala:25-41, 60-77`,
`TimedAction.scala:32-48`, `Agent.scala:50-65`, `AutonomousAgent.scala:64-71`, and the wiring in
`Ankka.scala:202-317`, `WorkflowHost.scala:58-66`, `ProjectionRuntime.scala:108, 242-274,
558-598`, `TopicHandlers.scala:86-92`, `TimerRuntime.scala:54`, `TimerSweeper.scala:43-101`,
`AgentRuntime.scala:123-344`, `AutonomousAgentHost.scala:75-87`. A workflow's command handlers
and steps share one context, and a command runs on the actor's thread, where the step mark is
never set (`StepScope.scala`; verified in 023 as V1).

The spec gives the client to "a workflow step" and does not say what a workflow's command handler
gets. Refusing it there is the entity rule applied to the other single-writer path, and it is
what the secret store does; a second answer for a second client would have to be explained.

**Alternatives considered**: a member on every context that throws in an entity — a run-time
failure for what the type can say. A separate step context — a breaking change to every workflow.

## R3. `ServiceUnanswered`, raised in one place

**Decision**: a fourth error in `sdk`, `ServiceUnanswered(service, reason)`, with the JDK's
exception as its cause. `HttpServiceClients.counted` raises it where it now rethrows: an
`HttpTimeoutException` becomes "no answer within <timeout>", and any other failure that is not
already one of the client's own errors becomes the failure's message.

**Rationale**: `counted` is the one function every request passes through, and it already tells
the two cases apart to count them — `Unanswered.TimedOut` and `Unanswered.Undelivered`
(`HttpServiceClients.scala:136-160`). `ServiceUnresolvable` and `ServiceIdentityMismatch` are
thrown inside the same block (`:77-87`, `:118-123`) and are counted as undelivered today; they
pass through unchanged. `ServiceCallFailed` is raised above this, by the typed helpers, from an
answer (`ServiceClient.scala:58-63`).

**Alternatives considered**: translating only in the sidecar — the Scala door would keep the
JDK's exceptions and the three languages would name different things, which the clarification
refused.

## R4. The timeout is a setting with a variable

**Decision**: `ankka.service-client.timeout = 30s` in `runtime`'s `reference.conf`, with
`timeout = ${?ANKKA_SERVICE_CLIENT_TIMEOUT}`. `HttpServiceClients.send` reads it in place of the
literal. `ANKKA_SERVICE_CLIENT_TIMEOUT` is added to `PlatformVariables.RuntimeOnlyNames`. The
five seconds to connect stay as they are.

**Rationale**: the literal is at `HttpServiceClients.scala:187`. A Scala service can set a key in
its `application.conf`; a process-hosted service has no such file, and the only thing its
descriptor can give its sidecar is a variable. The operator sends a variable to the platform's
container only when `PlatformVariables.runtimeOnly` says so (`PlatformVariables.scala:54-62,
86-87`); anything else goes to the process, which does not make the call. `ANKKA_SECRET_KEY` is
the precedent for one key, one variable, one name in that set (`reference.conf:22-25`). The
documentation's generator puts a key with a variable in the first table of
`reference/configuration.md` and its coverage check then requires the prose to name the variable.

**Alternatives considered**: a key with no variable — unusable from a descriptor for exactly the
services this feature is for. A per-call parameter — refused in clarification.

## R5. One call, with every outcome in the reply

**Decision**: `rpc Request (ServiceRequest) returns (ServiceReply)` on `Client`. The reply is one
of an answer (`endpoint.proto`'s `HttpResponse`), a `ServiceFailure` with a reason, or an `Error`.
Nothing is a gRPC status. The shapes are in [contracts/protocol.md](contracts/protocol.md).

**Rationale**: the secret store's calls set the rule and say why in the file: "A refusal or a
fault is an Error in the reply, never a gRPC status, so that a module's import can carry it the
same way" (`client.proto:16-18`). The module import that will carry these two messages is a later
feature, and it can only carry a message. `HttpResponse` and `HttpRequest.Pair` already describe
an HTTP answer and a header pair to a process (`endpoint.proto:16-30, 58-63`), so a process sees
one shape for the request it serves and the answer it is given. The three failure reasons are an
enum and not three `ErrorCode`s because `ErrorCode` is shared by every refusal a handler makes
(`payload.proto:20-29`) and none of its values means "the certificate named somebody else".

**Alternatives considered**: three RPCs (`Get`, `Post`, …) — the typed helpers are the SDK's, over
one raw request, as in Scala (`ServiceClient.scala:25-55`). gRPC statuses — cannot cross an
import.

## R6. Who is calling

**Decision**: `ServiceRequest` carries the handler's `metadata`, as `InvokeRequest` does.
`ClientLogic.request` resolves the caller with `observability.declared.origin(metadata)` and then
looks the component up in the registry: a declared handler of an event sourced or key value
entity is refused, naming the kind; a workflow's handler whose declared kind is not `Step` is
refused, naming "a step"; any other declared handler makes the call as itself; no declared caller
makes the call as nobody.

**Rationale**: the sidecar already believes a forwarded name only when the service declared it,
and makes the call as that handler (`ClientLogic.scala:53-66`, `CallOrigin.scala:66-76`). The
registry knows each component's kind and each declared handler's kind (`ComponentDescriptor.scala:
4-17, 51-73`), and two sharded kinds cannot share an id, so a component id that names an entity
names nothing else that makes calls. Research for the secret store found the sidecar could not
tell who called (`specs/023-secret-store/research.md:43-45`); that was because its requests carry
no metadata, and this one does.

The rule is as strong as the SDK's forwarding, which is why each SDK also offers an entity no
client (R11): a developer who goes round the SDK and omits the metadata makes a call counted from
the unknown caller, which the clarification accepted. It protects an entity's other commands and
is not a security boundary: the callee's ACL sees the service's certificate either way.

**Alternatives considered**: refusing a request with no declared caller — breaks a call made at
start-up or from background work, refused in clarification.

## R7. Headers are replaced in the client, for every door

**Decision**: `HttpServiceClients.send` drops, in any letter case, names starting `x-ankka-`,
`forwarded`, names starting `x-forwarded-`, `host`, `content-length`, `expect` and the hop-by-hop
headers, and takes the content type from its own parameter. The rule is one function in
`runtime`, and a test in `proxy` holds its list to `proxy-core`'s.

**Rationale**: FR-005 asks the sidecar to do this; doing it in the client means a Scala handler
cannot do what a Python one cannot, and there is one rule. No ankka runtime reads a caller from a
header — the caller is the certificate's (`Caller.fromCertificate`) — but a web-hosted callee's
process reads `X-Ankka-Caller` from its proxy, and a local runtime honours `X-Ankka-Local-Caller`
when it carries the process's token (`Caller.scala:118-143`); neither is the calling handler's to
say. The proxy has the same list for the same reason (`proxy-core/…/Headers.scala:11-33`), and
`runtime` cannot depend on `proxy-core`, which depends on nothing; `proxy` sees both. `Host`,
`Content-Length` and `Expect` are restricted by the JDK's client, which throws
`IllegalArgumentException` for them today; dropping them turns a crash that names nothing of
ankka's into nothing happening.

**Alternatives considered**: the rule in `ClientLogic` only — two behaviours. Refusing a request
that sets one — a handler that forwards the headers it was given would fail on `Host`.

## R8. Bodies are bounded by the protocol's messages

**Decision**: a request body and an answer body are each at most 4,000,000 bytes through the
sidecar. The SDK refuses a larger request before sending; the sidecar answers `INTERNAL`, naming
the limit and the service, for a larger answer. The Scala door has no such bound.

**Rationale**: grpc-java's server and Python's `grpcio` both refuse a message over 4 MiB by
default, and neither the callback server nor an SDK raises it (no `maxInboundMessageSize` in
`sidecar/src/main`, no receive option in either SDK). Without a stated bound the failure is a
gRPC `RESOURCE_EXHAUSTED` that names a byte count and nothing else. The consumer's publications
are bounded at 4 MiB for the same reason. The spec puts streaming bodies out of scope.

**Alternatives considered**: raising the limits — every SDK and the sidecar would have to agree
on a new number, and the answer to a large transfer is the object storage feature, not this call.

## R9. Protocol 1.7, checked in both directions

**Decision**: the protocol version becomes `1.7`: `Conversation.scala:165`, `Protocol.version` in
`controlplane-api`, `protocol/README.md`, `PROTOCOL_VERSION` in the Python and TypeScript SDKs.
An SDK reports `UNIMPLEMENTED` from `Request` as the runtime being too old, naming both versions.
`ClientLogic` is given the version the process declared in discovery and answers `BAD_REQUEST`,
naming both, to a `Request` from a process that declared less than 1.7. Rust's constant stays at
1.6.

**Rationale**: adding a call is a minor by the protocol's own rule (`protocol/README.md:16-17`),
and the secret store's `SECRETS_SINCE` and `tooOld` are the pattern in both SDKs
(`secrets.py:28, 61-68`; `client.ts:235, 255-263`). The sidecar holds the process's declared
version from discovery (`Discovery.scala:75-140`) and passes nothing of it to `ClientService`
today; FR-003 needs it there. Rust speaks through imports and gains none, and a module that
declares 1.6 runs unchanged on a 1.7 runtime.

**One thing outside this branch**: feature 024 is in progress and may also raise the minor. The
second of the two to merge takes the next number; nothing else in either changes.

## R10. An outbound call is a span

**Decision**: `Observability` gains one function for a call to another service, used by
`HttpServiceClients.counted` in place of its two counting calls: it begins a span when the thread
is in a trace, whose parent is the thread's current span, named by the service's admitted name
and the method, and completes it with the outcome it counts. For a process, `ClientLogic.request`
runs the call inside `Trace.within(traceId, spanId, origin)` read from the request's metadata.

**Rationale**: a call to another service is counted today and is in no trace: `counted` calls
`made` and `madeUnanswered`, which touch only `CallCounts` (`HttpServiceClients.scala:136-160`,
`Observability.scala:124-147`); spans are begun only by `invocation` and its two copies
(`Observability.scala:171-190`). FR-011 needs the span. Its two names are already bounded: the
service's by `ExternalServices`, which admits names up to a limit and counts the rest together
(`ExternalServices.scala`), and the method by `HttpServiceClients.methodName`. So nothing
unbounded is interned. `asCaller` sets an origin and no trace (`ClientLogic.scala:53-66`); for
this call the trace is wanted, and the metadata carries it (`Trace.scala:71-72, 170-176`).

The trace does not cross to the service called: "A call between services starts a new trace"
(`docs/reference/limitations.md:109-110`) stays true, and is feature 026's to change.

**Alternatives considered**: a span only in the sidecar — the Scala door would not have one.

## R11. Python and TypeScript

**Decision**: a `Services` object in each SDK, offered where `Secrets` is and through the same
means: Python's `HasServices` mixin on `Endpoint`, `Consumer`, `GraphConsumer`, `TimedAction`,
`Agent` and `AutonomousAgent`, and a step-only property on `Workflow`; TypeScript's `get
services()` on the same classes. No member on an entity or a view. The metadata sent is the
running handler's without the developer forwarding it. Shapes are in
[contracts/sdk-apis.md](contracts/sdk-apis.md).

**Rationale**: the secret store's placement is the map (`secrets.py:148-171`, `workflow.py:156,
180-196`; `client.ts:340-352` and the six getters). Two things differ from secrets:

- **Metadata must travel.** A secret request carries none. In Python every kind but the endpoint
  is constructed with a client already scoped to the handler's metadata (`server.py:127-133, 282,
  318, 434, 521, 539-544`); an endpoint instance is cached with the unscoped client and its
  handlers forward metadata by hand (`server.py:622-631`), so the endpoint's `services` reads the
  current request's metadata itself. In TypeScript every binding, the endpoint's included, scopes
  the client (`endpoint.ts:24-29`).
- **The errors are their own classes**, because a handler branches on them, where a secret's
  refusals are all `CommandError`.

**Alternatives considered**: `client.services(...)` on `ComponentClient`, as the spec's Context
sketches — an entity holds that client in both SDKs (`context.py:67-78`), so it would hand an
entity the call, which is what the secret store's research refused for the same reason.

## R12. Rust and modules

**Decision**: nothing but the copy of `protocol/`. No import, no crate API.

**Rationale**: the three SDK copies are held identical to the canonical directory by CI
(`ci.yml:330-331, 388-389, 486-489`), so `scripts/proto.sh` is run. `prost` generates the two new
messages and nothing uses them. The spec puts the import in feature 030.

## R13. On a developer's machine

**Decision**: no mechanism is added. The sidecar resolves a local service as a Scala service
does. A test that needs an address sets `ankka.local-services.<name>`: `AnkkaTestKit.start` gains
`localServices`, and a sidecar in a container is given it as a system property through
`JAVA_OPTS` in the `env` both integration kits already pass through.

**Rationale**: `ankka.local-services` is read from the service's configuration and nowhere else
(`HttpServiceClients.scala:171-175`), and tests set it today with `sys.props.put` and
`ConfigFactory.invalidateCaches()` (`TopologySuite.scala:102-105`), which changes the whole JVM. A
parameter on the kit scopes it to one service. Both SDK kits apply the caller's `env` last
(`integration.py:168-169`; `integration.ts:243-276`), and the sidecar's image is
`JavaAppPackaging`'s, whose launcher reads `JAVA_OPTS`.

## R14. Where each scenario is held

| Scenario | Level |
|---|---|
| a service in every language is admitted by name by a route that admits only it | k3s: `SidecarClusterSuite` for Python, `ZeroTrustClusterSuite` for Scala; conformance `service.request-reaches-target` for TypeScript (R15) |
| a route that admits only one service by name refuses the gateway | k3s, `ZeroTrustClusterSuite`, through the real gateway; and the sample's `CallersSuite`, with the gateway as caller |
| a refusal by the service called reaches the calling handler as that refusal | conformance `service.refusal-is-the-answer`, each target |
| a call to a service that cannot be found fails, naming the service, and is not sent | conformance `service.unresolvable`, each target |
| a call is not sent to a workload that is not the service asked for | `ServiceClientSuite` (the handshake, existing) and conformance `service.identity-mismatch`, each target |
| a call to a service whose instance is being replaced is unanswered and is not made again | `ServiceClientSuite`: a listener that accepts and closes, counting connections |
| on a developer's machine a service in every language calls another service running there | conformance `service.request-reaches-target` and `service.answer-reaches-handler`, each target; one integration test per SDK through a real sidecar container |
| a call that is not answered within the time its service is set to wait is unanswered | `ServiceCallsSuite` (`testkit`), with the setting at half a second |
| the process of a service that calls other services holds no certificate | k3s, `SidecarClusterSuite` |
| nothing the process says in a call makes it come from another service | conformance `service.platform-headers-replaced`, each target; `ClientRequestSuite` |
| two calls a process makes at once are both answered without waiting for each other | `ClientRequestSuite` (`sidecar`) |
| an entity's handler in a process may not call another service | `ClientRequestSuite`, both kinds; each SDK's test that an entity has no `services` |
| a call a process makes outside any handler is made and counted from the unknown caller | `ClientRequestSuite` |
| a process made for a protocol version before calls to other services is not served one | `ClientRequestSuite` |
| the eight scenarios of `components.feature` | `ServiceCallsSuite` (`testkit`), one test each, named for the scenario |
| the conformance suite's call to another service passes for every SDK | the `service.*` cases, run by each SDK's conformance command |
| an SDK's type checks accept a call to another service | `mypy` and `tsc` in each SDK's own checks |
| the three scenarios of `documentation/service-calls.feature` | `ServiceCallsDocumentationSuite` (`controlplane-api`), as `GrpcDocumentationSuite` is |

Suites named for scenarios, not a `GherkinSuite`: the scenarios span five modules and three
languages, and no one suite can run a file whole. That is how the secret store's features are
held.

## R15. The cluster case

**Decision**: `SidecarClusterSuite` deploys the Python sample a second time, as the service
`orders`, and the Scala shopping cart as `carts`, in one project. The Scala sample's
`CallersEndpoint` gains a route that admits only the service `orders`. The suite asks `orders` to
call that route and reads `200` and "the orders service in project …"; asks the same route from
another service's pod and reads `403`; and reads the Python container's files and environment for
a certificate. `sidecar`'s tests gain the sample image as a build-level task dependency, gated on
`-Dankka.cluster.tests` as its own image is.

**Rationale**: `SidecarClusterSuite` is the only suite that deploys a process-hosted service, and
it already builds the Python image and derives a second one from it (`:48-49, 96-109, 238-268`).
The sample's existing `/callers/only-orders` admits the internet as well (`CallersEndpoint.scala:
28-31`), so it cannot show that a name was what admitted the call.

Two departures from the spec's independent test, both in the plan's table:

- **The refused caller is another service, not the gateway.** That suite has no gateway.
  `ZeroTrustClusterSuite` has one, and already deploys the Scala sample as `carts` and as
  `orders` (`:339-405`), so the sample's new route is asked there too with no new image and no
  new pod: `orders` is admitted through the service client, `orders` of another project is
  refused, and a request through the gateway is refused. The Python case and the gateway case are
  then two clusters asking one route of one image, which is what the single check would say.
- **No TypeScript service is deployed.** Nothing in a cluster differs by language: the
  certificate, the resolution and the handshake are the sidecar's, one image for both. What
  differs is the SDK, and the conformance suite holds that for both against the same sidecar
  code.

**Alternatives considered**: the case in `ZeroTrustClusterSuite` — it would need the sidecar's and
the Python image in `controlplane`'s tests, and a fourth JVM on a node that already runs a
three-instance cluster; a busy k3s node is a known cause of failures no single case reproduces.

## R16. Documentation

**Decision**: a new page, `docs/build/calling-services.md`, takes the section "Call another
service" out of `build/http-endpoints.md`, which keeps a paragraph and a link. It shows the call
from an endpoint and from a workflow's step in Scala, Python and TypeScript, the four errors, the
timeout, local addresses, and who may not call. Samples are included from the three shopping
cart examples' endpoints (`call-another-service`, which exists in the Scala sample) and, for the
step, from a test in each language: `ServiceCallsSuite`, `test_services.py` and
`services.test.ts`. Changed besides: `reference/limitations.md` (the sentence at
73-75 becomes one about gRPC alone), `build/http-endpoints.md:677-678` (removed),
`reference/configuration.md` (the variable's prose), `reference/sidecar-protocol.md` (the
generated table, 1.7 and its paragraph), `reference/scala-sdk.md`, `python-sdk.md`,
`typescript-sdk.md`, `platform/networking.md` (its link), `build/testing.md` (the doubles), `mkdocs.yml`, and the `pages:` of `ankka-endpoints`,
`ankka-port`, `ankka-python` and `ankka-typescript`.

**Rationale**: the documentation feature asks for one page, and the section sits on a page about
HTTP endpoints, which is where a developer writing a workflow will not look. A new page needs the
nav and a skill or `docs check` fails. The limitation names gRPC too ("for HTTP or for gRPC"), and
only the HTTP half is withdrawn: a Python or TypeScript service still has no gRPC client.

## R17. Verify first

Each is cheap, and each would change a decision above if it did not hold.

1. **The JDK's client makes a request once.** It can retry a request whose connection closed
   before an answer. The accept-and-close listener of R14 says whether it does for `GET` and for
   `POST`; if it does, "not made again" needs the client told not to (R3).
2. **`JAVA_OPTS` reaches the sidecar's JVM** in the image as built, so that R13 is true and not a
   convention.
3. **A span named for another service renders.** The trace document resolves a span's component
   by name; one that is `service:<project>/<name>` is not a registered component (R10).
4. **Connect's client and `grpcio` both fail a 4 MiB reply the way R8 assumes**, so the bound is
   one number for both SDKs.
5. **A Python endpoint's `services` sees the request's metadata** on the thread and task a handler
   runs on (R11).
6. **Each conformance case fails before the SDK change**, and a filtered run names its wildcard
   (`'*service.*'`).
