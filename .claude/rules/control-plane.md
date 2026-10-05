---
paths:
  - "controlplane/**"
  - "controlplane-api/**"
  - "cli/**"
  - "modules/auth-oidc/**"
  - "kustomization/components/keycloak/**"
  - "kustomization/components/keycloak-operator/**"
---

# The control plane, identity, credentials and the CLI

## The control plane is an ankka application

`ControlPlane.components` and `ControlPlane.endpoints` are the whole inventory: three
event sourced entities (organization, project, service), three views for listing, and the
endpoints — organizations, projects, services, `whoami`, and the one open discovery route.
`componentsWith(projector)` adds the two consumers that react to desired-state changes
(`ProjectionTrigger`, `SuspensionTrigger`). `ControlPlane.builder` also registers
`ProjectionRuntime()` — without it every listing stays permanently empty while every write
succeeds.

Two invariants carry most of the weight:

- **`generation`** increments on every apply and restart; an observation states the
  generation it describes, and one describing a superseded generation is dropped. The
  guard is in `Service.onObserved` — the *fold* — so replay reproduces it exactly. An
  observation identical to the recorded state is also refused, or the timer-driven
  reconciler would grow the journal forever.
- **`exists` vs `known`.** Deleting an organization or project is a tombstone: `exists`
  goes false but `known` stays true, so the id cannot be recreated. A service is
  deliberately the opposite — a name is a deployment target, not a tenancy boundary.

- **Quotas are reserve-first.** An organization keeps an exact record of its projects and
  services (`UsageRecord`, one fold shared by the entity and the listing row), and the project
  and service endpoints ask it to *reserve* before creating or applying, then give the slot back
  if the second step fails — only if this request was the one that took it, which is what the
  reservation's reply says. `quota set` merges a snapshot from the views into the record, never
  replaces it: a listing lags, and a replace forgot a project created a moment earlier on the
  suite's first run. The shared codec omits a field at its default, so `usage` is absent from
  the wire when every count is zero; `Usage`'s own fields have no defaults so a written one is
  whole.

Cross-entity checks live in the endpoint, never a handler. An entity cannot see another
entity's state, and calling out to fetch it would be a check that does not hold anyway.

## Identity: Keycloak authenticates, the control plane authorizes

Every control plane route but `GET /auth` and the health probe is behind `Acl.Authenticate`
(`ControlPlaneAcl.oidc`): a token is verified offline against the realm's cached JWKS
(`controlplane/auth/TokenVerifier`, nimbus — the one library added, in `controlplane` only), and
the caller becomes a `Principal` on the request. Only the token's `sub` is ever a key; email and
name are display. Keycloak decides who is a user; the `Organization` entity decides what they may
touch; the control plane holds no Keycloak admin credential and the design (invitations claimed on
first verified login, research R9) exists so it never needs one. Every command carries an
`Attribution` as *metadata* (`Attribution.from(commandContext.metadata)`), and every
command-produced event has `actor: Option[Actor]` and `at: Option[Instant]` with `None` defaults so
pre-feature journals replay (`EventCompatibilitySuite` pins the old JSON).

The issuer inside a cluster is **derived** from `ANKKA_BASE_DOMAIN` and `ANKKA_HTTPS_PORT`
(`AuthConfig.derivedIssuer`: `https://auth.<base>[:port]/realms/ankka`) and keys are read over
the plain in-cluster service address (`ANKKA_AUTH_JWKS_URL`); locally, `ANKKA_AUTH_ISSUER` names
the compose Keycloak. The realm is one file, `kustomization/components/keycloak/realm-import.json`
— the `KeycloakRealmImport` itself, in JSON so compose can take the realm out of it with `jq` and
the test helpers with Jackson, while kustomize applies it as a resource; it carries no users — the
deploy script and compose's init create `dev`, so a remote installation cannot inherit one.

Who may *create* an organization is an installation setting, `OrganizationPolicy`
(`ANKKA_ORGANIZATION_CREATION` = `open` | `platform-admin`, `ANKKA_SIGNUP_URL`), read at startup and
enforced in `OrganizationEndpoint` only — feature 011, so a hosted installation's product can sell
access without the platform learning what a subscription is. A platform administrator may name the
first owner (`create-for-owner`, a separate wire name from `create` so an in-flight create survives a
rolling update); `OrganizationCreated.owner` defaults to `None` and both folds fall back to the actor.

## A credential a machine can hold, and one the cluster holds

A **deploy token** (`organizations tokens create`) is `ankka_<id>_<secret>`, shown once and stored
only as a digest. Its subject is an *ordinary organization member* (`token:<id>`, role `member`), so
every membership check, attribution rule and 404-not-403 answer applies to it unchanged and there is
no second authorization path for machines. It cannot manage members or tokens — including itself — so
a leaked one cannot mint a replacement or revoke what would stop it.

