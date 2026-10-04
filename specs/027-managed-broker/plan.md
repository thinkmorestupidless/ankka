# Implementation Plan: Managed Broker — Topics Provisioned, Secured and Injected the Way Databases Are

**Branch**: `027-managed-broker` | **Date**: 2026-10-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/027-managed-broker/spec.md`

## Summary

The platform gains a broker the way it has a database. An installation runs one Kafka, under
Strimzi, as a kustomization component. A descriptor declares the topics its service publishes to;
the operator renders each as a `KafkaTopic` named for the project, and renders every service that
has a runtime a `KafkaUser` whose permissions end at its project's topics. The service is told
where the broker is and proves which service it is with the certificate it already holds. A
project's topics are closed to every other project by the broker itself, nothing is created on use,
and nothing the platform made is ever removed. A descriptor that names a broker of its own gets
none of it, exactly as one that supplies a database.

Technically: `ServiceSpec` gains `topics`, projected onto the resource with a `provisionBroker`
flag (data model). The operator gets a second pure decision, `BrokerProvisioning.decide`, in the
shape of `Provisioning.decide` (R8), two typed Strimzi resources and two actions with no removal
(R6), and three settings that say whether the installation has a broker at all (R7). The runtime's
Kafka clients gain TLS over the `RotatingTls` the service already has (R10), a topic prefix applied
only where a topic is handed to Kafka (R11), and a producer made on first use (R13). The broker
component is Strimzi 1.2.0 with one listener that serves a cert-manager certificate and trusts
ankka's service authority by its certificate alone (R3).

A spike in a throwaway k3s node measured Strimzi and proved the access rules before any of this
was designed (R1, R3, R4, R5). Planning found four things the spec did not have:

- **The service certificate has no subject, and Kafka knows a TLS client by nothing else.** A
  certificate as ankka issues them today authenticates as nobody (measured). The certificate gains a
  common name, `<project>.<service>`, when the installation has a broker; the spec's "the
  certificate the service already holds" then stands (R2).
- **Installing the broker rolls every service once**, because each is told where the broker is,
  and reissues each service certificate once. Neither refuses a request; the upgrade page says so
  (R9).
- **The broker's own certificate must be long-lived.** Strimzi replaces the broker pod when its
  listener certificate changes (measured: within 12 seconds), so the 24-hour lifetime every other
  workload has would restart the broker daily (R3).
- **The broker costs about 1.1 GiB of memory** across four JVMs (measured), which matters most on a
  laptop's local platform. The component bounds each (R1, R19).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`runtime`, `controlplane-api`, `controlplane`, `crd`,
`operator`, `cli`, `sidecar` tests); TypeScript (`console/package`, one schema field and one page);
YAML (the CRD schema, the broker component, both overlays); bash (`deploy-local.sh`).

**Primary Dependencies**: none added to any module. The runtime's Kafka client is the one
`pekko-connectors-kafka` already brings; the operator writes Strimzi's resources through fabric8,
which it already has. In the cluster: Strimzi 1.2.0 (operator image 213 MB, Kafka 4.3.1 image
395 MB), pinned by version in the component.

**Storage**: none of ankka's. The broker's topics, on a persistent claim the component declares.
`AnkkaServiceSpec` gains `topics` and `provisionBroker`, and its status `broker`, each declared in
the CRD's schema. The control plane's `ServiceObserved` event gains two defaulted fields.

**Testing**: munit throughout. Offline: `controlplane-api` (descriptor rules), `operator`
(decision, rendering, typed resources, schema, the two pinned-rendering suites), `controlplane`
(`descriptor.feature` through `GherkinSuite` against the fast harness, projection, event
compatibility, both overlays), `testkit`, where the Kafka container suites already live (connection
settings, prefix, lazy producer; a TLS spike against a broker in a container, behind
`-Dankka.spikes`), `cli` (output), the console's fixtures
test. On k3s: one new case in `SidecarClusterSuite` with a plain broker (User Story 1), and one new
suite, `BrokerClusterFeatures`, that installs the component and runs `features/broker/`.

**Target Platform**: Kubernetes ≥ 1.32 with cert-manager, as today, plus Strimzi's operator in an
installation that enables the component. A developer's machine is unchanged: the bundled compose
stack has no broker, and a service run locally names one as it does today.

**Project Type**: a platform — libraries, an operator, a control plane, a CLI and manifests in one
repository.

**Performance Goals**: none new for a request. A suite that installs the broker pays about 90
seconds for it once (R1). A topic and a user are ready about a second after they are applied.

**Constraints**:

- A service of an installation with no broker, declaring no topic, renders byte for byte what it
  did before; no pinned fixture is rewritten for it.
- The operator keeps its dependency on `crd` alone; `PlatformVariables` is unchanged, since every
  new variable already falls under its shared prefix.
- No action removes a topic or a user, and the operator's grant has no `delete`.
- No key leaves cert-manager's Secrets: the broker is given the authority's certificate, never its
  key, and ankka's operator reads no Secret of Strimzi's.
- The CLI's native image gains nothing it must carry.

**Scale/Scope**: about 30 scenarios in six features; ten requirements and FR-011. Roughly: one
component and two overlay changes; eight operator files new or changed; four in the control plane
and its API; three in the runtime; the CLI's output; the console's schema and one page; six pages
of documentation.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so it sets no gate. The repository's
own rules (`CLAUDE.md`) are the ones that bind, and the design is checked against them:

| Rule | How the design meets it |
|---|---|
| Effects and actions are inert data | `BrokerProvisioning.decide` is pure; `EnsureKafkaTopic` and `EnsureKafkaUser` are descriptions `Fabric8Executor` alone performs. |
| The operator depends on `crd` only | No new dependency; Strimzi's resources are typed classes of the operator's own, as CNPG's are. |
| A field on the spec is not on the resource until the schema declares it | `ankkaservice.yaml` declares all three; `CrdSchemaSuite` fails otherwise. |
| No secret value in the control plane's journal | The feature has no secret: the credential is a certificate cert-manager holds. |
| Could this check pass while the thing is false? | Isolation is proved by the broker's own refusal with a real certificate, not by reading the rendered rules; "nothing created on use" by listing the broker's topics after a publish; each rendering row by asserting the object, not a string's presence. |
| Tests that start from an empty cluster cannot see upgrade bugs | The suite installs a service before the operator is given a broker, then gives it one, and asserts the roll and the reissued certificate (R9). |
| An overlay that only works from the deploy script is not an overlay | The component is whole under `kubectl apply -k`; the script only orders the CRD-bearing part first, as it does for CNPG. |

Re-checked after Phase 1: no violation, nothing to justify.

## Project Structure

### Documentation (this feature)

```text
specs/027-managed-broker/
├── plan.md              # this file
├── research.md          # R1–R19, with the spike's measurements
├── data-model.md        # fields and types, layer by layer; the phase's states
├── quickstart.md        # how to run each story's proof
├── contracts/
│   ├── descriptor.md    # topics, every refusal, what a member reads
│   ├── operator.md      # settings, what is rendered for whom, the grant, the runtime's variables
│   └── installation.md  # the broker component and both overlays
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
features/broker/                         # the six living features (written)
GLOSSARY.md                              # broker, partition, declared topic, broker variable (written)

