# Tasks: Tenancy and Replication Seams

**Input**: `plan.md`, `research.md` (R1 to R12), `data-model.md`, `contracts/`, `spec.md` (FR-001
to FR-014, SC-001 to SC-004), the 14 scenarios 057 added to `features/`.

**Tests**: every scenario becomes a test that fails without the feature (`CLAUDE.md`, Testing). A
task that adds a behaviour names the test that proves it; tick the task only when that test has
run green on this branch and has been seen red once.

**Organization**: two independent halves. Phases 1 to 4 are the control plane (US1, US2, US5);
phases 5 to 8 the runtime, test kit and protocol (US3, US4). Either half may merge first. Phase 9 is
the docs and the release note.

## Phase 1: Setup

- [X] T001 Read `.claude/rules/control-plane.md`, `runtime.md`, `messaging.md`, `sidecar.md`,
  `testing.md` and `docs.md`; confirm `sbt controlPlane/compile testkit/compile sidecar/compile`
  and `just features` are green at the start (no file change)

## Phase 2: Foundational (control plane)

- [X] T002 `controlplane-api/.../descriptors.scala`: `CreateDeployToken.project: Option[String]`,
  `DeployTokenCreated.project`, `DeployTokenSummary.{project, administrative}`,
  `GrantAct.administrative`, `GrantChangeRecord.administrative`, `Installation.{broker, retention,
  erasure}` with `InstallationBroker`, `InstallationRetention`, `InstallationErasure`; codecs;
  `DescriptorSuite` cases that each new field is absent-tolerant both ways (an old reply without
  it, a new reply read by the old shape)
- [X] T003 `controlplane/.../domain/model.scala`, `events.scala`, `application/DeployTokenEntity.scala`,
  `DeployTokenRows.scala`: `DeployToken.project`, `DeployTokenCreated.project`, the row; `DeployTokenRules`
  refuses a project of another organization as `404 no such project`; `TenancyEntitySuite` cases
- [X] T004 `controlplane/.../auth/DeployTokenIndex.scala`: `Live.project`, replayed from the event;
  `ControlPlaneAcl.scala`: the `"project"` claim on the admitted principal; `DeployTokenIndexSuite`:
  an event without the field replays as organization-wide

## Phase 3: User Story 1, a deploy token for one project (P1)

**Goal**: FR-001 to FR-003. **Independent test**: the four `deploy-tokens.feature` scenarios as
munit tests; `AuthorizationMatrixSuite` test 10 unchanged and green.

- [X] T005 [US1] `controlplane/.../auth/Authorization.scala`: `scopedTo`; `project` and
  `projectOwner` throw `noSuchProject` for a scoped token on another project; `requireMember` on
  an organization route narrows the project listing (`OrganizationEndpoint`'s projects route
  filters by the scope); new `projectGrants(principal, projectId)` (R1, R2)
- [X] T006 [US1] `controlplane/.../api/OrganizationEndpoint.scala`: `POST .../tokens` validates
  and records `project`; the list carries `project` and `administrative`; `ProjectEndpoint.scala`:
  `POST /projects/{id}/grants` and `DELETE .../grants/{grantId}` use `projectGrants`
- [ ] T007 [US1] `controlplane/src/test/.../AuthorizationMatrixSuite.scala`: tests named "a deploy
  token scoped to one project acts in that project and is told there is no other", "a project-scoped
  deploy token makes and ends its project's grants", "a project-scoped deploy token may not do what
  only an owner may" (create a token, invite, accept: each 403); the matrix walks every route of
  the control plane API with a project token and asserts exactly its project's routes plus its two
  grant routes answer (SC-002); a grant it makes to another organization is pending; a token whose
  project was deleted is told "no such project" and stays listed until revoked;
  `ControlPlaneHttpSuite.scala`: "a deploy token's list says which project a token is scoped to"; break `projectGrants` into `accept`
  once and watch `CrossProjectAcceptanceFeatures`' outline row go red, then restore
- [ ] T008 [US1] `cli/.../Main.scala`, `ControlPlaneClient.scala`, `Output.scala`: `ankka tokens create
  --project <id>`; the list's `PROJECT` column and the administrative mark; `OutputSuite` and
  `CliEndToEndSuite` cases; `just docs-reference` (the CLI page)

## Phase 4: User Story 2, the platform administrator (P1), and User Story 5, the installation's facts (P2)

