# Feature Specification: An Authenticated, Multi-User Control Plane

**Feature Branch**: `008-authenticated-control-plane`

**Created**: 2026-09-22

**Status**: Draft

**Input**: User description: "spec the feature described in docs/design/authenticated-control-plane.md"

## Context

The control plane is reachable by anyone holding one shared secret. The code that checks it
says so itself: a shared token is not identity, it cannot tell two operators apart, and it says
nothing about which projects a caller may touch. It was built as the floor, not the ceiling.
This feature is the ceiling.

Three things are missing, in order of dependency:

1. **Identity.** Nothing in the platform knows *who* made a request. The journal is described
   as the audit trail, and with one shared secret it is an audit trail with no actor.
2. **Registration.** Nothing decides who is a user of an installation at all. A remotely deployed
   installation is operated with one secret, shared by every person who will ever touch it.
3. **Tenancy that means something.** Organizations exist but bound nothing: any holder of the
   secret can create, rename, delete and deploy into any organization. A project's organization
   is a label, not a boundary.

The requirement, as stated: the control plane accepts only authenticated, registered users;
projects and services belong to an organization; an organization has members; users are managed
in a self-hosted identity provider (Keycloak) so that the platform gains no third-party
dependency.

The shape chosen in the design treatment (`docs/design/authenticated-control-plane.md`): the
identity provider is the *only* authenticator and decides who is a user of the installation; the
control plane is the *only* authorizer and answers every question about organizations, membership
and roles from its own recorded state. The control plane holds no credential able to act on the
identity provider. Membership is recorded as facts on the organization, so it replays, audits and
enforces exactly like everything else the control plane records.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Lock the door (Priority: P1)

An administrator deploys the platform. From that moment, no request to the control plane
succeeds without proving who is making it. A developer on a fresh machine points the CLI at the
installation, runs `ankka login`, is shown a short code and an address to open in any browser,
signs in there with the account the administrator created for them, and the CLI completes the
login on its own. Every command they already knew then works as before. Someone without an
account, or with a login that has expired, is told plainly to log in, and nothing else.

**Why this priority**: It is the entire security value of the feature. Until it holds, nothing
built on top of it means anything, and it is independently valuable: an installation with one
organization and one user is already safer than today's.

**Independent Test**: Deploy the platform locally with one user in the identity provider. Prove
that every route refuses a request carrying no credential, an expired one, or one issued by
anything other than this installation's identity provider; then log in from the CLI with no
prior setup beyond the installation's address and trust root, and list organizations.

**Acceptance Scenarios**:

1. **Given** a deployed control plane, **When** a request arrives with no credential, **Then** it
   is refused with an answer that says authentication is required, on every route.
2. **Given** a credential that has expired, been tampered with, or was issued by another
   identity provider, **When** it is presented, **Then** the request is refused as
   unauthenticated, never as a permission problem.
3. **Given** a developer with an account and a CLI configured with only the installation's
   address and trust root, **When** they run `ankka login`, **Then** they are shown a code and
   an address, complete the sign-in in a browser on any device, and the CLI finishes without
   further input.
4. **Given** a logged-in CLI whose short-lived credential has lapsed, **When** the developer runs
   a command, **Then** the CLI renews the credential silently and the command succeeds.
5. **Given** a logged-in CLI whose renewal no longer works (the user was disabled, or the login
   was revoked), **When** a command is run, **Then** the CLI says to run `ankka login` and exits
   with a failure, without printing any credential.
6. **Given** the identity provider is unavailable, **When** a request reaches the control plane,
   **Then** the control plane is still running and answers that it cannot verify callers right
   now, rather than either accepting the request or having failed to start.
7. **Given** the shared secret that authenticated callers before this feature, **When** it is
   presented, **Then** it is refused like any other invalid credential; there is no compatibility
   mode.

---

### User Story 2 - Tenancy holds (Priority: P2)

