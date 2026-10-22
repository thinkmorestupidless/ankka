# Implementation Plan: Tenancy and Replication Seams

**Branch**: `057-tenancy-and-replication-seams` | **Date**: 2026-10-22 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/057-tenancy-and-replication-seams/spec.md`, clarified
2026-10-22 (three decisions in its Clarifications section), planned against the code with the
findings in [research.md](research.md).

## Summary

Five small widenings the family's products asked for, in one release. In the control plane: a deploy
token created with `"project": "<id>"` is a member of that project only (a claim on the admitted
principal, applied in `Authorization`), and may make and end that project's grants through one new
check; a platform administrator's token creation and grant acceptance, which `Authorization.require`
already allows, are proven by tests and shown as administrative in the token list and the grant
views; `GET /installation` reports `broker`, `retention` and `erasure` beside `backups`, from the
topic policy the control plane already holds, one new variable `ANKKA_BROKER_NODES` the broker
kustomize components set, and erasure as not stated until spec 042. In the runtime: the
`ReplicationTransportProvider` is given the replicated components; a public `ReplicationPeer.reported`
delivers a handover's report; `Installations.using(factory)` runs the test kit's group over a
transport the test supplies; `ANKKA_INSTALLATIONS=a,b,c` names installations without a database; a
component call stamps the acting principal's subject as `ankka-attribution`, a forward carries it to
the home where `CommandContext` and the journal record hold it beside `forwardedFrom`, and a home
refuses a forwarder outside its installation list. The sidecar protocol becomes 1.22 with the two
fields on `Command` and `Event`, in every SDK.

## Technical Context

**Language/Version**: Scala 3.9.0 on JDK 21 in `controlplane`, `controlplane-api`, `cli`, `core`,
`runtime`, `sdk`, `http`, `testkit`, `sidecar`; protobuf in `protocol`; Python 3.12, TypeScript
on Node 22 and Rust 1.8x in `sdks/`. No new library.

**Primary Dependencies**: the existing `Authorization`, `DeployTokenIndex`, `TopicPolicy`,
`BackupViews`; `ReplicationRuntime`, `HostReplication`, `ReplicationSettings`, `Trace`'s
outgoing stamp; `GherkinSuite`, `ReplicationSteps`, `AuthorizationMatrixSuite`,
`InstallationRouteSuite`; the three SDKs' conformance suites.

**Storage**: the `deploy-token` journal gains `project` on `DeployTokenCreated` (an `Option`, absent
on old events); the services' journals gain `attribution` and `forwardedFrom` on `JournalRecord`,
`NON_ABSENT`. No DDL change; the schema stays additive.

**Testing**: munit everywhere. Control plane: `AuthorizationMatrixSuite` (scope and grants, the
administrator), `ControlPlaneHttpSuite` (the token routes and list), `DeployTokenIndexSuite`
(replay of `project`), `InstallationRouteSuite` and a new `InstallationFactsFeatures`
(`GherkinSuite("../features/installation/facts.feature")`), `CliEndToEndSuite` (the `--project`
flag), `OutputSuite`. Runtime: `ReplicationSteps` gains the six scenarios' steps (a recording
`TestProvider` transport for the three transport ones), `ReplicationSettingsSuite` for
`ANKKA_INSTALLATIONS`, `PlatformDeclarationSuite`, `JournalRecordCompatibilitySuite` unchanged and
green. Sidecar: `ProtocolSuite` and the conformance reference for 1.22; each SDK's own tests and
conformance. Every suite that already proves a token obeys every rule stays green unchanged: that
is the proof the scope changed nothing for an organization-wide token.

**Target Platform**: the control plane and operator in Kubernetes (kind and the cloud
installations), services on the JVM and behind the sidecar, the CLI on macOS and Linux.

**Project Type**: a platform change across the control plane, the runtime and the protocol; no new
module.

**Performance Goals**: none new. `GET /installation` adds three values read from configuration held
in memory; the authorization check adds one claim comparison; the metadata gains one entry per
attributed call.

**Constraints**: `PlatformVariables` imports nothing outside the standard library (the operator
compiles it); a new `ANKKA_` literal must be declared there or `PlatformDeclarationSuite` fails. A
new `JournalRecord` field is `NON_ABSENT` and the fixtures in `JournalRecordCompatibilitySuite` are
never regenerated. The `ReplicationTransportProvider` signature change is deliberate and named in
the release note; ankka-replication pins releases. No test binds a fixed port. The trace of a
forwarded command stays one call (`ReplicationSteps.scala:594-613` stays green). `GET
/installation` is readable by any authenticated caller, so nothing secret goes into the new
fields.

**Scale/Scope**: about twelve source files touched in the control plane and its wire types, eight
in the runtime, sdk and testkit, one `.proto` plus its copies and the three SDKs' contexts, four
docs pages, two kustomize components, one CLI flag, the generated CLI reference.

## Constitution Check

The repository's constitution is the template, unfilled; the family's principles apply. Effects
stay inert (no handler does I/O: the admission check is a rejection the host returns). Explicit
registration unchanged. The honest not-implemented list gains "erasure is reported as not stated
until spec 042" and "admission across organizations is ankka-replication's". Only a tag publishes.
No installation value enters the tree (the kustomize components set `1` and `3`, which size the
broker they ship).

## Project Structure

### Documentation (this feature)

```text
specs/057-tenancy-and-replication-seams/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── control-plane.md      # the token's project, projectGrants, administrative, GET /installation
│   ├── replication-seam.md   # the provider, ReplicationPeer, Installations.using, ANKKA_INSTALLATIONS, attribution
│   └── protocol.md           # sidecar protocol 1.22
└── tasks.md
```

### Source Code (repository root)

```text
controlplane-api/src/main/scala/.../controlplane/api/descriptors.scala   # CreateDeployToken.project, DeployTokenSummary, GrantAct/GrantChangeRecord.administrative, Installation.{broker,retention,erasure}
controlplane/src/main/scala/.../controlplane/
├── auth/Authorization.scala          # the project claim in project/projectOwner/requireMember; projectGrants
├── auth/DeployTokenIndex.scala       # Live.project, replayed
├── api/ControlPlaneAcl.scala         # the "project" claim on the admitted principal
├── api/OrganizationEndpoint.scala    # POST tokens with project; the list; projects narrowed for a scoped token
├── api/ProjectEndpoint.scala         # POST/DELETE grants through projectGrants
├── api/InstallationEndpoint.scala    # broker, retention, erasure
├── application/DeployTokenEntity.scala, DeployTokenRows.scala
├── domain/model.scala, events.scala  # DeployToken.project, DeployTokenCreated.project
├── tenancy/TopicPolicy.scala         # read as today; brokerNodes beside it (BrokerFacts)
└── ControlPlane.scala                # wiring
controlplane/src/test/scala/.../      # AuthorizationMatrixSuite, ControlPlaneHttpSuite, DeployTokenIndexSuite, InstallationRouteSuite, InstallationFactsFeatures, CliEndToEndSuite
cli/src/main/scala/.../cli/Main.scala, Output.scala, ControlPlaneClient.scala   # `ankka tokens create --project`, the list's PROJECT column, `ankka installation` shows the facts
modules/core/src/main/scala/.../core/PlatformVariables.scala                 # Installations, BrokerNodes
modules/runtime/src/main/scala/.../runtime/
├── Attribution.scala                 # the key, the thread-local field's accessors
├── Trace.scala                       # Working.attribution; the outgoing stamp
├── wire.scala                        # JournalRecord.attribution, forwardedFrom
├── EventSourcedEntityHost.scala      # CommandContext/EventContext fields; the record carries them
├── remote/RemoteEventSourcedHost.scala, remote/Conversation.scala   # the protocol fields; 1.22
└── replication/
    ├── ReplicationTransport.scala    # ReplicationTransportProvider.transport(.., replicated)
    ├── ReplicationPeer.scala         # reported(...)
    ├── ReplicationRuntime.scala      # passes the set; the refusal's hint
    ├── ReplicationSettings.scala     # ANKKA_INSTALLATIONS
    ├── HostReplication.scala         # admission at the home; attribution carried
    ├── DatabaseReplicationTransport.scala, Loopback.scala   # report through ReplicationPeer
