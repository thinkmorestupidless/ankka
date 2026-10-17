# Feature Specification: Installation Models — A Model Server the Installation Runs

**Feature Branch**: `053-installation-models`

**Created**: 2026-10-17

**Status**: Draft

**Input**: User description: "let's spec out running ollama as an inference server in our own
cluster as the next spec after this" — "this" being feature 052, which makes any model provider a
named configuration of a protocol, an address, a model and an optional key, with presets for the
endpoints ankka has tested.

## Context

After feature 052 a deployed service names its model providers by name, and the platform's own
program, the sidecar, talks to each; a developer's program holds no key and makes no model call. A
provider is a hosted vendor, or any server speaking one of the two protocols ankka knows, and a
developer with Ollama on a laptop points a provider at `localhost`. In a cluster, `localhost` is the
pod, and there is nothing there to point at: an installation offers its services a database, a
broker, an object store and a secret store, and no model. Every agent deployed on an installation
sends every token to a vendor outside it.

That is the right default for most, and the wrong only option for some: a service whose data may not
leave the installation; a product whose per-call cost must be the hardware's and not a meter's; a
development installation where every agent test is a paid call; an open-weights model the team has
tuned. Each of these wants a model served inside the cluster, reached the way the broker is reached,
chosen in the descriptor the way a model provider now is, and accounted the way every model call is.

The installation already has the shape for a shared thing services use without owning: the broker
(feature 027) is a component the operator provisions into, known to services by the certificate they
hold; the object store (feature 034) is a component in a namespace of its own outside the project
pattern, administered by the operator through one trait and reached by services at an address the
platform hands them; both are optional, and an installation without them renders exactly what it
rendered before. A cloud installation places such a component in its overlay; the local installation
includes it so the suites and a laptop have it. Settings reach the operator as `ANKKA_<THING>_*`
variables by the component's patch. Zero trust says every port in the cluster is mutual TLS from
cert-manager and a caller is read from its certificate's `ankka://<project>/<service>` URI, and the
platform's container holds that certificate while the developer's program does not.

This feature makes these decisions.

- **The installation may run one model server, as it runs one broker.** A component under
  `kustomization/components/`, in a namespace of its own outside the `ankka-<name>` pattern, running
  Ollama with a volume for its models. An installation without the component has no installation
  models and nothing changes for it. An installation with remote clusters runs a model server per
  cluster that serves models, or none; a service is answered by its own cluster's.
- **Which models are served is the installation's to say.** The component's configuration lists the
  models, by the names Ollama's registry knows. The server pulls each at start into its volume, loads
  it, and keeps it loaded, so the first call pays no load; a model added to the list is fetched and
  served without restarting any service. The list is read through the control plane as part of the
  installation's status, so a developer learns what they may name without asking an administrator.
- **A service names an installation model as it names any provider.** Feature 052's preset
  mechanism gains `installation`: `ANKKA_MODEL_<NAME>_PRESET=installation` and
  `ANKKA_MODEL_<NAME>_MODEL=<served model>`, no address and no key, because the platform supplies the
  address as it supplies the broker's: one platform setting, `ANKKA_MODELS_ADDRESS`, on every
  platform container of an installation with a model server, which a descriptor may not give.
  Adding the model server to an installation therefore rolls every service once, as adding the
  broker did. In Scala, a constructor taking the model's name. The sidecar
  speaks to the server in the OpenAI protocol's Chat Completions shape, as it speaks to Ollama on a
  laptop; `effort` reaches the server as its reasoning effort, `temperature` is sent, and usage is
  recorded as the server reports it, with no cache columns.
