# Implementation Plan: Polyglot Service Client — Calling Another Service as Yourself from Any Language and Any Component

**Branch**: `025-polyglot-service-client` | **Date**: 2026-10-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/025-polyglot-service-client/spec.md`

## Summary

Today only a Scala endpoint can call another service as itself. After this feature a workflow's
step, a consumer, a timed action and an agent can too, in Scala, Python and TypeScript, and a
Python or TypeScript endpoint can. There is still one client: the runtime's `HttpServiceClients`,
which resolves the service, presents the caller's certificate and checks the callee's. A Scala
context is handed it; a process asks its sidecar to use it, through one call added to the
protocol's `Client` service, and never holds a key.

Technically: the client is made before the components and given to the five contexts that may
have it, at the sites the secret store was (R1, R2). The protocol gains `Request` at 1.7, with
every outcome in the reply so that a module's import can carry it later (R5, R9). `ClientLogic`
serves it over the same client, as the handler its metadata names (R6). The client itself changes
in four small ways, for every door alike: a fourth error for a call nothing answered (R3), a
timeout that is a setting (R4), the platform's headers removed (R7), and a span in the caller's
trace (R10). Each SDK gains a `Services` object placed where its `Secrets` is (R11).

Planning found five things the spec did not have:

- **The sidecar can tell who is calling, where the secret store's research found it could not.**
  That finding was about requests with no metadata. `Request` carries the handler's, the sidecar
  already believes a forwarded name only when the service declared it, and the registry knows
  each component's kind. So FR-004 is enforceable as written, and the SDKs withhold the client
  from entities as well (R6, R11).
- **The client is not a member of the component client**, where the spec's Context sketches it.
  An entity holds that client in both SDKs. FR-006 was amended (R11).
- **A workflow's command handlers share its steps' context**, so the client refuses outside a
  step, as the secret store does. FR-010 was amended and a scenario added (R2).
- **Bodies are bounded at 4,000,000 bytes through the sidecar**, because the protocol's messages
  are. FR-015 was added (R8).
- **The timeout needs a variable as well as a key.** A process-hosted service has no
  configuration file; its descriptor can give its sidecar only a variable, and only one the
  platform's declaration sends to the platform's container (R4).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`core`, `sdk`, `runtime`, `http`, `agent`, `testkit`,
`sidecar`, `controlplane-api`, `proxy` for one test, the shopping cart sample); Python ≥ 3.12
(`sdks/python`); TypeScript on Node ≥ 22 (`sdks/typescript`); protobuf (`protocol`). Rust only as
a refreshed copy of `protocol/`.

**Primary Dependencies**: none added, in any language. The call is the JDK's `java.net.http`
client already in `runtime`; the protocol is grpc-java in `sidecar`, `grpcio` in Python and
Connect in TypeScript, as today.

**Storage**: none. No table, event, snapshot field or resource field (see
[data-model.md](data-model.md)).

**Testing**: munit in `http` (`ServiceClientSuite`), `testkit` (`ServiceCallsSuite`), `sidecar`
(`ClientRequestSuite`, eight new `service.*` cases of `ConformanceSuite` against the Scala
reference and each process, one k3s case in `SidecarClusterSuite`), `proxy` (the header lists
held to each other), `controlplane-api` (`ServiceCallsDocumentationSuite`, and the platform
variable iteration tests), the sample's `CallersSuite`, three assertions in `controlplane`'s `ZeroTrustClusterSuite` (k3s);
pytest with mypy; Node's test runner with
`tsc`; `cargo test` unchanged; the docs build; the features check.

**Target Platform**: wherever an ankka service runs in process or behind a sidecar, on a
developer's machine and in a cluster. A module is unchanged.

**Project Type**: platform libraries and a protocol (the runtime, the sidecar, two SDKs), a
sample, documentation

**Performance Goals**: nothing is added to any path that does not call another service. A call
through the sidecar adds one loopback gRPC round trip to the HTTP call it makes. A span costs
what any span costs, and is begun only when the thread is in a trace.

**Constraints**: no certificate or key in a process's container; no second resolver, TLS
configuration or identity check; no retries, redirects or streaming; an entity's and a view's
context have no client, by type; nothing unbounded is interned into the recorder's names; a 1.6
process and every module run unchanged; the three SDK copies of `protocol/` identical to the
canonical one; `Test / parallelExecution := false` stays; warning-free; no suite binds a fixed
port

**Scale/Scope**: about 6 new Scala source files and 24 changed across eight modules, with 4 new
suites and about 8 changed; 1 protocol file; per SDK about 2 new source files, 8 changed, 2 new
test files, and the example's endpoint and conformance reference changed; 1 route added to the
Scala sample; 1 new docs page and about 10 changed; the skills rendered again

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` states as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; the runtime interprets | pass | no effect is added. The client is offered only where handlers already run blocking sequential code on a virtual thread, and withheld from entities, whose handlers return effects (R2) |
| Where two interpreters reduce the same thing, they share one function | pass | one `HttpServiceClients` behind every door (R1); the header rule, the timeout, the fourth error and the span are in it and not in the sidecar (R3, R4, R7, R10); one function on `Observability` counts and traces a call (R10) |
| Module dependency direction | pass | error and contexts in `sdk`, wiring in `runtime` and `agent`, doubles in `testkit`; `runtime` does not gain `proxy-core`, and the two header lists are held together by a test in `proxy`, which sees both (R7) |
| `runtime` never sees the generated protocol | pass | `ClientLogic` in `sidecar` translates the two messages to the `sdk` trait's plain values and its errors (R5) |
| No classpath scanning; explicit registration | pass | no component is added; nothing is discovered |
| Wire names are a versioning boundary | pass | one call and one enum, declared; the change is a minor by the protocol's own rule, and both directions of a version mismatch are answered by name (R9) |
| The protocol directory is copied whole, and CI proves it | pass | one edit to `protocol/`, three runs of the SDKs' copy scripts (R12) |
| Stored forms stay readable both ways; the schema is additive | pass | nothing is stored |
| Never intern anything unbounded into the recorder's names | pass | the span's names are the ones the count already interns, bounded by `ExternalServices` and the method (R10) |
| A trace belongs on the thread doing the work | pass | the sidecar sets the trace and the caller inside the virtual thread that makes the call, from the request's metadata (R10) |
| A refusal is not a failure | pass | a 4xx answer is recorded refused and returned as an answer; only no answer is unanswered (R3) |
| Anything read from the environment is overridable; a new variable is in the one declaration | pass | the timeout is a key with a variable, and the variable is in `PlatformVariables` (R4) |
| Tests are serialised; a test never binds a fixed port; a test names no image by a literal tag | pass | every stand-in is on loopback and an ephemeral port; the cluster case uses `BuildInfo.imageTag` (R15) |
| A consumer that must fail forever cannot be tested in a running service | pass | the redelivery test fails once (`failNext`) (scala-api) |
| Could this check pass while the thing it checks is false? | pass | the span test asserts the parent's id; "not made again" counts connections; a conformance filter names its wildcard; the admitted call is paired with a refused one on the same route (R14, R15, quickstart) |
| Each acceptance scenario ends as a test that fails without the feature | pass | 27 scenario references, each mapped to a level in R14 |
| Docs: pages stand alone, samples from tested code, new pages in nav and a skill | pass | R16 |
| Every tracked file claimed by a CI path filter | pass | every new file is under a directory a filter already claims |