`Acl.Authenticate` runs **synchronously on the server's dispatcher**, so verifying a token may not do
I/O, and a `Consumer` runs on one node (`ShardedDaemonProcess`) while the ACL runs on whichever node
took the request. `DeployTokenIndex` is therefore a per-node `RuntimeExtension` that replays the token
journal itself: cold replay, then live follow. Creation and revocation write through to the local
index (`admit`, `evict`) so "create a token then use it" and "revoke then the next call fails" hold on
the node the CLI is talking to; other nodes learn through the journal, which is why
`controlplane/reference.conf` sets `pekko.persistence.r2dbc.refresh-interval = 500ms`.

A **project's registry credential** is the other direction: the control plane writes a
dockerconfigjson Secret into the project's namespace and records only that it did. The grant is
`secrets: create, patch` — no `get`, no `list`, no `delete` — so it can put a credential where the
kubelet reads it, can never read one back including its own, and cannot remove one; clearing a
registry stops *naming* the Secret rather than deleting it, the same rule that protects database
credentials. No password reaches the journal, and `EventCompatibilitySuite` asserts the event's wire
form has no `password` field at all. `ProjectEndpoint` takes a one-method `RegistryWriter` (the
projector) rather than the whole `AnkkaServiceClient`, so an endpoint cannot write desired state
behind the projector's back.

## A service verifies its users' tokens with one module

`ankka-auth-oidc` (feature 022) is the one token verifier in the repository: `Oidc.authenticate()`
is an `Acl.Authenticate` over the issuers a service lists as a named set of `ANKKA_AUTH_` variables
(`ANKKA_AUTH_ISSUERS=customers,staff`, then `ANKKA_AUTH_CUSTOMERS_ISSUER`, `_JWKS_URL`, `_AUDIENCE`,
optional `_CA`, `_TYP`, `_CLOCK_SKEW`). The token's `iss` picks the issuer before anything is
verified, so an unlisted issuer is refused with nothing fetched; keys are fetched on first use, never
at start, and held through an outage. nimbus is this module's dependency and no other published
module's. The sidecar reads the same set once, before it dials the process, and verifies every
`AUTHENTICATED` route with the same rule; a sidecar with such a route and no issuer is refused in
discovery's report. The operator routes `ANKKA_AUTH_` to the sidecar container only. The control
plane is a user of the module: `AuthConfig.toOidc` names the installation's issuer `ankka`, with
Keycloak's `typ: Bearer` check on, and its singular `ANKKA_AUTH_ISSUER`/`_JWKS_URL`/`_JWKS_CA` are
not part of the set, which ignores them. Tests mint tokens with the module's `TestIssuer`; the control
plane's `TestIdentity` extends it.

## Traps

- **Anything reading `~/.ankka/config.json` or `$HOME` must be overridable by a system
  property.** Environment variables cannot be set in-process, so `ANKKA_CONFIG` alone
  makes `config set` untestable without writing to the developer's own home directory.
  `Settings.path` checks `-Dankka.config` first for exactly this reason. The mirror of that trap
  bites from the *shell*: `HOME=$(mktemp -d) ankka …` does **not** isolate the CLI, because `~`
  resolves through the JVM's `user.home`, which the launcher fixes at startup — the process goes
  on reading the developer's real `~/.ankka/config.json` while the command looks isolated. Two
  attempts at feature 007's "no cluster credentials" proof were spent on a TLS error that was
  really a config never consulted. From a shell the override is `ANKKA_CONFIG`; in-process,
  `-Dankka.config`.
- **Read piped input through `Console.in`, not `System.in`.** Only the former is
  redirectable by `Console.withIn`, which is what lets a test drive `apply -f -` without
  spawning a subprocess.
- **`-Djdk.net.hosts.file` steers `java.net.http` — for the whole JVM.** Names not in the file
  stop resolving, so it can never be set on the forked test JVM. `ControlPlaneClusterSuite` runs
  the real CLI as a subprocess with it, on the same classpath; that is also the honest way to
  exercise `Main.main` and its `sys.exit`.
- **A CLI's `main` should be a one-line wrapper.** `Main.run(args, out, err): Int`
  returns the exit code and `main` calls `sys.exit` on it; `sys.exit` inside the command
  logic would kill the test JVM.
- **`KeycloakRealmImport` is one-shot.** It creates a realm that does not exist and never updates
  or deletes one; a re-apply is a no-op and deleting the resource leaves the realm. So a change to
  the realm on an existing installation is a console job. The resource *is* the checked-in file:
  the first shape had the deploy script render it from a bare `realm.json`, and the first cluster
  applied by anything else came up with a Keycloak and no realm — the same defect as the schema
  ConfigMap, found the same afternoon.
