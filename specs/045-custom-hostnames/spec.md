# Feature Specification: Custom Hostnames for an Exposed Service

**Feature Branch**: `045-custom-hostnames`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "The platform derives an exposed service's hostname as
`<service>-<project>.<base domain>` and the limitations page says a domain of your own is not
supported. A product live at its own domain (app.dumaflow.com, with an installed app polling it)
cannot move to ankka without this. A member names a hostname the owner brings, in addition to the
derived one; the owner proves control of it; the platform routes it to the service and obtains a
certificate for it, since the installation's wildcard cannot cover another domain; the derived
hostname keeps working; the caller is still read as the gateway and web hosting still derives
`X-Forwarded-*` from the hostname; what a browser sees during a rollout is unchanged; removing
the hostname and deleting the service release it. Out of scope: hostnames at the gateway for
anything but services, DNS management, and rate limiting."

## Context

An exposed service answers at exactly one hostname, and the platform chooses it. `Hostnames.of`
in `crd/.../Hostnames.scala` is the one derivation, `<service>-<project>.<base domain>`, shared
by the control plane, which shows it and refuses a collision (`ExposureRules.refusal`,
`ServiceEndpoint.hostnameHolder`), and by the operator, which renders it into the route
(`Rendering.httpRoute`) and, deliberately, never reads a hostname from the resource: "no writer of
the resource can point a route at a name the service does not own". The hostname is one label
under the base domain because a wildcard is one label deep (feature 005, research R2), for the
installation's single certificate `*.<base domain>` in the Secret `ankka-wildcard-tls` and for
the gateway's one HTTPS listener alike (`kustomization/components/gateway/gateway.yaml`, hostname
`*.BASE_DOMAIN`). The operator's grant is `httproutes` and `backendtlspolicies` and nothing else
about routing: not gateways, listeners, referencegrants or the certificates the gateway serves
(`kustomization/components/operator/operator.yaml`), and `features/exposure/tenancy.feature`
holds that the operator cannot change how the gateway is reached from the internet. The
limitations page says it plainly: "No custom hostnames. A domain of your own is not supported."

That is the right default and the wrong ceiling. A product with customers is live at a domain of
its own, with links in emails, an installed app that polls it and a search engine that knows it.
It cannot move to ankka if moving means a new address. DumaFlow is the concrete case: its phones
poll `app.dumaflow.com` for updates and its documents carry that address.

Everything the platform already does for the derived hostname is what a custom one needs, with
two things it does not have. The gateway's HTTPS listener matches only `*.<base domain>`, so a
route carrying any other hostname is rejected by the gateway, and the wildcard certificate does
not cover another domain, so the gateway has nothing to present for it. The decisions below add
exactly those two and change nothing else:

- **A custom hostname is added to an exposed service, by a command.** Exposure is a decision made
  after deploying, by a command, so applying a descriptor never changes it (`docs/deploy/expose.md`).
  A custom hostname is part of exposure and follows the same rule: `ankka services hostnames add`
  and `remove`, recorded on the service entity as events beside `ServiceExposed`, projected onto
  the resource beside `exposed`, and shown by `services get`. A descriptor says nothing about
  hostnames. The derived hostname is never replaced: a custom hostname is in addition to it, and
  unexposing a service stops every hostname it has.
- **The owner proves control before the claim is recorded.** The platform manages no DNS, but
  it reads it once, when a member adds a hostname: the control plane looks up the `TXT` records of
  `_ankka.<hostname>` for the value `ankka-project=<project id>`, and refuses the hostname until
  the record is there, naming the record to create. (The record sits beside the name, not at it,
  because a name that is a `CNAME` can carry no other record; its value is the project's id,
  since only the owner of the zone can write it, which is the whole proof — plan, R3.) Only a
  proved name is recorded as held, so a name cannot be claimed by a project that does not control
  its domain. The proof record is the project's: one record proves every hostname the project
  brings, and `services get` tells it. Once the claim is
  recorded the owner points the name at the installation with a second record, a `CNAME` to the
  service's derived hostname or an address record to the gateway's address, which the control
  plane also tells them. The certificate authority's challenge is answered through the
  installation's gateway and succeeds only when the name resolves here; a hostname that is proved
  but not yet pointed is held, reported as pending with the authority's reason and the record to
  create, and serves nothing. The platform never judges for itself whether a name resolves: after
  the proof lookup it reads no DNS, and what it shows for a pending hostname is what the authority
  said. The control plane does not read the proof record again: the claim stands until a member
  removes the hostname or a platform administrator takes it away.