controlplane-api/src/main/scala/.../api/descriptors.scala
                                         # TopicDeclaration, ServiceSpec.topics and its rules,
                                         # suppliesBroker; ServiceStatus.broker and .topics
controlplane/src/main/scala/.../controlplane/
├── deploy/ServiceProjection.scala       # topics and provisionBroker onto the resource
├── deploy/StatusIngest.scala            # the broker's phase and topics from the status
├── domain/{events,model}.scala          # ServiceObserved and Service gain both; the phrases
├── application/ServiceRows.scala        # the row carries declared topics
└── api/ServiceEndpoint.scala            # the two checks that need the project's other services

crd/src/main/scala/.../crd/AnkkaService.scala      # TopicEntry, provisionBroker, BrokerStatus
kustomization/components/crd/ankkaservice.yaml     # the schema for all three

operator/src/main/scala/.../operator/
├── Settings.scala                       # BrokerSettings, all three or none
├── BrokerNames.scala                    # user, topic, prefixes: one place
├── BrokerProvisioning.scala             # BrokerPlan, BrokerObservation, decide
├── strimzi/{KafkaTopicResource,KafkaUserResource,StrimziDefinitions}.scala
├── StrimziRendering.scala               # the user with its two rules; a topic
├── Action.scala, Executor.scala         # EnsureKafkaTopic, EnsureKafkaUser, observeBroker
├── Rendering.scala                      # brokerActions; the three variables
├── ZeroTrust.scala                      # the common name, with a broker
├── LifecycleRules.scala                 # brokerStatus
└── ServiceReconciler.scala              # observe, decide, report

modules/runtime/src/main/scala/.../runtime/
├── Kafka.scala                          # KafkaConnection; prefix; lazy producer; the log line
├── KafkaTls.scala                       # the engine factory over RotatingTls
└── ProjectionRuntime.scala              # fromEnv reads the three variables

cli/src/main/scala/.../cli/Output.scala            # broker and topics lines
console/package/src/{client/schemas.ts,routes/service.tsx}, fixtures/

kustomization/components/broker/         # namespace, Strimzi (nested), certificate, Kafka,
                                         # node pool, policy, the operator's Role, the patch
kustomization/overlays/{local,cloud}/    # enable it; SET placeholders in cloud
kustomization/deploy-local.sh            # Strimzi's CRDs first, as CNPG's

docs/build/topics.md, docs/reference/{service-descriptor,configuration,limitations}.md,
docs/platform/{networking,install-cloud}.md, docs/deploy/upgrading.md   # FR-010
```

Tests sit beside what they test: `TopicsDescriptorSuite` (`controlplane-api`);
`BrokerProvisioningSuite`, `BrokerRenderingSuite`, `StrimziModelsSuite`, `BrokerStack` and
`PlainKafka` (`operator`); `BrokerDescriptorFeature` and `BrokerClusterFeatures` (`controlplane`);
`KafkaConnectionSuite` and `KafkaTlsSpike` (`testkit`, beside `KafkaSuite`); one case in
`SidecarClusterSuite`.

**Structure Decision**: no new module. The feature is the database's path walked a second time, so
each change lands beside its database counterpart, in the module that already owns that step.

## Order of work

1. **The gate** (R10): the runtime's engine factory against a TLS broker in a container. Everything
   else assumes it.
2. **User Story 1** alone: the plain broker helper and the `SidecarClusterSuite` case. It needs no
   provisioning and can merge by itself.
3. **Descriptor to resource**: `ServiceSpec`, the projection, the CRD, the status wire, the CLI and
   console lines. Offline.
4. **The operator**: settings, names, decision, typed resources, rendering, the common name. Offline,
   with the pinned renderings untouched for the no-broker case.
5. **The runtime**: connection, TLS, prefix, lazy producer, the log line.
6. **The component and overlays**, then `BrokerStack` and `BrokerClusterFeatures` on k3s: topics,
   isolation, what is kept, the upgrade roll.
7. **The local platform and the documentation.**

Feature 024 must be on the branch before step 6's reading scenarios can pass (R17).

## Complexity Tracking

No violation to justify.
