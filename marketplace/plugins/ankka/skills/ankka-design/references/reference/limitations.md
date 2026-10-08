# Limitations

> What ankka does not do yet, stated plainly and grouped — the platform, networking and security, observability, components, and SDKs and releases — so you can plan around a gap before you reach it.

Source: https://docs.ankka.cloud/reference/limitations/
These are known gaps, not oversights. Each is listed where it applies as well, so the page that describes a
feature also says what that feature does not do.

## Platform

- **One region.** There is no multi-region deployment, replication filtering or routing by origin.
- **No autoscaling.** `minInstances` is a fixed instance count. `maxInstances` and `targetCpuPercent` are
  validated and stored, and nothing acts on them. Scaling is a change to the descriptor.
- **A command in flight while its entity moves can time out.** During a rollout, a scale-in or a failure,
  an entity moves from one instance to another, and the platform delivers a call to it at most once while
  it does: a call that reaches the old instance after the move began is dropped, and nobody answers it. A
  query that goes unanswered is sent again after `ankka.query-resend-after` (2 seconds) and answered,
  within the same `ankka.ask-timeout`. A command is never sent again, because one that went unanswered
  may have run, so its caller is told it timed out after `ankka.ask-timeout` (10 seconds) and cannot tell
  whether it ran. It is rare: about one call in five hundred during a three-instance rollout under steady
  load. A caller that must know makes the command safe to repeat and repeats it.
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
- **One broker per installation, and a project is its boundary.** Every project's topics are on the
  installation's one Kafka, and a service reaches the topics of its own project only. There is no grant
  that lets a service of one project read or publish to another project's topic. A project may declare
  brokers of its own beside the installation's, each named per topic by a component, and those topics are
  the broker owner's: the platform makes nothing on them and checks no contract there. A local platform's
  broker is a single node.
