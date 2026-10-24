# Limitations

> What ankka does not do yet, stated plainly and grouped — the platform, networking and security, observability, components, and SDKs and releases — so you can plan around a gap before you reach it.

Source: https://docs.ankka.cloud/reference/limitations/
These are known gaps, not oversights. Each is listed where it applies as well, so the page that describes a
feature also says what that feature does not do.

## Platform

- **Replication between installations needs `ankka-replication`.** An event sourced entity can be
  [replicated](../concepts/replicated-entities.md): held at several installations of a service and
  written at one, its home. What one installation offers on its own is the whole model over the
  runtime's own replication transport, which reads the other installations' journals from their
  databases directly: installations on one machine, and tests. The replication transport between
  installations on a network is `ankka-replication`, licensed separately. A service naming other
  installations with no transport for them does not start. Nothing deploys a project across
  installations yet: each installation's service is deployed on its own.
- **No forced takeover.** Only the home hands the home over. While a home cannot be reached, its entity's
  writes are refused `NotHome` at every other installation and reads go on from each copy; a partition
  leaves the entity unwritable rather than written in two places.
- **No coordinated creation.** An entity is created where it is first written. Two installations that
  create the same id before either has heard of the other put the entity in conflict at both, refusing
  writes, and ankka offers no command that resolves a conflict: choose replicated ids that one
  installation issues.
- **No concurrent writers.** A replicated entity is written at its home alone. There is no mode in which
  several installations write one entity and their states are merged.
- **Only event sourced entities replicate.** A key value entity or a workflow declared replicated is
  refused when the service starts.
- **A forwarded write is admitted only from an installation the home names.** The home of a replicated
  entity admits a write forwarded from an installation in its own list of installations and refuses any
  other, naming it. Admitting an installation of another organization, by granting it access as any
  other caller is granted, is `ankka-replication`'s, not the platform's.
- **The installation does not report erasure.** `GET /installation` reports its broker, its longest topic
  retention and its backups; it states nothing about erasure, which the platform does not yet provide, so
  a requirement that names erasure reads it as not stated.
- **Only an event sourced entity's context carries a call's attribution.** An event sourced entity's
  command and event contexts say who acted on a call and, for a forwarded write, which installation
  forwarded it. A workflow's or key value entity's context does not yet expose the attribution of a
  call.
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
- **One database per service, on one Postgres cluster per project.** Provisioned databases run on one
  Postgres cluster in the project's namespace, a primary and as many as four replicas the project asks for.
  A service that needs a different durability profile, or has existing data to migrate, supplies its own
  database through `ANKKA_DB_*` variables, and its isolation and backups are then whatever its owner
  configured.
- **A restore does not take back the broker.** Messages published from events a restore lost stay on their
  topics, and every view and consumer on the platform reads a message published again as a new one; its
  `ce-id` is the one it carried before, which protects only a reader that deduplicates by it. A restore
  reports the topics and groups newer than its moment, and changes nothing on the broker.
- **Backups on Garage are not encrypted by the platform.** Garage holds no key the archiver can send, so a
  backup is protected by its bucket's credential and by the installation's volume encryption. Without a
  secondary store, an installation's backups share its cluster's failure domain, and its status says so.
- **The platform removes no restore and no cluster a service left.** Each is listed, with its age or when
  the last service left it, and removed by hand. A rehearsal's database is the one the platform removes,
  when the rehearsal ends or its time to live passes.
- **One broker per installation, and a project is its boundary.** Every project's topics are on the
  installation's one Kafka, and a service reaches the topics of its own project, and another project's
  only where that project granted it the topic. A project may declare
  brokers of its own beside the installation's, each named per topic by a component, and those topics are
  the broker owner's: the platform makes nothing on them and checks no contract there. A local platform's
  broker is a single node, so every topic on it has a single copy.
- **A topic's copies cannot change, and a single-node broker is not grown in place.** A topic's copies and
  minimum in-sync copies are fixed when it is declared, since the broker changes a topic's replication
  only through a rebalancer the platform does not install. A broker of three nodes is a shape a new
  installation is installed with; a running single-node broker's node is its controller quorum too, and
  growing it is the administrator's own procedure, after which every topic made before keeps its copies.
