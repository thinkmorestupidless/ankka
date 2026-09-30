# {{name}}

An [ankka](https://docs.ankka.cloud/) service in Rust, built to a WebAssembly module. The stub domain is
an `Item` with a name and a count: one event sourced entity (`src/item_entity.rs`), one view for
listing (`src/item_rows.rs`), one HTTP endpoint (`src/api.rs`), and tests at two levels
(`tests/item.rs`). Replace the domain; keep the shape.

Needs: Rust (stable; `rust-toolchain.toml` adds the `wasm32-unknown-unknown` target), and Docker.

Your code makes the decisions — given this command and this state, what should happen. The ankka
**runtime** loads it as a module and owns everything stateful and distributed: the journal, the views,
HTTP, timers and the agent loop. The module reaches nothing but the runtime: no network, no file
system, no clock of its own.

## Test

```bash
cargo test                              # the entity, the view and the API, natively, in milliseconds
cargo test --features slow              # and the module in the real runtime, through Docker
```

The slow test builds the module, starts Postgres and the ankka runtime in Docker, and drives the
service over HTTP, restarting the runtime to show the state is durable.

## The runtime

Running the service locally and the slow test both start the ankka runtime in Docker:
`ghcr.io/thinkmorestupidless/ankka-sidecar`, public, at the same version as the crate this project
depends on, pulled the first time it is needed. The same image hosts a process in another language
beside it, or a module like this one inside it. `ANKKA_SIDECAR_IMAGE` names another image — one built
from a checkout of the ankka repository with `sbt sidecar/Docker/publishLocal`, say.

## Run locally

```bash
cargo module                            # target/wasm32-unknown-unknown/release/{{module_snake}}.wasm
docker compose up -d runtime            # Postgres and the runtime, with the module loaded

curl -XPOST localhost:9000/items/i1 -H 'content-type: application/json' -d '{"name":"Widget","count":2}'
curl localhost:9000/items/i1
curl localhost:9000/items/
```

After changing the code, `cargo module && docker compose restart runtime` loads the new module. The
listing comes from the view, which follows the journal: a new item appears in it a moment after the
write, not in the same instant.

The database schema comes out of the runtime image, so it always matches the runtime. Postgres applies
it only to an empty volume: after changing the runtime's version, `docker compose down -v` first.

## See what it is doing

```bash
ankka local console                     # http://localhost:9889
```

Lists every ankka service running on this machine, this one included, and for each: its registered
components, a form per HTTP route, and the trace of each request it served. It is local-only; for a
deployed service, `ankka services logs` is the equivalent.

## Work with a coding agent

This project carries ankka's documentation for the version it was made with, as Agent Skills in
`.claude/skills/`, and a `.mcp.json` that starts `ankka mcp`: the CLI's commands, the services running
on this machine and the same documentation, as tools. Claude Code reads both with no configuration; it
asks once before starting the project's MCP server. The `ankka` CLI must be on your `PATH`.

For Claude Desktop, or for Claude Code in every project rather than this one:

```bash
ankka mcp install --client desktop      # Claude Desktop; quit and reopen it afterwards
ankka mcp install                       # Claude Code, for you, in every project
```

## Build the image

```bash
cargo module
docker build -t {{name}}:latest .
```

The image holds only the module and a `cp`. On a platform, it runs once as an init container that
copies the module into the pod, and the pod's one container is the platform's runtime with it loaded.

## Deploy to an ankka platform

`service.json` is the descriptor `ankka services apply` takes. `"hosting": "wasm"` says the image
carries a module for the runtime to load, and `protocol` is the protocol this crate speaks; the
platform checks it against its own.

```bash
kind load docker-image {{name}}:latest --name ankka   # a local kind cluster; push to a registry otherwise
ankka services apply -f service.json
ankka services list                                  # Ready
ankka services expose {{name}}
```

**Before exposing:** `ItemApi` declares `Acl::AllowAll`. Exposure changes who can *reach* the endpoint,
not who is *allowed* to — an exposed `AllowAll` endpoint on a real platform is on the internet.

## Deploy from GitHub

This project carries two workflows. `.github/workflows/ci.yml` lints, builds the module and tests on
every push and pull request, and needs nothing configured. `.github/workflows/deploy.yml` builds the
module and the image, pushes it to this repository's GitHub Container Registry, and deploys — on a
version tag, or when you run it by hand. Until its secrets exist it declines to run rather than
failing, so the first push is green.

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

The crate's version is pinned in `Cargo.toml`, in both `[dependencies]` and `[dev-dependencies]`:
change both. The slow test's runtime follows the crate's version by itself; the runtime's tag in
`docker-compose.yml` is written once, so move it to the same version, then
`docker compose down -v && docker compose up -d runtime` for the local database. `protocol` in
`service.json` changes only when the crate's does.