Alice and Bob are both users of the installation. Alice creates the organization `acme` and is
its owner. Bob cannot see `acme`, its projects or its services; asking for them by name tells him
they do not exist. Alice invites Bob by his email address. The next time Bob uses the CLI, his
membership takes effect and he can create projects and deploy services in `acme`. Alice later
removes him, and his very next command against `acme` is refused.

**Why this priority**: It is what turns an organization from a label into a boundary, and it is
the second thing a multi-user platform must be able to promise. It comes after P1 because it is
meaningless until callers are identified.

**Independent Test**: With two users, create an organization as one, and prove that the other
can neither read nor write anything in it, cannot learn its identifiers exist, becomes able to
after invitation and a fresh login, and loses that ability on the request immediately after
removal.

**Acceptance Scenarios**:

1. **Given** a logged-in user, **When** they create an organization, **Then** it exists, they
   are recorded as its owner, and it appears in their organization list.
2. **Given** an organization Bob is not a member of, **When** Bob lists organizations, projects
   or services, **Then** none of that organization's are shown.
3. **Given** an organization Bob is not a member of, **When** Bob asks for it, or one of its
   projects or services, by identifier, **Then** the answer is indistinguishable from one for an
   identifier that was never used.
4. **Given** an organization Bob is not a member of, **When** Bob tries to create a project in
   it or apply a service into one of its projects, **Then** the request is refused.
5. **Given** an owner invites an email address, **When** a user whose verified email matches next
   makes a request, **Then** the invitation becomes a membership for that user and the members
   list shows them as a member rather than as pending.
6. **Given** an invitation that has not yet been claimed, **When** a user whose email is
   unverified presents that same address, **Then** the invitation is not claimed.
7. **Given** a member of an organization, **When** an owner removes them, **Then** the member's
   next request against that organization is refused.
8. **Given** a user who is a member of two organizations, **When** they list projects, **Then**
   projects from both are shown and from no others.

---

### User Story 3 - Administer an organization without the control plane holding a credential (Priority: P3)

An organization's owners manage its members from the CLI: list them, invite by email, remove,
and change a member's role between owner and member. The last remaining owner cannot be removed
or demoted, so an organization can never be left with nobody able to administer it. A platform
administrator, designated in the identity provider, can act on any organization: to see them all,
to remove a dead one, or to add an owner to one whose owners have all left. They can also
disable an organization outright: every service in every one of its projects stops, its members
can look but change nothing, and re-enabling it brings back exactly what was running before.
None of this requires the control plane to hold any credential for the identity provider.

**Why this priority**: It is the day-two half of P2. P2 can be demonstrated with one invitation;
this story makes membership something an organization can run for years.

**Independent Test**: As an owner, run every membership command and verify each takes effect on
the next request; attempt to remove the last owner and be refused; as a platform administrator,
act on an organization one is not a member of and see the action recorded as an administrative
one; disable an organization with a running service and a paused one, see the running one stop
and every write refused, re-enable it and see only the running one return.

**Acceptance Scenarios**:

1. **Given** an owner, **When** they list members, **Then** every member is shown with their
   role, and every unclaimed invitation is shown as pending with its email.
2. **Given** an owner, **When** they invite an email that is already a member, **Then** the
   request is refused and says so.
3. **Given** an owner and a pending invitation, **When** they revoke it, **Then** a later login
   by that email does not claim membership.
4. **Given** an organization with two owners, **When** one demotes the other to member, **Then**
   the demoted user can no longer manage members but can still deploy.
5. **Given** an organization with one owner, **When** that owner tries to leave, be removed, or
   be demoted, **Then** the request is refused and names the reason.
6. **Given** a member who is not an owner, **When** they try to invite, remove or change a role,
   **Then** the request is refused as a permission problem.
7. **Given** a platform administrator who is not a member of an organization, **When** they list
   organizations, **Then** every organization is shown; **When** they add an owner to one,
   **Then** it succeeds and the record of the change identifies the administrator.
