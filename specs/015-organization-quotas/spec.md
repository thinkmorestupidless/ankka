# Feature Specification: Organization Quotas

**Feature Branch**: `015-organization-quotas`

**Created**: 2026-09-28

**Status**: Draft

**Input**: User description: "A platform administrator can set, change and clear a quota on an organization: a maximum number of projects, services and instances. Default: no quota. Enforcement is the control plane's, at the moment something is asked for. Counts are exact facts the organization records. The quota and usage are readable by members and by the hosted product, so a plan can differ in capacity."

## Context

An organization on an installation may create as many projects and services, with as many instances,
as its members ask for. That is right for a company installing the platform for itself. It is wrong
for an installation that sells access by plan, where "what you may run" is the product, and it is a
risk for any shared installation where one tenant's growth is every other tenant's contention.

The platform already records everything an organization *is* as facts on the organization itself —
members, invitations, whether it is disabled — and refuses at the door, in the endpoint, on the
organization's own say-so. A quota is one more such fact, and its enforcement one more refusal at
the same door. The hosted product (built on the wire library) then sets a quota after checkout
according to the plan bought, reads usage to show it, and the platform still knows nothing about
money.

Three things are counted, because they are the three things a customer's usage is made of and a
plan would name: projects in the organization, services across the organization, and instances
across the organization — every service's minimum instance count, added up, since that is what an
installation has to run for it.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The administrator caps an organization (Priority: P1)

A platform administrator sets a quota on an organization: at most so many projects, so many
services and so many instances. Members see the quota and their current usage when they look at
the organization. A member creating a project when the organization is at its project quota is
refused with a message that names the quota and the count; applying a service that would take the
organization past its service or instance quota is refused the same way; everything within the
quota works as before. The administrator can change the quota, or clear it, at any time.

**Why this priority**: It is the feature. Without it nothing is capped and a plan cannot differ in
capacity.

**Independent Test**: Set a quota of two projects, three services and four instances on an
organization; create two projects and see the third refused with the quota in the message; apply
services up to the third and see the fourth refused; apply a service whose instances would exceed
four and see it refused; clear the quota and see the refused requests succeed.

**Acceptance Scenarios**:

1. **Given** an organization with no quota, **When** a member creates projects and applies
   services, **Then** nothing is refused for capacity, exactly as before this feature.
2. **Given** a platform administrator, **When** they set a quota of `projects: 2`, **Then**
   members reading the organization see `projects: 2` and the current usage.
3. **Given** the organization has two projects and a quota of two, **When** a member creates a
   third, **Then** it is refused, nothing is created, and the message says the organization has
   reached its quota of 2 projects.
4. **Given** a service quota of three and three services across the organization's projects,
   **When** a member applies a fourth service, **Then** it is refused, naming the quota of 3
   services.
5. **Given** an instance quota of four and services whose minimum instances add up to three,
   **When** a member applies a new service with two minimum instances, **Then** it is refused,
   naming the quota of 4 instances and the 3 in use; **When** they apply it with one, **Then** it
   succeeds.
6. **Given** a caller who is not a platform administrator, **When** they try to set or clear a
   quota, **Then** they are refused as forbidden and the quota is unchanged.
7. **Given** a quota, **When** the administrator clears it, **Then** the organization is unlimited
   again and members see no quota.

---

### User Story 2 - Usage follows what exists (Priority: P1)

A service that is re-applied with fewer instances counts fewer; one re-applied with more counts
more and is refused if that would exceed the quota. Deleting a service frees its service count and
its instances; deleting a project frees the project. Pausing a service, or the organization being
disabled, changes no count: what is paused is still owed to the organization and comes back.

**Why this priority**: A quota that counts wrong is worse than none: it refuses honest requests or
admits dishonest ones. Exact counts are what make the refusal in US1 trustworthy.

**Independent Test**: Under a quota, apply, re-apply with more and fewer instances, pause,
delete a service and delete a project, reading the usage after each; every reading equals what
exists.

**Acceptance Scenarios**:

1. **Given** a service with two minimum instances under an instance quota of four, **When** it
   is re-applied with one, **Then** usage shows one instance and a second service with three is
   accepted.
2. **Given** the same service, **When** it is re-applied with four while another service holds
   one, **Then** it is refused naming the quota, and its previous descriptor stays in force.
3. **Given** a service counted in usage, **When** it is deleted, **Then** usage drops by one
   service and its instances.
4. **Given** a project counted in usage, **When** it is deleted, **Then** usage drops by one
   project.
5. **Given** a paused service, **When** usage is read, **Then** it still counts its instances;
   **Given** a disabled organization, the same.
6. **Given** a service applied and its organization's count changed, **When** the platform is
   restarted and the organization rebuilt from its history, **Then** usage is unchanged.

---

### User Story 3 - Lowering a quota stops nothing (Priority: P2)

An administrator lowers a quota below what an organization already uses. Nothing running is
stopped or changed. New projects and services are refused until usage is under the quota again,
and a service re-applied with fewer instances is always accepted, so a member can work their way
down.

**Why this priority**: Plans get downgraded. A downgrade that stopped services would be the
website taking a customer's workload down for choosing a smaller plan, which nobody would sign
up for.

**Independent Test**: With three services running, set a service quota of one; all three keep
running; a fourth is refused; deleting two lets a new one in.

**Acceptance Scenarios**:

1. **Given** usage of three services, **When** the quota is set to one, **Then** the set is
   accepted and every service keeps running unchanged.