- **Only the platform reaches the model server.** The server accepts a call only from a holder of a
  service certificate of the installation, which the platform's container has and the developer's
  program does not. The door is a small program of the platform's beside the model server in its
  pod, the keeper, which also runs the declaration, answers the status and sends the metrics; the
  server itself listens on the pod's loopback alone, so the keeper is the only way in. So a process cannot call the model behind the loop's back: every call to an
  installation model goes through the loop, its guardrails, its approvals and its accounting, exactly
  as a vendor's does, and the invariant that no developer's program talks to a model holds without a
  key to protect. Traffic from the platform's container to the server is mutual TLS; the server's own
  process behind that door speaks plain HTTP on its loopback, as the object store does inside its pod.
- **A model that is not served is refused where the service starts, naming what is.** A descriptor
  naming an installation model the installation does not serve, or on a cluster without a model
  server, is refused at the service's start with a detail naming the served models, visible in
  `ankka services get`, and the service is `Failed` as it is for any start refusal: a service that
  asked for a model cannot be relied on without it. A model that is declared but not yet fetched holds
  the service back as an unanswered bucket does, `UpdateInProgress` with why, and it starts when the
  model is served.
- **Capability is the model's; the platform says which.** A model without tool calling given an
  agent with tools, or without thinking given `effort`, is told so at the first call as a provider's
  failure naming the model and the capability, as a wrong vendor model name is today. The
  documentation names models known to call tools well on a CPU and on a GPU, and the local
  installation's default is one of them.
- **Hardware is the installation's business.** The component runs on CPU by default with the small
  model the local installation serves, and carries the knobs an installation sets in its overlay:
  node selection and tolerations for an accelerator node pool, the accelerator count, memory, how
  many calls run at once, and the volume's size and class. The node pool itself, on a cloud, is the
  installation repository's. A call beyond what the server runs at once waits in the server's queue
  and is answered or times out as any model call is, bounded by the model timeout.
- **The local installation can serve one small model when asked, so a laptop and the suites have
  an installation model.** The local deploy takes a flag that adds the component with one small
  tool-capable model; without the flag a local platform has no model server, so a developer's
  laptop pays no disk, memory or fetch it did not ask for. The k3s suites set the flag, deploy a
  process-hosted service naming the model and prove the plumbing: a turn answered, a tool called,
  usage recorded, the process refused at the server's door. The suite asserts what the platform
  does, never how clever the model is.
- **Observed as the broker is.** The installation's status names the model server, the models it
  serves, whether each is loaded and the digest each was fetched at; the keeper sends the server's
  metrics to the installation's telemetry store through the collector already there; a model call
  to the server is a span like any other.

What this feature is not: a GPU node pool in any installation repository (named as the prerequisite
for anything larger than a small model); more than one model server per cluster; a model per project
or per service; a developer uploading a model or a fine-tune through the platform; the control plane
pulling models (the component's declaration does); judgments on an installation model (Jev stays the
only judgment provider); embeddings; serving an installation model to callers outside the
installation; a change to the agent loop; or Ollama's native API beyond what reports a model's
presence and capabilities.

## Clarifications

### Session 2026-10-17

- Q: Who may decide which models the installation's model server serves? → A: The platform administrator only, through the component's configuration; project self-service is a later feature.
- Q: Should the local installation include the model server and its small model by default? → A: No. It is opt-in, one flag on the local deploy; the cluster suites turn it on. A local platform without the flag has no model server.
- Q: When should a service learn that its installation model lacks a capability its agent needs? → A: At the first call, as the model provider's failure naming the model and the capability; never at the service's start, so a service with one agent that needs the capability and one that does not still starts.
- Q: Keep "model server" and "installation model" (preset "installation") as the words, refusing inference server, Ollama, local model, self-hosted model, in-cluster model and private model? → A: Yes; both settled in the glossary.
- Q: How is a declared model identified when a tag can move to new weights? → A: The administrator declares a tag; the model server fetches it once and the installation's status records the digest it got. A served model is never fetched again by the platform; an administrator re-fetches on purpose.