**Goal (US2)**: FR-004, FR-005. **Independent test**: the three `administrators.feature` scenarios
as munit tests. **Goal (US5)**: FR-013, FR-014. **Independent test**: `InstallationFactsFeatures`.

- [ ] T009 [US2] `controlplane/src/test/.../AuthorizationMatrixSuite.scala`: "a platform
  administrator creates a deploy token for an organization they are not a member of", "a platform
  administrator accepts a pending grant on an organization's behalf" (over `GrantsHarness` with a
  `platform-admin` token), "an organization's history shows an administrator's act under the role
  that allowed it" (the token list's and the received grant's `administrative: true`); a token or
  acceptance for an organization or grant that does not exist is 404 and records nothing (R3)
- [ ] T010 [US2] `controlplane/.../api/ProjectEndpoint.scala` and `OrganizationEndpoint.scala`:
  fill `GrantAct.administrative` and `GrantChangeRecord.administrative` from the events' actor;
  `DeployTokenSummary.administrative` from `createdBy`
- [ ] T011 [P] [US5] `modules/core/.../PlatformVariables.scala`: `BrokerNodes = "ANKKA_BROKER_NODES"`
  in `PlatformOnly`; `controlplane/.../tenancy/BrokerFacts.scala` (`nodes: Option[Int]` from
  `ankka.controlplane.broker.nodes = ${?ANKKA_BROKER_NODES}` in `reference.conf`);
  `PlatformDeclarationSuite` green (R4)
- [ ] T012 [US5] `controlplane/.../api/InstallationEndpoint.scala` and `ControlPlane.scala`: the
  endpoint takes `TopicPolicy` and `BrokerFacts`; fills `broker`, `retention`; `erasure = None` with
  a comment naming spec 042; `InstallationRouteSuite` cases for each field and for an unset node
  count
- [ ] T013 [US5] `controlplane/src/test/.../InstallationFactsFeatures.scala`:
  `GherkinSuite("../features/installation/facts.feature")` over `InstallationRouteSuite`'s harness,
  the configuration by system properties; both scenarios green, the first seen red with the fields
  left out
- [ ] T014 [P] [US5] `kustomization/components/broker/` and `broker-three-nodes/`: `ANKKA_BROKER_NODES`
  (`1`, `3`) beside the topic defaults; the k3s suite that reads `/installation`
  (`EndToEndClusterSuite` or the installation-models one) asserts `broker.nodes` is stated on kind
- [ ] T015 [P] [US5] `cli/.../Output.scala`, `Main.scala`: `ankka installation` prints `broker` and
  `retention`, `not stated` for an absent value; `OutputSuite` and `InstallationCommandSuite` cases;
  `console/package/src/client/schemas.ts` (optional fields), `fixtures/control-plane/Installation.json`,
  `src/testing/fake-control-plane.ts`, the front page shows the broker line; `just test-console`

**Checkpoint A**: `sbt controlPlane/test` (without the k3s suites) and `just test-console` green;
the control plane half may be reviewed on its own.

## Phase 5: Foundational (runtime)

- [ ] T016 `modules/core/.../PlatformVariables.scala`: `Installations = "ANKKA_INSTALLATIONS"` in
  `PlatformOnly`; `PlatformDeclarationSuite` green (R8)
- [ ] T017 `modules/runtime/.../wire.scala`: `JournalRecord.attribution` and `forwardedFrom`,
  `@JsonInclude(NON_ABSENT)`; `JournalRecordCompatibilitySuite` green with fixtures untouched; one
  case that a record with both round-trips (R9)
- [ ] T018 `modules/sdk/.../contexts.scala`: `CommandContext.{attribution, forwardedFrom}`,
  `EventContext.{attribution, forwardedFrom}`, defaults `None`; `modules/runtime/.../Attribution.scala`:
  `MetadataKey = "ankka-attribution"`, `within(subject)(body)`, `current`; `Trace.Working` gains
  the field and the outgoing stamp at `Trace.scala:~265` writes it when present

## Phase 6: User Story 3, a transport beside the runtime's own (P1)

**Goal**: FR-006 to FR-009. **Independent test**: `TransportFeatures` and `TestkitFeatures` with
the four new scenarios; `ReplicationSettingsSuite`.

