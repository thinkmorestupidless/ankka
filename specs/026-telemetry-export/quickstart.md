# Quickstart: validating telemetry export

The runs that show the feature works, cheapest first. Docker is required from step 3. Contracts:
[scala-api](contracts/scala-api.md), [export](contracts/export.md),
[operator](contracts/operator.md). Data: [data-model.md](data-model.md). Which suite holds which
scenario: [research.md](research.md), R21.

Every command switches the k3s suites off unless it is one; a k3s run is minutes, and belongs
under `caffeinate -i` on a laptop.

## 1. Pure: the ring, the cursor, the tally, the header

```bash
sbt -Dankka.cluster.tests=off 'runtime/testOnly *RecorderSuite *RecorderCursorSuite *InvocationTotalsSuite *TraceparentSuite *TraceSuite *TraceLoggingSuite' \
    'core/testOnly *PlatformVariablesSuite' \
    'controlPlaneApi/testOnly *DescriptorSuite'
```

Expect: span ids that do not start at 1 and differ between two recorders; a cursor that returns
every span exactly once under concurrent writers, counts what a small ring overwrote, and returns
a span that completed after it was first passed; totals that equal the number of spans completed
whatever the ring's size; the W3C examples parsed and the malformed ones refused; a line written
inside a handler carrying both ids and a line written after it, on the same thread, carrying
neither; eighteen platform-only names; a descriptor giving either telemetry variable refused by
name.

To see one fail: start the span id counter at zero again; the two-recorder case goes red.

## 2. One service: a context in, a call out

```bash
sbt -Dankka.cluster.tests=off 'http/testOnly *HttpTraceContextSuite' 'grpc/testOnly *GrpcTraceContextSuite' \
    'proxyCore/testOnly *ProxyEngineSuite *MountsSuite *CallingAddressEngineSuite'
```

Expect: a request with a `traceparent` recorded under that trace and parent, and one without, or
with a malformed one, recorded as a new root; the same for a gRPC call of each kind of method; a
`traceparent` arriving unchanged through the proxy on its three paths.

## 3. Export, against a fake collector

```bash
sbt -Dankka.cluster.tests=off 'telemetryOtlp/test'
sbt -Dankka.cluster.tests=off 'testkit/testOnly *TopicTraceSuite *KafkaSuite'
sbt -Dankka.cluster.tests=off 'telemetryOtlp/testOnly *TelemetryStoreSuite'      # pulls the store's image, about a gigabyte, once
```

Expect, from the module's suites: one trace id across two services, by HTTP and by gRPC, with the
callee's endpoint span naming the caller's call span as its parent; the resource, the attributes,
a refusal with no error status, an unknown caller with no parent and its mark; a counter of 200
from a ring of 64; nothing asked of the collector but traces and metrics; no thread and no
connection when no address is set; against a closed port, one `WARN`, a wait that grows, every
request answered; on reopening the port, the window's spans and a lost count that is not zero;
a stop that returns within its limit with the collector up and with it down.

From the test kit: a consumer's span in another service under the publishing consumer's trace,
and `traceparent` among a real Kafka record's headers.

From `TelemetryStoreSuite`, against the telemetry store's own image and its agent's own
configuration: a service's trace read back by its id, its counter by its name, and a line from a
pod's log file found under its service and project with the trace id it names.

To see one fail: stop `HttpServiceClients` setting the header; the cross-service case reports two
trace ids.

## 4. The operator and the overlays, offline

```bash
sbt -Dankka.cluster.tests=off 'operator/testOnly *TelemetryRenderingSuite *ProcessHostingRenderingSuite *RenderingUnchangedSuite *RenderingGoldenSuite' \
    'sidecar/testOnly *WasmHostSuite' \
    'controlPlane/testOnly *RemoteOverlaySuite *ProxyEnvironmentSuite *PlatformDeclarationSuite'
```

Expect: both variables on the platform's container and nowhere else, for each hosting; nothing
rendered and nothing changed with no address; the Secret's action holding no value; a module told
that neither variable is set; the local overlay rendering the telemetry store, its route, its
agent and its address on both Deployments exactly once; the cloud overlay rendering no collector,
no telemetry store and an empty address.
`RemoteOverlaySuite` skips without `kubectl` on the `PATH`: read that it ran.

## 5. The cost, on demand

```bash
sbt -Dankka.cluster.tests=off -Dankka.benchmarks=on 'runtime/testOnly *RecorderBenchmark' 'testkit/testOnly *ServiceRecordingCostSuite' 'telemetryOtlp/testOnly *ExporterCostSuite'
```

Expect recording under its stated bound with the tally in place, and under 1% of an invocation
with an exporter attached to a port nothing listens on. These do not run without the switch:
a run that says zero tests ran has checked nothing.

## 6. On k3s

```bash
caffeinate -i sbt 'controlPlane/testOnly *ZeroTrustClusterSuite'
caffeinate -i sbt 'controlPlane/testOnly *ControlPlaneClusterSuite'
caffeinate -i sbt 'sidecar/testOnly *SidecarClusterSuite'
```

Expect: the platform's collector (the `otel-collector` component, not the telemetry store) running
from the component's own manifests; a request through
one sample that calls another read back from the collector's log as one trace with spans named
for both services; a pod in an unlabelled namespace unable to reach the collector; spans named
`platform` / `controlplane`; spans from a Python process's sidecar and from a Rust module's
runtime, each named for its service.

## 7. Documentation

```bash
just docs-sync && just docs
```

Expect the configuration table to list `ankka.telemetry.*`, the prose beside it to name each
key, and the new page in `nav` and in a skill.

## 8. By hand, on the local cluster

```bash
just up
open https://grafana.127.0.0.1.sslip.io:8443            # the telemetry store; trust ~/.ankka/local-ca.crt
ankka services apply -f <descriptor> -p <project>      # the shopping cart, exposed, as docs/platform/install-local.md describes
curl --cacert ~/.ankka/local-ca.crt https://<service>-<project>.127.0.0.1.sslip.io:8443/carts/c1
kubectl -n ankka-<project> logs deploy/<service> | grep trace_id
```

Expect, in the telemetry store: traces from `controlplane` before anything is deployed; a second
or two after the request, its trace, with spans named for the service; the service's
`ankka.invocations` rising; and the service's printed lines, each with a link to its trace. The
trace id on the service's own log line is the one the store shows.

Then make the store unreachable (`kubectl -n ankka-telemetry scale deploy/lgtm --replicas=0`),
make a few requests, and read the service's log: one line saying the collector cannot be reached,
however many requests were made. Scale it back and read one line saying export resumed; the
store is empty of what it held before, which is what a development store does.

## Before a pull request

```bash
sbt scalafmtCheckAll scalafmtSbtCheck
just features
caffeinate -i sbt test
```