*Amended in planning (2026-10-17, plan.md):* the door, the declaration loop, the status and the
metrics are one program beside the server, the keeper; the platform's variable is
`ANKKA_MODELS_ADDRESS`, outside the `ANKKA_MODEL_` family; metrics are sent, not scraped; adding the
component rolls every service once; remote clusters (050) are unbuilt, so one cluster is proven.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - An administrator gives the installation a model server (Priority: P1)

An administrator adds the model server component to the installation's overlay with a list of two
models, applies it, and reads the installation's status: the server is there, both models are
served. A developer in any project deploys a Python service whose descriptor names one of
them under the preset `installation`; its agent answers, with a tool call, from inside the cluster,
and no token left the installation.

**Why this priority**: This is the feature: an installation that serves its own models, and a
service that uses one with nothing new to learn beyond a preset's name.

**Independent Test**: In the k3s suite, install the component with the local installation's small
model, apply a process-hosted service naming it, send one message that makes the agent call a tool,
and assert the reply arrived, the tool ran, the session's usage names the provider and is non-zero,
and the installation's status lists the model as served. Assert that no connection left
the cluster for the call, by the server's own request count or by the network policy's denial of
egress to the sidecar during the call.

**Acceptance Scenarios**:

- added `features/installation-models/serving.feature`: an installation with the model server serves the models its administrator declared
- added `features/installation-models/serving.feature`: a service names an installation model with a preset and a model and no key
- added `features/installation-models/serving.feature`: an agent on an installation model answers with a tool call and nothing leaves the installation
- added `features/installation-models/serving.feature`: the installation's status names the model server and the models it serves
- added `features/installation-models/lifecycle.feature`: a served model is fetched once and its digest recorded
- added `features/installation-models/serving.feature`: an installation without the model server is unchanged

---

### User Story 2 - Only the platform's program can reach the model server (Priority: P1)

A developer's Python program, running beside the sidecar, tries to call the model server directly
at the address the platform uses. It is refused: it holds no service certificate. The same call from
the platform's container is answered. A member reading the session afterwards sees every call the
agent made, because there was no other way to make one.

**Why this priority**: An installation model has no key. If the developer's program could reach it,
guardrails, approvals and accounting would be optional for the first time, and the platform's
promise that a process never talks to a model would be broken by the platform itself.

**Independent Test**: In the k3s suite, from the process container of a deployed service, open a
connection to the model server's address and assert it is refused at the TLS handshake; from the
platform's container of the same pod, assert a call is answered. Assert that a workload with no
service certificate at all (a plain pod in a project namespace) is refused the same way.

**Acceptance Scenarios**:

- added `features/installation-models/access.feature`: a developer's program is refused by the model server
- added `features/installation-models/access.feature`: the platform's program of the same service is answered by the model server
- added `features/installation-models/access.feature`: a workload with no service certificate is refused by the model server
- added `features/installation-models/access.feature`: every call to an installation model is a turn or an iteration in a session

---

### User Story 3 - A developer learns what is served and is told when it is not (Priority: P1)

A developer runs `ankka installation status` and reads the model server's models. They deploy a
service naming one that is not on the list; the service fails to start with a detail naming the
served models, which `ankka services get` shows. They correct the name and the service starts. On a
remote cluster with no model server, the same descriptor is refused naming that the cluster serves
no models.

**Why this priority**: An installation model is chosen by a string, and the string must be
discoverable and its mistakes said plainly, or the feature is a support ticket.

**Independent Test**: Against the k3s installation, assert the CLI's status output lists the served
models; apply a descriptor naming an unserved model and assert `services get` reports `Failed` with
a detail naming the served models; apply the corrected descriptor and assert `Ready`. In the operator
suite, render a service naming an installation model on a cluster whose settings name no server, and
assert the refusal.

**Acceptance Scenarios**:

- added `features/installation-models/naming.feature`: a service naming a model the installation does not serve fails to start naming the served models
- added `features/installation-models/naming.feature`: a service naming an installation model on a cluster without a model server fails to start saying so
- added `features/installation-models/naming.feature`: a service naming a declared model not yet fetched waits for it and starts when it is served
- added `features/installation-models/naming.feature`: a descriptor that gives an address or a key beside the preset installation is refused at the service's start
- added `features/installation-models/naming.feature`: a service run outside an installation that names the preset installation is told there is no model server