2. **Given** that state, **When** a member applies a new service, **Then** it is refused naming
   the quota of 1 and the 3 in use.
3. **Given** that state, **When** a member re-applies an existing service with fewer instances,
   **Then** it is accepted.
4. **Given** that state, **When** a member re-applies an existing service unchanged, **Then** it
   is accepted — an unchanged service does not need new capacity.

---

### User Story 4 - The hosted product reads and sets the quota (Priority: P2)

A program outside the platform, holding the administrator credential and the wire library, sets an
organization's quota right after creating it and reads the quota and usage back to show a
customer how much of their plan they are using.

**Why this priority**: It is why quotas exist for the hosted product; it is P2 because the
platform's own CLI covers the first installation's administrator.

**Independent Test**: With the published wire library alone, set a quota through the API and read
the organization's quota and usage back as both the administrator and a member.

**Acceptance Scenarios**:

1. **Given** the wire library, **When** a client sends a quota for an organization as a platform
   administrator, **Then** the organization carries it.
2. **Given** an organization with a quota, **When** any member reads it, **Then** the answer
   carries the quota and the usage (projects, services, instances) as numbers.

---

### Edge Cases

- A quota of zero for projects is valid: the organization may create no project. Zero services or
  zero instances likewise. A negative number is refused as a bad request.
- A quota naming only some of the three limits leaves the others unlimited.
- Two members create a project each at the same moment against a quota with one slot left: at
  most one succeeds.
- A service apply that is refused for quota leaves the service exactly as it was, including a
  service that did not exist (nothing is created).
- A project deleted while it still has services: already refused today, so its services' counts
  cannot be orphaned by it.
- An organization deleted: its usage is gone with it; the id cannot be reused, as today.
- The organization is disabled: setting or clearing its quota is still allowed (administrators
  act on disabled organizations already); members' writes are refused for being disabled, as
  today, before any quota is consulted.
- Usage recorded before this feature: an organization that existed before has services and
  projects it never counted, and reads as holding nothing until an administrator sets a quota,
  which brings the count up to date with what exists. See the assumption on backfill.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A platform administrator MUST be able to set an organization's quota — any of
  maximum projects, maximum services, maximum instances, each a non-negative whole number — and
  to clear it. Anyone else MUST be refused as forbidden.
- **FR-002**: An organization with no quota MUST behave exactly as before this feature; a limit
  not named in a quota MUST mean unlimited for that count.
- **FR-003**: Creating a project when the organization's project count equals or exceeds its
  project quota MUST be refused, creating nothing, with a message naming the quota and the count.
- **FR-004**: Applying a service that would raise the organization's service count past its
  service quota, or its instance total past its instance quota, MUST be refused, changing
  nothing, with a message naming the quota and the count in use; a re-apply that keeps or lowers
  the service's instances MUST be accepted whatever the quota.
- **FR-005**: Usage MUST be an exact record kept by the organization: projects that exist,
  services that exist, and the sum of their minimum instances; deleting a project or service MUST
  reduce it; pausing, suspending or disabling MUST not change it; it MUST survive replay.
- **FR-006**: Lowering a quota below usage MUST be accepted and MUST stop or change nothing that
  exists.
- **FR-007**: The organization's quota and usage MUST be readable by its members and by a platform
  administrator wherever the organization is read, and MUST be carried by the wire library.
- **FR-008**: The CLI MUST offer `ankka organizations quota set` and `ankka organizations quota
  clear` for administrators, and MUST show the quota and usage in `ankka organizations get`.
- **FR-009**: The control plane's route reference and the CLI reference MUST describe the new
  routes and commands; the organizations page MUST describe quotas; the limitations page MUST no
  longer list capacity as unlimited per organization.

### Key Entities

- **Quota**: up to three limits on an organization — projects, services, instances — each
  optional; set, changed and cleared by a platform administrator; a fact on the organization.
- **Usage**: the organization's exact count of projects, of services, and of instances (the sum
  of minimum instances), kept as facts on the organization as things are created, changed and
  deleted.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An installation with no quota set passes every existing test suite unchanged.
- **SC-002**: Under a quota, 100% of creates and applies that would exceed it are refused with a
  message naming the quota and the count, and 100% of those within it succeed, across the full
  matrix of projects, services and instances.
- **SC-003**: After any sequence of applies, re-applies, pauses, deletes and a restart, the usage
  read from the organization equals the projects and services that exist and the sum of their
  minimum instances.
- **SC-004**: Lowering a quota below usage stops zero running instances.
- **SC-005**: A client outside the repository sets a quota and reads usage with the published wire
  library alone.
- **SC-006**: Every new route and command appears in the generated reference pages and the docs
  build passes.

## Assumptions

- Instances are counted as a service's *minimum* instance count, since that is what the platform
  runs for it; the maximum is not enforced today and is not counted.
- Zero is a valid limit and means "none allowed"; clearing is the way to lift a limit.
- Setting a quota is a single request that replaces the whole quota; a limit left out is
  unlimited. Simpler to reason about than partial updates.
- Backfill: an organization that predates this feature reads as holding nothing until it is first
  given a quota; setting a quota brings its usage up to date with the projects and services that
  exist, so it is exact from the moment a quota can refuse anything. This is stated on the
  organizations page.
- The hosted product sets quotas by plan after checkout and shows usage; that is its change and
  is out of scope here, as is any quota on memory, CPU or per project.
