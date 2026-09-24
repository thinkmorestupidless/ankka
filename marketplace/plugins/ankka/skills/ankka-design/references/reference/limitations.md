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

- **Cluster traffic is neither isolated nor encrypted.** Remoting on port 17355 and the management port 7626
  are plain TCP on the pod network, reachable from any namespace. A node chooses its peers by pod label, but
  nothing stops another workload connecting. TLS for cluster traffic and a network policy are not built.
- **Projects are not a network boundary.** A service's in-cluster address is reachable from every namespace,
  so any project's pods can call any other project's services. Projects separate names and databases, and
  database separation is enforced, but not traffic.
- **The platform authenticates its operators, not your service's callers.** The control plane verifies
  identity-provider tokens; a deployed service's endpoints are protected only by the ACL their author wrote.
  The platform provisions no identity realm, client or token check for services.
- **Roles are per organization.** A member is an owner or a member of an organization. There are no
  per-project roles and no read-only role.
- **Nothing at the gateway but routing.** There is no authentication, rate limiting or header policy at the
  gateway. HTTP/1.1 only through it, with no gRPC or HTTP/2 to services, and one gateway per installation.
- **One port, HTTP only.** A service has a single HTTP port and no other protocol.
- **No custom hostnames.** An exposed service's hostname is derived by the platform as
  `<service>-<project>.<base domain>`. A domain of your own is not supported.

## Observability

- **The console is local only.** `ankka local console` shows the services running on your own machine. There
  is no console for a deployed installation; the CLI is the only client for anything in a cluster.
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
- **Output guardrails cannot unsay a stream.** On a streaming agent handler, output guardrails run after the
  tokens have been delivered. They can stop the reply being written to memory, but not un-send it. Use input
  guardrails for anything that must never be shown.

## SDKs and releases

- **Two languages.** Services are written in Scala or Python. Another language needs an SDK that passes the
  conformance suite; see [Adding a language SDK](../contributing/language-sdks.md).
- **The Python SDK is not on PyPI.** Install it from the repository by path.
- **The CLI is a JVM program.** It is installed with Homebrew (`brew install
  thinkmorestupidless/tap/ankka`, which brings its own JDK) or unpacked from a release's zip onto a
  JDK 21; there is no native binary and no Linux package. `ankka init` needs `sbt` on `PATH` too.
- **The template waits on a release.** `sbt new thinkmorestupidless/ankka.g8` works once a release has
  published the template; until then use `sbt new file:///path/to/ankka/ankka.g8` from a checkout.
- **No local image registry.** A local platform loads images straight into its cluster with
  `kind load docker-image`. A cluster that pulls images needs a registry configured.
