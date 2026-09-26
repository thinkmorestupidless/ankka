# ankka-action

Installs the [ankka](https://github.com/thinkmorestupidless/ankka) CLI in a GitHub Actions job,
points it at a control plane, and authenticates it — so every later step in the job can run any
`ankka` command with no further setup.

```yaml
- uses: actions/setup-java@v4
  with: { distribution: temurin, java-version: "21" }
- uses: thinkmorestupidless/ankka-action@v1
  with:
    url: ${{ secrets.ANKKA_URL }}
    token: ${{ secrets.ANKKA_TOKEN }}
    project: ${{ secrets.ANKKA_PROJECT }}
- run: ankka services deploy orders ghcr.io/acme/orders:1.4.2
```

**Java 21 or later must be on `PATH`.** The CLI is a JVM application and this action installs no
runtime: `actions/setup-java` is the standard, cached way to pick one, and fetching a second here
would be slower and larger for no gain. The action checks first and fails naming `setup-java` when
there is none, rather than letting a later step produce a stack trace.

## Inputs

| Input | Required | Default | Meaning |
|---|---|---|---|
| `url` | yes | | The control plane's address, `https://api.<base domain>`. |
| `token` | yes | | A deploy token. Pass a secret, never a literal. |
| `version` | no | the release's own | Which CLI version to install. |
| `project` | no | | The project later commands act on (`ANKKA_PROJECT`). |
| `ca` | no | | PEM text of a certificate authority to trust, for an installation whose certificate is not publicly trusted. |

## Getting a token

An owner of an organization creates one; nobody else needs to be involved:

```bash
ankka organizations tokens create acme --label github-deploy
```

The secret is shown once. Store it as a repository secret named `ANKKA_TOKEN`. It authenticates as a
**member** of that organization — it can deploy, pause, restart and expose services, and it cannot
invite members, delete the organization or manage deploy tokens, including itself. Revoke it with
`ankka organizations tokens revoke acme <id>`, which takes effect within a second everywhere.

See [Identity and machine accounts](https://github.com/thinkmorestupidless/ankka/blob/main/docs/platform/identity.md).

## What it does not do

- **It does not build or push an image.** That is `docker/build-push-action` or your project's own
  build, and wrapping a well-understood step in an ankka-specific one would only hide it.
- **It does not wrap CLI commands.** It puts `ankka` on `PATH` and gets out of the way, so a job can
  run `services deploy`, `services get`, `services logs`, `projects list` or anything else without
  this action having grown an input per command.
- **It does not expose a service.** Making a service publicly reachable is a decision its owner takes
  once, with `ankka services expose`, and it survives every later deploy.

## What it guarantees

- The CLI on `PATH` at exactly the version asked for, verified against the checksum published beside
  the release's zip.
- `ANKKA_URL`, `ANKKA_TOKEN`, and where given `ANKKA_PROJECT` and `ANKKA_CA`, set for the rest of the
  job and nowhere else — nothing is written into the checkout, and `GITHUB_ENV` goes with the runner.
- The token masked in the log before it is written anywhere.
- A failure that names its cause: no Java, no such version, a checksum mismatch, a missing input, or
  a credential the control plane refused.

## Versioning

`@v1` follows the latest 1.x release; `@v1.2.3` pins one. The action is released from the ankka
repository, so its version is the platform version it was tested against, and that is the CLI version
it installs by default.

## Licence

[Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0), as ankka is.