8. **Given** an owner, **When** they try to delete the organization while it still has projects,
   **Then** it is refused exactly as today.
9. **Given** a platform administrator and an organization with one running and one paused
   service, **When** they disable it, **Then** the running service stops and reports itself as
   suspended, the paused one stays paused, and the organization is shown as disabled.
10. **Given** a disabled organization, **When** a member or owner tries any change in it,
    **Then** it is refused with a reason naming the organization as disabled; **When** they read
    a service, its logs or its history, **Then** it succeeds.
11. **Given** a disabled organization, **When** the administrator re-enables it, **Then** the
    service that was running before returns to running and the one its members had paused stays
    paused.
12. **Given** an owner, **When** they try to disable or re-enable their own organization,
    **Then** it is refused as a permission problem.

---

### User Story 4 - Machines log in the same way (Priority: P4)

A continuous-integration job deploys a service. It is given a credential obtained from the
identity provider for a non-interactive client, supplies it to the CLI through the existing
token flag or environment variable, and applies a descriptor with no browser and no prompt. The
record of that apply names the client as its actor. There is no second kind of credential to
create, rotate, document or audit.

**Why this priority**: Every real installation deploys from automation. It is fourth because it
is the interactive path re-used, not a new capability, and it can only be demonstrated once P1
and P2 exist.

**Independent Test**: Obtain a credential for a non-interactive client that is a member of an
organization, run `services apply` with it in a shell with no home directory and no browser, and
confirm the service's recorded history names that client.

**Acceptance Scenarios**:

1. **Given** a non-interactive client credential supplied through the token flag, **When** the
   CLI runs a command, **Then** no login is attempted and the command succeeds.
2. **Given** such a client that has not been made a member of an organization, **When** it tries
   to deploy into that organization, **Then** it is refused like any other non-member.
3. **Given** a token flag and a saved interactive login both present, **When** a command runs,
   **Then** the flag wins, matching how every other setting already resolves.

---

### User Story 5 - Read the audit trail (Priority: P5)

A developer looking at a service can see who applied its current generation and when, and who
paused, restarted, exposed or deleted it. An owner can see who invited whom. What happened before
this feature is still visible, marked as having no recorded actor rather than attributed to
anyone.

**Why this priority**: It is what "the journal is the audit trail" has to mean once there is more
than one user, and it costs nothing once actors are recorded. It is last because it reads what
the other stories write.

**Independent Test**: Perform an apply and a pause as two different users, then read the
service's history and see each action attributed to the right user with a time; read the history
of a service that predates the feature and see its actions marked as unattributed.

**Acceptance Scenarios**:

1. **Given** a service applied by Alice and paused by Bob, **When** its history is read, **Then**
   the apply names Alice and the pause names Bob, each with when it happened.
2. **Given** a service whose history predates this feature, **When** it is read, **Then** those
   entries are shown with no actor and the service otherwise behaves normally.
3. **Given** a service deployed by a non-interactive client, **When** its history is read,
   **Then** the client is named as the actor.

---

### Edge Cases

- A user's email address changes in the identity provider after they have joined an
  organization: their membership is unaffected, because membership is keyed to their stable
  identity, never their email.
- Two invitations are pending for the same email in two organizations: one login claims both.
- An invitation is pending for an email, and a different user is later given that email in the
  identity provider: whoever first presents that *verified* email claims it. Owners are shown
  pending invitations precisely so they can revoke one that should not stand.
- An organization is deleted while it has members: the memberships go with it, and the
  identifier stays taken as it does today.
- A platform administrator is also an ordinary member somewhere: their administrative power is
  noted only when it was needed, so the audit trail distinguishes "acted as owner" from "acted as
  administrator".
- An organization is disabled while a service apply is in flight: the apply either completes
  and the service is then suspended, or is refused; it never leaves a running service in a
  disabled organization.
