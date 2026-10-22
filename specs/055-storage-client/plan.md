# Implementation Plan: A Storage Client in the SDKs

**Branch**: `055-storage-client` | **Date**: 2026-10-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/055-storage-client/spec.md`

## Summary

A service reaches its bucket as it reaches its secrets: `context.storage`, on the contexts that
may block, with put and get as streams, delete, list by prefix, stat, signed URLs and, for a
public bucket, an address anyone can read. Buckets are named in the descriptor, each private,
signed or public, or supplied by the developer, and the client reaches each by name. One
implementation in the runtime speaks S3 over the JDK's HTTP client with a signer of its own; a
directory on disk stands in on a laptop, with the service serving its own signed and public
addresses; memory stands in under test. Python and TypeScript speak S3 themselves from the same
variables; a Rust module reaches the runtime's client through a host import, whole objects at a
time. The platform side makes, grants, routes and reports the named buckets, with Garage's web
listener behind a rewriting route for public reads and two parameters more on the cloud
provider's bucket request.

Technically (research R1 to R18): the trait in `sdk`, three `Bucket` implementations in
`runtime`, wired exactly as the secret store is (R1, R2); a SigV4 signer of about 150 lines
because nothing in the tree signs an S3 request (R3); **streamed request and response bodies
added to `ankka-http`**, because every body is read whole today and SC-002 cannot hold without
them (R4); a **`storage` host import for modules**, because feature 030's outbound call reaches
only another ankka service and a module has no other network (R6); named buckets threaded through
the descriptor, the resource, the operator's plan per bucket, the status as memory of a bucket no
longer named, and the control plane's report (R7); one key per service granted on every made
bucket (R8); the Garage web listener, website access per bucket and a `URLRewrite` route for a
public bucket (R9); `name`, `public` and `publicUrl` on the cloud provider's bucket request and
`buckets` on its credential request, amendments to 044's contract and to `ankka-gcp` (R10).

Planning found three things the spec assumed wrongly or did not know, each decided in research
and written back as a planning amendment below: a module cannot speak S3 (R6); the HTTP layer
cannot stream (R4); a put declares its length (R5).

## Technical Context

**Language/Version**: Scala 3.9 on JDK 21 (`sdk`, `runtime`, `http`, `agent`, `testkit`,
`sidecar`, `core`, `crd`, `controlplane-api`, `controlplane`, `operator`, `cli`, `storage-mover`);
Python ≥ 3.12 (`sdks/python`); TypeScript on Node ≥ 22.22 (`sdks/typescript`, `console/package`);
Rust 1.88 edition 2024 for `wasm32-unknown-unknown` (`sdks/rust`); protobuf (`protocol`); YAML
(kustomize, CRD schema); Gherkin

**Primary Dependencies**: none added anywhere. The runtime's S3 client is `java.net.http` and
`javax.crypto`; Python's is `http.client`, `hashlib`, `hmac`; TypeScript's is `node:http`,
`node:https`, `node:crypto`; Rust's is the host. Test only: the Garage image testcontainers
already starts; the AWS SDK stays where it is.

**Storage**: the installation's Garage or Google Cloud Storage through S3; a directory under the
working directory on a laptop; memory under test. `AnkkaServiceSpec` gains `buckets`, its status
`objectStorage.buckets`, with the schema. The control plane's journal gains a defaulted field on
`ServiceObserved`. No DDL.

**Testing**: munit in every Scala module; `S3BucketSuite` against Garage by testcontainers;
`StreamedBodySuite` under `-Xmx512m`; `StorageClientFeatures` (`GherkinSuite`, in-process) and
`StorageClientClusterFeatures` (k3s, unlisted in the nightly matrix); pytest, vitest and cargo
test with the rules fixture; the four conformance references and `ConformanceSuite`'s `storage.*`
cases; the console's unit and Playwright tests; the docs build; the features check.

**Target Platform**: a deployed service on an installation (Garage locally, Google Cloud Storage
with `ankka-gcp`); a service on a developer's machine with `sbt run` or beside the sidecar; the
test kits.

**Project Type**: an SDK surface in four languages; the runtime, the HTTP layer and the sidecar;
the platform's descriptor, resource, operator, cloud contract, control plane, CLI and console;
documentation

**Performance Goals**: a 2 GiB object passes an endpoint's put and get with a 512 MiB heap
(SC-002); a `signedUrl` or `url` is a computation, no request (SC-005's "nothing was sent"); a
reconcile of a service without named buckets makes the calls it made before (unchanged render);
one more admin call per named bucket per pass (website access, compared first).

**Constraints**: no object or part of one through the sidecar's rpc for Python and TypeScript; a
module's body at most 4,000,000 bytes; no secret in an action, a status, the journal or a log
line; the operator's dependencies unchanged and its RBAC unchanged; nothing rendered for a service
without `buckets` differs from before (`RenderingUnchangedSuite`, no repin); the platform deletes
no bucket, object or Secret; a stored journal decodes unchanged; warning-free; serialized tests;
no suite binds a fixed port; `PlatformVariables.scala` stays standard-library only; the
`ankka-docs` tool gains no ankka constant.

**Scale/Scope**: about 22 new Scala source files and 40 changed across twelve modules; 6 new
Python, 5 TypeScript and 6 Rust source files and about 20 changed; 1 proto message pair and one
ABI import; 5 kustomize files changed; about 6 console files; 1 new docs page and 7 changed; 7
feature files already written (54 scenarios).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` and the rule files state, as 034's and 039's plans read them:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; one thing performs them | pass | the client is a trait; a handler calls it and the runtime's implementation does the I/O; `EnsureBucket`, `SetWebsiteAccess`, `EnsureHttpRoute` are descriptions `Fabric8Executor` performs (R1, R9) |
| Explicit registration, never scanning | pass | buckets are named in the descriptor; the directory store's endpoint is registered by the runtime when its backend says so (R12) |
| Module dependency direction | pass | `sdk` gains traits with no Pekko; `runtime` the implementations; `http` the streamed bodies; nothing below `runtime` sees S3; the operator depends on `crd` only; `PlatformVariables` compiled into it unchanged in kind (R15) |
| Where two parties must agree on a derivation, there is one | pass | `Buckets.named` and `Buckets.keyName` in `crd`; the variable's `<NAME>` derivation once in `PlatformVariables`, read by the operator and the runtime; the object name rule once in a fixture every SDK reads (R7, R15, R16) |
| The wire name is separate from the method name | pass | the `storage` import's ops are an enum in the proto; the SDK method names are each language's |
| A field on the resource needs the schema | pass | `spec.buckets.items` and `status.objectStorage.buckets.items`, `CrdSchemaSuite` both ways |
| Never render what must not roll | pass | a service without `buckets` renders what it rendered; a named bucket's route removal is rendered from the status, not for every service (R7) |
| A credential is written where the pod reads it and never read back | pass | the one key is issued and granted per bucket, then `create`d; a supplied bucket's key is a project secret by `secretKeyRef`; the operator and the control plane read no key (R8) |
| Nothing the platform does may destroy data | pass | no bucket, object or Secret is deleted; `Kept` is a phase, not a removal; a failed put leaves the old object (R5, R7) |
| Stored forms stay readable both ways | pass | `ServiceObserved.buckets` defaults; `EventCompatibilitySuite` pins it; the old mover names accepted |
| Which variables are the platform's is said once | pass | `ANKKA_STORAGE_BACKEND`, `ANKKA_STORAGE_DIR` declared in `PlatformVariables`; the `<NAME>` families documented under `ANKKA_S3_` (R15) |
| A behaviour an older runtime must refuse is a new rpc or import | pass | the `storage` import is new; a module importing it is refused by an older runtime at load, naming it (R6) |
| Could this check pass while the thing it checks is false? | pass | the 2 GiB case runs under a heap it cannot fit in; "nothing was sent" is asserted against a store that is unreachable; the public read is `curl` with no credential from outside; `Kept` is asserted after a reapply, not a status alone; `ranOutside` names the Google-only scenarios, never skips silently |
| Each acceptance scenario ends as a test that fails without the feature | pass | 54 scenarios: 29 by `StorageClientFeatures` in-process, 18 by `StorageClientClusterFeatures` on k3s, 5 by the SDK suites and conformance (named in `ranElsewhere`), 2 by ankka-gcp (`ranOutside`) |
| Docs: pages stand alone, samples from tested code, new pages in nav and a skill | pass | R17 |
| Every tracked file claimed by a CI path filter | pass | no new top-level directory; `protocol/fixtures/storage/` under the existing `protocol/**` filters and the three SDK copies |
| Every port a workload has is mutual TLS | pass | the web listener is reached by the gateway alone, inside the store's namespace, under 034's recorded limitation for Garage's plain HTTP; the laptop's reserved path is loopback |
| A feature consumes another's contract, never a copy of it | pass | 044's bucket and credential requests are amended, not reimplemented; the scripted provider implements the amendments (R10) |

