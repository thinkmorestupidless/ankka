# Feature Specification: Expose Services Outside the Cluster

**Feature Branch**: `005-expose-services`

**Created**: 2026-09-18

**Status**: Draft

**Input**: User description: "Reach a deployed service from outside the cluster. Today a service is
reachable only inside the cluster, at its ClusterIP Service (feature 003); the sole way for a person
or an external client to call a service's HTTP endpoints — or the control plane's own API, which is
how the CLI works at all — is `kubectl port-forward`, which needs cluster credentials and a terminal
left open. [...] let an operator expose a service's HTTP port on a stable, externally reachable
hostname, following the model Akka's platform uses [...] the same mechanism must expose the control
plane itself so the CLI can be pointed at a real address rather than a port-forward. [...] the proof
is the shopping cart deployed through the CLI and driven by `curl` against its hostname from the
developer's machine, with no port-forward anywhere."

## Where this starts from

A deployed service has, since feature 003, a stable address *inside* the cluster: `<service>` from
its own project, `<service>.<prefix>-<project>.svc.cluster.local` from anywhere else. Since feature
004 that address routes only to instances that are members of the service's cluster and have bound
their HTTP port. Nothing outside the cluster can reach either address. The control plane is in the
same position: the CLI's `config set url` has only ever been given a `kubectl port-forward`
address, so using the platform at all requires cluster credentials — which is exactly what the
control plane's own design (feature 001) says a user should not need.

The platform therefore has two kinds of user who cannot do their job without a cluster login: the
person who deployed a service and wants to call it, and the person who wants to *deploy* one.

Akka's platform is the reference model: a service is private until someone exposes it; exposing it
yields a hostname the platform derives, under a domain the platform owns; unexposing removes the
route and nothing else. This feature adopts that model. Three questions the model leaves open were
put to the user and are settled in Requirements: exposure is a command, hostnames are the
platform's, and TLS is in from the start.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Expose a service and call it from outside (Priority: P1)

An operator has deployed a service and wants to call its HTTP endpoints from their own machine, or
give a client an address to call. They expose the service, are told its hostname, and requests to
that hostname reach the service's ready instances. They unexpose it, and the hostname stops
answering while the service keeps running untouched.

**Why this priority**: this is the feature. Everything else here exists so that this works without
a port-forward.

**Independent Test**: deploy the shopping cart through the CLI on the local cluster, expose it, and
from the developer's machine — no `kubectl` involved — add an item to a cart and read it back by
hostname. Unexpose it and the same request fails to connect while `services get` still reports the
service `Ready`.

**Acceptance Scenarios**:

1. **Given** a `Ready` service that has not been exposed, **When** a request is made to the hostname
   it *would* have, **Then** nothing answers — a service is private by default.
2. **Given** a `Ready` service, **When** the operator exposes it, **Then** within 60 seconds the
   platform reports a hostname for it and an HTTPS request to that hostname from outside the
   cluster — verifying the certificate against the platform's root, not skipping verification —
   reaches the service and is answered by it. Plain HTTP to the same hostname redirects to HTTPS.
3. **Given** an exposed service, **When** a client reads back state it wrote through the hostname,
   **Then** it sees what it wrote — the route reaches the same service, not a copy.
4. **Given** an exposed service with three instances, **When** one instance is not ready (starting,
   or being replaced), **Then** no request through the hostname is routed to it — the hostname's
   readiness guarantee is the same as the in-cluster address's (feature 004).
5. **Given** an exposed service, **When** the operator unexposes it, **Then** within 30 seconds the
   hostname no longer answers, the service is still `Ready`, its instances were not restarted, and
   its in-cluster address still works.
6. **Given** an exposed service, **When** it is deleted, **Then** its route is removed with it — no
   hostname is left pointing at nothing.
7. **Given** an exposed service, **When** it is paused, **Then** requests to the hostname fail
   (there is nothing to reach) and resume brings them back without the operator exposing it again.

---