- A member pauses a service, the organization is disabled, and the member resumes it while
  disabled: the resume is refused, so re-enabling restores the state from before the disable.
- A disabled organization is deleted: its services must already be gone, as for any deletion.
- A caller is a member of the organization but the project they name belongs to another: the
  project is reported as not existing, not as forbidden.
- The identity provider revokes or disables a user mid-session: the control plane refuses them
  within one short-lived credential lifetime; removal from an organization is immediate.
- A request arrives while the control plane's cached knowledge of the identity provider's signing
  keys is stale (the provider rotated keys): the control plane refreshes once and verifies; it
  never accepts on stale trust.
- The CLI is used from a machine with no browser: the login prints the code and the address and
  waits; the sign-in can be completed anywhere.
- Two control planes are configured in the CLI over time: logins are kept per installation
  address, so switching the address does not present one installation's credential to another.

## Requirements *(mandatory)*

### Functional Requirements

**Authentication**

- **FR-001**: Every control plane route MUST require a valid credential issued by the
  installation's identity provider, except one unauthenticated discovery route that reveals only
  where the identity provider is and which public client the CLI uses.
- **FR-002**: A request with no credential, or an invalid one (expired, tampered, issued by a
  different provider, signed with an unrecognised key after one refresh of the provider's keys,
  or not of the expected type), MUST be refused as *unauthenticated*, with a challenge that lets
  the CLI recognise the condition.
- **FR-003**: A request with a valid credential whose holder lacks the right to the action MUST
  be refused as *forbidden*, distinguishable from FR-002.
- **FR-004**: Verification MUST NOT contact the identity provider on the request path once its
  signing keys are cached; keys are refreshed on a schedule and once on encountering an unknown
  key.
- **FR-005**: The control plane MUST refuse to start without an identity provider configured,
  and MUST keep running when the configured provider is unreachable, answering requests with a
  temporary-failure status until it can verify again.
- **FR-006**: The caller's identity MUST be the identity provider's stable subject identifier.
  Email and display name MUST be used for display only and never as a key.
- **FR-007**: The shared secret used before this feature, its configuration key and its
  deployment secret MUST be removed. There is no compatibility mode.

**Registration and administration**

- **FR-008**: Who is a user of an installation MUST be decided in the identity provider.
  Self-registration MUST be disabled in the shipped configuration; an administrator adds users
  there.
- **FR-009**: The identity provider MUST be deployed as part of the installation by the same
  single command that deploys everything else, with its realm defined declaratively from one
  copy that local development also uses.
- **FR-010**: The local installation MUST include a development user and administrator secret;
  the remote installation MUST include neither, and MUST NOT be able to start its identity
  provider until an administrator secret is supplied out of band, mirroring how the development
  secret is removed today.
- **FR-011**: The control plane MUST hold no credential for the identity provider's
  administration; every membership operation MUST be achievable without one.
- **FR-012**: A designated installation-level role in the identity provider (`platform-admin`)
  MUST allow its holder to list every organization, to act on any organization as an owner, and
  to disable and re-enable any organization; every use of that role where ordinary membership
  would not have sufficed MUST be recorded as administrative.

**Organizations and membership**

- **FR-013**: Any authenticated user MUST be able to create an organization and MUST become its
  first owner. Creation is self-service; there is no installation setting that restricts it.
- **FR-014**: An organization MUST record its members, each with a role, and its pending
  invitations, each an email with a role, as durable facts that replay with the organization.
- **FR-015**: The roles MUST be `owner` (everything a member can do, plus manage members, rename
  and delete the organization) and `member` (create, rename and delete projects; every service
  operation within them). No other role exists in this feature; a read-only role is deferred.
- **FR-016**: An owner MUST be able to invite by email, revoke a pending invitation, remove a
  member, and change a member's role. Inviting an existing member MUST be refused.
