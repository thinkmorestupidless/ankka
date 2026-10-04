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

## Picked up on 2026-10-03 (worktree `../ankka-020-grpc-endpoint`)

- **`GrpcClusterSuite` has run on k3s** (OrbStack). Run by run it found three things, all fixed:
  - `InPod.prober` re-applied a pod holding another service's certificate, which a pod's
    immutable volumes refuse; it now deletes a prober holding a different service's certificate.
  - The route-rejection case removed the namespace's `managed-by` label, which the operator puts
    back on every reconcile, so it passed or failed on timing. It now narrows the gateway's HTTPS
    listener selector instead, and restores it in a `finally`.
  - **A platform bug**: on SIGTERM, Pekko's coordinated shutdown terminated the actor system while
    a gRPC stream was inside the server's shutdown grace, so the caller saw `INTERNAL`, not
    `UNAVAILABLE`. Coordinated shutdown now starts stopping the extensions in its first phase and
    waits for them in its last (`AnkkaService.registerShutdown`); `ShutdownOrderSuite` (testkit)
    fails without that. A first version waited in the first phase, which delayed the shard handoff
    and failed `ExposureClusterSuite`'s rolling restart under load every run (it passed with the
    change disabled); the full `sbt test` on a quiet machine found it.
    The stopping-stream case now uses the echo stream, which depends on nothing but its server.
  - **An operator gap**: a container that refuses to start spends most of each restart booting,
    and in that window its state is `Running`, so the member's detail fell back to the kubelet's
    failed readiness probe and the refusal's own words came and went. A running, not-ready
    container now reports what it said as it last exited (`PodProblem.restarted`); three cases in
    `WasmHostingRenderingSuite`, the first failing without it.
  - `waitReady` attaches the service's pod logs when it gives up, after one run where a service
    never became ready with nothing to say why (that run shared the machine with offline suites).
- **R8 and R12 confirmed** on the cluster; T029 and T048 are closed on the exposed cases, as
  proposed, rather than a separate spike.
- **FR-040 and FR-050 have tests**: `TemplateSuite` case 9 (a gRPC endpoint added to a fresh
  expansion using the docs page's own build blocks) and two reflection scenarios in
  `deployed.feature` run by the cluster suite. `templateArtifacts` now publishes `ankka-grpc`.

## Next, in order

1. ~~A green `GrpcClusterSuite` run~~ — 28 of 28 on 2026-10-03; T028, T033, T041, T049, T051, T058
   ticked. ~~The benchmark (T065)~~ — recorded in `research.md`. ~~T068~~ — no gaps.
2. T070: `sbt -Dankka.cluster.tests=off test` green once the two template fixes landed. The full
   `caffeinate -i sbt test` on a quiet machine (1h36m, 2026-10-04) passed every suite but
   `ExposureClusterSuite`'s rolling restart under load, a regression from the first version of the
   shutdown fix (see above). Reworked; `ExposureClusterSuite` 9/9 and `GrpcClusterSuite` 28/28 then
   passed together, and `ShutdownOrderSuite` and `GrpcShutdownSuite` still pass. An earlier full run
   failed `MultiNodeClusterSuite` and `ControlPlaneClusterSuite` while another session's k3s suites
   ran beside it; both passed in the quiet run. T070 is ticked on that evidence: every suite has
   passed on the final code except those the rework cannot reach, which passed on the run before it.
3. T071 by hand on kind (quickstart step 6). Not done: it replaces the operator in the kind cluster
   on this machine, which was running something else, so it is the user's to do.
4. T072, run on 2026-10-04 after rebasing onto main (a629d23, with features 019 topology and graph
   delta publisher): `@ignore` none; `ci-coverage.py` passes; `RenderingGoldenSuite` passes, its
   record re-pinned to main's rendering at the rebased T002 commit (main now renders the observe
   port) and this branch differing from it by exactly the two owner-checked removal lines per
   service, as before. The BDD checker now reports findings, all in main's own specs (019 graph delta
   publisher, 023–034), identical to its report on main itself; this branch adds none. T072 is
   unticked only for that: its "0 findings" cannot hold until main's specs are brought to features.

## Open questions for the user

- Should the control plane refuse a service name whose `<name>-grpc-peers` clashes with another
  service's name (research R12)? Today the operator's owner guard keeps it safe but silent.
- When **every** instance of a service is replaced at once (a crash, not a rollout), a caller's
  channel keeps the old addresses until grpc-java's DNS resolver looks again, cached up to about 30
  seconds, and its calls fail in that window; seen twice on k3s. A rolling replacement never shows
  it. Is that acceptable, or should a caller re-resolve sooner (a shorter DNS cache, or retrying
  `UNAVAILABLE` once after re-resolution)?
- research R9: the existing HTTP route sets no timeout, so Envoy's 15-second default likely cuts a
  server-sent-event stream through the gateway. Predates this feature; recorded, not fixed.
- The branch's commits carry a `--global` author name because of the machine's git config. Rewriting
  them means a force push of a remote branch, so it waits for the user.
