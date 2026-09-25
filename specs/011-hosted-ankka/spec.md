# Feature Specification: A Platform a Hosted Product Can Provision

**Feature Branch**: `011-hosted-ankka`

**Created**: 2026-09-24

**Status**: Draft

**Input**: User description: "Hosted ankka: the platform-side changes that let a separate product (the ankka-cloud website) sign customers up and provision them on an installation. Scope is the platform slice of docs/design/hosted-ankka.md only."

## Context

The platform authenticates its operators and bounds tenancy: a user of an installation is whoever
its identity provider knows, and once known they may create organizations and deploy into them at
no cost. That is right for a company installing the platform for itself and wrong for a product
anyone can sign up for, where being *registered* and being *entitled* are different facts and the
gap between them is the checkout.

The design treatment (`docs/design/hosted-ankka.md`) puts the product — signup, payment,
provisioning — in a repository of its own, and leaves the platform with exactly the changes a
product needs to drive it and none of the product's own concepts. This feature is that slice.
It has four parts:

1. An installation can say who creates organizations: anyone who is logged in (today, and still
   the default), or the platform administrator only. In a hosted installation the product's own
   service account is the administrator, creates each organization after checkout, and a
   registered user cannot create one past it.
2. An administrator creating an organization can name its first owner, so the customer — not the
   administrator's service account — owns what was created for them, in one step.
3. The control plane's wire types and descriptor rules are published as a library, so a client
   outside this repository speaks the same protocol the CLI does.
4. An installation can trust an identity provider hosted elsewhere, proven end to end, so several
   installations can share one set of users. The platform already allows this by configuration;
   what is missing is the proof that it holds on a real cluster.

Everything else in the treatment — the website, Stripe, quotas, pull credentials, and anything in
`ankka-cloud` or `ankka-deployments` — is out of scope here.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Only the administrator creates organizations here (Priority: P1)

An installation is configured so that organizations are created by the platform administrator
only. A registered user logs in from the CLI and tries to create an organization. They are refused
with a message that says organizations in this installation are created for them, and — when the
installation supplies one — where to go to get one. Everything else they are entitled to still
works: they see the organizations they belong to, deploy into them, and accept invitations. An
installation that says nothing behaves exactly as before: anyone logged in may create one.

**Why this priority**: Without it, a hosted installation gives away compute to anyone with a
verified email, and nothing else in the treatment can be enforced. It is also the smallest change
with the whole security value.

**Independent Test**: Run the control plane with the setting at each of its two values. With the
default, a plain user creates an organization and becomes its owner as today. With the restricted
value, the same request from the same user is refused, the refusal names why, an administrator's
identical request succeeds, and every other route the user is entitled to answers as before.

**Acceptance Scenarios**:

1. **Given** an installation with the default setting, **When** a logged-in user creates an
   organization, **Then** it exists with them as its first owner, exactly as before this feature.
2. **Given** an installation restricted to the administrator, **When** a logged-in user who is not
   an administrator creates an organization, **Then** the request is refused as forbidden, no
   organization exists, and the refusal says organizations here are created by the administrator.
3. **Given** the same restricted installation and a sign-up address supplied by the installation,
   **When** the refusal is produced, **Then** the message includes that address, and the CLI
   prints it.
4. **Given** the same restricted installation, **When** a platform administrator creates an
   organization, **Then** it succeeds.
5. **Given** the same restricted installation, **When** a non-administrator lists, reads, renames
   or deletes an organization they own, manages its members, or deploys a service into one of its
   projects, **Then** every such request answers exactly as it would with the default setting.
6. **Given** a setting with a value that is neither of the two, **When** the control plane starts,
   **Then** it refuses to start and names the setting and its allowed values.

---

### User Story 2 - The administrator creates an organization for its owner (Priority: P1)

A product's service account, which is a platform administrator, has just taken a payment from a
customer. It creates the customer's organization in one request that names the customer as the
first owner. The organization exists with the customer as its only owner; the record of the
creation shows that the administrator made it and for whom. The customer's next CLI login sees
the organization and can deploy into it. A caller who is not an administrator cannot name an
owner: they may only create organizations for themselves, when the installation allows it.

**Why this priority**: Without it the administrator becomes the first owner, and handing the
organization over is three requests — create, add the customer, remove itself — with a window in
which a retry or a crash leaves an organization the customer cannot administer or the service
account cannot leave. One request is the whole difference between a provisioning step that can be
retried and one that has to be repaired.

**Independent Test**: As an administrator, create an organization naming a subject and their
display details as owner; read the organization's members and see exactly one owner, the named
subject, with the display details given; read the history and see the administrator as the actor.
Repeat as a non-administrator naming an owner and see the request refused.

**Acceptance Scenarios**:

1. **Given** a platform administrator, **When** they create an organization naming an owner
   (subject, email, display name), **Then** the organization exists, its members are exactly that
   one owner with those details, and the administrator is not a member.