---

### User Story 4 - Models are fetched, loaded and kept loaded by the installation (Priority: P2)

An administrator adds a third model to the list. The server pulls it into its volume and loads it;
the status shows it fetching and then served; no service restarted. A service deployed while the fetch
is under way waits for it and starts when it is served. Days later the first call to each model is
answered within the model timeout, because the models were never unloaded.

**Why this priority**: A model that must be loaded from disk on the first call after an idle hour
fails the loop's timeout on a CPU, and a model that must be fetched by hand is not provisioned.

**Independent Test**: In the k3s suite, change the component's declaration to add a model, assert
the status moves through fetching to served without any service's pod restarting, and assert a call
after the server's idle period is answered as fast as the one before it. Test the smallest models
available so the suite's time is the fetch's, not the model's.

**Acceptance Scenarios**:

- added `features/installation-models/lifecycle.feature`: a model added to the declaration is fetched and served without restarting services
- added `features/installation-models/lifecycle.feature`: a served model stays served while no call comes
- added `features/installation-models/lifecycle.feature`: a model that cannot be fetched is reported in the installation's status and the others are served
- added `features/installation-models/lifecycle.feature`: a model removed from the declaration is no longer served and a service naming it fails to start naming the served models
- added `features/installation-models/lifecycle.feature`: the model server restarts and serves its models again without fetching them
- added `features/installation-models/lifecycle.feature`: a call in flight when the model server restarts is the model provider's failure

---

### User Story 5 - A model's capabilities are said at the first call (Priority: P2)

A developer gives an agent tools and names an installation model that cannot call tools. The first
message is answered with the provider's failure naming the model and tool calling, as `Unavailable`
to a caller and a failed iteration to an autonomous agent; the service itself is unaffected. The
developer picks a model the documentation names and the agent works.

**Why this priority**: Open-weights models differ in what they can do, and a silent failure here
looks like a bad prompt. It is P2 because the documentation's model list prevents most of it.

**Independent Test**: With the stand-in from feature 052 reporting a model without tool calling, run
an agent with tools through the `installation` preset and assert the failure's message; with the
real local installation's model, assert the capability check passes and the tool call runs.

**Acceptance Scenarios**:

- added `features/installation-models/capabilities.feature`: an agent with tools on a model that cannot call tools is told so at the first call
- added `features/installation-models/capabilities.feature`: effort on a model that cannot think is told so at the first call
- added `features/installation-models/capabilities.feature`: a capability failure is the model provider's failure and the service is unaffected
- added `features/installation-models/capabilities.feature`: an agent on a model with the capabilities it needs is answered

---

### User Story 6 - The installation sizes and places the server (Priority: P3)

An administrator of a cloud installation with an accelerator node pool sets the component's node
selection, tolerations and accelerator count in the overlay, raises how many calls run at once, and
declares a larger model. The server lands on the accelerator nodes and the model answers at the
speed the hardware gives. A second administrator on a CPU-only installation leaves the defaults and
serves a small model; a developer who asks their local platform for the model server gets the same
small model. Calls beyond the server's concurrency wait in its queue and are answered or
time out as the model timeout says.

**Why this priority**: Without the knobs the feature is a toy; without a GPU node pool in an
installation repository the knobs cannot be proven. The feature ships the knobs, documents them, and
proves the CPU default; the GPU proof is an administrator's checklist until a pool exists.

**Independent Test**: In the operator and component suites, render the component with each knob set
and assert the pod spec carries it; in the k3s suite, set concurrency to one and send two calls at
once, asserting both are answered in order and neither fails before the model timeout. On GKE, an
administrator's checklist in the install guide, run by hand when the pool exists.

**Acceptance Scenarios**:

