# Implementation Plan: Polyglot Runtimes

**Branch**: `009-polyglot-runtimes` | **Date**: 2026-09-22 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/009-polyglot-runtimes/spec.md`, and the design
treatment it was written from, `docs/design/polyglot-runtimes.md`.

## Summary

Host a developer's components in a process written in another language, with ankka's own runtime
running beside it as a sidecar. The sidecar owns everything the runtime owns today: sharding, the
journal, snapshots, projections, timers, the agent loop, cluster formation, observability. The
developer's process owns only the decision a handler makes. The two speak a protobuf protocol over
gRPC on the pod's loopback interface, after Cloudstate: one long-lived conversation per loaded
stateful instance, in which the sidecar supplies recovery and commands and the process answers
with the same inert effect an in-process handler returns today.

Nothing about the in-process Scala path changes. The runtime gains a second family of component
descriptors whose handler and fold live across the protocol; the sidecar is a new application
(`sidecar`, an image like the operator and control plane) that boots the existing runtime from a
discovery handshake instead of a Scala builder. The operator learns one new hosting mode and
renders two containers for it. The control plane learns a declared protocol version and applies
the compatibility rule it already applies to a declared runtime version.

One SDK is built, in TypeScript, in this repository under `sdks/typescript`, with a unit testkit
that needs no sidecar and an integration testkit that starts one. A conformance suite, run from
the sidecar's side, defines what a compatible SDK is, and it runs against the Scala SDK in-process
as well, so the two hosting modes are held to one definition of the component model.

## Technical Context

**Language/Version**: Scala 3.9.0, JDK 21, sbt 1.12.15 for the sidecar, protocol module and
operator changes (unchanged). TypeScript 5.x on Node 22 LTS for the SDK (new; research R9).

**Primary Dependencies**: `protocol` (new module): ScalaPB runtime and `sbt-protoc` for generated
messages, `grpc-netty-shaded` and `grpc-stub` for the transport — chosen over pekko-grpc so that
nothing new lands in the pinned Pekko HTTP family (R1). `sidecar` (new application): `runtime`,
`http`, `agent`, `protocol`. The TypeScript SDK: `@grpc/grpc-js` and `@bufbuild/protobuf` (or
`protobufjs`; R9 settles which), `testcontainers` for the integration testkit. No new dependency
in any published Scala module: `core`, `sdk`, `runtime`, `http`, `agent`, `testkit` are untouched
except for the remote descriptor family in `runtime`, which is plain Scala over `EntityProtocol`.

**Storage**: unchanged. The sidecar writes the same journal, snapshot, durable-state, projection,
view and timer tables with the same DDL. Payloads cross the protocol as opaque bytes carrying the
serializer's manifest, so a `JournalRecord` written by the sidecar is indistinguishable from one
written in-process (R4).

**Testing**: munit for everything JVM-side. A protocol suite that runs both ends in one JVM with a
Scala *test double* for the developer's process (`ProcessDouble`, `sidecar/src/test`), which is
how the conversation shape is proven before any TypeScript exists. `AnkkaTestKit` reused for the
sidecar by hosting remote descriptors against the double. One k3s suite (`SidecarClusterSuite`)
that deploys a process-hosted service with the double's image and then the real TypeScript cart.
The conformance suite is a munit suite parameterised by how the service under test is started
(R12). The TypeScript SDK is tested with `vitest`, and its integration testkit starts the
`ankka-sidecar` image and Postgres via `testcontainers`.

**Target Platform**: unchanged for the sidecar (Kubernetes pod, two containers; docker on a laptop).
The SDK targets Node 22 on Linux and macOS.

**Project Type**: two new sbt projects (`protocol`, `sidecar`), one new directory outside sbt
(`sdks/typescript`), additive changes to `runtime`, `crd`, `operator`, `controlplane-api`,
`controlplane`, `cli`, the kustomize components, `docker-compose.yml`, `build.sbt` and the
release workflow, and documentation.

**Performance Goals**: SC-003: one command against a process-hosted entity, measured the way
feature 007 measured recording (HTTP in, entity, journal, reply; 640µs in-process), costs at most
2× in-process, with the wait on the process shown in the console as unattributed time inside the
handler span. Loopback gRPC is expected to add 100–300µs (R1; a number to measure, not assume).

**Constraints**: the in-process path is unchanged in behaviour (FR-026: every existing test passes
with no modification); no existing table changes shape (FR-027); `runtime` gains no dependency on
`http`, `agent` or gRPC (the remote descriptors are data and the conversation client is supplied
to them by `sidecar`, the same inversion as `CallTransport`); the sidecar accepts protocol
connections on loopback only (FR-009); a descriptor cannot name the sidecar image or set its
variables (FR-016); the control plane still holds no credential able to create a workload (the
operator injects the sidecar).

**Scale/Scope**: around 70 files touched or added JVM-side, of which the protocol definition, the
remote hosts and the conformance suite are the large ones; the TypeScript SDK is a project of its
own of roughly the same size as `modules/sdk` plus `modules/testkit`. The riskiest piece is the
per-instance conversation under passivation, process restart and sidecar restart (edge cases in
the spec); the most expensive is the SDK.

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template; `CLAUDE.md` governs.

| Principle | How this plan keeps it |
|---|---|
| Effects are inert data; the runtime interprets them | the protocol's reply *is* the effect, as data: events, retention, reply-or-refusal; the sidecar interprets it under the same `Outcome` rules as `materialise` (R3, R6). A process never performs I/O on the platform's behalf |
| One function reduces an effect, shared by runtime and testkit | the remote hosts reduce a `Reply` through one function, `RemoteEffect.materialise`, which the conformance suite and the `ProcessDouble` also use to state expectations (R6) |
| Module dependency direction | `protocol` depends on nothing of ankka's; `sidecar` depends on `runtime`, `http`, `agent`, `protocol` and is an application with `publish / skip`; `runtime` gains remote descriptors but no transport — the conversation client is a trait in `runtime` implemented in `sidecar`, exactly as `CallTransport` is implemented by `ShardingTransport` (R6) |
| Registration is explicit; no classpath scanning | discovery is the process handing over its descriptors; the sidecar validates them with `ComponentRegistry.from` and reports every problem at once (R5) |
| Wire names are declared separately from method names | discovery carries handler *wire* names; the TypeScript SDK's API takes the wire name as a string argument, as the Scala companion's `command("add-item")` does (R9) |
| `query` cannot persist, by construction | a discovered handler carries `read_only`; a reply with events from a read-only handler is refused by the sidecar as a protocol violation, and the SDK's `query` builder cannot produce one (R3) |
| The RuntimeExtension seam | the sidecar's callback server (the component client's far end) and its readiness are a `RuntimeExtension`; readiness says no until discovery has completed and while the process is unreachable (R8) |
| Never intern anything unbounded | component and handler names from discovery are bounded by the discovered registry and interned once; entity ids and session ids never are |
| Every host handles unexpected commands explicitly, and replies to `InvokeStream` | the remote entity hosts reply `StreamFailed` to a stream request exactly as the in-process ones do |
| Never touch `ActorContext` from a callback | the conversation client completes a `Future`; the host uses `pipeToSelf`, and every reply re-enters the actor as a message (R6) |
| `RollingUpdate` with surge stays; `Recreate` is wrong | the two-container pod keeps the same strategy, `preStop` sleep and readiness probe by port name (R8) |
| Nothing a descriptor writes may point at a name the service does not own | the descriptor gains `hosting` and `protocol` only; the sidecar image and its environment are the operator's, refused in a descriptor by the same rule as `ANKKA_HTTP_PORT` (R8) |
| A test must never name an image by literal tag | the sidecar image tag in the k3s suite is `BuildInfo.version` with `+` → `-`, built by the same sbt session (`ClusterImages`) |
| Cluster formation is an overlay chosen by where the process runs | unchanged; the sidecar is the runtime and reads the same overlays. Locally it is `local`; in a pod the operator sets the same five variables it sets today, on the sidecar container |

**One tension, named**: `CLAUDE.md` says the operator holds only what the resource says. The
sidecar image is not in the resource, by design (FR-016), so the operator must know it from its own
configuration. That is a second thing the operator knows that the resource does not say (the first
is its own version). It is recorded in `operator/Settings` beside the schema-init image and pinned
to the operator's own version, so an upgrade of the platform is an upgrade of the sidecar every
service gets on its next rollout (R8).

No violations.

## Project Structure

### Documentation (this feature)

```text
specs/009-polyglot-runtimes/
├── plan.md
├── research.md          # R1–R12, and the "verify at implementation" list
├── data-model.md        # protocol messages, remote descriptors, sidecar state, descriptor changes
├── quickstart.md        # tiers 1–6 and the reviewer's checklist
├── contracts/
│   ├── protocol.md            # the .proto, conversation by conversation
│   ├── sidecar.md             # image, environment, ports, readiness, the generic invoke route
│   ├── descriptor-and-crd.md  # the `hosting` and `protocol` fields, refusals, what the operator renders
│   ├── typescript-sdk.md      # the package's public API, the two testkits
│   └── conformance.md         # behaviours by name, how a target is supplied
└── checklists/requirements.md
```

### Source Code (repository root)

```text
protocol/                                   # NEW sbt project, no ankka dependency, publish / skip
├── src/main/protobuf/ankka/protocol/v1/
│   ├── discovery.proto                     # Discover, Spec, component entries, ReportError
│   ├── payload.proto                       # Payload{content_type, manifest, data}, Metadata, Failure
│   ├── event_sourced.proto                 # per-instance stream: Init, Event, Command → Reply
│   ├── key_value.proto                     # per-instance stream: Init, Command → Reply
│   ├── workflow.proto                      # per-instance stream: Init, Command, RunStep → Reply/StepReply
│   ├── view.proto, consumer.proto          # stateless: Handle → Effect
│   ├── timed_action.proto                  # stateless: Invoke → Done/Fail
│   ├── agent.proto                         # Plan → AgentReply; InvokeTool → ToolResult; guardrails
│   └── client.proto                        # the callback service: Invoke, InvokeStream, Query, Schedule
└── README.md                               # protocol versioning rule; where the SDKs copy from

modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/
├── remote/
│   ├── RemoteDescriptors.scala             # RemoteEventSourced/KeyValue/Workflow/View/Consumer/
│   │                                       #   TimedActionDescriptor: data from discovery (R6)
│   ├── Conversation.scala                  # trait: what a host needs from the process; implemented
│   │                                       #   in `sidecar` — runtime gains no gRPC (R6)
│   ├── RemoteEffect.scala                  # Reply → Materialised-shaped value; the one interpretation
│   ├── RemoteEventSourcedHost.scala        # EventSourcedBehavior over bytes; stash-while-in-flight
│   ├── RemoteKeyValueHost.scala
│   ├── RemoteWorkflowHost.scala            # WorkflowEngine unchanged; steps run over the conversation
│   └── RemoteProjection.scala              # view/consumer handlers over the conversation
├── Ankka.scala                             # host(): remote descriptors init their remote hosts
└── ProjectionRuntime.scala, TimerRuntime.scala   # accept remote view/consumer/timed-action descriptors

sidecar/                                    # NEW sbt project: an application, an image, publish / skip
├── src/main/scala/com/thinkmorestupidless/ankka/sidecar/
│   ├── Main.scala                          # discover → registry → Ankka.service.registerAll(...).start()
│   ├── Settings.scala                      # ANKKA_PROCESS_ADDRESS, ANKKA_SIDECAR_PORT, timeouts
│   ├── Discovery.scala                     # dials the process, validates the Spec, builds descriptors
│   ├── GrpcConversation.scala              # Conversation over grpc-java; loopback-only server for client.proto
│   ├── ClientService.scala                 # client.proto → ComponentClient / ViewClient / timers
│   ├── RemoteAgent.scala                   # AgentDescriptor whose plan and tools cross the protocol (R10)
│   ├── SidecarExtension.scala              # RuntimeExtension: readiness = discovered && process reachable
│   └── InvokeEndpoint.scala                # the generic HTTP invoke route (R7)
├── src/main/resources/{application.conf, logback.xml}
└── src/test/scala/com/thinkmorestupidless/ankka/sidecar/
    ├── ProcessDouble.scala                 # a Scala process speaking the protocol, scriptable
    ├── ProtocolSuite.scala                 # every conversation, both ends in one JVM
    ├── RemoteEntitySuite.scala             # AnkkaTestKit + double: persist, recover, snapshot, passivate,
    │                                       #   process restart, sidecar restart, late reply, wrong id
    ├── RemoteWorkflowSuite.scala, RemoteProjectionSuite.scala, RemoteAgentSuite.scala
    ├── ConformanceSuite.scala              # behaviours by name; target = in-process | sidecar (R12)
    └── SidecarClusterSuite.scala           # k3s: double image, then the TypeScript cart

