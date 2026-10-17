# Implementation Plan: Installation Models — A Model Server the Installation Runs

**Branch**: `053-installation-models` | **Date**: 2026-10-17 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/053-installation-models/spec.md`

## Summary

An installation may run one model server: Ollama, in a namespace of its own, behind a door only a
holder of a service certificate can open. A platform administrator declares the models by tag; the
server fetches each once, keeps it loaded, records its digest and reports it served, fetching or
failed. A service names one through feature 052's preset `installation` with a model and nothing
else; the platform supplies the address and the certificate, the operator holds a service back until
its model is served and fails it, naming what is served, when it never will be; the control plane
shows the served models in the installation's status. Nothing of a call leaves the installation, and
a developer's program, which holds no certificate, cannot reach the server at all.

Technically: the component is a two-container StatefulSet (R3). Beside Ollama runs the **keeper**, a
small JVM of ankka's (`model-keeper`, image `ankka-model-keeper`, R2) that is the mutual-TLS door
(`RotatingTls` and the proxy's `TlsTransport` reading of a caller, over the JDK's HTTP server), runs
the declaration loop against Ollama on the pod's loopback (pull, preload, digest, remove), answers a
status document, pushes its metrics over OTLP and serves a plain probe. Ollama itself listens on
`127.0.0.1` only, so the keeper is the only way in (R1). The operator learns the server from three
settings its patch sets (R4), reads the keeper's status over a plain port its policy admits the
operator alone to, decides per service with a pure function in `ObjectStorage.decide`'s shape (R6),
and gives every platform container `ANKKA_MODELS_ADDRESS` (R4). The sidecar's `Models` and a Scala
constructor resolve the preset from that address with the service's own certificate through the
OpenAI SDK's `sslSocketFactory` (R5). The control plane reads the keeper's status with its own
certificate and adds it to `GET /installation` (R7).

Planning found four things the spec did not have, and the spec is amended for each:

- **Remote clusters are a spec, not code.** Feature 050 has no implementation on `main`, so "a
  cluster without a model server" and "status per cluster" are designed for (the status is a list
  of clusters with one entry, `home`) and the remote scenario waits on 050 (R11).
- **Metrics are pushed, not scraped.** Ollama has no metrics endpoint and the collector has no
  scrape receiver; the keeper pushes OTLP as every platform program does (R8). FR-014's "scraped"
  becomes "reach".
- **Adding the component rolls every service once.** `ANKKA_MODELS_ADDRESS` is a variable on every
  platform container, as the broker's are, so an installation that adds the server rolls its
  services as it did when it added a broker. An installation without it renders byte for byte what
  it rendered before (R4).
- **The variable cannot live under `ANKKA_MODEL_`.** Feature 052's family reads
  `ANKKA_MODEL_<NAME>_<FIELD>`, so `ANKKA_MODEL_SERVER_URL` would be a provider called `server`. The
  platform's variable is `ANKKA_MODELS_ADDRESS`, platform-only, outside the family (R4).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`core`, `crd`, `controlplane-api`, `controlplane`,
`operator`, `cli`, `agent`, `sidecar`, `proxy`, new `model-keeper`); YAML (kustomize); bash
(`deploy-local.sh`); Gherkin

**Primary Dependencies**: none added to any main classpath. The keeper is `proxy-core`, `proxy`
(for `TlsTransport` and `RotatingServerTls`), `runtime` (for `RotatingTls`) and `telemetry-otlp`
(for `Metrics`), all of which exist. One third-party image: `ollama/ollama:0.40.6`, MIT, published
for `linux/amd64` and `linux/arm64` (R1). Feature 052 must have landed: the preset mechanism, the
Chat Completions provider and named providers are what this feature extends (R5).

**Storage**: one PersistentVolumeClaim per model server for Ollama's models. A ConfigMap for the
declaration. `AnkkaServiceStatus` gains one block, with the schema. The `Installation` wire type
gains one optional field. No DDL, no journal change: the control plane records nothing about
installation models; it reads them live.

**Testing**: munit in `core`, `crd`, `controlplane-api`, `operator`, `controlplane`, `cli`, `agent`,
`sidecar` and `model-keeper`; the keeper's engine and loop against a stand-in Ollama on loopback;
`RenderingGoldenSuite` and `RenderingUnchangedSuite`; `RemoteOverlaySuite` for the new overlay and
the cloud patch; one `GherkinSuite` on k3s, `InstallationModelsClusterFeatures`, running
`features/installation-models/` with `ModelServerStack`; `TelemetryStoreSuite` extended; `DoorSuite` holding the call's span; the docs
build; the features check.

**Target Platform**: an installation's cluster: kind locally, when asked for; any Kubernetes ≥ 1.32
otherwise. A service on a developer's own machine has no installation model and is told so.

**Project Type**: an installation component with a program of its own; the operator, the control
plane, the CLI, the sidecar and the agent module; documentation

**Performance Goals**: a reconcile of a service that names no installation model makes no call to
the keeper. One that names one makes one status read per pass, cached per pass across services. A
model call passes through the keeper once, streamed, adding one hop on the pod's loopback. The
local installation's small model answers a short message with a tool call within the default model
timeout on two CPUs.

**Constraints**: Ollama bound to the pod's loopback and reached by nothing but the keeper; the
keeper admits only a certificate the service authority issued and a `Caller.Service` reading of it;
no key anywhere; the operator's ClusterRole unchanged and its `dependsOn` still `crd` alone; no
object rendered for a service on an installation without the component differs from before; `kubectl
apply -k` remains the whole deploy; no model deleted except by the declaration; a stored journal
decodes unchanged; warning-free; `Test / parallelExecution := false` stays; no suite binds a fixed
port or names an image this build makes by a literal tag

**Scale/Scope**: about 11 new Scala source files and 20 changed across nine modules; about 10 new
suites and 12 changed; 1 new kustomize component of about 9 files, 1 new overlay, 1 cloud patch,
the local deploy script; 1 new docs page and about 8 changed; 8 feature files, 32 scenarios

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` states as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; one thing performs them | pass | `InstallationModels.decide` is pure and the status block is a description; the keeper's loop is a pure `Reconcile.plan` over the declaration and what Ollama reports, and one `OllamaClient` performs it (R2, R6) |
| Where two parties must agree on a derivation, there is one | pass | the preset's address is read from one variable the operator sets; the status document's shape is one case class in `core` the keeper writes and the operator, the sidecar and the control plane read (R2, R7) |
| Module dependency direction | pass | `crd` still depends on nothing; `controlplane-api` on `core`; the operator on `crd`, compiling the shared file; `model-keeper` depends on `proxy`, `runtime` and `telemetry-otlp`, and nothing depends on it; `test-ollama` depends on nothing of ankka's and is a test dependency only |
| The operator cannot reach into the control plane, and depends on as little as possible | pass | no library is added; the keeper's status is read over HTTP with the JDK's client as Garage's API is (R6) |
| A field on the resource needs the schema | pass | one status block, `CrdSchemaSuite` extended to its insides (R6) |
| Never render what must not roll | pass | nothing on any pod template of an installation without the component; with it, one variable on every platform container, announced as rolling every service once, as the broker's did (R4) |
| Every port a workload has is mutual TLS | pass | the door is mutual TLS from the service authority; Ollama's port is loopback inside the pod and no policy admits it because no Service exposes it; the status port is plain and admits the operator's pods alone, as Garage's admin port does (R2, R3) |
| Nothing the platform does may destroy data | pass | a model is deleted only when its tag leaves the declaration; the volume has no owner and outlives the pod (R2) |
| Stored forms stay readable both ways | pass | the resource's new block is optional; the wire's new field is optional with a default; nothing journaled changes |
| An overlay that only works from the script is not an overlay | pass | the local opt-in is an overlay (`overlays/local-models`), and the script selects it; `RemoteOverlaySuite` renders it (R3) |
| A strategic merge patch names a container that exists | pass | the component's patches name `ankka-operator` and `ankka-controlplane`; `RemoteOverlaySuite` asserts the variable set once (R4) |
| Tests are serialised; no fixed port; no literal image tag | pass | the keeper's suites bind port 0; the k3s suite names the keeper's image by `BuildInfo.imageTag` and Ollama's by its pinned third-party tag |
| Could this check pass while the thing it checks is false? | pass | R12 names the checks that could and how each is sharpened: the egress proof, the refusal at the handshake, the "stays served" timing |
| Each acceptance scenario ends as a test that fails without the feature | pass | 32 scenarios: 24 by the k3s suite, 7 by named tests, 1 waiting on feature 050 and said so (R11) |
| Docs: pages stand alone, samples from tested code, new pages in nav and a skill | pass | R10 |
| Every tracked file claimed by a CI path filter | pass | `kustomization/**`, `features/**` and a new module's `src/**` are claimed by the existing filters; the new module's suites run in `build (rest)` by subtraction |