### User Story 2 - The hostname is stable, unique and discoverable (Priority: P1)

The hostname a service receives is derived from the service, its project and a domain the platform
owns, so it can be written into a client's configuration once, two projects that each have a
service called `cart` never collide, and anyone who can see the service can find out its address.

**Why this priority**: an address that changes, or that has to be guessed, is not an address. This
is inseparable from US1 in value but separately testable.

**Independent Test**: expose two services named the same in two different projects; both are
reachable at distinct hostnames; `services get` and `services list` show each one's hostname;
restarting a service does not change it.

**Acceptance Scenarios**:

1. **Given** the platform's base domain is `example.test`, **When** service `cart` in project
   `checkout` is exposed, **Then** its hostname is derived from exactly those three parts, and the
   derivation is documented so a user can predict it.
2. **Given** `cart` exposed in projects `checkout` and `returns`, **When** both are called, **Then**
   each hostname reaches its own project's service.
3. **Given** an exposed service, **When** it is restarted, re-applied with a new image, or scaled,
   **Then** its hostname is unchanged.
4. **Given** an exposed service, **When** a user runs `services get` or `services list`, **Then** the
   hostname is shown; for an unexposed service the same output says plainly that it is not exposed.
5. **Given** a service and project whose names are valid today, **When** the hostname is derived,
   **Then** it is a valid hostname — no name the platform accepts can produce an address that does
   not resolve.

---

### User Story 3 - The control plane is reachable the same way (Priority: P2)

A person installing the platform gets a real address for the control plane's API, and points the
CLI at it. No port-forward is needed to use the platform.

**Why this priority**: it removes the cluster-credential requirement from every platform user, but a
port-forward still works for the person installing it, so it is not blocking US1.

**Independent Test**: after `deploy-local.sh`, `ankka config set url` to the printed control plane
address and `config set ca` to the printed root certificate, then run `services list` from a shell
with no port-forward open and `KUBECONFIG` unset.

**Acceptance Scenarios**:

