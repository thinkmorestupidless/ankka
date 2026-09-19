# $name;format="norm"$

A [nakka](https://github.com/thinkmorestupidless/nakka) service. The stub domain is an `Item` with a
name and a count: one event sourced entity (`application/ItemEntity.scala`), one HTTP endpoint
(`api/ItemEndpoint.scala`), one view for listing (`application/ItemRows.scala`), and a test at each
level. Replace the domain; keep the shape.

Needs: JDK 21, sbt, Docker.

## Test

```bash
sbt test
```

`ItemEntitySuite` runs with no runtime at all; the other two start a throwaway Postgres in Docker.

## Run locally

```bash
sbt schema                # nakka's database schema, extracted from the nakka-runtime artifact into target/ddl
docker compose up -d      # Postgres, initialised from target/ddl
sbt run                   # http://localhost:9000

curl -XPOST localhost:9000/items/i1 -H 'content-type: application/json' -d '{"name":"Widget","count":2}'
curl localhost:9000/items/i1
curl localhost:9000/items/
```

Postgres applies its init directory only to an empty volume: after `sbt schema` changes anything,
`docker compose down -v` first.

## Build the image

```bash
sbt Docker/publishLocal   # $name;format="norm"$:<version> and $name;format="norm"$:latest, in the local Docker daemon
```

## Deploy to a nakka platform

`service.json` is the descriptor `nakka services apply` takes. It names the image above and the
nakka version this service was built against; the platform checks that version against its own.

```bash
kind load docker-image $name;format="norm"$:latest --name nakka   # a local kind cluster; push to a registry otherwise
nakka services apply -f service.json
nakka services list                              # Ready
nakka services expose $name;format="norm"$
curl --cacert ~/.nakka/local-ca.crt https://$name;format="norm"$-<project>.127.0.0.1.sslip.io:8443/items/i1
```

The `curl` shown is for a local cluster deployed with nakka's `deploy-local.sh`, whose root
certificate is at `~/.nakka/local-ca.crt` and whose base domain is `127.0.0.1.sslip.io`; on any
other platform, use the URL `nakka services expose` prints and that platform's root.

**Before exposing:** `ItemEndpoint` declares `acl = Acl.AllowAll`. Exposure changes who can *reach*
the endpoint, not who is *allowed* to — an exposed `AllowAll` endpoint on a real platform is on the
internet.

## Upgrading nakka

The nakka version lives in two places that must move together: `nakkaVersion` in `build.sbt` (the
artifacts this build resolves) and `runtime` in `service.json` (what the platform checks). Then
`sbt schema` and `docker compose down -v && docker compose up -d` for the local database.
