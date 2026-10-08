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
curl -H "Authorization: Bearer $TOKEN" https://api.127.0.0.1.sslip.io:8443/organizations
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

## From Scala

The request and response types on this page, the `Role` and service lifecycle vocabularies, and the
service descriptor with its validation are published as one library, `ankka-controlplane-api`, in the
package `com.thinkmorestupidless.ankka.controlplane.api`. It depends on `ankka-core` alone, so a client
of the control plane carries no actor system, database driver or Kubernetes client:

```scala
libraryDependencies += "com.thinkmorestupidless" %% "ankka-controlplane-api" % "0.5.0"
```

The CLI is built on the same library, so a client using it reads every answer the CLI can read and
refuses an invalid descriptor with the platform's own message before sending it.

## Roles

A caller's permissions come from their role in an organization, which the control plane records itself:

- A **member** reads the organization, its projects and services, creates projects, and applies, pauses,
  resumes, restarts, exposes, unexposes and deletes services in them.
- An **owner** can also rename and delete the organization and manage its members and invitations. The
  last owner cannot be removed or demoted.
- A **platform administrator** holds the `platform-admin` role in the identity provider. They can read
  every organization, disable and enable one, set and clear its quota, and add a member to one directly.

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
| `GET` | `/organizations/{organizationId}/tokens` | |
| `POST` | `/organizations/{organizationId}/tokens` | |
| `DELETE` | `/organizations/{organizationId}/tokens/{tokenId}` | |
| `POST` | `/organizations/{organizationId}/disable` | |
| `POST` | `/organizations/{organizationId}/enable` | |
| `PUT` | `/organizations/{organizationId}/quota` | |
| `DELETE` | `/organizations/{organizationId}/quota` | |
| `GET` | `/projects` | |
| `GET` | `/projects/{projectId}` | |
| `POST` | `/projects/{projectId}` | |
| `PUT` | `/projects/{projectId}/name` | |
| `DELETE` | `/projects/{projectId}` | |
| `PUT` | `/projects/{projectId}/registry` | |
| `DELETE` | `/projects/{projectId}/registry` | |
| `PUT` | `/projects/{projectId}/secrets/{name}` | |
| `DELETE` | `/projects/{projectId}/secrets/{name}` | |
| `PUT` | `/projects/{projectId}/topics/{name}` | |
| `GET` | `/projects/{projectId}/topics/{name}/schema` | |
| `DELETE` | `/projects/{projectId}/topics/{name}` | |
| `GET` | `/projects/{projectId}/topics` | |
| `PUT` | `/projects/{projectId}/brokers/{name}` | |
| `DELETE` | `/projects/{projectId}/brokers/{name}` | |
| `GET` | `/projects/{projectId}/brokers` | |
| `GET` | `/projects/{projectId}/secrets` | |
| `GET` | `/services/{projectId}` | |
| `GET` | `/services/{projectId}/{name}` | |
| `PUT` | `/services/{projectId}/{name}` | |
| `POST` | `/services/{projectId}/{name}/rollback` | |
| `GET` | `/services/{projectId}/{name}/descriptor` | |
| `POST` | `/services/{projectId}/{name}/pause` | |
| `POST` | `/services/{projectId}/{name}/resume` | |
| `POST` | `/services/{projectId}/{name}/restart` | |
| `POST` | `/services/{projectId}/{name}/expose` | |
| `POST` | `/services/{projectId}/{name}/unexpose` | |
| `GET` | `/services/{projectId}/{name}/logs` | |
| `GET` | `/services/{projectId}/{name}/topology` | |
| `GET` | `/services/{projectId}/{name}/history` | |
| `DELETE` | `/services/{projectId}/{name}` | |
| `GET` | `/auth/whoami` | |
| `GET` | `/auth` | |
Path parameters are shown in braces. Identifiers for organizations and projects are lowercase letters,
digits and `-`, starting with a letter; a project id must also fit in a Kubernetes namespace name, so it
is at most 57 characters. The project id `platform` is reserved for the platform's own workloads, and
creating a project with it answers `400`.

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
{
  "id": "acme", "name": "Acme Corp", "projects": 2, "disabled": false, "role": "owner",
  "quota": { "projects": 2, "services": 5, "instances": 8 },
  "usage": { "projects": 2, "services": 3, "instances": 5 }
}
```

`role` is the caller's role, `owner` or `member`, and is absent for a platform administrator looking at
an organization they do not belong to. `quota` is the organization's quota and is absent when none is set;
a limit absent from it is unlimited. `usage` is what the organization records as holding, counting every
service's `minInstances` as its instances, and is absent when every count is zero. `projects` is the
listing's count of the organization's projects; `usage.projects` is the organization's own record, and the
two differ only for an organization created before the installation had quotas and not yet given one.

### `GET /organizations`

The organizations the caller belongs to, as a list of summaries. A platform administrator sees every
organization.

### `GET /organizations/{organizationId}`

One organization, as a summary. Members only.

### `POST /organizations/{organizationId}`

Creates an organization. Body: `{ "name": "Acme Corp" }`. Any authenticated caller; the caller becomes
its first owner. An id that was ever used, including by a deleted organization, is refused with `409`.
Answers `204`.

A platform administrator may name the first owner instead:

```json
{
  "name": "Acme Corp",
  "owner": { "subject": "3f2a9c1e-…", "email": "alice@example.com", "display": "Alice Example" }
}
```

`owner.subject` is required and `email` and `display` are optional. The organization's only member is
then that subject, as owner, and the administrator is recorded as the one who created it. From a caller
without the `platform-admin` role, a body with `owner` is refused with `403`, `platform administrator role
required to name an owner`.

When the installation's creation policy is `platform-admin`, set by `ANKKA_ORGANIZATION_CREATION`, a
caller without the role is refused with `403`: `organizations in this installation are created by the
platform administrator`, followed by `; sign up at <url>` when `ANKKA_SIGNUP_URL` is set. Neither refusal
creates anything.

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

### `GET /organizations/{organizationId}/tokens`

Lists the organization's deploy tokens, newest first. Owners only. Each entry carries `id`, `label`,
`subject`, `createdBy`, `createdAt`, `expiresAt` (absent when the token never expires) and `lastUsed`
(a date, absent when it has never been used). No entry carries the secret, which exists only in the
response that created it.

### `POST /organizations/{organizationId}/tokens`

Creates a deploy token: a credential a machine can hold, which authenticates as a `member` of this
organization. Body: `{ "label": "github-deploy", "expiresIn": 7776000 }`, where `label` is for people
and `expiresIn` is seconds — absent means 90 days, and `0` means a token that never expires. The
maximum is 365 days. Owners only.

Answers `200` with `{ "id", "label", "secret", "subject", "expiresAt" }`. **`secret` is shown here and
nowhere else**: it is stored only as a one-way digest, so a lost token is replaced rather than
recovered. Present it as `Authorization: Bearer <secret>`, or give it to the CLI through `ANKKA_TOKEN`.

The token's subject is `token:<id>`, an ordinary member of the organization — so it is authorized,
attributed and made invisible outside its organization by exactly the rules that apply to a person. It
can do everything a member can, and nothing an owner can: it cannot invite members, rename or delete
the organization, or manage deploy tokens, including its own.

### `DELETE /organizations/{organizationId}/tokens/{tokenId}`

Revokes a deploy token and removes its membership. Owners only. Answers `204`, and `404` for a token
that does not exist, was already revoked, or belongs to another organization — the three are one
answer so that a guessed id discloses nothing.

The control plane node that handles the revocation refuses the token on the very next request. Other
nodes refuse it within the platform's read-refresh interval, since each one learns from the token's
journal; the id is never reused.

### `POST /organizations/{organizationId}/disable`

Disables an organization: every service in its projects is suspended, and its members can read but
change nothing. Platform administrators only. Answers `204`.

### `POST /organizations/{organizationId}/enable`

Re-enables a disabled organization. Each service returns to the state it was in; one its members had
paused stays paused. Platform administrators only. Answers `204`.

### `PUT /organizations/{organizationId}/quota`

Sets the organization's quota, replacing any. Platform administrators only, allowed on a disabled
organization. The body names up to three limits:

```json
{ "projects": 2, "services": 5, "instances": 8 }
```

A limit left out or `null` is unlimited; `0` allows none. Refused `400` when a limit is negative or when
no limit is named. Accepted whatever the organization already holds: nothing running is stopped, and a
quota below usage refuses only what is asked for next. Setting a quota also brings the organization's
usage record up to date with what exists, for an organization created before the installation had
quotas. Answers `204`.

From then on, `POST /projects/{projectId}` is refused `409` when the organization's project count is at
its quota, and `PUT /services/{projectId}/{name}` is refused `409` when a new service would exceed the
service quota or when the descriptor's `minInstances` would take the organization's instances past the
instance quota; each refusal names the quota and the count in use. A re-apply with the same or fewer
instances is never refused for quota.

### `DELETE /organizations/{organizationId}/quota`

Clears the quota; the organization is unlimited again. Platform administrators only. Answers `204`, also
when there was no quota.

## Projects

A project summary is:

```json
{
  "id": "checkout",
  "name": "Checkout",
  "organizationId": "acme",
  "services": 3,
  "registry": {
    "server": "ghcr.io",
    "username": "octocat",
    "setAt": "2026-09-25T10:00:00Z",
    "setBy": "sam@example.com"
  }
}
```

`registry` is absent unless the project has a registry credential, and never carries the password.

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

### `PUT /projects/{projectId}/registry`

Registers the credential the cluster pulls this project's private images with. Body:
`{ "server": "ghcr.io", "username": "octocat", "password": "…" }`. Members of its organization,
including deploy tokens. Answers `204`.

The credential is written to the cluster before anything is recorded, and a cluster that could not be
written answers `503` with the reason and records nothing — so the platform never claims a credential
the cluster does not hold. The password appears in no reply, no listing and no history; a `server`
given as a URL rather than a host is refused with `400`.

### `DELETE /projects/{projectId}/registry`

Stops claiming the project's registry credential. Answers `204`, or `404` if none is set.

The Secret itself is left in the cluster — the control plane holds no permission to delete one — and
the next deploy of each service in the project stops naming it.

### `PUT /projects/{projectId}/secrets/{name}`

Sets entries of a project secret, which a descriptor's variable takes by `secretKeyRef`. Body:
`{ "entries": { "STRIPE_KEY": "…" } }`. Members of the project's organization, including deploy tokens.
Answers `204`.

The entries named are added or replaced and every other entry of the secret is kept. They are written to
a Kubernetes Secret in the project's namespace before anything is recorded, and a cluster that could not
be written answers `503` and records nothing. No value appears in any reply, listing or history: the
control plane records the secret's name and its entries' names. A name the platform uses for its own
Secrets (beginning `ankka-`, or ending `-db`, `-cluster-tls`, `-service-tls`, `-database-tls`,
`-secret-key` or `-telemetry`), a malformed name or entry, an empty value or one over 64 KiB is refused with `400`, every
problem at once.

### `DELETE /projects/{projectId}/secrets/{name}`

Removes the one entry named by the `entry` query parameter:
`DELETE /projects/shop/secrets/checkout?entry=STRIPE_KEY`. Answers `204`, or `404` when the project
secret has no such entry, in which case nothing is written. The Secret itself is never deleted; one with
no entry left is no longer listed.

### `GET /projects/{projectId}/secrets`

The project's secrets, by name: `[{ "name": "checkout", "entries": ["STRIPE_KEY"], "setAt": "…",
"setBy": "…" }]`. From the control plane's own record, so a secret just set is listed at once. Never a
value — the control plane cannot read a Secret back.

### `PUT /projects/{projectId}/topics/{name}`

Declares a topic on the project, which the platform makes on the installation's broker as
`<projectId>.<name>`, or changes a declared topic: more partitions, its compaction, its contract. Body:
`{ "partitions": 12, "compacted": false, "contract": { "name": "order.v1", "schema": { … } } }`, where
`compacted` and `contract` are optional and absent means not compacted and no contract. Members of the
project's organization, including deploy tokens. Answers `204`.

A project holds one declaration per topic, and every service of the project uses the topic by its name.
Declaring a topic again as it is records nothing. A name that is not lower-case letters, digits, `-` and
`.` starting and ending with a letter or digit, or is over 100 characters, partitions outside 1 to 1000,
a contract name outside `[a-z0-9][a-z0-9._-]{0,98}[a-z0-9]`, and a schema that is not JSON or is over
64 KiB, are refused with `400`, every problem at once. Fewer partitions than the project declares is
refused with `409`, naming both counts: a topic is never made smaller.

A contract's schema is written to the project's schema store in the cluster before the declaration is
recorded, under its fingerprint — `sha256:` and the SHA-256 of the document under RFC 8785 — so the
record never names a document the cluster does not hold; a cluster that could not be written answers
`503` and records nothing.

### `GET /projects/{projectId}/topics/{name}/schema`

The schema document a topic's contract was declared with, as JSON, exactly as it was given. Members
only. `404` when the project declares no contract on the topic.

### `DELETE /projects/{projectId}/topics/{name}`

Stops declaring a topic. Answers `204`, or `404` when the project declares no topic of that name. The
topic and what was published to it stay on the broker; declaring it again finds them.

### `GET /projects/{projectId}/topics`

The project's declared topics, by name: `[{ "name": "orders", "partitions": 3, "compacted": false,
"contract": { "name": "order.v1", "fingerprint": "sha256:…" }, "phase": "provisioned", "checks": [ { "topic":
"orders", "service": "wallet", "component": "consumer:relay", "direction": "publishes", "stated":
"order.v1", "state": "checked" } ] }]`. From the project's own record, so a topic just declared is listed
at once. `phase` says how far the platform has got with it — `waiting for broker`, `provisioned`,
`recovered` or `failed`, with a `detail` — and is absent until the operator has reported on the topic,
or when the cluster cannot be read. `checks` lists each side a running service takes on a topic with a
contract, read from the services' instances: `checked` when the component states the declared contract,
`mismatch` when it states another or none, `unchecked` for an instance started before the declaration;
empty for a topic without a contract or when no instance could be read.

### `PUT /projects/{projectId}/brokers/{name}`

Declares a broker beside the installation's, which a component of any service in the project may name
for one topic, or changes where it is. Body: `{ "bootstrap": "kafka.legacy:9094", "shape": "sasl",
"secret": "legacy-credential" }`. Members of the project's organization, including deploy tokens.
Answers `204`.

`shape` is `certificate` (the project secret holds `ca.crt`, `tls.crt` and `tls.key`) or `sasl` (it
holds `ca.crt`, `username` and `password`, and optionally `mechanism`); every shape is over TLS. A name
outside a topic's rule, a `bootstrap` that is not `host:port[,host:port]`, another shape, or a secret
name the platform reserves is refused with `400`; a secret the project has not set, or one lacking an
entry the shape needs, is refused with `400` naming the entry. Declaring a broker again as it is records
nothing.

### `DELETE /projects/{projectId}/brokers/{name}`

Stops declaring a broker. Answers `204`, or `404` when the project declares no broker of that name. A
service whose component names the broker is refused at its next start.

### `GET /projects/{projectId}/brokers`

The project's declared brokers, by name: `[{ "name": "legacy", "bootstrap": "kafka.legacy:9094",
"shape": "sasl", "secret": "legacy-credential", "declaredAt": "…" }]`. Never a credential.

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
| `topicSources` | list, optional | Each topic source of the service, from its running instances: `kind`, `component`, `topic`, `group`, `start`, `version`, `recordedVersion`, `behind`, `broker`, `contract`, `lag` (messages past the last one handled, summed over the instances, as of their last poll) and `failing` (the reason of the change being delivered again). Absent when no instance answered, and on a listing row. |
| `topicChecks` | list, optional | Each side the service's components take on a declared topic with a contract: `topic`, `component`, `direction`, `stated`, `state` (`checked`, `mismatch` or `unchecked`). Absent as `topicSources` is. |

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

### `POST /services/{projectId}/{name}/rollback`

Rolls the service back: applies the descriptor it recorded at an earlier generation again, as a new
generation. Nothing is rewound; the generation keeps counting and the history shows the rollback. The
body names the generation, `{ "generation": 1 }`; `{}` asks for the most recent generation whose
descriptor differs from the one the service has, passing over restarts and applies of the same
descriptor. Answers `{ "rolledBackTo": 1, "status": { … } }`, the generation it rolled back to and the
status it produced.

A rollback is checked as an apply is: the descriptor against the platform's rules as they are now
(`400 invalid descriptor at generation 1: …`), the organization's quota, and whether the organization
is disabled. Whether the service is paused or exposed, and its restart count, are unchanged. The service
keeps the descriptors of its last fifty applies, and refuses with:

- `404 service 'cart' has no generation 9` for a generation it never had;
- `409 the descriptor of generation 3 is no longer kept; the oldest kept is generation 11`;
- `409 generation 3 was a restart and ran the descriptor of generation 2`;
- `409 service 'cart' already has the descriptor of generation 2`, the generation it is at included;
- `409 service 'cart' has no earlier generation with a different descriptor`, with no generation named.

A refused rollback writes nothing.

### `GET /services/{projectId}/{name}/descriptor`

The descriptor the service applied at the generation named by the required `generation` query
parameter, exactly as `PUT /services/{projectId}/{name}` accepts one, so it can be compared with the
current one or applied again. Members only. Answers for a deleted service, as its history does. It
refuses as a rollback does for a generation never had, no longer kept, or that recorded none. A
variable's literal value is in the reply as it was applied; one taken from a project secret is the
reference to it, never the value.

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
| `platform` | Present, or `true`: the platform's container instead of the developer's. A process-hosted service's pod holds the sidecar beside the process, and a web-hosted one's the proxy beside it; without this the developer's is read. Refused with `400` for a service whose pod has one container: `--platform applies to a service with process or web hosting`. |
| `tail` | Only the last N lines. |
| `since` | Only the last N seconds. |

Response: `{ "instances": [ { "instance": "cart-6d9…", "output": "…", "error": null } ] }`. An instance
that could not be read carries an `error` rather than failing the whole response. A service with no
running instance, for example a paused one, answers `404`.

### `GET /services/{projectId}/{name}/topology`

What a deployed service is made of and what calls what, read from each running instance and merged.
Members only; a non-member is answered `404`, as for the service itself. The control plane reads
every instance concurrently over the service's observe port, presenting its own certificate, and the
member receives the merged document and no credential for the service, its instances or the cluster.

Response: `ServiceTopology` — `service`, `running` (instances found), `contributing` (instances that
answered), `partial`, `instances`, `window`, `nodes`, `declared`, `calls` and `differences`. Each entry
of `instances` carries `pod` and a `status`:

| `status` | Meaning |
|---|---|
| `ok` | The instance answered, and its counts are in the merge. |
| `unreachable` | It did not answer in time. |
| `unsupported` | The connection was refused: its runtime serves no topology. |
| `failed` | It answered with an error, or with something that is not a topology; `problem` says what. |

`partial` is `true` whenever an instance is not `ok`; such an instance contributes nothing and is never
guessed at. Nodes and declared connections are the union across the instances that answered, and a
node not on every one of them is listed under `differences` with the pods that have it. Observed calls
are summed per pair of handlers over a recent window, with `handled` (`ok`, `refused`, `failed`) and
`unanswered` (`timedOut`, `undelivered`) kept apart: one is counted where a handler ran, the other where
a caller got no answer, and they are never added together. Durations are read from bucketed histograms,
so `p50`, `p99` and `max` are bucket edges and say `bucketed: true`. A service with no running instance,
for example a paused one, answers `404`.

### `GET /services/{projectId}/{name}/history`

Who did what to the service, newest first. Members only. Each entry is
`{ "kind": "applied", "generation": 3, "actor": { "subject": "…", "display": "Ada", "administrative": false }, "at": "2026-09-20T12:00:00Z" }`.
`kind` is one of `applied`, `rolled-back`, `restarted`, `paused`, `resumed`, `exposed`, `unexposed`,
`deleted`, `suspended` or `reinstated`. `administrative` is `true` when the platform administrator role
is what allowed the action. Entries recorded before actors were tracked have no `actor` or `at`.

An `applied` or `rolled-back` entry also carries `image`, the image of the descriptor it recorded, and
`digest`, 64 hexadecimal characters that two entries share exactly when their descriptors state the same
things: reordered labels or annotations are the same descriptor, and reordered variables are not. A
`rolled-back` entry carries `rolledBackTo`, the generation whose descriptor it applied again. An entry
the control plane held from before it recorded images may have neither `image` nor `digest`.

### `DELETE /services/{projectId}/{name}`

Deletes the service's workload. Its database is kept, so applying the same name again recovers its data.
A service name, unlike an organization or project id, can be reused. Answers `204`.
