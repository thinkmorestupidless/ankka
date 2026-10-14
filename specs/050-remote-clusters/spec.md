# Feature Specification: Remote Clusters — One Control Plane, Services on Clusters It Does Not Run In

**Feature Branch**: `050-remote-clusters`

**Created**: 2026-10-11

**Status**: Draft

**Input**: User description: "Could an application running on k3s on the Raspberry Pis use the
control plane running in Kubernetes in Google's cloud? One control plane, in the cloud, with its
identity provider and its journal; a cluster at home, or a customer's own cluster, that runs the
services of the projects placed on it. The cluster reaches out to the control plane, never the
reverse: nothing at home accepts a connection from the internet, and the control plane holds no
credential for any cluster. Exposed services on that cluster answer under a domain of the cluster's
own, through a gateway of the cluster's own."

## Context

An installation is one cluster. The control plane builds its Kubernetes clients from the credentials
around it — its service account in the cluster, else a kubeconfig
(`Fabric8AnkkaServiceClient.apply`, `PodLogs`, `InstanceTopologies`) — and nothing in a project or a
service says which cluster it is on, because there is only one. What the control plane asks of the
cluster is one trait, `AnkkaServiceClient`: ensure a project's namespace, write a registry credential
and a project secret's entries without ever reading them, write a service's desired state as an
`AnkkaService` and a project's as an `AnkkaProject`, write a contract's schema, delete a resource,
list and watch the resources, and say whether the cluster is readable. Beside it, the control plane
reads a pod's log and an instance's topology directly. The operator, in the same cluster, watches
the resources and owns everything beneath them; an exposed service's route binds to the gateway
beside the operator, and its hostname is `<service>-<project>.<base domain>` (`crd`'s `Hostnames`),
which resolves to that gateway.

Two things already point the other way. The docs say the resource is the only thing the two sides
share, that the operator depends on nothing but Kubernetes, and that an unreachable cluster is a
reconciliation problem rather than a lost request; the control plane restates what it last knew and
marks it `unconfirmed` when it cannot reach the cluster. The operator keeps working while the
control plane is down. This is the shape of a control plane with more than one cluster; what is
missing is for a cluster to be a thing the platform knows, for a project to be on one, and for the
control plane's writes and reads to reach a cluster it does not run in.

Two ways exist today to run services somewhere else, and this feature is neither.

- **A spoke installation** (feature 011, `docs/platform/install-cloud.md`): a whole platform — control
  plane, its database, operator — on the other cluster, trusting the hub's identity realm. Right when
  the other cluster is a tenancy boundary: a jurisdiction, a brand with its own billing, an account
  of its own. Wrong when it is a placement: a spoke has its own API address, its own organizations
  and projects, its own journal, and an organization lives on exactly one of them. A person with a
  cluster at home and an account in the cloud wants one `ankka login`, one project list, and a
  project whose services happen to run at home.
- **The local platform** (`deploy-local.sh`): a whole platform on one's own machine, for development.

**Chosen: the cluster pulls.** A remote cluster runs the operator and what the operator needs — the
database operator, the certificate manager, a gateway — and no control plane. Its operator opens a
connection to the control plane's address, as the CLI does, presents a credential the platform
administrator was shown once when the cluster was created, and keeps that connection open. Down it
comes everything the control plane would have written into its own cluster for the projects placed
there: namespaces, desired state, schemas, registry credentials, project secret entries. Up it goes
what the operator sees: every report, and on request a log and a topology. The operator writes what
arrives into its own cluster with the grants the control plane holds at home and no more, so the
resources, the owner references, the cascade and everything beneath them are exactly what they are
today. The home cluster — the one the control plane runs in — is unchanged: the same trait, the same
client, the same watch.

This is the shape the private infrastructure repository already insists on (its D3: Flux is the
seam, and the cluster pulls), the shape of GKE Connect and Rancher's cluster agent, and the only
one in which a cluster behind a home router or a customer's firewall can join at all.

**Rejected: the control plane holds a kubeconfig per cluster and pushes.** The quickest to build and
the worst fit: every remote cluster's API server has to be reachable from the control plane, which
for a cluster at home means a tunnel or an open port; the control plane, which was designed to hold
no credential able to create a workload, would hold one for every cluster; and zero trust (feature
014) would have a second kind of inbound connection to defend.

**Rejected: a spoke per site.** Above. It stays the right answer for a tenancy boundary and is
documented as such; this feature adds placement, not tenancy.

**Rejected: a multi-cluster federation layer** propagating the resources (KubeFed, Cluster API's
inventory, Argo CD's cluster secrets). Each is a controller with cluster credentials, which is the
push model with a vendor's name on it, and each would still need the status path home.

Six decisions shape the feature.

- **A cluster is the installation's, created by a platform administrator**, with a name and the
  domain its exposed services answer under. It may admit every organization, or name the
  organizations that may place projects on it; a customer's own cluster admits that customer alone.
  The home cluster exists from the first start, admits everyone, and its domain is the base domain.
- **A project is placed on one cluster when it is created, and stays there.** Its databases, its
  topics, its buckets and its services are on that cluster. Moving a project is not this feature.
- **The operator is the client.** No new process, no agent beside the operator. The operator on a
  remote cluster is told the control plane's address and given the cluster's credential as a Secret,
  and does everything else. The same image, the same manifests, two more settings. A remote cluster's
  operator declares its version and its domain when it tethers; a version outside the range the
  platform supports, or a domain that is not the cluster's, is refused with the reason.
- **One projector, two clients.** The control plane's projector, its generations and its staleness
  rules do not change. Where it holds one `AnkkaServiceClient` for the cluster it runs in, it holds
  one per cluster: the existing client for home, and one per remote cluster that speaks over the
  tether. The offline suite's fake is the proof that the projector cannot tell which.
- **A credential the cluster holds, never the control plane.** The cluster token is shown once, kept
  as a digest, and revoked by renewing it; it admits one cluster and is good for nothing else. Project
  secret values and registry passwords cross the tether once, downward, and the control plane keeps
  none, exactly the rule it keeps today for its own cluster. A remote cluster is sent only what is
  placed on it and can learn nothing of another cluster's.
- **Everything the operator provisions is per cluster.** A remote cluster brings its own Postgres
  operator, its own certificate authority, its own gateway and wildcard certificate, its own broker
  or none, its own object store or none, its own cloud provider or none. Each is the operator's
  setting, which it already is; a descriptor that asks for what the placed cluster lacks is reported
  as it is reported on an installation that lacks it today.

## Clarifications

### Session 2026-10-11

- Q: Is this a feature or an overlay? → A: A feature. The control plane assumes it shares a cluster
  with the operator in its client construction, its hostname derivation, its log and topology reads
  and its lack of a cluster in the model. An overlay can place the operator anywhere; it cannot make
  the control plane reach it.
- Q: Push with a kubeconfig, or pull with the operator dialling out? → A: Pull. The reasons are in the
  Context; the deciding one is that a cluster at home accepts no inbound connection.
- Q: Does a spoke installation remain? → A: Yes, unchanged, for a tenancy boundary. This feature is
  for placement under one control plane. The install page says which to choose.
- Q: Is the Raspberry Pi cluster the first remote cluster? → A: It is the first customer: an arm64
  k3s cluster at home, joined to a control plane in the cloud, after feature 049 gives it an image.
  Nothing in this spec names it; the private infrastructure repository's overlay does.
- Q: Can a service on one cluster call a service on another? → A: Not in this feature. A project's
  services are on one cluster and call each other there; cross-project access (040) is within a
  cluster, and a grant across clusters is refused with the reason.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A cluster joins the installation and reports itself (Priority: P1)

A platform administrator creates a cluster, `ankka clusters create pi --domain pi.example.com`, and
is shown its token once. On the remote cluster they install the operator and what it stands on, put
the token in a Secret and set the control plane's address on the operator. The operator tethers,
declares its version and its domain, and from then on `ankka clusters list` shows `pi` as tethered,
with when it was last seen and the operator's version. When the token is renewed, the old one stops
admitting the cluster at once and the operator tethers again with the new one.

**Why this priority**: Nothing can be placed on a cluster the platform does not know, and the tether is
what every other story runs over.

**Independent Test**: In a k3s suite with a second k3s cluster, create a cluster at the control
plane, start the operator on the second cluster with the token and the address, and assert the
cluster is listed as tethered within seconds with the version and domain it declared; renew the token
and assert the old one is refused and the new one admits.

**Acceptance Scenarios**:

- added `features/remote-clusters/joining.feature`: a platform administrator creates a remote cluster and is shown its cluster token once
- added `features/remote-clusters/joining.feature`: an operator given the cluster token and the address of the control plane tethers and the cluster is listed as tethered
- added `features/remote-clusters/joining.feature`: a cluster token is good for one cluster and for nothing else
- added `features/remote-clusters/joining.feature`: renewing the cluster token stops the old one admitting the cluster at once
- added `features/remote-clusters/joining.feature`: an operator at a version the platform does not accept is refused the tether with the reason
- added `features/remote-clusters/joining.feature`: an operator declaring a domain that is not the cluster's is refused the tether with the reason
- added `features/remote-clusters/joining.feature`: a member who is not a platform administrator cannot create, renew or delete a cluster

---

### User Story 2 - A project is placed on a remote cluster and its services run there (Priority: P1)

A member creates a project on the cluster, `ankka projects create shop --cluster pi`, applies a
descriptor, and the service reaches `Ready`: its namespace, its database, its Deployment and its
instances are on the remote cluster, written by the operator from what came down the tether, and the
report came back up it. `ankka services get` shows the same lifecycle, generation and detail it would
for a service at home. A project created with no cluster named is on the home cluster, as every
project is today.

**Why this priority**: This is the feature: a service of the installation running on a cluster the
control plane does not run in, with nothing about the descriptor, the apply or the report different.

**Independent Test**: In the two-cluster k3s suite, create a project placed on the remote cluster,
apply the sample, and assert `Ready` at the control plane with the Deployment, the database and the
namespace present on the remote cluster and absent on the home one; pause, resume, restart, scale
and delete it and assert each is honoured there.

**Acceptance Scenarios**:

- added `features/remote-clusters/placement.feature`: a project is placed on a remote cluster when it is created
- added `features/remote-clusters/placement.feature`: a project created with no cluster named is on the home cluster
- added `features/remote-clusters/placement.feature`: a project cannot be placed on a cluster that does not admit its organization
- added `features/remote-clusters/placement.feature`: a project cannot be moved to another cluster
- added `features/remote-clusters/placement.feature`: a member is shown the clusters their organization is admitted to
- added `features/remote-clusters/placement.feature`: a project cannot be placed on a cluster that does not exist
- added `features/remote-clusters/placement.feature`: a platform administrator changes which organizations a cluster admits
- added `features/remote-clusters/placement.feature`: every project that existed before clusters is on the home cluster
- added `features/remote-clusters/reconciliation.feature`: a service applied to a project on a remote cluster runs on that cluster and nowhere else
- added `features/remote-clusters/reconciliation.feature`: a service on a remote cluster is paused, resumed, restarted, scaled and deleted as a service at home is
- added `features/remote-clusters/reconciliation.feature`: a service on a remote cluster is reported with the same lifecycle, generation and detail as a service at home
- added `features/remote-clusters/reconciliation.feature`: a project's declared topics are made on the broker of the cluster it is placed on
- added `features/remote-clusters/reconciliation.feature`: a descriptor that asks for what the placed cluster lacks is reported as it is on an installation that lacks it

---

### User Story 3 - The tether is lost and nothing is lost (Priority: P1)

The home router reboots. The services on the remote cluster keep serving; the operator keeps
reconciling what it has. At the control plane, after the tether bound, every service placed on that
cluster reads `unconfirmed` and the cluster is listed as untethered with when it was last seen. A
member applies a descriptor meanwhile and the apply is recorded. When the tether is back, the operator
receives what it missed, the service rolls, and every report is confirmed again.

**Why this priority**: The platform's own words are that an unreachable cluster is a reconciliation
problem rather than a lost request; a remote cluster is the case where that is tested by the
world rather than by a suite.

**Independent Test**: In the two-cluster suite, cut the tether; assert the service on the remote
cluster answers throughout, that its lifecycle reads `unconfirmed` within the bound, that an apply
made meanwhile is recorded with its new generation; restore the tether and assert the generation is
reported `Ready` and confirmed.

**Acceptance Scenarios**:

- added `features/remote-clusters/reconciliation.feature`: services on a remote cluster keep serving while the tether is down
- added `features/remote-clusters/reconciliation.feature`: a service on a remote cluster reads unconfirmed once the tether has been down for the tether bound
- added `features/remote-clusters/reconciliation.feature`: a descriptor applied while the tether is down is recorded and honoured when the tether is back
- added `features/remote-clusters/reconciliation.feature`: a report that reaches the control plane after the tether is back confirms the service again
- added `features/remote-clusters/reconciliation.feature`: the operator on a remote cluster that cannot write to its own cluster says so
- added `features/remote-clusters/reconciliation.feature`: sending what was recorded a second time changes nothing

---

### User Story 4 - An exposed service answers under the cluster's domain (Priority: P2)

A member exposes a service on `pi` and is shown `cart-shop.pi.example.com`. The route is on the
remote cluster's gateway, the certificate is from that cluster's own wildcard, and the hostname
resolves to wherever that cluster's owner pointed it: a private address at home, a load balancer in
a customer's cloud. The control plane derived the hostname from the cluster's domain and provisioned
nothing else; the cluster's DNS, address and certificate authority are its owner's.

**Why this priority**: Exposure is how a service is used, and a hostname under the base domain would
name a gateway the service is not behind.

**Independent Test**: In the two-cluster suite, expose a service on the remote cluster and assert the
hostname shown is under the cluster's domain, that an `HTTPRoute` for it exists on the remote
cluster's gateway and none on the home one, and that a request to that hostname, resolved to the
remote gateway, is answered with a certificate for the cluster's wildcard.

**Acceptance Scenarios**:

- added `features/remote-clusters/exposure.feature`: an exposed service on a remote cluster answers at a hostname under the cluster's domain
- added `features/remote-clusters/exposure.feature`: an exposed service on a remote cluster has its route on that cluster's gateway and on no other
- added `features/remote-clusters/exposure.feature`: the control plane of a remote cluster's installation answers under the base domain alone
- added `features/remote-clusters/exposure.feature`: a service on a remote cluster cannot be granted to a project on another cluster

---

### User Story 5 - Credentials reach the placed cluster and no other (Priority: P2)

A member sets a project's registry credential and a project secret's entries. Both reach the remote
cluster the project is placed on, written by the operator by create and patch as the control plane
writes them at home, and the control plane keeps neither value. A second remote cluster, tethered to
the same installation, is never sent them; a remote cluster is sent only the projects placed on it.

**Why this priority**: The tether carries the two values the platform otherwise never carries, and the
rules about them — written once, never read back, never in the journal, never to another tenant —
have to survive the trip.

**Independent Test**: In a suite with two remote clusters, set a registry credential and a project
secret on a project placed on one; assert the Secrets exist on that cluster, are absent on the
other, that the control plane's journal holds names only, and that the tether's record of what was
sent carries no value.

**Acceptance Scenarios**:

- added `features/remote-clusters/secrets.feature`: a project's registry credential is written on the cluster the project is placed on
- added `features/remote-clusters/secrets.feature`: a project secret's entries are written on the cluster the project is placed on
- added `features/remote-clusters/secrets.feature`: a remote cluster is sent nothing of a project placed elsewhere
- added `features/remote-clusters/secrets.feature`: the control plane keeps no value that crossed the tether
- added `features/remote-clusters/secrets.feature`: setting a credential for a project on a remote cluster whose tether is down is refused and records nothing

---

### User Story 6 - Logs and topology come back over the tether (Priority: P3)

A member runs `ankka services logs cart` for a service on `pi` and reads the instances' logs as they
would at home; the console shows the service's topology. Both are asked of the operator over the
tether, which answers from its own cluster.

**Why this priority**: Operating a service means reading it; without this a service at home is
operated with `kubectl` at home, which is what the feature exists to remove.

**Independent Test**: In the two-cluster suite, `ankka services logs` on a service placed remotely
returns the instance's log lines; the control plane's topology route for it returns what the
instance's observe port reports.

**Acceptance Scenarios**:

- added `features/remote-clusters/operating.feature`: the logs of a service on a remote cluster are read over the tether
- added `features/remote-clusters/operating.feature`: the topology of a service on a remote cluster is read over the tether
- added `features/remote-clusters/operating.feature`: the logs of a service on a remote cluster whose tether is down are refused with the reason
- added `features/remote-clusters/operating.feature`: the console names the cluster a project is placed on

---

### User Story 7 - A cluster is retired (Priority: P3)

A platform administrator deletes a cluster that has no projects. Its token stops admitting, its
operator is told and untethers, and the cluster is listed no more. A cluster that still has projects
cannot be deleted; the refusal names them.

**Why this priority**: A lab cluster comes and goes; the platform should let it go without leaving a
credential that still admits.

**Independent Test**: Delete a cluster with a project placed on it and assert the refusal names the
project; delete the project, delete the cluster, and assert the token is refused and the cluster is
absent from the listing.

**Acceptance Scenarios**:

- added `features/remote-clusters/joining.feature`: a cluster with projects placed on it cannot be deleted
- added `features/remote-clusters/joining.feature`: deleting a cluster with no projects stops its cluster token admitting and unlists it
- added `features/remote-clusters/joining.feature`: the home cluster is listed as home and cannot be deleted

---

### Edge Cases

- Two operators tether with the same token at once (a rollout of the operator's Deployment): the
  newest tether wins and the older is closed; nothing is sent twice, since what is sent is desired
  state and writing it is idempotent (FR-011).
- The tether is up and the remote cluster's API server is down: the operator reports that it cannot
  write, and the control plane records the services there as `unconfirmed` with that detail, not as
  tethered and healthy.
- The control plane restarts: every tether is re-established by the operators, which retry with
  backoff; on tether each sends its full set of reports, and the control plane reconciles as it does
  from a fresh watch at home.
- A descriptor applied to a project on a cluster that lacks what it asks for — a broker, an object
  store, a cloud provider: the report says so, as it does today on an installation without one
  (FR-016).
- A cluster's domain is changed at the control plane while an operator is tethered with the old one:
  the tether is closed with the reason, and hostnames already shown stop being right until the
  operator's setting matches; the CLI says which domain each side holds.
- A cluster token is leaked: renew it. The old one is refused at once; the operator holding it
  untethers and keeps running what it has until given the new one.
- The home cluster: it is never "tethered" or "untethered"; `clusters list` shows it as home, with no
  token, and `clusters delete` refuses it.
- A project secret entry set while the tether is down: refused with 503 and nothing recorded, exactly
  as a home cluster that is unreachable (the rule "write the cluster first, then the journal").

## Requirements *(mandatory)*

### Functional Requirements

**Clusters**

- **FR-001**: The platform MUST know a set of clusters: the home cluster, present from first start,
  and any number of remote clusters, each with a name unique in the installation, a domain, the
  organizations it admits (every one, or a list), and whether it is tethered and when it was last
  seen. The home cluster's domain MUST be the base domain and it MUST admit every organization.
- **FR-002**: A platform administrator, and only a platform administrator, MUST be able to create a
  remote cluster, list clusters, change which organizations a cluster admits, renew a cluster's
  token and delete a cluster. A member MUST be able to list the clusters their organization is
  admitted to, by name and domain, so they can place a project.
- **FR-003**: Creating or renewing MUST show the cluster token once; the platform MUST keep only a
  digest; the token MUST admit one cluster's tether and nothing else — no control plane route a person
  or a deploy token may call accepts it.
- **FR-004**: Deleting a cluster MUST be refused, naming the projects, while any project is placed on
  it; otherwise it MUST stop the token admitting, close the tether with the reason, and remove the
  cluster from listings. Its name MUST NOT be reusable, as a project's id is not.
- **FR-005**: Every creation, renewal, admission change, tether, untether and deletion MUST be recorded
  with its actor, as organization and project changes are.

**Placement**

- **FR-006**: A project MUST be placed on exactly one cluster when it is created, named by the
  creator or the home cluster when none is named; the creation MUST be refused when the cluster is
  unknown or does not admit the project's organization. The placement MUST be shown by
  `projects get` and in the project listing, and MUST NOT be changeable.
- **FR-007**: Everything the control plane writes for a project — its namespace, its `AnkkaProject`,
  each service's `AnkkaService`, its schemas, its registry credential, its project secrets — MUST be
  written on the cluster the project is placed on and on no other.

**The tether**

- **FR-008**: The operator MUST tether when given the control plane's address and a cluster token (as a
  Secret), declaring its version and its base domain; MUST retry with backoff while refused for
  reasons that may change or unreachable; and MUST run unchanged, with no tether, when given neither.
- **FR-009**: The control plane MUST refuse a tether whose token is unknown or revoked, whose declared
  version is outside the range the platform supports (the rule it applies to a runtime version), or
  whose declared domain is not the cluster's, each with the reason, which the operator MUST log.
- **FR-010**: The tether MUST be opened by the operator, over HTTPS to the control plane's address,
  and MUST be the only connection between them; the control plane MUST hold no address and no
  credential for any remote cluster.
- **FR-011**: Over the tether the control plane MUST send, for the projects placed on that cluster, the
  same desired state it writes at home — namespaces, `AnkkaProject`s, `AnkkaService`s, schemas,
  registry credentials, project secret entries and their removals — on tether in full and afterwards
  as it changes; writing it MUST be idempotent, so a repeated send changes nothing.
- **FR-012**: The operator MUST write what arrives into its own cluster using create, patch,
  server-side apply and delete on the same kinds the control plane is granted at home, and MUST gain
  no `get`, `list` or `delete` on Secrets. The resources it writes MUST be the same objects the
  control plane would have written, so the operator's reconciliation beneath them is unchanged.
- **FR-013**: The operator MUST send up the tether every status it writes on an `AnkkaService` or
  `AnkkaProject`, and on tether its full set; the control plane MUST fold them through the same
  observation path as a home report, with the same generation and staleness rules.
- **FR-014**: The control plane MUST mark every service placed on a cluster `unconfirmed` once the
  cluster's tether has been down for the tether bound (`ANKKA_CLUSTER_TETHER_BOUND`, a platform setting,
  30 seconds as shipped) and MUST confirm again on the first report after the tether is back. The
  cluster's listing MUST show tethered or untethered and when it was last seen.
- **FR-015**: A write the control plane cannot deliver because a cluster's tether is down MUST be
  handled as a home cluster being unreachable is today: an apply is recorded and reconciled later; a
  registry credential or a project secret is refused with 503 and nothing is recorded.

**What a cluster provides**

- **FR-016**: A remote cluster's Postgres operator, certificate authority, gateway, broker, object
  store and cloud provider MUST be its own, configured by the operator's settings as they are today;
  a descriptor that asks for what the placed cluster lacks MUST be reported as it is on an
  installation that lacks it.
- **FR-017**: An exposed service on a remote cluster MUST answer at
  `<service>-<project>.<cluster domain>`, its route MUST be on that cluster's gateway, and its
  certificate MUST be from that cluster's wildcard; the control plane MUST derive the hostname from
  the cluster's domain and provision nothing for it. The control plane itself MUST keep answering at
  `api.<base domain>` only.
- **FR-018**: A cross-project grant (040) MUST be refused with the reason when the two projects are
  on different clusters; a mount (021) MUST be refused likewise.

**Operating**

- **FR-019**: `ankka services logs` and the control plane's topology route MUST answer for a service
  on a remote cluster by asking the operator over the tether, which MUST answer from its own cluster;
  a remote cluster whose tether is down MUST answer 503 with the reason.
- **FR-020**: The console MUST name the cluster a project is placed on wherever it names the project,
  and MUST show a platform administrator the clusters, their tether state and last seen.

**Documentation**

- **FR-021**: `docs/platform/install-cloud.md` MUST gain a section on joining a remote cluster — what
  to install there, the two settings, the token — and MUST say when to choose a remote cluster and
  when a spoke installation; `docs/concepts/architecture.md` and `docs/concepts/control-plane.md`
  MUST describe the tether; the CLI reference MUST carry `ankka clusters`; `docs/reference/limitations.md`
  MUST say that a project is on one cluster and services do not call across clusters.

### Key Entities

- **Cluster**: a named set of machines the installation can place projects on: the home cluster, or
  a remote cluster with a domain, a token digest, the organizations it admits, and its tether state.
- **Placement**: which cluster a project is on; set at creation, never changed.
- **Tether**: the one connection a remote cluster's operator keeps open to the control plane; desired
  state goes down it, reports and answers come up it.
- **Cluster token**: the credential a remote cluster's operator presents to tether, shown once,
  kept as a digest, renewed to revoke.
- **Tether bound**: how long a tether may be down before every service on the cluster reads unconfirmed.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In the two-cluster k3s suite, a service applied to a project placed on the remote
  cluster is reported `Ready` at the control plane within 90 seconds of apply, with the Deployment
  on the remote cluster and nothing of it on the home cluster.
- **SC-002**: With the tether cut, the service keeps answering throughout and every service on the
  cluster reads `unconfirmed` within the tether bound plus 5 seconds; with the tether restored, the
  first confirmed report arrives within 10 seconds, and an apply made during the cut is reported
  `Ready` at its generation within 90 seconds of the restore.
- **SC-003**: The control plane's main sources contain no address or credential of any remote cluster
  and `EventCompatibilitySuite` asserts no cluster event carries a token; the operator's ClusterRole
  on a remote cluster holds no `get` or `list` on Secrets, proved under the real identity as
  `OperatorClusterSuite` proves it today.
- **SC-004**: A second remote cluster receives no object, no credential and no desired state of a
  project placed on the first, asserted by the suite from the tether's record of what was sent.
- **SC-005**: The projector's offline suite runs unchanged against the fake for both kinds of client,
  and no projector source changes for this feature beyond choosing a client by placement.
- **SC-006**: The first remote cluster of an installation that is not a suite — an arm64 k3s cluster
  at home joined to a control plane in the cloud — reaches `Ready` for the sample within 10 minutes of
  its operator starting, recorded in the plan's research.

## Assumptions

- The tether is one long-lived HTTPS connection through the installation's gateway at
  `api.<base domain>`, on routes of the control plane that accept a cluster token and nothing else;
  whether it is server-sent events, a socket route or gRPC is the plan's choice. It needs no new
  port, no new hostname and no change to the gateway.
- The operator keeps its single image and its manifests; a remote cluster's overlay is the platform's
  components less the control plane, Keycloak and the console, with the two settings on the operator.
  That overlay is the private infrastructure repository's; ankka's example production overlay gains a
  remote variant so `RemoteOverlaySuite` renders it.
- A project's placement is a field of the `Project` entity, defaulted to the home cluster for every
  project that exists; the journal replays unchanged.
- The home cluster is a cluster like any other to the model and the CLI, with no token, no tether and
  the base domain as its domain; nothing on the home path changes behaviour.
- The tether bound is 30 seconds as shipped and is the installation's to set.
- The control plane's existing reconciler sweep, which lists resources on a timer, lists a remote
  cluster's from the operator's last full report rather than over the tether each time.
- A remote cluster's identity provider is the installation's: its services verify tokens against
  `auth.<base domain>` over the internet as a spoke's do.
- The hosted product's "region" (its D7, a spoke per region) may become a remote cluster of one
  installation; that is the product's decision and not this feature's.

## Dependencies

- **049 (multi-architecture images)**: the first remote cluster is arm64; its operator needs the
  image.
- **001 (reconciliation)** and **003 (deploy a real service)**: the projector, the resource and the
  report this feature carries over the tether.
- **005 (expose services)**: `Hostnames`, which gains the cluster's domain as its base.
- **008 (authenticated control plane)**: who a platform administrator is; the cluster token beside
  the deploy token.
- **011 (hosted ankka)**: the spoke, which stays for tenancy; the install page says when to choose
  which.
- **014 (zero trust)** and **022 (service identity)**: the per-cluster authority a remote cluster
  brings.
- **021 (web hosting)**, **027 (managed broker)**, **034 (object storage)**, **044 (cloud
  provider)**: per-cluster provisions the operator already reads from its settings.
- **040 (cross-project access)**: grants are within a cluster; across is refused.
- **023 (secret store)**: project secrets, which cross the tether once.

## Open Questions

- Whether a project may one day move between clusters (its databases would have to, through 041's
  backup and restore); noted, not specified.
- Whether a service on one cluster should be able to call a service on another through the calling
  cluster's gateway, with a certificate the other cluster trusts; noted, not specified.
- Whether a remote cluster's operator should also serve `ankka services logs` when the cluster is
  the home one — that is, whether the home path should become a tether to itself, so there is one path
  rather than two. Kept as two in this feature so the home cluster changes nothing.
