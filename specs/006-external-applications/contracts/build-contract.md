# Contract: What an Application's Build Sees

**Satisfies**: FR-001 to FR-006, FR-017

## Dependencies

```scala
val nakkaVersion = "0.2.0"
libraryDependencies ++= Seq(
  "com.thinkmorestupidless" %% "nakka-sdk"     % nakkaVersion,
  "com.thinkmorestupidless" %% "nakka-runtime" % nakkaVersion,
  "com.thinkmorestupidless" %% "nakka-http"    % nakkaVersion,
  "com.thinkmorestupidless" %% "nakka-agent"   % nakkaVersion,   // only if the service has agents
  "com.thinkmorestupidless" %% "nakka-testkit" % nakkaVersion % Test
)
```

`nakka-core` arrives transitively. These six are the whole published surface; naming
`nakka-controlplane`, `nakka-crd`, `nakka-operator`, `nakka-cli` or `nakka-controlplane-api` fails
to resolve, by design.

| Artifact | Brings | An application uses it for |
|---|---|---|
| `nakka-sdk` | the component API: entities, views, consumers, workflows, timers, `ComponentClient` | writing components |
| `nakka-runtime` | Pekko, r2dbc, projections, cluster formation, the DDL under `nakka/ddl/` | `Nakka.service…start()`; the schema for local runs |
| `nakka-http` | the endpoint DSL, `HttpServer`, `Acl` | endpoints |
| `nakka-agent` | the agent loop, providers | agents |
| `nakka-testkit` | `EventSourcedTestKit`, `NakkaTestKit`, `TestModelProvider` | tests at both levels |

## What the runtime promises an application

- `NAKKA_HTTP_PORT` is honoured; 9000 by default.
- `NAKKA_CLUSTER_MODE` unset means "join self" (a laptop); the platform sets it in a pod. An
  application never sets it.
- `nakka/ddl/*.sql` on the classpath is the schema the runtime needs, in apply order.
- The management endpoint on 7626 serves `/ready` and `/nakka/version` in a pod.
- `nakka.core.BuildInfo.version` is the runtime's version.

## What the platform promises an application

- A platform at version `P` deploys an image whose declared `runtime` is `R` when
  `R.major == P.major && P.minor - 1 <= R.minor <= P.minor`; otherwise the service is reported
  `Unavailable` with both versions in `detail` and nothing starts.
- The schema the operator provisions for a service at `P` is a superset of what runtime `R` in
  that range needs.

## Versions

- Released versions are tags: `v0.2.0` → `0.2.0`. Everything published from one tag shares it.
- `+N-sha-SNAPSHOT` versions are local builds; `publishLocal` of one is the platform's own
  development loop and is never released.