- added `features/installation-models/placement.feature`: the model server is placed and sized by the installation's own settings
- added `features/installation-models/placement.feature`: a call beyond what the model server runs at once waits and is answered
- added `features/installation-models/placement.feature`: a local platform asked for the model server serves one small model on a CPU
- added `features/installation-models/placement.feature`: a local platform not asked for the model server has none
- added `features/installation-models/placement.feature`: a call that waits past the model timeout is timed out as any model call is

---

### User Story 7 - Operating and documenting the model server (Priority: P3)

An operator reads the model server's request rate, calls in flight and waiting, and loaded models in the telemetry
store beside the broker's and the databases' metrics, and traces a slow agent turn to the model
server's span. A developer reads one platform page that says what an installation model is, how to
name one, what the platform refuses and why, which models are known to work, and that nothing of a
call leaves the installation. The install guides say how to add the component locally, on GKE and
on any cloud; the limitations page says what the model server does not do.

**Why this priority**: The feature is complete when it is as operable and as documented as the
broker.

**Independent Test**: `just docs` passes with the new page and every new variable in prose beside
its generated table; the telemetry suite asserts the keeper's metrics reach the store when the component
is present; a traced turn's spans include the model server's call.

**Acceptance Scenarios**:

- added `features/installation-models/observing.feature`: the model server's metrics reach the installation's telemetry store
- added `features/installation-models/observing.feature`: a call to an installation model is a span in the turn's trace
- added `features/documentation/installation-models.feature`: the documentation describes installation models and how a service names one
- added `features/documentation/installation-models.feature`: the install guides describe adding the model server locally and on a cloud
- added `features/documentation/installation-models.feature`: the documentation's limitations say what the model server does not do

---

### Edge Cases

- **The model server is down.** A call is the provider's failure, `Unavailable` to a caller and a
  failed iteration to an autonomous agent, as a vendor outage is; the service's other components are
  unaffected; the installation's status says the server is not ready.
- **The server restarts.** Its models are on its volume, so it pulls nothing; it reloads the declared
  models before reporting ready, and calls in flight at the restart fail as the provider's failure.
- **A declared model name that does not exist in the registry, or an installation with no egress to
  the registry.** The fetch fails; the status names the model and the reason; the other declared models
  are served; a service naming the failed model waits as for an unfetched one, and the limitation says
  an installation without egress supplies models through its overlay.
- **A model is removed from the declaration while a service uses it.** The model is unloaded and
  removed from the volume; the service's next call is the provider's failure naming the model; a
  service deployed afterwards naming it fails to start naming the served models. The documentation
  says to move services first.
- **Two services, two projects, one model.** Served by one server; the server's queue serialises what
  its concurrency cannot run at once; each service's usage is its own; nothing of one project's
  conversation is visible to another's, because the server holds no conversation.
- **A descriptor gives an address or a key beside the `installation` preset.** Refused at the
  service's start: an installation model has neither, and a value here is a mistake, not an override.
- **The preset on a developer's laptop with no installation.** A service run locally with
  `ANKKA_MODEL_<NAME>_PRESET=installation` is told at start that there is no installation model
  server, and to point a provider at a local server instead, naming the documentation page.
- **A remote cluster that serves a different list** from the home cluster's. A service is answered by
  its own cluster's server and refused by its own cluster's list; the installation's status lists
  models per cluster.
- **Thinking models.** `effort` is sent as the server's reasoning effort; thinking tokens are counted
  in the output the server reports; `thinking` off sends the lowest effort the model offers.
- **Concurrency set higher than the hardware bears.** The platform refuses nothing; the model timeout
  bounds every call; the metrics show the queue. The documentation says how to size it.
- **A very large model on a CPU installation.** Loads, slowly, once; answers, slowly; the model
  timeout decides whether slowly is too slow. The documentation names what is reasonable on a CPU.
- **An installation upgraded to this release with no component added.** Nothing changes: no server,
  no status line, no new variable on any workload.

