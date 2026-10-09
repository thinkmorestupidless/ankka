# Tasks: Cross-Project Access — A Project Grants Named Routes and Topics to Named Callers

**Input**: Design documents from `/specs/040-cross-project-access/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature. The scenarios are in `features/cross-project/`; where a task
says "case", it means a `test(...)` in the named suite, named for the scenario it holds; where it
says "steps", it means the Gherkin step definitions a `GherkinSuite` runs the feature file through.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a service calls a route another project granted it), US2 (a service consumes
  or produces another project's topic, and the broker enforces it), US3 (a machine outside the
  installation calls a granted route with a token), US4 (members see who may reach their project
  and what their project may reach), US5 (an affiliate platform outside ankka consumes a brand's
  attribution topic), US6 (a grant to another organization takes effect only when accepted)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `CORE`/`CORET` =
`modules/core/src/{main,test}/scala/…/core`; `SDK` = `modules/sdk/src/main/scala/…/sdk`; `RT`/`RTT`
= `modules/runtime/src/{main,test}/scala/…/runtime`; `H`/`HT` = `modules/http/src/{main,test}/scala/…/http`;
`G`/`GT` = `modules/grpc/src/{main,test}/scala/…/grpc`; `AO`/`AOT` =
`modules/auth-oidc/src/{main,test}/scala/…/auth/oidc`; `TK`/`TKT` =
`modules/testkit/src/{main,test}/scala/…/testkit`; `SC`/`SCT` = `sidecar/src/{main,test}/scala/…/sidecar`;
`OP`/`OPT` = `operator/src/{main,test}/scala/…/operator`; `CRD` = `crd/src/main/scala/…/crd`;
`API`/`APIT` = `controlplane-api/src/{main,test}/scala/…/controlplane/api`; `CP`/`CPT` =
`controlplane/src/{main,test}/scala/…/controlplane`; `CLI`/`CLIT` = `cli/src/{main,test}/scala/…/cli`;
`PY` = `sdks/python/src/ankka`; `TS` = `sdks/typescript/src`; `RS` = `sdks/rust/ankka/src`; `CON` =
`console/package/src`; `K` = `kustomization`; `F` = `features/cross-project`; `SAMPLE` =
`samples/shopping-cart/src/main/scala/…/samples/shoppingcart`.

Offline runs always pass `-Dankka.cluster.tests=off`. A k3s run is `caffeinate -i sbt 'set
controlPlane / Test / logBuffered := false' …`, so a scenario reports as it ends; the four k3s
suites are plain `class`es so `.github/cluster-suites.py` lists them for the `cluster` workflow.

## Phase 1: Setup

- [X] T001 `CORE/PlatformVariables.scala`: add `MachineIssuer = "ANKKA_MACHINE_ISSUER"`, `MachineJwksUrl`, `MachineJwksCa`, `ProjectGrants = "ANKKA_PROJECT_GRANTS"` to `PlatformOnly`; add `ANKKA_MACHINE_PRODUCE_BYTES`, `ANKKA_MACHINE_CONSUME_BYTES`, `ANKKA_MACHINE_REQUEST_PERCENTAGE`, `ANKKA_MACHINE_BYTE_RATE_CEILING`, `ANKKA_MACHINE_TOKEN_RATE`, `ANKKA_BROKER_EXTERNAL_BOOTSTRAP` as named vals (platform settings, never rendered to a service); `CORET/PlatformVariablesSuite.scala` pins the new count of `PlatformOnly`; `CPT/PlatformDeclarationSuite.scala` still passes (no second list anywhere)
- [X] T002 [P] `API/descriptors.scala`: `ProjectId.Reserved += "machine"` with `reservedBecause` naming the broker; `OP/Names.scala` `ReservedProjectIds += "machine"`; `CPT/ReservedProjectIdsSuite.scala` passes; the upgrade note (T092) says a project named `machine` is refused on upgrade
- [X] T003 [P] Protocol 1.15: `protocol/src/main/protobuf/ankka/protocol/v1/discovery.proto` `CallerMatcher.kind += Empty granted = 5`, `Source.Topic += optional string project`, `Publication += optional string project`; `endpoint.proto` `Caller.kind += MachineCaller machine = 4 { string organization = 1; string name = 2; }`; `protocol/README.md` version section states 1.15 and what it adds; `WireProtocol.Version` in `RT/remote/Conversation.scala`, `Protocol.version` in `API/Compatibility.scala` (doc comment lists the minor), `PY/service.py`, `TS/spec.ts`, `RS/service.rs`, `SCT/ProtocolSuite.scala` to 1.15; copy the protocol into the three SDKs with their scripts (`sdks/python/scripts/proto.py`, `npm run proto`, `sdks/rust/scripts/proto.sh`) and run their generation
- [X] T004 [P] `K/kind.yaml`: a third `extraPortMappings` entry `30094 → 9094` with a comment that an existing cluster must be recreated to reach an exposed broker from the host; `K/components/gateway/envoyproxy.yaml` is **not** changed here (T073 does, in the component)

## Phase 2: Foundational

Shared by every story: the grant's vocabulary, the grant on the `Project`, the record on the
grantee side, the routes and CLI that make and end a grant, and the projection of accepted grants
onto `AnkkaProject` and into `grants.json`.