**Violations to justify**: none against these principles. Where the plan departs from the spec's
wording is under *Complexity Tracking*.

**Post-design re-check**: unchanged. The contracts add no dependency, no stored form, no grant
and no image.

## Project Structure

### Documentation (this feature)

```text
specs/025-polyglot-service-client/
├── plan.md              # this file
├── research.md          # R1–R17: decisions with file-level evidence; six things to verify first
├── data-model.md        # the three messages, the errors, the caller, the setting, the span
├── quickstart.md        # the validation runs: client → Scala service → sidecar → conformance → SDKs → k3s → docs
├── contracts/
│   ├── scala-api.md         # the errors, who has the client, what it does to a request, test kits
│   ├── protocol.md          # the wire at 1.7, who is calling, headers, bounds, conformance cases
│   └── sdk-apis.md          # Python and TypeScript
└── tasks.md             # /speckit-tasks, not created here
```

The acceptance scenarios are in `features/service-calls/` (four files) and
`features/documentation/service-calls.feature`, in the words of `GLOSSARY.md`.

### Source Code (repository root)

```text
modules/sdk/…/sdk/ServiceClient.scala                          # ServiceUnanswered
modules/sdk/…/sdk/contexts.scala, TimedAction.scala            # services on workflow, consumer, timed action
modules/runtime/…/runtime/HttpServiceClients.scala             # the timeout, the headers, the fourth error
modules/runtime/…/runtime/OutboundHeaders.scala                # new: the one rule for a request's headers
modules/runtime/…/runtime/Observability.scala                  # one function: a call to another service, counted and traced
modules/runtime/…/runtime/Ankka.scala                          # the client made before the components; withServices
modules/runtime/…/runtime/StepScope.scala, WorkflowHost.scala  # the step-only client
modules/runtime/…/runtime/ProjectionRuntime.scala, TopicHandlers.scala, TimerRuntime.scala, TimerSweeper.scala
modules/runtime/src/main/resources/reference.conf              # ankka.service-client.timeout
modules/core/…/core/PlatformVariables.scala                    # ANKKA_SERVICE_CLIENT_TIMEOUT
modules/agent/…/agent/Agent.scala, AgentRuntime.scala, autonomous/AutonomousAgent.scala, AutonomousAgentHost.scala
modules/testkit/…/testkit/ScriptedServices.scala               # new: the unit double
modules/testkit/…/testkit/ScriptedService.scala                # new: the loopback stand-in
modules/testkit/…/testkit/AnkkaTestKit.scala, ConsumerTestKit.scala   # localServices; services

modules/http/src/test/…/http/ServiceClientSuite.scala          # unanswered, once, headers
modules/testkit/src/test/…/testkit/ServiceCallsSuite.scala     # new: components.feature
proxy/src/test/…/proxy/OutboundHeadersSuite.scala              # new: the two lists agree

protocol/src/main/protobuf/ankka/protocol/v1/client.proto
protocol/README.md

modules/runtime/…/runtime/remote/Conversation.scala            # Version = "1.7"
controlplane-api/…/api/Compatibility.scala                     # protocol 1.7
sidecar/…/sidecar/ClientLogic.scala, ClientService.scala       # request; the declared version
sidecar/…/sidecar/SidecarExtension.scala, Discovery.scala      # the declared version reaches the client service
sidecar/src/test/…/sidecar/ClientRequestSuite.scala            # new
sidecar/src/test/…/conformance/ConformanceSuite.scala, ConformanceReference.scala, ConformanceTarget.scala
sidecar/src/test/…/sidecar/SidecarClusterSuite.scala           # the cluster case
build.sbt                                                      # sidecar's tests build the sample's image

samples/shopping-cart/…/api/CallersEndpoint.scala              # a route that admits only `orders`
samples/shopping-cart/src/test/…/CallersSuite.scala
controlplane/src/test/…/controlplane/ZeroTrustClusterSuite.scala   # the route, as Scala and through the gateway

sdks/python/src/ankka/services.py (new), endpoint.py, consumer.py, timed_action.py, agent.py,
                      autonomous.py, graph.py, workflow.py, service.py, __init__.py
sdks/python/tests/test_services.py (new), examples/shopping_cart/endpoint.py, conformance.py
sdks/typescript/src/services.ts (new), endpoint.ts, consumer.ts, timedAction.ts, agent.ts,
                    autonomous.ts, graph.ts, workflow.ts, spec.ts, index.ts
sdks/typescript/test/services.test.ts (new), examples/shopping-cart/endpoint.ts, conformance.ts
sdks/python/proto/, sdks/typescript/proto/, sdks/rust/ankka/protocol/   # the copies

docs/build/calling-services.md (new), docs/build/http-endpoints.md, docs/build/testing.md,
docs/platform/networking.md, docs/reference/{limitations,configuration,sidecar-protocol,
scala-sdk,python-sdk,typescript-sdk}.md, mkdocs.yml, tools/docs/skill/
controlplane-api/src/test/…/api/ServiceCallsDocumentationSuite.scala   # new
```

