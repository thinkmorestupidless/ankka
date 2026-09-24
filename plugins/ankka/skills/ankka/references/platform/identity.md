# Identity and machine accounts

> How people and machines authenticate to the ankka control plane through the installation's Keycloak — logging in with the CLI, adding users, platform administrators, machine accounts and the realm.

Source: https://docs.ankka.cloud/platform/identity/
Every call to the control plane carries an OpenID Connect access token from the installation's own
identity provider, a Keycloak that is deployed as part of the platform and answers at
`https://auth.<base domain>`. There is no shared token and no anonymous access. Two responsibilities are
kept apart:

- **Keycloak authenticates.** It decides who is a user of the installation, holds their passwords,
  sessions and second factors, and signs their tokens.
- **The control plane authorizes.** It verifies each token against Keycloak's published keys, takes the
  token's subject as the caller's identity, and answers every question about organizations and roles from
  its own records. It holds no credential for Keycloak's administration.

Every change the control plane records names who asked for it, which is what makes its history an audit
trail.

## Log in

```bash
ankka config set url https://api.example.com
ankka login
```

```text
To log in, open  https://auth.example.com/realms/ankka/device
and enter the code  WDJB-MJHT
```

`ankka login` uses the OAuth 2.0 device authorization grant. The CLI asks the control plane where its
identity provider is, prints an address and a short code, tries to open a browser, and waits while you
sign in. The sign-in can happen in any browser on any device, so it works over SSH; pass `--no-browser`
to skip opening one.

The login is saved in `credentials.json` beside the CLI's configuration file, normally
`~/.ankka/credentials.json`, readable only by you and keyed by control plane URL. It holds a refresh
token, so later commands renew their access token without a browser. An access token lives five minutes.
A login unused for thirty days has to be repeated. The CLI never prints a token.

```bash
ankka whoami              # who the control plane thinks you are, and your organizations
ankka logout              # forget the login for the configured control plane
ankka logout --all        # forget every saved login
```

`logout` also tells the identity provider to revoke the session, and forgets the local login even when
the identity provider cannot be reached.

A request whose token is missing or expired is answered `401`, which the CLI turns into advice to run
`ankka login`. A valid token for something its holder may not do is a `403`.

## Add people

Self-registration is off: an administrator adds each person in Keycloak's console, at
`https://auth.<base domain>/admin/`, in the `ankka` realm. Give each user:

- a username and an email address, with **Email verified** set, because organization invitations are
  claimed by verified email;
- a first and a last name, because Keycloak treats a user without them as not fully set up and refuses
  their login;
- a password, or whatever credentials the realm's policy asks for.

A new user belongs to no organization. They can create their own, or an owner invites their email
address; see [Organizations, projects and members](organizations.md).

## Platform administrators

`platform-admin` is a realm role in Keycloak. Its holders see every organization, act as an owner in any
of them, and can repair an organization whose owners have all left and disable or enable an organization.
Grant it in Keycloak's console, under the user's role mapping. It is the one installation-wide role, and
it lives in Keycloak because who administers the installation is a question about identity, not about any
one organization.

## Machine accounts

A machine, such as a CI job, is a Keycloak client:

1. Create a confidential client with **service accounts enabled**.
2. Assign it the **`ankka-controlplane`** client scope as a default scope. That scope puts the control
   plane's audience, the subject and the identity claims the control plane reads on every token; a
   token without it is refused.
3. To invite it to an organization like a person, give its service-account user an email address marked
   verified.

The machine obtains a token with the client-credentials grant and passes it to the CLI through
`ANKKA_TOKEN` or `--token`, which presents it exactly as given. A machine is another principal in the
same identity provider, so there is no second kind of credential to create, rotate or audit, and its
changes are recorded under its own identity. See [Deploy from CI](../deploy/ci.md).

## The realm

The `ankka` realm is defined in one file, `kustomization/components/keycloak/realm-import.json` in the
ankka repository. The file is a `KeycloakRealmImport` resource, so installing the platform's manifests
creates the import, and the Keycloak operator imports the realm once Keycloak is ready. The repository's
`docker-compose.yml` reads the realm out of the same file for a local Keycloak. It contains:

- the public `ankka-cli` client, with the device authorization grant, used by `ankka login`;
- the `ankka-controlplane` client scope, which puts the control plane's audience and the claims it reads
  on every token;
- the `platform-admin` realm role;
- no users at all.

The platform imports the realm once, when it is installed. **A realm import is one-shot:** it creates a
realm that does not exist and never updates or deletes one. A later change to the file has no effect on
an existing installation, so change an existing realm in Keycloak's console.

## The issuer the control plane expects

The control plane accepts only tokens whose issuer is exactly its configured issuer, scheme, host, port
and path included. In a cluster the issuer is derived from the installation's base domain and HTTPS
port:

```text
https://auth.<base domain>[:<https port>]/realms/ankka
```

The port is left out when it is 443. `ANKKA_BASE_DOMAIN` and `ANKKA_HTTPS_PORT` on the control plane set
the two parts, and an installation's overlay writes both once. `ANKKA_AUTH_ISSUER` names an issuer
explicitly and takes precedence over the derivation. The control plane reads Keycloak's keys over the
cluster's internal address, `ANKKA_AUTH_JWKS_URL`, and caches them, so verifying a token needs no call to
Keycloak on the request path.

When the control plane rejects every token with `401`, compare the issuer it expects with the one
Keycloak advertises:

```bash
curl https://auth.example.com/realms/ankka/.well-known/openid-configuration | jq -r .issuer
```

## Running a control plane outside a cluster

The ankka repository's `docker-compose.yml` runs a Keycloak on port 8081 with the same realm, and creates
a development user `dev` with password `dev` and the `platform-admin` role. To run a control plane from a
checkout against it:

```bash
docker compose up -d
ANKKA_AUTH_ISSUER=http://localhost:8081/realms/ankka sbt controlPlane/run
ankka config set url http://localhost:9000
ankka login                      # dev / dev
```

A control plane run this way reaches no cluster, so services it is given stay `UpdateInProgress`. It is
for developing the control plane, not for running services; see
[Install a local platform](install-local.md) for a complete local installation.
