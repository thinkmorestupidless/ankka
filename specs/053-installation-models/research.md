# Research: Installation Models

Decisions for [plan.md](plan.md), each with what in the repository or in Ollama it rests on. File
references are to this branch's base (`b331c9a9`). "Verify first" marks a claim read from code or
documentation and not yet run; the task that touches it starts with a spike that would show it
false. The words are the glossary's: the installation runs a **model server** that serves
**installation models**, each a **served model** once fetched; a service names one as a **model
provider** through the **preset** `installation`.

## R1. The server is Ollama, loopback-bound, and what it does and does not do

**Decision**: `ollama/ollama:0.40.6`, the release of 2026-10-16, run with `OLLAMA_HOST=127.0.0.1:11434`
so it listens on the pod's loopback alone, `OLLAMA_KEEP_ALIVE=-1`, `OLLAMA_NUM_PARALLEL` from the
component's setting (default 1), `OLLAMA_MODELS=/var/lib/ollama` on the volume, and
`OLLAMA_NO_CLOUD=true` so nothing of a call can be sent to Ollama's hosted service.

**Rationale**: Ollama serves the OpenAI protocol's Chat Completions shape with `tools`,
`reasoning_effort` and streaming, which is what feature 052's provider speaks; its own API reports
each model's `capabilities` (`completion`, `tools`, `insert`, `vision`, `embedding`, `thinking`,
`image`, `audio`, `decision`, `types/model/capability.go`), a model's `digest` (`/api/tags`), what is
loaded (`/api/ps`), fetches by tag (`/api/pull`, streamed, ending in `success`) and removes
(`/api/delete`). `OLLAMA_KEEP_ALIVE` accepts a Go duration or seconds and a negative value means
forever (`envconfig/config.go`); `OLLAMA_NUM_PARALLEL` is per loaded model and defaults to 1;
`OLLAMA_MAX_QUEUE` defaults to 512 and a request beyond it is answered "server busy, please try
again. maximum pending requests exceeded" (`server/sched.go`). It is MIT licensed (`LICENSE`), so
principle 1 holds, and its image is published for `linux/amd64` (3.8 GB) and `linux/arm64` (2.8 GB),
so feature 049's both-architecture installations run it. It has no metrics endpoint (none registered
in `server/routes.go`; no `OLLAMA_*METRICS*` variable), no authentication worth the name
(`OLLAMA_AUTH` is off and undocumented) and no TLS; those are the keeper's (R2). A model without
`tools` sent tools is refused by Ollama itself, and one without `thinking` asked to think is a 400
`"<model>" does not support thinking`; the keeper's capability read (R5) gives the better message
first.

**Alternatives considered**: llama.cpp's `llama-server` (smaller image, speaks both protocols) —
rejected: it serves one model per process and has no pull or capability API, so the declaration
loop would be a model-file manager of ankka's own. vLLM — rejected for the default: NVIDIA only, no
CPU path for a laptop or the suites; an installation with a GPU pool may still prefer it, and the
door and the preset do not care what is behind them, so it is a later component, not a change here.

**Verify first**: (S1) in the real image, a `/api/generate` with no prompt and `keep_alive: -1`
loads a model that `/api/ps` then reports with no `expires_at` in the past, and it is still loaded
after the default five minutes; `OLLAMA_KEEP_ALIVE=-1` as an environment variable does the same
for every load. The scheduler's `processCompleted` treats a session duration of zero or less as
"expire now", and the negative value is turned into a maximum duration earlier, in the API's
duration type: the spike shows which reading is right in 0.40.6. (S1 also) the HTTP status of the
queue-full answer, which the sidecar must read as the provider's failure, not a refusal.

## R2. The keeper: a program of ankka's in the model server's pod

**Decision**: a new module `model-keeper` and image `ankka-model-keeper`, the second container of
the model server's pod, which is:

