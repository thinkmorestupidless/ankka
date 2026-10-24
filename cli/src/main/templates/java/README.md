# {{name}}

An ankka service in Java: a shopping cart entity and the HTTP endpoint in front of it. The runtime
hosts the components in this JVM; there is no sidecar.

## Build and test

Needs a JDK (21 or later) and Docker. Maven is not needed: `./mvnw` fetches the version this
project names.

```bash
./mvnw test        # the entity on its own, then the whole service against a throwaway database
```

`ShoppingCartTest` drives the entity with `EventSourcedTestKit`, with no runtime or database.
`ServiceTest` starts the whole service with `AnkkaTestKit` against a Postgres of its own, calls it
over HTTP and restarts it, so what it reads back was recorded rather than remembered.

## Run it

```bash
./mvnw generate-resources   # the database schema, from the ankka runtime, into target/ddl
docker compose up -d        # Postgres
./mvnw exec:java            # HTTP on :9000

curl -X POST localhost:9000/carts/c1/items -H 'Content-Type: application/json' \
  -d '{"productId":"p1","name":"Pen","quantity":2}'
curl localhost:9000/carts/c1
```

## Where things are

- `ShoppingCart`: an event sourced entity. `@Command` and `@Query` name each handler on the wire,
  apart from the method's name, so renaming a method never breaks a caller.
- `Cart`, `LineItem`, `CartEvent`: records and a sealed interface, stored as JSON.
- `CartEndpoint`: the routes; it calls the entity by method reference.
- `Main`: the service, every component and endpoint registered by name.

## Deploy

`./mvnw package jib:dockerBuild` builds the image `{{name}}:local` into Docker; `service.json` is
its descriptor. On a `v*` tag, `.github/workflows/deploy.yml` pushes the image to this repository's
GitHub Container Registry and deploys it.

### Deploy from GitHub

Set the repository secrets `ANKKA_URL`, `ANKKA_TOKEN` and `ANKKA_PROJECT` (and `ANKKA_CA` for an
installation with a private certificate authority). Until `ANKKA_TOKEN` is set the deploy workflow
declines rather than failing.

## Commits and pull requests

Commit messages are [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/):
`<type>(<scope>): <description>`, as in `feat(orders): reserve stock when an order is placed`, with
`!` and a `BREAKING CHANGE:` footer when a change breaks an event, a wire format or an API.
`.githooks/commit-msg` refuses anything else and says what to write; enable it once per clone:

```bash
git config core.hooksPath .githooks
```

CI runs the same check over every commit of a pull request, and over its title: the title is the
same kind of header, because the squash merge makes it the commit on `main`. The description follows
`.github/pull_request_template.md` (why, what changed, what is left out, how it is tested, risk and
rollback) in under about three hundred words; the detail goes in a design note or the docs, linked.
