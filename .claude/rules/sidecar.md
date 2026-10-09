---
paths:
  - "sidecar/**"
  - "protocol/**"
  - "sdks/**"
  - "modules/runtime/**/remote/**"
---

# The polyglot sidecar and the Python SDK

## Protocol 1.15 gates grants

Feature 040. A route or endpoint whose ACL admits granted callers, a component reading another project's
topic and a publication to another project's topic each need protocol 1.15; discovery refuses them from an
SDK speaking an earlier minor (`Discovery.beforeGrants`), naming the version, rather than letting a
runtime read the matcher as nobody or the topic as the project's own. The three SDKs carry `project` on a
source and a publication and refuse the same at start.

## Traps

- **A behaviour an older runtime must refuse is a new call, not a new field.** A protobuf field the
  runtime does not know is read as absent, so a period added to `ScheduleRequest` would have been a timer
  that fires once, silently. `ScheduleRecurring` is its own rpc (1.12), which an older runtime answers
  `UNIMPLEMENTED` and each SDK reports as too old.

- **A Python endpoint without its own `__init__` was handed a client and failed its first request**:
  `object.__init__`'s `*args` counted as a parameter. The private endpoint never reached its handler,
  so nothing noticed until the conformance suite's caller cases.
- **A module's fresh instance knows only what the request carries.** A Rust task rule calls the client from
  a fresh instance with no context but the request, and a result check or guardrail check carried no
  metadata, so every call a rule made was counted from the unknown caller. Only the Rust conformance run
  saw it; the Python and TypeScript references passed because their rules call nothing. Anything the runtime
  asks a process to do on a handler's behalf carries the handler's metadata.
- **A workflow's stream carries a command and a step at once, and a query mid-step must not close
  the conversation.** The engine keeps answering commands while a step runs (that is the point of
  steps being asynchronous), so the sidecar tracks one pending command *and* one pending step per
  workflow session; with one slot a `status` query during `reserve` was a protocol violation that
  dropped the session and failed the step over to compensation. The Python server runs steps as
  tasks on a fresh instance for the same reason — a command and a step sharing one instance's
  context slot had the query's `finally` clear the step's context mid-await.
- **A process fault in a remote step is *thrown*, never a `Fail` outcome.** The engine applies the
  declared recovery (retries, failover) only to a step that threw; a `StepOutcome.Fail` ends the
  workflow. `RemoteWorkflowHost` throws on a `Failure`, a timeout and a wrong id, and reserves the
  `Fail` outcome for what the process answered on purpose. Settings the engine enforces (timeouts,
  recovery) are declared in discovery (`WorkflowDetail.Settings`), since the process cannot.
- **A sidecar's `Main.run` must block on `whenTerminated`.** Returning after start exits the JVM,
  and coordinated shutdown has the node leave the cluster it just joined while it is still answering
  HTTP.
- **PID 1 in a container ignores signals from its own namespace**, so `kill 1` inside the app
  container proves nothing; the k3s suite signals the host pid found through `crictl inspect` on
  the node. And a readiness probe at 3×5s cannot observe a container that restarts in two seconds,
  so a test that kills the process asserts on a request retried until it answers, not on `Ready`
  flapping.
- **The encoding's primitives are `text/plain`, not JSON.** A `String`, an `Int`, a `Long` cross the
  wire as their text under manifests `string`, `int`, `long`; only records and sum types are JSON.
  An endpoint returning `str` answers `text/plain`, so a test that calls `.json()` on it fails with
  "Expecting value", and a `str` body is posted raw, not as a JSON string.
- **A directory from `mkdtemp` is mode 0700, and a container reads a bind mount as its own user.**
  The Python testkit mounts the DDL it copied out of the sidecar image into Postgres's
  `docker-entrypoint-initdb.d`, and the image's entrypoint runs `ls` on that directory as `postgres`
  (uid 70) under `set -e` before initdb — so on Linux the container exited before it listened, the
  readiness wait reported only "container is not running", and every CI run of the SDK failed while
  every laptop run passed: Docker Desktop on macOS maps ownership through its file sharing and hides
  the permission. The directory is `chmod 0o755` before it is mounted, and a container that fails to
  start now raises with its own logs attached.
- **`host.docker.internal` needs `--add-host=host.docker.internal:host-gateway` on Linux.** Docker
  Desktop provides it; the Python integration testkit and compose set it unconditionally.
- **On the machine this repository is developed on, every Docker registry client is refused by ghcr.io,
  and the cause is unknown.** Docker Desktop's engine and CLI, OrbStack's engine and CLI, with and without
  credentials, signed in to Docker or not: `denied` for every ghcr.io image, public ones included. `curl`
  and `crane` from the same Mac, and `curl` from inside a container on the same engine, get the same
  manifests anonymously with a 200, and replaying Docker's requests (its User-Agent, its token parameters,
  IPv4 or IPv6 — ghcr.io has no AAAA) with `curl` succeeds too. It is not an organisation's registry
  policy, which was the first guess and was written down here. CI and other machines pull from ghcr.io
  normally. Locally, pull through the Artifact Registry cache
  (`europe-west2-docker.pkg.dev/ankka-ops/ghcr/…`, via `ANKKA_SIDECAR_IMAGE`), or `crane pull` to a tarball
  and `docker load` it. The Python sample's Dockerfile installs with pip from the official `python` image
  rather than `ghcr.io/astral-sh/uv` for the same reason. A separate, stale ghcr.io login in the Docker
  keychain breaks tools that read Docker's config (`crane`); `docker logout ghcr.io` removes it.
- **munit's `--` filter matches the full test name, suite included.** `ANKKA_CONFORMANCE_ONLY='es.*'`
  matched nothing and the whole `ConformanceSuite` reported as *ignored* with zero tests — a green exit for
  a run that did nothing. The glob needs a leading wildcard: `'*es.*'`.
- **A socket field the runtime does not know is a plain GET to it.** A runtime before protocol 1.9 reads
  `Route.socket` as nothing and would serve the route as a request, so a socket route is refused from
  both ends: the sidecar refuses a `Spec` declaring one under an earlier minor — the first minor it gates
  on — and an SDK refuses discovery from a runtime stating an earlier version.
