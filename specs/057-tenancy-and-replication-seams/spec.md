# Feature Specification: Tenancy and Replication Seams — What the Family's Products Ask of the Platform

**Feature Branch**: `057-tenancy-and-replication-seams`

**Created**: 2026-10-22

**Status**: Draft

**Input**: User description: "What the family's downstream products ask of the platform for
replication across installations and for a vendor's agent in a tenant project: (1) the replication
transport seam opened to a transport beside the runtime's own; (2) a forwarded replicated write
reaching its home as a call by the forwarding installation's identity, with the person or principal
who acted carried as attribution; (3) a deploy token scoped to one project; (4) a platform
administrator able to issue a deploy token for an organization they are not an owner of and to
accept a pending grant on an organization's behalf; (5) `GET /installation` reporting the
installation's erasure, broker and longest topic retention beside its backups."

## Context

Two products of the family are the platform's first customers for replication across
installations and for a vendor's agent inside a customer's project, and each has written down what
the platform lacks (`ankka-replication/notes/ankka-requests.md`, requests 1, 2, 3 and 5;
`ankka-vendor/notes/ankka-requests.md`, requests 1, 2 and 4). This spec answers them in one
release, so that neither product works around the platform (family principle 6). Everything here
is a seam widened or a fact reported; nothing is a product feature, and nothing here names a
vendor, a casino or a module.

Spec 051 defined the replication transport as a seam and shipped the runtime's own transport. A
transport beside it (ankka-replication, licensed) can carry records and forwarded commands, but
cannot deliver a handover's report to the local copy, is not told which components are replicated,
cannot be put under the test kit's installations group, and cannot be named in the environment
without a database. A forwarded write today reaches the home as the call it was, which leaves
open who the home sees as its caller once installations belong to different organizations; the
family decided on 2026-10-22 that the home sees the forwarding installation's identity, and the
one who acted travels as attribution. A deploy token is a member of its whole organization, which
is wider than an agent deployed into one project should hold; only an owner can mint one, so a
product that provisions an organization for a stranger cannot deploy its agent before the stranger
signs in; and the installation reports its backups but not the other facts a product's
requirements name.

## Clarifications

### Session 2026-10-22

- Q: May every project-scoped deploy token make and end its project's grants, or is that asked for
  at creation? → A: every project-scoped token may; there is no opt-in flag. Organization-wide
  deploy tokens keep today's rule.
- Q: Does 057 define how the home admits a forwarding installation of another organization? → A:
  no. 057 defines the forwarding installation's identity as the caller and the attribution; a grant
  whose grantee is an installation's identity is ankka-replication spec 002's. Within one
  organization or one machine nothing changes.