- **One hostname is held by one service across the installation.** The control plane refuses a
  hostname another service holds, as it refuses a derived label another service derives. It also
  refuses any hostname under the base domain: those are the platform's, covered by the wildcard
  and derived for services, and a custom one there would collide with a derivation that does not
  exist yet. A platform administrator can take a hostname away from a service that holds it, for
  a domain that has changed hands since its claim was proved.
- **The gateway serves the hostname with a certificate of its own.** The operator asks cert-manager
  for a certificate for the hostname, in the project's namespace, from the issuer the installation
  names for custom hostnames: on a cloud installation an ACME issuer answering the HTTP-01
  challenge through the gateway, since a hostname is one name and needs no DNS-01; on a local
  platform the installation's own authority, as `overlays/local/local-ca.yaml` signs the wildcard.
  The operator attaches to the gateway only what is for that hostname, a listener for it carrying
  that certificate, and still cannot change how the gateway is reached for the base domain, the
  control plane or the store. The attachment is a listener set in the project's namespace, which
  the gateway admits by selector and whose listeners lose every conflict with the gateway's own
  (plan, R1); the issuer is cluster-scoped, since a certificate in a project's namespace can name
  no other kind (plan, R5); the tenancy rule is held by a scenario.
- **The route carries every hostname the service has.** `Rendering.httpRoute` renders the derived
  hostname and each custom hostname the resource carries, with the same rules, gRPC's first, to the
  same backend, under the same `BackendTLSPolicy`. gRPC answers at a custom hostname as it does at
  the derived one. A web-hosted service's mounts are under its custom hostname too, since a mount
  is a path of the service.
- **The proxy states the address the gateway routed a request to.** Today the operator gives a
  web-hosted service's proxy one public authority (`ANKKA_PROXY_PUBLIC_AUTHORITY`) and
  `Headers.inbound` derives `X-Forwarded-Host` from it, never from the request. The gateway routes
  a hostname only to the service that holds it, so a request that reaches the proxy from the
  gateway arrived at a hostname the service holds, and that name is the gateway's word, not the
  request's: the proxy states it, and the operator gives the proxy no list, since a variable would
  roll the pod on every change and a mount would roll every web-hosted service once (plan, R12). A
  request that says it was sent to another address, in a forwarded header, is still told the
  hostname it was sent to.
- **Nothing about the caller, readiness or rollouts changes.** Every request from the internet is
  the gateway, whichever hostname it arrived at (`docs/reference/limitations.md`). The route's
  backend is the service's own Service, so a request by any hostname reaches only a ready instance.
  A rollout refuses no request at a custom hostname and is as it was for the derived one.
- **Removing a hostname releases it.** `hostnames remove` takes away the route's hostname, the
  listener and the certificate, and another service may claim the name. Deleting the service does
  the same through ownership, as it removes the route today. A certificate the platform obtained
  is the platform's to discard.

What this feature is not: a hostname at the gateway for anything but a service (the control plane,
the console, the identity provider and the store keep their derived names); DNS management, a
zone the platform writes to, or a stable address the platform promises (a cloud installation's
gateway has whatever address its load balancer has, and a local platform has none); a certificate
the owner supplies rather than the platform obtains, which expires in someone else's calendar and
is a later feature if asked for; a hostname shared by two services by path; or any authentication,
rate limiting or header policy at the gateway.

## Clarifications

### Session 2026-10-09

- Q: How does a service come to hold a custom hostname: is the claim recorded first, with proof coming only from the certificate challenge, or must control be proved before the claim is recorded? → A: Prove first. The control plane requires a TXT record, holding a token it issues, at the name before it records the claim, and looks the record up itself when a member adds the hostname.
- Q: How does the platform know a custom hostname's name does not yet resolve to the installation? → A: Only from the authority. The hostname is pending with the authority's reason, which for an ACME issuer is that the challenge could not be reached at the name; the platform never says "does not resolve" on its own, and after the proof lookup never reads DNS.
- Q: Is there a limit on how many custom hostnames a service, or a project, may hold? → A: A fixed cap per service, 5, refused beyond it, naming the cap. Not configurable in this feature.
- Q: Is `custom hostname` the right term, and are *own domain*, *vanity domain*, *bring-your-own domain* and *custom domain* the right refused synonyms? → A: Yes. The term is settled in the glossary; the thing added is one name, not a domain, and the platform writes nothing in the owner's zone.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A product answers at its own domain (Priority: P1)

