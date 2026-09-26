# Deploy from GitHub Actions

> Deploy a service from a GitHub workflow — create a deploy token, add three secrets, and use the ankka action to install and authenticate the CLI — plus the environment variables, exit codes and output formats any CI system needs.

Source: https://docs.ankka.cloud/deploy/ci/
A CI job deploys with the same CLI a person uses, holding its own credential. Nothing about the
installation has to be administered to set it up: an owner of an organization creates a **deploy
token**, puts it in a repository secret, and the job deploys.

A project created by `ankka init` already carries the workflows described here. This page is what they
do and how to point them at your installation — and how to do the same thing from any other CI system.

## What a generated project carries

| File | Runs on | What it does |
|---|---|---|
| `.github/workflows/ci.yml` | every push and pull request | `sbt test`. Needs no secrets, so it is green from the first push. |
| `.github/workflows/deploy.yml` | a `v*` tag, or run by hand | Builds the image, pushes it to the repository's GitHub Container Registry, and deploys. Declines to run when its secrets are absent, so a first push shows no failure. |

The snippets below are the pieces those files are built from, written out so they can be adapted. The
files themselves are not quoted here. In the template each GitHub expression carries an escape that
the project generator removes on expansion, so quoting the source would put that escape on the page
and mislead anyone copying it. What keeps the files correct is the template's own test, which expands
a project and asserts every expression survived.

## Create a deploy token

```bash
ankka organizations tokens create acme --label github-deploy
```

```text
Deploy token 'github-deploy' created. This is the only time the secret is shown.

  ankka_3f9a1c2e7b4d8f01_9c2e…a1f0

Expires 2026-12-24T10:00:00Z. Store it as a secret named ANKKA_TOKEN.
```

The secret is shown once and stored only as a one-way digest, so a lost token is replaced rather than
recovered. It authenticates as a **member** of that organization: it can apply, pause, resume,
restart, expose and delete services and read their logs and history, and it cannot invite members,
rename or delete the organization, or manage deploy tokens — including itself, so a leaked credential
cannot mint a replacement or revoke the one that would stop it.

