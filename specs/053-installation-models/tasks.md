# Tasks: Installation Models — A Model Server the Installation Runs

**Input**: Design documents from `/specs/053-installation-models/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md).
**Feature 052 (model providers: named providers, presets, the Chat Completions provider) must be on
the branch before Phase 5**: the preset `installation` is one row in its table, and
`ModelProviders.scala` and the preset mechanism in `sidecar/Models.scala` are its files.

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature, and the plan's "verify first" spikes turn each fact read from
documentation into a throwaway suite before the code that relies on it. The scenarios are in
`features/installation-models/` and `features/documentation/installation-models.feature`; where a
task says "case", it means a `test(...)` in the named suite, named for the scenario or the rule it
holds.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (an administrator gives the installation a model server), US2 (only the
  platform's program reaches it), US3 (a developer learns what is served and is told when it is
  not), US4 (models are fetched, loaded and kept loaded), US5 (capabilities said at the first call),
  US6 (the installation sizes and places the server), US7 (operating and documenting it)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `MK`/`MKT` =
`model-keeper/src/{main,test}/scala/…/modelkeeper`; `CORE`/`CORET` =
`modules/core/src/{main,test}/scala/…/core`; `AG` = `modules/agent/src/main/scala/…/agent`;
`SC`/`SCT` = `sidecar/src/{main,test}/scala/…/sidecar`; `CRD`/`CRDT` =
`crd/src/{main,test}/scala/…/crd`; `API`/`APIT` =
`controlplane-api/src/{main,test}/scala/…/controlplane/api`; `CP`/`CPT` =
`controlplane/src/{main,test}/scala/…/controlplane`; `OP`/`OPT` =
`operator/src/{main,test}/scala/…/operator`; `CLI`/`CLIT` = `cli/src/{main,test}/scala/…/cli`;
`KUST` = `kustomization`; `DOCS` = `docs`; `FEAT` = `features/installation-models`. "R*n*" is a
section of `research.md`; "S*n*" a row of its *Spikes* table; a contract is named by its file under
`contracts/`.

The branch `053-installation-models` exists, in the worktree `.claude/worktrees/053-installation-models`.
Work there. Every `sbt` command below takes `-Dankka.cluster.tests=off` unless the task names a k3s
suite or a spike; a k3s run belongs under `caffeinate -i`. sbt's project ids are `core`, `agent`,
`sidecar`, `crd`, `controlPlaneApi`, `controlPlane`, `operator`, `cli`, `proxy`, `telemetryOtlp`
and the new `modelKeeper`.

---

## Phase 1: Setup — the module, and the four things the design rests on

**Purpose**: R1, R2 and R5 each rest on a fact read from Ollama's code or the SDK's README. A spike
is a throwaway suite that runs only under `-Dankka.spikes=on`, as `GatewayGrpcSpike` does; each ends
by writing what it found under a new heading, *Verified during implementation*, at the end of
`research.md`. If one is false, stop and change the decision it holds before going on.

- [X] T001 Add the module to `build.sbt`: `lazy val modelKeeper = project.in(file("model-keeper"))` depending on `proxy`, `runtime`, `telemetryOtlp`, and `testPki % Test`, `testkit % Test`, with `JavaAppPackaging` and `DockerPlugin`, `commonSettings`, `dockerSettings`, `name := "ankka-model-keeper"`, `publish / skip := true`, `Compile / mainClass := Some("com.thinkmorestupidless.ankka.modelkeeper.Main")`, `dockerExposedPorts := Seq(8443, 8080, 7627)`, `libraryDependencies ++= Seq(logback, testcontainers % Test)`, and the proxy's `Universal / javaOptions` and `Test / javaOptions` (`allowRestrictedHeaders=host`, `SerialGC`, C1, one processor; `logback-proxy-test.xml`). Add it to the root aggregate. Confirm `sbt modelKeeper/compile` and that `sbt 'show modelKeeper/Compile/dependencyClasspath'` names no Pekko HTTP server jar beyond what `proxy` already carries.
- [X] T002 Add `model-keeper/**` to the `scala-build` anchor in `.github/workflows/ci.yml`'s filters and confirm `python3 .github/ci-coverage.py` passes; note in the task's commit that the module's suites run in `build (rest)` by subtraction.
- [X] T003 [P] Spike S1 and S3 in `MKT/OllamaSpike.scala`: start `ollama/ollama:0.40.6` with testcontainers' `GenericContainer`, `OLLAMA_HOST=0.0.0.0:11434`, `OLLAMA_KEEP_ALIVE=-1`, a tmpfs at `/var/lib/ollama`; `POST /api/pull` `qwen3:0.6b` streaming until `success`; `POST /api/generate` with the model, no prompt and `keep_alive: -1`; assert `/api/ps` lists it with `expires_at` far in the future, and again after six minutes idle (one case, long, under the spike switch only). Then `POST /v1/chat/completions` with one function tool and the message "look up order 42", `reasoning_effort: "low"`, `max_tokens: 200`; assert a `tool_calls` entry arrives within two minutes and record the wall-clock. Then fire `OLLAMA_MAX_QUEUE + 1` concurrent long requests against a container started with `OLLAMA_MAX_QUEUE=2` and record the HTTP status and body of the refused one. Write the three findings into `research.md`.
- [X] T004 [P] Spike S2 in `SCT/ModelDoorSpike.scala`: with `TestPki` issue a listener certificate carrying `ankka://platform/models` and a client certificate carrying `ankka://shop/cart`; start a JDK `HttpsServer` configured by `RotatingServerTls.configurator` over a `RotatingTls` on the listener's directory and `senderOf` from `TlsTransport`; build `OpenAIOkHttpClient.builder().baseUrl(...).apiKey("none").sslSocketFactory(ctx.getSocketFactory).trustManager(tm)` from `RotatingTls(clientDir).contextRequiring("ankka://platform/models")` and `trustManagerRequiring(...)`; assert a `chat().completions().create` reaches the handler with the sender read as `Sender.Service("shop","cart")` (answer a canned completion); assert a plain `HttpClient` with no certificate fails with `SSLHandshakeException`, not an HTTP status. Write the finding into `research.md`.

**Checkpoint**: three findings are written into `research.md`. Nothing else has changed.

---

## Phase 2: Foundational — the shared document, the variable, the settings, the keeper, the component

**Purpose**: what every story reads. Nothing here holds a service back or answers a member yet.

**⚠️ CRITICAL**: a block inside `AnkkaServiceStatus` that `ankkaservice.yaml` does not declare
passes every offline suite and is refused by a real API server on every projection. T011 extends
the one suite that sees it; show it failing before trusting it.

### The shared shapes

- [X] T005 [P] `CORE/ModelServerDocument.scala`: the status document of data-model.md (`ModelServerDocument(server, ready, pass, models: Vector[ModelStatus], calls: Calls, detail: Option[String])`, `ModelStatus(name, state, digest, capabilities, detail, since)`, `Calls(answered, failed, inFlight, waiting)`), named so it cannot be confused with the resource's `InstallationModelsStatus` block (T009) with a jsoniter codec in the companion and `state` as a string `JsonValueCodec` enum (`fetching`, `served`, `failed`, `removing`); imports nothing outside the standard library and jsoniter, since the operator compiles the file (T006). Case in `CORET/ModelServerDocumentSuite.scala`: round-trip, an unknown state refused, `detail` absent when `None`.
- [X] T006 Add the file to the operator's `Compile / unmanagedSources` beside `PlatformVariables.scala` in `build.sbt`, and extend `PlatformDeclarationSuite` (`controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/PlatformDeclarationSuite.scala`, the suite that fails on a second copy of the shared file) so a second copy of `ModelServerDocument.scala` fails too.
- [X] T007 [P] `CORE/PlatformVariables.scala`: `val ModelsAddress = "ANKKA_MODELS_ADDRESS"` in `PlatformOnly`. Case in `CORET/PlatformVariablesSuite.scala`: platform-only, not runtime-only, not shared, and `"ANKKA_MODELS_ADDRESS".startsWith("ANKKA_MODEL_")` is false, with a comment naming R4. Case in `APIT/DescriptorSuite.scala`: a descriptor giving it is refused with `env var 'ANKKA_MODELS_ADDRESS' is set by the platform and cannot be declared`.
- [X] T008 [P] `OP/ModelServerSettings.scala`: `ModelServerSettings(address, statusUrl, namespace)` and `read(lookup)` in `BrokerSettings.read`'s shape (all three or none, the by-halves message of operator-and-status.md); `Settings.models: Option[ModelServerSettings]` read in `OP/Settings.scala` and handed on in `OP/Operator.scala`. Cases in `OPT/ModelServerSettingsSuite.scala`: all three, none, each one missing naming it.

### The resource

- [X] T009 [P] `CRD/AnkkaService.scala`: `InstallationModelsStatus(phase, models: Vector[ServedModel], detail)` and `ServedModel(name, digest)` (data-model.md), `AnkkaServiceStatus.installationModels: Option[...] = None`; codec case in `CRDT/AnkkaServiceCodecSuite.scala` that a status without the field decodes as before.
- [X] T010 [P] Declare it in `KUST/components/crd/ankkaservice.yaml` under `status.properties.installationModels` with `phase`, `models` (items `name`, `digest`), `detail`.
- [X] T011 Extend `OPT/CrdSchemaSuite.scala` to compare the block's insides both ways; remove `digest` from the YAML once, watch it fail, put it back.

### The keeper

- [X] T012 [P] `modules/test-ollama/src/main/scala/com/thinkmorestupidless/ankka/testollama/FakeOllama.scala`, in a new helper module `testOllama` declared in `build.sbt` as `testPki` is (`commonSettings`, `publish / skip`, no ankka dependency) and added `% Test` to `modelKeeper` and `sidecar`, with `modules/test-ollama/**` under `scala-build` in `.github/workflows/ci.yml`: a loopback `HttpServer` stand-in with a scriptable catalogue (`held: Map[tag, (digest, capabilities)]`, `loaded: Set[tag]`, `pulls: Map[tag, Either[failureLine, Unit]]`, a default `keepAlive`), answering `GET /api/version`, `GET /api/tags`, `GET /api/ps`, `POST /api/show`, `POST /api/pull` (streamed status lines ending in `success` or the scripted failure), `POST /api/generate` (no prompt: marks loaded, honours `keep_alive`), `DELETE /api/delete`, `POST /v1/chat/completions` (a canned completion, or SSE chunks when `stream`), `GET /v1/models`; records every request. Port 0.
- [X] T013 [P] `MK/KeeperSettings.scala`: the environment of keeper.md (`ANKKA_KEEPER_*`, `ANKKA_OTLP_*`, the service-directory property), `fromEnvironment(env): Either[Vector[String], KeeperSettings]` naming every problem. Cases in `MKT/KeeperSettingsSuite.scala`: defaults, a missing address, a malformed port, a malformed interval.
- [X] T014 [P] `MK/Reconcile.scala`: pure `plan(declaration: Vector[String], held: Map[tag, Digest], loaded: Set[tag], recorded: Map[tag, ModelStatus], now, backoff: Map[tag, Instant]): (Vector[Action], InstallationModelsStatus)` with `Action = Pull | Load | Record | Delete`, at most one `Pull` per pass, in declaration order; the state rules of data-model.md; the backoff from one minute doubling to an hour, reset by a declaration change. Cases in `MKT/ReconcileSuite.scala`: every row of the state table, a served model never re-pulled, a removed tag deleted, a failed tag in backoff skipped and retried when due, an empty declaration, a tag appearing twice (second ignored, detail set).
- [X] T015 `MK/OllamaClient.scala` over `java.net.http.HttpClient`: `version`, `tags`, `ps`, `show`, `pull` (streamed, returns the last status line), `load` (generate with `keep_alive: -1`), `delete`; `Either[String, _]` everywhere, two-second connect and a pull with no read timeout. Cases in `MKT/OllamaClientSuite.scala` against `FakeOllama`: each call, a pull that fails mid-stream returning the failure line, a connection refused as `Left`.
- [X] T016 `MK/Door.scala`: `HttpsServer` on `ANKKA_KEEPER_PORT` with `RotatingServerTls.configurator(tls)` (the `proxy` module's), `TlsTransport.senderOf` for the sender; admit `Sender.Service` only (403 body of keeper.md otherwise); forward the five paths of keeper.md to Ollama with `proxy-core`'s `StreamingBody` both ways, drop `Authorization`, add `X-Ankka-Caller`, pass the status through; 404 everything else; `GET /ankka/models` from the keeper's last document; count `calls` and open a span `models.call` with the attributes of keeper.md, child of `traceparent` when present. Also the plain status listener on `ANKKA_KEEPER_STATUS_PORT` (`/ankka/models` only) and the probe on `ANKKA_KEEPER_PROBE_PORT` (`/ready`, `/live`).
- [X] T017 Cases in `MKT/DoorSuite.scala` with `TestPki` certificates: a service certificate admitted and its chat completion forwarded with the body streamed in pieces (SSE arriving before the stand-in closes); the gateway's certificate 403; a mount certificate 403; no certificate failing the handshake (assert the exception type, not a status); `POST /api/pull` through the door 404; `Authorization` not forwarded; `X-Ankka-Caller` forwarded; `/ready` 503 before the first pass and 200 after; the status port answering `/ankka/models` and 404 elsewhere; a forwarded call's span `models.call` carrying `ankka.model`, `ankka.caller.project`, `ankka.caller.service` and the status, with the request's `traceparent` as its parent (named for "a call to an installation model is a span in the turn's trace").
- [X] T018 `MK/Keeper.scala`: the loop of keeper.md: read the declaration (`{"models": [...]}`, keep the last good one on a parse error with `ready: false` and the error as `detail`), observe through `OllamaClient`, `Reconcile.plan`, perform, publish; re-run on the file's modification-time change (poll every second, as `RotatingTls` watches) and every `ANKKA_KEEPER_PASS_INTERVAL`; one log line per change as keeper.md lists. `MK/KeeperMetrics.scala`: the eight instruments of keeper.md over `telemetry-otlp`'s `Metrics`, `waiting` as `max(0, inFlight − ANKKA_KEEPER_PARALLEL)`, built only when `ANKKA_OTLP_ENDPOINT` is set.
- [X] T019 Cases in `MKT/KeeperSuite.scala` against `FakeOllama` and a temporary declaration file: start → one pass → the declared tag pulled, loaded, recorded with its digest and reported `served`; the file edited to add a tag → served within one pass without the first being pulled again; a tag removed → `removing` then gone; a scripted pull failure → `failed` with the line, retried after the backoff (clock injected); a malformed file → last good declaration kept, `ready` false, `detail` set; the stand-in's default `keepAlive` of two seconds defeated by the preload (model still loaded after three seconds).
- [X] T020 `MK/Main.scala`: settings from the environment, refusing to start naming every problem and when the certificate's URI is not `ankka://platform/models` (as the proxy's `identityMismatch`); the door, the keeper, the shutdown hook. Case in `MKT/MainSuite.scala`: `run` returns 1 with each refusal text; with everything set, `run` serves until the latch (start it on a thread, hit `/live`, interrupt).
- [X] T021 Add the image to the build: `(modelKeeper / Docker / publishLocal).value` in `sampleImageForClusterTests` and in `buildAll` beside the proxy's in `build.sbt`; `ankka-model-keeper` in `IMAGES` in `.github/workflows/release.yml`; `ClusterImages.importInto` in `OPT/ClusterImages.scala` mapping `ankka-model-keeper` to the `modelKeeper` project for its "missing image" hint. Confirm `sbt modelKeeper/docker:publishLocal` produces `ankka-model-keeper:latest`.

### The component

- [X] T022 [P] `KUST/components/models/`: `kustomization.yaml` (a `Component`, with a header comment in the Garage component's manner), `namespace.yaml` (`models-system`, unlabelled), `declaration.yaml` (ConfigMap `models-declaration`, `models.json` = `{"models": ["qwen3:0.6b"]}`), `certificate.yaml` (component.md: `ankka://platform/models`, the four DNS names, `ankka-service` `ClusterIssuer`, PKCS8, `rotationPolicy: Always`, secret `ankka-models-tls`), `statefulset.yaml` (the pod of component.md exactly: `ollama` loopback-bound with the five variables, `keeper` with its environment including `ANKKA_KEEPER_PARALLEL` equal to `OLLAMA_NUM_PARALLEL`, ports, probe, mounts, the claim template of 20Gi), `service.yaml` (ports `models` 8443, `status` 8080, `publishNotReadyAddresses: true` so the document is readable during the first fetch), `zero-trust.yaml` (the policy of component.md: 8443 from managed pods in managed namespaces and from `ankka-controlplane`; 8080 from the operator's pods; nothing for 11434), `operator-patch.yaml` (container `ankka-operator`, the three `ANKKA_MODELS_*`), `controlplane-patch.yaml` (container `ankka-controlplane`, `ANKKA_MODELS_ADDRESS`).
- [X] T023 [P] `KUST/overlays/local-models/kustomization.yaml` (`resources: [../local]`, `components: [../../components/models]`); `KUST/overlays/cloud/kustomization.yaml` listing the component after `controlplane` and `models-size.yaml` under `patches`; `KUST/overlays/cloud/models-size.yaml` as component.md gives it, every value marked `SET`; the `images:` block renaming `ankka-model-keeper`.
- [X] T024 Cases in `CPT/RemoteOverlaySuite.scala`: `local` renders no object in `models-system` and no `ANKKA_MODELS_` anywhere; `local-models` renders the StatefulSet with two containers named `ollama` and `keeper`, `OLLAMA_HOST` `127.0.0.1:11434`, `ANKKA_KEEPER_PARALLEL` equal to `OLLAMA_NUM_PARALLEL`, the Service with `publishNotReadyAddresses: true`, and `ANKKA_MODELS_ADDRESS` exactly once on `ankka-operator`'s container and once on `ankka-controlplane`'s, both Deployments still with one container; `cloud` renders the selector, the toleration, `nvidia.com/gpu`, `OLLAMA_NUM_PARALLEL` and `ANKKA_KEEPER_PARALLEL` equal, and the claim's class from `models-size.yaml`, and the keeper's image renamed; the Certificate's issuer exists and asks for PKCS8 (as the broker's is held).
- [X] T025 `KUST/deploy-local.sh`: `ANKKA_LOCAL_MODELS="${ANKKA_LOCAL_MODELS:-off}"`; when `on`, render `overlays/local-models` in place of `local` (the one `kubectl kustomize` line), `kind load docker-image ankka-model-keeper:latest` beside the other images, and after the operator's rollout wait `kubectl -n models-system rollout status statefulset/models --timeout=900s` (the fetch is minutes); when `off`, change nothing. `Justfile`: `deploy-models:` → `ANKKA_LOCAL_MODELS=on ./kustomization/deploy-local.sh`, one line, with the comment the other recipes carry.
- [X] T026 [P] `CPT/ModelServerStack.scala` (shared with the operator's tests as `ObjectStoreStack` is): `install(k3s, k8s, repoRoot, declaration: Vector[String] = Vector("qwen3:0.6b"))` applies the component's files with the node's `kubectl` (the declaration rewritten to the given list), imports `ollama/ollama:0.40.6` and `ankka-model-keeper:<BuildInfo.imageTag>` with `ClusterImages.importInto`, waits for the StatefulSet's ready replica (up to fifteen minutes, since the fetch is in it), and returns `Installed(settings: ModelServerSettings, statusForward: LocalPortForward)` for a test JVM's operator; `redeclare(models)` patches the ConfigMap and returns when the keeper's document shows the new list. S4 is this stack's first run: if `/ready` is refused under the policy, say so in `research.md` and fix the policy before going on.

**Checkpoint**: `sbt core/test crd/test modelKeeper/test 'operator/testOnly *ModelServerSettingsSuite *CrdSchemaSuite' 'controlPlane/testOnly *RemoteOverlaySuite'` green; `just deploy-models` on a kind cluster leaves `kubectl -n models-system get statefulset models` at `1/1`; `just deploy` on a fresh cluster leaves no `models-system`.

---

## Phase 3: User Story 1 — An administrator gives the installation a model server (Priority: P1) 🎯 MVP

**Goal**: an installation with the component serves what its administrator declared; a Python
service names one with the preset and a model; its agent answers with a tool call and nothing
leaves the installation; the installation's status names the server and the models.

**Independent Test**: `InstallationModelsClusterFeatures` running `FEAT/serving.feature` whole.

### The operator's plan and rendering

- [X] T027 [P] [US1] `OP/InstallationModels.scala`: `Named(provider, model, address: Option, key: Option)`, `named(spec): Vector[Named]` from `spec.env` (`ANKKA_MODEL_<NAME>_PRESET` = `installation`, lowercase name); `InstallationModelsPlan` (`NotNeeded`, `Ready(models)`, `Waiting(detail)`, `Failed(detail)`); `decide(named, settings: Option[ModelServerSettings], observed: Either[String, ModelServerDocument]): InstallationModelsPlan` with the exact details of data-model.md; `withheld(plan): Option[String]` (Waiting or Failed) and `refused(plan): Option[String]` (Failed), as `ObjectStorage`'s; `status(plan): Option[InstallationModelsStatus]` for the resource. Cases in `OPT/InstallationModelsSuite.scala`: every row of the plan table, each detail text exactly, a service naming two models one of which is fetching, the observation `Left` as `Waiting("the model server is not answering")`, names none with no settings as `NotNeeded`.
- [X] T028 [P] [US1] `OP/ModelServerDocumentReader.scala`: `read(settings): Either[String, ModelServerDocument]` over the JDK's client with a two-second timeout, decoding T005's codec; memoised per reconcile pass by `ServiceReconciler` (one read, shared). Cases in `OPT/ModelServerDocumentReaderSuite.scala` against a loopback document: a document read, a timeout as `Left`, a 500 as `Left`, a malformed body as `Left` naming the problem.
- [X] T029 [US1] `OP/Rendering.scala`: `ANKKA_MODELS_ADDRESS` as an `EnvEntry` on the platform container of an embedded and a process-hosted service when `settings.models` is present, never the process's container, never the proxy; `OP/ServiceReconciler.scala`: the plan computed beside the bucket's (T027, T028), `withheld`/`refused` folded where `ObjectStorage`'s are (line 71 onward), the block written to the status, a `Waiting` service requeued at thirty seconds; `OP/LifecycleRules.scala`: the detail folded as the bucket's is. Golden `operator/src/test/resources/golden/installation-model.txt` for a process-hosted service naming one (settings present, `Ready`), added to `OPT/RenderingGoldenSuite.scala`; `RenderingUnchangedSuite` run and `git diff --stat operator/src/test/resources/unchanged` empty; a case in `OPT/RenderingSuite.scala` that with settings present and a service naming none, the variable is on the platform container and on nothing else, and with settings absent nowhere.

### The preset (needs feature 052)

- [X] T030 [US1] `SC/Models.scala`: the preset `installation` in 052's table: protocol `openai`, Chat Completions, address `${ANKKA_MODELS_ADDRESS}/v1`, no key, the client built with `sslSocketFactory`/`trustManager` from the sidecar's service `RotatingTls` requiring `ankka://platform/models` (as T004 showed), the start refusals of provider.md (address, key, no model, no `ANKKA_MODELS_ADDRESS` with the FR-010 text naming `docs.ankka.cloud/platform/installation-models`), each a `StartRefusal`. Cases in `SCT/ModelsSuite.scala`: the resolution, each refusal's text, `temperature` and `reasoning_effort` sent, no `Authorization` header (against `testOllama`'s `FakeOllama` behind a `TestPki` listener).
- [X] T031 [P] [US1] `AG/ModelProviders.scala` (052's file): `installation(model, name = "installation")` reading `ANKKA_MODELS_ADDRESS` and the runtime's service `RotatingTls`, with the same refusals when the runtime starts; case in `modules/agent/src/test/…/ModelProvidersSuite.scala` for the refusal without the variable and for the name reported.
- [X] T032 [US1] One conformance case per SDK naming the preset, in `sidecar/src/test/…/conformance/` as 052 adds its named-provider case, answered by `FakeOllama` behind a `TestPki` listener; `features/secrets/platform-settings.feature` gains `ANKKA_MODELS_ADDRESS` as an example of a variable the platform alone sets, and the suite that runs that outline gains the row. *Done as a `RemoteModelsSuite` case rather than one per SDK: an SDK names a provider by name only and the sidecar resolves it, so the case is the same for every SDK, as 052's named providers are; the platform-settings row is in `WasmHostSuite`.*

### The installation's status

- [X] T033 [P] [US1] `API/descriptors.scala`: `Installation.models: Option[InstallationModels] = None`, `InstallationModels(clusters)`, `ClusterModels(cluster, server, ready, models, detail)`, `ModelLine(name, state, digest, detail)` with codecs; fixtures in `APIT/ControlPlaneFixturesSuite.scala`.
- [X] T034 [US1] `CP/deploy/InstallationModelsReader.scala`: `read(): Option[InstallationModels]` — `GET ${ANKKA_MODELS_ADDRESS}/ankka/models` with a `RotatingTls` over the control plane's service mount requiring `ankka://platform/models` (as `InstanceTopologies.tlsFor` builds one), decoded with T005's codec, one cluster `home`; a failure answers the cluster with `ready: false` and a `detail`; `None` when the variable is absent. Wired into `CP/api/InstallationEndpoint.scala`'s `get("/")` as `models = reader.read()`. Cases in `CPT/InstallationEndpointSuite.scala` (new, in `ControlPlaneHttpSuite`'s manner) with a stubbed reader: present, absent, failing.
- [X] T035 [P] [US1] `CLI/Output.scala`: the lines of operator-and-status.md after the backups in `installation(...)`, and `model server: none` when absent; `CLI/mcp/AnkkaTools.scala`: `installation_status`'s description names the served models. Case in `CLIT/OutputSuite.scala` for both renderings.

### On k3s

- [X] T036 [US1] `CPT/InstallationModelsClusterFeatures.scala`: a `GherkinSuite` over `features/installation-models/` in `ObjectStorageGcsClusterFeatures`' shape: `PkiStack`, `GatewayStack`, `ModelServerStack.install`, the operator in the test JVM with `settings.models` from the stack, the control plane deployed with `ANKKA_MODELS_ADDRESS`; the steps for `serving.feature`: a Python service from the template with the two variables and `with_model("local")` on an agent with a tool `lookup`; "the message is answered" through the deployed service's route; "nothing leaves the installation" by the keeper's `calls.answered` before and after (through the status forward) and, for that scenario, a `NetworkPolicy` denying egress from the service's namespace applied for the turn and removed in a `finally`; "the status of the installation says …" through `ankka installation` (the CLI's `Main` as `DescriptorFeatures` drives it); "an installation without the model server is unchanged" by a second service deployed with the stack's settings removed from the operator (`settings.copy(models = None)`) and its pod's environment read with `ServicePods.environment` holding no `ANKKA_MODELS_ADDRESS`. `ranElsewhere` names nothing yet.
- [ ] T037 [US1] Add the suite's images to the matrix: nothing to list (`.github/cluster-suites.py` derives it), but run `python3 .github/cluster-suites.py InstallationModels*` and confirm it prints the class under project `controlPlane`; then `caffeinate -i sbt 'set controlPlane / Test / logBuffered := false' 'controlPlane/testOnly *InstallationModelsClusterFeatures -- *serving*'` green.

**Checkpoint**: `FEAT/serving.feature` green on k3s; `ankka installation` on `just deploy-models` prints the small model served.

---

## Phase 4: User Story 2 — Only the platform's program reaches the model server (Priority: P1)

**Goal**: a developer's program is refused at the handshake; the platform's program of the same
service is answered; a workload with no certificate is refused; every call is a turn or an
iteration in a session, under the provider's name.

**Independent Test**: `FEAT/access.feature` in `InstallationModelsClusterFeatures`.

- [X] T038 [US2] Steps for `access.feature` in `CPT/InstallationModelsClusterFeatures.scala`: "the process of X connects to the model server" is `ServicePods.inPod` running `curl -sS -o /dev/null -w '%{http_code}' https://models.models-system.svc:8443/v1/models` in the `-app` container, asserting exit 35 (TLS handshake failure) and no status; "the platform's own program of X connects" is the same `curl` in the sidecar container with `--cert /var/run/secrets/ankka/service/tls.crt --key …/tls.key --cacert …/ca.crt`, asserting 200; "a workload that holds no service certificate" is `InPod.curl` from the `prober` pod in a project namespace, exit 35; "every call … is a turn or an iteration in a session" asserts the keeper's `calls.answered` equals the number of turns and iterations the suite drove, read from the session (the local console's or `services logs`), and the recorded model usage names `local`.
- [X] T039 [P] [US2] Case in `MKT/DoorSuite.scala`: a certificate from a *different* authority (a second `TestPki`) fails the handshake, so a certificate of another installation opens nothing (R2's "the authority issued").
- [ ] T040 [US2] Run `-- *access*` on k3s green.

**Checkpoint**: US1 and US2 green together on k3s.

---

## Phase 5: User Story 3 — A developer learns what is served and is told when it is not (Priority: P1)

**Goal**: the served models are read through the control plane; an unserved model fails the
service to start naming the served ones; an address or key beside the preset is refused; a
declared model still being fetched holds the service as `UpdateInProgress` and it starts when
served; outside an installation the preset is told there is no server.

**Independent Test**: `FEAT/naming.feature` in `InstallationModelsClusterFeatures`, with the
remote-cluster scenario in `ranElsewhere` waiting on feature 050.

- [X] T041 [US3] Steps for `naming.feature` in `CPT/InstallationModelsClusterFeatures.scala`: a descriptor naming `mistral:7b` → `services get` `Failed` within one operator resync interval plus a margin, with the detail `installation model "mistral:7b" is not served; served: qwen3:0.6b` and `ServicePods.pods` empty; the corrected descriptor → `Ready`; a descriptor with `ANKKA_MODEL_LOCAL_URL` or `_KEY` beside the preset (outline) → `Failed` with the two details; `ModelServerStack.redeclare` adding `llama3.2:1b` and applying a service naming it at once → `UpdateInProgress` with `… is still being fetched`, then `Ready` once served, with the digest in the status block.
- [X] T042 [US3] `ranElsewhere` in `CPT/InstallationModelsClusterFeatures.scala`: `"a service naming an installation model on a cluster without a model server fails to start saying so" -> "feature 050 (remote clusters) is a spec with no code; the operator's Failed(\"this cluster has no model server\") is held by InstallationModelsSuite"`, and that case in `OPT/InstallationModelsSuite.scala` (T027) named for the scenario.
- [X] T043 [P] [US3] Case in `SCT/ModelsSuite.scala` named for "a service run outside an installation that names the preset installation is told there is no model server": the sidecar started with the two variables and no `ANKKA_MODELS_ADDRESS`, asserting the `StartRefusal` text and the documentation path; and the same for `ModelProviders.installation` in `modules/agent`'s suite (T031), with `ANKKA_CLUSTER_MODE` unset.
- [X] T044 [US3] `DOCS/reference/service-descriptor.md`'s refusal table gains the `ANKKA_MODELS_ADDRESS` row; `CP/api/ServiceEndpoint.scala` or `API/descriptors.scala` surfaces the refusal where other platform-only variables are refused (confirm `ServiceSpec.problems` already produces it from T007; add the row only).
- [ ] T045 [US3] Run `-- *naming*` on k3s green, `ranElsewhere` reported.

**Checkpoint**: US1 to US3 green; the MVP is deliverable.

---

## Phase 6: User Story 4 — Models are fetched, loaded and kept loaded by the installation (Priority: P2)

**Goal**: a model added is served without restarting services; a served model stays served while
no call comes; a model that cannot be fetched is reported while the others serve; a removed model
is no longer served; the server restarts without fetching again; a call in flight at a restart is
the provider's failure; a served model is fetched once and its digest recorded.

**Independent Test**: `FEAT/lifecycle.feature` in `InstallationModelsClusterFeatures`, with the
two timing scenarios also held by `KeeperSuite`.

- [X] T046 [US4] Steps for `lifecycle.feature` in `CPT/InstallationModelsClusterFeatures.scala`: pod names of the service recorded before `redeclare` and compared after; "stays served while no call comes" asserts `GET /api/ps` through the door (the sidecar container's `curl`) lists the model after six minutes since the suite's last call (one long case, tagged so `-- *stays*` runs it alone); the declaration with `no-such-model:1b` → `ankka installation` shows it `failed` with Ollama's line and `qwen3:0.6b` `served`; "stays served" also times two short calls, one before the idle period and one after, asserting the second within twice the first; removal → not served and a new service naming it `Failed` naming the served; `kubectl -n models-system delete pod models-0` → the pod back, `/api/ps` listing the model, the keeper's log showing no `pulling`; a message sent while the pod is deleted → the caller told `Unavailable` and the service `Ready`; the digest in the installation's status equal to `/api/tags`'s through the door.
- [X] T047 [P] [US4] Cases in `MKT/KeeperSuite.scala` named for "a served model stays served while no call comes" (the preload defeating the stand-in's two-second keep-alive, from T019) and "a served model is fetched once and its digest recorded" (a second pass issues no `Pull` and the digest is the stand-in's), and in `MKT/ReconcileSuite.scala` for "a model removed from the declaration is no longer served".
- [ ] T048 [US4] Run `-- *lifecycle*` on k3s green.

---

## Phase 7: User Story 5 — A model's capabilities are said at the first call (Priority: P2)

**Goal**: an agent with tools on a model without tool calling, or with effort on one without
thinking, is told so at the first call as the provider's failure naming the model and the
capability; the service is unaffected; a model with the capabilities is answered.

**Independent Test**: `FEAT/capabilities.feature`: three scenarios in `SCT/InstallationModelClientSuite.scala`
against the stand-in (the k3s installation serves only a model with both capabilities), the fourth
on k3s.

- [X] T049 [US5] `SC/InstallationModelClient.scala`: `capabilities(model): Either[String, Set[String]]` through the door (`POST /api/show`), cached per model on success; `check(request, model): Option[String]` answering `installation model "<m>" lacks tool calling` when the request has tools and `tools` is absent, `… lacks thinking` when `effort` is set or thinking is on and `thinking` is absent; wired into the preset's provider before each call, a `ModelCallFailed(name, message)` on `Some`. Cases in `SCT/InstallationModelClientSuite.scala` against `FakeOllama` behind a `TestPki` listener, named for the three scenarios: tools refused with nothing sent to `/v1/chat/completions`; effort refused; the autonomous-agent half asserting a failed iteration naming the capability and the service `Ready` (through the sidecar's test harness as `SidecarAgentSuite` drives an autonomous agent); a `/api/show` failure not cached.
- [X] T050 [US5] `ranElsewhere` entries for the three scenarios naming `InstallationModelClientSuite`; the fourth ("an agent on a model with the capabilities it needs is answered") is `serving.feature`'s tool-call step reused on k3s.
- [ ] T051 [US5] Run `sidecar/testOnly *InstallationModelClientSuite` and `-- *capabilities*` on k3s green.

---

## Phase 8: User Story 6 — The installation sizes and places the server (Priority: P3)

**Goal**: the overlay places and sizes the server; a call beyond what it runs at once waits and is
answered; a local platform asked for the server serves one small model on a CPU and one not asked
has none; a call past the model timeout times out as any model call.

**Independent Test**: `RemoteOverlaySuite` for placement (T024), `FEAT/placement.feature`'s other
scenarios in `InstallationModelsClusterFeatures`.

- [X] T052 [US6] Steps for `placement.feature` in `CPT/InstallationModelsClusterFeatures.scala`: "runs one call at once" is the stack's default (`OLLAMA_NUM_PARALLEL=1`); two agents sent a message at the same moment → both answered, neither `Unavailable`; "a local platform asked for the model server" and "not asked" are held by `RemoteOverlaySuite` (T024) and by the local deploy by hand (quickstart 7), named in `ranElsewhere`; the timeout scenario: a service with `ANKKA_MODEL_TIMEOUT` (or 052's per-provider timeout setting, whichever it names) of ten seconds, one long request in flight (a message asking for a long story with `max_tokens` high), a second caller told `Timeout` after ten seconds.
- [X] T053 [P] [US6] `ranElsewhere` for "the model server is placed and sized by the installation's own settings" → `RemoteOverlaySuite` (the cloud render's selector, toleration, GPU, parallel, class) and the two local-platform scenarios → `RemoteOverlaySuite` plus `deploy-local.sh` by hand, named.
- [ ] T054 [US6] Run `-- *placement*` on k3s green.

---

## Phase 9: User Story 7 — Operating and documenting the model server (Priority: P3)

**Goal**: the keeper's metrics reach the telemetry store; a call is a span in the turn's trace;
the documentation describes installation models, the install guides the component, the
limitations what it does not do.

**Independent Test**: `TelemetryStoreSuite`/`ExportedMetricsSuite` for the metrics, a k3s trace
read for the span, `just docs` and `just features` for the documentation.

- [X] T055 [P] [US7] Case in `modules/telemetry-otlp/src/test/…/ExportedMetricsSuite.scala` (or a new `KeeperMetricsSuite` in `MKT`) against `FakeCollector`: `KeeperMetrics` built with the fake's endpoint emits the eight instrument names of keeper.md with `service.name` `ankka-model-keeper`; named for "the model server's metrics reach the installation's telemetry store".
- [X] T056 [US7] `ranElsewhere` entry for "a call to an installation model is a span in the turn's trace" naming `DoorSuite`'s span case (T017), and a case in `SCT/InstallationModelClientSuite.scala` that the preset's provider sends `traceparent` on its calls so the door's span is a child of the provider's; the k3s suite reads no trace.
- [X] T057 [P] [US7] `DOCS/platform/installation-models.md` (R10: what the server is and is not, the declaration and its ConfigMap, naming a model in Scala, Python, TypeScript and Rust, the refusals in the contract's words, the capability failures, the status and the CLI output, models known to call tools on a CPU and on a GPU, that nothing leaves the installation, re-fetching by removing and re-adding, the opt-in flag), in `mkdocs.yml` after `platform/object-storage.md`.
- [X] T058 [P] [US7] `DOCS/platform/install-local.md` (the flag and `just deploy-models`), `DOCS/platform/install-cloud.md` and `DOCS/platform/install-gke.md` (the component, `models-size.yaml`, the accelerator node pool as the installation repository's, the keeper's image in the `images:` block), `DOCS/reference/configuration.md` (`ANKKA_MODELS_ADDRESS` platform-only; the keeper's `ANKKA_KEEPER_*` under the components' settings), `DOCS/reference/limitations.md` (FR-015's paragraph: one server per cluster, administrator-chosen models, no uploads, no judgments, no embeddings, nothing served outside, an installation without egress supplies models itself, remote clusters not yet), `DOCS/build/agents.md` (a row for the preset linking the page), `DOCS/deploy/upgrading.md` (adding the component rolls every service once).
- [X] T059 [P] [US7] `tools/docs/skill/ankka-platform/SKILL.md` gains the installation-models section; `just docs-sync` renders the skills.
- [X] T060 [US7] `just docs` green with every new variable mentioned in prose beside its generated table; `just features` green; the three documentation scenarios named in `ranElsewhere` as `just docs` (as the other documentation features are held).

---

## Phase 10: Polish and the whole build

- [X] T061 [P] `GLOSSARY.md`: the `*Proposed.*` markers on `served model`, `capability` and `keeper` removed with a line each on what they do not mean, now that the plan has fixed them; `model provider`, `preset` and `effort` left for feature 052's clarify.
- [X] T062 [P] `.claude/rules/kubernetes.md`: a section "The installation's model server" in the broker's and object store's manner (the component, the keeper, loopback-bound Ollama, the plain status port, the variable that rolls every service, `models-system`), and the traps found on the way (at least: Ollama's negative keep-alive reading, the 3.8 GB image in a k3s run); `.claude/rules/agents.md`: one paragraph on the preset and the capability check.
- [ ] T063 `sbt scalafmtCheckAll scalafmtSbt`, `sbt -Dankka.cluster.tests=off test` awake, then `caffeinate -i sbt 'controlPlane/testOnly *InstallationModelsClusterFeatures'` whole, then `gh workflow run cluster --ref 053-installation-models -f suite=InstallationModelsClusterFeatures` on both architectures and `gh run watch`; fix what either finds.
- [ ] T064 `sbt buildAll`; the release notes draft naming the three operational consequences of plan.md (every service rolls once, the new image, Ollama's image size); the tick count here updated.

---

## Dependencies

- Phase 1 → Phase 2 → every story. T030, T031, T032 and T049 also need feature 052 on the branch.
- US1 (Phase 3) is the MVP and needs T005–T011, T012–T021 (the keeper), T022–T026 (the component).
- US2 (Phase 4) needs US1's suite (T036) and the keeper's door (T016, T017).
- US3 (Phase 5) needs T027–T029 (the plan) and T033–T035 (the status).
- US4 (Phase 6) needs T018–T019 (the loop) and T026's `redeclare`.
- US5 (Phase 7) needs T030 (the preset).
- US6 (Phase 8) needs T023–T025 (the overlays and the flag).
- US7 (Phase 9) needs T018 (metrics) and T016 (the span); the documentation needs every contract's final wording.
- Phase 10 needs everything.

## Parallel opportunities

- Phase 1: T003 and T004 together, after T001.
- Phase 2: T005, T007, T008, T009, T010 together; T012, T013, T014 together; T022, T023, T026 together once T021 names the image.
- Phase 3: T027 and T028 together; T031, T033, T035 together; T030 after 052.
- Phases 4 to 8 are independent of each other once Phase 3's suite exists; their k3s runs serialise on the one node.
- Phase 9: T055, T057, T058, T059 together.

## Implementation strategy

MVP is Phase 1 to Phase 3: the keeper, the component, the plan, the preset and the status, proven
by `serving.feature` on k3s. Phase 4 and Phase 5 complete the P1 stories and make the feature
honest about access and refusals. Phases 6 to 9 add the P2 and P3 behaviour and the documentation.
Each phase ends green offline before its k3s run; k3s runs are serialised and, on a laptop, under
`caffeinate -i`.
