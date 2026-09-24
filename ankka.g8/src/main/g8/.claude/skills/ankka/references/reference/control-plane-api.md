# Control plane HTTP API

> Every route the control plane serves, with its parameters, request body, response and who may call it, plus how requests are authenticated and how errors are reported.

Source: https://docs.ankka.cloud/reference/control-plane-api/
The control plane is operated through a JSON API over HTTPS. The `ankka` CLI is a thin client of this
API: every CLI command is one call to one route listed on this page, so anything the CLI does, a script
or another tool can do with the same token.

The API's address is the control plane's URL, `https://api.<base domain>` on an installation. A local
platform prints it when it is deployed.

## Authentication

Every route except `GET /auth` requires an OpenID Connect access token from the installation's identity
provider, sent as a bearer token:

```bash
curl -H "Authorization: Bearer \$TOKEN" https://api.127.0.0.1.sslip.io:8443/organizations
```

`GET /auth` answers without a token and says where the identity provider is, which is how `ankka login`
starts. A person obtains a token with `ankka login`; a machine obtains one from the identity provider
with the client-credentials grant and presents it as given. The control plane verifies the token itself,
against the identity provider's published keys, and takes the token's `sub` claim as the caller's
identity.

| Status | Meaning |
|---|---|
| `401` | No token, or one that is invalid or expired. The response carries `WWW-Authenticate: Bearer …`. Log in again. |
| `403` | A valid token for an action its holder may not take, such as a member renaming an organization. |
| `404` | The organization, project or service does not exist, or the caller is not a member of the organization it belongs to. The two are indistinguishable on purpose. |
| `503` | The control plane cannot verify tokens right now, because it cannot read the identity provider's keys. The response carries `Retry-After: 5`. |

## Requests and responses

Request and response bodies are JSON (`application/json`). A route that succeeds with nothing to say
answers `204 No Content` with an empty body. Every error has a JSON body naming its status and the
reason:

```json
{"status":409,"error":"organization 'acme' still has 2 project(s)"}
```

Errors from a command use the platform's error codes: `400` for a malformed request or an invalid
descriptor, `404` for something that does not exist, `409` for a request that conflicts with the
current state, `503` and `504` when the control plane could not complete the call in time. See
[Error codes](error-codes.md).

Every change is recorded with who made it and when, and a service's record is readable through
`GET /services/{projectId}/{name}/history`.

## Roles

A caller's permissions come from their role in an organization, which the control plane records itself:

- A **member** reads the organization, its projects and services, creates projects, and applies, pauses,
  resumes, restarts, exposes, unexposes and deletes services in them.
- An **owner** can also rename and delete the organization and manage its members and invitations. The
  last owner cannot be removed or demoted.
- A **platform administrator** holds the `platform-admin` role in the identity provider. They can read
  every organization, disable and enable one, and add a member to one directly.

Anyone logged in may create an organization, and becomes its first owner.

## Routes

The table is generated from the control plane's own route declarations.

| Method | Path | Streaming |
|---|---|---|
| `GET` | `/organizations` | |
| `GET` | `/organizations/{organizationId}` | |
| `POST` | `/organizations/{organizationId}` | |
| `PUT` | `/organizations/{organizationId}/name` | |
| `DELETE` | `/organizations/{organizationId}` | |
| `GET` | `/organizations/{organizationId}/members` | |
| `POST` | `/organizations/{organizationId}/members` | |
| `DELETE` | `/organizations/{organizationId}/members/{subject}` | |
| `PUT` | `/organizations/{organizationId}/members/{subject}/role` | |
| `DELETE` | `/organizations/{organizationId}/invitations/{email}` | |
| `POST` | `/organizations/{organizationId}/members/{subject}/repair` | |
| `POST` | `/organizations/{organizationId}/disable` | |
| `POST` | `/organizations/{organizationId}/enable` | |
| `GET` | `/projects` | |
| `GET` | `/projects/{projectId}` | |
| `POST` | `/projects/{projectId}` | |
| `PUT` | `/projects/{projectId}/name` | |
| `DELETE` | `/projects/{projectId}` | |
| `GET` | `/services/{projectId}` | |
| `GET` | `/services/{projectId}/{name}` | |
| `PUT` | `/services/{projectId}/{name}` | |
| `POST` | `/services/{projectId}/{name}/pause` | |
| `POST` | `/services/{projectId}/{name}/resume` | |
| `POST` | `/services/{projectId}/{name}/restart` | |
| `POST` | `/services/{projectId}/{name}/expose` | |
| `POST` | `/services/{projectId}/{name}/unexpose` | |
| `GET` | `/services/{projectId}/{name}/logs` | |
| `GET` | `/services/{projectId}/{name}/history` | |
| `DELETE` | `/services/{projectId}/{name}` | |
| `GET` | `/auth/whoami` | |
| `GET` | `/auth` | |
Path parameters are shown in braces. Identifiers for organizations and projects are lowercase letters,
digits and `-`, starting with a letter; a project id must also fit in a Kubernetes namespace name, so it
is at most 57 characters.