1. **Given** the platform is installed, **When** installation finishes, **Then** it prints the
   control plane's external address, where it wrote the root certificate, and the exact `ankka
   config set` commands to use both.
2. **Given** the CLI is configured with that address, **When** any CLI command runs, **Then** it
   succeeds with no port-forward and no cluster credentials on the machine.
3. **Given** the control plane's three instances (feature 004), **When** one is being replaced,
   **Then** CLI commands through the external address keep succeeding.

---

### User Story 4 - The local cluster serves it end to end (Priority: P2)

A developer creating the local `kind` cluster gets one that accepts traffic on their machine's
ports, resolves the platform's hostnames without editing any system file, and serves exposed
services and the control plane at those hostnames.

**Why this priority**: without it US1–US3 can only be proven on a cluster nobody has. It is P2
because the mechanism (US1) is testable in the automated cluster suites without it.

**Independent Test**: from a clean machine, follow the README's local-deployment steps and reach
both the control plane and an exposed shopping cart by hostname with `curl`.

**Acceptance Scenarios**:

1. **Given** a machine with the prerequisites, **When** the documented cluster-creation and
   deployment steps run, **Then** the control plane's hostname answers from that machine.
2. **Given** that cluster, **When** the shopping cart is deployed and exposed, **Then** `curl` to its
   hostname adds an item and reads it back — no port-forward, no hosts-file edit.
3. **Given** an existing `kind-ankka` cluster created with the old one-liner, **When** the new steps
   are followed, **Then** the documentation says what to do (recreate the cluster) rather than
   failing obscurely.

---

### User Story 5 - A route cannot outlive or escape what it exposes (Priority: P3)

The route for a service exists only while the service does, belongs only to that service's
project, and cannot be made to point at another project's service or at the platform itself.

**Why this priority**: correctness and tenancy, but nothing a user does on the happy path depends on
it.

**Independent Test**: automated — delete a project with an exposed service and confirm the route is
gone; attempt to expose under a hostname belonging to another project and confirm refusal; confirm
the identity a service runs as cannot read or write routes.

**Acceptance Scenarios**:

1. **Given** an exposed service, **When** its project is deleted, **Then** the route is gone with
   everything else the project owned.
2. **Given** the credentials a deployed service runs with (feature 004), **When** they are used to
   read or change any route, **Then** the cluster refuses.
3. **Given** the operator's identity, **When** it writes a route, **Then** it can only write the
   kind of route object it renders — it holds no permission to create load balancers or change the
   cluster's own routing configuration.

### Edge Cases

- **Exposing a service that is not `Ready`** — allowed; the hostname is reported at once and answers
  once an instance is ready. Exposure is desired state, not an action on a running thing.
- **Two projects, same service name** — distinct hostnames by construction (US2); this is the
  reason the project is part of the hostname.
- **A service that serves no HTTP (`"http": false`)** — cannot be exposed; the platform refuses with
  a message naming the field, since there is no port to route to.
- **The base domain changes after services are exposed** — every hostname changes. The platform
  re-renders every route; the old hostnames stop answering. This is an installation-level decision
  and is documented as such, not guarded against.
- **Unexposing something never exposed** — succeeds, changing nothing.
- **A hostname collision with the control plane's own address** — impossible by construction: the
  control plane's hostname is not in the form a service's can take.
- **The port changes on re-apply** — the route follows the port; one apply, one consistent object,
  the same rule feature 003 set for the in-cluster address.
- **The platform's route-serving component is not installed** — a route object exists but nothing
  answers. `services get` reports the route's status as the cluster reports it, so this is visible
  rather than silent.

## Requirements *(mandatory)*

### Functional Requirements

**Declaring exposure**

- **FR-001**: A service MUST be private by default: a newly applied service is reachable only at its
  in-cluster address.
- **FR-002**: An operator MUST be able to expose and unexpose a service with a command of its own
  (`services expose <name>`, `services unexpose <name>`) — the model Akka uses and the one ankka's
  `pause`/`resume` already follow: state beside the descriptor, which `apply` never touches.
  Re-applying a descriptor MUST NOT change whether a service is exposed.
- **FR-003**: Exposure MUST be desired state recorded by the control plane and folded into the
  service's lifecycle like everything else: it survives restarts, is reported on `services get`
  and `services list`, and is projected into the same resource that carries the rest of the
  service's desired state.
- **FR-004**: Exposing a service declared with `"http": false` MUST be refused with a message naming
  the field.
- **FR-005**: Unexposing MUST remove only the route: instances are not restarted, the in-cluster
  address is unaffected, and the service's lifecycle state is unchanged.

**The hostname**

- **FR-006**: An exposed service's hostname MUST be derived from the service name, the project id
  and a platform-wide base domain, in a documented form, such that two services with the same name
  in different projects have different hostnames.
- **FR-007**: The hostname MUST be a valid DNS name, or exposure MUST be refused with a message
  naming the rule broken (a length limit, or a hostname already held by another exposed service) —
  never a hostname that cannot resolve. *(Amended in planning: the one-label form the chosen
  routing model requires can exceed a DNS label's length for the longest accepted names; see
  research R2.)*
- **FR-008**: The hostname MUST NOT change on restart, re-apply, scale, pause or resume.
- **FR-009**: The hostname is the platform's to derive: a service MUST NOT be able to choose its own.
  Custom hostnames — which need the user to own DNS and certificates for a domain the platform does
  not control — are a feature of their own and are out of scope.
- **FR-010**: The base domain MUST be a single installation-level setting, given to the platform at
  deployment and not per service or per project.
- **FR-011**: `services get` and `services list` MUST show an exposed service's hostname and state
  plainly when a service is not exposed.

**Routing**

- **FR-012**: A request to the hostname MUST reach only instances that are ready by the platform's
  own definition (feature 004: cluster member, HTTP bound). The external route and the in-cluster
  address MUST NOT disagree about which instances receive traffic.
- **FR-013**: The route MUST target the port the descriptor resolves (feature 003) and follow it
  when it changes.
- **FR-014**: A route MUST be owned by the service it exposes: deleting the service, or its project,
  MUST remove the route with no separate cleanup.
- **FR-015**: The route MUST be rendered by the operator from the service's resource, so that the
  control plane holds no credential that can create or change routes.
- **FR-016**: An exposed service MUST be served over TLS at its hostname with a certificate the
  platform issues for that hostname, obtained automatically on expose and renewed without operator
  action. Plain HTTP to the hostname MUST redirect to HTTPS, never serve.
- **FR-016a**: The platform MUST NOT require an operator to obtain or install a certificate to
  expose a service; the certificate's lifecycle is the platform's.
- **FR-016b**: Which authority issues certificates is an installation-level setting like the base
  domain. The local deployment MUST provide an authority of its own and export its root certificate
  to a file the deployment step names, so that the developer can trust it explicitly.
- **FR-016c**: A route whose certificate is not yet issued MUST be visible as such in `services get`
  (the route's status is the cluster's), not silently served with the wrong certificate.

**The control plane**

- **FR-017**: The control plane's API MUST be exposed by the same mechanism at a hostname under the
  base domain that no service's hostname can equal.
- **FR-018**: The local deployment MUST print the control plane's external address and the CLI
  command that uses it.
- **FR-019**: Every CLI command MUST work through the external address with no port-forward and no
  cluster credentials present.
- **FR-019a**: The control plane's external address MUST be HTTPS with a platform-issued certificate
  (FR-016), and the CLI MUST be able to trust a named root certificate through its own saved
  settings (`config set ca <file>`), without modifying the machine's trust store and without any
  option that disables verification.

**The local cluster**

- **FR-020**: The documented local cluster creation MUST produce a cluster that accepts HTTP traffic
  on the developer's machine, and the documented deployment MUST install whatever serves routes, so
  that an exposed service is reachable by hostname from that machine.
- **FR-021**: The local deployment MUST choose a base domain that resolves to the developer's
  machine without editing any system file, so that the documented `curl` works on a clean machine.
- **FR-021a**: The documented `curl` MUST verify the certificate — against the exported root
  certificate (FR-016b), never with verification disabled.
- **FR-022**: The local deployment MUST detect a cluster created without the new configuration and
  say what to do, rather than proceed to a deployment whose hostnames never answer.

**Tenancy and permissions**

- **FR-023**: The identity a deployed service runs as (feature 004) MUST NOT be able to read or write
  routes.
- **FR-024**: The operator MUST hold permission only for the kind of route object it renders — no
  permission to create load balancers, change cluster-wide routing configuration, manage the
  component that serves routes, or read any issued certificate's private key.
- **FR-025**: A route rendered for a service MUST be unable to name any backend other than that
  service's own in-cluster address in that service's own project.

**Proof**

- **FR-026**: The automated cluster suites MUST prove, against a real cluster: a route reaches a
  ready instance and no unready one; unexpose removes only the route; the route dies with the
  service and with the project; the withheld permissions are refused by the cluster itself.
- **FR-027**: The local `kind` walkthrough MUST be documented step by step and performed once: the
  shopping cart deployed through the CLI, exposed, driven by `curl` by hostname from the developer's
  machine, with no port-forward.

### Key Entities

- **Exposure**: part of a service's desired state — exposed or not. Recorded by the control plane,
  carried on the service's resource, acted on by the operator.
- **Hostname**: derived, never stored as the source of truth: a function of (service name, project
  id, base domain). Reported wherever the service is.
- **Base domain**: one installation-level value. The local deployment picks one that resolves to
  the developer's machine.
- **Certificate**: issued per hostname by the installation's authority when a route is rendered,
  renewed by the platform, and never handled by an operator or a service. The local deployment's
  authority is self-signed and its root is exported for the developer to trust.
- **Route**: the cluster object the operator renders for an exposed service — owned by the service's
  resource, in the service's project, targeting the service's in-cluster address on its resolved
  port. Its status is the cluster's, surfaced through `services get`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: From the developer's machine, with no port-forward and no cluster credentials, an
  operator can deploy the shopping cart, expose it, add an item and read it back over HTTPS by
  hostname with the certificate verified, in under five minutes following the README.
- **SC-002**: Exposing a `Ready` service makes it answer at its hostname, with a valid certificate,
  within 60 seconds; unexposing stops it within 30 seconds; neither restarts an instance.
- **SC-010**: No documented command, test or script disables certificate verification.
- **SC-003**: Over a rolling restart of a three-instance exposed service under continuous requests
  by hostname, 100% of requests are answered by a ready instance — the same bar feature 004 set for
  the in-cluster address.
- **SC-004**: Two services with the same name in two projects are simultaneously reachable at two
  distinct hostnames, each returning its own state.
- **SC-005**: A service's hostname is byte-for-byte identical before and after restart, re-apply,
  scale, pause and resume.
- **SC-006**: Every CLI command succeeds against the control plane's external address with
  `KUBECONFIG` unset.
- **SC-007**: A deployed service's own identity is refused by the cluster when it reads or writes
  any route; the operator's identity is refused when it creates a load balancer.
- **SC-008**: Deleting a project that has an exposed service leaves no route behind, verified by
  listing routes across the cluster.
- **SC-009**: Every existing suite passes unchanged; a service that was never exposed behaves
  exactly as before this feature.

## Assumptions

- **The route-serving component is part of the platform installation, not of a service.** Serving
  routes is a cluster-wide concern; the operator renders routes and something the installation
  provides serves them. On the local cluster the deployment installs it. On any other cluster the
  installer provides one; the platform names the class of route it renders and does not assume it
  is the only one in the cluster.
- **A hostname is one level under the base domain, or two — decided in planning, with one
  constraint from this spec**: whichever form is chosen must be one that certificates can later
  cover without changing any hostname (FR-016). A two-level form (`cart.checkout.<base>`) reads
  best and cannot collide; a one-level form (`cart-checkout.<base>`) can be covered by one
  certificate for the whole platform but can collide on names containing dashes (`a-b` in `c` versus
  `a` in `b-c`) unless a separator no name can contain is used. The plan must pick and say why.
- **The control plane's hostname is a fixed label the derivation can never produce** — e.g. a
  reserved name directly under the base domain, where every service hostname has a project
  component.
- **Exposure does not change a service's access control.** An endpoint's `acl` (which ankka makes
  every endpoint declare, with `DenyAll` the default posture) is what governs who may call it;
  exposure changes who can *reach* it. The README must say this in the same breath as the feature,
  because an exposed `AllowAll` endpoint on a real cluster is on the internet.
- **The local base domain resolves by a public wildcard-DNS convention** (an address embedded in
  the name) rather than a hosts-file entry, so a clean machine needs nothing configured. This is a
  planning detail; the requirement is FR-021.
- **`kind` port publishing is decided at cluster creation** and cannot be added to a running
  cluster. The documented creation step changes; FR-022 covers the cluster that predates it.
- **Resolved with the user before drafting**: exposure is a separate command (FR-002), hostnames
  are platform-derived only (FR-009), and TLS is in scope now (FR-016). Authentication *at the
  route* is not: who may call an endpoint is the endpoint's `acl`, as above.
- **TLS now constrains the hostname shape less than it seems**: an in-cluster authority issues a
  certificate per hostname, so a two-level name (`cart.checkout.<base>`) needs no wildcard and
  costs nothing extra. The plan should still record why it picks the shape it does.
- **Trusting the local root is explicit, per tool**: the CLI through its own setting, `curl`
  through `--cacert`. Installing the root into the machine's trust store is never required and is
  not documented as a step, so the platform never modifies anything outside its cluster.
- **Readiness is unchanged.** Feature 004's probe already gates the in-cluster address; the route
  targets that address, so it inherits the guarantee rather than re-implementing it (FR-012).