A member exposes a service and adds the hostname `app.example.com` to it. The control plane
refuses, telling them the `TXT` record that proves the project controls the name. They create it
at their DNS provider and add the hostname again; it is recorded, and the control plane tells them
the record that points the name at the installation. They create that too. Within a few minutes
the service answers at `https://app.example.com` with a certificate a browser trusts, and it still
answers at its derived hostname. `services get` shows both hostnames and that the custom one is
serving.

**Why this priority**: This is the feature. A product live at its own domain cannot move to ankka
without it, and everything else here is a consequence of it.

**Independent Test**: In the k3s suite, with the local authority as the installation's issuer for
custom hostnames and a resolver the control plane is pointed at that the test populates, add a
hostname to the exposed sample and assert the refusal names the proof record; serve the record,
add again, resolve the name to the gateway's address from the test (as a hosts file would), and
assert a request to it is answered by the service with a certificate for that hostname from the
installation's authority, while a request to the derived hostname is answered as before. Assert
`services get` lists both and the custom one as serving.

**Acceptance Scenarios**:

- added `features/exposure/custom-hostnames.feature`: a service answers at a custom hostname with a certificate for that hostname
- added `features/exposure/custom-hostnames.feature`: a service that gains a custom hostname still answers at the hostname the platform derived
- added `features/exposure/custom-hostnames.feature`: a custom hostname is refused until the project proves control of the name
- added `features/exposure/custom-hostnames.feature`: a custom hostname whose name carries the project's proof is recorded
- added `features/exposure/custom-hostnames.feature`: a member is told the record to create for a custom hostname
- added `features/exposure/custom-hostnames.feature`: a custom hostname whose name does not resolve to the installation is held and serves nothing
- added `features/exposure/custom-hostnames.feature`: a member is shown every hostname of a service and where each stands
- added `features/exposure/custom-hostnames.feature`: a gRPC call is answered at a custom hostname
- added `features/exposure/custom-hostnames.feature`: a request sent to a custom hostname in the clear is redirected and served by no handler
- added `features/exposure/custom-hostnames.feature`: a service that is not exposed cannot be given a custom hostname
- added `features/exposure/custom-hostnames.feature`: the console shows a service's custom hostnames beside the one the platform derived
- added `features/exposure/custom-hostnames.feature`: a custom hostname that is not a name is refused, naming what is wrong
- added `features/exposure/custom-hostnames.feature`: a custom hostname is refused when the installation names no authority for custom hostnames
- added `features/exposure/custom-hostnames.feature`: a sixth custom hostname is refused, naming the cap
- added `features/exposure/custom-hostnames.feature`: a certificate the authority refuses is shown with the authority's reason

---

### User Story 2 - A hostname is one service's and the platform's names are nobody's (Priority: P1)

A second service, in the same or another project, asks for a hostname the first holds and is
refused, naming the holder. A member asks for a hostname under the installation's base domain and
is refused. A platform administrator takes a hostname away from a service whose project no longer
controls the domain, and the new owner's service claims it.

**Why this priority**: A hostname two services hold is a request answered by the wrong product,
and a custom hostname under the base domain collides with the platform's own derivation. Without
these refusals the feature is unsafe to turn on.

**Independent Test**: Through the control plane's HTTP API, add a hostname to one service, add the
same to another and assert the refusal names the first; add `x.<base domain>` and assert the
refusal; take the hostname away as a platform administrator and add it to the second, and assert
it succeeds and the first no longer lists it.

**Acceptance Scenarios**:

- added `features/exposure/custom-hostnames.feature`: a custom hostname another service holds is refused, naming the holder
- added `features/exposure/custom-hostnames.feature`: a custom hostname under the base domain is refused
- added `features/exposure/custom-hostnames.feature`: a platform administrator takes a custom hostname away from a service
- added `features/exposure/custom-hostnames.feature`: a custom hostname taken away from a service can be given to another
- changed `features/exposure/tenancy.feature`: the operator cannot change how the gateway itself is reached
- added `features/exposure/tenancy.feature`: the operator attaches to the gateway only what serves a hostname a service holds

---

### User Story 3 - A web-hosted interface knows which address it was reached at (Priority: P2)

