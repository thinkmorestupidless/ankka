# Limitations

> What ankka does not do yet, stated plainly and grouped — the platform, networking and security, observability, components, and SDKs and releases — so you can plan around a gap before you reach it.

Source: https://docs.ankka.cloud/reference/limitations/
These are known gaps, not oversights. Each is listed where it applies as well, so the page that describes a
feature also says what that feature does not do.

## Platform

- **One region.** There is no multi-region deployment, replication filtering or routing by origin.
- **No autoscaling.** `minInstances` is a fixed instance count. `maxInstances` and `targetCpuPercent` are
  validated and stored, and nothing acts on them. Scaling is a change to the descriptor.
- **Readiness is membership, not health.** An instance is ready once it has joined its cluster and bound its
  HTTP port. The platform does not call your routes, because it knows neither your routes nor their ACLs,
  and there is no liveness probe.
- **The declared runtime version is trusted.** A descriptor's `runtime` is checked against the platform's
  version before deployment. The platform does not compare it with the version the running image reports at
  `/ankka/version`. There is one compatibility rule — the same major, and a minor equal to the platform's or
  one below — and no finer matrix.
- **One database per service, on one Postgres cluster per project.** Provisioned databases run on a
  single-instance Postgres cluster in the project's namespace. A service that needs a different durability
  profile, or has existing data to migrate, supplies its own database through `ANKKA_DB_*` variables, and its
  isolation is then whatever its owner configured.
- **Deleting a service keeps its database.** Nothing the platform does destroys a database. Removing one is a
  manual task for whoever administers the cluster.
- **Listings can lag.** Organization, project and service listings are read from projections and may miss a
  change made a moment ago. Checks that depend on a count — "the project has no services" before it is
  deleted — use the same projections, so they guard against the obvious mistake rather than every race.

## Networking and security

- **No restriction on where a workload connects to.** Network policies decide who may connect to a
  workload; nothing restricts where it may connect. There is no egress policy.
- **A project is not a network boundary for HTTP.** Any ankka workload can open a connection to any
  service's HTTP port; whether the request is served is the callee's ACL's decision, from the caller's
  certificate. Cluster ports and databases are closed to other projects.
- **The gateway is one caller.** Every request from outside the cluster reads as the gateway, whichever
  hostname it arrived at. Telling users apart is a bearer token the service verifies in
  `Acl.Authenticate`.
- **The platform authenticates its operators, not your service's users.** The control plane verifies
  identity-provider tokens; a deployed service's endpoints are protected only by the ACL their author
  wrote. The platform provisions no identity realm, client or token check for a service's users.
- **Python and TypeScript services cannot call another service as themselves.** They can be called, and
  read the caller; only a Scala service has a service client that presents its certificate.
- **Protection depends on the cluster enforcing network policy.** On a network plugin that accepts
  policies and ignores them, every connection is still mutual TLS and every caller still named, but
  nothing is refused before the handshake. `deploy-local.sh` checks; a cloud cluster must be checked by
  its installer.
- **The installation's root authorities do not rotate.** Workload certificates rotate every eight hours;
  the two roots they are issued from are valid for ten years and replacing one is a manual job.
- **The operator's grant on Secrets is broader than it uses.** It reads one Secret per provisioned
  database by name, and the same grant would let it read any Secret whose name it knows, including an
  issued certificate's. It never does.
- **A supplied database's credential is its owner's.** A service that brings its own database through
  `ANKKA_DB_*` variables can connect with TLS and a client certificate, but the platform issues and
  rotates nothing for it.
- **The control plane's own database still uses a password.** Every service's provisioned database
  authenticates by certificate; the control plane's does not yet, though its connection is private to
  its namespace.
- **Roles are per organization.** A member is an owner or a member of an organization. There are no
  per-project roles and no read-only role.
- **Quotas count projects, services and instances, and nothing else.** An organization's quota does not
  cover memory, CPU or storage, there is no per-project quota, and a quota lowered below what an
  organization holds refuses new things but stops nothing that runs.
- **Nothing at the gateway but routing.** There is no authentication, rate limiting or header policy at the
  gateway. HTTP/1.1 only through it, with no gRPC or HTTP/2 to services, and one gateway per installation.
- **One port, HTTP only.** A service has a single HTTP port and no other protocol.
- **No custom hostnames.** An exposed service's hostname is derived by the platform as
  `<service>-<project>.<base domain>`. A domain of your own is not supported.

## Observability

- **The installation's console shows the control plane's records only.** [The console](../operate/console.md)
  at `console.<base domain>` manages organizations, projects, members, deploy tokens and services, and shows
  a service's status, history and logs. It does not show a deployed service's traces, sessions or entity
  state; [the local console](../operate/local-console.md) shows those for services on your own machine.
