# Progress — 020 gRPC endpoint

Where the implementation stood on 2026-10-02, when work moved off the machine it was done on (out
of disk; Docker Desktop also stopped there, which is why everything below that needs Docker is
unrun). `tasks.md` is the authority on what is ticked; this file says what the open tasks need.

## Verified

- Every container-free suite of the feature passes: `grpc` (features, validation, limits, statuses,
  admission, flow control, clients, spans, reflection, shutdown, logging), `operator` (rendering, golden
  record), `controlPlaneApi` (descriptor, documentation, fixtures), `crd`, `runtime`, `cli` console.
- With Docker: `features/grpc/` whole — 59 scenarios, no `@ignore` left anywhere under `features/`;
  the sample's `CartGrpcSuite` and `CartGrpcMoreSuite`; `ServiceRegistrationSuite` (testkit).
- Final gates that need no Docker: the BDD checker reports 0 findings with 1 spec read;
  `python3 .github/ci-coverage.py` passes; `sbt compile Test/compile` is warning-free; formatting
  is clean (T069).
- `docs` built clean (78 pages) when last run; the Python and TypeScript SDKs and their conformance
  suites passed against the sidecar (T003). Rust was not run (no cargo on that machine).

## Written, compiled, never run

- `controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/GrpcClusterSuite.scala`
  (k3s) — one test per scenario of `features/grpc-deployed/`, and SC-010's unexpose case. Covers
  T028, T033, T041, T049, T051, T058. Its one run failed in `beforeAll` because Docker Desktop
  stopped under the k3s container, not because of the suite. Expect to fix some cases on the first
  real run; the ones most likely to need adjusting:
  - *a stream on an instance that is stopping…* deletes every cart pod and asserts the stream ends
    `UNAVAILABLE` no sooner than preStop (5s) + shutdown grace (5s) − 1s, through the gateway.
  - *an instance that cannot yet answer a gRPC call is not ready* compares the log line
    `ankka grpc listening` with the pod's Ready transition (whole seconds).
  - *a member is shown why…* removes the namespace's `app.kubernetes.io/managed-by` label and expects
    `route rejected` with `NotAllowedByListeners` in `services get`; it restores the label after.
  - *a service that declares gRPC and serves none…* uses the suite's 170s progress deadline, not the
    60s T028 suggests, because a shorter deadline would mark the other services `Failed` while CNPG
    provisions.
- `modules/grpc/src/test/scala/com/thinkmorestupidless/ankka/grpc/GrpcBenchmark.scala` (T065),
  ignored unless `-Dankka.benchmarks=on`. Record its two printed figures in `research.md`.
- The sample's new route `/callers/grpc/{service}/explained` (used by the cluster suite).

## Next, in order

1. `caffeinate -i sbt 'controlPlane/testOnly *GrpcClusterSuite'` — then tick T028, T033, T041,
   T049, T051, T058.
2. T029/T048, the gateway spike: proposed to close them on the cluster suite's exposed and stream
   cases instead of writing a separate hand-applied spike, since those run against the operator's own
   rendering — **awaiting the user's decision**. If accepted, replace R8's "Not confirmed" in
   `research.md` with what the run observed.
3. `sbt -Dankka.benchmarks=on 'grpc/testOnly *GrpcBenchmark'` (T065).
4. T068: two clauses still have no test (listed under *Gaps* in `tasks.md`): FR-040 and FR-050
   (reflection at the in-cluster gRPC address — needs a gRPC client in a pod).
5. T070 `sbt -Dankka.cluster.tests=off test`, then `caffeinate -i sbt test`.
6. T071 by hand on kind (quickstart step 6), T072 final gates.

## Open questions for the user

- Should the control plane refuse a service name whose `<name>-grpc-peers` clashes with another
  service's name (research R12)? Today the operator's owner guard keeps it safe but silent.
- research R9: the existing HTTP route sets no timeout, so Envoy's 15-second default likely cuts a
  server-sent-event stream through the gateway. Predates this feature; recorded, not fixed.
- The branch's commits carry a `--global` author name because of the machine's git config; offered to
  rewrite them once that is fixed.