A web-hosted service with the custom hostname `app.example.com` is asked for a page at that
hostname. Its process is told `app.example.com` as the address the request was sent to, so the
links it writes and the cookies it sets name the address the browser used. A request to the
derived hostname is told the derived one. A request that claims another address is told the
hostname it arrived at. A browser reaches the services mounted under the interface at the custom
hostname too.

**Why this priority**: An interface at a custom hostname that writes links to the derived one
sends every user back to a name they were never meant to see. It is P2 because a service made of
components reads nothing from these headers.

**Independent Test**: In the k3s suite, deploy the web sample with a mount, add a custom hostname,
request a page at it and assert the process saw the custom hostname as the forwarded host; request
at the derived hostname and assert the derived one; request under the mount at the custom hostname
and assert the mounted service answered.

**Acceptance Scenarios**:

- added `features/web-hosting/requests.feature`: the process is told the custom hostname a request was sent to
- changed `features/web-hosting/requests.feature`: a request cannot say that it was sent to another address
- added `features/web-hosting/mounts.feature`: a mount is reached under a custom hostname of the web-hosted service

---

### User Story 4 - Removing a hostname releases it, and so does deleting the service (Priority: P2)

A member removes a custom hostname. Within a short time nothing answers at it, the derived
hostname still answers, no instance restarts, and another service may claim the name. A member
unexposes a service and nothing answers at any of its hostnames; exposing it again brings every
hostname back without adding them again. A member deletes a service and its hostnames are
released.

**Why this priority**: The lifecycle is what makes the feature operable: a hostname that cannot
be moved from one service to another is a hostname that cannot survive a service being renamed
or rebuilt.

**Independent Test**: In the k3s suite, remove the hostname and assert nothing answers at it, the
derived hostname does, and the pods are the same pods; unexpose and assert neither answers, expose
and assert both do; delete the service and add the hostname to another service, asserting it
succeeds.

**Acceptance Scenarios**:

- added `features/exposure/custom-hostnames.feature`: removing a custom hostname stops it answering and changes nothing else
- added `features/exposure/custom-hostnames.feature`: unexposing a service stops every hostname it has
- added `features/exposure/custom-hostnames.feature`: a service exposed again answers at every hostname it had
- added `features/exposure/custom-hostnames.feature`: a deleted service leaves its custom hostnames free for another service

---

### Edge Cases

- A hostname that is not a hostname (spaces, a scheme, a path, a port, a wildcard, a label over 63
  characters, or more than 253 characters): refused at the control plane, naming what is wrong. A
  wildcard is refused because a certificate for one needs a challenge the platform does not run.
- A hostname the project never proves: never recorded. Each attempt to add it is refused, naming
  the proof record to create; nothing is held and nothing is shown on the service.
- A hostname the owner proves but never points: held and pending indefinitely, serving nothing,
  listed with the authority's reason and the record to create. Nothing times out; the member
  removes it or the record arrives. Under an authority that runs no challenge, the local one, the
  certificate issues at once and the hostname is reported serving: the gateway serves it, and
  whether anything resolves to it is the owner's to know.
- A proof record removed after the claim was recorded: the claim stands; the control plane reads
  the proof once, when the hostname is added. A domain that changes hands is a platform
  administrator's to take away from its old holder.
- The control plane's resolver cannot be reached, or the lookup times out: the hostname is
  refused, saying the name could not be looked up rather than that the proof is missing, so a
  member does not create a record they already have.
- A hostname whose record is removed after it was serving: the certificate stays valid until it
  expires and is not renewed; the hostname stays serving while the certificate is valid, with the
  authority's reason for the failed renewal shown beside it. The platform does not probe DNS
  itself.
- An authority that refuses or rate-limits issuance: reported as pending with the authority's
  reason, as a rejected route reports the gateway's. Let's Encrypt limits certificates per
  registered domain per week, which is the owner's domain, not the installation's.
- The installation names no issuer for custom hostnames: adding one is refused, naming the
  configuration, as exposing with no base domain is.
- A paused service: its hostnames answer nothing, as the derived one does today, and come back on
  resume.
- A hostname added to a service in a project that is later deleted: released with the service.
- Two members of projects that have both proved the name add it to two services at the same
  moment: the listing can lag, so both may be recorded; the gateway then serves one and rejects
  the other visibly, as it does for a derived collision today, and the status says so.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The control plane MUST accept, for an exposed service, the addition and removal of a
  custom hostname by command, record each as an event on the service, and project the service's
  custom hostnames onto the resource beside `exposed`. A descriptor MUST NOT name a hostname.