**Violations to justify**: none. Three departures from the spec's letter are in *Complexity
Tracking* and written into the spec as planning amendments.

**Post-design re-check**: unchanged. The contracts add traits, one import, two streamed body
kinds, fields the schema declares, one Garage listener, and two parameters on a request.

## Project Structure

### Documentation (this feature)

```text
specs/055-storage-client/
├── plan.md                        # this file
├── research.md                    # R1–R18: decisions with evidence; two spikes to run first
├── data-model.md                  # the SDK's values, the settings, the directory layout, the resource, the requests, the import
├── quickstart.md                  # the validation runs: spikes → offline → SDKs → k3s → docs → by hand
├── contracts/
│   ├── sdk.md                     # the client in Scala, Python, TypeScript and Rust; the rules fixture
│   ├── descriptor-and-status.md   # the buckets list, its refusals, the variables, what a member reads, the reserved path
│   ├── operator-and-cloud.md      # settings, the component, actions, the public route, the cloud amendments, the mover
│   └── runtime-and-protocol.md    # configuration, AnkkaService, streamed bodies, the reserved endpoint, the import, the kits
└── tasks.md                       # /speckit-tasks, not created here
```

The acceptance scenarios are in `features/storage-client/` (seven files), in the words of
`GLOSSARY.md`.

