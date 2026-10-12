# Implementation Plan: Replicated Entities — One Home, Mirrored Everywhere Else

**Branch**: `051-replicated-entities` | **Date**: 2026-10-12 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/051-replicated-entities/spec.md`, clarified 2026-10-12
(five decisions in its Clarifications section).

## Summary

A replicated entity is an ordinary event sourced entity whose journal is held at several
installations under the same persistence id. One installation is its home and handles every
persisting command; each follower pulls the home's journal from its own last sequence number and
persists each record unchanged, so every copy's journal is identical and serves reads locally. The
home, the replication set and a pending handover are platform records in the entity's own journal
(kinds 3 to 6) folded into the host's `Stored` state; a copy's mode is derived from them on every
command. A persisting command at a copy that is not the home is forwarded to the home through a
`ReplicationTransport` seam the runtime defines and discovers through `ServiceLoader`; the runtime
ships one implementation, database backed and in process, for the test kit and two services on one
machine, and refuses to start a service that names installations with no transport. Pekko's
replicated event sourcing is not used: the spike in this branch shows it applying a third copy's
events out of causal order after a home move, with the concurrent flag false throughout and the
follower's journal keeping the arrival order. Views and consumers read the local journal; a
producing or graph consumer acts on local changes only unless it declares otherwise. The protocol
gains a declaration flag and two context fields at the next free minor; the Python, TypeScript and
Rust SDKs follow. `features/replication/` runs whole against the Scala test kit's new
`installations` group, the languages rows through the SDK kits, and four conformance cases pin every
reference.

## Technical Context

**Language/Version**: Scala 3 on Apache Pekko 1.7.0 (`core`, `sdk`, `runtime`, `testkit`,
`sidecar`, `controlplane-api`, `operator` for `PlatformVariables`); Python 3 (`sdks/python`, uv);
TypeScript under Node type stripping (`sdks/typescript`); Rust for `wasm32-unknown-unknown`
(`sdks/rust`). No new library anywhere: pekko-persistence-r2dbc 1.2.0's `eventsByPersistenceId`
and `eventsBySlices` and its per-plugin `use-connection-factory` are what the runtime's own
transport uses (research R6).

**Primary Dependencies**: Pekko cluster sharding and persistence typed (the host and its internal
commands), pekko-persistence-r2dbc (the runtime's own transport reads sibling journals through a
query plugin per sibling), pekko-projection (the wake feed's sharded daemon process), the JDK's HTTP
server already behind `ObservabilityEndpoint` (the two loopback routes for two local processes),
grpc-java and ScalaPB (the protocol fields), Jackson CBOR (the record fields, with the inclusion
annotation `WorkflowRecord.standing` taught us).

**Storage**: the journal gains a nullable field and four mirrored kinds plus one local kind; the
snapshot gains four nullable fields. No DDL, no table: the r2dbc schema's `event_journal` holds the
record as CBOR in `event_payload` as it does today. The runtime's own transport keeps the wake
feed's offsets in the existing projection offset store.

**Testing**: munit everywhere; `GherkinSuite` over `features/replication/` from `testkit`'s tests,
one `ReplicationSteps` abstract suite and one final class per feature file; `AnkkaTestKit.installations` for
every multi-installation scenario; pytest, the Node runner and `conformance.sh` for the SDK rows;
`ConformanceSuite` gains three cases; `JournalRecordCompatibilitySuite` pins the bytes;
`ReplicatedEntitiesDocumentationSuite` for the documentation feature; `ReplicationOrderSpike` stays
as evidence.

**Target Platform**: the JVM runtime and the sidecar image. Locally: two services on one machine
through the runtime's own transport. On Kubernetes: one installation per service, with the
cross-installation transport supplied by `ankka-replication`, outside this repository; a Kubernetes
service naming installations with no provider refuses to start.

**Project Type**: platform: runtime, SDKs in four languages, protocol, test kits, docs and skills.

**Performance Goals**: SC-002, a read at a follower makes no call to the home and a write makes one
forwarded call; SC-003, a change visible in a follower's view within two seconds of the home's view
on the runtime's own transport (poll interval 1s; the view's own projection latency is the same at
both); SC-005, an unreplicated entity's journal
bytes unchanged and every existing suite's time unchanged (`installations.isEmpty` short-circuits
every check).

**Constraints**: a command is sent once and a query is resent within one ask (`docs/reference/limitations.md`);
a forwarded command inherits the first rule, which is why the transport's `forward` does not retry.
"Never touch `ActorContext` from a `Future` callback": every transport answer re-enters the actor
through `pipeToSelf` as a module command. A behaviour an older runtime must refuse is a declaration
checked at discovery, not a new field on an event (`sidecar.md`). A new test switch is forwarded in
`Test / javaOptions`; this feature adds none. No test binds a fixed port: the loopback routes are on
the observability endpoint's ephemeral port. The `ankka.replication` keys are per service, and the
two variables are platform-only and runtime-only in `PlatformVariables`, which the operator compiles
unchanged.

**Scale/Scope**: five record kinds, four stored fields, four module commands, two reserved methods,
one trait and one provider, one runtime transport with two loopback routes, three SDK effects, two
context members, one consumer declaration, one new error code (two if `Conflict` is absent), four
protocol fields and two flags, four conformance cases, ten feature files already written, about
eight documentation pages and three skills.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template: no principles are declared, so no gate
derives from it. The repository's standing rules (`CLAUDE.md`, `.claude/rules/runtime.md`,
`messaging.md`, `sidecar.md`, `wasm.md`, `observability.md`, `cluster-and-tls.md`, `docs.md`,
`testing.md`) and the family's `principles.md` are the gates:

| Gate | Pre-research | Post-design |
|---|---|---|
| Effects are inert; the runtime interprets them | pass: `handOverTo`, `replicateTo`, `stopReplicatingTo` are values on `PersistBuilder`, reduced by `materialise` for the pure kit and by the host (R8) | pass |
| Wire names are declared separately and never derived | pass: `ankka:replication` and `ankka:handover-status` are reserved names under the prefix `Companion.descriptor` refuses | pass |
| `runtime` does not see `http` or `agent`; `sdk` carries no Pekko | n/a | pass: the seam is in `runtime` on runtime types; the SDK gains only values and context members (R6) |
| Explicit registration, never scanning | n/a | pass: the transport is a `ServiceLoader` provider, the one pattern the tree has for a thing a service does not hand over (`RuntimeExtensionProvider`); components are still handed over |
| No BSL on the path | pass: Pekko's own persistence and r2dbc; Akka's gRPC replication explicitly not reached for (R1) | pass |
| Adopt, then diverge with reasons | n/a | pass: Akka's replicated reads with a moving primary and filters is the model; replicated writes is not offered; the divergences page says so (R15) |
| Split by lifecycle and trust | pass: the seam is the boundary; `ankka-replication` needs no `private[ankka]` hook (contracts/transport-seam.md) | pass |
| Which variables are the platform's is said once, in `core` | n/a | pass: `ANKKA_INSTALLATION` in `PlatformOnly`, `ANKKA_INSTALLATION_` in `RuntimeOnlyPrefixes`; `PlatformDeclarationSuite` holds it (R9) |
| Jackson writes an absent `Option` as `null` | n/a | pass: every new record field is `NON_ABSENT`; fixtures pinned before the fields exist (R3, R4) |
| A behaviour an older runtime must refuse is a declaration, refused at discovery | n/a | pass: `replicated` on `EventSourcedDetail` is refused below the minor; the event and command fields carry information only (R13) |
| Every host handles unexpected module commands explicitly | n/a | pass: four new `ModuleCommand`s are listed in `runtime-wire.md` for every other host's `unhandled` |
| Work handed to another thread cannot see a thread-local | n/a | pass: the forwarding host captures the caller's metadata in `ForwardedCall` on the actor's thread; the home reads the trace from it |
| A refusal is not a failure | n/a | pass: `NotHome` and `Conflict` are `Refused` in the recorder; a transport failure on forward is `unanswered` at the forwarder, never `handled` |
| Every acceptance scenario ends as a test that fails without the feature | n/a | pass: quickstart lists the suite and the red condition per file |
| Could this check pass while the thing it checks is false? | | "same history" compares bytes at the same sequence numbers, not counts; "publishes once" counts at the broker, not at the consumer; "refuses to start" asserts the message names the transport; "no call crossed" reads the topology's observed calls |

## Project Structure

### Documentation (this feature)

```text
specs/051-replicated-entities/
├── plan.md              # this file
├── research.md          # R1–R15: the decisions and what was rejected
├── data-model.md        # records, Stored, snapshot, module commands, settings, errors, transitions
├── quickstart.md        # how to prove each feature file green, and what red looks like
├── contracts/
│   ├── scala-sdk.md     # replicated, the three effects, contexts, mirroredChanges, errors, test kits
│   ├── transport-seam.md# ReplicationTransport, its provider, the runtime's own implementation
│   ├── runtime-wire.md  # records, module commands, reserved methods, forwarding, refusals, loopback routes
│   ├── protocol.md      # discovery, event_sourced and consumer fields; the minor
│   └── polyglot-sdks.md # Python, TypeScript and Rust surfaces; conformance cases
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
modules/core/src/main/scala/com/thinkmorestupidless/ankka/core/
├── CommandError.scala                 # ErrorCode.NotHome (and Conflict if absent)
├── ComponentDescriptor.scala          # ComponentDescriptor.replicated (false by default)
├── PlatformVariables.scala            # ANKKA_INSTALLATION platform-only; ANKKA_INSTALLATION_ runtime-only
└── effect/EventSourcedEffect.scala    # ReplicationChange on Persisting; PersistBuilder.handOverTo/replicateTo/stopReplicatingTo;
                                       # Materialised.replication; materialise reduces it

