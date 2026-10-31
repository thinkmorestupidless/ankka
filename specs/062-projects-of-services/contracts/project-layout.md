# Contract: what the project template writes

For `ankka init shop --project --services orders,billing` (Scala). Tokens the template uses:
`{{project}}`, `{{service}}`, `{{services_json}}` (the context map's services array),
`{{ankka_version}}`, `{{package}}`, `{{package_path}}`, `{{protocol_version}}`.

```text
shop/
├── README.md                     # what this is, how to run, add a service, check the map, release one service
├── .gitignore                    # target/ .bsp/ .metals/ .idea/ .ankka/ plus each language's
├── .mcp.json                     # the ankka mcp entry, as every template
├── .githooks/{commit-msg,check-commits}   # as every template
├── .github/
│   ├── pull_request_template.md
│   └── workflows/{ci.yml,service-checks.yml,deploy.yml}        # contracts/workflows.md
├── .claude/skills/<the 18 ankka skills>/, speckit-design/, speckit-build/
├── .specify/
│   ├── extensions.yml            # installed: bdd, review, design-build; the three hooks
│   └── extensions/{bdd,review,design-build}/…
├── GLOSSARY.md                   # seeded: project, service names, the crossing's words; an Everyday words section
├── features/README.md            # where the living features go
├── specs/                        # empty, with a .gitkeep
├── context-map.json              # orders, billing; one topic crossing orders → billing; one route crossing
├── CONTEXT-MAP.md                # rendered
├── contracts/orders.v1.json      # the sample contract's JSON Schema
├── docker-compose.yml            # postgres:17-alpine (user/password ankka, no POSTGRES_DB beyond the default), apache/kafka:3.8.0 on 9092
├── scripts/
│   ├── run.sh                    # run.sh <service>: databases.sh, then the service with its env (R8)
│   ├── databases.sh              # create a database per services/* that is missing; apply the DDL to each
│   ├── changed-services.sh       # <base> <head> → the change-to-work JSON (data-model.md)
│   └── service-language.sh       # <dir> → scala|python|typescript|web|rust|go|java|kotlin
├── build.sbt                     # scalaVersion; shared; tests (dependsOn every discovered service); schema task
├── project/
│   ├── build.properties          # sbt.version as the single template's
│   ├── plugins.sbt               # sbt-native-packager
│   └── Services.scala            # the AutoPlugin: discover services/*, docker settings, version from <name>/v* tags
├── shared/src/main/scala/<pkg>/contracts/
│   └── OrdersV1.scala            # the message type and `val ordersV1 = Contract.fromSchema("orders.v1", <contracts/orders.v1.json as a resource>)`
├── services/
│   ├── orders/
│   │   ├── service.json          # {"name":"orders","service":{"image":"orders:latest","runtime":"<ankka_version>","port":9000}}
│   │   ├── src/main/resources/{application.conf,logback.xml}
│   │   ├── src/main/scala/<pkg>/orders/{Main.scala, OrdersService.scala, api/…, application/…, domain/…}
│   │   └── src/test/scala/<pkg>/orders/{…EntitySuite, …HttpSuite, …IntegrationSuite}.scala
│   └── billing/ (likewise, port 9001; a consumer reading orders-placed and an invoice entity; an endpoint POST /invoices)
└── tests/src/test/scala/<pkg>/
    ├── OrderInvoicedSuite.scala  # the cross-service test: two kits, one InMemoryBroker, localServices
    └── ContextMapSuite.scala     # ContextMapCheck.problems for orders and billing is empty
```

## A Scala service inside a project (`project-service-scala/`)

`service.json`, `src/main/resources/`, `src/main/scala/<pkg>/<service>/`, `src/test/scala/…`. No
`build.sbt`, `project/`, compose, workflows, hooks, skills or `.mcp.json`. `Main.scala`:

```scala
object Main:
  def main(args: Array[String]): Unit =
    OrdersService.start()           // Ankka.service.register(…).withExtension(…).start(name = "orders")
```

`OrdersService` exposes `descriptors` and `extensions(broker: Broker)`, so the tests module starts
it under a kit with the shared in-memory broker (R7); `start()` uses `ProjectionRuntime.fromEnv()`.

## A service in another language inside a project

The language's template rendered into `services/<service>/` minus: `.github/`, `.githooks/`,
`.claude/`, `.mcp.json`, `docker-compose.yml`, `README.md`'s deploy section. Its `service.json`
gets `port` set. Its own build and tests are untouched; it reads the contract's schema from
`../../contracts/<contract>.json` when it needs it (documented, not wired).

## What `ankka init <service>` inside a project touches

`services/<service>/**` (new), `context-map.json` (one entry appended), `CONTEXT-MAP.md`
(re-rendered). Nothing else.

## Differences from the single-service Scala template, deliberate

| Single service | Project |
|---|---|
| `version := SERVICE_VERSION or 0.1.0-SNAPSHOT` | `SERVICE_VERSION` or derived from `<service>/v*` tags |
| `on: push: tags: ['v*']` | `['*/v*']`; the service from the tag |
| `ci.yml` runs everything | `changes` → per-service checks, `tests` |
| one Postgres database `ankka` | one Postgres, one database per service, made by `scripts/databases.sh` |
| no broker | Kafka in compose |
| `Ankka.service … .start()` | `.start(name = "<service>")` |
| no `.specify/` | the three extensions; `specify init` by `ankka init` or by hand |
