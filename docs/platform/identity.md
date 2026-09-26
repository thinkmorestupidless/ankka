---
title: Identity and machine accounts
description: How people and machines authenticate to the ankka control plane through the installation's Keycloak — logging in with the CLI, adding users, platform administrators, machine accounts and the realm.
kind: guide
related: [platform/organizations.md, deploy/ci.md, concepts/tenancy-and-access.md]
---

# Identity and machine accounts

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

## Who may create organizations

By default anyone who can log in may create an organization, and becomes its first owner. An
installation that sells access, where being registered and being entitled are different things, can
reserve creation for platform administrators instead. Two settings on the control plane decide it:

- `ANKKA_ORGANIZATION_CREATION` is `open`, the default, or `platform-admin`. With `platform-admin`, a
  request to create an organization from anyone without the `platform-admin` role is refused with `403`,
  and nothing is created.
- `ANKKA_SIGNUP_URL` is an address to send a refused caller to. When it is set, the refusal ends with
  `; sign up at <url>`, and the CLI prints it. It has no effect while creation is open.

Any other value of `ANKKA_ORGANIZATION_CREATION` stops the control plane from starting, so a mistyped
value can never quietly leave creation open.

The setting changes organization creation and nothing else. Members of an existing organization read,
rename, invite, create projects and deploy exactly as they would in an open installation, and invitations
are claimed the same way.

In such an installation an administrator, usually a product's own machine account, creates each
organization for its customer and names the customer as its first owner, in one request:

```bash
ankka organizations create acme --name "Acme" \
  --owner 3f2a9c1e-… --owner-email alice@example.com --owner-name "Alice Example"
```

The owner is the customer's subject, their stable id in Keycloak. The organization's only member is that
owner, and its history records the administrator as the one who created it. Only a platform administrator
may name an owner; the option is refused from anyone else in every installation.

## Platform administrators

`platform-admin` is a realm role in Keycloak. Its holders see every organization, act as an owner in any
of them, and can repair an organization whose owners have all left and disable or enable an organization.
Grant it in Keycloak's console, under the user's role mapping. It is the one installation-wide role, and
it lives in Keycloak because who administers the installation is a question about identity, not about any
one organization.

## Machine accounts

A machine — a CI job, a script, anything that is not a person at a browser — holds a **deploy token**.
An owner of an organization creates one, and nobody else needs to be involved:

```bash
ankka organizations tokens create acme --label github-deploy
```

```text
Deploy token 'github-deploy' created. This is the only time the secret is shown.

  ankka_3f9a1c2e7b4d8f01_9c2e…a1f0

Expires 2026-12-24T10:00:00Z. Store it as a secret named ANKKA_TOKEN.
```

The secret is shown once and stored only as a one-way digest, so a lost token is replaced rather than
recovered. Present it to the CLI through `ANKKA_TOKEN` or `--token`, exactly as any other token:

```bash
ANKKA_TOKEN=ankka_… ankka services deploy orders ghcr.io/acme/orders:1.4.2
```

A deploy token authenticates as `token:<id>`, an ordinary **member** of the organization it was created
in. That is the whole of its design: it is authorized, attributed and made invisible outside its
organization by exactly the rules that apply to a person, with no second set of rules for machines.
It can do everything a member can, and nothing an owner can — it cannot invite members, rename or
delete the organization, or manage deploy tokens, including its own. So a leaked CI credential cannot
mint a replacement for itself or revoke the one that would stop it.

```bash
ankka organizations tokens list acme         # label, creator, expiry, the date last used
ankka organizations tokens revoke acme 3f9a1c2e7b4d8f01
```

A token expires 90 days after it is created unless you choose otherwise — `--expires-in 30d`, up to
365 days, or `--never-expires` for a pipeline that must not stop on a date nobody remembers. The
listing always says which. Revoking takes effect at once on the control plane node that handled it,
and within a second everywhere else; the id is never reused.

Every change a token makes is recorded under its own identity, so `ankka services history` distinguishes
a deploy from CI from a deploy by hand. See [Deploy from GitHub Actions](../deploy/ci.md).

### A machine account in Keycloak

The alternative, for an installation that wants every principal in its own identity provider. It needs
an administrator with access to Keycloak's console, which is why it is not the recommended path:

1. Create a confidential client with **service accounts enabled**.
2. Assign it the **`ankka-controlplane`** client scope as a default scope. That scope puts the control
   plane's audience, the subject and the identity claims the control plane reads on every token; a
   token without it is refused.
3. To invite it to an organization like a person, give its service-account user an email address marked
   verified, then invite that address.

The machine obtains a token with the client-credentials grant and presents it as given. Because it is a
principal in the same identity provider as your people, it appears in Keycloak's own session and audit
views — which is the reason to prefer it, where that matters more than an owner being able to grant
access without an administrator.

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

An explicit issuer is also how one installation trusts another installation's realm. Set
`ANKKA_AUTH_ISSUER` to the other realm's issuer and `ANKKA_AUTH_JWKS_URL` to its public key address, and
the control plane accepts that realm's tokens and refuses every other issuer's, including the one its own
base domain would derive. Its login discovery then sends `ankka login` to that realm, so a person
registered once can log in to both installations. See
[Install on a cloud cluster](install-cloud.md#a-spoke-installation).

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