- **The platform removes nothing from the broker.** Deleting a service or a project leaves its topics,
  what was published to them and its user on the broker. Removing them is a manual task for whoever
  administers the installation; see [The installation's broker](../platform/broker.md#what-is-kept).
- **Deleting a service keeps its database.** Nothing the platform does destroys a database. Removing one is a
  manual task for whoever administers the cluster.
- **One object store, of one node.** A bucket is in the installation's own store, a single Garage node with
  one volume, so its durability is that volume's. A cloud provider's buckets are not provisioned; a service
  that needs one supplies its own store through `ANKKA_S3_*` variables. A bucket and its objects are never
  deleted by the platform, and a storage credential is never rotated.
- **No storage client in the SDKs.** A service keeps and reads objects with its own language's S3 client. A
  service run on a developer's own machine is given no bucket.
- **Listings can lag.** Organization, project and service listings are read from projections and may miss a
  change made a moment ago. Checks that depend on a count — "the project has no services" before it is
  deleted — use the same projections, so they guard against the obvious mistake rather than every race.

## Web-hosted services

- **No upgraded connections.** The proxy speaks HTTP/1.1 and passes a request asking to upgrade its
  connection on without the `Upgrade` and `Connection` headers, so a web-hosted program cannot hold a
  socket, and a socket route of a service mounted under one is not reached through the mount. A browser
  opens the socket at the mounted service's own hostname instead, with its token as a subprotocol (see
  [HTTP endpoints](../build/http-endpoints.md#sockets)). A stream of server-sent events does pass.
- **Web hosting keeps no image but the one its descriptor names.** A rollout replaces instances one at a
  time, and no previous build's files are kept anywhere. An interface names its built files by their
  content and serves its index page uncached; see [Deploy a user
  interface](../deploy/web-hosting.md#what-a-browser-sees-during-a-rollout).
- **A request may reach any instance of a web-hosted service.** There is no affinity between a browser
  and an instance, so a session lives in a cookie or a service, never in the process's memory.
- **No traces, metrics or topology for a web-hosted service.** The local console and `/ankka/metrics`
  know only services built on ankka. Its logs are read with `ankka services logs`.
- **A mounted service on an older runtime refuses.** A runtime that predates web hosting refuses a
  request under a mount, so a mounted service must run a runtime that knows mounts.
- **What a service no longer needs is kept until it is deleted.** A mount certificate stays after the last
  mount is removed, and a service that changes hosting keeps what its old hosting had (a cluster
  certificate, a cluster network policy, a peers role, a database), all owned by the service.
- **A descriptor can name a sibling's database credential.** The rule against reading a Secret the platform
  issues does not cover `<service>-db`, a database's credential, because a descriptor that supplies its own
  database may keep a credential under such a name. The platform's own authenticates nothing once a
  service connects to its database by certificate.

## Networking and security

- **No restriction on where a workload connects to.** Network policies decide who may connect to a
  workload; nothing restricts where it may connect. There is no egress policy.
- **The object store speaks plain HTTP inside the cluster.** Every other port a workload reaches is mutual
  TLS; the store's are not. A network policy admits only ankka workloads, the gateway and the operator, and
  a request is signed, so a secret key never crosses the network, but an object's contents cross it
  unencrypted. A request from a browser is encrypted as far as the gateway.
- **A project is not a network boundary for HTTP.** Any ankka workload can open a connection to any
  service's HTTP port; whether the request is served is the callee's ACL's decision, from the caller's
  certificate. Cluster ports and databases are closed to other projects.
- **The gateway is one caller.** Every request from outside the cluster reads as the gateway, whichever
  hostname it arrived at. Telling users apart is a bearer token the service verifies in
  `Acl.Authenticate`.
- **The platform verifies a service's users' tokens and issues none.** A service lists the issuers it
  accepts, and an authenticated route admits only a token one of them signed. No realm, client or user is
  provisioned for a service's users, and the platform administers none: the identity provider is the
  installation's or the service's to run. Only signed JSON Web Tokens whose issuer publishes its keys are
  verified, every listed issuer must name an audience, and a route accepts any of the service's issuers.
- **Only a Scala service calls another service's gRPC endpoint.** A Python or TypeScript service calls
  another service's HTTP routes as itself, through the runtime beside it, and is called over gRPC as any
  service is; it has no gRPC client of its own.
- **Protection depends on the cluster enforcing network policy.** On a network plugin that accepts
  policies and ignores them, every connection is still mutual TLS and every caller still named, but
  nothing is refused before the handshake. `deploy-local.sh` checks; a cloud cluster must be checked by
  its installer.
- **The installation's root authorities do not rotate.** Workload certificates rotate every eight hours;
  the two roots they are issued from are valid for ten years and replacing one is a manual job.
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
  gateway, and one gateway per installation.
- **Two protocols: HTTP and gRPC.** A service has at most an HTTP port and a gRPC port, and serves no other
  protocol. A web page cannot call a gRPC endpoint: gRPC-Web is not routed.
- **A gRPC service's peers address is skipped if its name is taken.** A service that serves gRPC has a
  headless address named `<service>-grpc-peers`. If another service in the project is called that, the
  platform leaves that service's address alone and does not create the peers address, and callers then
  balance by connection rather than by call.
- **No custom hostnames.** An exposed service's hostname is derived by the platform as
  `<service>-<project>.<base domain>`. A domain of your own is not supported.

## Observability

- **A call between services starts a new trace.** A call to another service, over HTTP or gRPC, is the root
  of a trace in the service called; the two traces are not joined.
- **The installation's console shows a deployed service's topology, not its traces.** [The console](../operate/console.md)
  at `console.<base domain>` manages organizations, projects, members, deploy tokens and services, and shows
  a service's status, history, logs and topology. It does not show a deployed service's traces, sessions or
  entity state; [the local console](../operate/local-console.md) shows those for services on your own machine.
- **Observed calls are a window.** A topology's observed calls are the calls made in a recent window, ten
  minutes by default. A call a service can make but did not make in that time is absent, so the topology is
  never a complete list of what calls what. Declared connections, read from what components register, are
  complete.
- **An instance's traces are a window; a history is the collector's.** Each instance records every component
  invocation into a fixed ring of recent spans, 4096 by default, and overwrites the oldest; nothing is
  persisted in the instance and there is no sampling. Where the installation names a collector the spans are
  exported too, but a span the window overwrites before it is read is lost, and only counted. The platform
  installs no store outside a local platform.
- **`tracestate` and baggage are not carried.** A trace's `traceparent` crosses HTTP, gRPC and topics; a
  vendor's `tracestate` stops at the first ankka service, and no baggage is propagated.
- **A collector with a private certificate authority is not supported.** An `https` collector is verified
  against the JVM's trust store, and the exporter presents no client certificate.
- **An event read from a journal starts a trace.** A consumer or view reading an entity's events or state is
  the root of a trace of its own; only a message read from a topic continues its publisher's.
- **Time the platform cannot attribute is shown, not distributed.** Waiting on a model, on a database, or on
  work a handler handed to another thread appears as unattributed time on a trace. A span whose parent has
  gone stays at the root, marked as having an unknown parent.
- **Tokens, not money.** Agent usage is reported in tokens. There is no price table, so cost is shown as
  unknown, never as zero.
- **`ankka services logs` is not a log store, and logs are never exported.** It reads what Kubernetes holds
  for each instance at the moment of asking: no search, no aggregation, no retention. Logs are for the
  installation's own agent to gather from standard output; a line a handler writes names its trace, so a log
  store can join it. A process's own lines, in Python or TypeScript, carry no trace.
- **The metrics endpoint is a window.** `/ankka/metrics` reports counts over the current span window, not
  totals since start; the exported metrics count since the instance started. The platform ships no
  dashboards or alerts.

## Components

- **A view writes one table, and a query reads that table alone.** A keyed view reads several entities
  into one table, but there is no query across two views' tables, and Akka's snapshot-handler
  projection optimisation is not built.
- **A topic and an entity may not be sources of one view.** Only the entity's half could be rebuilt, so
  a view reading both is refused when the service starts. A keyed view reads entities only; a view of a
  topic is a plain view.
- **A keyed view has one writer.** It handles one change at a time, across every source and every
  instance, so its throughput does not grow with the service's instances.
- **Reads other than declared queries have no timeout in the database.** A declared query is ended by the
  database when the service's ask timeout runs out; `get`, `all` and, in Scala, `where`, `ordered` and
  `count` stop the caller waiting and leave the statement to finish.
- **A view's version may be raised only once every instance knows versions of views that read
  entities.** An instance from before cannot be told to stop writing during the rebuild. See
  [Upgrading](../deploy/upgrading.md).
- **A topic source's rebuild is bounded by what the broker retains.** A rebuild of a view reading an
  entity is not: an entity keeps every change it recorded, and the view reads all of them again. Raising a topic-sourced view's
  version empties it and reads its topic again, but a topic's retention is a window, not a record: what
  the broker has dropped is not read, and the rebuilt view holds only what the window still holds. While
  it runs the view serves an empty or partial table. See
  [Rebuilding by version](../build/topics.md#rebuilding-by-version).
- **Topic sources are at least once.** A view or consumer sourced from a topic must tolerate duplicates,
  and a view skips a message with no `ce-subject`. Only Kafka is supported; another broker needs its own
  implementation of the broker interface.
- **A consumer's several messages are not published atomically.** They are published at least once and
  in order; when the broker refuses one, the change is delivered again and all are published again. One
  change's messages may be at most 4 MiB together.
- **The secret store has no rotation, sharing or history.** A service's secret key cannot be changed in
  place: values kept with one key fail to read under another. A service secret belongs to the service that
  kept it, and another service asks for what it needs over HTTP. There are no versions of a value and no
  audit of reads. A project secret reaches a pod as an environment variable, or, when it is the credential
  of a broker the project declares, as files on the platform's container alone.
- **A topic is made only by declaring it, with its partitions and whether it is compacted.** On the
  installation's broker a topic exists because its project declares it; publishing to one nobody
  declared waits. A declaration says how many partitions a topic has and whether the broker keeps only
  the last message under each key, and nothing more: no retention or other topic setting. A
  [graph consumer](../build/graph.md#the-topic)'s topic is declared compacted on its project; on a
  broker a descriptor or a project names, every topic is the broker owner's to create.
- **A graph consumer's rules are the author's.** That an element has one writing entity, and that an
  element is its whole state, are not checked. A graph consumer writes tombstones and no delete markers,
  so a tombstoned element's record stays in its topic; and there is no source that hands a consumer an
  event sourced entity's state, only its events.
- **Expiry tells nobody.** An entity whose state has expired is not deleted: no view's row is removed, no
  consumer's deletion handler runs, and a graph consumer's elements for it are not tombstoned.
- **A topic source has no sequence number.** A change from a topic reads as sequence zero, and a graph
  consumer over a topic must state the version of each element it publishes.
- **A recurring timer's period is a length of time.** A period is a length of time, never a time of day
  or a day of the week: there are no calendars, time zones or cron expressions. Work out the first delay
  to align a timer, and give a period from there. A recurring timer whose due times passed while it could
  not run fires once and goes on, and never catches the missed ones up. The local console does not list
  timers.
- **Only agents stream.** Entities and workflows refuse a streaming request.
- **A socket carries text frames only.** A frame that is not text closes the socket `1003`, not text.
- **The platform keeps nothing of a socket.** No frame is stored and nothing records who holds a socket
  open: presence, fan-out and catching a reconnecting client up are a service's own entities and
  consumers.
- **A client that vanishes is noticed late.** The platform pings a quiet socket and does not wait for the
  answer, so a client whose network went away without closing is noticed only when its connection fails,
  which can take minutes; its handler is then told the socket is closed.
- **A socket is checked once.** Its ACL is decided when it is opened, and a token that expires while it is
  open leaves it open.
- **A module cannot be interrupted.** A call into a WebAssembly module that runs past the runtime's command
  timeout is abandoned rather than stopped: the caller is answered with a fault and the instance is
  discarded, but the thread running it is not reclaimed until the module returns. A module's call to
  another service is not ended before that service answers or `ankka.service-client.timeout` passes, thirty
  seconds unless it is set; a handler whose own deadline is shorter, such as a consumer's, is answered with
  a fault first, and the abandoned call makes no further call to another service. Lower
  `ANKKA_SERVICE_CLIENT_TIMEOUT` in the descriptor to bound the wait.
- **A module cannot forward an autonomous agent's notifications.** They are a live stream, and a module's
  routes cannot stream; read a task's record, or await it, instead.
- **A module cannot stream.** A WebAssembly module answers every call whole, so its handlers and HTTP routes
  cannot stream, and it cannot declare a socket route; a module declaring either is refused at start.
- **A deployed module cannot be debugged in place.** There is no debugger attached to a module the runtime
  has loaded; its `log` calls go to the runtime's log, and its unit tests run natively.
- **The module image must copy.** A wasm service's image is run once to copy `service.wasm` into
  `/ankka/module`; an image that does anything else fails the pod's start. The platform does not yet mount
  the image as a volume, which would need a container runtime newer than every cluster it targets.
- **Autonomous agents do not coordinate yet.** There is no delegating a subtask to another agent, handing a
  task on, leading a team over a shared backlog, or moderating a conversation between agents; coordinate
  several from a workflow instead. There are no per-instance overrides of a definition.
- **An autonomous agent's tools run at least once.** A tool whose result had not been recorded when a task's
  process stopped runs again when the task resumes. Write tools with side effects to tolerate a repeat.
- **An attachment by reference is not fetched.** The model is shown the reference; a tool fetches it.
- **The local console does not show autonomous agents.** Read a task's record, or watch an instance's
  notifications.
- **Judgments are for Scala services' agents only.** Python, TypeScript and Rust services cannot declare a
  question, ask for a judgment or use a judged guardrail. A workflow step, an endpoint or a consumer reaches
  one by calling an agent's judgment handler, and a handler cannot judge and then call the text model in one
  reply; make two calls. Guardrails, judged or not, see a message and a reply, never the tool calls a model
  asks for. No console shows a judgment's answers or probabilities, and a task's and an instance's own usage
  do not include judgment tokens, which are on the task's session. TypeSafe AI's Jev is the only provider
  that ships, and it is in early access.
- **A session that recorded judgment tokens is unreadable by an older runtime.** While a service is being
  rolled onto its first version that uses judgments, an instance still running the previous version cannot
  replay a session in which a judgment's tokens have been recorded, until the roll completes.
- **MCP servers are reached over Streamable HTTP only.** There is no stdio or other transport, so a server
  that runs as a local program is reached through a small HTTP bridge. Only a server's tools are used —
  not its resources or prompts — and a server is listed in code, not discovered.
- **An MCP server's credential is a header from a variable, read at start.** There is no OAuth flow, and a
  credential cannot be given or changed while the service runs; a new one takes a restart.
- **An approval is decided whole.** A person approves or refuses a tool call as the model made it; there
  is no editing its arguments, and a decision is not streamed back to a caller still connected.
- **A module's agents take no part in approvals or MCP servers.** A WebAssembly module declares neither,
  and its client answers a call that would wait for a person as a conflict.
- **Output guardrails cannot unsay a stream.** On a streaming agent handler, output guardrails run after the
  tokens have been delivered. They can stop the reply being written to memory, but not un-send it. Use input
  guardrails for anything that must never be shown.

## SDKs and releases

- **gRPC endpoints are for Scala services.** A Python, TypeScript or Rust service cannot declare one, and a
  descriptor that declares gRPC with process or wasm hosting is refused.
- **The local console does not call gRPC methods.** It lists them beside a service's routes; call them with
  `grpcurl` or a client generated from the service definition.

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
