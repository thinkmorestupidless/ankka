# {{name}}

An ankka service in Kotlin: a shopping cart entity, a view of every cart, and the HTTP endpoint in
front of them. The runtime hosts the components in this JVM; there is no sidecar.

## Build and test

Needs a JDK (21 or later) and Docker. Gradle is not needed: `./gradlew` fetches the version this
project names, and a JDK 21 to compile with if the machine has none.

```bash
./gradlew test     # the entity and the view on their own, then the whole service against a throwaway database
```

`ShoppingCartTest` drives the entity with its test kit and `CartViewTest` the view with its own, with
no runtime or database. `ServiceTest` starts the whole service against a Postgres of its own, calls
it over HTTP and restarts it, so what it reads back was recorded rather than remembered.

## Run it

```bash
./gradlew schema        # the database schema, from the ankka runtime, into build/ddl
docker compose up -d    # Postgres
./gradlew run           # HTTP on :9000

curl -X POST localhost:9000/carts/c1/items -H 'Content-Type: application/json' \
  -d '{"productId":"p1","name":"Pen","quantity":2}'
curl localhost:9000/carts/c1
curl localhost:9000/carts/c1/row
```

## Where things are

- `ShoppingCart`: an event sourced entity. `@Command` and `@Query` name each handler on the wire,
  apart from the function's name, so renaming a function never breaks a caller. `Cart`, `LineItem`
  and `CartEvent` beside it are data classes and a sealed interface, stored as JSON.
- `CartRows`: a view with a row per cart, kept from the cart's events.
- `CartEndpoint`: the routes, declared in code; each calls the entity by function reference and
  suspends while it waits.
- `Main.kt`: the service, every component and endpoint registered by name.

The Java SDK says which values may be null, and the build reads that strictly: a null given where it
takes a value does not compile.

## Deploy

`./gradlew installDist && docker build -t {{name}}:local .` builds the image into Docker;
`service.json` is its descriptor. On a `v*` tag, `.github/workflows/deploy.yml` pushes the image to
this repository's GitHub Container Registry and deploys it.

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
