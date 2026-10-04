# Contract: the operator and the installation

What is rendered for a workload, and what an installation sets. Decisions are in
[research.md](../research.md) (R18, R19, R20).

## The operator's settings

| Setting | Variable in the operator's environment | Absent or empty |
|---|---|---|
| `otlpEndpoint` | `ANKKA_OTLP_ENDPOINT` | nothing is rendered for telemetry |
| `otlpHeaders` | `ANKKA_OTLP_HEADERS` | no Secret is written and no reference rendered |

Headers without an address are ignored, with one `WARN` at the operator's start.

## What is rendered, by hosting

| Hosting | Containers | `ANKKA_OTLP_ENDPOINT` and `ANKKA_OTLP_HEADERS` are on |
|---|---|---|
| embedded | one | that one |
| process | the platform's (index 0) and `<service>-app` | the platform's only |
| module | one, the platform's | that one; the module's `config` answers absent |
| web | the proxy and `<service>-app` | neither |

`ANKKA_OTLP_ENDPOINT` is a literal. `ANKKA_OTLP_HEADERS` is
`valueFrom.secretKeyRef: { name: <service>-telemetry, key: headers }`.

With no address set the rendered objects are byte for byte what they are today.

## The telemetry Secret

`Action.EnsureTelemetrySecret(namespace, name, labels, owner)` describes it and holds no value;
`describe` prints the namespace and name. It is emitted before `ApplyDeployment`, only when the
installation has headers and the hosting is not web. `Fabric8Executor` holds the headers and
applies the Secret with the service's resource as its owner.

The operator's grant is unchanged: `secrets: get, create, patch`.

## The platform's variables

`PlatformVariables.PlatformOnly` gains `ANKKA_OTLP_ENDPOINT` and `ANKKA_OTLP_HEADERS`
(sixteen names become eighteen). From that one change:

| Who | Sees |
|---|---|
| a member applying a descriptor that gives either, as a value or a `secretKeyRef` | a refusal: `env var 'ANKKA_OTLP_ENDPOINT' is set by the platform and cannot be declared` |
| a process | neither variable |
| a module asking its `config` import | absent |

`ServiceSpec.PlatformSecretSuffixes` gains `-telemetry`: a descriptor's `secretKeyRef` naming
`<service>-telemetry` is refused, and so is a project secret of that name.

## The installation

| Object | Where | Content |
|---|---|---|
| ConfigMap `ankka-platform` | `ankka-gateway` | gains `otlpEndpoint` |
| Secret `ankka-telemetry` | `ankka-operator` and `ankka-controlplane` | `headers`; made by the installer, out of band; optional |
| Deployment `ankka-operator` | | `ANKKA_OTLP_ENDPOINT` (replaced from the ConfigMap), `ANKKA_OTLP_HEADERS` (from the Secret, `optional: true`) |
| Deployment `ankka-controlplane` | | the same two; it exports as `platform` / `controlplane` |

| Overlay | `otlpEndpoint` | `otel-collector` | `telemetry-store` |
|---|---|---|---|
| `local` | `http://lgtm.ankka-telemetry.svc.cluster.local:4318` | not listed | listed |
| `cloud` | `""`, marked `SET` | not listed | not listed |

The two components are alternatives: each makes the namespace `ankka-telemetry`, and an overlay
that lists both does not render.

## The `otel-collector` component

| Object | |
|---|---|
| Namespace | `ankka-telemetry` |
| ConfigMap | the collector's configuration: OTLP in on 4318 (HTTP) and 4317 (gRPC); traces and metrics out to `debug`, in detail |
| Deployment | `otel/opentelemetry-collector:0.161.0`, one replica, `imagePullPolicy: IfNotPresent` |
| Service | `otel-collector`, 4318 and 4317 |
| NetworkPolicy | ingress to the collector's pods on those two ports from pods labelled `app.kubernetes.io/managed-by: ankka` in namespaces labelled the same; nothing else |

It keeps nothing. Where an installation's telemetry is stored and shown is the installation's.

## The `telemetry-store` component

For a local platform. Decision: [research.md](../research.md), R23.

| Object | |
|---|---|
| Namespace | `ankka-telemetry`, labelled `app.kubernetes.io/managed-by: ankka` so the gateway's listener admits its route |
| Deployment `lgtm` | `grafana/otel-lgtm:0.35.0`, one replica, `imagePullPolicy: IfNotPresent`; no volume: what it holds is gone when it restarts |
| Service `lgtm` | 4318 and 4317 (OTLP), 3000 (Grafana) |
| HTTPRoute `grafana` | `grafana.<base domain>` on the gateway's `https` listener, to the Service's 3000 |
| NetworkPolicy | to the store's pods: OTLP from pods labelled `managed-by: ankka` in namespaces labelled the same, and from the log agent; 3000 from the gateway's proxy pods in `envoy-gateway-system`; nothing else |
| ConfigMap `log-agent` | the agent's configuration, below |
| DaemonSet `log-agent` | `otel/opentelemetry-collector-contrib:0.161.0`; `/var/log/pods` from the node, read-only; no ServiceAccount grant |

**The log agent** reads every container's log file on its node and sends each line to the store
as a log record:

| Of a line | Becomes |
|---|---|
| the file's path, `/var/log/pods/<namespace>_<pod>_<uid>/<container>/…` | `k8s.namespace.name`, `k8s.pod.name`, `k8s.container.name` |
| the container's name | `service.name` |
| the namespace, less the installation's prefix | `ankka.project`, `service.namespace` |
| ` trace_id=<32 hex> span_id=<16 hex>` at its end | the record's trace id and span id |
| the rest | the record's body, as printed |

A line that names no trace is sent with none. A pod that is not an ankka service is gathered
under its own namespace and container.

**Where a developer looks**: `https://grafana.<base domain>:<https port>`, which for the local
platform as shipped is `https://grafana.127.0.0.1.sslip.io:8443`, with the sign-in the image
ships. Neither the route nor that sign-in is ever rendered by the cloud overlay.

## What changing a setting does to running services

| Change | Effect |
|---|---|
| the address is set, changed or removed | every workload's pod template changes; the operator rolls every service once, by rolling update |
| the headers change | the Secret changes; a service sends the new headers when it next starts |
| the collector is unreachable | nothing: see [export.md](export.md) |