### Source Code (repository root)

```text
modules/core/…/core/PlatformVariables.scala                   # StorageBackend, StorageDir; bucketVariableName(<name>)
modules/sdk/…/sdk/ObjectStorage.scala                          # new: ObjectStorage, Bucket, StoredObject, ObjectInfo, ObjectEntry, ObjectRules, unavailable
modules/sdk/…/sdk/contexts.scala, TimedAction.scala            # storage beside secrets
modules/sdk/src/test/…/ObjectRulesSuite.scala, StorageAvailabilitySuite.scala

modules/runtime/…/runtime/storage/StorageSettings.scala        # new: backend, directory, the ANKKA_S3_* families
modules/runtime/…/runtime/storage/SigV4.scala                  # new: header and query signing
modules/runtime/…/runtime/storage/S3Bucket.scala               # new: the five operations over java.net.http
modules/runtime/…/runtime/storage/DirectoryBucket.scala        # new
modules/runtime/…/runtime/storage/MemoryBucket.scala           # new
modules/runtime/…/runtime/storage/ObjectStorages.scala         # new: build (the one place a backend is chosen), the bucket map
modules/runtime/…/runtime/storage/DirectoryStorageEndpoint.scala   # new: /_ankka/storage, tokens
modules/runtime/…/runtime/Ankka.scala                          # build beside SecretStores; AnkkaService.storage; withStorage
modules/runtime/…/runtime/StepScope.scala, WorkflowHost.scala, ProjectionRuntime.scala, TopicHandlers.scala, TimerSweeper.scala, TimerRuntime.scala
modules/runtime/src/main/resources/reference.conf              # ankka.storage
modules/runtime/src/test/…/storage/*Suite.scala                # settings, SigV4, S3 (Garage), directory, memory, endpoint

modules/http/…/http/codecs.scala, responses.scala, HttpEndpoint.scala, HttpServer.scala   # StreamedBody, postStream/putStream, Streamed, max-stream-bytes, the _ankka refusal
modules/http/…/http/EndpointClients.scala                      # storage
modules/http/src/main/resources/reference.conf                 # ankka.http.max-stream-bytes
modules/http/src/test/…/StreamedBodySuite.scala, ReservedPathSuite.scala

modules/agent/…/agent/Agent.scala, AgentRuntime.scala, autonomous/AutonomousAgent.scala, autonomous/AutonomousAgentHost.scala, blueprint/BlueprintRegistry.scala

modules/testkit/…/testkit/AnkkaTestKit.scala, StorageBackendChoice.scala (new), InMemoryObjectStorage.scala (new), ConsumerTestKit.scala and the other unit kits
modules/testkit/src/test/…/StorageComponents.scala (new, the docs' samples), AnkkaTestKitStorageSuite.scala, StorageClientFeatures.scala

protocol/src/main/protobuf/ankka/protocol/v1/client.proto      # StorageRequest, StorageReply
protocol/WASM-ABI.md, protocol/README.md                       # storage, 1.21
protocol/fixtures/storage/rules.json                           # new
sidecar/…/sidecar/ClientLogic.scala, wasm/HostImports.scala, wasm/ModuleLoader.scala   # the import
sidecar/…/sidecar/Main.scala                                   # the _ankka/storage prefix admitted for a process's endpoint
sidecar/src/test/…/conformance/ConformanceSuite.scala, ConformanceReference.scala, ConformanceTarget.scala

sdks/python/src/ankka/storage.py (new), sigv4.py (new), __init__.py, endpoint.py, consumer.py, graph.py, timed_action.py, agent.py, autonomous.py, workflow.py, server.py, testkit/unit.py, testkit/integration.py
sdks/python/examples/shopping_cart/conformance.py, tests/test_storage.py (new)
sdks/typescript/src/storage.ts (new), sigv4.ts (new), index.ts, endpoint.ts, consumer.ts, graph.ts, timedAction.ts, agent.ts, autonomous.ts, workflow.ts, testkit/kinds.ts, testkit/integration.ts
sdks/typescript/examples/shopping-cart/conformance.ts, test/storage.test.ts (new)
sdks/rust/ankka/src/storage.rs (new), abi/imports.rs, context.rs, components/{consumer,endpoint,workflow,timed_action}.rs, agent.rs, autonomous.rs, testkit/{unit,integration}.rs
sdks/rust/examples/shopping-cart/src/conformance.rs, ankka/tests/storage.rs (new)
sdks/{python,typescript,rust}: the protocol copies (scripts/proto.*)

crd/…/crd/AnkkaService.scala, Buckets.scala                     # BucketSpec, BucketStatus; named, keyName, problems per name
kustomization/components/crd/ankkaservice.yaml                  # the same, declared
kustomization/components/garage/config.yaml, service.yaml, zero-trust.yaml, operator-patch.yaml   # [s3_web], port web, the gateway admitted, ANKKA_OBJECT_STORE_WEB_SERVICE
kustomization/components/garage-replicated/…                    # the same

controlplane-api/…/api/descriptors.scala                        # BucketSpec, SuppliedBucket, BucketExposure, the refusals; ServiceStatus.buckets, BucketReport
controlplane/…/controlplane/deploy/ServiceProjection.scala, StatusIngest.scala, DeployConfig.scala
controlplane/…/controlplane/domain/events.scala, model.scala    # ServiceObserved.buckets; Service.buckets; the gates
controlplane/…/controlplane/application/ServiceEntity.scala, ServiceRows.scala
controlplane/…/controlplane/api/ServiceEndpoint.scala           # the supplied credential's entries checked; the gates' words
controlplane/src/test/…/StorageClientClusterFeatures.scala (new), ObjectStorageGcsClusterFeatures.scala, ServicePods.scala

operator/…/operator/Settings.scala, ObjectStoreSettings.scala   # webService
operator/…/operator/ObjectStore.scala, GarageStore.scala         # setWebsite; BucketInfo.website
operator/…/operator/ObjectStorage.scala                          # decide per bucket and exposure; status.buckets; Kept
operator/…/operator/StorageCredential.scala                      # lists of buckets; keyName
operator/…/operator/Action.scala, Executor.scala                 # SetWebsiteAccess; EnsureBucket(keyName); the list forms; observe per bucket
operator/…/operator/Rendering.scala                              # bucketsActions, the public route, the variables per bucket, routes from the status
operator/…/operator/ServiceReconciler.scala                      # per-bucket plans; cloud requests per bucket
operator/…/operator/CloudRequests.scala                          # name, public, publicUrl; buckets
operator/…/operator/StorageMove.scala                            # the list
operator/src/test/…/cloud/ScriptedFulfilment.scala, ScriptedCloudProvider.scala, BucketNames.scala
operator/src/test/resources/golden/storage-client.txt            # new
storage-mover/…/mover/Mover.scala, Main.scala                    # parallel lists

cli/…/cli/Output.scala, ControlPlaneClient.scala, mcp/AnkkaTools.scala   # the bucket lines
console/package/src/client/schemas.ts, routes/service.tsx, testing/fake-control-plane.ts; console/package/fixtures/control-plane/

docs/build/object-storage.md (new); docs/platform/object-storage.md; docs/platform/cloud-provider.md; docs/reference/{service-descriptor,limitations,configuration}.md; docs/build/http-endpoints.md; docs/operate/status-and-history.md; mkdocs.yml; tools/docs/skill/{ankka-endpoints,ankka-workflows,ankka-platform}/SKILL.md; the rendered skills
```