- [ ] T019 [US3] `modules/runtime/.../replication/ReplicationTransport.scala`: `ReplicationTransportProvider.transport(system, config, settings, replicated)`;
  `ReplicationRuntime.resolve` passes the set; the runtime's own test `TestProvider` updated (R5)
- [ ] T020 [US3] `modules/runtime/.../replication/ReplicationPeer.scala`: `reported(...)`;
  `DatabaseReplicationTransport.report` and `LoopbackRoutes`' report route call it;
  `HandoverFeatures` unchanged and green (R6)
- [ ] T021 [US3] `modules/runtime/.../replication/ReplicationSettings.scala`: `ANKKA_INSTALLATIONS`
  in `withEnvironment`, merged with the database form; `ReplicationRuntime.scala`'s refusal hint
  names both forms; `ReplicationSettingsSuite`: the list alone, the list plus a database for one
  name, a name that is not a DNS label refused (R8)
- [ ] T022 [US3] `modules/testkit/.../AnkkaTestKit.scala`, `TestReplicationTransport.scala`:
  `Installations.using(factory)`; `start`/`restart` wire the supplied factory; the switches throw
  on a `using` group naming `using` (R7)
- [ ] T023 [US3] `modules/testkit/src/test/.../replication/RecordingTransport.scala`: a transport
  built on the runtime's own that records the components it was given (an empty set for a service
  with none declared) and delivers reports through
  `ReplicationPeer.reported`; registered as a provider on the test classpath beside `TestProvider`
  (`META-INF/services`); its factory for `using`
- [ ] T024 [US3] `modules/testkit/src/test/.../replication/ReplicationSteps.scala`: steps for "a
  transport beside the runtime's own is given the replicated components it serves", "... delivers a
  handover's report to the local copy", "a service names an installation it reaches through a
  transport beside the runtime's own by its name alone" (the service started with
  `ANKKA_INSTALLATIONS` through the settings override and `RecordingTransport` on the classpath),
  "the test kit starts several installations over a transport the test supplies"; each seen red
  once (the set not passed; the report dropped; the variable ignored; the factory ignored)

**Checkpoint B**: `sbt 'testkit/testOnly *TransportFeatures *TestkitFeatures'` lists 6 and 3 headers,
all green.

## Phase 7: User Story 4, who the home sees on a forwarded write (P2)

**Goal**: FR-010 to FR-012. **Independent test**: `ForwardingFeatures` with its two new scenarios.

- [ ] T025 [US4] `modules/http/.../HttpServer.scala`: enter `Attribution.within(principal.subject)`
  around a handler whose `RequestContext` holds a principal, in the wrapper that enters the trace;
  an `HttpServer` suite case that a component call from an authenticated handler carries
  `ankka-attribution` and one from an unauthenticated handler does not (R9)
- [ ] T026 [US4] `modules/runtime/.../EventSourcedEntityHost.scala`: `CommandContext.attribution`
  and `forwardedFrom` from the metadata; the records a command persists carry both;
  `EventContext` reads them from the record; `HostReplication.forward` unchanged (metadata
  carried as is); a host suite case
- [ ] T027 [US4] `modules/runtime/.../replication/HostReplication.scala`: admission at the home
  (`ankka.forwarded-from` not in `settings.installations` → `Rejected(... not admitted ...)`,
  `Forbidden`), before the handler; the forwarder returns the rejection unchanged (R10)
- [ ] T028 [US4] `ReplicationSteps.scala`: steps for "a forwarded write reaches the home as a call
  by the forwarding installation, with the caller who acted as its attribution" (a person's call
  through an endpoint at the follower; the handler's context at the home; no `authorization` key
  in the metadata the home saw; the record carrying both) and "a forwarded write from an
  installation the home does not admit is refused naming the installation" (`vienna` absent from
  `leipzig`'s list; the error distinct from the unreachable one); the trace assertion at
  `ReplicationSteps.scala:594-613` stays green

**Checkpoint C**: `sbt 'testkit/testOnly *ForwardingFeatures'` 8 headers green.

## Phase 8: Protocol 1.22 (US4 in every language; SC-003)

- [ ] T029 `protocol/src/main/protobuf/ankka/protocol/v1/event_sourced.proto`: `Command.attribution = 8`,
  `Command.forwarded_from = 9`, `Event.attribution = 4`, `Event.forwarded_from = 5`;
  `protocol/README.md`; `modules/runtime/.../remote/Conversation.scala` `WireProtocol.Version = "1.22"`;
  `sidecar/.../Discovery.scala` changelog lines for 1.21 and 1.22;
  `remote/RemoteEventSourcedHost.scala` fills and reads the fields; `sidecar` `ProtocolSuite` (R11)