- **FR-002**: The control plane MUST refuse a custom hostname that is not a valid DNS name, is a
  wildcard, is the base domain or any name under it, is one of the platform's own hostnames, or is
  held by another service of the installation, naming the reason and, for a holder, the service.
- **FR-002a**: The control plane MUST refuse a custom hostname whose name does not carry the
  project's proof record when the hostname is added, naming the record to create, and MUST look
  the record up itself at that moment and never later. A refusal for a name the control plane
  could not look up MUST say so rather than say the proof is missing. The proof is checked after
  every other refusal in FR-002 and FR-003, so a member is told the first thing that is wrong.
- **FR-003**: The control plane MUST refuse a custom hostname for a service that is not exposed,
  and MUST refuse one when the installation names no issuer for custom hostnames.
- **FR-003a**: A service MUST hold at most 5 custom hostnames; a sixth is refused, naming the
  cap. The cap is the platform's and not configurable in this feature; removing a hostname, or
  an administrator taking one away, makes room for another.
- **FR-004**: The control plane MUST tell a member two records: the proof record, `TXT` at
  `_ankka.<hostname>` with the value `ankka-project=<project id>`, whenever a hostname is refused
  for lacking it and whenever the service is read; and the record to create, a `CNAME` to the derived hostname or an
  address record to the gateway's address where the installation knows it, when a hostname is
  recorded and whenever the service is read.
- **FR-005**: The operator MUST render the route for an exposed service with its derived hostname
  and every custom hostname the resource carries, with the same rules and backend, and MUST still
  derive the derived hostname itself rather than read it from the resource.
- **FR-006**: For each custom hostname the operator MUST ask cert-manager for a certificate for
  that hostname from the issuer the installation names for custom hostnames, in the project's
  namespace, owned by the service, and MUST never read the issued key.
- **FR-007**: For each custom hostname the operator MUST attach to the installation's gateway
  exactly what serves that hostname with that certificate, and MUST NOT be able to change how the
  gateway is reached for the base domain, the control plane, the identity provider or the store.
- **FR-008**: The resource's status MUST report each custom hostname as `pending` with the reason
  (the certificate is being issued, or the authority's reason, which for an ACME issuer may be
  that the challenge could not be reached at the name), `serving`, or `rejected` with the
  gateway's reason, and the control plane MUST show them with `services get`, in the listing and
  in the console beside the derived hostname. The platform MUST NOT state on its own whether a
  name resolves: what it reports for a pending hostname is the authority's word, and a renewal
  the authority refuses MUST be shown beside a hostname that stays serving.
- **FR-009**: A request from the internet at a custom hostname MUST reach only a ready instance,
  MUST be read by the service as the gateway, MUST be redirected to HTTPS when sent in the clear,
  and MUST be routed to the gRPC port when its content type is gRPC's, exactly as at the derived
  hostname.
- **FR-010**: A web-hosted service's proxy MUST state as the address a request from the gateway
  was sent to the hostname the gateway routed it at, which by the gateway's routing is one the
  service holds, and never a name a forwarded header states; the operator MUST give the proxy
  nothing that changes its pod when a hostname is added or removed.
- **FR-011**: A mount of a web-hosted service MUST be reached under each of its hostnames.
- **FR-012**: Removing a custom hostname MUST remove its route hostname, its attachment to the
  gateway and its certificate, MUST leave the service, its instances and its derived hostname as
  they were, and MUST release the name for another service to claim. Deleting the service MUST do
  the same. Unexposing a service MUST stop every hostname it has and keep them recorded; exposing
  it again MUST serve them again.
- **FR-013**: A platform administrator MUST be able to take a custom hostname away from any
  service, as a recorded event, so that a domain that changed hands after its claim was proved
  cannot be kept from its new owner.
- **FR-014**: The cloud overlay MUST provide a cluster-scoped issuer for custom hostnames that
  answers the ACME HTTP-01 challenge through the installation's gateway, and the local overlay
  MUST name the installation's own authority as a cluster-scoped issuer, so a local platform serves
  a custom hostname a developer points at it with a hosts file, once they have created the proof
  record at their provider; nothing waives the proof.