- [X] T005 [P] `APIT/GrantRulesSuite.scala`: `Grantee.parse` accepts `service:p/n` and `machine:o/n` and refuses anything else; `GrantTarget` text forms round-trip; `GrantRules.problems` refuses a bad project id, a bad service or machine name, a method outside the HTTP set, a path not `/`-rooted, a topic name outside the rule, `decrypt` with `produce`; a message per refusal as `contracts/grants.md` lists them
- [X] T006 `API/Grants.scala`: `Grantee`, `GrantTarget`, `TopicRight`, `GrantState`, `GrantChange`, their text forms and `GrantRules.problems`; `API/descriptors.scala`: the wire types `GrantRequest`, `GrantDetail`, `ReceivedGrantDetail`, `TopicSettings`, each with a `given JsonValueCodec` in `Wire` (`GrantState` and `GrantChange` with an explicit string codec in their companions); `APIT/ControlPlaneFixturesSuite.scala` gains a `fixture(...)` for each and writes `console/package/fixtures/control-plane/*.json` under `-Dankka.docs.update=true`
- [X] T007 [P] `CPT/EventCompatibilitySuite.scala`: pin the wire form of `GrantMade` (service and machine grantee, every target kind, `pending` true and false), `GrantAccepted`, `GrantDeclined`, `GrantWithdrawn`, `GrantRevoked`, `GrantRelinquished`, `GrantLapsed`, `GrantRecorded`; assert no event's JSON holds a `secret` or `token` field; a pre-040 `Project` state decodes with `grants` and `received` empty
- [X] T008 [P] `CPT/GrantEntitySuite.scala` (`EventSourcedTestKit` over `ProjectEntity`): `make-grant` for a declared topic within the organization starts `accepted`; across organizations starts `pending`; a second `make-grant` for a live pair replies the same id and persists nothing; after `revoke-grant` a third makes a new id; every transition in `data-model.md`'s table, and every other one is `Conflict`; an undeclared topic is `NotFound` naming it; a grantee service of the project itself is refused; `decrypt` on `produce` is refused; `lapse-grant` from `pending` and from `accepted`
- [X] T009 `CP/domain/model.scala`: `Grant`, `ReceivedGrant`, `Project.grants`, `Project.received`, `Organization.received`, the folds; `CP/domain/events.scala`: the seven grant events and `GrantRecorded` with `actor`/`at` defaulting to `None`; `CP/application/ProjectEntity.scala`: commands `make-grant`, `withdraw-grant`, `revoke-grant`, `accept-grant`, `decline-grant`, `relinquish-grant`, `lapse-grant`, `record-grant-change`, queries `grants`, `grant`, `received-grants`; `CP/application/OrganizationEntity.scala`: `record-grant-change` and `received-grants`
- [X] T010 [P] `CPT/GrantMirrorSuite.scala` (`ConsumerTestKit`): a `GrantMade(pending)` naming `machine:affiliates/network` records `offered` on organization `affiliates`; one naming `service:payments/merchant` records `made` on project `payments`; each later event records its change; a redelivered event is ignored after the entity answered `Conflict`
- [X] T011 `CP/application/GrantMirror.scala`: `Consumer[ProjectEvent, Nothing]`, component id `grant-mirror`, source `ChangeSource.eventsOf(ProjectEntity)`, reads the granting project's `organizationId` once per event through `componentClient`, commands `record-grant-change` on the grantee `Project` or `Organization` with `Attribution.from(actor, at)`; registered in `CP/ControlPlane.scala` `componentsWith`
- [X] T012 [P] `CPT/ControlPlaneHttpSuite.scala` cases (or a new `CPT/GrantRoutesSuite.scala`): `POST /projects/{id}/grants` by an owner returns the `GrantDetail`; by a member 403; by a deploy token 403; by a non-member 404 `no such project`; a grantee project id never created is 404; `GET /projects/{id}/grants` lists it; `DELETE /projects/{id}/grants/{grantId}` withdraws a pending and revokes an accepted grant; `GET /projects/{id}/grants/received` on the grantee project shows the record; `ControlPlaneRoutesReferenceSuite` regenerated under `-Dankka.docs.update=true` with a hand-written section per route in `docs/reference/control-plane-api.md`
- [X] T013 `CP/api/ProjectEndpoint.scala`: the four grant routes (`contracts/grants.md`), owner checks through `authz.requireOwner`, the grantee-project existence check through `ProjectEntity.exists`, the id minted from `SecureRandom`, `effect` left as the state's word until T057; `CP/api/Attributing.scala` reused
- [X] T014 [P] `CLIT/MainSuite.scala` (or `CLIT/GrantsCommandSuite.scala`) cases: `projects grants make service:payments/merchant route wallet POST /v1/wallets/{player}/{currency}/deposits -p spinvibe` posts the `GrantRequest` the contract shows, likewise `method`, `topic … consume --decrypt`, `erasure`; `list`, `received`, `withdraw`, `revoke`; a bad grantee is a usage error before any request; `CliReferenceSuite` regenerated with a hand-written section per command in `docs/reference/cli.md`
- [X] T015 `CLI/GrantsCommand.scala`, `CLI/Main.scala` (`projectsCommand` gains `grants`), `CLI/ControlPlaneClient.scala` (`makeGrant`, `listGrants`, `receivedGrants`, `endGrant`), `CLI/Output.scala` (`grants`, `receivedGrants` tables with the columns in `contracts/service-status-and-listing.md`)
- [X] T016 [P] `OPT/CrdSchemaSuite.scala` passes with `spec.grants.items` compared both ways; `OPT/ProjectRenderingSuite.scala` case: an `AnkkaProject` with two accepted grants renders `grants.json` with the shape in `data-model.md`, sorted by id, beside `topics.json`; `CPT/ProjectProjectionSuite.scala` case: only `accepted` grants are projected
- [X] T017 `CRD/AnkkaProject.scala` `ProjectGrantEntry` and `AnkkaProjectSpec.grants`; `K/components/crd/ankkaproject.yaml` declares every field; `CP/deploy/ProjectProjection.scala` projects accepted grants; `CP/application/ProjectTopicsTrigger.scala` renamed in code to `ProjectTrigger` (component id `project-topics-trigger` kept) and calling `projector.projectTopics` on every grant event too; `CP/deploy/ServiceProjector.scala` `grantsOf(projectId)` read through the entity's `grants` query; `OP/ProjectConfig.scala` `GrantsKey = "grants.json"` and `renderGrants(spec)`; `OP/ProjectReconciler.scala` writes both keys in `EnsureProjectConfig`

**Checkpoint**: `sbt controlPlaneApi/test controlPlane/test operator/test cli/test` green offline; a
grant made through the CLI lands in `grants.json` of the project's ConfigMap on a kind cluster
(`kubectl get cm ankka-project -n ankka-spinvibe -o jsonpath='{.data.grants\.json}'`).

## Phase 3: User Story 1 — A service calls a route another project granted it (P1) 🎯 MVP

**Goal**: `Callers.granted` admits a service of another project that holds a grant on this route
or gRPC method, from a file re-read without a redeploy; a revocation refuses it, closes its streams
and sockets, and a refusal is recorded as one.

**Independent test**: `F/route-grants.feature` through `CrossProjectRouteGrantsFeatures` (k3s:
the sample as `spinvibe/wallet`, `payments/merchant`, `payments/psp-gateway`), with the matcher,
the file and the streams proved offline in `http` and `testkit`.

### Tests

- [X] T018 [P] [US1] `HT/GrantedCallerSuite.scala`: `CallerMatcher.Granted.admits` with a `Grants.of(...)` handle admits `Caller.Service("payments","merchant")` on `Route("POST", "/v1/wallets/{player}/{currency}/deposits")` and refuses the same caller on `GET /v1/wallets/{player}/{currency}`, another service of `payments`, and `Caller.Gateway`; admits `Local` always; `Granted` with no target admits only `Local`; `Method("WalletService/Deposit")` admits by full name and not `WalletService/GetBalance`
- [X] T019 [P] [US1] `RTT/GrantsFileSuite.scala`: reads `grants.json` from a temp directory, filters to the service's own entries, re-reads when the real path's mtime changes and not within the interval, keeps the last good value on malformed JSON, answers no grants when the variable is unset; a kubelet-style swap (a new directory and a moved symlink) is seen
- [X] T020 [P] [US1] `HT/RefusedSpanSuite.scala`: a request refused by `AllowCallers` is recorded by the server's `Observability` as `SpanOutcome.Refused` with the route's handler name, never `Failed`, and the body says nothing of grants (`HttpProblem.forbidden` unchanged)
- [X] T021 [P] [US1] `TKT/sockets/RevokedStreamsSuite.scala` (`AnkkaTestKit.start(grants = …)`): a socket and an SSE stream opened as `Caller.Service("payments","merchant")` on granted routes are closed (socket code 1008, stream completed) within one reload interval after the test kit's `grants` handle drops the grant; a reconnect is 403; a socket on an ungranted route is unaffected
- [X] T022 [P] [US1] `GT/AdmissionSuite.scala` cases: `decide` on an `AllowCallers(Granted)` method with a grants handle admits the named service on `WalletService/Deposit` and answers `PERMISSION_DENIED` on `GetBalance`; the refusal is recorded `Refused` as today
- [X] T023 [P] [US1] `RTT/ObservabilityDocumentsSuite.scala` (or `TopologyJsonSuite`) case: a route whose effective ACL names `Granted` carries `grantable: true` on its handler; one whose endpoint ACL names it and whose route ACL does not carries nothing
- [X] T024 [P] [US1] `CPT/CrossProjectRouteGrantsFeatures.scala` (k3s, `ankka.cluster.tests`, the `BrokerClusterFeatures` shape without the broker): steps for every scenario of `F/route-grants.feature` — deploy the sample with `CART_WALLET=on` as the three services, `InPod.curl` from `merchant` and `psp-gateway` with their certificates, the CLI for grants, `kubectl get pod` restart counts before and after, the gRPC scenario through `GrpcChannels.tls` from a probe pod, the socket and stream scenario holding both open across the revocation; the rollout scenario is `ranElsewhere` → `CPT/ServiceStatusSuite.scala` (T026)