sdks/typescript/                            # NEW, outside sbt; the second SDK (R9)
├── package.json                            # @thinkmorestupidless/ankka (name to confirm at publish)
├── proto/                                  # copied from protocol/src/main/protobuf by `npm run proto`
├── src/
│   ├── index.ts, service.ts                # Ankka.service().register(...).listen()
│   ├── eventSourcedEntity.ts, keyValueEntity.ts, workflow.ts, view.ts, consumer.ts,
│   │   timedAction.ts, agent.ts            # companions: command/query with wire names; effects
│   ├── effects/                            # inert effect values, one file per kind
│   ├── client.ts                           # ComponentClient over client.proto
│   ├── server.ts                           # the gRPC server the sidecar dials; discovery
│   └── testkit/{unit.ts, integration.ts}   # in-memory harness; sidecar + Postgres via testcontainers
├── test/                                   # vitest
└── examples/shopping-cart/                 # the port; the conformance reference service

crd/src/main/scala/.../crd/AnkkaService.scala           # + hosting: String = "embedded", + processPort
operator/src/main/scala/.../operator/
├── Settings.scala                          # + sidecar image (pinned to the operator's version)
├── Rendering.scala                         # two containers for hosting=process; env, ports, probes (R8)
└── LifecycleRules.scala                    # readiness folds both containers
controlplane-api/src/main/scala/.../api/descriptors.scala
                                            # + hosting, + protocol; refuses ANKKA_PROCESS_*/ANKKA_SIDECAR_*
