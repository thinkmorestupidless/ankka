# Implementation Plan: Object Storage — A Bucket Per Service, Provisioned Like a Database

**Branch**: `034-object-storage` | **Date**: 2026-10-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/034-object-storage/spec.md`

## Summary

A descriptor gains two fields. `provisionObjectStorage` gives the service one bucket in the
installation's object store and one storage credential that reaches it and nothing else;
`exposeObjectStorage` makes that bucket reachable from a browser, for presigned URLs. The
developer's program is given the variables any S3 client needs, the platform's own program is
given none, and the SDKs wrap nothing. The platform never deletes a bucket or an object, and a
service applied again under a deleted name is given the bucket it had.

Technically: the store is Garage, one node in a namespace of its own, as a kustomize component
both overlays list (R1, R2). The operator reaches it through an `ObjectStore` trait implemented
over the JDK's HTTP client, so it gains no dependency (R4). A second pure plan beside the
database's decides the phase from what the store reports (R7), and a credential is issued with a
`create` whose conflict is the only thing the operator learns about the Secret, so nothing is read
back from the cluster or from the store (R6). The bucket's name is derived in `crd`, as a
hostname is, so the resource carries two booleans and no writer of it can point a service at
another's bucket (R5). A reachable bucket is one `HTTPRoute` per bucket, owned by the service's
resource, at one hostname the platform derives, with a `ReferenceGrant` in the store's namespace
(R15). The control plane journals the phase alone and derives the name and the address (R14).

Planning found five things the spec did not have, and the spec is amended for each:

- **MinIO cannot be installed.** Its community edition was archived and its images deleted this
  year. The store is Garage (R1).
- **A client needs the region.** A fifth variable, `ANKKA_S3_REGION` (R9).
- **`ANKKA_S3_` is not a reserved prefix.** The shared declaration lists variables kept from the
  developer's program; these are for it. The prefix is declared once and withheld from nobody
  (R10).
- **A browser upload needs a CORS rule only the bucket's owner can set**, so a service owns its
  bucket's settings (R16).
- **The store speaks no TLS.** Requests to it inside the cluster are plain HTTP behind a network
  policy, recorded as a limitation (R17).

And two that change the work without changing the spec: the Deployment is never withheld for a
database, so "no instance starts without a store" is an `envFrom` naming a Secret that does not
exist, not a gate (R8); and a process-hosted service's database credential goes to the platform's
container, so putting these variables on the developer's is new rendering, not a copy (R9).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`core`, `crd`, `controlplane-api`, `controlplane`,
`operator`, `cli`); TypeScript on Node ≥ 22 (`console/package`); YAML (kustomize); Gherkin

**Primary Dependencies**: none added to any main classpath. The operator calls the store with
`java.net.http.HttpClient` and the Jackson `crd` already has. One test dependency: the AWS SDK for
Java's `s3`, in `controlplane`'s tests, to sign URLs with a client nobody here wrote. One
third-party image: `dxflrs/garage:v2.3.0`.

**Storage**: Garage, one node with one volume, for buckets and objects. Kubernetes Secrets for
storage credentials. `AnkkaServiceSpec` gains two fields and `AnkkaServiceStatus` one block, with
the schema. The control plane's journal gains one defaulted field on `ServiceObserved` and one on
`Service`, and two defaulted fields inside the descriptor `ServiceApplied` already holds. No DDL.

**Testing**: munit in `crd`, `core`, `controlplane-api`, `operator`, `controlplane`, `cli`;
`GarageStoreSuite` against the store's real image in a container; six `GherkinSuite`s on k3s,
each running one feature file whole, modelled on `WebHostingClusterSteps`; `SidecarClusterSuite`
and `OperatorClusterSuite` extended; the console's unit, fixtures and Playwright tests; the docs build;
the features check.

**Target Platform**: an installation's cluster — kind locally, any Kubernetes ≥ 1.32 otherwise. A
service on a developer's own machine is given no bucket.

**Project Type**: a platform's operator, control plane, CLI and console; an installation
component; documentation

**Performance Goals**: a reconcile of a service that does not ask makes no call to the store. One
that asks makes one read per pass and, once per operator process, the credential's exchange. No
request of a service passes through the platform on its way to the store.

**Constraints**: no secret key in an action, a log line, a status, the journal or a wire type; the
operator's ClusterRole unchanged and its `dependsOn` still `crd` alone; no object rendered for a
service that does not ask differs from before, so upgrading the operator rolls no pod; `kubectl
apply -k` remains the whole deploy; no bucket and no object deleted by the platform; a stored
journal decodes unchanged; warning-free; `Test / parallelExecution := false` stays; no suite binds
a fixed port or names an image by a literal tag

**Scale/Scope**: about 9 new Scala source files and 22 changed across six modules; about 12 new
suites and 16 changed; 1 new kustomize component of about 8 files and 2 overlays changed; 3
console source files and their tests; 1 new docs page and about 9 changed; 8 feature files and 1
changed, 27 scenarios

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` states as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; one thing performs them | pass | `EnsureBucket`, `EnsureStorageCredential` and `EnsureReferenceGrant` are descriptions with no secret in them; `Fabric8Executor` performs them (R6, R8) |
| Where two parties must agree on a derivation, there is one | pass | `Buckets` in `crd` is the control plane's and the operator's (R5); `PlatformVariables.objectStorage` is the descriptor's rules' and the operator's (R10); one phrase function serves `services get` and the listing (R14) |
| Module dependency direction | pass | `crd` still depends on nothing; `controlplane-api` on `core`; the operator on `crd`, compiling the one shared file |
| The operator cannot reach into the control plane, and depends on as little as possible | pass | no library is added; the store is reached over HTTP with what the JDK has (R4) |
| A field on the resource needs the schema | pass | two spec fields and a status block, with `CrdSchemaSuite` extended to the block's insides (R13) |
| Never render what must not roll | pass | nothing on the pod template of a service that does not ask; the unchanged fixtures gain one removal line and no object (R8) |
| No secret value in the control plane's journal | pass | the control plane never holds a key; it journals a phase (R14) |
| A credential is written where the pod reads it and never read back | pass | `create`, with `patch` for one stated case; no `get`, and no request to the store for a secret (R6). The prerequisite removes the verb (R19) |
| Nothing the platform does may destroy data | pass | no call deletes a bucket or an object; the Secret has no owner; `deleteKey` removes only a key whose secret is in no Secret (R6) |
| Stored forms stay readable both ways | pass | every new journaled field is defaulted and pinned by `EventCompatibilitySuite` (R11, R14) |
| An overlay that only works from the script is not an overlay | pass | `--single-node` removes the store's one imperative step; the script gains a wait (R2) |
| A strategic merge patch names a container that exists | pass | the component's patch on the operator is asserted by shape (R2, S4) |
| A wildcard is one label deep | pass | one hostname, the bucket in the path (R15) |
| A route can be `Accepted` and still not serve | pass | the grant is rendered before the route, and the k3s suite asserts an answer from the store, not the route's condition (R15, R20) |
| Tests are serialised; no fixed port; no literal image tag | pass | nothing changes in `build.sbt`'s test settings; the store's image is a pinned third-party tag, not one this build makes |
| Could this check pass while the thing it checks is false? | pass | R20 names the four checks that could and how each is sharpened; the browser upload asserts the preflight (R16) |
| Each acceptance scenario ends as a test that fails without the feature | pass | 27 scenarios; 24 run by a `GherkinSuite`, three by tests named for them (R20) |
| Docs: pages stand alone, samples from tested code, new pages in nav and a skill | pass | R21 |
| Every tracked file claimed by a CI path filter | pass | `kustomization/**` and `features/**` are claimed already |
| Every port a workload has is mutual TLS | **departure** | the store's ports are plain HTTP inside the cluster; justified under *Complexity Tracking* (R17) |

