# Contract: the template's workflows

**Feature**: [../spec.md](../spec.md) | **Plan**: [../plan.md](../plan.md) | **Research**: R10

Two files under `ankka.g8/src/main/g8/.github/workflows/`. Giter8 reads `$` as template syntax, so
every GitHub expression is written `\${{ … }}` in the template and becomes `${{ … }}` on expansion;
`TemplateSuite` asserts that on the expanded files (FR-023). Below is what a generated project
receives.

## `.github/workflows/ci.yml`

```yaml
name: ci
on:
  push:
  pull_request:
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          cache: sbt
      - uses: sbt/setup-sbt@v1
      # The test kit starts a throwaway Postgres in Docker, which the runner has.
      - run: sbt test
```

No secrets; green on the first push (FR-019, US3 scenario 1).

## `.github/workflows/deploy.yml`

```yaml
name: deploy
on:
  push:
    tags: ["v*"]
  workflow_dispatch:
jobs:
  # Secrets cannot be tested directly in a job-level `if`, so one job decides and the next obeys.
  check:
    runs-on: ubuntu-latest
    outputs:
      deploy: ${{ steps.secrets.outputs.deploy }}
    steps:
      - id: secrets
        env:
          TOKEN: ${{ secrets.ANKKA_TOKEN }}
        run: |
          if [ -n "$TOKEN" ]; then
            echo "deploy=true" >> "$GITHUB_OUTPUT"
          else
            echo "deploy=false" >> "$GITHUB_OUTPUT"
            echo "::notice::No ANKKA_TOKEN secret is configured, so nothing is deployed. See README.md, 'Deploy from GitHub'."
          fi
  deploy:
    needs: check
    if: needs.check.outputs.deploy == 'true'
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write          # push the image to this repository's GitHub Container Registry
    env:
      SERVICE_VERSION: ${{ github.ref_type == 'tag' && github.ref_name || github.sha }}
      DOCKER_REPOSITORY: ghcr.io/${{ github.repository_owner }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          cache: sbt
      - uses: sbt/setup-sbt@v1
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - name: Build and push the image
        run: |
          export SERVICE_VERSION="${SERVICE_VERSION#v}"
          sbt Docker/publish
          echo "IMAGE=$DOCKER_REPOSITORY/orders:$SERVICE_VERSION" >> "$GITHUB_ENV"
      - uses: thinkmorestupidless/ankka-action@v1
        with:
          url: ${{ secrets.ANKKA_URL }}
          token: ${{ secrets.ANKKA_TOKEN }}
          project: ${{ secrets.ANKKA_PROJECT }}
          ca: ${{ secrets.ANKKA_CA }}
      - name: Deploy
        run: |
          ankka services deploy orders "$IMAGE"
          ankka services get orders
```

`orders` above is `$name;format="norm"$` in the template — the same value as the descriptor's `name`
and the build's `serviceName`, so `services deploy` cannot be asked for a name the descriptor does not
carry. On `workflow_dispatch` from a branch the version is the commit SHA, so a manual run deploys an
image tagged by the commit it ran on (US3 scenario 3).

## Changes to the template's build

```scala
// build.sbt
// The version a CI build gives the image: the tag being released, or a SHA. Locally, sbt's default.
version := sys.env.getOrElse("SERVICE_VERSION", "0.1.0-SNAPSHOT"),
// Unset, the image is tagged unqualified for `kind load`; set, it is tagged for that registry and
// `sbt Docker/publish` pushes there (FR-024).
Docker / dockerRepository := sys.env.get("DOCKER_REPOSITORY"),
```

`Docker / version := version.value.replace('+', '-')` stays, so a SHA or a snapshot is a valid tag.

## The generated README's new section

**Deploy from GitHub.** Three repository secrets, each with the command that produces it:

| secret | value | from |
|---|---|---|
| `ANKKA_URL` | the control plane's address | `ankka config get url` |
| `ANKKA_TOKEN` | a deploy token | `ankka organizations tokens create <org> --label github` |
| `ANKKA_PROJECT` | the project id | `ankka projects list` |
| `ANKKA_CA` (optional) | the installation's CA, PEM | `cat ~/.ankka/local-ca.crt` for a local platform |

Then: make the package public, or register the registry for the project —
`ankka projects registry set <project> --server ghcr.io --username <github user> --password <a
read:packages PAT>` — and tag a release.

The workflow deploys; it does not publish. A service is reachable inside the cluster as soon as it is
`Ready`. To give it a public hostname, run `ankka services expose <name>` once — exposure is desired
state that survives every later deploy, so no workflow needs to repeat it, and no workflow decides on
its own that a service should face the internet.

## Tests that pin this

- `TemplateSuite`: a new case reads the two expanded workflow files, asserts they parse as YAML (a
  minimal check: every line that has `${{` closes it, and no `\${{` survives), contain
  `thinkmorestupidless/ankka-action@`, and name the project's service in the `deploy` step. The
  existing cases (own tests pass, image builds under its name) keep running, which proves the build
  changes.
- The manual tier in `quickstart.md`: a real repository, one push with no secrets (green `ci`, skipped
  `deploy`), then the secrets and a tag.