- [ ] T030 `sidecar/src/test/.../conformance/ConformanceReference.scala`, `ConformanceSuite`: the
  case "a command carrying attribution and where it was forwarded from persists a record carrying
  both"; `sbt 'sidecar/testOnly *ConformanceSuite'` green
- [ ] T031 [P] `sdks/python`: proto copy, `service.py` version, `attribution`/`forwarded_from` on
  the command and event contexts, `_conformance.py` case, docs string; `uv run pytest -q && uv run
  mypy && uv run conformance`
- [ ] T032 [P] `sdks/typescript`: proto copy, `spec.ts` version, `attribution`/`forwardedFrom` on
  the contexts, `bin/conformance.ts` case; `npm run proto && npm run typecheck && npm test && npm
  run conformance`
- [ ] T033 [P] `sdks/rust`: proto copy, `service.rs` version, `attribution()`/`forwarded_from()`
  on the contexts, `tests/contracts_discovery.rs`, the conformance case; `cargo test --workspace &&
  ./conformance.sh`
- [ ] T034 `features/replication/languages.feature`'s protocol scenario covers 1.22 through
  `ranElsewhere` (`LanguagesFeatures`); `sbt 'testkit/testOnly *LanguagesFeatures'`

**Checkpoint D**: `sbt sidecar/test` and the three SDK runs green.

## Phase 9: Docs, limitations, release note

- [ ] T035 [P] `docs/reference/control-plane-api.md`: `project` on tokens, the scoped token's table
  (contracts/control-plane.md), `projectGrants`' routes, the administrator on `tokens` and
  `accept`, `administrative` on the views, `GET /installation`'s three objects;
  `docs/concepts/tenancy-and-access.md`, `docs/platform/organizations.md`, `docs/deploy/ci.md`
  (`--project`), `docs/platform/broker.md` and `docs/reference/configuration.md`
  (`ANKKA_BROKER_NODES`, `ANKKA_INSTALLATIONS`)
- [ ] T036 [P] `docs/concepts/replicated-entities.md`: a transport beside the runtime's own (the
  provider's set, `ReplicationPeer`, `Installations.using`, `ANKKA_INSTALLATIONS`), the forwarded
  write's caller and attribution, admission at the home; `docs/reference/sidecar-protocol.md` 1.22
- [ ] T037 `docs/reference/limitations.md`: erasure reported as not stated until spec 042;
  admission across organizations is ankka-replication's; `just docs-sync && just docs` green;
  `git diff --exit-code` after `just docs-reference` (the pages are committed)
- [ ] T038 `CHANGELOG.md` or the release notes file the repository keeps: the four answered requests
  by repository and number, the provider signature change, protocol 1.22; `.claude/rules/runtime.md`
  gains the attribution key and the admission rule as one line each; `just features`, `sbt
  -Dankka.cluster.tests=off test` green; `gh workflow run cluster --ref 057-tenancy-and-replication-seams`
  requested and its result in the pull request

## Dependencies

- Phases 2 to 4 depend on nothing outside the control plane; phases 5 to 8 on nothing outside the
  runtime, testkit, protocol and SDKs. The two halves share only `PlatformVariables` (T011, T016,
  different constants) and the docs phase.
- T005 after T004; T007 after T006; T013 after T012; T015 after T012.
- T019 and T020 before T023; T021 and T022 before T024.
- T017 and T018 before T025 to T028; T028 after T027.
- T029 after T026 (the host reads the same metadata); T030 after T029; T031 to T033 after T029;
  T034 after T030.
- Phase 9 last; T037 after T035 and T036.

## Parallel opportunities

- T011, T014, T015 beside the rest of phase 4 once T012 is in.
- T031, T032, T033 in parallel after T029.
- T035 and T036 in parallel.

## Implementation strategy

Deliver checkpoint A first (US1, US2, US5: the vendor's three requests) and checkpoint B second
(ankka-replication's four): each is a reviewable half. US4 and the protocol follow. A worker given
phases 1 to 4 and another phases 5 to 9 could run side by side on two branches off this one; the
plan names the files so they do not overlap except `PlatformVariables`.