modules/sdk/src/main/scala/com/thinkmorestupidless/ankka/sdk/
├── EventSourcedEntity.scala           # Companion.replicated; descriptor carries it; eventContext; _setEventContext
├── contexts.scala                     # CommandContext.installation/isHome; EventContext; SimpleEventContext
├── ChangeSource.scala                 # ChangeContext.installation; SimpleChangeContext
├── Consumer.scala                     # Companion.mirroredChanges; ConsumerDescriptor.mirroredChanges
├── ComponentClient.scala              # EventSourcedCalls.replication (the reserved query), handoverStatus loop
└── ReplicationStatus.scala            # the reserved query's answer

modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/
├── wire.scala                         # JournalRecord.installation + kinds 3–7; StateRecord/RemoteStateRecord fields;
│                                      # Replicate, Wake, Reported, Forwarded module commands
├── EventSourcedEntityHost.scala       # Stored fields; Journaled cases; mode; forwarding; puller; handover; conflict
├── replication/
│   ├── ReplicationTransport.scala     # the seam, its values, ReplicationTransportProvider
│   ├── ReplicationSettings.scala      # ankka.replication.* read and checked
│   ├── Replication.scala              # settings + transport + self, handed to the hosts
│   ├── Mode.scala                     # Unreplicated | Unknown | Home | Follower | Outside, derived from Stored; shared by both hosts
│   ├── HostReplication.scala          # the mode gate and the forward, over a HostView both hosts implement
│   ├── LocalInstallations.scala       # JVM-wide registry of started installations, for the in-JVM forward
│   ├── ReplicationRuntime.scala       # the wake daemon over `advanced`; stops the transport
│   ├── Puller.scala                   # one follower's stream: records → Replicate, restart with backoff, switch on kind 3
│   ├── Handover.scala                 # the chase and the hold: Reported → kind 3; timeout → kind 6
│   ├── SetChange.scala                # replicateTo / stopReplicatingTo reduced at the home → kind 4
│   └── DatabaseReplicationTransport.scala  # the runtime's own: per-sibling query plugins, locate, in-JVM and loopback forward
├── ChangeReader.scala                 # kinds 3–7 are Skip; Changed carries installation
├── ProjectionSupport.scala            # localOrigin from the record and self
├── ProjectionRuntime.scala            # the three sites; skip non-local for producing/graph consumers unless mirroredChanges
├── ObservabilityEndpoint.scala        # /replication/forward and /replication/report, local mode only
├── Ankka.scala                        # ServiceBuilder resolves the transport; ComponentRegistry refuses replicated on other kinds
├── remote/RemoteEventSourcedHost.scala# folds kinds 3–7; sends installation/at_home; forwards as the in-process host does
└── remote/RemoteProjection.scala      # ankka.installation and ankka.local on consumer requests
modules/runtime/src/main/resources/reference.conf   # ankka.replication.{installation,installations,poll,handover.*}

