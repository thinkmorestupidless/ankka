# Contract: the generated project's workflows and scripts

All under `.github/workflows/` and `scripts/` of a generated project. Written with `${{ }}`
unescaped (the CLI's `Scaffold` leaves it alone). `ProjectTemplateSuite` asserts the shape:
balanced `${{`/`}}`, the triggers, the expressions named below, `secrets.` in `deploy.yml` only.

## `scripts/service-language.sh <services/dir>`

Prints one word by the directory's build files: `pyproject.toml` → `python`; `package.json` with
`"ankka"` in dependencies → `typescript`, with the web template's `vite` → `web`; `Cargo.toml` →
`rust`; `go.mod` → `go`; `pom.xml` → `java`; `build.gradle.kts` → `kotlin`; none of those and
`src/main/scala` → `scala`. Exit 1 and `cannot tell the language of <dir>` otherwise.

## `scripts/changed-services.sh <base> <head>`

`git diff --name-only <base> <head>` mapped as data-model.md's table; prints
`{"services":[{"service":"orders","language":"scala"}],"tests":true}` on one line. With no
arguments, lists every service (what `workflow_dispatch` uses). Exit 0 always when git answers.

## `ci.yml`

```yaml
on: [push, pull_request]
jobs:
  changes:         # fetch-depth 0; outputs matrix (the services array) and tests (the flag)
    runs: scripts/changed-services.sh "${{ github.event.pull_request.base.sha || github.event.before }}" "${{ github.sha }}"
  checks:          # needs changes; if the matrix is not empty
    strategy: { matrix: { include: ${{ fromJson(needs.changes.outputs.matrix) }} }
    uses: ./.github/workflows/service-checks.yml
    with: { service: ${{ matrix.service }}, language: ${{ matrix.language }} }
  tests:           # needs changes; if tests == 'true'
    steps: checkout, setup-java 21 + setup-sbt, `sbt tests/test`
  commits:         # as the single templates: .githooks/check-commits on pull requests
```

No `secrets.` anywhere in it.

## `service-checks.yml` (reusable)

```yaml
on:
  workflow_call:
    inputs: { service: {type: string, required: true}, language: {type: string, required: true} }
jobs:
  checks:
    steps:
      - checkout
      - if: inputs.language == 'scala'      → setup-java 21, setup-sbt, `sbt "${{ inputs.service }}/test"`
      - if: inputs.language == 'python'     → setup-uv, `uv sync`, `uv run mypy`, `uv run pytest -q -rs`   (working-directory services/<service>)
      - if: inputs.language == 'typescript' || 'web' → setup-node 24, `npm ci`, `npm run typecheck`, `npm test` (+ `npm run build` for web)
      - if: inputs.language == 'rust'       → rust-toolchain (wasm32), `cargo fmt --check`, `cargo clippy …`, `cargo module`, `cargo test --features slow`
      - if: inputs.language == 'go'         → setup-go, `go mod verify`, `go vet ./...`, `ANKKA_SLOW=1 go test ./...`
      - if: inputs.language == 'java'       → setup-java (maven cache), `./mvnw -B verify`
      - if: inputs.language == 'kotlin'     → setup-java (gradle cache), `./gradlew --no-daemon test`
```

Each non-Scala group runs with `working-directory: services/${{ inputs.service }}`. The steps are
the single templates' `ci.yml` steps, moved.

## `deploy.yml`

```yaml
on:
  push: { tags: ['*/v*'] }
  workflow_dispatch: { inputs: { service: {required: true}, version: {required: true} } }
jobs:
  resolve:     # SERVICE and VERSION from the tag (${GITHUB_REF_NAME%%/v*}, ${GITHUB_REF_NAME#*/v}) or the inputs;
               # fail unless services/$SERVICE/service.json exists: "::error::<project> has no service <service>"
               # LANGUAGE from scripts/service-language.sh; deploy=true when secrets.ANKKA_TOKEN is set (else ::notice, as today)
  checks:      # needs resolve; uses service-checks.yml with the one service   ← a failing test deploys nothing
  deploy:      # needs [resolve, checks]; if deploy == 'true'; permissions contents: read, packages: write
    env: { SERVICE_VERSION: <version>, DOCKER_REPOSITORY: ghcr.io/${{ github.repository_owner }}, ANKKA_IMAGE_PLATFORMS: linux/amd64,linux/arm64 }
    steps:
      - checkout; docker/login-action (ghcr, GITHUB_TOKEN); setup-qemu; setup-buildx
      - build and push, by language:
          scala:  setup-java, setup-sbt; `sbt "$SERVICE/Docker/publish"`; IMAGE=$DOCKER_REPOSITORY/$SERVICE:$SERVICE_VERSION
          python|typescript|web|go: `docker build -t $IMAGE services/$SERVICE && docker push $IMAGE`   (single-arch, as today)
          rust:   rust toolchain, `cargo module`, then as python
          java:   `./mvnw -B -DskipTests package jib:build -Dimage=$IMAGE -Djib.from.platforms=linux/amd64,linux/arm64`
          kotlin: `./gradlew --no-daemon installDist`; `docker buildx build --platform … --push -t $IMAGE .`
      - uses: thinkmorestupidless/ankka-action@v1 with url, token, project, ca from the four ANKKA_* secrets
      - `ankka services deploy "$SERVICE" "$IMAGE" -f "services/$SERVICE/service.json"`; `ankka services get "$SERVICE"`
```

Secrets read, as today: `ANKKA_TOKEN`, `ANKKA_URL`, `ANKKA_PROJECT`, `ANKKA_CA`, `GITHUB_TOKEN`.
Nothing of any other service is built, pushed or deployed. A plain `v1.0.0` tag matches no trigger.

## `scripts/run.sh <service>` and `scripts/databases.sh`

`databases.sh`: for each `services/*/` with a `service.json`, `psql` on the compose Postgres:
`CREATE DATABASE "<name>"` when `pg_database` lacks it, then the DDL (`target/ddl/*.sql` after
`sbt schema`, or the sidecar image's `/opt/docker/ddl` for a process-hosted service, as the
single templates' compose `schema` service copies it) with `psql -v ON_ERROR_STOP=1 -d <name>`;
idempotent (`CREATE TABLE IF NOT EXISTS` throughout the DDL). Exit 1 naming the service on a
failure.

`run.sh <service>`: refuses a name not under `services/`; runs `databases.sh`; when
`services/<service>/docker-compose.yml` exists, runs `docker compose -f` on it `up -d` (the
service's sidecar or runtime and its schema copy); exports
`ANKKA_DB_NAME=<service>`, `ANKKA_HTTP_PORT=<port from service.json>` (`jq` or `sed`),
`ANKKA_SERVICE_NAME=<service>`, `ANKKA_KAFKA_BOOTSTRAP_SERVERS=localhost:9092`; execs the
language's run command in the right directory (`sbt "<service>/run"`; `uv run <module>`;
`npm start`; `cargo module` then the compose `runtime` profile; `go run .`; `./mvnw exec:java`;
`./gradlew run`).