**Structure Decision**: no module, image or published artifact is added. Each piece goes where
its kind already lives: the error beside the three it joins in `sdk`, the client's rules in the
client, the call on the protocol's existing `Client` service behind the existing `ClientLogic`,
each SDK's `Services` beside its `Secrets`. The one new placement is the header rule as a file of
its own in `runtime`, so that a test in `proxy` can name it.

## Order of work

Cut by user story, tests before the code they hold. The six checks of research R17 come first,
because each would change a decision.

1. **The client's own changes** (the ground under both P1 stories). `ServiceUnanswered`, the
   timeout and its variable, the header rule, the span. Held by `ServiceClientSuite`,
   `OutboundHeadersSuite` and the platform variable tests. Nothing else is touched, and an
   endpoint's calls already show all four.
2. **Scala components** (User Story 2). The client made before the components, the five
   contexts, the step-only client, the test kits, `ServiceCallsSuite`.
3. **The protocol and the sidecar** (User Story 1, first half; User Story 3). 1.7, `ClientLogic`,
   the declared version, `ClientRequestSuite`, the Scala conformance reference and the eight
   cases against it.
4. **The two SDKs** (User Story 1, second half), independent of each other: `Services`, the
   kinds that offer it, the doubles, the example's route, the conformance reference, the
   integration test. The Rust copy.