## Requirements *(mandatory)*

### Functional Requirements

**The component**

- **FR-001**: A model server component MUST exist under `kustomization/components/`, running Ollama
  and the keeper in a namespace of its own outside the project pattern (`models-system`), with a
  volume for its models, a network policy, a patch giving the operator its settings (the server's
  address, its status address and the namespace, all or none) and a patch giving the control plane
  the server's address. An installation without the component MUST render exactly what it rendered
  before.
- **FR-002**: The component's configuration, set by the platform administrator and by no one else, MUST declare the models to serve by name. The server MUST
  fetch each declared model into its volume at start, load it, and keep it loaded; a model added to
  the declaration MUST be fetched and served without restarting any service; a model removed MUST
  stop being served. A declared model is identified by a tag; it MUST be fetched once, the digest
  fetched MUST be recorded, and a served model MUST NOT be fetched again except when an
  administrator asks for it.
- **FR-003**: The component MUST carry, as values an overlay sets: node selection and tolerations,
  accelerator count, CPU and memory, how many calls run at once, and the volume's size and class;
  the defaults MUST run the small model the local deploy's flag serves, on a CPU.
- **FR-004**: The local deploy (`just deploy-local`) MUST offer one flag that adds the component
  serving one small tool-capable model, and MUST leave it out without the flag; the k3s suites MUST
  install the component. The documentation of running locally MUST name the flag where it describes agents.

**Access**

- **FR-005**: The model server MUST accept a call only from a holder of a service certificate of the
  installation, by mutual TLS from the installation's authority. A connection without one MUST be
  refused at the handshake. The platform's container of a service MUST be able to reach it and the
  developer's program MUST NOT.
- **FR-006**: Every call to an installation model MUST be made by the loop, so that it is a turn or
  an iteration in a session, runs guardrails and approvals, and is counted as model usage under the
  provider's name.

**Naming a model**

- **FR-007**: Feature 052's presets MUST gain `installation`, taking a model name and nothing else:
  the platform MUST supply the server's address to the platform's container of a service that names
  it, as it supplies the broker's, and MUST refuse at the service's start a descriptor that gives an
  address or a key beside it.
- **FR-008**: A Scala service MUST be able to build a provider on an installation model from the
  model's name alone, reading the address the platform supplies.
- **FR-009**: A service naming a model the cluster's server does not serve, or naming an installation
  model on a cluster with no server, MUST fail to start with a detail naming the served models (or
  that none are), reported by `ankka services get` as other start refusals are. A service naming a
  declared model not yet served MUST wait, reporting `UpdateInProgress` with why, and start when it
  is served.
- **FR-010**: A service run outside an installation that names the preset MUST be told at start that
  no installation model server exists and where the documentation describes a local server.

**Speaking to the server**

- **FR-011**: The sidecar MUST speak to the model server in the OpenAI protocol's Chat Completions
  shape through feature 052's provider; `effort` MUST be sent as the server's reasoning effort,
  `temperature` MUST be sent, usage MUST be recorded as reported with no cache columns.
- **FR-012**: A call that needs a capability the model lacks (tool calling with tools declared,
  thinking with `effort` set) MUST fail as the provider's failure naming the model and the
  capability, read from what the server reports of the model, and MUST NOT fail the service.

**Status and observation**

- **FR-013**: The installation's status, through the control plane and the CLI, MUST name the model
  server per cluster, each declared model and whether it is fetching, served or failed with why, and
  the digest of each served model.
- **FR-014**: The server's metrics MUST reach the installation's telemetry store when the
  component is present, sent by the keeper as every platform program sends its own, and a call to
  the server MUST be a span in the turn's trace.

**Documentation**