2. **Given** the organization was created that way, **When** the named owner logs in and lists
   organizations, **Then** it is listed and they may act on it as an owner.
3. **Given** the organization was created that way, **When** its history is read, **Then** the
   creation records the administrator as the actor and the named subject as the first owner.
4. **Given** a platform administrator, **When** they create an organization naming no owner,
   **Then** they become its first owner, as before this feature.
5. **Given** a caller who is not a platform administrator in an installation with the default
   setting, **When** they create an organization naming an owner, **Then** the request is refused
   as forbidden and no organization exists.
6. **Given** an owner named with a subject but no display details, **When** the organization is
   created, **Then** it succeeds and the member is shown by subject until they log in.
7. **Given** an organization created with a named owner, **When** the platform is restarted and
   the organization's state rebuilt from its history, **Then** the members are unchanged.

---

### User Story 3 - A client outside this repository speaks the control plane's protocol (Priority: P2)

A developer building a product on the platform, in a repository that depends only on published
ankka libraries, adds one more dependency and has the control plane's request and response types,
its status words and error codes, and the same descriptor validation the platform applies — so
the client refuses a bad descriptor before sending it, with the platform's own message, and reads
every answer the CLI can read.

**Why this priority**: The product's provisioning is a client of the control plane; without the
published types it would redefine the protocol by hand and the two would drift, which is exactly
the disagreement the CLI's tests exist to catch. It is P2 because a hand-written client would work
until it drifted; the first two stories are what make provisioning possible at all.

**Independent Test**: Publish locally; from a scratch project outside this repository depending
only on published artifacts, decode a control plane response and validate a descriptor, with no
checkout of this repository on the path.

**Acceptance Scenarios**:

1. **Given** a local publish, **When** the published artifacts are listed, **Then** the control
   plane's wire library is among them and the count of published artifacts is seven.
2. **Given** a project outside this repository depending on that artifact alone, **When** it
   compiles a descriptor validation and a response decode, **Then** it builds and runs with no
   other ankka library than the one the wire library itself depends on.
3. **Given** the published wire library, **When** its dependencies are listed, **Then** it depends
   on no actor system, database driver or Kubernetes client.

---

### User Story 4 - An installation trusts an identity provider hosted elsewhere (Priority: P2)

An operator deploys a second installation with no identity provider of its own, configured to
trust the realm of the first. A user registered once, at the first installation's identity
provider, logs in from the CLI against the second installation and is recognised: the CLI's login
sends them to the shared provider, and the second control plane accepts the resulting token,
records them as the actor of what they do, and refuses a token from any other issuer.

**Why this priority**: It is what makes "one set of users, several installations" true, and it
must be proven on a real cluster because the shape is two hosts and two trust decisions, which
no in-process test exercises. It is P2 because the platform already permits it by configuration;
the proof is what is missing.

**Independent Test**: On a real cluster, run a control plane whose trusted issuer and key source
name a realm served from a different host than its own base domain; obtain a token from that
realm; make an authenticated request and see it accepted with the caller recorded; present a token
from a realm on the control plane's own base domain and see it refused.

**Acceptance Scenarios**:

1. **Given** a control plane configured with an explicit issuer and key source on another host,
   **When** it starts, **Then** it runs with no identity provider of its own deployed.
2. **Given** that control plane, **When** the CLI asks where to log in, **Then** it is told the
   remote issuer, and a login against it completes.
3. **Given** a token from the remote realm, **When** it is presented, **Then** the request is
   accepted and the caller is recorded as the actor.
4. **Given** a token from a different realm, **When** it is presented to that control plane,
   **Then** it is refused as unauthenticated.

---

### Edge Cases

- A restricted installation's administrator creates an organization for an owner whose subject
  has never logged in anywhere: the organization exists, the member is listed by subject with
  whatever display details were given, and the owner's first login sees it.
- An administrator names as owner a subject that is the administrator's own: it succeeds, and the
  administrator is the sole owner, which is the same outcome as naming no owner.
- An administrator names an owner and the organization id already exists or was deleted: refused
  as a conflict, as today, and the named owner is not touched anywhere.
- The setting is restricted after organizations already exist: existing organizations and their
  owners are unaffected; only creation changes.
- The sign-up address is supplied but the setting is the default: the address is unused and
  nothing changes.
- The remote issuer is unreachable when the spoke control plane starts: it starts, and requests are
  refused as unavailable until keys can be fetched, as for a local issuer today.
- A token whose issuer is the spoke's *own* derived issuer, in an installation configured to trust
  the remote one: refused; only the configured issuer is trusted.

## Requirements *(mandatory)*

### Functional Requirements

Organization creation policy:

- **FR-001**: An installation MUST be able to state who may create organizations, with exactly two
  values: anyone who is logged in, or the platform administrator only. The default MUST be the
  former, and an installation that says nothing MUST behave identically to before this feature.
