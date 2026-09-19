# Contract: What an Application's Build Sees

**Satisfies**: FR-001 to FR-006, FR-017

## Dependencies

```scala
val ankkaVersion = "0.2.0"
libraryDependencies ++= Seq(
  "com.thinkmorestupidless" %% "ankka-sdk"     % ankkaVersion,
  "com.thinkmorestupidless" %% "ankka-runtime" % ankkaVersion,
  "com.thinkmorestupidless" %% "ankka-http"    % ankkaVersion,
  "com.thinkmorestupidless" %% "ankka-agent"   % ankkaVersion,   // only if the service has agents
  "com.thinkmorestupidless" %% "ankka-testkit" % ankkaVersion % Test
)
```

`ankka-core` arrives transitively. These six are the whole published surface; naming
`ankka-controlplane`, `ankka-crd`, `ankka-operator`, `ankka-cli` or `ankka-controlplane-api` fails
to resolve, by design.

| Artifact | Brings | An application uses it for |
|---|---|---|
| `ankka-sdk` | the component API: entities, views, consumers, workflows, timers, `ComponentClient` | writing components |
| `ankka-runtime` | Pekko, r2dbc, projections, cluster formation, the DDL under `ankka/ddl/` | `Ankka.service…start()`; the schema for local runs |
| `ankka-http` | the endpoint DSL, `HttpServer`, `Acl` | endpoints |
| `ankka-agent` | the agent loop, providers | agents |
| `ankka-testkit` | `EventSourcedTestKit`, `AnkkaTestKit`, `TestModelProvider` | tests at both levels |

## What the runtime promises an application

- `ANKKA_HTTP_PORT` is honoured; 9000 by default.
- `ANKKA_CLUSTER_MODE` unset means "join self" (a laptop); the platform sets it in a pod. An
  application never sets it.
- `ankka/ddl/*.sql` on the classpath is the schema the runtime needs, in apply order.
- The management endpoint on 7626 serves `/ready` and `/ankka/version` in a pod.
- `com.thinkmorestupidless.ankka.core.BuildInfo.version` is the runtime's version.

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