### Implementation

- [X] T025 [US1] `H/Grants.scala` (`GrantTarget`, `GrantEntry`, `Grants` with `none`, `of`, `admits`, `entries`, and a `listeners` hook); `H/Caller.scala` (`CallerMatcher.Granted`, `admits(caller, self, target: Option[GrantTarget])`, `Callers.granted`); `H/HttpServer.scala` (`admit` passes the matched route's `GrantTarget.Route(method, template)`; a `grants: Grants` constructor input, `Grants.none` by default; the refused span T020; `OpenStreams` with a `KillSwitch` per SSE source; the grants listener that closes sockets and streams whose caller the new grants no longer admit); `H/Sockets.scala` (`OpenSocket` keeps its `RequestContext` and `GrantTarget`; `CloseReason.Revoked` 1008); `G/Admission.scala` and `G/Binding.scala` (target `GrantTarget.Method(fullName)`, the grants handle from `GrpcServer`); `RT/ObservabilityDocuments.scala` and `API/descriptors.scala` `TopologyHandler.grantable`
- [X] T026 [US1] `RT/GrantsFile.scala` (`ANKKA_PROJECT_GRANTS`, `ankka.grants.reload-interval = 10s` in `modules/runtime/src/main/resources/reference.conf`, filtered by `ServiceIdentity`, notifies listeners on change); `RT/Ankka.scala` wires it into `HttpServer` and `GrpcServer` when the variable is set; `OP/Rendering.scala` adds `ANKKA_PROJECT_GRANTS=/var/run/ankka/project/grants.json` to `projectEnvironment` (every platform container but web); `OP/ServiceReconciler.scala` reports `status.grants = "mounted"`; `CRD/AnkkaService.scala` and `K/components/crd/ankkaservice.yaml` gain `status.grants`; `CP/deploy/StatusIngest.scala` and `API/descriptors.scala` `ServiceStatus.grants`; `CLI/Output.scala` prints `Grants: mounted | rollout needed`; regenerate `OPT` goldens once (`-Dankka.golden.update=true`, `-Dankka.rendering.pin=true`) and review the diff is one env entry per fixture and no new object; `CPT/ServiceStatusSuite.scala` case: a status without `grants` reads as `rollout needed`, with it as `mounted`
- [X] T027 [US1] `TK/AnkkaTestKit.scala`: `start(…, grants: Grants = Grants.none)` handed to the servers and exposed as `kit.grants` (a `Grants.Mutable` whose `set` notifies), `asCaller(Caller.Machine(...))` already works through `LocalCallers`; `TKT` cases in T021 use it
- [X] T028 [P] [US1] `SAMPLE/WalletEntity.scala` (a key value entity per player holding balances and the idempotency keys it has applied), `SAMPLE/WalletEndpoint.scala` (`/v1/wallets`: `POST /{player}/{currency}/deposits` with `Idempotency-Key`, `GET /{player}` under `Callers.service("lobby")`, `GET /{player}/{currency}`, stream `GET /{player}/ledger`, socket `/{player}/events`, the rest under `Acl.allowCallers(Callers.granted)`, with `// docs:start granted-callers` regions), `SAMPLE/AffiliatesEndpoint.scala` (`/v1/affiliates`: `GET /attribution` granted, `GET /feed` allow-all, `GET /report` `Oidc.authenticate()` when issuers are configured), `samples/shopping-cart-api/src/main/protobuf/wallet.proto` (`WalletService` `Deposit`, `GetBalance`) and `SAMPLE/WalletGrpcEndpoint.scala` under `Callers.granted`; `SAMPLE/Main.scala` registers them under `CART_WALLET=on`; `samples/shopping-cart/src/test/.../WalletSuite.scala` drives the entity and the idempotency key through the test kits
- [X] T029 [US1] `SC/Discovery.scala` reads `CallerMatcher.granted` (refused under a spec protocol below 1.15), `SC/RemoteEndpoint.scala` `matcherOf` maps it to `Granted`; `SCT/DiscoverySuite.scala` cases; `SCT/conformance/ConformanceReference.scala` `CallersEndpoint` gains `GET /callers/granted` under `withAcl(Acl.allowCallers(Callers.granted))`; `SCT/conformance/ConformanceSuite.scala` cases `http.granted-admits-a-grant` (the suite's `Grants` handle holds `local/orders` on the route) and `http.granted-refuses-without`, `http.granted-in-grpc`
- [X] T030 [P] [US1] Python: `PY/endpoint.py` `Callers.granted` (`CallerMatcher(kind="granted")`, `to_pb` sets `granted`), the protocol gate in `PY/server.py` discovery; `sdks/python/examples/shopping_cart/conformance.py` declares `/callers/granted`; `sdks/python/tests/test_caller.py` cases. TypeScript: `TS/routes.ts` `Callers.granted`, `TS/spec.ts` `callersToProto`, the gate; `sdks/typescript/examples/shopping-cart/conformance.ts`; `sdks/typescript/test/caller.test.ts`. Rust: `RS/components/endpoint.rs` `CallerMatcher::Granted` and `to_proto`; `sdks/rust/examples/shopping-cart/src/conformance.rs`; `sdks/rust/ankka/tests/endpoint.rs`
- [X] T031 [US1] `docs/build/http-endpoints.md` "Name who may call" gains `Callers.granted` with the sample's region; `docs/build/grpc-endpoints.md` likewise; `docs/reference/{scala,python,typescript,rust}-sdk.md` matcher rows; `docs/reference/service-descriptor.md` (nothing: no descriptor change) — checked

**Checkpoint**: `F/route-grants.feature` green on k3s; `sbt http/test grpc/test testkit/test sidecar/testOnly *ConformanceSuite` green; the three SDKs' conformance runs green.

## Phase 4: User Story 2 — A service consumes or produces another project's topic, and the broker enforces it (P1)

**Goal**: a topic grant becomes a literal ACL entry on the grantee's `KafkaUser`; a component names
another project's topic by project; the broker, not the SDK, refuses without a grant; the status
says whether each cross-project topic is granted.

**Independent test**: `F/topic-grants.feature` through `CrossProjectTopicGrantsFeatures` (k3s, the
broker stack, `BrokerProbe` holding the grantee's certificate), with the rendering and the SDK
option proved offline.

### Tests

- [X] T032 [P] [US2] `OPT/BrokerGrantsRenderingSuite.scala`: `StrimziRendering.user(spec, broker, granted)` with a `consume` grant on `spinvibe.casino.players` adds exactly `topic spinvibe.casino.players literal Read, Describe`; `produce` adds `Write, Describe`; both rights are two entries; no group entry and no prefix is added; with no grants the rendering equals `golden/broker.txt`'s user byte for byte; `KafkaUserSpec` encodes `authentication` absent and `quotas` present/absent as Strimzi reads them
- [X] T033 [P] [US2] `OPT/OperatorSuite.scala` (or `GrantsNamingSuite`) cases: `Executor.grantsNaming(ServiceRef)` over three `AnkkaProject`s in the cache returns the accepted grants naming the service and nothing pending; a changed `AnkkaProject` requeues the services in its namespace and every service its grants name in other namespaces
- [X] T034 [P] [US2] `TKT/CrossProjectTopicSuite.scala` (in-memory broker): a view over `fromTopic("spinvibe", "casino.players", …)` subscribes to `spinvibe.casino.players` under `ankka.<own>.<service>.view.players`; a consumer `produceTo("spinvibe", "payments.deposits")` publishes to the qualified name; the topology edge is `topic:spinvibe/casino.players`; `rejectUndeclared` passes with a declarations file that does not list it; naming a declared broker and a project together is refused at start with the message in `contracts/runtime-and-sdks.md`
- [X] T035 [P] [US2] `CPT/ServiceEndpointSuite.scala` case: `crossProjectTopics` assembled from a topology with `topic:spinvibe/casino.players` edges and the granting project's grants: `granted`, `not granted (no grant)`, `not granted (pending)`, `not granted (ended)`
- [X] T036 [P] [US2] `CPT/CrossProjectTopicGrantsFeatures.scala` (k3s, `BrokerClusterFeatures` shape): steps for every scenario of `F/topic-grants.feature` — the sample as `spinvibe/<publisher>` and `affiliates-hub/attribution` with `CART_CHECKOUTS_TOPIC=spinvibe/casino.players`, `payments/notifier` with `CART_PUBLISH_TO=spinvibe/payments.deposits`; `BrokerProbe.holding(k3s, ns, "attribution")` for "something holding the credential reads/publishes" and `Result.refused`; the view's row count through the sample's `/checkouts-seen` route; the group name through `kafka-consumer-groups` in the probe; `kubectl get pod` restart counts

### Implementation

- [X] T037 [US2] `SDK/ChangeSource.scala` `TopicOptions.project`, `fromTopic(project, name, decoder[, startFrom])`; `SDK/Consumer.scala` `Publication.project`, `produceTo(project, name)`; `RT/remote/RemoteDescriptors.scala` `RemoteSource.Topic.options.project`, `RemoteConsumerDescriptor.produces.project`; `RT/DeclaredConnections.scala` `DeclaredSource.Topic.project` and `topic:<project>/<name>` ids; `RT/Kafka.scala` `KafkaConnection.qualified(topic, project)`; `RT/ProjectionRuntime.scala` skips the declaration check for a project-named topic and refuses `broker` with `project`; `RT/TopicSourceRules.scala`; `SC/Discovery.scala` reads `project` (gated on 1.15), `SC/Translate.scala`
- [X] T038 [US2] `OP/strimzi/KafkaUserResource.scala` (`authentication: Option`, `KafkaUserQuotas`, `KafkaUserSpec.quotas: Option`); `OP/StrimziRendering.scala` `user(spec, broker, granted: Vector[GrantedTopic])`; `OP/Executor.scala` `grantsNaming(ref): Vector[GrantedTopic]` over `client.resources(classOf[AnkkaProject]).inAnyNamespace().list()` (trait default empty); `OP/ServiceReconciler.scala` passes it to `Rendering.render`; `OP/Rendering.scala` threads `granted` to `brokerActions`; `OP/Operator.scala` requeues grantee services on an `AnkkaProject` change
- [X] T039 [US2] `API/descriptors.scala` `CrossProjectTopic`, `ServiceStatus.crossProjectTopics`; `CP/api/ServiceEndpoint.scala` `withCrossProjectTopics` reading the granting project's `grants`; `CLI/Output.scala` the `Cross-project topics:` lines; `CLI/mcp/AnkkaTools.scala` `get_service` description; `CON/routes/service.tsx` rows, `CON/client/schemas.ts`, the fake
- [X] T040 [P] [US2] `SAMPLE/CheckoutsSeen.scala` and `SAMPLE/Main.scala`: `CART_CHECKOUTS_TOPIC` accepts `project/name` (a view over another project's topic), `CART_PUBLISH_TO=project/name` makes `CheckoutNotifier` publish there; `samples/shopping-cart/src/test` case for the parsing
- [X] T041 [P] [US2] Python `PY/view.py`/`PY/consumer.py` `topic(..., project=)`, `produces_to(..., project=)`; TypeScript `TS/view.ts`/`TS/consumer.ts` `{ project }`; Rust `RS/components/{view,consumer}.rs` `.project(...)`; each gated on 1.15; `SCT/conformance/ConformanceSuite.scala` cases `topics.cross-project-source` and `topics.cross-project-publication` with the in-memory broker, and each SDK's conformance service declaring one of each
- [X] T042 [US2] `docs/build/topics.md`: "Reading another project's topic" (the four SDK forms, the group rule, the broker's refusal and the status), replacing the sentence at 430-438; `docs/platform/broker.md` ACL table gains the granted rows; `.claude/rules/messaging.md` notes the project option and that grants are the broker's ACLs

**Checkpoint**: `F/topic-grants.feature` green on k3s; `sbt operator/test testkit/testOnly *CrossProjectTopicSuite` green.

## Phase 5: User Story 3 — A machine outside the installation calls a granted route with a token (P1)

**Goal**: an owner registers a machine and is shown its secret once; the control plane issues it a
fifteen-minute token by client credentials from a key it keeps in a Secret it creates and publishes
as a JWKS; a service verifies the token with the JDK and sees `Caller.Machine`; a revoked grant
refuses an unexpired token within the bound; the token route is rate-limited.

**Independent test**: `F/machines.feature` through `CrossProjectMachinesFeatures` (k3s with the
gateway, `curl` from the host), with the entity, the keys, the token route and the verifier proved
offline.

### Tests

- [X] T043 [P] [US3] `CPT/MachineEntitySuite.scala`: `register` keeps a digest and no secret; a second `register` is `Conflict`; `delete` then `register` is a new machine with a new digest; `check-secret` answers only an undeleted machine with the matching digest, in constant time (`MessageDigest.isEqual`); `set-byte-rates` within and above the ceiling; `MachineRows` row per live machine, deleted on `MachineDeleted`
- [X] T044 [P] [US3] `CPT/MachineKeysSuite.scala`: a directory with two `<kid>.pem` files signs with the newest and publishes both; a key added later is seen after the mtime check; an unreadable file keeps the last good set; in-memory mode generates one key; `rotate` adds a key and the sweep an hour later keeps the newest two; the Secret create path answers 409 as "exists" and writes nothing else
- [X] T045 [P] [US3] `CPT/MachineTokenSuite.scala` (in-process control plane; the `keys` server bound to port 0, never 7629, as every suite binds): `POST /oauth/token` with form fields and with HTTP Basic answers a token with the claims in `data-model.md` and `expires_in` 900; a wrong secret, an unknown id and a deleted machine all answer 401 `invalid_client` with the same body; `grant_type=password` is 400; the thirteenth request in a minute for one client id is 429 with `Retry-After`; `GET /.well-known/jwks.json` lists the `kid` the token names; the same two routes on the `keys` port without a client certificate; `GET /.well-known/openid-configuration`
- [X] T046 [P] [US3] `HT/MachineTokensSuite.scala`: a token `TestIssuer`-style signed with a JDK-generated RSA key verifies against a JWKS the suite serves on loopback (`ca` trusted); `alg: HS256`, `none`, an unknown `kid` (then a refetch finds it), an expired token, a wrong `aud`, a wrong `iss`, a missing `typ`, a `sub` not of the machine form are each refused with the reason; the JWKS is fetched once and held through the server being stopped
- [X] T047 [P] [US3] `HT/CallerIdentitySuite.scala` cases (TLS through `TlsServing`): a gateway-certificate connection with a valid machine token yields `Caller.Machine("eitheror","affiliate-network")`; with no token, an expired one or another issuer's, `Caller.Gateway`; a service-certificate connection carrying a machine token yields `Caller.Service` and the token is ignored
- [X] T048 [P] [US3] `AOT/OidcAclSuite.scala` case: with the machine issuer configured, an `Acl.Authenticate` route sees a `Principal` with subject `machine:eitheror/affiliate-network` and claims `kind: machine`, `organization`, beside the request's `Caller.Machine`; a listed OIDC issuer still works unchanged
- [X] T049 [P] [US3] `CPT/CrossProjectMachinesFeatures.scala` (k3s, `ControlPlaneClusterSuite`'s gateway shape): steps for every scenario of `F/machines.feature` — the sample as `spinvibe/affiliates` with `CART_WALLET=on`, exposed; the token taken with `curl` from the host against `https://api.<base>:<port>/oauth/token`; the route through the gateway with `Authorization: Bearer`; the `strangers` issuer from `TestIssuer` reached through a `ServiceProjector` setting; the rate-limit scenario with 13 requests; the `/v1/affiliates/report` scenario with `ANKKA_AUTH_ISSUERS=customers` on the descriptor

### Implementation

- [X] T050 [US3] `CP/domain/model.scala` `Machine`, `ByteRates`; `CP/domain/events.scala` `MachineEvent`; `CP/application/MachineEntity.scala` (component id `machine`, commands `register`, `set-byte-rates`, `delete`, queries `get`, `check-secret`), `CP/application/MachineRows.scala`; `CP/auth/MachineSecrets.scala` (`mint`, `digest`, `matches`, `clientId(org, name)`, `parseClientId`) sharing `DeployTokens`' primitives; `CPT/EventCompatibilitySuite.scala` pins the three events and asserts no `secret`; registered in `CP/ControlPlane.scala`
- [X] T051 [US3] `CP/auth/MachineKeys.scala` (the directory reader, the in-memory mode, `sign(claims)`, `jwks`, `rotate()`, the sweep; the Secret `create`/`patch` through `AnkkaServiceClient` gaining `createSecret`/`patchSecret` in the control plane's own namespace); `CP/application/MachineKeyRotation.scala` (a `TimedAction` set recurring every 30 days at start; the hour-later sweep as a one-shot timer); `K/components/controlplane/deployment.yaml` mounts `ankka-controlplane-machine-keys` `optional: true` at `/var/run/ankka/machine-keys` and sets `ANKKA_MACHINE_KEYS_DIRECTORY`; `controlplane/src/main/resources/reference.conf` `ankka.controlplane.machines.{issuer, token-rate, keys-directory}` from `ANKKA_MACHINE_ISSUER`, `ANKKA_MACHINE_TOKEN_RATE`, `ANKKA_MACHINE_KEYS_DIRECTORY`; `CP/auth/AuthConfig.scala` `derivedMachineIssuer(baseDomain, httpsPort)`
- [X] T052 [US3] `CP/api/MachineTokenEndpoint.scala` (`POST /oauth/token`, `GET /.well-known/jwks.json`, `GET /.well-known/openid-configuration`, `POST /platform/machine-keys/rotate` admin-only; `CP/auth/TokenBucket.scala`); `CP/ControlPlane.scala` registers it and starts a second `HttpServer` on port 7629 `keys` serving only the two `.well-known` routes with `ankka.http.tls.client-auth = none` (a new `HttpServer` option, `H/HttpServer.scala`, used by nothing else); `K/components/controlplane/{deployment,service,zero-trust}.yaml` the `keys` port, the Service port and the network policy admitting `ankka-broker` and `managed-by: ankka` namespaces to 7629
- [X] T053 [US3] `CP/api/OrganizationEndpoint.scala` machine routes (`contracts/machines.md`; `MachineRegistered` carries `tokenUrl` from the issuer and `brokerBootstrap` from `ANKKA_BROKER_EXTERNAL_BOOTSTRAP`); `API/descriptors.scala` `MachineRegistration`, `MachineRegistered`, `MachineSummary`, `ByteRatesRequest`, `TokenResponse`, `Jwks` with `Wire` codecs and fixtures; `ControlPlaneRoutesReferenceSuite` regenerated with a hand-written section per route; `CPT/ControlPlaneHttpSuite.scala` cases: owner registers, member 403, deploy token 403, duplicate 409, delete then list
- [X] T054 [US3] `CLI/MachinesCommand.scala`, `CLI/Main.scala` (`organizations machines register|list|delete|byte-rates`), `CLI/ControlPlaneClient.scala`, `CLI/Output.scala` (`machineRegistered` prints the client id, the secret once, the token URL and the broker address; `machines` table); `CLIT` cases; `CliReferenceSuite` regenerated with sections
- [X] T055 [US3] `H/MachineTokens.scala` (JWS parse, RS256 verify, JWKS cache over pekko-http's client with the CA from `ANKKA_MACHINE_JWKS_CA`, the rules in R9, started at server start); `H/Caller.scala` `Caller.Machine`, `encode`/`decode` `machine:<o>/<n>`; `H/HttpServer.scala` `CallerSource.callerOf` consults it when the certificate says `Gateway`; `G/Admission.scala` the same for gRPC metadata `authorization`; `AO/Oidc.scala` `authenticate()` adds the machine issuer when `ANKKA_MACHINE_ISSUER` is set and yields the principal; `RT/remote/Conversation.scala` `RemoteCaller.Machine`, `SC/Translate.scala` → `Caller.machine`; `OP/Rendering.scala` renders `ANKKA_MACHINE_ISSUER`, `ANKKA_MACHINE_JWKS_URL`, `ANKKA_MACHINE_JWKS_CA=/var/run/secrets/ankka/service/ca.crt` on every platform container from `OP/Settings.scala` (`ANKKA_MACHINE_ISSUER`, `ANKKA_MACHINE_JWKS_URL` on the operator, set by `K/components/controlplane/operator-settings.yaml`); `API/descriptors.scala` `ServiceSpec.problems` refuses the three in a descriptor; goldens regenerated once more and reviewed (three env entries)
- [X] T056 [P] [US3] Python `PY/context.py` `MachineCaller`, `PY/server.py` decode; TypeScript `TS/context.ts`, `TS/server/http.ts`; Rust `RS/components/endpoint.rs` `Caller::Machine`; `SCT/conformance/ConformanceSuite.scala` case `http.caller-machine` (`LocalCallers.header(Caller.Machine(...))` to `/callers/whoami`); each SDK's caller test

**Checkpoint**: `F/machines.feature` green on k3s; `sbt http/test auth-oidc/test controlPlane/testOnly *Machine*` green; `curl` by hand on kind per `quickstart.md`.

## Phase 6: User Story 4 — Members see who may reach their project and what their project may reach (P2)

**Goal**: every listing says whether a grant is in effect and why not; a topic grant tells its
grantee the topic's settings; `decrypt` and `erasure` grants are held and listed; the console shows
all of it.

**Independent test**: `F/listing.feature` through `CrossProjectListingFeatures` (offline: an
in-process control plane, a scripted `TopologyReader` and statuses).

### Tests

- [X] T057 [P] [US4] `CPT/GrantEffectSuite.scala`: the pure `GrantEffect.of(grant, serviceStatus, topology, brokerExposed)` answers each of the eleven words in `contracts/grants.md` for the situations in `F/listing.feature`'s outline, in the precedence order of `contracts/service-status-and-listing.md`
- [X] T058 [P] [US4] `CPT/CrossProjectListingFeatures.scala` (`GherkinSuite("../features/cross-project/listing.feature")`, offline): steps over an in-process `ControlPlane` with `TopologyReader` and `ServiceProjector` doubles the steps script per scenario ("wallet has no route … yet", "whose ACL admits only lobby", "a web-hosted service", "deployed by a platform too old", "does not expose its broker"), two organizations for the "member of neither" scenario, `history` for the attribution scenario, and the `decrypt`/`erasure` scenario asserting both sides' listings and that `grants.json`'s rendering (T017's `renderGrants`) carries both

### Implementation

- [X] T059 [US4] `CP/api/GrantEffect.scala`; `CP/api/ProjectEndpoint.scala` `GET /projects/{id}/grants` fills `effect` (reads each named service's status and merged topology once per listing, `topology.read`), `GET /projects/{id}/grants/received` and `CP/api/OrganizationEndpoint.scala` `GET /organizations/{id}/grants` fill `TopicSettings` from the granting project's `topics` query; `ANKKA_BROKER_EXTERNAL_BOOTSTRAP` read into `ankka.controlplane.broker.external-bootstrap`
- [X] T060 [US4] `CON/client/schemas.ts` (grant, received grant, topic settings, machine, byte rates, token schemas and `schemasByType`), `CON/client/control-plane.ts` methods for every grant and machine route; `CON/routes/project.tsx` "Grants" and "Received" cards with withdraw/revoke forms; `CON/extensions/types.ts` operations `grant.make`, `grant.end`, `grant.answer`, `grant.relinquish`, `machine.register`, `machine.delete`, `machine.byte-rates`; `CON/testing/fake-control-plane.ts` every route with the 404/403 rules; `console/package/test/fixtures.test.ts` decodes the new fixtures
- [X] T061 [US4] `console/e2e/tests/grants.spec.ts`: a project page lists a grant with its effect, makes one, revokes one, shows received grants with retention; `parity.ts` green for every grant route (the organization-side routes are exercised in T084, the machine routes in T062)
- [X] T062 [US4] `CON/routes/machines.tsx` (register through `tokenFlash` so the secret shows once, delete, byte rates), `CON/context.ts` `areas += "machines"`, `CON/ui/shell.tsx` rail entry beside deploy tokens, `CON/routes.ts`; `console/e2e/tests/machines.spec.ts` (shown once and never again, delete, byte rates); `console/e2e/tests/accessibility.spec.ts` covers the new pages

**Checkpoint**: `F/listing.feature` green offline; `just test-console` green with parity.

## Phase 7: User Story 5 — An affiliate platform outside ankka consumes a brand's attribution topic (P2)

**Goal**: an installation that opts in exposes its broker through the Gateway by SNI passthrough;
a machine's Kafka client authenticates with its token, reaches exactly its granted topics under its
own group within its byte rates, and is cut off when its token cannot be renewed.

**Independent test**: `F/machine-topics.feature` through `CrossProjectMachineTopicsFeatures` (k3s
with the gateway, the broker and the `broker-external` component; the Apache Kafka Java client in
the test JVM as the stock client, from the host).

### Tests

- [X] T063 [P] [US5] `OPT/MachineRenderingSuite.scala`: `MachineRendering.user(machine, grants, settings)` renders `machine.affiliates.network` with no `authentication`, the literal topic entries of its accepted grants, the group prefix `ankka.machine.affiliates.network.` Read, byte rates (Strimzi's `quotas` block) from the resource or the defaults, clamped to the ceiling; with no grants and a deleted resource the user keeps only the group entry; the YAML matches `contracts/operator.md`
- [X] T064 [P] [US5] `OPT/CrdSchemaSuite.scala` compares `AnkkaMachine` both ways; `OPT/OperatorClusterSuite.scala` RBAC case covers `ankkamachines` get/list/watch and `/status` for the operator, and asserts `delete` is refused
- [X] T065 [P] [US5] `CPT/RemoteOverlaySuite.scala` cases: the cloud render's `Kafka` has the `external` listener with `type: tlsroute`, `oauth` authentication and the `networkPolicyPeers` of `contracts/broker-external.md`; its `Gateway` has the `broker` listener on 9094 in `Passthrough` mode; its `EnvoyProxy` Service carries `tls-9094` and the gateway component's own files are unchanged; the `Kafka` `config` holds `max.connections.per.ip` and `listener.name.external.max.connection.creation.rate` from the overlay's replacements; the certificate `ankka-broker-external` names an issuer that exists in the render; the local render has none of the three; both overlays' wildcard certificates name a `ClusterIssuer`; `deploy-local.sh` exports the root from `cert-manager`
- [X] T066 [P] [US5] `CPT/CrossProjectMachineTopicsFeatures.scala` (k3s): base domain `127.0.0.1.sslip.io`; `GatewayStack.install` plus `BrokerStack.installExternal` (applies the component with `PUBLIC_ISSUER=ankka-ca` and the Gateway patch, waits for the `broker` listener to be `Programmed` and the bootstrap `TLSRoute` to be `Accepted`); the k3s container binds `30094` to the host's `9094` (`withCreateContainerCmdModifier`, a fixed `PortBinding`); steps for every scenario of `F/machine-topics.feature` with `org.apache.kafka.clients` (`KafkaConsumer`/`KafkaProducer` configured per the contract, `ssl.truststore.certificates` from `GatewayStack.exportCa`), `kafka-acls`-free assertions through `BrokerProbe.allowed(user)` for "what the broker allows the credential is listed", the throttling scenario asserting the machine's bytes fetched over a 30 s window are at most 1.25 times its byte rate while the `ledger` view's lag stays at zero throughout, a step for SC-009 in which a `BrokerProbe` pod in a project namespace connects to the `external` listener's bootstrap Service and is refused before authentication (the listener's network policy), the re-authentication scenario waiting past 900 s replaced by a listener set to `maxSecondsWithoutReauthentication: 60` in the suite's patch (and the feature's "before 15 minutes" step holding `≤ 900`)

### Implementation

- [X] T067 [US5] `CRD/AnkkaMachine.scala` (cluster-scoped, `@Kind("AnkkaMachine") @Plural("ankkamachines")`, spec and status in `data-model.md`); `K/components/crd/ankkamachine.yaml` and `crd/kustomization.yaml`; `operator/src/main/resources/ankka/crd/` symlink; `K/components/operator/operator.yaml` and `K/components/controlplane/controlplane-rbac.yaml` RBAC per `contracts/operator.md`
- [X] T068 [US5] `CP/deploy/AnkkaServiceClient.scala` `putMachine`/`deleteMachine`, `CP/deploy/Fabric8AnkkaServiceClient.scala`, `CP/deploy/MachineProjection.scala`; `CP/application/MachineLifecycleTrigger.scala` (made by T081 with the lapse reaction; if US5 lands first, make it here with the projection reaction and T081 adds the lapse) gains: project on `MachineRegistered`/`MachineByteRatesSet`, delete on `MachineDeleted`; `CP/ControlPlane.scala`
- [X] T069 [US5] `OP/MachineRendering.scala`, `OP/MachineReconciler.scala` (its own `WorkQueue`, keyed by machine name; reads the `AnkkaMachine` and every `AnkkaProject` grant naming it; `EnsureKafkaUser`; `SetMachineStatus`), `OP/Action.scala` `SetMachineStatus`, `OP/Executor.scala`; `OP/Operator.scala` the `AnkkaMachine` informer (a missing type logs and skips, as `AnkkaProject` does) and the requeue of machines named by a changed `AnkkaProject`; `OP/Settings.scala` `MachineSettings` from the five `ANKKA_MACHINE_*` variables with the defaults in `contracts/operator.md`
- [ ] T070 [US5] `K/overlays/local/local-ca.yaml`: `Certificate ankka-root-ca` in `cert-manager`, `ClusterIssuer ankka-ca`; `K/overlays/local/kustomization.yaml` replacements unchanged for the wildcard; `K/deploy-local.sh` exports `~/.ankka/local-ca.crt` from `cert-manager/ankka-root-ca`; `K/overlays/cloud/acme-issuer.yaml` unchanged; both overlays' `kustomization.yaml` gain the `PUBLIC_ISSUER` replacement source (`ankka-platform` ConfigMap key `publicIssuer`: `ankka-ca` locally, `letsencrypt-production` in the cloud); `OPT/GatewayStack.scala` applies the moved CA
- [X] T071 [US5] `K/components/broker-external/{kustomization.yaml, kafka-listener.yaml, certificate.yaml, gateway-listener.yaml, envoyproxy-port.yaml, settings.yaml, README.md}` per `contracts/broker-external.md` (the Kafka JSON patch appends the listener; `settings.yaml` patches `ANKKA_BROKER_EXTERNAL_BOOTSTRAP` onto the operator and the control plane); `K/overlays/cloud/kustomization.yaml` lists it; `K/components/gateway/gateway.yaml` gains nothing (the listener comes with the component); `K/components/broker/namespace.yaml` unchanged (the listener's `allowedRoutes` selects it by `kubernetes.io/metadata.name`)
- [X] T072 [US5] `OPT/BrokerStack.scala` `installExternal(k3s, baseDomain)`; `OPT/BrokerProbe.scala` `allowed(user)` (the ACLs the broker holds for a principal, through `kafka-acls --list --principal`) and a `read`/`publish` variant taking a bootstrap and a client properties file
- [X] T073 [US5] The connection caps of FR-025: `K/components/broker-external/kafka-listener.yaml` sets `max.connections.per.ip` and `listener.name.external.max.connection.creation.rate` under the `Kafka`'s `config` from the `ankka-platform` ConfigMap keys `brokerMaxConnectionsPerIp` (64) and `brokerExternalConnectionRate` (20) through both overlays' replacements (ConfigMap keys, not environment variables, so `PlatformVariables` is untouched); `docs/platform/cross-project-access.md` says what each bounds and that the per-address cap is per broker; `RemoteOverlaySuite` (T065) asserts both values in the cloud render
- [X] T074 [US5] `docs/platform/cross-project-access.md` (new): grants, machines, the token route, the broker exposed, a partner's client properties from `contracts/broker-external.md`, the `keys` port, kind's port mapping; `docs/build/topics.md` "Reading a granted topic from outside the installation" with a stock client; `docs/platform/broker.md` the external listener; `docs/platform/networking.md` the Gateway's `broker` listener and port 9094; `mkdocs.yml` nav and `tools/docs/skill/{ankka-platform,ankka-views-consumers,ankka-endpoints}/SKILL.md` pages

**Checkpoint**: `F/machine-topics.feature` green on k3s; `sbt operator/test controlPlane/testOnly *RemoteOverlaySuite` green; by hand on a recreated kind cluster per `quickstart.md`.

## Phase 8: User Story 6 — A grant to another organization takes effect only when that organization accepts it (P2)

**Goal**: a cross-organization grant is pending and opens nothing until an owner of the grantee
organization accepts; decline, withdraw, relinquish and revoke each end it from one side; a deleted
grantee lapses its grants; every change is on both histories with its owner.

**Independent test**: `F/acceptance.feature` through `CrossProjectAcceptanceFeatures` (offline:
two organizations on an in-process control plane; "the credential may read" through the operator's
pure `MachineRendering` over the projected `AnkkaProject` and `AnkkaMachine` specs).

### Tests

- [X] T075 [P] [US6] `CPT/ControlPlaneHttpSuite.scala` cases: `POST /organizations/{org}/grants/{id}/accept` by an owner of the grantee organization; by a member 403; by a deploy token 403; by an owner of the *granting* organization 404 (the grant is not offered to it); `decline`, `relinquish`; a second `accept` 409; `GET /organizations/{org}/grants` lists offered and held grants for machines and for the organization's projects' services
- [X] T076 [P] [US6] `CPT/MachineLifecycleTriggerSuite.scala` (`ConsumerTestKit`): `MachineDeleted` lapses every grant naming the machine on each granting project with the deleter's attribution; `CPT/ProjectTriggerSuite.scala`: `ProjectDeleted` lapses every grant naming a service of the project; a lapsed grant's `GrantRecorded(lapsed)` reaches the grantee side
- [X] T077 [P] [US6] `CLIT/GrantsCommandSuite.scala` cases: `organizations grants list affiliates`, `accept|decline|relinquish affiliates <id>` call the routes; `CliReferenceSuite` regenerated
- [X] T078 [P] [US6] `CPT/CrossProjectAcceptanceFeatures.scala` (`GherkinSuite("../features/cross-project/acceptance.feature")`, offline): steps with two organizations and `TestIdentity` users `ada`, `bo`, `cy` and a deploy token; "the credential of network on the broker may (not) read" renders `MachineRendering.user` over `ProjectProjection.spec` of the granting project and `MachineProjection.spec`; "within 120 seconds" steps pass at once offline; the history steps read `GET /projects/{id}/history` and the organization's history; the lapse scenario deletes the machine and registers it again

### Implementation

- [X] T079 [US6] `CP/api/OrganizationEndpoint.scala` the four grant routes with the owner and offered-to checks of R5 (`received-grants` on the organization, or on a project whose `organizationId` is this organization); `ControlPlaneRoutesReferenceSuite` regenerated with sections
- [X] T080 [US6] `CLI/GrantsCommand.scala` `organizations grants list|accept|decline|relinquish`, `CLI/Main.scala`, `CLI/ControlPlaneClient.scala`, `CLI/Output.scala`
- [X] T081 [US6] `CP/application/MachineLifecycleTrigger.scala` (`Consumer[MachineEvent, Nothing]`, component id `machine-lifecycle-trigger`): lapse on `MachineDeleted` through the organization's `received-grants`; `CP/application/ProjectTrigger.scala` lapse on `ProjectDeleted` through the deleted project's `received-grants`; `CP/ControlPlane.scala` registers the trigger; no dependency on US5 (T068 extends the same consumer later)
- [X] T082 [US6] `docs/concepts/tenancy-and-access.md` the cross-organization section; `docs/platform/organizations.md` "Grants offered to an organization" and "Machines"; `docs/platform/identity.md` "A machine account in Keycloak" rewritten to point at registered machines (Keycloak clients remain for people's tools)
- [X] T083 [US6] `CON/routes/organization.tsx` "Offered grants" card with accept/decline/relinquish forms; `console/e2e/tests/grants.spec.ts` the organization half; `parity.ts` green for every organization-side route

**Checkpoint**: `F/acceptance.feature` green offline; `sbt controlPlane/test cli/test` green; `just test-console` green.

## Phase 9: Polish

- [X] T084 [P] `docs/reference/limitations.md`: drop "There is no grant that lets a service of one project read or publish to another project's topic", add the `keys` port as the second port that is not mutual TLS, add "a machine is a `Caller.Machine`, not a `Caller.Service`; cross-installation trust is not built", keep "a project is not a network boundary for HTTP" with the grant sentence; `docs/build/calling-services.md` the granted-route paragraph; `docs/concepts/consistency.md` a sentence that a granted call is at least once and the callee's idempotency key is its own (FR-012); `docs/reference/control-plane-api.md` "Authentication" gains the token route
- [X] T085 [P] `.claude/rules/control-plane.md`: grants, machines, the keys and the `keys` port, the token bucket, the one-writer mirror; `.claude/rules/kubernetes.md`: `grants.json` in the project ConfigMap, `AnkkaMachine`, the `broker-external` component, the `ClusterIssuer` move and why, kind's 30094; `.claude/rules/runtime.md`: `Grants`, `GrantsFile`, the refused span, `OpenStreams`; `.claude/rules/sidecar.md`: the 1.15 gates
- [X] T086 [P] `GLOSSARY.md`: `lapsed`, `target`, `in effect` already settled on 2026-10-08; add `keys port` and `external listener` as everyday words or terms as the features use them; `just features` clean
- [X] T087 `.github/workflows/ci.yml` filters: `kustomization/components/broker-external/**` is under `kustomization/**` already; `.github/ci-coverage.py` passes; `cluster-suites.py` lists the four new k3s classes (run it: `python3 .github/cluster-suites.py 'CrossProject*'`)
- [X] T088 `sbt -Dankka.cluster.tests=off buildAll`; `cd sdks/python && uv run pytest -q && uv run mypy && uv run conformance`; `cd sdks/typescript && npm run typecheck && npm test && npm run conformance`; `cd sdks/rust && cargo test --workspace && ./conformance.sh`; `just docs-reference && just docs && just test-console`
- [ ] T089 `gh workflow run cluster --ref 040-cross-project-access-impl -f suite='CrossProject*'` and `-f suite=OperatorClusterSuite`; read each suite's report for the scenarios by name, not the job's colour
- [ ] T090 After the k3s runs, re-read `specs/040-cross-project-access/spec.md` against `plan.md` and `research.md` (they were aligned on 2026-10-09: the `tlsroute` listener, the resolved assumptions, FR-010's status, FR-025's caps, SC-008's client) and record any divergence the runs showed as a dated note in `research.md`, never silently
- [X] T091 [P] `samples/shopping-cart/features/` and its glossary: the wallet routes the sample now serves, if `CartFeatures` names routes; `DocumentationDescriptorsSuite` passes for every `service.json` block in the new page
- [X] T092 [P] `docs/reference/limitations.md` gains an "Upgrading" note for this release: a project named `machine` is refused on upgrade and must be renamed first; a kind cluster made before this release needs recreating to reach an exposed broker from the host; the local overlay's CA moved to the `cert-manager` namespace, so `~/.ankka/local-ca.crt` is re-exported by `deploy-local.sh`; the control plane's `keys` port. `docs check` passes (no feature numbers in the text)
- [ ] T093 Measure R25 on the k3s route-grants run (the time from `grants make` to the first 200 and from `revoke` to the first 403) and record it in `research.md` R25; tighten the feature's "120" only if every run is under 60 s
- [X] T094 Review every refusal body and log line added for a grant name, a secret or a token (`grep -rn 'clientSecret\|access_token' controlplane/src/main` finds only the two reply types); `EventCompatibilitySuite`'s absence assertion runs over every event type
- [ ] T095 An in-place upgrade check on kind, by hand and recorded in `research.md` R7: deploy the previous release's operator and the sample, upgrade the operator to this branch, and assert with `kubectl get pod` that no sample pod restarted and that `services get` shows `Grants: mounted` after one reconcile — the proof that `grants.json` riding the existing mount rolls nothing on upgrade

## Dependencies

- Phase 1 → Phase 2 → every story. Within Phase 2: T005, T007, T008, T010, T012, T014, T016 are
  tests and can be written together; T006 → T009 → T011, T013, T017; T013 → T015; T009 → T016's
  projection case.
- US1 (Phase 3) needs T017 (`grants.json`) and T003 (the matcher's wire form for T029–T030).
  T025 → T026 → T027; T028 is independent of all three; T029–T030 need T025.
- US2 (Phase 4) needs T017 and T003; T037 and T038 are independent of each other; T039 needs
  T037; T041 needs T037; T040 needs nothing but the sample.
- US3 (Phase 5) needs Phase 2 only. T050 → T051 → T052; T053 needs T050; T054 needs T053; T055 is
  independent of the control plane tasks and needs T001; T056 needs T055's `Caller.Machine`.
- US4 (Phase 6) needs US1's `grantable` (T025) and `status.grants` (T026), and US3's machine
  routes for T062. T059 → T060 → T061, T062.
- US5 (Phase 7) needs US2's `KafkaUserSpec` (T038) and US3's token, machines and
  `ANKKA_BROKER_EXTERNAL_BOOTSTRAP` (T053). T067 → T068 → T069; T070 → T071 → T072; T066 last.
- US6 (Phase 8) needs Phase 2 and, for T078's "credential may read" steps, US5's
  `MachineRendering` (T069) — until then the step is `ranElsewhere`. T079 → T080; T081 needs nothing
  of US5, and whichever of T068 and T081 lands second extends the consumer the first made.
- Polish needs every story; T090 can be done at any time after the plan.

## Parallel execution

- After Phase 2: US1 (T018–T031) and US3 (T043–T056) touch disjoint files — `http`'s matcher and
  file on one side, the control plane's machines and `http`'s verifier on the other — except
  `H/Caller.scala` and `H/HttpServer.scala`, which both change: do T025 before T055, or merge by
  hand. US2 (T032–T042) runs beside both.
- Within US1: T018–T024 together; T025 → T026 → T027 while T028 proceeds; T029 and T030 after T025.
- Within US3: T043–T049 together; T050–T054 in order while T055–T056 proceed.
- Within US5: T063–T066 together; T067–T069 beside T070–T072; T074 last.
- Within US6: T075–T078 together; T079–T081 in order; T082–T083 beside them.

## Implementation strategy

MVP is Phase 2 plus US1: a grant made as data on the project, rendered into the file every pod
already mounts, admitted by one matcher on HTTP and gRPC, revoked without a redeploy. It is the path
eitheror's money moves on and the shape every other story reuses (the file, the record, the
routes). Then US3 — a machine, a token, `Caller.Machine` — since partners are the second half of the
driving case and it needs nothing from the broker. US2 next, the broker's side of the same grant.
US6 then, because acceptance is a control plane change that both brand and partner stories need
before a real cross-organization grant is made, and it is cheap once the record exists. US4 ties
the listing to everything built so far. US5 last: it is the one story that changes the
installation's edge (a listener, a certificate, a port, an issuer moved), and the route feed of US3
already serves its case more slowly.