modules/testkit/src/main/scala/com/thinkmorestupidless/ankka/testkit/
├── AnkkaTestKit.scala                 # installations(names*) group handle: start(name, …), transport pause/resume/unreachable
├── TestReplicationTransport.scala     # the runtime's own transport over the kits' databases, in-JVM forward
├── EventSourcedTestKit.scala          # Mode.Home | Mode.Follower(home); NotHome on a persisting command
└── ConsumerTestKit.scala              # a change's installation and localOrigin
modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/
├── replication/ReplicationSteps.scala # the step definitions; one final class per feature file
├── JournalRecordCompatibilitySuite.scala
├── ReplicatedEntitiesDocumentationSuite.scala
└── ReplicationOrderSpike.scala        # unchanged; the evidence
modules/testkit/src/test/resources/journal/{journal-record.txt,state-record.txt}

controlplane-api/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api/
└── descriptors.scala                  # ServiceSpec.problems: the two variables refused as platform-only

protocol/src/main/protobuf/ankka/protocol/v1/
├── discovery.proto                    # EventSourcedDetail.replicated; ConsumerDetail.mirrored_changes
├── event_sourced.proto                # Event.installation; Command.installation, at_home
└── consumer.proto                     # (metadata entries, documented)
sidecar/src/main/scala/com/thinkmorestupidless/ankka/sidecar/
├── Discovery.scala                    # the minor; refuse replicated below it
├── GrpcConversation.scala             # the event and command fields
└── Translate.scala                    # mirrored_changes → ConsumerDescriptor

