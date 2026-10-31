# Data model: Projects of several services

Nothing here is stored by the platform. Every entity is a file in a generated project's
repository, or a convention over its tree. The glossary (`GLOSSARY.md`, "Projects of several
services") defines the words.

## The context map

One file, `context-map.json`, at the root of a project's repository. Its presence makes a
directory a project to `ankka init`.

| Field | Type | Rule |
|---|---|---|
| `project` | string | a valid project id (the descriptor's rule); the name the project has on the platform |
| `services` | array of Service | at least one; names distinct |
| `crossings` | array of Crossing | may be empty; no two equal |

**Service**

| Field | Type | Rule |
|---|---|---|
| `name` | string | a valid service name; `services/<name>/service.json` must exist and carry the same name |
| `owns` | string | free text, may be empty; what the service is the one owner of |
| `boundary.criterion` | `ownership` \| `scaling` \| `consistency` | which of the three put the boundary here |
| `boundary.why` | string | free text, may be empty |

**Crossing**: exactly one of the two shapes.

| Shape | Fields | Rule |
|---|---|---|
| topic | `from`, `to`, `topic`, `contract`, `schema` | `topic` a valid topic name; `contract` a valid contract name; `schema` a path, relative to the root, of a file that exists and is a JSON document |
| route | `from`, `to`, `route` | `route` is `<METHOD> <path template>`, the method upper-case; `to` is the service that serves it, `from` the one that calls it |

`from` and `to` are each a service of the map, or `<project>/<service>` naming a service of another
project, which is then not expected under `services/`. A topic crossing reads: `from` publishes to
`topic` what `to` reads. A route crossing reads: `from` calls `route` of `to`.

An unknown field anywhere is refused, as in a descriptor.

**Derived**: the rendering `CONTEXT-MAP.md` (contracts/context-map.md); the local declarations a
future run script could write (not built here); the set of services a scenario crosses, read by the
inspect skill as the crossings between the services the scenario names.

**Invariants the checks hold** (static, `ankka context-map check`):

1. every `services[].name` has `services/<name>/service.json` with that name;
2. every `services/<dir>/service.json` has its name in `services[]`;
3. every `from` and `to` resolves (a service of the map, or qualified by another project);
4. every topic crossing's `schema` exists and parses; its `contract` is valid;
5. no crossing is of neither shape or of both;
6. no two crossings are equal;
7. `CONTEXT-MAP.md` equals the rendering.

**Invariants the tests module holds** (run time, `ContextMapCheck`, Scala services): for each
Scala service S started under its kit, every topic S publishes to is a topic crossing with
`from = S`, every topic S reads is one with `to = S`, with the same contract name as S states; and
every topic crossing naming S is stated by a component of S. The inspect skill holds the same for
a running service of any language, from `describe_local_service`.

## The project

A repository with the layout in contracts/project-layout.md. Conventions:

| Thing | Where | Named |
|---|---|---|
| a service | `services/<name>/` | by its directory, equal to `service.json`'s `name` |
| its image | `<name>:<version>` under `DOCKER_REPOSITORY` | by the service |
| its database | `<name>` in the project's one Postgres, locally and when deployed | by the service |
| its local HTTP port | `port` in its `service.json` | 9000 + the order it was added in; distinct |
| its language | by its build files (`scripts/service-language.sh`) | `scala` when none and `src/main/scala` |
| the shared module | `shared/` | one; Scala services depend on it |
| the tests module | `tests/` | one; depends on every Scala service |
| the contracts | `contracts/<contract>.json` | by contract name |

The Scala build discovers a Scala service by its directory (`project/Services.scala`); the scripts
and workflows discover any service by its directory.

## The service tag

`<service>/v<version>`, a git tag in the project's repository. `service` must be a directory under
`services/` with a descriptor, or the deploy job fails saying the project has no such service.
`version` is `MAJOR.MINOR.PATCH` with optional pre-release, as the single templates accept. The
deploy job derives from it: `SERVICE=orders`, `VERSION=1.4.2`, `IMAGE=<repository>/orders:1.4.2`.

A Scala service's local version (`sbt 'show orders/version'`):

| State | Version |
|---|---|
| at tag `orders/v1.4.2` | `1.4.2` |
| 3 commits past it, clean | `1.4.2+3-<sha>-SNAPSHOT` |
| no `orders/v*` tag | `0.0.0+<n>-<sha>-SNAPSHOT` |
| `SERVICE_VERSION` set | that value, whatever the tags |

`Docker / version` replaces `+` with `-`.

## The change-to-work map

What `scripts/changed-services.sh <base> <head>` prints for a pull request, as JSON:
`{"services": [{"service": "orders", "language": "scala"}, …], "tests": true|false}`.

| Changed path | Services | `tests` |
|---|---|---|
| `services/<n>/**` | `n` | yes when `n` is Scala (the tests module depends on it) |
| `shared/**`, `build.sbt`, `project/**` | every Scala service | yes |
| `tests/**`, `contracts/**`, `context-map.json` | none | yes |
| `.github/**`, `scripts/**` | every service | yes |
| anything else (README, docs) | none | no |

The `checks` job runs `service-checks.yml` once per entry; the `tests` job runs `sbt tests/test`
when the flag is set. A service tag's deploy runs `service-checks.yml` for its one service, then
builds and deploys it.

## The skills' steps (what each reads and writes)

| Skill | Reads | Writes |
|---|---|---|
| `ankka-design`, step "One service, or several" | the domain description; the three criteria | `context-map.json` (services, boundaries, crossings), or nothing when one service |
| `ankka-inspect`, "A scenario that crosses services" | `context-map.json`; `list_local_services`; `describe_local_service` of each side; `call_local_endpoint`; `query_local_entity`; `local_traces` of each side by one trace id | the report row naming the crossing; a test in `tests/` for a met scenario |

## The project template's spec-kit part

| Path | From |
|---|---|
| `.specify/extensions/{bdd,review,design-build}/` | this repository's, copied at build time |
| `.specify/extensions.yml` | this repository's shape: installed, hooks |
| `.claude/skills/speckit-design/`, `speckit-build/` | this repository's |
| `GLOSSARY.md`, `features/README.md` | the template's, seeded with the project's terms |
| spec-kit's own `.specify/{scripts,templates,memory}` | `specify init --here --ai claude --ai-skills`, run by `ankka init` when on PATH, else printed |