- **The door.** A JDK `HttpsServer` on port 8443 configured by `RotatingServerTls` (the proxy's)
  over a `RotatingTls` reading the Certificate's Secret mounted at `/var/run/secrets/ankka/service`,
  so it rotates as every workload's does. Every connection must present a certificate the
  `ankka-service` authority issued, or the handshake fails (the proxy's `TlsTransport` already
  behaves so). The sender is read by `Caller.fromCertificate`; `Caller.Service(project, name)` is
  admitted (every service of every project, and the platform's own `platform/controlplane`);
  `Caller.Gateway`, a mount certificate and anything else are refused with 403. Admitted requests
  on `POST /v1/chat/completions`, `GET /v1/models`, `POST /api/show`, `GET /api/tags` and
  `GET /api/ps` are forwarded to `http://127.0.0.1:11434` with the body streamed both ways
  (`proxy-core`'s `StreamingBody`, so server-sent events flow as they arrive); every other path is
  404, so no service can pull, delete or create a model. `GET /ankka/models` answers the status
  document (data-model.md) to an admitted caller.
- **The loop.** Reads the declaration file (a ConfigMap mounted at `/etc/ankka/models/models.json`)
  at start and whenever its modification time changes (as `RotatingTls` watches its files), asks
  Ollama what it holds and what is loaded, and plans: pull what is declared and absent (one at a
  time, in declaration order), load what is pulled and not loaded (a `/api/generate` with no prompt
  and `keep_alive: -1`), record the digest `/api/tags` reports, delete what is held and no longer
  declared, and mark failed what Ollama could not pull, with Ollama's last status line as the
  reason. A failed pull is retried on the next pass with a backoff from a minute to an hour; a
  declaration change resets it. The pass runs every thirty seconds and on a change.
- **The status.** The document the loop last published, served on the door and on the plain status
  port 8080 (for the operator, R6), and the probe on 7627 (`/ready`: Ollama answers and the loop has
  completed one pass).
- **The metrics.** `telemetry-otlp`'s `Metrics` built directly, with `ANKKA_OTLP_ENDPOINT` from the
  component: `ankka.models.calls` (counter, by model and outcome), `ankka.models.in_flight`
  (up-down counter), `ankka.models.declared`, `ankka.models.served`, `ankka.models.loaded` (gauges)
  and `ankka.models.fetch_failures`. Each forwarded call is a span, child of the trace context the
  sidecar sends, so a turn's trace holds the door's span beside the provider's.

The JVM runs as the proxy's does: `SerialGC`, C1 only, one processor, a 250m allotment, because it
is I/O bound and the measured proxy serves within two seconds of start on that.

**Rationale**: three things must happen in Ollama's pod and nothing that exists can do them. The
sidecar cannot start without forming a cluster and opening a database (`web-hosting.md`). The
proxy's engine admits the internet, the service itself and named callers under one service's
identity, and a web-hosted pod's shape; bending it to "any service, no process, these five paths,
plus a loop" is a second proxy inside the first. The keeper reuses the proxy module's two pieces
that matter, the rotating server context and the caller reading, and `proxy-core`'s streaming body,
and adds about four hundred lines. The alternative of a bare Envoy for the door still leaves the
loop, the status and the metrics unowned.

**Why Ollama is loopback-bound rather than policed**: a NetworkPolicy decides by pod, and the
developer's program shares the sidecar's pod, so no policy can let the sidecar in and keep the
process out. Binding Ollama to `127.0.0.1` and exposing only the keeper's ports through the Service
makes the door the only way in; there is no port for a policy to get wrong.

**Alternatives considered**: a second `main` in the `proxy` image — rejected: sbt-native-packager
fixes the entrypoint's class, and a component overriding it with `-main` is a trap waiting to be
tidied away. Putting the loop in the operator — rejected: the operator would then pull models,
which is minutes of I/O in a reconcile loop, and would need a route into the pod's loopback.

**Verify first**: (S2) the OpenAI Java SDK's `OpenAIOkHttpClient.builder().sslSocketFactory(…)
.trustManager(…)` with a context from `RotatingTls.contextRequiring("ankka://platform/models")`
completes a handshake with the door and the door reads the sidecar's identity; and a plain
`HttpClient` with no certificate fails at the handshake, not with a 403. The SDK's README documents
both builder methods under "Mutual TLS with native JSSE".

## R3. The component: one StatefulSet in `models-system`, and the opt-in local overlay

**Decision**: `kustomization/components/models/` is a `Component` holding a Namespace
`models-system`; a ConfigMap `models-declaration` with `models.json` (`{"models": ["qwen3:0.6b"]}`
as shipped); a cert-manager `Certificate` `ankka-models` from the `ankka-service` `ClusterIssuer`
with the URI `ankka://platform/models` and the Service's DNS names, at the workload default
duration (the keeper re-reads its files, so no long life is needed as the broker's is); a
one-replica StatefulSet `models` with the containers `ollama` and `keeper`, one
`volumeClaimTemplate` `models` of 20Gi, a readiness probe on the keeper's 7627; a Service `models`
with ports `models` 8443 and `status` 8080; a NetworkPolicy (contracts/component.md); and two
patches, one adding the operator's three `ANKKA_MODELS_*` settings to `ankka-operator` and one
adding `ANKKA_MODELS_ADDRESS` to `ankka-controlplane`. Placement and size are a strategic-merge
patch an overlay writes on the StatefulSet: `overlays/cloud/models-size.yaml` is the example, with
a node selector, a toleration, `nvidia.com/gpu: 1`, memory, `OLLAMA_NUM_PARALLEL` and the claim's
size and class, all marked `SET`, as `broker-size.yaml` is. The namespace is outside the
`ankka-<name>` pattern for Garage's reason: a project called `models` could otherwise name it.

The local deploy gains one flag: `ANKKA_LOCAL_MODELS=on ./kustomization/deploy-local.sh` (Justfile:
`deploy-models`) renders `kustomization/overlays/local-models/` instead of `local` — an overlay
whose `resources` is `../local` and whose `components` adds the model server — loads
`ankka-model-keeper:latest` into kind beside the other images, and waits for the StatefulSet. Without
the flag nothing of the component is applied or loaded, which is the clarification's answer.

**Rationale**: the broker and the object store are the shape (`kubernetes.md`): a component with
its own namespace, a patch that tells the operator, a policy that says who connects, and an
installation's overlay that sizes it. An opt-in that is an overlay rather than a script branch is
what `RemoteOverlaySuite` can render and what a GitOps tool can apply; a `Component` cannot be
applied alone, so the opt-in overlay composes the local one rather than the script applying the
component after it.

**Alternatives considered**: a Deployment with a PersistentVolumeClaim — rejected: a StatefulSet's
claim template is what the object store uses and what gives the pod a stable claim across replaces.
Baking models into an image — rejected for the default: a 523 MB model in an image is a second
registry artefact per model; an installation without egress can still build one and set
`OLLAMA_MODELS` to it, which the limitation says.

**Verify first**: (S4) the keeper's readiness probe on 7627 is answered under the NetworkPolicy on
k3s and kind; every platform container already probes 7627 under a policy, so this is confirmation,
not discovery.

## R4. The operator's settings and the one variable every platform container gets

**Decision**: `ModelServerSettings(address, statusUrl, namespace)` from `ANKKA_MODELS_ADDRESS`,
`ANKKA_MODELS_STATUS_URL` and `ANKKA_MODELS_NAMESPACE`, all three or none, read as
`BrokerSettings.read` reads (`operator/Settings.scala:460`), refusing to start by halves.
`PlatformVariables.ModelsAddress = "ANKKA_MODELS_ADDRESS"` joins the platform-only names a
descriptor may not give (`ServiceSpec.problems` refuses it as it refuses `ANKKA_CLUSTER_MODE`), and
the operator renders it on the platform container of every embedded and process-hosted service when
the settings are present: the one container of an embedded service, the sidecar of a process-hosted
one, never the process and never the proxy (a web-hosted service has no agents). It is rendered as
an `EnvEntry` beside the broker's (`StrimziRendering.environment`), platform-side only.

**Rationale**: the sidecar resolves the preset from its environment at start, as it resolves
`ANTHROPIC_API_KEY`, and the start-time refusals of FR-009 need the address then. The name cannot
be under `ANKKA_MODEL_`: feature 052 reads `ANKKA_MODEL_<NAME>_<FIELD>`, so any name there is a
provider's. `ANKKA_MODELS_` does not begin `ANKKA_MODEL_` (the underscore differs), so the family's
prefix routing is untouched, and the variable is platform-only rather than runtime-only: a
descriptor that sets it is refused, since only the operator knows where the server is.

**Consequence**: an installation that adds the component changes every service's pod template by
one variable and rolls each once, as adding the broker did. An installation without the component
renders exactly what it rendered before: the golden and unchanged suites hold that. The release
notes say so.

**Alternatives considered**: mounting the address as a file the sidecar reads lazily — rejected:
the preset's refusals must happen at start (FR-009), and a service deployed before the component
would read the file at its first call and refuse then, which the spec forbids.

## R5. The preset `installation`, in the sidecar and in Scala

**Decision**: feature 052's `Models` (sidecar) gains the preset `installation`: protocol `openai`,
shape Chat Completions, address `${ANKKA_MODELS_ADDRESS}/v1`, no key, the model from
`ANKKA_MODEL_<NAME>_MODEL`, and a client built with `sslSocketFactory` and `trustManager` from the
sidecar's `RotatingTls` at the service mount requiring `ankka://platform/models`, so the call
presents the service's certificate and accepts only the model server's. At start: an address or a
key given beside the preset is refused naming the variable; no model is refused; no
`ANKKA_MODELS_ADDRESS` in the environment is refused with the message FR-010 asks for, naming the
documentation page. A Scala service builds the same through `ModelProviders.installation(model)`
(052's constructors' file), which reads the variable and the runtime's `RotatingTls`.

The capability check is the sidecar's `InstallationModelClient`: at a provider's first call for a
model, `POST /api/show` through the door, the `capabilities` array cached for the process's life;
a request with tools against a model without `tools`, or with `effort` set or thinking on against
one without `thinking`, is `ModelCallFailed(name, "<model> lacks <capability>")` before any call to
the model, and the loop answers `Unavailable` as for any provider's failure. The sidecar also asks
`GET /ankka/models` once at start for FR-009's belt: a model not served when the sidecar starts
refuses the start naming the served models, which only happens if a model left the declaration
between the operator's pass and the pod's start.

**Rationale**: the preset is one more row in 052's table, which is the point of having presets. The
certificate is the credential, so the client is the one piece a vendor preset does not have, and it
comes from what the sidecar already holds for every call to another service. The capability check
is read from Ollama rather than guessed because models differ and the declaration does not say.

**Verify first**: (S3) `qwen3:0.6b` through Chat Completions with one tool declared calls the tool
on a short message, on two CPUs, within the default model timeout, and `reasoning_effort: "low"` is
accepted; and (S2) above.

## R6. The operator decides per service, in the bucket's shape

**Decision**: `InstallationModels.named(spec)` reads the resource's `env` for every
`ANKKA_MODEL_<NAME>_PRESET` equal to `installation` and its `_MODEL`, `_URL` and `_KEY`;
`InstallationModels.decide(named, settings, observed)` is pure and answers `NotNeeded` (names none),
`Failed("this cluster has no model server")` (names one, no settings), `Failed("… takes no
address/key")`, `Failed("<model> is not served; served: a, b")` (not declared, or failed, naming
the reason), `Waiting("<model> is still being fetched")` (declared, not yet served) or
`Ready(models with digests)`. `withheld` is `Waiting` or `Failed`, `refused` is `Failed`, both
read by `ServiceReconciler` exactly where `ObjectStorage.withheld` and `refused` are
(`ServiceReconciler.scala:71`): the Deployment is not applied, the lifecycle is `UpdateInProgress`
with the detail or `Failed` with it, and instances already running are left alone. The observation
is the keeper's status document, read by `ModelServerDocumentReader` over the plain status port with the
JDK's client (as `GarageStore` reaches Garage), once per reconcile pass and shared by every service
in it; a status that cannot be read is `Waiting("the model server is not answering")`, never
`Failed`, because a restarting keeper is not a missing model. A waiting service is requeued at
thirty seconds, as the cloud path requeues at its bound. The resource's status gains
`installationModels: InstallationModelsStatus(phase, models: [{name, digest}], detail)`, declared
in `ankkaservice.yaml` and held by `CrdSchemaSuite`; the control plane folds its detail into
`services get` as it folds the bucket's.

**Rationale**: the bucket's cloud path is the exact precedent for "hold the Deployment back,
report why, fail when it never will be": the plan is pure, the reconciler asks one function, and
the status carries the reason. The operator reading `spec.env` for a preset is what it does for
`ANKKA_S3_*` (`ObjectStorage` "reads `spec.env` to tell its own from none"). The operator cannot
hold a service certificate without becoming a service, so the keeper's status is a plain port the
policy admits the operator's pods alone to, as Garage's administration port is.

**Alternatives considered**: refusing at apply in the control plane — rejected: the served list is
live and per cluster, and `controlplane-api`'s rules are static. Letting the sidecar's start
refusal be the only check — rejected: a crash-looping pod is `Failed` with a detail, but a model
still being fetched would crash-loop instead of waiting, and the spec says it waits.

## R7. The installation's status reads the keeper with the control plane's own certificate

**Decision**: `InstallationModelsReader` in `controlplane/deploy`, beside `PlatformBackupsReader`:
`GET /ankka/models` on `ANKKA_MODELS_ADDRESS` (the component's second patch gives the control plane
the same variable) with a `RotatingTls` over the control plane's service mount requiring
`ankka://platform/models`, as `InstanceTopologies` builds one per expected peer. `GET /installation`
gains `models: Option[InstallationModels]`, a list of clusters each with its server's address and
its model lines (name, state, digest, detail); today one entry, `home`. `ankka installation status`
prints a line per model; the MCP `installation_status` tool's description names it. Absent the
variable, the field is absent and the CLI says the installation has no model server.

**Rationale**: the control plane is a service of the installation with a certificate the door
admits, so it needs no new port, no new permission and no new object; it reads the same document
the operator and the sidecar read. Nothing is journaled: the list is a fact of the cluster, not a
record, as backups' lines are.

## R8. Metrics are pushed; a call is a span

**Decision**: the keeper pushes OTLP metrics and spans to `ANKKA_OTLP_ENDPOINT`, which the
component sets to the collector's address, through `telemetry-otlp`'s `Metrics` and the
OpenTelemetry SDK the module already carries; the sidecar's provider span for an installation model
is the parent of the door's span by the `traceparent` header the sidecar's client already sends to
another service. `TelemetryStoreSuite` gains a case that the keeper's metric names arrive when the
stand-in collector receives them, and `DoorSuite` holds the span's attributes and parent from a
`traceparent` header; the k3s suite does not read a trace.

**Rationale**: the collector's configuration has OTLP receivers only (`otel-collector/config.yaml`)
and Ollama exposes nothing to scrape (R1). Every platform program pushes; the keeper is one more.

**Spec amendment**: FR-014's "scraped into" becomes "reach", and the observing feature's steps say
"reach".

## R9. The suites

**Decision**:

- `modules/test-ollama` (a test helper module like `test-pki`): `FakeOllama`, a loopback stand-in of
  the six paths with a scriptable catalogue (which tags exist, each one's capabilities and digest,
  how a pull proceeds or fails, what is loaded). `model-keeper`: `ReconcileSuite` (every row of the plan's table, including removal and a failed pull's
  reason); `OllamaClientSuite` against the stand-in; `DoorSuite` with `test-pki`'s certificates (a
  service admitted and forwarded, the gateway's refused 403, no certificate refused at the
  handshake, a disallowed path 404, a streamed body arriving in pieces); `KeeperSuite` end to end
  with a declaration file changed under it.
- `core`: `PlatformVariablesSuite` for the platform-only name; `ModelServerDocumentSuite` for the
  document's codec.
- `operator`: `ModelServerSettingsSuite`, `InstallationModelsSuite` (the table), the golden file
  `installation-model.txt`, `RenderingUnchangedSuite` with no change, `CrdSchemaSuite`,
  `ModelServerDocumentReaderSuite` against a loopback document.
- `sidecar`: the preset's resolution and each refusal in `ModelsSuite`; the capability check and
  the door's client in `InstallationModelClientSuite` against `FakeOllama` behind a `test-pki`
  listener; the conformance suite's case naming the preset against the stand-in.
- `controlplane`: `RemoteOverlaySuite` renders `local-models` and the cloud overlay with
  `models-size.yaml`, asserting the two patches' shape and that `local` alone renders no model
  server; `InstallationEndpointSuite` with a stubbed reader; `InstallationModelsClusterFeatures`
  (k3s) with `ModelServerStack`.
- `cli`: the status output.

**The k3s suite's cost**: Ollama's image is 3.8 GB on amd64 and `qwen3:0.6b` is 523 MB, fetched
from Ollama's registry at the component's start; a run adds several minutes to a runner's time and
needs egress from the k3s container, which the other suites already need for images. One suite,
one runner per architecture, as every k3s suite.

## R10. Documentation

**Decision**: `docs/platform/installation-models.md` beside `broker.md` and `object-storage.md`
in `mkdocs.yml`'s platform section: what the server is and is not, the declaration, naming a model
in each language, the refusals and their wording, the capability failures, the status, models
known to call tools on a CPU (`qwen3:0.6b`, `qwen3:1.7b`, `llama3.2:1b`) and on a GPU, that nothing
leaves the installation, and the opt-in flag. `install-local.md` gains the flag; `install-cloud.md`
and `install-gke.md` gain the component, `models-size.yaml` and the sentence that the accelerator
node pool is the installation repository's; `configuration.md` gains `ANKKA_MODELS_ADDRESS` as
platform-only; `service-descriptor.md`'s refusal table gains the three refusals; `limitations.md`
gains the paragraph FR-015 lists; `agents.md` links the page from the presets table 052 adds. The
`ankka-platform` skill gains the section. Pages stand alone and name no feature.

## R11. Remote clusters are not built; the shape is per cluster, the proof is the home cluster

**Decision**: feature 050 is `specs/050-remote-clusters/spec.md` and nothing else on `main`. This
feature keeps the status per cluster (a list with one entry, `home`) and the operator's settings per
operator, which is what a remote operator will carry when 050 lands, and names the scenario "a
service naming an installation model on a cluster without a model server fails to start saying so"
in `ranElsewhere` as waiting on 050. The spec's Dependencies say so.

## R12. Checks that could pass while false, and how each is sharpened

- **"Nothing leaves the installation."** Asserting the reply arrived proves nothing about where it
  came from. The suite reads the keeper's `ankka.models.calls` counter before and after the turn
  through the status document's counters, and, for the one scenario, applies an egress-denying
  NetworkPolicy to the service's namespace for the duration and asserts the turn still completes.
- **"A developer's program is refused."** A 403 would mean the door read a certificate. The suite
  runs `curl` in the process container without a certificate and asserts the failure is a TLS
  handshake failure (exit 35), not an HTTP status; and from the sidecar container with its
  certificate asserts a 200.
- **"A served model stays served."** The suite cannot wait an hour. It sets the stand-in's
  `keep_alive` default to a few seconds in `KeeperSuite` and asserts the keeper's preload defeats
  it; on k3s it asserts `/api/ps` through the door shows the model loaded after the model's default
  five minutes have passed since the last call, timed from the suite's own call.
- **"Fails to start naming the served models."** A `Failed` lifecycle alone could come from the
  pod crash-looping on something else. The suite asserts the detail's text names the model and the
  served list, and that no pod of the service exists.
- **"A model added is served without restarting services."** The suite records the service's pod
  names before the declaration change and asserts the same names after the model is served.

## Spikes

| Spike | Shows false | Where |
|---|---|---|
| S1 | `keep_alive: -1` unloads at idle in 0.40.6; a preload does not keep a model loaded; the queue-full status is not a 5xx | `model-keeper/src/test/…/OllamaSpike`, against the real image in a container, `-Dankka.spikes=on` |
| S2 | the OpenAI SDK's client cannot present a `RotatingTls` certificate, or the door cannot read it | `sidecar/src/test/…/ModelDoorSpike` |
| S3 | `qwen3:0.6b` does not call a tool through Chat Completions on a CPU within two minutes | `OllamaSpike` |
| S4 | the probe on 7627 is refused under the component's policy | `ModelServerStack` on k3s, first run |