sdks/python/src/ankka/{event_sourced_entity.py,effects/,context.py,consumer.py,server.py}
sdks/typescript/src/{eventSourcedEntity.ts,effects/,context.ts,consumer.ts,server/}
sdks/rust/ankka/src/{components/,effects/,context.rs,abi/}

docs/concepts/replicated-entities.md   # new
docs/build/event-sourced-entities.md   # a section; docs/build/consumers.md: mirroredChanges
docs/reference/{limitations.md,akka-divergences.md,configuration.md,error-codes.md,sidecar-protocol.md,scala-sdk.md,python-sdk.md,typescript-sdk.md,rust-sdk.md}
tools/docs/skill/{ankka-entities,ankka-design,ankka-views-consumers}/
```

**Structure Decision**: the seam and the host changes are in `runtime`, where every host already
lives and where the private half's pin lands; the SDK gains values and contexts only; the runtime's
own transport is one file in a `replication` package so the private transport has a single sibling
to model on. No new sbt module: a module for the seam would carry `runtime`'s types anyway, and
`ankka-replication` depends on `ankka-runtime` as `ankka-contrib` depends on `ankka-sdk`.

## Complexity Tracking

No constitution violations to justify. Two choices that look like extra machinery, and why they are
not:

| Choice | Why | Simpler alternative rejected because |
|---|---|---|
| Platform records in the journal for home, set and handover (R3) | they are the only facts every copy is guaranteed to share, and they make a follower's mode change exactly when it persists the record | a side table is a second truth, not mirrored, and a snapshot-only mode leaves a follower without a snapshot blind |
| Two loopback routes on the observability endpoint for the runtime's own transport (R6) | two services on one machine have no other door the runtime owns without `ankka-http` | forwarding through `HttpServiceClients` needs a route on a server a service may not have; a second port is a fixed port a test cannot bind beside the documented workflow |