- **FR-017**: A pending invitation MUST become a membership the first time a request arrives
  from a user whose *verified* email matches; an unverified email MUST NOT claim it.
- **FR-018**: The last owner of an organization MUST NOT be removable or demotable.
- **FR-019**: Deleting an organization MUST remove its memberships and invitations with it, and
  MUST still be refused while it has projects.

**Disabling an organization**

- **FR-032**: A platform administrator MUST be able to disable an organization and later
  re-enable it. Only a platform administrator can do either; an owner cannot.
- **FR-033**: Disabling an organization MUST stop every service in every one of its projects:
  each is brought to zero running instances and reports itself as suspended, distinct from a
  service its members paused.
- **FR-034**: While an organization is disabled, its members MUST still be able to read it, its
  projects, its services, their logs and history, and MUST be refused every change — creating,
  renaming or deleting projects, applying, resuming, restarting, exposing or unexposing
  services, and every membership change — with a refusal that names the organization as
  disabled. Membership is otherwise unaffected: nobody is removed and no invitation is revoked.
- **FR-035**: Re-enabling an organization MUST return to running every service that was running
  when it was disabled, and MUST leave paused every service its members had paused before it
  was disabled.
- **FR-036**: Listings and reads MUST show whether an organization is disabled, and a
  suspended service MUST be distinguishable in its status from a paused one.
- **FR-037**: A disabled organization MAY still be deleted by a platform administrator under the
  existing rule that it has no projects.

**Authorization of every action**

- **FR-020**: Authorization for a project or service action MUST be resolved from the project's
  organization and the caller's role in it, read from the authoritative record at the time of the
  request, so that removal takes effect on the next request.
- **FR-021**: Listings of organizations, projects and services MUST include only those the
  caller is a member of (all of them for a platform administrator). Listings MAY reflect a recent
  change with a short delay; enforcement MUST NOT.
- **FR-022**: Reading an organization, project or service the caller is not a member of MUST
  answer exactly as for one that does not exist, so identifiers cannot be probed.

**Audit**

- **FR-023**: Every recorded change to an organization, project or service that resulted from a
  command MUST carry the actor's identity, and, when administrative power was used, that fact.
- **FR-024**: Records made before this feature MUST remain readable and MUST present as having
  no actor.
- **FR-025**: A user MUST be able to read a service's history of changes with actor and time
  through the CLI.

**The CLI**

- **FR-026**: The CLI MUST gain `login` (a code-and-address flow completable in any browser on
  any device), `logout`, and `whoami`, and MUST learn where the identity provider is from the
  control plane it is already configured for, so that no new setting is needed to log in.
- **FR-027**: Saved logins MUST be stored per installation address, readable only by the user,
  and never printed in any output format. Short-lived credentials MUST be renewed silently.
- **FR-028**: The existing token flag and environment variable MUST continue to mean "present
  this credential as given", so non-interactive clients authenticate with no second mechanism,
  and MUST take precedence over a saved login.
- **FR-029**: The CLI MUST gain `organizations members list|add|remove|role`, with `add` taking
  an email and a role.
- **FR-030**: When refused as unauthenticated, the CLI MUST say to log in; when refused as
  forbidden, it MUST say the caller is not permitted; both exit as failures with the existing
  exit codes.
- **FR-031**: The trust root the CLI is already given for the control plane MUST also be used
  when it talks to the identity provider at the installation's address.

### Key Entities

- **User**: a person or automated client known to the identity provider. Identified by a stable
  subject identifier; carries a display name, an email, whether that email is verified, and any
  installation-level roles. Not stored by the control plane beyond references to its identifier.
- **Organization**: the tenancy boundary. Now additionally holds its members (user identifier,
  role), pending invitations (email, role), and whether it is disabled, all recorded as facts in
  its history.
- **Service (changed)**: gains a *suspended* state, set and cleared by its organization being
  disabled and re-enabled, held separately from the *paused* state its members control, so that
  re-enabling restores exactly what the members had chosen.