- **The platform removes nothing from the broker.** Deleting a service or a project leaves its topics,
  what was published to them and its user on the broker. Removing them is a manual task for whoever
  administers the installation; see [The installation's broker](../platform/broker.md#what-is-kept).
- **Deleting a service keeps its database.** Nothing the platform does destroys a database. Removing one is a
  manual task for whoever administers the cluster.
- **One object store for new buckets.** A bucket is made in the store the installation names: its own
  Garage, or Google Cloud Storage in its cloud account. A local platform runs Garage as one node with one
  volume, so its durability is that volume's; an installation may run it on three machines, each object on
  all three, and copy every bucket hourly to a secondary store outside the cluster. A bucket stays where it
  was made until a member moves it. A bucket and its objects are never deleted by the platform.
- **Garage keeps one version of an object.** An object overwritten or deleted in Garage is gone at once;
  only Google Cloud Storage keeps noncurrent versions and a soft-delete window.
- **A move from Garage does not move an object larger than 5 GiB**, and moves one service's bucket at a
  time. Traffic to Garage inside the cluster stays plain HTTP while a bucket is there.
- **One cloud provider and one cloud account per installation.** A project names no account of its own. The
  platform deletes nothing in a cloud account, ever: removing what a provider made is the administrator's
  task.
- **An object is kept and read whole.** The storage client has no directories, no rename, no copy, no append
  and no partial writes, and keeps nothing beside an object but its content type. A put declares its
  length, so a body of unknown length is read first or uploaded through a signed URL.
- **A module's objects are at most 4,000,000 bytes.** A Rust service's put and get carry the whole object
  through the runtime; a larger one goes through a signed URL.
- **A keep through a signed URL on a developer's machine is at most 4,000,000 bytes for Python,
  TypeScript and Go**, since the directory store's address is served through the platform's program beside the
  process. A deployed service's signed URLs are the store's, with no such bound.
- **A Python, TypeScript or Go service's routes read their bodies whole.** Only a Scala, Java or Kotlin
  endpoint streams a request or a response; the other languages send large objects through signed URLs.
- **A signed URL spans at most seven days.**
- **A gRPC endpoint has no storage client.**
- **A supplied bucket is checked on its first call**, not when the service starts: a wrong endpoint or key
  is a refused call, not a refused start.
- **An installation model is reached only inside an installation.** A service run on a developer's own
  machine is given no [model server](../platform/installation-models.md): a provider whose preset is
  `installation` is refused at start there, and the developer points a provider at a server of their own.
- **One model server per cluster, serving the models its administrator declared.** It runs one replica.
  No service can fetch, upload, remove or make a model, and the server serves nobody outside the
  installation. It serves chat completions only: no embeddings, and an agent's judgments are not made on
  it. Its models are fetched from Ollama's registry, so an installation that cannot reach it supplies its
  models some other way. A remote cluster has no model server of its own.
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

- **The gateway bounds an HTTP response at fifteen seconds.** A route the platform renders for an exposed
  service's HTTP port names no timeout, and the gateway then ends a request that has not finished
  within fifteen seconds, a stream of server-sent events included. A wait for a workflow's end served
  through the gateway is bounded by it, as any long response is; the gRPC route has no such bound.
- **No restriction on where a workload connects to.** Network policies decide who may connect to a
  workload; nothing restricts where it may connect. There is no egress policy.
- **The object store speaks plain HTTP inside the cluster.** Every other port a workload reaches is mutual
  TLS; the store's are not. A network policy admits only ankka workloads, the gateway and the operator, and
  a request is signed, so a secret key never crosses the network, but an object's contents cross it
  unencrypted. A request from a browser is encrypted as far as the gateway.
- **The control plane's `keys` port asks for no client certificate.** It serves only the public keys
  machine tokens are signed with, port 7629, to the broker and the services that verify those tokens; a
  network policy admits ankka workloads and the broker.
- **A project is not a network boundary for HTTP.** Any ankka workload can open a connection to any
  service's HTTP port; whether the request is served is the callee's ACL's decision, from the caller's
  certificate, and a service of another project is admitted only on a route its project granted it.
  Cluster ports and databases are closed to other projects.
- **A machine is a `Caller.Machine`, not a `Caller.Service`.** A registered machine outside the
  installation is admitted by a grant as a service is, but it is a caller of its own kind, verified by a
  token rather than a certificate. A service of another installation is not a caller ankka can name:
  trust between installations is not built, and such a service is registered as a machine.
- **The gateway is one caller.** Every request from outside the cluster reads as the gateway, whichever
  hostname it arrived at. Telling users apart is a bearer token the service verifies in
  `Acl.Authenticate`.
- **The platform verifies a service's users' tokens and issues none.** A service lists the issuers it
  accepts, and an authenticated route admits only a token one of them signed. No realm, client or user is
  provisioned for a service's users, and the platform administers none: the identity provider is the
  installation's or the service's to run. Only signed JSON Web Tokens whose issuer publishes its keys are
  verified, every listed issuer must name an audience, and a route accepts any of the service's issuers.
- **Only a Scala, Java or Kotlin service calls another service's gRPC endpoint.** A Python, TypeScript or Go service calls
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
  protocol. A web page cannot call a gRPC endpoint: gRPC-Web is not routed. An MCP endpoint rides on the
  HTTP port.
- **The sign-in broker holds no account and no password.** The platform signs people in for a service only
  through a broker the service's MCP endpoint opted into, which sends each person to the service's own
  issuer and mints a token only for that endpoint. A service that has not opted in has no authorization
  server, and no part of the platform keeps the accounts of the people a service serves.
- **A gRPC service's peers address is skipped if its name is taken.** A service that serves gRPC has a
  headless address named `<service>-grpc-peers`. If another service in the project is called that, the
  platform leaves that service's address alone and does not create the peers address, and callers then
  balance by connection rather than by call.
- **Custom hostnames have limits.** A service can answer at names under domains you own, beside its
  derived hostname (see [Serve a service at your own domain](../deploy/custom-hostnames.md)), up to five
  per service. The platform writes no DNS: you create the proof record and the record that points the
  name at the installation. It does not accept a certificate you supply; it obtains one. The control
  plane, the console, the identity provider and the object store keep their derived names. Two services
  cannot share one hostname by path. An apex name needs the installation to publish its gateway's
  address.

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
  store can join it. A process's own lines, in Python, TypeScript or Go, carry no trace.
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
  database when the service's ask timeout runs out; `get`, `all`, `count` and, in Scala, `where` and
  `ordered` stop the caller waiting and leave the statement to finish.
- **A view's version may be raised only once every instance knows versions of views that read
  entities.** An instance from before cannot be told to stop writing during the rebuild. See
  [Upgrading](../deploy/upgrading.md).
- **A topic source's rebuild is bounded by what the broker retains.** A rebuild of a view reading an
  entity is not: an entity keeps every change it recorded, and the view reads all of them again. Raising a topic-sourced view's
  version empties it and reads its topic again, but a topic's retention is a window, not a record: what
  the broker has dropped is not read, and the rebuilt view holds only what the window still holds. While
  it runs the view serves an empty or partial table. What a rebuild reached is shown in the service's
  status, per partition, but what was dropped cannot be known or read back. See
  [Rebuilding by version](../build/topics.md#rebuilding-by-version).
- **Topic sources are at least once.** A view or consumer sourced from a topic must tolerate duplicates,
  and a view skips a message with no `ce-subject`. Only Kafka is supported; another broker needs its own
  implementation of the broker interface.
- **A consumer's several messages are not published atomically.** They are published at least once and
  in order; when the broker refuses one, the change is delivered again and all are published again. One
  change's messages may be at most 4 MiB together.
- **The secret store has no rotation, sharing or history a service can read.** A service's secret key
  cannot be changed in place: values kept with one key fail to read under another. A service secret
  belongs to the service that kept it, and another service asks for what it needs over HTTP. On Secret
  Manager a few versions of a value are kept and the rest destroyed; the kept count is the
  installation's, not a secret's. A project secret reaches a pod as an environment variable, or, when it
  is the credential of a broker the project declares, as files on the platform's container alone — never
  as a file from Secret Manager.
- **Secret Manager is Google's, on GKE, and its access is written by the cloud provider.** The
  `secret-manager` backend needs Workload Identity Federation for GKE; no other cloud's secret manager is
  supported. The bindings that admit each service to its own secrets, the sync of project secrets into
  the cluster, a service's wait for that sync, and the report of whether Google Cloud's access log is on
  are the installation's cloud provider's; without one, they are made by hand and the status says
  `unknown`. On that backend a project secret's value is also in the cluster's Secret store, and a pod's
  read of it at start is not recorded per read.
- **Every read of a secret depends on the record of reads.** A read whose record the control plane does not
  acknowledge within five seconds fails as unavailable. A keep or a removal is recorded after the backend
  answers, so one that succeeded while the record could not be written is done and reported as
  unavailable. The record is not shown in the console, and the platform's own copying during a move to
  Secret Manager is not recorded.
- **A topic is made only by declaring it, with its partitions and its settings.** On the installation's
  broker a topic exists because its project declares it; publishing to one nobody declared waits. A
  declaration says a topic's partitions, its retention time and size, its cleanup policy with the
  tombstone window and compaction lags, and its copies and minimum in-sync copies, and nothing more: no
  other Kafka topic setting can be declared. A [graph consumer](../build/graph.md#the-topic)'s topic is
  declared compacted on its project; on a broker a descriptor or a project names, every topic is the
  broker owner's to create, and its settings are the owner's too.
- **Retention is not erasure.** How long a topic keeps a message says nothing about whether a person's
  data is forgotten: a shorter retention removes messages at the broker's next cleanup, from every copy,
  but is not a way to honour a request to erase someone's data, which is done to the data itself.
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
- **Agents stream their replies and views their rows; nothing else streams.** Entities and workflows
  refuse a streaming request. A wait for a workflow's end can be served as a stream; the
  workflow itself does not stream.
- **A watch of a view guarantees no delivery.** It is live, not a record: it may give a watcher fewer
  versions of a row than were written, drops unread rows past its bound as the watcher chose, gives
  nothing written while it was not open, and ends — for a rebuild, a stopping instance or a lost
  connection — with the reason, to be watched again. A reader that must see every change reads the
  source with a consumer. A watched query is decided for each written row alone, so a match that depends
  on the time or on another row changes on the row's next write.
- **A module waits whole.** A WebAssembly module waits for a workflow's end with one call, and cannot
  serve the wait as a stream. The instance it runs on is held for as long as the wait, so a module waits
  from a step, a route or a tool rather than a command.
- **A wait needs both halves at the same release.** An instance from before waiting answers a wait for a
  workflow's end as an unknown handler, and the caller is told so; a process beside a runtime before
  protocol 1.18 is refused when it waits. During a rolling update, a wait can fail this way until every
  instance is at the release.
- **A paused workflow's deadline needs the sweeper and the workflow at the same release.** An instance
  from before durable pauses drops a pause's timer when it holds the sweeper, and answers a wake as an
  unknown handler when it hosts the workflow, which the sweeper retries. During a rolling update a paused
  workflow that is not in memory can therefore resume only when it is next called. A workflow whose id is
  longer than about 1,000 bytes has no timer for its pause, since a timer's payload is at most 1,024
  bytes; it resumes on time only while it stays in memory, and the service logs a warning.
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
- **A module reads a view whole.** It cannot ask a query as a stream or watch one, and a module whose view
  declares a watchable query is refused at start.
- **A deployed module cannot be debugged in place.** There is no debugger attached to a module the runtime
  has loaded; its `log` calls go to the runtime's log, and its unit tests run natively.
- **The module image must copy.** A wasm service's image is run once to copy `service.wasm` into
  `/ankka/module`; an image that does anything else fails the pod's start. The platform does not yet mount
  the image as a volume, which would need a container runtime newer than every cluster it targets.
- **Autonomous agents do not coordinate yet.** There is no delegating a subtask to another agent, handing a
  task on, leading a team over a shared backlog, or moderating a conversation between agents; coordinate
  several from a workflow, or write the steps as a [blueprint](../build/blueprints.md). A blueprint's work
  step gives its task a definition of its own — instructions, tools, model, budget — which the platform's
  worker agent carries out; outside blueprints there are still no per-instance overrides of a definition.
- **An autonomous agent's tools run at least once.** A tool whose result had not been recorded when a task's
  process stopped runs again when the task resumes. Write tools with side effects to tolerate a repeat.
- **Blueprints are Scala only, and say nothing a step cannot.** Only a Scala service registers blueprints;
  Python, TypeScript and Go services, through the sidecar, are a later feature. A blueprint has no conditions,
  branches or loops of its own, and no branching search (several drafts scored and the weaker pruned):
  every repetition is inside a step and bounded by it, and a choice is a step's shape or a gather chosen
  by an earlier step's result. No step waits for a person as a pattern of its own; a person is reached
  through a tool that requires approval, and the run waits. A blueprint does not start, wait on or read
  another's run; blueprints compose through a service's records. An ask turn cut off part way, by a
  restart, runs again from its start: a model call recorded before the restart is not made again, but
  one inside the turn that was cut off is. No console shows blueprints or runs; read a run's record, or
  follow its events.
- **An attachment by reference is not fetched.** The model is shown the reference; a tool fetches it.
- **The local console does not show autonomous agents.** Read a task's record, or watch an instance's
  notifications.
- **Judgments are for Scala services' agents only.** Java, Kotlin, Python, TypeScript, Rust and Go services cannot declare a
  question, ask for a judgment or use a judged guardrail. A workflow step, an endpoint or a consumer reaches
  one by calling an agent's judgment handler, or a blueprint's judge or critique step asks one; a handler
  cannot judge and then call the text model in one reply; make two calls. Guardrails, judged or not, see a message and a reply, never the tool calls a model
  asks for. No console shows a judgment's answers or probabilities, and a task's and an instance's own usage
  do not include judgment tokens, which are on the task's session. TypeSafe AI's Jev is the only provider
  that ships, and it is in early access.
- **A session that recorded judgment tokens is unreadable by an older runtime.** While a service is being
  rolled onto its first version that uses judgments, an instance still running the previous version cannot
  replay a session in which a judgment's tokens have been recorded, until the roll completes.
- **Model providers speak two protocols, and nothing more of any vendor's API.** Every provider is
  Anthropic's Messages protocol or OpenAI's, in its Responses or Chat Completions shape. A vendor's own
  hosted tools (web search, file search, code interpreter), embeddings, and Gemini's native API are not
  used; Gemini is reached through its OpenAI-compatible endpoint. A Bedrock endpoint takes a Bedrock API
  key and an Azure one an API key: neither request signing nor Entra credentials are configured by
  variable, though a Scala service may hand either provider a client of its own.
- **A model provider at an address of the developer's own gives less than a vendor's.** A compatible
  endpoint never produces a refusal: a model that declines in prose has answered, and a guardrail is the
  place to catch it. Cache accounting is what the endpoint reports, which for a local server is none.
  Whether a model calls a tool, rather than writing a call in its text, is the model's and its chat
  template's to get right.
- **A model's reasoning is carried within a turn only.** OpenAI's reasoning models are handed back what
  they reasoned with a tool's result for the rest of the turn, and never after: a turn that waits for a
  person to approve a tool goes on without it, and so does an autonomous agent's task after its instance
  restarts. The protocol accepts that, at some cost to the answer.
- **A process names a configured provider, not a model.** A Python, TypeScript, Rust or Go handler names
  one of the providers the sidecar was configured with, each with one model; it cannot choose another model
  of the same provider for one interaction.
- **MCP servers are reached over Streamable HTTP only.** There is no stdio or other transport, so a server
  that runs as a local program is reached through a small HTTP bridge. Only a server's tools are used —
  not its resources or prompts — and a server is listed in code, not discovered.
- **An MCP server's credential is a header from a variable, read at start.** There is no OAuth flow, and a
  credential cannot be given or changed while the service runs; a new one takes a restart.
- **An approval is decided whole.** A person approves or refuses a tool call as the model made it; there
  is no editing its arguments, and a decision is not streamed back to a caller still connected.
- **A module's agents take no part in approvals or MCP servers.** A WebAssembly module declares neither,
  and its client answers a call that would wait for a person as a conflict.
- **An MCP endpoint offers tools and nothing else.** It offers no resources or prompts, and asks the client
  for no sampling or elicitation.
- **An MCP endpoint keeps nothing between requests.** It issues no session and sends no notice that its
  tools changed; a change to a service's tools is seen by an MCP client at its next listing.
- **There is no one MCP endpoint for every service of a project.** Each service serves its own tools at its
  own address, and a client that wants several services' tools lists each.
- **A route referred to as a tool takes its body as JSON.** A route whose body is another content type
  cannot be referred to; a tool with a handler of its own can call it.
- **Output guardrails cannot unsay a stream.** On a streaming agent handler, output guardrails run after the
  tokens have been delivered. They can stop the reply being written to memory, but not un-send it. Use input
  guardrails for anything that must never be shown.

## SDKs and releases

- **gRPC endpoints are for Scala and Java services.** A Kotlin service declares one through the Java SDK's
  `GrpcEndpoint`, with grpc-java's generated stubs. A Python, TypeScript, Rust or Go service cannot declare one,
  and a descriptor that declares gRPC with process or wasm hosting is refused.
- **The local console does not call gRPC methods.** It lists them beside a service's routes; call them with
  `grpcurl` or a client generated from the service definition.

- **The TypeScript and Go SDKs declare and call autonomous agents but cannot script one in their unit
  test kits.** Test one through a sidecar with `ANKKA_MODEL_SCRIPT`, as the Python SDK's integration
  testkit does.
- **The Java SDK cannot script a judgment.** Its test kits script a model (`TestModelProvider`) but not a
  judgment provider, and a `JudgmentProvider` is only one `JudgmentProviders` builds, so a Java test of a
  judgment or a judged guardrail needs Jev itself; nor do they hold an MCP server of their own, so a test of
  an agent's MCP tools runs a server on loopback. Its codec reads records, sealed interfaces and enums by
  reflection when the service starts; there is no annotation processor and no compile-time derivation,
  and a codec is customised by handing `withSerializer` a serializer of your own, not by a given. A record
  field of type `float`, `short`, `byte` or `char` is refused at start, naming the field, because the
  encoding has no form for it; use `int`, `long` or `double`. A gRPC endpoint is written against
  grpc-java's generated descriptors.
- **The Kotlin SDK has no agent test kit of its own.** A Kotlin agent is tested with the Java SDK's
  `AgentTestKit` and `TestModelProvider`, so a judgment cannot be scripted in Kotlin either. It runs on the JVM only, with no Kotlin multiplatform or Kotlin/Native build. Data
  classes are read by the Java SDK's codec through Kotlin reflection, not by `kotlinx.serialization`. A
  declared query's `Flow` (`askFlow`) is cold and ends with the query's last row; a watch is the Java
  SDK's `ViewWatch`, not a `Flow`. gRPC uses grpc-java's stubs; there are no Kotlin coroutine stubs.
- **Seven languages.** Services are written in Scala, Java, Kotlin, Python, TypeScript, Rust or Go. Another language needs an SDK, or a
  guest library for the WebAssembly mode, that passes the conformance suite; see
  [Adding a language SDK](../contributing/language-sdks.md).
- **The CLI has native builds for macOS and Linux only.** There is no Windows executable, no Linux
  package, and the Linux builds need glibc, so they do not run on musl (Alpine); the release's zip runs
  anywhere with a JDK 21. The macOS executables are not signed by Apple. `ankka init` needs `sbt` on
  `PATH` for a Scala service.
- **`ankka mcp` serves no plugin's commands.** A program run as an `ankka` plugin is a command at the
  shell only; a coding agent reaches it by running the shell. The CLI asks a plugin nothing about
  itself, so `ankka --help` lists a plugin by its word, with no description.
- **The template waits on a release.** `sbt new thinkmorestupidless/ankka.g8` works once a release has
  published the template; until then use `sbt new file:///path/to/ankka/ankka.g8` from a checkout.
- **No local image registry.** A local platform loads images straight into its cluster with
  `kind load docker-image`; it runs no registry of its own. A cluster that pulls images needs one
  elsewhere. A private one works: `ankka projects registry set` puts its credential in the cluster
  for a whole project, and the platform never reads the credential back.

## Upgrading to cross-project access

- **A project named `machine` is refused.** A grantee is written `machine:<organization>/<name>`, so the
  word is reserved; rename such a project before upgrading.
- **A kind cluster reaches an exposed broker only if it was created with the `30094 → 9094` mapping** in
  `kustomization/kind.yaml`. kind decides port mappings when it creates a cluster, so one made before
  must be recreated to reach the broker from the host.
- **The control plane has a second port, `keys` (7629),** which asks for no client certificate and serves
  only the public keys machine tokens are signed with. A network policy of the installation's own that
  names the control plane's ports needs it too.
- **Services read their project's grants from the project's ConfigMap they already mount**, so upgrading
  the operator restarts nothing; a service built before the release admits no granted caller until it is
  rebuilt, and a grant naming one of its routes is listed as `rollout needed`.
