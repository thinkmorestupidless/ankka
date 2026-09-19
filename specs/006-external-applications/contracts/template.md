# Contract: The Template and `ankka init`

**Satisfies**: FR-012 to FR-018

## Expanding

```bash
sbt new thinkmorestupidless/ankka.g8 --name=orders
# or, with the CLI on PATH:
ankka init orders
# or, from a checkout:
sbt new file:///path/to/ankka/ankka.g8 --name=orders
```

Parameters: `name` (the service name; also the image and descriptor name; validated), `package`
(default `com.example.<name>`), `ankka_version` (default: the template's release version).

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
sbt schema                        # the platform's DDL, extracted from ankka-runtime into target/ddl
docker compose up -d              # Postgres, initialised from target/ddl
sbt run                           # http://localhost:9000
curl -XPOST localhost:9000/items/i1 -H 'content-type: application/json' -d '{"name":"Widget","count":2}'
curl localhost:9000/items/i1

sbt Docker/publishLocal           # orders:<version> and orders:latest
kind load docker-image orders:latest --name ankka      # or push, once there is a registry
ankka services apply -f service.json
ankka services list
ankka services expose orders
curl --cacert ~/.ankka/local-ca.crt https://orders-<project>.127.0.0.1.sslip.io:8443/items/i1
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

`runtime` and `build.sbt`'s `ankkaVersion` are written from one template parameter; the README's
"upgrading ankka" section says to change both.

## `ankka init`

| Situation | Behaviour |
|---|---|
| `sbt` not on `PATH` | exit 1: `ankka init needs sbt on PATH; install it from https://www.scala-sbt.org/` |
| target directory exists and is not empty | exit 1, naming it |
| invalid name | exit 1 with the service-name rule — the same message `ankka services apply` gives |
| success | prints the expanded path and the first three README commands |