- **FR-015**: The documentation MUST say how a custom hostname is added, proved, shown and
  removed, what record to create and why the platform manages no DNS, and the limitations page
  MUST no longer say a domain of your own is unsupported, saying instead what remains out of scope.

### Key Entities

- **custom hostname**: a hostname the owner of a domain brings to an exposed service, in addition
  to the hostname the platform derives. Held by one service of the installation; pending, serving
  or rejected.
- **the proof record**: `_ankka.<hostname> TXT "ankka-project=<project id>"`, which the control
  plane looks up once, when the hostname is added, and requires before it records the claim. One
  record proves every hostname the project brings.
- **the record to create**: what a member is told to put in their DNS so a custom hostname resolves
  to the installation: a `CNAME` to the derived hostname, or an address record to the gateway.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A service with a custom hostname whose name resolves to a cloud installation answers
  at it with a publicly trusted certificate within 5 minutes of the hostname being added.
- **SC-002**: A request to a custom hostname takes the same path as one to the derived hostname:
  one listener on the same port, the same route and rules, the same backend. The k3s suite shows it
  by sending the same requests at both hostnames and asserting the medians within a factor of two,
  which is the difference measurement noise on a k3s node admits.
- **SC-003**: No hostname is ever served for two services at once: in the k3s suite, two services
  claiming one name result in exactly one serving and one reported rejected or refused.
- **SC-004**: A custom hostname removed stops answering within 30 seconds, and no instance of the
  service restarts.
- **SC-005**: Every scenario this spec names passes in the k3s suite against a cloud-shaped
  installation whose issuer is an ACME authority the suite runs in the cluster, answering a real
  challenge through the gateway; the local authority's path is one case of the operator's k3s
  suite; the web-hosted scenarios pass with the web sample.
- **SC-006**: A web-hosted service's process is told the hostname a request arrived at in 100% of
  requests at either hostname, and never a name the request stated.

## Assumptions

- Control of a hostname is proved before its claim is recorded, by a `TXT` record the control
  plane looks up once (Clarifications, 2026-10-09). The alternative, first claim holds with an
  administrator able to take a wrong claim away, was not chosen: a name already pointed at the
  installation, as one is while a product moves, could be claimed and served by a project that
  does not own it until an administrator acted. The lookup is the one DNS read the platform makes;
  it manages no DNS and probes none after the claim.
- The proof record is `_ankka.<hostname> TXT "ankka-project=<project id>"` (plan, R3): one record
  serves every hostname a project brings, `services get` tells it, and a refusal for a missing
  proof states the whole record to create.
- The control plane resolves the proof record through the resolver its environment gives it, and
  a k3s suite points it at a resolver the test populates; the installation does not run a
  resolver of its own. That lookup is the platform's only DNS read: whether a name resolves to
  the installation afterwards is known only through the authority (Clarifications, 2026-10-09),
  so the k3s suite needs an ACME authority in the cluster for the one scenario where the
  authority's challenge fails.
- The certificate for a custom hostname is obtained by the platform, over ACME HTTP-01 on a cloud
  installation, and is never supplied by the owner in this feature.
- A service may hold up to 5 custom hostnames (Clarifications, 2026-10-09). Each is a certificate
  and a listener attachment of its own; none counts towards an organization's quota. The cap
  bounds the gateway's listeners and the authority's issuance per installation without a new
  administrative setting; a quota per organization is a later feature if 5 proves too few.
- A custom hostname is served by the gateway on the ports the installation's gateway already uses,
  443 and 80 on a cloud installation and 8443 and 8080 locally; the port is part of the record the
  member is told on a local platform.
- The apex of a domain (`example.com` with no label) is allowed where the installation knows its
  gateway's address; a `CNAME` cannot sit at an apex, and the control plane says so when it tells
  the member the record to create.
- The Gateway API implementation pinned in `kustomization/components/envoy-gateway` (v1.9.1, with
  Gateway API v1.6.1) serves a `ListenerSet` in a project's namespace once the gateway allows
  them by selector; the operator is granted that kind and nothing on the gateway (plan, R1).
- The certificate and its Secret for a custom hostname are named by the hostname, so a project
  secret's name may no longer contain a dot (plan, R8).
- The address an apex record points at is an installation setting, `ANKKA_GATEWAY_ADDRESS`; a
  cloud installation sets it from its load balancer and a local platform has none (plan, R17).
- Hostnames for the control plane, the console, the identity provider and the object store are
  out of scope and keep their derived names.
