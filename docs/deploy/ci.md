---
title: Deploy from CI
description: Give a CI job its own identity as a Keycloak client, obtain a token with the client-credentials grant, and run the ankka CLI non-interactively with environment variables and meaningful exit codes.
kind: guide
related: [platform/identity.md, deploy/deploy-a-service.md, reference/cli.md]
---

# Deploy from CI

A CI job deploys with the same CLI a person uses, with its own identity. It authenticates as a Keycloak
client using the client-credentials grant, passes the resulting token to the CLI through `ANKKA_TOKEN`,
and needs no browser, no prompt and no saved login. A machine identity is another principal in the
installation's identity provider, so it is invited to an organization, authorized and audited exactly as
a person is.

## Create the client

In the identity provider's console at `https://auth.<base domain>/admin/`, in the `ankka` realm, create a
client for the job:

1. **Client authentication on**, making it a confidential client with a secret.
2. **Service accounts enabled**, which is what allows the client-credentials grant.
3. **Standard flow off**; a CI job never signs in through a browser.
4. Assign the **`ankka-controlplane`** client scope as a default scope. It puts the control plane's
   audience and the identity claims the control plane reads on every token the client obtains. A token
   without it is refused.

Copy the client's secret into the CI system's secret store.

To give the job access, add it to an organization. Invitations are by email, so give the client's
service-account user an email address in the console and mark it verified, then invite that address:

```bash
ankka organizations members add acme --email ci-deployer@example.com
```

The invitation becomes a membership the first time the job calls the control plane. A member can apply,
pause, restart, expose and delete services in the organization's projects. See
[Organizations, projects and members](../platform/organizations.md).

## Obtain a token

```bash
TOKEN=$(curl -s -d grant_type=client_credentials -d client_id=ci-deployer -d "client_secret=$SECRET" \
  https://auth.example.com/realms/ankka/protocol/openid-connect/token | jq -r .access_token)
```

The CLI presents a token given this way exactly as it is. It does not refresh it, so obtain one per job;
an access token lives for minutes, not days.

## Configure the CLI with environment variables

The CLI resolves each setting from a flag first, then an environment variable, then its saved
configuration file. A CI job normally uses environment variables and has no configuration file at all:

| Variable | Flag | Meaning |
|---|---|---|
| `ANKKA_URL` | `--url` | the control plane's address, `https://api.<base domain>` |
| `ANKKA_TOKEN` | `--token` | the bearer token to present |
| `ANKKA_PROJECT` | `--project`, `-p` | the project to act on |
| `ANKKA_CA` | none | a PEM file of the certificate authority to trust, for an installation whose certificate is not publicly trusted |
| `ANKKA_CONFIG` | none | an alternative configuration file path |

Prefer `ANKKA_TOKEN` to `--token`: a flag's value appears in process listings and, often, in the job's
log.

```bash
ANKKA_URL=https://api.example.com ANKKA_TOKEN="$TOKEN" ANKKA_PROJECT=checkout \
  ankka services apply -f service.json
```

## Exit codes

| Code | Meaning |
|---|---|
| `0` | the command succeeded |
| `1` | the command failed: the control plane refused it, it was not authorized, or it could not be reached |
| `2` | the command was misused: an unknown command, option or argument |

A refused command prints `error: <reason>` on standard error. An expired or invalid token is reported as
`the token was rejected`, and a valid token for an action its holder may not take is refused as
forbidden; both exit 1. Use `-o json` for output a script reads:

```bash
ankka services get orders -o json | jq -r .lifecycle
```

## An example job

This GitHub Actions job is an example to adapt, not a supplied workflow. It installs the `ankka` CLI
from the zip attached to a release, which needs a JDK 21 on the runner; a macOS runner can
`brew install thinkmorestupidless/tap/ankka` instead. Pin the CLI's version as you would any tool.

```yaml
deploy:
  runs-on: ubuntu-latest
  needs: build
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v4
      with:
        distribution: temurin
        java-version: "21"
    - name: Install the ankka CLI
      env:
        ANKKA_VERSION: "0.3.1"
      run: |
        curl -sSLO "https://github.com/thinkmorestupidless/ankka/releases/download/v$ANKKA_VERSION/ankka-cli-$ANKKA_VERSION.zip"
        unzip -q "ankka-cli-$ANKKA_VERSION.zip"
        echo "$PWD/ankka-cli-$ANKKA_VERSION/bin" >> "$GITHUB_PATH"
    - name: Obtain a token
      run: |
        TOKEN=$(curl -s -d grant_type=client_credentials -d client_id=ci-deployer \
          -d "client_secret=${{ secrets.ANKKA_CLIENT_SECRET }}" \
          https://auth.example.com/realms/ankka/protocol/openid-connect/token | jq -r .access_token)
        echo "::add-mask::$TOKEN"
        echo "ANKKA_TOKEN=$TOKEN" >> "$GITHUB_ENV"
    - name: Deploy
      env:
        ANKKA_URL: https://api.example.com
        ANKKA_PROJECT: checkout
      run: |
        ankka services apply -f service.json
        ankka services get orders
```

Change the image tag in `service.json` for each build, so that each deploy is a new generation naming
exactly what it runs. See [Build an image](images.md#tag-by-version-for-anything-real).

## Every change names its actor

Every change the control plane records carries who asked for it and when. A deploy from CI names the
client's service account:

```bash
ankka services history orders
```

See [Status and history](../operate/status-and-history.md).