**Violations to justify**: none.

**Post-design re-check**: unchanged. The contracts add no dependency to a main classpath, no verb
to the operator's ClusterRole and no field beyond the one block on the resource and the one on the
installation wire.

## Project Structure

### Documentation (this feature)

```text
specs/053-installation-models/
├── plan.md              # this file
├── research.md          # R1–R12: decisions with file-level evidence; four spikes to run first
├── data-model.md        # the declaration, a served model's states, the status document, the resource's block, the wire
├── quickstart.md        # the validation runs: pure → the keeper → the sidecar → the overlays → k3s → by hand → docs
├── contracts/
│   ├── component.md               # the objects, the ports, who may connect, the patches, the overlay knobs
│   ├── keeper.md                  # the door's paths, the status document, the probe, the metrics, its environment
│   ├── operator-and-status.md     # the settings, the plan, the resource's block, what services get and installation show
│   └── provider.md                # the preset on a sidecar and in Scala: variables, refusals, the capability check
└── tasks.md             # /speckit-tasks, not created here
```

The acceptance scenarios are in `features/installation-models/` (seven files) and
`features/documentation/installation-models.feature`, in the words of `GLOSSARY.md`.

### Source Code (repository root)

```text
model-keeper/                                                  # new module, image ankka-model-keeper
├── src/main/scala/…/modelkeeper/Main.scala                     # settings from the environment, the door, the loop, the probe
├── …/modelkeeper/KeeperSettings.scala                          # ANKKA_KEEPER_*: Ollama's address, the declaration's path, ports
├── …/modelkeeper/Door.scala                                    # mutual TLS listener; forwards the allowed paths, streamed
├── …/modelkeeper/OllamaClient.scala                            # /api/tags, /api/show, /api/pull, /api/ps, /api/generate, /api/delete
├── …/modelkeeper/Reconcile.scala                               # pure: declaration + observed → actions and the status
├── …/modelkeeper/Keeper.scala                                  # the loop: re-read, plan, perform, publish
├── …/modelkeeper/KeeperMetrics.scala                           # OTLP counters and gauges
└── src/test/…                                                  # DoorSuite, ReconcileSuite, KeeperSuite

modules/test-ollama/src/main/scala/…/testollama/FakeOllama.scala   # new: the loopback stand-in, a test helper module like test-pki,
                                                               #      consumed % Test by model-keeper and sidecar

modules/core/…/core/PlatformVariables.scala                    # ModelsAddress, platform-only
modules/core/…/core/ModelServerDocument.scala                  # new: the keeper's status document, compiled by the operator too

modules/agent/…/agent/ModelProviders.scala                     # installation(model): the preset in Scala (052's file)
sidecar/…/sidecar/Models.scala                                 # the preset `installation`: address, TLS, refusals, capability check
sidecar/…/sidecar/InstallationModelClient.scala                # new: the door's client and the capability cache

crd/…/crd/AnkkaService.scala                                   # InstallationModelsStatus block on the status
kustomization/components/crd/ankkaservice.yaml                 # the same, declared

operator/…/operator/ModelServerSettings.scala                  # new: three settings, all or none
operator/…/operator/InstallationModels.scala                   # new: what a service names, decide, withheld, refused
operator/…/operator/ModelServerDocumentReader.scala            # new: reads the keeper's document over the JDK's client, per pass
operator/…/operator/Settings.scala, Operator.scala             # the settings read and handed on
operator/…/operator/Rendering.scala                            # ANKKA_MODELS_ADDRESS on platform containers; nothing else
operator/…/operator/ServiceReconciler.scala, LifecycleRules.scala   # withheld, refused, the block, the detail
operator/src/test/resources/golden/installation-model.txt      # new
operator/src/test/resources/unchanged/*.json.txt               # unchanged, asserted

controlplane-api/…/api/descriptors.scala                       # Installation.models; InstallationModels, ModelLine
controlplane/…/controlplane/api/InstallationEndpoint.scala     # the field
controlplane/…/controlplane/deploy/InstallationModelsReader.scala   # new: the keeper's status with the control plane's certificate
cli/…/cli/Output.scala, mcp/AnkkaTools.scala                   # the installation status lines; the tool's description

kustomization/components/models/                               # new: namespace, declaration, certificate, statefulset,
                                                               #      service, zero-trust, operator-patch, controlplane-patch
kustomization/overlays/local-models/kustomization.yaml         # new: the local overlay plus the component
kustomization/overlays/cloud/kustomization.yaml, models-size.yaml   # the component, the example placement patch
kustomization/deploy-local.sh                                  # ANKKA_LOCAL_MODELS=on: the overlay, the image, one wait
Justfile                                                       # deploy-models
build.sbt, project/Dependencies.scala                          # the module, its image, buildAll, the cluster suites' images
.github/workflows/release.yml                                  # ankka-model-keeper in IMAGES

controlplane/src/test/…/InstallationModelsClusterFeatures.scala    # new: the steps and the suite
controlplane/src/test/…/ModelServerStack.scala                 # new: the component on k3s, the images imported
controlplane/src/test/…/RemoteOverlaySuite.scala               # the new overlay; the cloud patch; the two patches' shape

docs/platform/installation-models.md                           # new
docs/platform/install-local.md, install-cloud.md, install-gke.md
docs/reference/configuration.md, service-descriptor.md, limitations.md; docs/build/agents.md
mkdocs.yml, tools/docs/skill/ankka-platform/SKILL.md, the rendered skills
```