- Q: Does the attribution reach a handler in every SDK language, or Scala only? → A: every
  language; the sidecar's protocol carries it beside the caller it already carries, under the
  version bump the release makes anyway. (Coordinator's decision, for Trevor to overrule.)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A deploy token for one project (Priority: P1)

An owner of an organization, or the platform administrator acting for a product that provisions
organizations, creates a deploy token for one project of the organization. The token's holder can
do in that project what a member can: apply and delete services, declare topics, write secrets,
expose services, read the project's status, history and grants; and, because an agent that deploys
a module must open what the module declares, it can make and end the project's own grants, which
until now only an owner could. Outside its project the token is told there is no such project,
exactly as a member of another organization is told. It cannot do what only an owner can: invite
members, manage tokens, accept a grant offered to the organization, rename or delete anything above
its project. Every change it makes is attributed to the token, as today.

**Why this priority**: A vendor's agent in a tenant project is the family's first case of a
machine that must hold exactly one project and nothing beside it; without this the agent holds an
organization-wide credential, which the vendor's spec records as out of policy.

**Independent Test**: Create a project-scoped token; apply a service, declare a topic, make a grant
in its project; be refused a service in a sibling project with "no such project"; be refused
creating a token.

**Acceptance Scenarios**:

- added `features/github/deploy-tokens.feature`: a deploy token scoped to one project acts in that project and is told there is no other
- added `features/github/deploy-tokens.feature`: a project-scoped deploy token makes and ends its project's grants
- added `features/github/deploy-tokens.feature`: a project-scoped deploy token may not do what only an owner may
- added `features/github/deploy-tokens.feature`: a deploy token's list says which project a token is scoped to

---

### User Story 2 - A platform administrator issues a token and accepts a grant for an organization (Priority: P1)

A platform administrator creates a deploy token, organization-wide or project-scoped, for an
organization they are not a member of, and accepts a pending grant offered to an organization on
its behalf. Both are recorded in the organization's history as the administrator's act under the
platform-admin role, as adding an owner already is, so the organization's owners can see that the
platform, not one of them, did it.

**Why this priority**: A product that turns a stranger into a customer provisions the
organization and must deploy into it before any owner has signed in; today it has to make itself
an owner of every customer's organization to mint a token or accept a grant, which is wider than
it should be and is visible to the customer as a foreign owner.

**Independent Test**: As a platform administrator with no membership, create a project-scoped
token for an organization and accept a pending grant to it; read the organization's history and
find both entries attributed to the administrator with the role that allowed them.

**Acceptance Scenarios**:

- added `features/organizations/administrators.feature`: a platform administrator creates a deploy token for an organization they are not a member of
- added `features/organizations/administrators.feature`: a platform administrator accepts a pending grant on an organization's behalf
- added `features/organizations/administrators.feature`: an organization's history shows an administrator's act under the role that allowed it

---

### User Story 3 - A transport beside the runtime's own (Priority: P1)

A service with another replication transport on its classpath (ankka-replication) runs across
installations as one with the runtime's own does. The transport is told at start which components
are replicated, so it serves and tails those and no others; it can deliver a handover's report to
the local copy through a public operation rather than a private message; a test can start several
installations under the test kit's installations group over the transport the test supplies; and
an installation reached through such a transport is named in the environment by name alone, with
no database, as `ANKKA_INSTALLATIONS=a,b,c`.

**Why this priority**: ankka-replication spec 001 is tasked against these four seams and carries a
debt list in its `CLAUDE.md` for each; they are small and block the family's first paid layer.

**Independent Test**: A test transport in the test kit's installations group replicates an entity
between two installations, delivers a handover's report, and is given exactly the replicated
components; a service started with `ANKKA_INSTALLATIONS=leipzig,bratislava` and no database for
them names both.

**Acceptance Scenarios**:

- added `features/replication/transport.feature`: a transport beside the runtime's own is given the replicated components it serves
- added `features/replication/transport.feature`: a transport beside the runtime's own delivers a handover's report to the local copy
- added `features/replication/transport.feature`: a service names an installation it reaches through a transport beside the runtime's own by its name alone
- added `features/replication/testkit.feature`: the test kit starts several installations over a transport the test supplies

---

### User Story 4 - Who the home sees on a forwarded write (Priority: P2)

A write forwarded from one installation to the entity's home reaches the home as a call made by
the forwarding installation's own identity, admitted by what that identity is granted, as any call
across installations would be. The principal who made the original call travels with the forwarded
command as its attribution: the handler at the home can read it, the journal records it with the
event, and the entity's history shows who acted and where the write was forwarded from (one
scenario covers the call, the attribution and the history line). The
forwarding installation never lends the caller's token.

**Why this priority**: Decided for the family on 2026-10-22; the vendor's agent permission model
and ankka-replication's spec 002 are designed against it. It changes no behaviour on one machine
or within one organization, where the forwarding installation is admitted anyway.

**Independent Test**: Forward a write from a follower where a person called; at the home the
caller is the follower installation and the attribution is the person's subject; the history line
shows both.

**Acceptance Scenarios**:

- added `features/replication/forwarding.feature`: a forwarded write reaches the home as a call by the forwarding installation, with the caller who acted as its attribution
- added `features/replication/forwarding.feature`: a forwarded write from an installation the home does not admit is refused naming the installation

---

### User Story 5 - The installation reports what a requirement asks (Priority: P2)

Any caller with a token reads, in the one read of the installation that already reports its
backups, whether erasure is on and how many keyring instances hold its keys, how many nodes the
broker has and the copies and in-sync minimum it gives a topic by default, and the longest
retention a topic may be given, or that it is unbounded. A fact the control plane cannot read is
reported as not stated, never guessed; an older control plane leaves the fields absent.

**Why this priority**: A product's module may require facts of the installation before it is
applied (a real-money brand needs backups off the cluster, erasure on, three broker nodes and
unbounded retention); the agent that applies it must read them in one call rather than infer them.

**Independent Test**: Read the installation on a kind cluster: erasure off, one broker node, one
default copy, the shipped retention bound; on a cluster configured for the opposite, the opposite.

**Acceptance Scenarios**:

- added `features/installation/facts.feature`: what the installation reports includes its erasure, its broker and its longest retention
- added `features/installation/facts.feature`: a fact the control plane cannot read is reported as not stated

---

### Edge Cases

- A project-scoped token for a project that is later deleted: the token is told there is no such
  project from then on and can do nothing; it is listed with the project it was scoped to until
  revoked.
- A project-scoped token asked to accept a grant its project received: refused, as a member is;
  acceptance stays an owner's act (or a platform administrator's, US2).
- A project-scoped token making a grant to a grantee of another organization: the grant is pending
  as any such grant is; the token cannot accept it on the other side.
- A platform administrator's token or acceptance for an organization that does not exist: refused
  with not found, nothing recorded.
- A forwarded write whose attribution names a principal the home has never seen: recorded as
  given; the home authorizes the forwarding installation, never the attribution.
- A forwarded write arriving at the home from an installation in the replication set but not
  granted at the home: refused naming the installation; the caller at the follower is told the
  home refused the forwarding installation, not that the home is unreachable.
- A transport beside the runtime's own given no replicated components (a service with none
  declared): started with an empty set, serving nothing.
- `ANKKA_INSTALLATIONS` naming an installation that also has a database in the environment: the
  two forms agree on the name and the database form adds the database; a name in one and not the
  other is one installation.
- The installation read by an older CLI or console: the new fields are ignored; nothing breaks.

## Requirements *(mandatory)*

### Functional Requirements

**A deploy token for one project**

- **FR-001**: An owner of an organization MUST be able to create a deploy token scoped to one
  project of the organization, naming the project at creation; the token's subject MUST be a
  member of that project and of no other, told "no such project" for any other project of the
  organization exactly as a stranger is, and listed with the project it is scoped to.
- **FR-002**: A project-scoped deploy token MUST be able to do in its project everything an
  organization-wide deploy token can, and in addition make and end the project's own grants, with
  no flag to ask for that right; it MUST NOT be able to accept, decline or relinquish a grant, manage members or tokens, or act on
  the organization above its project.
- **FR-003**: A project-scoped token's changes MUST be attributed to the token as today, and its
  scope MUST survive the project's renaming and end with the project's deletion.

**A platform administrator acting for an organization**

- **FR-004**: A platform administrator MUST be able to create a deploy token, organization-wide or
  project-scoped, for any organization, and to accept a pending grant offered to any organization,
  without being a member of it; each act MUST be recorded in the organization's history as the
  administrator's, under the platform-admin role, as adding an owner is.
- **FR-005**: An administrator's token or acceptance for an organization or grant that does not
  exist MUST be refused with not found and record nothing.

**The replication transport seam**

- **FR-006**: The runtime MUST give a replication transport, when it is created, the set of
  replicated components of the service, so a transport serves and tails those components only.