## Identity

### `GET /auth`

Where to log in. No token needed. Response: `{"issuer": "...", "clientId": "...", "audience": "..."}`,
the identity provider's issuer URL, the public client id the CLI logs in as, and the audience the
control plane expects on a token.

### `GET /auth/whoami`

The caller as the control plane sees them. Response:

| Field | Type | Meaning |
|---|---|---|
| `subject` | string | The token's `sub`: the caller's stable identity. |
| `name` | string, optional | Display name from the token. |
| `email` | string, optional | Email address from the token. |
| `emailVerified` | boolean | Whether the identity provider verified the email address. |
| `platformAdmin` | boolean | Whether the caller holds the `platform-admin` role. |
| `organizations` | array | `{ "id", "name", "role" }` for every organization the caller belongs to. |

Calling it also claims any pending invitation addressed to the caller's verified email.

## Organizations

An organization summary is:

```json
{ "id": "acme", "name": "Acme Corp", "projects": 2, "disabled": false, "role": "owner" }
```

`role` is the caller's role, `owner` or `member`, and is absent for a platform administrator looking at
an organization they do not belong to.

### `GET /organizations`

The organizations the caller belongs to, as a list of summaries. A platform administrator sees every
organization.

### `GET /organizations/{organizationId}`

One organization, as a summary. Members only.

### `POST /organizations/{organizationId}`

Creates an organization. Body: `{ "name": "Acme Corp" }`. Any authenticated caller; the caller becomes
its first owner. An id that was ever used, including by a deleted organization, is refused with `409`.
Answers `204`.

### `PUT /organizations/{organizationId}/name`

Renames an organization. Body: `{ "name": "Acme Corporation" }`. Owners only. Answers `204`.

### `DELETE /organizations/{organizationId}`

Deletes an organization. Owners only. Refused with `409` while it still has projects. The id is never
reusable. Answers `204`.

### `GET /organizations/{organizationId}/members`

Members and pending invitations. Members only. Response:

```json
{
  "members": [ { "subject": "3f1c…", "role": "owner", "email": "ada@example.com", "display": "Ada", "since": "2026-09-01T10:00:00Z", "addedBy": "Ada" } ],
  "invitations": [ { "email": "bob@example.com", "role": "member", "invitedAt": "2026-09-02T09:00:00Z", "invitedBy": "Ada" } ]
}
```

### `POST /organizations/{organizationId}/members`

Invites an email address. Body: `{ "email": "bob@example.com", "role": "member" }`, where `role`
defaults to `member`. Owners only. The invitation becomes a membership the first time a token carrying
that email address, verified by the identity provider, reaches the control plane. Answers `204`.

### `DELETE /organizations/{organizationId}/members/{subject}`

Removes a member by subject. Owners only. The removal takes effect on the member's next request.
Answers `204`.

### `PUT /organizations/{organizationId}/members/{subject}/role`

Changes a member's role. Body: `{ "role": "owner" }`. Owners only. Answers `204`.

### `DELETE /organizations/{organizationId}/invitations/{email}`

Withdraws an invitation that has not been claimed. Owners only. Answers `204`.

### `POST /organizations/{organizationId}/members/{subject}/repair`

Adds a member directly by subject, for an organization whose owners have all left. Body:
`{ "role": "owner" }`, where `role` defaults to `owner`. Platform administrators only. Answers `204`.

### `POST /organizations/{organizationId}/disable`

Disables an organization: every service in its projects is suspended, and its members can read but
change nothing. Platform administrators only. Answers `204`.

### `POST /organizations/{organizationId}/enable`

Re-enables a disabled organization. Each service returns to the state it was in; one its members had
paused stays paused. Platform administrators only. Answers `204`.

## Projects

A project summary is:

```json
{ "id": "checkout", "name": "Checkout", "organizationId": "acme", "services": 3 }
```

### `GET /projects`

The projects in the caller's organizations, ordered by name. The optional query parameter
`organization` narrows the list to one organization; naming one the caller does not belong to yields an
empty list.

### `GET /projects/{projectId}`

One project, as a summary. Members of its organization only.

