# Quickstart: validating installation models

The runs that show the feature works, cheapest first. Docker is required from step 0. Contracts:
[component](contracts/component.md), [keeper](contracts/keeper.md),
[operator-and-status](contracts/operator-and-status.md), [provider](contracts/provider.md). Data:
[data-model.md](data-model.md).

Every command switches the k3s suites off unless it is one; a k3s run is minutes, and belongs under
`caffeinate -i` on a laptop. Feature 052 must be on the branch first.

## 0. The spikes, once

```bash
sbt -Dankka.spikes=on -Dankka.cluster.tests=off 'modelKeeper/testOnly *OllamaSpike' 'sidecar/testOnly *ModelDoorSpike'
```

Expect: against `ollama/ollama:0.40.6` in a container, a model loaded with `keep_alive: -1` still
loaded after six minutes idle and answering a tool call on a short message; the queue-full status
printed; the OpenAI SDK's client through a `RotatingTls` context completing a handshake with a
mutual-TLS listener that reads the identity, and a client without a certificate failing at the
handshake. S1 to S3 of the research.

## 1. Pure: the plan, the settings, the variable, the document, the rendering

```bash
sbt -Dankka.cluster.tests=off \
    'core/testOnly *PlatformVariablesSuite *ModelServerDocumentSuite' \
    'crd/testOnly *AnkkaServiceCodecSuite' \
    'controlPlaneApi/testOnly *DescriptorSuite *ControlPlaneFixturesSuite' \
    'operator/testOnly *ModelServerSettingsSuite *InstallationModelsSuite *RenderingGoldenSuite *RenderingUnchangedSuite *CrdSchemaSuite *SettingsSuite' \
    'modelKeeper/testOnly *ReconcileSuite'
```

Expect: every row of the plan's table with the exact detail texts; `ANKKA_MODELS_ADDRESS` refused
in a descriptor; the variable on the platform container alone in the golden `installation-model.txt`;
`RenderingUnchangedSuite` green with `git diff --stat operator/src/test/resources/unchanged` empty;
every row of the keeper's reconcile table, including a failed pull's reason and a removal.

To see a check fail once: remove `installationModels` from `ankkaservice.yaml`'s status and run
`CrdSchemaSuite`.

## 2. The keeper against the stand-in

```bash
sbt -Dankka.cluster.tests=off 'modelKeeper/testOnly *OllamaClientSuite *DoorSuite *KeeperSuite'
```

Expect: `FakeOllama` on loopback; a service certificate from `test-pki` admitted and its request
forwarded with the body streamed; the gateway's certificate answered 403; no certificate failing
the handshake; `POST /api/pull` through the door answered 404; a declaration file edited under the
running keeper fetching the new tag, recording its digest and serving it within one pass, and the
removed tag deleted; the status document holding each state.

## 3. The preset on a sidecar, offline

```bash
sbt -Dankka.cluster.tests=off 'sidecar/testOnly *ModelsSuite *InstallationModelClientSuite *ConformanceSuite -- *installation*'
```

Expect: the preset resolved from `ANKKA_MODELS_ADDRESS` with the service's certificate; each start
refusal in the contract's words; a model without `tools` given tools refused at the first call
naming the capability and the model, with nothing sent to the model; a Python, a TypeScript and a
Rust handler naming the preset answered by the stand-in behind a `test-pki` listener.

## 4. The overlays

```bash
sbt -Dankka.cluster.tests=off 'controlPlane/testOnly *RemoteOverlaySuite'
```

Needs `kubectl` on PATH. Expect: `local` renders nothing in `models-system`; `local-models` renders
the component with the two patches each setting their variable once and no second container; `cloud`
renders `models-size.yaml`'s selector, toleration, GPU limit and claim class; the Certificate's
issuer exists and asks for PKCS8.

## 5. The installation's status

```bash
sbt -Dankka.cluster.tests=off 'controlPlane/testOnly *InstallationEndpointSuite' 'cli/testOnly *OutputSuite'
```

Expect: `GET /installation` carrying `models` with one cluster `home` when the reader answers, and
no field when the control plane has no address; the CLI's lines as the contract prints them, and
`model server: none`.

## 6. The whole thing on k3s

```bash
caffeinate -i sbt 'set controlPlane / Test / logBuffered := false' 'controlPlane/testOnly *InstallationModelsClusterFeatures'
```

Expect: the component applied with the node's `kubectl`, `ollama/ollama:0.40.6` and this build's
`ankka-model-keeper` imported, `qwen3:0.6b` fetched and served (minutes); then
`features/installation-models/` scenario by scenario: a Python service's agent answering with a tool
call while the keeper's `calls.answered` rises and an egress-denying policy is in place; `curl` from
the process container failing with exit 35 and from the sidecar container answering 200; a
descriptor naming `mistral:7b` reported `Failed` with the detail naming `qwen3:0.6b` and no pod; a
declaration change adding `llama3.2:1b` served with the same pod names before and after; `/api/ps`
through the door showing the model loaded after six idle minutes. `ranElsewhere` names the
placement, metrics, documentation and the remote-cluster scenario (feature 050).

Or on demand in CI: `gh workflow run cluster --ref 053-installation-models -f suite=InstallationModelsClusterFeatures`.

## 7. By hand, on kind

```bash
kind create cluster --name ankka --config kustomization/kind.yaml   # if not there
just deploy-models                                                   # ANKKA_LOCAL_MODELS=on deploy-local.sh
ankka installation                                                   # model server (home): …, qwen3:0.6b served
```

Deploy the Python template with `ANKKA_MODEL_LOCAL_PRESET=installation` and
`ANKKA_MODEL_LOCAL_MODEL=qwen3:0.6b` in its descriptor and `with_model("local")` on its agent; send
it a message; read the session's model usage under `local` in the local console or `services
logs`. Change the model to `nope:1b`, apply, and read `services get`'s detail. Run
`just deploy` without the flag on a fresh cluster and confirm `ankka installation` says
`model server: none`.

## 8. Documentation

```bash
just docs && just docs-sync && just features
```

Expect: the new page in the platform section; every new variable in prose beside its generated
table; the skill rendered; the features check green with every scenario named.