controlplane-api/src/main/scala/.../api/Compatibility.scala   # + supportsProtocol
controlplane/src/main/scala/.../deploy/ServiceProjector.scala # protocol check → Refused
cli/                                        # `services get` shows hosting and protocol
kustomization/components/operator/          # ANKKA_SIDECAR_IMAGE on the operator Deployment
docker-compose.yml                          # `sidecar` service under a `polyglot` profile
build.sbt, project/Dependencies.scala, project/plugins.sbt   # protocol, sidecar, sbt-protoc, images
.github/workflows/                          # sidecar image on tag; SDK tests
README.md, CLAUDE.md, docs/polyglot.md
```

**Structure Decision**: the protocol is its own sbt project so that it depends on nothing of
ankka's and nothing of ankka's published depends on it: the `.proto` files are the artifact an SDK
consumes, not a jar. The sidecar is an *application*, beside `operator` and `controlplane`, because
it needs `http` and `agent` and `runtime` must not; it is the thing that gets an image. The remote
descriptor family and the `Conversation` trait live in `runtime` so the existing hosts' sibling
implementations can share `EntityProtocol`, `Observability`, `Trace` and the projection and timer
runtimes without exposing any of them, on the same inversion that keeps `ComponentClient` in `sdk`
over a transport `runtime` supplies. The TypeScript SDK lives in this repository under `sdks/` for
the reason `ankka.g8` does: the conformance suite, the proto and the SDK must move together, and a
tag of this repository must be able to prove them consistent.

## Complexity Tracking

No constitution violations to justify. Three additions that could look like scope and are not:

| Addition | Why it is in this feature |
|---|---|
| A generic HTTP invoke route on the sidecar (R7) | there is no generic component-invoke surface today; endpoints are user-written route trees. Without one, a process-hosted service with no HTTP layer of its own is unreachable, and the local console's invoke panel has nothing to call. It is an `HttpEndpoint` in `sidecar`, not a change to the published `http` module |
| Agents over the protocol (P4) | the strongest product reason for the sidecar; the plan bounds it to what `AgentEffect` already expresses (system, user, context, tools by name, response shape, memory on or off, guardrails by name) and hosts the loop unchanged |
| The `ProcessDouble` in Scala | the only way to prove the conversation under passivation, restart and misbehaviour before an SDK exists, and afterwards the only way to test misbehaviour a well-written SDK cannot produce |