5. **The cluster case** (User Story 1's proof). The sample's route, the suite, the build hook.
6. **Documentation** (User Story 4), then the whole build.

Slices 2 and 3 each depend on 1; slice 3's suites use slice 2's test-kit stand-ins; 4 depends on 3; 5 on 4's Python half.

## Complexity Tracking

No principle is violated. These are the places the plan departs from the spec's wording or widens
the platform's surface, each with the simpler thing that was rejected.

| Departure | Why needed | Simpler alternative rejected because |
|---|---|---|
| The SDKs' client is not on the component client (FR-006, as amended) | an entity holds the component client in both SDKs, so a member there hands an entity the call | `client.services(...)` as the spec's Context sketches it leaves FR-010 true in Scala and false in Python and TypeScript (R11) |
| A workflow's client refuses outside a step (FR-010, as amended) | a workflow's commands and steps share one context, so the type cannot separate them | offering it to commands puts a blocking call on the workflow's single-writer path, which is what the spec refuses for entities; the secret store already answers this way (R2) |
| Bodies are bounded at 4,000,000 bytes through the sidecar, and not in Scala (FR-015, added) | the protocol's messages are bounded at 4 MiB by grpc-java and `grpcio` | no stated bound is a `RESOURCE_EXHAUSTED` that names a byte count; raising the limits is a number three programs must agree on (R8) |
| The header rule and the fourth error change the Scala client's behaviour | one rule for every door | the rule in the sidecar alone lets a Scala handler send what a Python one cannot, and leaves Scala naming the JDK's exceptions (R3, R7) |
| A variable, `ANKKA_SERVICE_CLIENT_TIMEOUT`, beside the key the clarification named | a descriptor can give a sidecar nothing but a variable | a key alone cannot be set by the services this feature is for (R4) |
| The Python call and the gateway's refusal are shown on two clusters, not one | the suite that deploys a process has no gateway; the suite with a gateway already runs the Scala sample, so the same route is asked there at no cost | moving the Python case to the suite with the gateway puts two more images and a fourth JVM on a node already running a three-instance cluster (R15) |
| No TypeScript service is deployed to a cluster | nothing in a cluster differs by language; the sidecar is one image | a third image and its build for a path the Python case and the conformance suite already cover between them (R15) |
| `ServiceBuilder.withServices`, `private[ankka]` | an identity mismatch needs certificates, and a conformance run has none | without the seam the mismatch is proven in three pieces and never through an SDK (protocol contract) |
| The "Call another service" section moves to a page of its own | the documentation scenario asks for one page, and a workflow's author does not read the endpoints page | a longer section on `http-endpoints.md` keeps the call filed under the one component that already had it (R16) |

**Two operational consequences** to announce in the release:

- A Scala handler that caught `java.io.IOException` or `HttpTimeoutException` around a call to
  another service now sees `ServiceUnanswered`, with the old exception as its cause.
- The protocol is 1.7. A process built with the new SDK needs a sidecar of this release to call
  another service; everything else it does works with a 1.6 sidecar, and a 1.6 process works with
  the new sidecar.