- **Traces are a window, not a history.** Each instance records every component invocation into a fixed ring
  of recent spans, 4096 by default, and overwrites the oldest. Nothing is persisted, there is no sampling and
  no query language, and a trace whose older spans are gone is reported as partial.
- **Time the platform cannot attribute is shown, not distributed.** Waiting on a model, on a database, or on
  work a handler handed to another thread appears as unattributed time on a trace. A span whose parent has
  gone stays at the root, marked as having an unknown parent.
- **Tokens, not money.** Agent usage is reported in tokens. There is no price table, so cost is shown as
  unknown, never as zero.
- **`ankka services logs` is not a log store.** It reads what Kubernetes holds for each instance at the moment
  of asking: no search, no aggregation, no retention. A service that has restarted many times has lost all but
  its current and previous containers' output.
- **`ankka services logs` cannot read a service with process hosting.** Its pods have two containers and the
  command does not choose one, which Kubernetes refuses; read them with `kubectl logs -c` as
  [Logs](../operate/logs.md) shows.
- **Metrics are a window too.** The metrics endpoint reports counts over the current span window, not totals
  since start, and the platform ships no dashboards or alerts.

## Components

- **Views read one source into one table.** Multi-table views, rebuilding a view on deploy, and Akka's
  snapshot-handler projection optimisation are not built.
- **Topic sources are at least once and cannot replay.** A view or consumer sourced from a topic sees only
  what was published after it started, must tolerate duplicates, and skips a message with no `ce-subject`.
  Only Kafka is supported; another broker needs its own implementation of the two-method broker interface.
- **Only agents stream.** Entities and workflows refuse a streaming request.
- **A module cannot be interrupted.** A call into a WebAssembly module that runs past the runtime's command
  timeout is abandoned rather than stopped: the caller is answered with a fault and the instance is
  discarded, but the thread running it is not reclaimed until the module returns.
- **A module cannot forward an autonomous agent's notifications.** They are a live stream, and a module's
  routes cannot stream; read a task's record, or await it, instead.
- **A module cannot stream.** A WebAssembly module answers every call whole, so its handlers and HTTP routes
  cannot stream; a module declaring one is refused at start.
- **A deployed module cannot be debugged in place.** There is no debugger attached to a module the runtime
  has loaded; its `log` calls go to the runtime's log, and its unit tests run natively.
- **The module image must copy.** A wasm service's image is run once to copy `service.wasm` into
  `/ankka/module`; an image that does anything else fails the pod's start. The platform does not yet mount
  the image as a volume, which would need a container runtime newer than every cluster it targets.
- **Autonomous agents do not coordinate yet.** There is no delegating a subtask to another agent, handing a
  task on, leading a team over a shared backlog, or moderating a conversation between agents; coordinate
  several from a workflow instead. There are no MCP tools and no per-instance overrides of a definition.
- **An autonomous agent's tools run at least once.** A tool whose result had not been recorded when a task's
  process stopped runs again when the task resumes. Write tools with side effects to tolerate a repeat.
- **An attachment by reference is not fetched.** The model is shown the reference; a tool fetches it.
- **The local console does not show autonomous agents.** Read a task's record, or watch an instance's
  notifications.
- **Output guardrails cannot unsay a stream.** On a streaming agent handler, output guardrails run after the
  tokens have been delivered. They can stop the reply being written to memory, but not un-send it. Use input
  guardrails for anything that must never be shown.

## SDKs and releases

- **The TypeScript SDK declares and calls autonomous agents but cannot script one in its unit testkit.**
  Test one through a sidecar with `ANKKA_MODEL_SCRIPT`, as the Python SDK's integration testkit does.
- **Four languages.** Services are written in Scala, Python, TypeScript or Rust. Another language needs an SDK, or a
  guest library for the WebAssembly mode, that passes the conformance suite; see
  [Adding a language SDK](../contributing/language-sdks.md).
- **The CLI has native builds for macOS and Linux only.** There is no Windows executable, no Linux
  package, and the Linux builds need glibc, so they do not run on musl (Alpine); the release's zip runs
  anywhere with a JDK 21. The macOS executables are not signed by Apple. `ankka init` needs `sbt` on
  `PATH` for a Scala service.
- **The template waits on a release.** `sbt new thinkmorestupidless/ankka.g8` works once a release has
  published the template; until then use `sbt new file:///path/to/ankka/ankka.g8` from a checkout.
- **No local image registry.** A local platform loads images straight into its cluster with
  `kind load docker-image`; it runs no registry of its own. A cluster that pulls images needs one
  elsewhere. A private one works: `ankka projects registry set` puts its credential in the cluster
  for a whole project, and the platform never reads the credential back.