**Structure Decision**: nothing new at the top level and no new module: the client goes where
the secret store went, the signer and the three stores under `runtime/storage/`, the streamed
bodies in `http` beside the strict ones, the import beside `get_secret`, and each platform change
beside 034's and 039's. The one new directory under `protocol/fixtures/` follows `secrets/`.

## Order of work

Cut by user story, tests before the code they hold. Slices 1 to 5 need no cluster.

0. **The spikes** — S1 (`UNSIGNED-PAYLOAD`, presigned URLs, paging against Garage, then Google by
   hand) and S2 (the dotted `Host` and the rewrite on k3s). Each is a test that stays.
1. **The trait, the rules and the contexts** (FR-001, FR-003, FR-004's codes, story 2).
   `ObjectStorage`, `Bucket`, `ObjectRules` with the fixture, `storage` on every context,
   `StepScope.stepsOnly`, `StorageAvailabilitySuite`, `MemoryBucket`, `ObjectStorages.build`,
   `AnkkaService.storage`, the test kit's `Memory` choice. `StorageClientFeatures` runs
   `contexts.feature` and `testkit.feature` green here.
2. **The S3 store and the signer** (FR-005, FR-006, story 1 and 3 on a bucket). `SigV4`,
   `S3Bucket`, `StorageSettings` with the families, `S3BucketSuite` against Garage.
3. **Streamed bodies** (FR-002's streams, SC-002). `StreamedBody`, `postStream`/`putStream`,
   `Streamed`, `max-stream-bytes`, `StreamedBodySuite` under `-Xmx512m`; `client.feature`'s
   "larger than an instance's memory" through an endpoint.
4. **The directory store and the reserved path** (FR-012, story 5). `DirectoryBucket`,
   `DirectoryStorageEndpoint`, the `_ankka` refusal in `validate`, `local.feature` green
   in-process.
5. **Python, TypeScript and Rust** (FR-014, story 6). The fixture copies; Python's and
   TypeScript's client, signer, directory and memory stores, mixins, kits, conformance regions;
   the proto messages and the `storage` import; the sidecar's `HostImports` and `ClientLogic`;
   Rust's `storage.rs`, the import block, the native host; the four conformance references and
   `ConformanceSuite`'s cases; the SDKs' tests. `languages.feature`.
6. **Named buckets: descriptor, resource, control plane** (FR-007, FR-010's status half).
   `BucketSpec` and its refusals, the projection, the schema, `BucketReport`, the gates, the CLI
   lines, the console. Offline.
7. **Named buckets: the operator on Garage** (FR-008 on Garage, FR-009's Garage half, FR-011's
   variables). `Buckets.named`/`keyName`, the per-bucket plan, `EnsureBucket(keyName)`, the
   credential on a list, `SetWebsiteAccess`, the public route, `Kept` from the status, the
   component's web listener, `StorageClientClusterFeatures` for `buckets.feature` and
   `signed-urls.feature`.
8. **The cloud path and the move** (FR-008 on GCS, FR-009, FR-010's move). `CloudRequests`'
   `name`, `public`, `buckets`, `publicUrl`; the scripted provider; the per-bucket requests in
   `ServiceReconciler`; the mover's lists; `ObjectStorageGcsClusterFeatures` extended;
   `ranOutside` for the Google-only scenarios; the amendments written into
   `docs/platform/cloud-provider.md`.
9. **Documentation** (FR-015), then the whole build.

Slice 5 needs 1, 2 and 4; slice 7 needs 6; slice 8 needs 7. Slices 2, 3 and 4 are independent of
each other after 1.

## Complexity Tracking

No constitution principle is departed from. Three places depart from the spec's letter, each
written back into the spec as a planning amendment, and two widen the platform's surface, each
with the simpler thing rejected.

| Departure | Why needed | Simpler alternative rejected because |
|---|---|---|
| A module's client is a host import answered by the runtime's client, so a module's objects pass through the platform's program, at most 4,000,000 bytes (R6) | feature 030's outbound call reaches only another ankka service by name; a module has no socket | a general HTTP import for modules is a change to the zero-trust model and belongs to no feature; leaving Rust out was declined in clarification |
| Streamed request and response bodies in `ankka-http` (R4) | every body is read whole with an 8 MiB limit, so a large upload cannot reach a handler and SC-002 cannot hold | "use a signed URL for anything large" keeps the endpoint path at 8 MiB and makes story 1's scenario false through an endpoint |
| A put declares its length (R5) | S3's `PutObject` needs `Content-Length`; a stream of unknown length needs `aws-chunked`, which is where Garage's checksum trap lives | buffering an unknown length to a temporary file hides a pass over the object behind a convenience; the endpoint knows its `Content-Length` |
| Garage's web listener, enabled in the component, and a rewriting route (R9) | Garage serves nothing unsigned without it, and picks a bucket by `Host` | a reader of the platform's own is a fifth image doing what the store does; a hostname per bucket needs a certificate per bucket |
| Two parameters and an output on the cloud provider's bucket request, and a list on its credential request (R10) | a public bucket and one key over several buckets are the provider's to make | one credential request per bucket is N keys and N Secrets where the spec says one credential; a public bucket by a separate kind is a second request shape for one flag |

**Operational consequences** to announce in the release:

- The `garage` and `garage-replicated` components gain a web listener on 3902 and the operator
  the setting that names it; an installation that pins the component must take both, or a
  public bucket is `Failed` naming the setting.
- `ankka-gcp` must implement `name`, `public`, `publicUrl` and `buckets` before a public or named
  bucket works on Google Cloud Storage; until it does, such a bucket's request is `Failed` with
  the provider's reason and the service does not start.
- The protocol is 1.21 and the ABI gains `storage`; a module built with the new crate and using
  the client is refused by an older runtime at load, naming the import.
- The limitations page says what the client is not: no directories, rename, copy, append or
  partial writes; a put declares its length; a module's object is at most 4,000,000 bytes; a gRPC
  endpoint has no client; a supplied bucket is checked on its first call.