A token expires after 90 days unless you say otherwise (`--expires-in 30d`, up to 365 days, or
`--never-expires`). See [Identity and machine accounts](../platform/identity.md#machine-accounts) for
the whole model, including the Keycloak-client alternative.

## Add three secrets

In the repository's **Settings → Secrets and variables → Actions**:

| Secret | Value | Where it comes from |
|---|---|---|
| `ANKKA_URL` | the control plane's address, `https://api.<base domain>` | `ankka config get url` |
| `ANKKA_TOKEN` | the deploy token's secret | the command above |
| `ANKKA_PROJECT` | the project to deploy into | `ankka projects list` |
| `ANKKA_CA` | optional: the installation's certificate authority, as PEM | `cat ~/.ankka/local-ca.crt`, for an installation whose certificate is not publicly trusted |

`ANKKA_URL` and `ANKKA_PROJECT` are not secret, but keeping all four in one place means one screen to
fill in rather than a workflow to edit.

## Use the action

```yaml
- uses: actions/setup-java@v4
  with: { distribution: temurin, java-version: "21" }
- uses: thinkmorestupidless/ankka-action@v1
  with:
    url: ${{ secrets.ANKKA_URL }}
    token: ${{ secrets.ANKKA_TOKEN }}
    project: ${{ secrets.ANKKA_PROJECT }}
    ca: ${{ secrets.ANKKA_CA }}
- run: |
    ankka services deploy orders ghcr.io/acme/orders:1.4.2
    ankka services get orders
```

The action installs the CLI, verifies it against the checksum published beside the release, and sets
the environment for the rest of the job — so every later step runs any `ankka` command with no further
setup. It does not wrap commands: `services deploy`, `services logs`, `projects list` and everything
else work exactly as they do in a terminal.

**Java 21 or later must be on `PATH`.** The CLI is a JVM application and the action installs no
runtime, because `actions/setup-java` is the standard, cached way to pick one. The action checks and
fails naming `setup-java` when there is none.

## Deploy the image the build just pushed

`ankka services deploy` takes the image on the command line and everything else from the descriptor:

```bash
ankka services deploy orders ghcr.io/acme/orders:1.4.2
```

`service.json` stays as it is in the repository — the file is never rewritten, and the one field that
is genuinely different on every build comes from the build. The command refuses when the descriptor
names a different service, so a workflow pointed at the wrong file stops rather than deploying
something unexpected. See [Deploy a service](deploy-a-service.md) and
[Build an image](images.md).

Tag each build. A cluster runs `imagePullPolicy: IfNotPresent`, so re-pushing the same tag does not
give a node the new image; a new tag is a new descriptor, a new generation and a rolling update. See
[Tag by version for anything real](images.md#tag-by-version-for-anything-real).

## The cluster has to be able to pull

The job pushes the image; the *cluster* pulls it, with no access to the job's credentials. A package on
a registry that requires authentication therefore needs a credential registered for the project, once:

```bash
ankka projects registry set checkout --server ghcr.io --username octocat --password-stdin
```

A GitHub Container Registry package is private by default, so either make the package public or
register a credential whose password is a personal access token with the `read:packages` scope. The
token the job itself pushes with is not usable here: it exists only while that job runs. See
[a private registry](images.md#a-private-registry).

A missing or wrong credential shows as `ImagePullBackOff` in the `detail` of:

```bash
ankka services get orders
```

## Exposure is not a build step

A service is reachable inside the cluster as soon as it is `Ready`. Giving it a public hostname is a
separate decision, taken once:

```bash
ankka services expose orders
```

Exposure is desired state and survives every later deploy, so no workflow needs to repeat it — and no
workflow decides on its own that a service should face the internet. See
[Expose a service](expose.md).

## Any other CI system

Nothing above needs GitHub beyond the action, which is a convenience. The CLI resolves each setting
from a flag, then an environment variable, then its configuration file, and a CI job normally has no
configuration file at all:

| Variable | Flag | Meaning |
|---|---|---|
| `ANKKA_URL` | `--url` | the control plane's address |
| `ANKKA_TOKEN` | `--token` | the token to present |
| `ANKKA_PROJECT` | `--project`, `-p` | the project to act on |
| `ANKKA_CA` | none | a PEM file of the certificate authority to trust |
| `ANKKA_CONFIG` | none | an alternative configuration file path |

Prefer `ANKKA_TOKEN` to `--token`: a flag's value appears in process listings and often in the job's
log. Installing the CLI by hand is a download and an unzip:

```bash
curl -sSLO "https://github.com/thinkmorestupidless/ankka/releases/download/v$ANKKA_VERSION/ankka-cli-$ANKKA_VERSION.zip"
curl -sSLO "https://github.com/thinkmorestupidless/ankka/releases/download/v$ANKKA_VERSION/ankka-cli-$ANKKA_VERSION.zip.sha256"
sha256sum --check "ankka-cli-$ANKKA_VERSION.zip.sha256"
unzip -q "ankka-cli-$ANKKA_VERSION.zip"
export PATH="$PWD/ankka-cli-$ANKKA_VERSION/bin:$PATH"
```

On macOS, `brew install thinkmorestupidless/tap/ankka` instead.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | the command succeeded |
| `1` | the command failed: refused, not authorized, or the control plane could not be reached |
| `2` | the command was misused: an unknown command, option or argument |

A refused command prints `error: <reason>` on standard error. A token that has expired or been revoked
is reported as `the token was rejected`; a valid token for an action its holder may not take is
`not permitted` — the two are different answers and the CLI keeps them apart. Use `-o json` for output
a script reads:

```bash
ankka services get orders -o json | jq -r .lifecycle
```

## Every change names its actor

A deploy from CI is attributed to the token that made it, distinguishably from the person who created
it:

```bash
ankka services history orders
```

See [Status and history](../operate/status-and-history.md).
