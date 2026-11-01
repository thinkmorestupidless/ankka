# Build a project of several services

> Start a project of several services with ankka init, add a service in any language, keep the context map true, run every service on one machine, test across services, and release one service at a time with a service tag.

Source: https://docs.ankka.cloud/build/projects/
A project of several services is one repository holding every service of an application, the
context map that says where the boundaries between them go, the contracts that cross those
boundaries, and the specs that change them. Each service is still built, tested, released and
deployed on its own. [A project of several services](../concepts/projects.md) says when an
application should be several services; this page is how to build one.

## Start a project

```bash
ankka init shop --project --services orders,billing
```

This writes `shop/`, a project of two Scala services. Each name is a service name: lower-case
letters, digits and `-`, starting with a letter. At least two are needed, and `shared` and `tests`
are the project's own directories, so neither is a service's name.

```text
shop/
├── build.sbt                 the build: a project per service, found under services/
├── project/Services.scala    finds the services and gives each its image and version
├── context-map.json          the services, their boundaries and every crossing
├── CONTEXT-MAP.md            its rendering, for people to read
├── contracts/orders.v1.json  the JSON Schema of the contract the sample topic carries
├── shared/                   the contract's Scala types, which every Scala service builds against
├── services/
│   ├── orders/               service.json (port 9000), its code and its tests
│   └── billing/              service.json (port 9001), its code and its tests
├── tests/                    the tests that cross services, and the context map held to the code
├── docker-compose.yml        one Postgres and one Kafka for every service
├── scripts/                  run a service, make the databases, work out what a change touched
├── .github/workflows/        ci.yml, service-checks.yml and deploy.yml
├── features/, GLOSSARY.md, specs/, .specify/   spec-kit with living features
└── README.md
```

The two services are a working sample of the two kinds of crossing. `orders` publishes the items
people add to the topic `orders-events` under the contract `orders.v1`, and `billing` reads that
topic and records what it reads. `orders` also calls `billing`'s route `GET /billing/{id}` to read
back what `billing` recorded. Replace both with your own once the project builds.

`ankka init` runs `specify init --here --ai claude --ai-skills` in the project when `specify` is on
the `PATH`. When it is not, the next steps it prints start with that command; run it once, in the
project, before the first spec.

## Add a service

Inside a project, `ankka init` adds a service rather than starting one:

```bash
cd shop
ankka init stock                       # a Scala service
ankka init stock --language python     # or Python, TypeScript, Go, Rust, Java, Kotlin or web
```

The service is written under `services/stock/` with its own `service.json`, given the next port no
other service's descriptor names, and appended to `context-map.json`, whose rendering is written
again. Nothing else in the project changes: the build, the checks, the change detection and the
release job each find a service by its directory.

A service in another language is that language's template without the parts the project already
owns: no workflows, no commit hooks, no agent skills and no MCP configuration of its own. A Python,
TypeScript or Go service keeps a compose file for its sidecar, and a Rust service one for the
runtime that loads its module; each points at the project's Postgres and Kafka rather than starting
its own.

`--add` says the directory must be a project. Outside one, `ankka init stock --add` is refused,
naming the directory, and writes nothing.

After adding a service, complete its entry in the map: what it owns, and why its boundary is there.
Then record every crossing it takes part in.

## The shared module and the contracts

A contract is the shape of what crosses a boundary on a topic: a name such as `orders.v1` and a
JSON Schema in `contracts/<name>.json`. The `shared` module holds the contract's Scala types and reads
the schema from its own classpath, so the fingerprint a Scala service declares is the fingerprint of
the file the map names. Every Scala service depends on `shared`; a change to it is a change to every
Scala service, and is checked as one.

A service in another language does not build against `shared`. It reads the same schema file,
`contracts/<name>.json`, and declares the contract by that name. An incompatible change to a contract
is a new contract, `orders.v2`, which each side reads beside the old one until nothing publishes the
old one.

## The context map

`context-map.json` is the record of the project's boundaries:

```json
{
  "project": "shop",
  "services": [
    {"name": "orders", "owns": "items as people add to them",
     "boundary": {"criterion": "ownership", "why": "the orders team releases it"}},
    {"name": "billing", "owns": "what it records of the items orders keeps",
     "boundary": {"criterion": "ownership", "why": "finance releases it"}}
  ],
  "crossings": [
    {"from": "orders", "to": "billing", "topic": "orders-events",
     "contract": "orders.v1", "schema": "contracts/orders.v1.json"},
    {"from": "orders", "to": "billing", "route": "GET /billing/{id}"}
  ]
}
```