### `POST /projects/{projectId}`

Creates a project. Body: `{ "name": "Checkout", "organizationId": "acme" }`. Members of that organization
only. An invalid id is refused with `400`; an id that was ever used is refused with `409`. Answers `204`.

### `PUT /projects/{projectId}/name`

Renames a project. Body: `{ "name": "Checkout team" }`. Members of its organization. Answers `204`.

### `DELETE /projects/{projectId}`

Deletes a project. Refused with `409` while it still has services. The id is never reusable. Answers
`204`.

## Services

Every service route answers with a service status, except where noted:

| Field | Type | Meaning |
|---|---|---|
| `name` | string | The service's name. |
| `projectId` | string | Its project. |
| `lifecycle` | string | One of the [lifecycle states](lifecycle-states.md). |
| `generation` | integer | Increments on every apply and restart. |
| `image` | string | The image of the current descriptor. |
| `readyInstances` | integer | Instances that are ready. |
| `desiredInstances` | integer | Instances that should be running. |
| `detail` | string, optional | Why the service is in its state, when there is something to say. |
| `confirmed` | boolean | `false` when the status restates what was last known rather than a current observation. |
| `database` | string, optional | What the platform did about the service's database, such as `provisioned` or `supplied`. |
| `hostname` | string, optional | The full `https://` URL the service answers at, while exposed. |
| `exposed` | boolean | Whether the service has been exposed. |
| `suspended` | boolean | Whether its organization is disabled. |
| `paused` | boolean | Whether its members paused it. |
| `hosting` | string | `embedded` or `process`. |
| `protocol` | string, optional | The sidecar protocol a process-hosted service declared. |

### `GET /services/{projectId}`

Every service in a project, as a list of statuses ordered by name. Members only. A listing can lag a
moment behind a change, because it is read from a projection; a single service's status is not.

### `GET /services/{projectId}/{name}`

One service's status. Members only.

### `PUT /services/{projectId}/{name}`

Applies a [service descriptor](service-descriptor.md), creating the service if it is new. The body is the
descriptor, and its `name` must equal `{name}`. Every validation problem is reported at once, with
`400`. Each apply increments the generation, including one that changes nothing, which is how an image
tag is pulled again. A paused service stays paused. Answers with the new status.

### `POST /services/{projectId}/{name}/pause`

Stops every instance and keeps the descriptor, database and hostname. Pausing a paused service changes
nothing. Answers with the status.

### `POST /services/{projectId}/{name}/resume`

Starts a paused service again. Answers with the status.

### `POST /services/{projectId}/{name}/restart`

Replaces every instance by a rolling update, and increments the generation. Refused with `409` while the
service is paused. Answers with the status.

### `POST /services/{projectId}/{name}/expose`

Makes the service reachable from outside the cluster at `https://<service>-<project>.<base domain>`, and
answers with the status, whose `hostname` is that URL. Refused with `409` when the service serves no
HTTP, when the hostname's first label would be over 63 characters, or when another exposed service
already derives the same hostname. A control plane with no base domain configured refuses every expose
the same way.

### `POST /services/{projectId}/{name}/unexpose`

Removes the external route and nothing else. Answers with the status.

### `GET /services/{projectId}/{name}/logs`

Recent output of the service's running instances, as the cluster holds it at the moment of asking.
Members only. Query parameters:

| Parameter | Meaning |
|---|---|
| `instance` | One instance, by pod name; otherwise every instance. |
| `previous` | Present, or `true`: the container before the last restart. |
| `tail` | Only the last N lines. |
| `since` | Only the last N seconds. |

Response: `{ "instances": [ { "instance": "cart-6d9…", "output": "…", "error": null } ] }`. An instance
that could not be read carries an `error` rather than failing the whole response. A service with no
running instance, for example a paused one, answers `404`.

### `GET /services/{projectId}/{name}/history`

Who did what to the service, newest first. Members only. Each entry is
`{ "kind": "applied", "generation": 3, "actor": { "subject": "…", "display": "Ada", "administrative": false }, "at": "2026-09-20T12:00:00Z" }`.
`kind` is one of `applied`, `restarted`, `paused`, `resumed`, `exposed`, `unexposed`, `deleted`,
`suspended` or `reinstated`. `administrative` is `true` when the platform administrator role is what
allowed the action. Entries recorded before actors were tracked have no `actor` or `at`.

### `DELETE /services/{projectId}/{name}`

Deletes the service's workload. Its database is kept, so applying the same name again recovers its data.
A service name, unlike an organization or project id, can be reused. Answers `204`.