- **FR-002**: In an installation restricted to the administrator, a request to create an
  organization from a caller who is not a platform administrator MUST be refused as forbidden and
  MUST create nothing.
- **FR-003**: The refusal MUST say that organizations in this installation are created by the
  platform administrator, and MUST include a sign-up address when the installation supplies one.
  The CLI MUST print that message.
- **FR-004**: The restriction MUST affect organization creation only. Every other route MUST
  answer exactly as it does with the default value.
- **FR-005**: A value for the setting that is neither of the two MUST prevent the control plane
  from starting, with a message naming the setting and its allowed values.
- **FR-006**: The setting and the sign-up address MUST be documented in the configuration
  reference alongside the control plane's other installation settings.

Creating an organization for an owner:

- **FR-007**: A platform administrator creating an organization MUST be able to name its first
  owner: a subject, and optionally an email and a display name.
- **FR-008**: An organization created that way MUST have exactly one member, the named subject as
  owner with the details given, and the administrator MUST NOT be a member.
- **FR-009**: The creation MUST be recorded with the administrator as the actor and the named
  subject as the first owner, and rebuilding the organization from its history MUST reproduce the
  same members.
- **FR-010**: A caller who is not a platform administrator naming an owner MUST be refused as
  forbidden, regardless of the installation's creation policy.
- **FR-011**: A creation naming no owner MUST behave exactly as before this feature: the caller
  becomes the first owner.
- **FR-012**: The CLI MUST be able to name an owner when creating an organization, and the
  control plane's route reference MUST describe the option.

Published wire library:

- **FR-013**: The control plane's wire types and descriptor validation MUST be published as a
  library under the same coordinates and version as the other published libraries.
- **FR-014**: That library MUST depend on no actor system, database driver, Kubernetes client or
  HTTP server — only on the platform's core library.
- **FR-015**: The published set MUST be exactly seven libraries, and the documentation and the
  build's own accounting of "what is published" MUST say so.

Trusting a remote identity provider:

- **FR-016**: A control plane configured with an explicit issuer and key source MUST accept tokens
  from that issuer and refuse tokens from any other, including one at its own base domain.
- **FR-017**: The login discovery route of such a control plane MUST advertise the configured
  issuer, so the CLI's login goes to the shared provider.
- **FR-018**: The shape MUST be proven by the end-to-end cluster suite: one realm, two control
  planes on different base domains, one user accepted by both.
- **FR-019**: The documentation for installing on a cloud cluster MUST describe the spoke shape:
  which two settings to set, and that no identity provider is deployed for it.

### Key Entities

- **Creation policy**: an installation-level fact with two values — open, or administrator only —
  and an optional sign-up address to point refused callers at. It is configuration, not state.
- **Organization creation with a named owner**: the existing creation, extended so the first
  owner may be a subject other than the caller when the caller is a platform administrator. The
  recorded creation carries both the actor and the first owner.
- **Wire library**: the control plane's request and response types, status and error vocabulary,
  and descriptor validation, published as a library.
- **Spoke installation**: a control plane and operator with no identity provider of their own,
  trusting a realm on another host by explicit configuration.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An installation deployed with the default configuration passes every existing test
  suite unchanged — no test in the repository needs modification for the default to hold.
- **SC-002**: In a restricted installation, 100% of organization-creation attempts by
  non-administrators are refused with the documented message, and 100% of every other kind of
  request from the same users answer as with the default, across the full authorization matrix.
- **SC-003**: An administrator provisions an organization for a customer in one request, and the
  customer can deploy into it on their next login with no further action by anyone.
- **SC-004**: A project outside this repository builds a working control plane client against
  published artifacts only, with no checkout of this repository.
- **SC-005**: On a real cluster, a user registered once logs in against two installations on
  different base domains and is accepted by both, and a token from any other issuer is refused by
  each.
- **SC-006**: Every new setting, route option and CLI flag appears in the generated reference
  pages, and the documentation build passes.

## Assumptions

- The sign-up address is a second, optional installation setting beside the creation policy. The
  control plane has no other way to know where a product's website is, and a refusal that names
  nowhere to go is a dead end for a registered user.
- The owner's display details are the email and display name the organization already records for
  members added by an administrator; no new member attributes are introduced.
- "Platform administrator" keeps its existing meaning: a caller whose token carries the
  installation-level administrator role from the identity provider.
- The spoke's key source is reached over the public address of the shared realm, with a real
  certificate; there is no in-cluster address to a realm on another cluster.
- The end-to-end proof reuses the cluster suite's existing identity provider as the shared realm
  and adds a second control plane beside the first, rather than a second cluster.
- Publishing the wire library changes its coordinates from unpublished to published and nothing
  about its contents; existing consumers in this repository are unaffected.
- The product's own service account, the website, Stripe, quotas, pull credentials and per-
  installation scoping of the administrator role are out of scope, per the design treatment.