- **Membership**: a user's role in an organization: `owner` or `member`. Created by claiming an
  invitation or by an owner's or administrator's action.
- **Invitation**: an email address and a role, pending until a user with that verified email
  makes a request; revocable by an owner until then.
- **Actor**: the identity attached to every recorded change; absent on records that predate the
  feature; flagged when administrative power was required.
- **Login**: what the CLI keeps per installation after `ankka login`: a renewable credential,
  stored privately, never displayed.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a deployed installation reached through its real external address, 100% of
  requests without a valid credential are refused on every route, including listings; proven
  against the real installation, not a simulation.
- **SC-002**: A member of one organization can complete 0 reads or writes against another
  organization's projects or services, and 0 attempts reveal whether a given identifier exists.
- **SC-003**: Removal from an organization takes effect on the next request, every time; a user
  disabled in the identity provider is refused within five minutes.
- **SC-004**: A developer on a fresh machine goes from an installation address and trust root to
  a successful `services list` in under two minutes, with no step beyond `ankka login`.
- **SC-005**: Verifying a caller adds no network round trip to a request after the first, and its
  cost, measured against a real request the way the tracing overhead was, is under 1% of that
  request.
- **SC-006**: The local installation still deploys with one command, and its smoke test now logs
  in as a user before declaring success.
- **SC-007**: The control plane process holds zero credentials for the identity provider's
  administration, verifiable by inspecting its deployed configuration.
- **SC-008**: A continuous-integration job deploys with a non-interactive credential and zero
  prompts, and the resulting history names that client.
- **SC-009**: Every change recorded after the feature ships carries an actor; every change
  recorded before it remains readable.
- **SC-010**: Disabling an organization brings 100% of its running services to zero instances,
  and re-enabling it returns exactly the set that was running, with 0 services that its members
  had paused coming back.

## Assumptions

- The identity provider is Keycloak, self-hosted inside the installation and deployed through
  the Keycloak operator, as the design treatment decided and the user confirmed. The platform's
  own instance is kept separate from any future tenant-facing identity, so an outage or upgrade
  on the tenant side cannot lock anyone out of operating the platform.
- Short-lived credentials last five minutes; this is the revocation latency for a user disabled
  in the identity provider and is a realm setting an installation may change.
- Pending invitations do not expire; owners see and can revoke them.
- Membership is at the organization level only. There are no per-project roles, and no
  read-only role in this feature (decided at specification).
- Organization creation is self-service for every registered user (decided at specification);
  the administrative control over tenants is disabling an organization, not gating its creation.
- A disabled organization keeps its members, invitations, projects, descriptors and data;
  disabling stops workloads and freezes changes, nothing more. Any data-retention or deletion
  policy is the administrator's, applied through the existing delete commands.
- Organization identifiers, project identifiers and service names keep their existing rules and
  their existing tombstone behaviour.
- The identity provider's own administration console is the way users are created, disabled and
  have passwords reset; the platform provides no user-management commands of its own.
- The local console (`ankka local console`) is unaffected: it remains loopback-only and holds no
  credential.
- The end-to-end cluster test deploys the identity provider for real, accepting tens of seconds
  of additional startup, because the deployment path is the thing under test. Whether the other
  cluster suites do the same or verify against a locally served key set is a planning decision.

## Out of Scope

- Authentication for the services ankka hosts. A deployed service's endpoints keep their own
  access rules. Provisioning identity *for* hosted services (a realm per project, a credential per
  service, a token check in the HTTP module) is a feature of its own; this feature's choice of the
  operator is what makes it an addition rather than a replacement.
- Per-project roles, teams, or a read-only role (see FR-015).
- A browser console for the control plane.
- Federating the identity provider to external providers; that is an installation's realm
  configuration, not a platform feature.
- Sending invitation emails. The platform records an invitation; the identity provider is how a
  user gets an account.