**Structure Decision**: one module and one image are added, the keeper, because the door, the loop
and the status have to run in Ollama's pod and nothing that exists can: the sidecar cannot start
without a cluster and a database, the proxy's engine admits named callers under a service's own
identity, and Ollama has no door of its own. Everything else goes where its kind lives: the settings
beside `ObjectStoreSettings`, the plan beside `ObjectStorage`, the preset in 052's `Models`, the
status beside backups' in the installation endpoint, the page beside `broker.md`. The status
document's case class is in `core` and compiled by the operator the way `PlatformVariables` is, so
the writer and its three readers hold one shape without the operator gaining a dependency.

## Order of work

Cut by user story, tests before the code they hold. Slices 1 to 4 need no cluster; slice 0 needs
Docker.

0. **Spikes** — S1 to S4 of the research, each a throwaway under `-Dankka.spikes=on`: Ollama's
   `keep_alive` of `-1` keeps a model loaded and a preload makes the first call fast; the queue-full
   answer; the OpenAI SDK's client over `RotatingTls` through a mutual-TLS listener; `qwen3:0.6b`
   calling a tool through Chat Completions on two CPUs within the timeout. R1, R2 and R5 rest on
   them.
1. **The keeper** (FR-001's program, FR-002, FR-005's door, FR-014's metrics). The module, the
   stand-in, `Reconcile` and its suite, `OllamaClient` against the stand-in, `Door` and its suite
   (a certificate of a service admitted, no certificate refused, the gateway refused, only the
   allowed paths), `Keeper` end to end, the image.
2. **Variables, settings and the plan** (FR-004's settings, FR-006, FR-007's refusals, FR-009).
   `PlatformVariables.ModelsAddress`, the shared status document, `ModelServerSettings`,
   `InstallationModels.decide` and its table, the resource's block and schema, the rendering of the
   one variable, the golden file, `RenderingUnchangedSuite` green with no change.
3. **The preset** (FR-007, FR-008, FR-010, FR-011, FR-012). `Models` resolving `installation` with
   the certificate, the refusals at start, `InstallationModelClient` and the capability check with
   the stand-in, the Scala constructor, the conformance case.
4. **The component and the overlays** (FR-001, FR-003, FR-004's flag). The directory, the
   `local-models` overlay, the cloud listing and `models-size.yaml`, the script's flag, image and
   wait, `RemoteOverlaySuite`'s assertions, `ModelServerStack`.
5. **The installation's status** (FR-013). `InstallationModelsReader`, the wire, the endpoint, the
   CLI, the MCP tool's description.
6. **User Stories 1 to 5 on k3s.** `InstallationModelsClusterFeatures` over the seven feature
   files, `ranElsewhere` naming the scenarios held by named tests and the one waiting on 050.
7. **Documentation** (FR-015), then the whole build and `gh workflow run cluster -f
   suite=InstallationModelsClusterFeatures`.

Slice 3 depends on 1's stand-in and on feature 052; slice 5 on 2; slice 6 on 1 to 5.

## Complexity Tracking

No principle is departed from. The rows are places the plan departs from the spec's wording or adds
a piece the spec did not name, each with the simpler thing that was rejected.

| Departure | Why needed | Simpler alternative rejected because |
|---|---|---|
| A program of ankka's in the model server's pod, the keeper (FR-001, FR-002, FR-005, amended) | Ollama has no mutual TLS, no declaration, no preload, no metrics and no status; something must do each, and must sit where Ollama's loopback is | an Envoy sidecar gives the door and nothing else, and does not read cert-manager's rotating files the way every workload does; a shell loop in Ollama's container gives the loop and no door; a Go program is a language the family does not maintain (R2) |
| The variable is `ANKKA_MODELS_ADDRESS`, outside the `ANKKA_MODEL_` family (FR-007, amended) | 052's family parses `ANKKA_MODEL_<NAME>_<FIELD>`, and any name under it is a provider | reserving a provider name (`server`) for the platform's variable makes the family's grammar carry an exception for ever (R4) |
| One variable on every platform container, so adding the component rolls every service once (SC-005, clarified) | the sidecar resolves the preset at start from its environment, as it resolves the broker | a mounted file re-read at the first call avoids the roll but makes "refused at the service's start" (FR-009) a lie for a service deployed before the component (R4) |
| Metrics pushed over OTLP, not scraped (FR-014, amended) | Ollama exposes no metrics and the collector has no scrape receiver | adding a Prometheus receiver and a third-party exporter is two more components to run for numbers the keeper already has (R8) |
| The remote-cluster scenario waits on feature 050 | 050 is a spec with no code on `main` | writing a cluster list with one entry is enough for the shape; proving a second cluster needs the tether (R11) |
| The namespace is `models-system`, not `ankka-models` | `ankka-<name>` is a project namespace's form, so a project called `models` could name it | Garage made the same choice for the same reason (R3) |
| A plain status port for the operator beside the mutual-TLS door | the operator holds no service certificate, as it holds none for Garage's administration API | giving the operator a certificate is a change to the platform's identities for one read (R6) |
| The declaration is a ConfigMap, not a field anywhere the control plane writes | the administrator declares in the overlay, and nothing of the platform's writes it | a control-plane route for declarations is the project path the clarification declined (R3) |

**Operational consequences** to announce in the release:

- An installation that adds the component rolls every service once, for the new variable.
- The keeper's image, `ankka-model-keeper`, joins the images a release publishes and an overlay's
  `images:` block renames.
- Ollama's image is large (3.8 GB on amd64); a node pulls it once per release of the component.
