# Contract: the GitHub Action

**Feature**: [../spec.md](../spec.md) | **Plan**: [../plan.md](../plan.md) | **Research**: R9, R11

Referenced as `thinkmorestupidless/ankka-action@v1` (a major tag the release moves) or
`@v1.2.3`. Held in this repository at `action/`, pushed to its own repository on every tag.

## `action/action.yml`

```yaml
name: ankka
description: Install the ankka CLI, point it at a control plane and authenticate it for the rest of the job.
branding: { icon: upload-cloud, color: blue }
inputs:
  version:
    description: The ankka CLI version to install. Defaults to the version this action was released with.
    default: "0.0.0"          # rewritten by the release; ActionSuite pins the placeholder
  url:
    description: The control plane's address, e.g. https://api.example.com.
    required: true
  token:
    description: A deploy token (ankka organizations tokens create), or any token the control plane accepts.
    required: true
  project:
    description: The project later commands act on (ANKKA_PROJECT). Optional.
  ca:
    description: PEM text of a certificate authority to trust, for an installation whose certificate is not publicly trusted. Optional.
runs:
  using: composite
  steps:
    - name: Check for Java 21
      shell: bash
      run: ...             # java -version; major >= 21; else fail naming actions/setup-java
    - name: Install the ankka CLI
      shell: bash
      env: { ANKKA_VERSION: ${{ inputs.version }} }
      run: ...             # fetch zip + .sha256 from the release; sha256sum --check; unzip to $RUNNER_TEMP/ankka-cli; bin -> $GITHUB_PATH
    - name: Configure
      shell: bash
      env: { INPUT_URL: ..., INPUT_TOKEN: ..., INPUT_PROJECT: ..., INPUT_CA: ... }
      run: ...             # ANKKA_URL, ANKKA_PROJECT -> $GITHUB_ENV; ::add-mask:: then ANKKA_TOKEN -> $GITHUB_ENV; ca -> $RUNNER_TEMP/ankka-ca.crt + ANKKA_CA
    - name: Verify the login
      shell: bash
      run: ankka whoami -o json > /dev/null
```

## Guarantees

| # | guarantee | how |
|---|---|---|
| 1 | `ankka` is on `PATH` for every later step of the job, at exactly `inputs.version` | `$GITHUB_PATH`; `ankka version` is checked after unzip |
| 2 | later steps are authenticated and pointed at the control plane with no further setup | `ANKKA_URL`, `ANKKA_TOKEN`, `ANKKA_PROJECT`, `ANKKA_CA` in `$GITHUB_ENV` |
| 3 | the token never appears in the log | `::add-mask::` before it is written anywhere; never echoed; `whoami` output discarded |
| 4 | nothing outlives the job | everything under `$RUNNER_TEMP`, `$GITHUB_PATH`, `$GITHUB_ENV`; nothing in the checkout |
| 5 | the downloaded CLI is the one the release attached | `sha256sum --check` against `ankka-cli-<v>.zip.sha256`, which the release's `cli` job now attaches beside the zip |
| 6 | a runner with no Java 21 fails before downloading anything, naming `actions/setup-java` | step 1 |
| 7 | a version that does not exist fails naming the version and the release URL it tried | step 2, `curl --fail` |
| 8 | a missing, malformed or rejected token fails in the action, with the CLI's own message | step 4: `whoami` exits 1 with `the token was rejected: …` |
| 9 | any CLI command works afterwards, not only deployment | nothing here is command-specific (FR-016) |

## Failure messages (verbatim)

```text
::error::Java 21 or later is required on PATH and none was found. Add before this step:
  - uses: actions/setup-java@v4
    with: { distribution: temurin, java-version: "21" }
::error::ankka CLI 0.5.0 could not be fetched from https://github.com/thinkmorestupidless/ankka/releases/download/v0.5.0/ankka-cli-0.5.0.zip
::error::the downloaded ankka-cli-0.5.0.zip does not match its published checksum
::error::input 'token' is required: a deploy token from `ankka organizations tokens create`
```

## Release

A new `action` job in `.github/workflows/release.yml`, `needs: [publish, cli]`, `if:
startsWith(github.ref, 'refs/tags/v')`, checked out with `persist-credentials: false` like its
siblings:

1. `sed -i 's/default: "0.0.0"/default: "<version>"/' action/action.yml`; `grep` it back.
2. `git commit`, `git subtree split --prefix action -b ankka-action`.
3. `git push --force <token>@github.com/thinkmorestupidless/ankka-action.git ankka-action:main`.
4. Tag `v<version>` and move `v<major>` (`git tag -f v1`), push both with `--force`.

`ActionSuite` (`cli/src/test`) parses `action/action.yml` and asserts: the `version` default is
`0.0.0`; the download URL in the install step is
`https://github.com/thinkmorestupidless/ankka/releases/download/v${ANKKA_VERSION}/ankka-cli-${ANKKA_VERSION}.zip`
and the checksum file is that plus `.sha256`; every input above exists with the stated `required`.
A `README.md` in `action/` shows the three-line usage and the Java prerequisite.

## Usage, as the template's `deploy.yml` uses it

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