Two commands keep it true:

```bash
ankka context-map check     # silent when the map holds; one line per problem otherwise
ankka context-map render    # write CONTEXT-MAP.md from context-map.json
```

`check` holds the map to the project's files. Every service the map names has a
`services/<name>/service.json` named the same, and every service there is in the map. Every crossing
names two services of the project, or a service of another project as `<project>/<service>`; a
crossing with another project's service by a topic records the topic's bare name, which a component
of this project states either as that name or as `<project>/<name>`, the address it gives another
project's topic. Every crossing is either a topic with a contract and an existing schema, or a route written
`<METHOD> <path>`, never both and never neither. Each boundary is drawn by one of the known
criteria. `CONTEXT-MAP.md` is what `render` would write. Each problem is one line, and the command
exits 1 when there is any.

The tests module holds the map to the code as well. Its `ContextMapSuite` reads each Scala service's
components and fails when a service publishes to or reads a topic the map records no crossing for,
when a recorded topic crossing has no component that publishes or reads it, or when a contract
differs from the map's. A route crossing is not held to components; the inspect skill checks it
against the running services.

## Run every service on one machine

```bash
docker compose up -d        # one Postgres and one Kafka, shared by every service
sbt schema                  # ankka's schema, once and after every ankka upgrade
scripts/run.sh orders       # in one terminal
scripts/run.sh billing      # in another
ankka local console         # every running service, and the calls between them
```

`scripts/run.sh` makes a database for each service of the project, named for the service, and
applies the schema to it. It then starts the service on the port its `service.json` names, in its
own database, under its own name, with Kafka at `localhost:9092`. A service in another language is
started with its sidecar or runtime from its own compose file, which reaches the same Postgres and
Kafka from its container. Each Python, TypeScript or Go service has a process port of its own in that
file, which the script exports as `ANKKA_PROCESS_PORT`, so several such services run side by side.

A service started this way announces itself in a file under `~/.ankka/running/`, and a call to
another service by name reaches the address that service announced. The announcement is a file, so
services find each other whether or not `ankka local console` is running; the console only reads
the same files to list them. A sidecar in a container cannot read those files, so it is told where
another service is with a JVM option, as
[calling other services on a developer's machine](calling-services.md#on-a-developers-machine)
describes.

## A test that crosses services

The `tests` module holds the tests that cross services. Each starts the services it needs in one
JVM, two test kits on one in-memory broker, each kit under its own service's name and told where the
other listens, and drives them through their real endpoints. The sample, `ItemAddedIsRecordedSuite`,
adds an item at `orders`, waits for `billing` to record what `orders` published, and reads it back
through `orders`' call to `billing`.

```bash
sbt test            # every service's own tests, then the tests that cross services
sbt orders/test     # one service
sbt tests/test      # the crossings, and the context map held to the code
```

A scenario that crosses services, once it is met, is kept here as a test that starts the services
it needs. The tests module starts Scala services. A service in another language is checked against
the running project by the inspect skill instead, with the requests, what was published and the
trace that joins them.

## Release one service

A service tag releases one service. `orders/v1.4.2` checks `orders`, builds its image as
`orders:1.4.2`, pushes it and deploys it with `ankka services deploy`, and builds and deploys
nothing of any other service.

```bash
git tag orders/v1.4.2 && git push origin orders/v1.4.2
```

- Each service's version comes from its own tags: `sbt 'show orders/version'` is `1.4.2` at that
  tag and `1.4.2+<n>-<commit>-SNAPSHOT` past it, whatever any other service was released at.
- A tag naming no service of the project, such as `shipping/v1.0.0`, fails the job, saying the
  project has no service by that name, and releases nothing.
- A plain `v1.0.0` tag names no service and starts nothing.
- A service whose checks fail at the tagged commit is not deployed, and nothing else is.
- Pull requests run the checks of the services a change touched, and a change to `shared` runs every
  Scala service's checks.

The deploy job needs the same repository secrets as a single service, described in
[Deploy from GitHub Actions](../deploy/ci.md#a-project-of-several-services). Without `ANKKA_TOKEN` it
checks the service and deploys nothing.