**Violations to justify**: one, the last row.

**Post-design re-check**: unchanged. The contracts add no dependency to a main classpath, no verb
to the operator's ClusterRole and no field beyond the three on the resource.

## Project Structure

### Documentation (this feature)

```text
specs/034-object-storage/
├── plan.md              # this file
├── research.md          # R1–R22: decisions with file-level evidence; seven things to verify first
├── data-model.md        # the bucket, the key, the Secret, the resource's fields, what is journaled
├── quickstart.md        # the validation runs: pure → the store → offline → console → k3s → docs → by hand
├── contracts/
│   ├── descriptor-and-status.md   # the two fields, the refusals, the names, what a member reads
│   ├── operator.md                # settings, the store's interface, the plan, actions, what is rendered
│   └── installation.md            # the component, who may connect, the overlays
└── tasks.md             # /speckit-tasks, not created here
```

The acceptance scenarios are in `features/object-storage/` (seven files),
`features/web-hosting/object-storage.feature` and one changed outline in
`features/secrets/project-secrets.feature`, in the words of `GLOSSARY.md`.

### Source Code (repository root)

```text
crd/…/crd/Buckets.scala                                        # new: the name, the Secret, the address
crd/…/crd/Hostnames.scala                                      # StorageLabel
crd/…/crd/AnkkaService.scala                                   # two spec fields; ObjectStorageStatus
kustomization/components/crd/ankkaservice.yaml                 # the same, declared

modules/core/…/core/PlatformVariables.scala                    # ObjectStoragePrefix, objectStorage

controlplane-api/…/api/descriptors.scala                       # ServiceSpec's two fields and rules;
                                                               # the two reserved lists; ServiceStatus's three fields
controlplane/…/controlplane/api/ServiceEndpoint.scala          # the name's limit at apply; the address
controlplane/…/controlplane/deploy/ServiceProjection.scala     # the two fields; the name's limit
controlplane/…/controlplane/deploy/StatusIngest.scala          # the phase; the detail
controlplane/…/controlplane/domain/events.scala, model.scala   # one field each; the phrase
controlplane/…/controlplane/application/ServiceEntity.scala, ServiceRows.scala

operator/…/operator/ObjectStore.scala                          # new: the trait and its values
operator/…/operator/GarageStore.scala                          # new: over java.net.http
operator/…/operator/ObjectStorage.scala                        # new: the plan, decide, the observation
operator/…/operator/StorageCredential.scala                    # new: ensure, over ObjectStore and SecretWriter
operator/…/operator/Settings.scala                             # ObjectStoreSettings
operator/…/operator/Action.scala, Executor.scala               # three actions; observeObjectStorage
operator/…/operator/Rendering.scala                            # objectStorageActions; the variables per hosting
operator/…/operator/LifecycleRules.scala, ServiceReconciler.scala   # the status block
operator/…/operator/Operator.scala                             # builds the store from the settings
operator/src/test/…/ObjectStoreStack.scala                     # new: the component on k3s
operator/src/test/resources/golden/object-storage.txt          # new
operator/src/test/resources/unchanged/*.json.txt               # one removal line each

kustomization/components/garage/                               # new: namespace, config, statefulset, service,
                                                               #      policy, role, secrets, operator patch
kustomization/overlays/local/kustomization.yaml                # the component
kustomization/overlays/cloud/kustomization.yaml                # the component; the Secrets deleted
kustomization/deploy-local.sh                                  # one wait

cli/…/cli/Output.scala, mcp/AnkkaTools.scala                   # three lines; the tools' descriptions

console/package/src/client/schemas.ts                          # three fields
console/package/src/routes/service.tsx                         # the fact; the delete text
console/package/src/testing/fake-control-plane.ts              # the fields; the reserved suffixes
console/package/fixtures/control-plane/                        # written again
console/e2e/tests/services.spec.ts

controlplane/src/test/…/ObjectStorageClusterSteps.scala        # new: the steps, and five suites
controlplane/src/test/…/WebHostingClusterFeatures.scala        # one more suite, with the store
sidecar/src/test/…/SidecarClusterSuite.scala                   # the process and the module
project/Dependencies.scala, build.sbt                          # the AWS SDK, Test only

docs/platform/object-storage.md                                # new
docs/reference/service-descriptor.md, limitations.md; docs/platform/secrets.md, networking.md,
docs/platform/install-cloud.md; docs/operate/status-and-history.md; docs/deploy/upgrading.md
mkdocs.yml, tools/docs/skill/ankka-platform/SKILL.md, the rendered skills
```

