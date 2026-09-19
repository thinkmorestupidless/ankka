# Contract: The Template and `nakka init`

**Satisfies**: FR-012 to FR-018

## Expanding

```bash
sbt new thinkmorestupidless/nakka.g8 --name=orders
# or, with the CLI on PATH:
nakka init orders
# or, from a checkout:
sbt new file:///path/to/nakka/nakka.g8 --name=orders
```

Parameters: `name` (the service name; also the image and descriptor name; validated), `package`
(default `com.example.<name>`), `nakka_version` (default: the template's release version).

## What comes out

```
orders/
├── build.sbt  project/  docker-compose.yml  service.json  README.md
└── src/main/scala/com/example/orders/{Main,domain/Item,application/ItemEntity,application/ItemRows,api/ItemEndpoint}.scala
    src/main/resources/{application.conf,logback.xml}
    src/test/scala/com/example/orders/{ItemEntitySuite,ItemHttpSuite,ItemIntegrationSuite}.scala
```

The stub domain: an `Item` with a name and a count. `POST /items/{id}` with `{"name":…,"count":…}`,
`GET /items/{id}`, `GET /items`. The endpoint declares `acl = Acl.AllowAll` with a comment saying
that is a decision, and that `expose` puts it on the internet.

## The README's commands, in order — each executed by this feature's proof

```bash
sbt test                          # entity, endpoint and integration suites (Docker for the last)
sbt schema                        # the platform's DDL, extracted from nakka-runtime into target/ddl
docker compose up -d              # Postgres, initialised from target/ddl
sbt run                           # http://localhost:9000
curl -XPOST localhost:9000/items/i1 -H 'content-type: application/json' -d '{"name":"Widget","count":2}'
curl localhost:9000/items/i1

sbt Docker/publishLocal           # orders:<version> and orders:latest
kind load docker-image orders:latest --name nakka      # or push, once there is a registry
nakka services apply -f service.json
nakka services list
nakka services expose orders
curl --cacert ~/.nakka/local-ca.crt https://orders-<project>.127.0.0.1.sslip.io:8443/items/i1
```

## `service.json`

```json
{
  "name": "orders",
  "service": {
    "image": "orders:latest",
    "runtime": "0.2.0"
  }
}
```

`runtime` and `build.sbt`'s `nakkaVersion` are written from one template parameter; the README's
"upgrading nakka" section says to change both.

## `nakka init`

| Situation | Behaviour |
|---|---|
| `sbt` not on `PATH` | exit 1: `nakka init needs sbt on PATH; install it from https://www.scala-sbt.org/` |
| target directory exists and is not empty | exit 1, naming it |
| invalid name | exit 1 with the service-name rule — the same message `nakka services apply` gives |
| success | prints the expanded path and the first three README commands |