- **FR-015**: A platform page MUST describe installation models beside the broker's and the object
  store's pages: what is served, how a service names one, what is refused and why, which models are
  known to work on a CPU and on a GPU, and that nothing of a call leaves the installation. The
  install guides MUST describe adding the component locally, on GKE and on any cloud, with the GPU
  node pool named as the installation repository's. The limitations page MUST say: one server per
  cluster, models chosen by the administrator, no uploads, no judgments, no embeddings, no serving
  outside the installation, and that an installation without egress supplies models itself.

### Key Entities

- **Model server**: the one server of a cluster that serves installation models, a component the
  installation runs; reached only by holders of a service certificate.
- **Installation model**: a model the installation's model server serves, declared by name by the
  administrator; what a service names through the `installation` preset.
- **Declaration**: the list of models the component's configuration names; the source of what is
  fetched, loaded and served.
- **Served**: of a declared model, fetched into the server's volume and loaded; the only state in which
  a service naming it starts.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A process-hosted service naming an installation model answers a message with a tool
  call in the k3s suite, run with the local deploy's flag set, and the server's request count rises by the turn's calls while no connection
  leaves the cluster for them.
- **SC-002**: A connection to the model server from a developer's program or from a pod without a
  service certificate is refused at the handshake in the k3s suite, every time.
- **SC-003**: A descriptor naming an unserved model reports `Failed` with a detail naming the served
  models within one resync interval of the operator, and the corrected descriptor reports `Ready`.
- **SC-004**: A model added to the declaration is served without any service pod restarting, and a
  model is still loaded after the server's default idle period and answers a call then within
  twice the time of a call before it.
- **SC-005**: An installation without the component renders, byte for byte, what it rendered before
  this feature, in the golden suite.
- **SC-006**: `just docs` passes with the new page and every new variable in prose; the component's
  every knob is documented with its default.
- **SC-007**: Every suite that passed before this feature passes after it.

## Assumptions

- Feature 052 has landed: providers are named, built from a protocol, an address, a model and an
  optional key, with presets, and the sidecar speaks Chat Completions to a compatible endpoint. The
  `installation` preset is one more preset, whose address the platform supplies rather than the
  descriptor.
- Ollama is the server: it serves the OpenAI protocol's Chat Completions shape with tools and
  reasoning effort, reports a model's capabilities and presence through its own API, keeps a model
  loaded when told to, pulls by name from its registry, and is licensed under the MIT licence, so it
  adds no BSL constraint. Its image is published for both architectures the platform builds for
  (feature 049). The plan confirms each.
- The platform's container holds the service certificate and the developer's program does not, so
  mutual TLS at the server's door is what keeps the process out. The plan verifies this for the
  sidecar and the proxy and chooses what terminates TLS in front of the server.
- The small model the local installation serves is one the plan chooses for tool calling on a CPU
  within the suites' memory and time; the k3s suites assert plumbing, so a slow or dull answer is not
  a failure.
- A cloud installation's GPU node pool is the installation repository's to add; no public repository
  of the family has one today, and this feature proves the GPU path by a documented checklist until
  one exists.
- Remote clusters (feature 050) place a service on one cluster; the model server is that cluster's,
  and the status is per cluster.

## Dependencies

- 052-model-providers: presets, the Chat Completions provider, named providers and their usage.
- 027 (broker) and 034 (object storage): the component pattern, the operator's settings patch, the
  golden rendering, the status shapes `UpdateInProgress` with why and `Failed` with a detail.
- Zero trust (`RotatingTls`, the service authority): the certificate the server's door checks.
- 050-remote-clusters: a server per cluster, status per cluster. As of planning, 050 is a spec with
  no code, so this feature is designed per cluster (the status is a list of clusters) and proven on
  the home cluster alone; the scenario of a cluster without a model server waits on 050.
- 049-multi-architecture-images: the server's image on both architectures the local installation
  may run on.
- The telemetry collector and store: where the server's metrics go.

## Open Questions

- Whether a second server per cluster, for a GPU pool beside a CPU pool, is wanted soon enough to
  shape the settings now. This feature says one, named by the preset alone.