- **FR-007**: The runtime MUST offer a transport a public operation that delivers a handover's
  report to the local copy of an entity, so a transport beside the runtime's own needs nothing
  private to complete a handover.
- **FR-008**: The Scala test kit's installations group MUST accept a replication transport the
  test supplies, and run every installation of the group over it.
- **FR-009**: A service MUST be able to name an installation it replicates with by name alone,
  from the environment (`ANKKA_INSTALLATIONS=a,b,c`), for a transport that needs no database; the
  database form of naming an installation MUST go on working, and the two forms MUST compose.

**A forwarded write's caller and attribution**

- **FR-010**: A command forwarded to an entity's home MUST reach the home as a call made by the
  forwarding installation's own identity, authorized at the home by what that identity holds; the
  caller's own credential MUST NOT travel with the command.
- **FR-011**: The principal who made the original call MUST travel with the forwarded command as
  its attribution, readable by the handler at the home in every SDK language, recorded with each
  event the command persists, and shown in the entity's history together with the installation the write was
  forwarded from.
- **FR-012**: The home MUST refuse a forwarded write from an installation it does not admit, naming
  the installation, and the caller at the forwarding installation MUST be told so, distinctly from
  the home being unreachable.

**The installation's facts**

- **FR-013**: The read of the installation MUST report, beside its backups: whether erasure is on
  and the number of keyring instances; the broker's number of nodes and the copies and in-sync
  minimum it gives a topic by default; and the longest retention a topic may be given, or that it
  is unbounded.
- **FR-014**: A fact the control plane cannot read MUST be reported as not stated; an older
  control plane MUST be readable by a newer client with the fields absent, and a newer control
  plane by an older client.

### Key Entities

- **Deploy token**: as today, with an optional project scope; listed with it.
- **Attribution**: the principal who made the original call, carried with a forwarded command
  and recorded with the events it persists.
- **Installation facts**: what the read of the installation reports: backups (today), erasure,
  broker, retention.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: ankka-replication's requests 1, 2, 3 and 5 are each closed by a scenario above, and
  its `CLAUDE.md` debt list for them can be emptied against this release.
- **SC-002**: A project-scoped token holder attempting every route of the control plane API
  reaches exactly the routes of its project plus the grant routes of its project, and no other:
  verified by a suite that walks the whole route table.
- **SC-003**: A forwarded write's history line names the acting principal and the forwarding
  installation in every language's sample (Scala, Python, TypeScript, Rust), with no change to the
  samples' code.
- **SC-004**: The installation read returns the new facts in one call on kind and on a cloud
  installation, and every existing CLI and console suite passes unchanged.

## Assumptions

- A project-scoped token is a member of exactly one project; a token for several projects is not
  asked for and is not made.
- Giving a project-scoped token the right to make its project's grants is a deliberate widening
  for one credential kind, justified by what a module's agent must do; organization-wide deploy
  tokens keep today's rule (no grants).
- Attribution is the principal's subject as the forwarding installation saw it (a person's
  subject, a token's `token:<id>`, a machine's client id, or a service's identity); nothing else
  about the principal travels.
- The forwarding installation's identity at the home is the identity spec 051 gives a service
  calling itself at another installation. Admitting it across organizations, by a grant whose
  grantee is an installation's identity, is ankka-replication 002's and is out of scope here.
- `ANKKA_INSTALLATIONS` is the only new environment form; `ANKKA_INSTALLATION_<NAME>=on` was the
  alternative and is not added.

## Dependencies

- Spec 051 (the transport seam, the test kit's installations group); spec 040 (grants, machines);
  spec 041 (backups reported); spec 042 (erasure, the keyring); spec 043 (retention bounds).
- Consumers: ankka-replication 001 and 002; ankka-vendor 004 (requests 1, 2, 4).

## Out of scope

- The transport between installations on a network itself: ankka-replication.
- A vendor, a tenant, a module or a requirement: the products'.
- Scoping a person's membership to one project.
