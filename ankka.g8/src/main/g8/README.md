# $name;format="norm"$

A [ankka](https://github.com/thinkmorestupidless/ankka) service. The stub domain is an `Item` with a
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
sbt schema                # ankka's database schema, extracted from the ankka-runtime artifact into target/ddl
docker compose up -d      # Postgres, initialised from target/ddl
sbt run                   # http://localhost:9000

curl -XPOST localhost:9000/items/i1 -H 'content-type: application/json' -d '{"name":"Widget","count":2}'
curl localhost:9000/items/i1
curl localhost:9000/items/
```

Postgres applies its init directory only to an empty volume: after `sbt schema` changes anything,
`docker compose down -v` first.

## See what it is doing

```bash
ankka local console       # http://localhost:9889
```

Lists every ankka service running on this machine, this one included, and for each: its registered
components, a form per HTTP route so you can send a request without leaving the page, and the trace
of each request it served — which components it went through, how long each took, and how much of
the elapsed time the platform cannot account for. That last figure is usually the interesting one.

The console also reads an entity's state, through the queries a component declares for itself
(`get-item` on `ItemEntity`). It will not run a command — `add-item` is refused — because
`query` accepts only a `ReadOnlyEffect`, so
"cannot persist" is the compiler's guarantee rather than a rule the console enforces.

It is local-only — loopback, no credential. For a service deployed to a platform, `ankka services
logs` is the equivalent.

## Build the image

```bash
sbt Docker/publishLocal   # $name;format="norm"$:<version> and $name;format="norm"$:latest, in the local Docker daemon
```

## Deploy to an ankka platform

`service.json` is the descriptor `ankka services apply` takes. It names the image above and the
ankka version this service was built against; the platform checks that version against its own.

```bash
kind load docker-image $name;format="norm"$:latest --name ankka   # a local kind cluster; push to a registry otherwise
ankka services apply -f service.json
ankka services list                              # Ready
ankka services expose $name;format="norm"$
curl --cacert ~/.ankka/local-ca.crt https://$name;format="norm"$-<project>.127.0.0.1.sslip.io:8443/items/i1
```

The `curl` shown is for a local cluster deployed with ankka's `deploy-local.sh`, whose root
certificate is at `~/.ankka/local-ca.crt` and whose base domain is `127.0.0.1.sslip.io`; on any
other platform, use the URL `ankka services expose` prints and that platform's root.

**Before exposing:** `ItemEndpoint` declares `acl = Acl.AllowAll`. Exposure changes who can *reach*
the endpoint, not who is *allowed* to — an exposed `AllowAll` endpoint on a real platform is on the
internet.

## Upgrading ankka

The ankka version lives in two places that must move together: `ankkaVersion` in `build.sbt` (the
artifacts this build resolves) and `runtime` in `service.json` (what the platform checks). Then
`sbt schema` and `docker compose down -v && docker compose up -d` for the local database.
