# Data Model: A nakka Application Built Outside This Repository

**Feature**: [spec.md](./spec.md) | **Research**: [research.md](./research.md)

## Published artifacts

| Artifact | Module | Depends on (published) |
|---|---|---|
| `com.thinkmorestupidless:nakka-core_3` | `core` | — |
| `nakka-sdk_3` | `sdk` | core |
| `nakka-runtime_3` | `runtime` | core, sdk — carries `nakka/ddl/*.sql` as resources |
| `nakka-http_3` | `http` | core, sdk, runtime |
| `nakka-agent_3` | `agent` | core, sdk, runtime |
| `nakka-testkit_3` | `testkit` | all of the above |

Every other project — `controlPlaneApi`, `crd`, `operator`, `controlPlane`, `cli`,
`shoppingCart`, `multiAgentPlanner`, root — has `publish / skip := true`. `SC-004` lists the
local repository and expects exactly the six.

Each artifact ships with `-sources.jar`, `-javadoc.jar` and a POM carrying `licenses`,
`homepage`, `scmInfo`, `developers` — Central's requirements, set once in `ThisBuild`.

## Version

- Derived by `sbt-dynver` from the nearest tag: `v0.2.0` → `0.2.0`; `v0.2.0` + 3 commits →
  `0.2.0+3-<sha>-SNAPSHOT`; no tag → `0.0.0+…-SNAPSHOT`.
- Carried in code as `nakka.core.BuildInfo.version` (sbt-buildinfo on `core`).
- Shared by the six artifacts, the operator and control plane images (already tagged with the
  build's version by sbt-native-packager) and the CLI's `nakka version`.

## Compatibility (`controlplane-api`)

```
Compatibility.supports(platform: Version, runtime: Version): Boolean =
  platform.major == runtime.major &&
  runtime.minor >= platform.minor - 1 && runtime.minor <= platform.minor

Compatibility.describe(platform): String  // "runtimes 0.2.x–0.3.x (platform 0.3.1)"
```

`Version` is parsed leniently from `MAJOR.MINOR.PATCH[+…][-SNAPSHOT]`; a string that does not
parse is a descriptor problem at apply time ("runtime version 'x' is not MAJOR.MINOR.PATCH").

### Descriptor — one new optional field

| Field | Type | Meaning |
|---|---|---|
| `service.runtime` | `Option[String] = None` | the nakka version the image was built against. Absent: not checked (every pre-006 descriptor). Present: checked at projection against `Compatibility`. |

### Projection

`ServiceProjection.project` returns `Left(Vector(s"runtime $declared is outside the platform's
supported range: ${Compatibility.describe(platform)}"))` when `service.runtime` is declared and
unsupported. That takes the existing "cannot project" path: the projector records
`ClusterView.Unreachable(problem)` → `lifecycle: Unavailable`, `detail` = the problem, no resource
is written, no pod starts. When the declaration becomes supported (a re-apply with a compatible
image, or a platform upgrade), the next projection succeeds and the service proceeds normally.

## Template (`nakka.g8/`, Giter8)

```
nakka.g8/
└── src/main/g8/default.properties   # name=my-service, nakka_version=<build's version>, package=…
└── src/main/g8/
    ├── build.sbt               # nakkaVersion = "$nakka_version$"; JavaAppPackaging + DockerPlugin; schema task
    ├── project/build.properties, project/plugins.sbt
    ├── docker-compose.yml      # postgres:17-alpine, ./target/ddl:/docker-entrypoint-initdb.d
    ├── service.json            # {"name":"$name;format="norm"$","service":{"image":"$name;format="norm"$:latest","runtime":"$nakka_version$"}}
    ├── README.md
    └── src/
        ├── main/scala/$package$/
        │   ├── Main.scala                       # Nakka.service.register(ItemEntity.descriptor).register(ItemRows.descriptor).withExtension(HttpServer.of(...)).start()
        │   ├── domain/Item.scala
        │   ├── application/ItemEntity.scala     # add-item, get-item
        │   ├── application/ItemRows.scala       # the listing view
        │   └── api/ItemEndpoint.scala           # POST/GET /items/{id}, GET /items; acl declared
        ├── main/resources/{application.conf,logback.xml}
        └── test/scala/$package$/
            ├── ItemEntitySuite.scala            # EventSourcedTestKit
            ├── ItemHttpSuite.scala              # NakkaTestKit + HTTP
            └── ItemIntegrationSuite.scala       # NakkaTestKit, restartService()
```

### Parameters

| Key | Default | Rule |
|---|---|---|
| `name` | `my-service` | `format="norm"` for the service/image/descriptor name; validated against `[a-z]([-a-z0-9]{0,61}[a-z0-9])?` by a `require` in the generated `build.sbt` so an invalid name fails at the first `sbt` invocation with the rule |
| `package` | `com.example.$name;format="camel"$` | Scala package |
| `nakka_version` | the version the template was released with | the artifact version *and* the descriptor's `runtime` — written to both from one parameter |

### The `schema` task (generated `build.sbt`)

```
schema := {
  val jar = (Compile / dependencyClasspath).value.map(_.data)
    .find(_.getName.startsWith("nakka-runtime")).getOrElse(sys.error("nakka-runtime not on the classpath"))
  // unzip nakka/ddl/*.sql into target/ddl
}
```

## CLI

| Command | Behaviour |
|---|---|
| `nakka version` | prints `BuildInfo.version` |
| `nakka init <name> [--template <ref>] [--package <pkg>] [--dir <path>]` | runs `sbt new <ref> --name=<name> [--package=<pkg>]` in `<dir>` (default `.`); `<ref>` defaults to `thinkmorestupidless/nakka.g8`; exit 1 with a message if `sbt` is not on `PATH` |

## Repository additions

| Path | Purpose |
|---|---|
| `project/plugins.sbt` | `sbt-ci-release`, `sbt-buildinfo` |
| `build.sbt` | POM metadata; `publish / skip` on every non-library; buildinfo on `core`; `JavaAppPackaging` on `cli` |
| `.github/workflows/ci.yml` | `sbt -Dnakka.cluster.tests=off test` on push/PR (cluster suites need Docker-in-Docker and stay local for now) |
| `.github/workflows/release.yml` | `sbt ci-release` on tag `v*` with the four secrets sbt-ci-release documents |
| `nakka.g8/` | the Giter8 template |
| `cli/src/test/scala/nakka/cli/TemplateSuite.scala` | R7 |
| `README.md` | "Your first service" |
