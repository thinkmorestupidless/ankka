# {{name}}

An [ankka](https://docs.ankka.cloud/) service in Python. The stub domain is an `Item` with a name and a
count: one event sourced entity (`src/{{module}}/item_entity.py`), one view for listing
(`item_rows.py`), one HTTP endpoint (`api.py`), and tests at two levels. Replace the domain; keep the
shape.

Needs: [uv](https://docs.astral.sh/uv/), Python 3.12, Docker, and the ankka sidecar image.

Your process makes the decisions — given this command and this state, what should happen. The ankka
**sidecar** beside it owns everything stateful and distributed: the journal, the views, HTTP, timers
and the agent loop. The two talk over gRPC on loopback, and the SDK hides that entirely.

## The sidecar image

Running the service locally and the integration tests both need the sidecar image, named by
`ANKKA_SIDECAR_IMAGE` or `ankka-sidecar:latest`. It is built from the ankka repository:

```bash
sbt sidecar/Docker/publishLocal         # in a checkout of github.com/thinkmorestupidless/ankka
```

## Test

```bash
uv sync
uv run pytest -q -rs
uv run mypy
```

`tests/test_item.py` runs the entity and the view with no sidecar at all, in milliseconds.
`tests/test_integration.py` starts Postgres and the sidecar in Docker and drives the service over
HTTP; it is skipped, with the reason, when the sidecar image is not on this machine.

## Run locally

```bash
docker compose up -d                    # Postgres and the sidecar
uv run python -m {{module}}.main        # your process, on port 9010, where the sidecar finds it

curl -XPOST localhost:9000/items/i1 -H 'content-type: application/json' -d '{"name":"Widget","count":2}'
curl localhost:9000/items/i1
curl localhost:9000/items/
```

The listing comes from the view, which follows the journal: a new item appears in it a moment after
the write, not in the same instant.

The database schema comes out of the sidecar image, so it always matches the sidecar. Postgres applies
it only to an empty volume: after changing the sidecar's version, `docker compose down -v` first.

## See what it is doing

```bash
ankka local console                     # http://localhost:9889
```

Lists every ankka service running on this machine, this one included, and for each: its registered
components, a form per HTTP route, and the trace of each request it served — which components it went
through and how long each took. It reads an entity's state through the queries it declares (`get-item`)
and refuses to run a command. It is local-only; for a deployed service, `ankka services logs` is the
equivalent.

## Build the image

```bash
docker build -t {{name}}:latest .
```

The image holds only your process and the SDK. On a platform, the sidecar runs beside it in the same
pod.

## Deploy to an ankka platform

`service.json` is the descriptor `ankka services apply` takes. `"hosting": "process"` says the image is
a process for the sidecar to host, and `protocol` is the sidecar protocol this SDK speaks; the platform
checks it against its own.

```bash
kind load docker-image {{name}}:latest --name ankka   # a local kind cluster; push to a registry otherwise
ankka services apply -f service.json
ankka services list                                  # Ready
ankka services expose {{name}}
```

**Before exposing:** `ItemEndpoint` declares `acl = Acl.ALLOW_ALL`. Exposure changes who can *reach*
the endpoint, not who is *allowed* to — an exposed `ALLOW_ALL` endpoint on a real platform is on the
internet.

## Deploy from GitHub

This project carries two workflows. `.github/workflows/ci.yml` type-checks and tests on every push and
pull request, and needs nothing configured. `.github/workflows/deploy.yml` builds the image, pushes it
to this repository's GitHub Container Registry, and deploys — on a version tag, or when you run it by
hand. Until its secrets exist it declines to run rather than failing, so the first push is green.

Four repository secrets, under **Settings → Secrets and variables → Actions**:

| Secret | Value | Where it comes from |
|---|---|---|
| `ANKKA_URL` | the control plane's address | `ankka config get url` |
| `ANKKA_TOKEN` | a deploy token | `ankka organizations tokens create <org> --label github` |
| `ANKKA_PROJECT` | the project to deploy into | `ankka projects list` |
| `ANKKA_CA` | optional: the platform's certificate authority, as PEM | `cat ~/.ankka/local-ca.crt`, for a platform whose certificate is not publicly trusted |

A deploy token acts as a **member** of its organization: it can deploy, pause and restart services, and
it cannot manage members or other tokens. `ankka organizations tokens revoke` stops it.

The cluster has to be able to *pull* the image. A GHCR package is private by default, so either make the
package public, or register the registry for the project once:

```bash
ankka projects registry set <project> --server ghcr.io --username <github user> --password <a read:packages token>
```

Then tag a release:

```bash
git tag v0.1.0 && git push --tags
```

The workflow deploys to `Ready`. It does not expose the service: that is your decision, taken once with
`ankka services expose {{name}}`, and it survives every later deploy.

## Upgrading ankka

The SDK version is pinned in `pyproject.toml`, twice (the runtime dependency and the `testkit` extra in
the dev group): move both together, then `uv sync`. Use a sidecar image of the same version, and
`docker compose down -v && docker compose up -d` for the local database. `protocol` in `service.json`
changes only when the SDK's does.