- **Keycloak writes a lone `aud` as a string and several as an array.** A test (or a verifier)
  that reads `aud` as an array sees nothing on a service-account token. nimbus handles both;
  `KeycloakAdmin.audiences` does for tests.
- **A token has no `sub` unless a scope maps it — and no name, email or roles either.** The realm
  file declares `clientScopes`, and a realm imported with its own list gets none of Keycloak's
  built-ins: `basic`, `profile`, `email` and `roles` do not exist in it, and a client that names them
  as defaults is silently given only the scopes that do. The token verified but carried no subject,
  and later no `name` (`ankka whoami` printed `(none)` for a user with both names set). Every claim
  the control plane reads is therefore mapped by the `ankka-controlplane` scope itself — subject,
  audience, email, verification, realm roles, full name and username — and `KeycloakRealmSuite`
  asserts each on a real token. A new claim needs a mapper there, not a built-in scope.
- **A Keycloak user with no first and last name cannot log in with a password grant** — "Account
  is not fully set up", a pending profile action. The deploy script, compose and the test helper
  all set both on the users they create.
- **The Keycloak operator needs all four of its CRDs, not the two ankka uses.** With only
  `keycloaks` and `keycloakrealmimports` applied it crash-loops on
  `keycloakoidcclients … Not Found` and never reconciles anything. The component's nested
  kustomization and `KeycloakStack` install the OIDC and SAML client CRDs too.
- **The control plane's issuer must equal what Keycloak writes into `iss`, port included — and
  Keycloak learns the port only from `X-Forwarded-Port`.** With a bare `hostname` it takes scheme
  and port from the proxy headers; Envoy forwards the proto and not the port, so through kind's
  8443 every token and every discovery URL named `https://auth.<base>/…` — unreachable on kind and
  a 401 on every request. The identity provider's `HTTPRoute` sets `X-Forwarded-Port` to the HTTPS
  port (the overlay replaces it from `ankka-platform.httpsPort`, the deploy script's sed too, and
  `KeycloakStack` templates the mapped port), the control plane derives the same string from
  `ANKKA_BASE_DOMAIN` and `ANKKA_HTTPS_PORT`, and `EndToEndClusterSuite` asserts the advertised
  issuer equals the derived one. `X-Forwarded-Host` with a port works as well; `Host` with a port
  does not — measured against the image, not read from the docs.
- **An operator *reports* `Paused`, so a listing row cannot infer "the members paused it" from
  its own lifecycle word.** `ServiceRows` kept `Paused` on any observation while the row said
  `Paused` — and a stale operator report of the pause, landing just after a resume, pinned the
  listing at `Paused 1/1` while `services get` said `Ready` (the k3s end-to-end suite caught it;
  the fast harness could not until it replayed that exact report). The row now carries the
  members' `paused` flag and the organization's `suspended` flag and applies the same rule as the
  entity's fold: desired state wins over a report. `SuspensionSuite` pins the sequence.
- **A CLI test that deletes the credentials file after removing the config override deletes the
  developer's own.** `Credentials.path` follows `Settings.path`; clean up *before* the property
  goes, in a directory the test owns.
- **A `Consumer` runs on one node, so it can never back a per-request check.** `ShardedDaemonProcess`
  places a consumer on one member of the cluster; an ACL runs on whichever node took the request.
  Anything every node must know — a deploy token's digest, say — is a `RuntimeExtension` with its own
  local projection of the journal, replayed on start and followed live, not a consumer writing a view.
  And because `Acl.Authenticate` is synchronous on the server's dispatcher, that projection must
  already be in memory when the request arrives: a verification that queries anything is a verification
  that blocks the dispatcher.
- **A write-through is what makes "create, then use" work on the node that created it.** An index fed
  only by the journal is a refresh interval behind, so the obvious script — mint a token, use it —
  answers 401 against the very node that minted it. `admit` on create and `evict` on revoke are not
  optimisations; without them the feature is wrong on one node and right on the others, which is the
  worst shape a bug can have.
- **No secret value in the control plane's journal.** A credential goes to the cluster and the journal
  records that it exists, where, and as whom. A journal, a snapshot, a backup of either and every view
  built from them are all readable by anything that can read Postgres, and a password in an event is
  permanent — there is no migration that unwrites it. Both places this applies (a deploy token's
  secret, a project's registry password) keep only a digest or nothing at all, and
  `EventCompatibilitySuite` asserts the absence rather than trusting it.
- **Write the cluster first, then the journal.** `PUT /projects/{id}/registry` applies the Secret and
  only then persists `RegistryConfigured`; a cluster that refused is a 503 and records nothing. The
  reverse order leaves services naming a Secret that does not exist, with the journal insisting it
  does — and nothing in the sweep can tell that from a Secret someone deleted by hand.