**Structure Decision**: no module, image this build makes or published artifact is added. The one
new directory is the component. Each piece goes where its kind already lives: the name beside
`Hostnames` in `crd`, the plan beside `Provisioning` in the operator, the refusals in
`ServiceSpec.problems`, the phrase beside the database's in the `Service` model, the page beside
`databases.md`. The store's client is the one new kind of thing the operator holds, and it is
behind a trait in the operator, not a module.

## Order of work

Cut by user story, tests before the code they hold. Slices 1 to 3 need no cluster.

0. **Spikes and the prerequisite's shape** — S1, S2 and S3 of the research, each a throwaway
   under `-Dankka.spikes=on`: the image with `--single-node`, a dotted bucket path-style and
   presigned, and two routes from two namespaces on one hostname behind a grant. They come first
   because R2, R5 and R15 rest on them, and each is cheaper to learn now than after the rendering
   exists. The prerequisite (R19) is a separate branch and pull request; only SC-003's test waits
   on it.
1. **Names, fields and rules** (FR-001, FR-005, FR-009, FR-011, FR-013). `Buckets`, the
   declaration, the descriptor's two fields and refusals, the reserved suffixes, the resource's
   fields and schema, the projection. All offline.
2. **The store and the credential** (FR-002, FR-003, FR-008's interface). `ObjectStore`,
   `GarageStore` against the real image, `StorageCredential.ensure` against doubles, the settings.
3. **The plan and the rendering** (FR-004, FR-006, FR-007, FR-017). `decide`, the actions, the
   variables per hosting, the status block, the golden file, the repin.
4. **The component** (FR-008). The directory, both overlays, `RemoteOverlaySuite`,
   `ObjectStoreStack`, the script's wait.
5. **User Stories 1 to 3 on k3s.** The steps and the four suites for provisioning, isolation,
   kept and own-store; `OperatorClusterSuite`'s cases.
6. **The status a member reads** (FR-007, FR-012, FR-016). The event's field, the phrase, the
   wire, the CLI, the console and its fixtures.
7. **User Story 4** (FR-014, FR-015). The grant, the route, the variable, the public address, the
   reachable suite with the CORS preflight.
8. **Documentation** (FR-010, FR-018), then the whole build.

Slice 6 depends on 3 only; slice 7 on 3 and 4. Slices 5 and 6 do not depend on each other.

## Complexity Tracking

One principle is departed from, in the first row. The others are places the plan departs from the
spec's wording or widens the platform's surface, each with the simpler thing that was rejected.

| Departure | Why needed | Simpler alternative rejected because |
|---|---|---|
| The store's ports are plain HTTP inside the cluster, where every other port a workload has is mutual TLS (FR-018, added in planning) | Garage serves no TLS, and a certificate from the platform's authority would have to be trusted by every S3 client a developer brings, in a container that is given no certificate files | a TLS proxy in the store's pod is a sixth variable and a mounted file before "any S3 client works" is true again; it can be added later without changing anything decided here. Saying nothing was rejected: it is written into the limitations (R17) |
| Garage, where the spec said MinIO (FR-008, amended) | MinIO's community edition is archived and its images are deleted | building an image from an archived source makes this repository its maintainer (R1) |
| Five variables and a sixth for a reachable bucket, where the spec said four (FR-004, amended) | a signature is made for a region, and for the address a browser uses | a client left to its default region fails its first request with an error that names neither the region nor the fix (R9) |
| The storage credential owns its bucket's settings (FR-017, added in planning) | a browser upload needs a CORS rule, which only the bucket's owner can set | `read` and `write` alone makes the upload scenario pass with `curl` and fail in a browser; the operator setting CORS decides every service's origins for it (R16) |
| The operator deletes access keys | a pass interrupted between issuing a key and writing its Secret leaves a key whose secret nobody holds | asking the store for the secret again makes "never read back" true of one API and false of the other (R6) |
| A `ReferenceGrant` per project in the store's namespace, never removed | the bucket's route is in the project's namespace so that deleting the service removes it, and a reference across namespaces needs a grant with no selector | a route in the store's namespace cannot be owned and would outlive its service, leaving a deleted service's bucket reachable (R15) |
| The operator reads `spec.env` to tell "its own" from "none" | the flag's default is `false`, so the flag alone cannot | a third value on the resource states twice what the environment already says (R7) |
| `RenderingUnchangedSuite`'s fixtures are repinned | the route's removal is rendered for every service, so that dropping both fields in one apply leaves no route behind | a removal only for services that still ask leaves that bucket reachable; the repin's diff is one line per fixture and no object (R8) |
| A project secret may no longer be named `…-storage` | a storage credential sits where a sibling's descriptor could name it | without the rule the isolation scenario passes while one line of a descriptor hands a service another's credential (R12) |
| The store is one replica | kind has one node, and `--single-node` is what makes `kubectl apply -k` the whole deploy | a replicated store needs a layout applied after the nodes meet, which is an installation's own overlay and is documented, not built (R2) |
| The control plane derives the bucket's name and address and journals neither | both are functions of what it already has | journaling them makes a change to the rule a migration (R14) |

**Operational consequences** to announce in the release:

- An installation that adds the component must create two Secrets out of band in the cloud
  overlay, or the store and the operator do not start.
- A project secret named `…-storage` can no longer be set, and a descriptor that takes a variable
  from one is refused at its next apply.
- The prerequisite change removes a verb from the operator's ClusterRole; it is its own release
  note.