modules/sdk/src/main/scala/.../sdk/contexts.scala              # CommandContext.attribution, forwardedFrom; EventContext likewise
modules/http/src/main/scala/.../http/HttpServer.scala          # enters the attribution with the request context
modules/testkit/src/main/scala/.../testkit/AnkkaTestKit.scala, TestReplicationTransport.scala   # Installations.using
modules/testkit/src/test/scala/.../testkit/replication/ReplicationSteps.scala, RecordingTransport.scala
protocol/src/main/protobuf/ankka/protocol/v1/event_sourced.proto, protocol/README.md
sidecar/src/main/scala/.../sidecar/Discovery.scala; sidecar/src/test/.../ProtocolSuite.scala, conformance/
sdks/python, sdks/typescript, sdks/rust   # proto copies, version constants, contexts, conformance
kustomization/components/broker/, broker-three-nodes/   # ANKKA_BROKER_NODES
docs/reference/control-plane-api.md, docs/concepts/tenancy-and-access.md, docs/platform/organizations.md,
docs/deploy/ci.md, docs/platform/broker.md, docs/reference/configuration.md,
docs/concepts/replicated-entities.md, docs/reference/sidecar-protocol.md, docs/reference/limitations.md
```

**Structure Decision**: no new module. The control plane half and the runtime half are
independent and are phased so either can merge first; the protocol bump is the runtime half's last
step so one version covers both fields.

## Complexity Tracking

No constitution violations to justify. The one deliberate incompatibility is
`ReplicationTransportProvider.transport`'s new parameter (R5), for a consumer that asked for it.

## Design notes for the tasks

1. **The token's project** (R1): add the field at the wire type, the command, the event, the state,
   the row, `Live` and the claim, in that order, with `DeployTokenIndexSuite` proving replay of an
   event without the field. Then `Authorization`: a private `scopedTo(principal): Option[String]`
   reads the claim; `project` and `projectOwner` throw `noSuchProject` when it is set and differs;
   the organization's project listing filters by it. `AuthorizationMatrixSuite` test 10 must stay
   green unchanged.
2. **`projectGrants`** (R2): owner or the scoped token; used by the two grantor routes only.
   Break it once: give the check to `accept` and watch the outline row for a deploy token go red.
3. **The administrator** (R3): tests first, since the behaviour exists; then `administrative` on
   `DeployTokenSummary`, `GrantAct` and `GrantChangeRecord`, from the `Actor` the events hold; then
   the docs' "owners only" lines.
4. **`GET /installation`** (R4): `InstallationBroker`, `InstallationRetention`,
   `InstallationErasure` in `controlplane-api`; a `BrokerFacts(nodes: Option[Int])` read from
   `ANKKA_BROKER_NODES` beside `TopicPolicy`; the endpoint takes both; erasure is `None` with a
   comment naming spec 042. `InstallationFactsFeatures` runs the two scenarios through system
   properties. The console schema and fixtures gain the optional fields; the CLI's `ankka
   installation` prints a `broker` and `retention` block.
5. **The provider** (R5) and **`ReplicationPeer`** (R6): change the trait, pass the set, write the
   object, route the runtime's own `report` paths through it. A `RecordingTransport` in the
   testkit's test sources, registered through `META-INF/services` on the test classpath beside
   `TestProvider`, records the set it was given and delivers reports through `ReplicationPeer`:
   that is the step implementation for the two transport scenarios.
6. **`Installations.using`** (R7): the group keeps its databases; `start` picks the factory;
   `transport.pause` on a `using` group throws naming the method.
7. **`ANKKA_INSTALLATIONS`** (R8): `PlatformVariables.Installations` first (the declaration
   suite), then `withEnvironment`, then the refusal hint; a settings suite proves the merge of the
   two forms.
8. **Attribution** (R9): `Attribution.scala` (the key, `within`, `current`); `Trace.Working` gains
   the field and the outgoing stamp writes it; `HttpServer` enters it from the request context's
   principal; the host reads `ankka-attribution` and `ankka.forwarded-from` into the contexts;
   `JournalRecord` gains the two `NON_ABSENT` fields and the host writes them from the command that
   persisted the event; `EventContext` exposes them. `JournalRecordCompatibilitySuite` must stay
   green with its fixtures untouched. The forwarding scenario's steps assert the token never
   reached the home by asserting no `authorization` key in the metadata the home's handler saw.
9. **Admission** (R10): in `HostReplication` where a forwarded invoke is recognised at the home;
   the error names both installations; `ForwardingFeatures` proves the distinction from
   "cannot be reached".
10. **Protocol 1.22** (R11): the `.proto`, `WireProtocol.Version`, `Discovery`'s changelog (1.21
    and 1.22), the copies and constants in the three SDKs, the contexts (`attribution`,
    `forwarded_from` / `forwardedFrom`), the conformance step in each language, `ProtocolSuite`,
    `docs/reference/sidecar-protocol.md`. `RemoteEventSourcedHost` fills the fields from the same
    metadata the JVM host reads.
11. **Docs and the reference** (R3, R4, R8, R9): the pages listed above; `just docs-reference` for
    the CLI flag; `just docs-sync`; the limitations bullets; the marketplace and template copies of
    the API page follow `docs-sync`.
12. **The sibling's request files** are theirs to close; the release note names the four answers
    and the provider signature change.
