# Feature Specification: A Console for a Deployed Installation

**Feature Branch**: `017-control-plane-console`

**Created**: 2026-09-28

**Status**: Draft

**Input**: User description: "Address the frontend, or lack thereof: build a SPA that runs in the
cluster as a UI for the control plane. Server-rendered TypeScript, so the system presenting ankka to
users is very responsive. Users sign in securely — server-side HTTP cookies, not access tokens in
local storage — against the Keycloak that already handles access and refresh tokens. Start with
CRUD for organizations, projects and services."

## Context

Everything a person can do to an installation today, they do from the CLI. `ankka login` signs them
in through the installation's Keycloak with the device grant; `ankka organizations`, `projects` and
`services` drive the control plane's HTTP API; `ankka local console` shows the services running on
their own machine. The limitations page says the rest plainly: *there is no console for a deployed
installation; the CLI is the only client for anything in a cluster.* A developer evaluating ankka
installs a CLI before they have seen a single thing the platform holds, and an owner adding a member
or reading why a service is `Failed` opens a terminal to do it.

This feature is the console for a deployed installation: a web application, deployed beside the
control plane by the same manifests, reachable at `https://console.<base domain>`, that signs a person
in through the installation's identity provider and lets them see and change what they are entitled
to — organizations, their members and deploy tokens; projects and their registries; services, their
status, history and logs — with the same rules the CLI is held to, because it is the same API behind
both.

Three facts about the platform decide the shape:

- **The control plane authorizes; the identity provider authenticates.** Every route on the control
  plane wants a bearer token from the realm, verified offline; the realm is one file and already
  carries the CLI's public client. The console is therefore a second client of that realm and a
  second client of that API, and it must not become a third place authorization rules live. It holds
  no state about who may do what: it asks, and it shows the answer, including the refusal.
- **A browser is not a machine that can be trusted with a bearer token.** An access token in local
  storage, or in a cookie a script can read, is readable by any script that runs on the page. So the
  console is *server-rendered*: a server the platform runs holds the tokens, the browser holds only a
  session cookie it cannot read, and the server calls the control plane on the browser's behalf. That
  server is also what makes the console fast — the first page arrives rendered, and navigation after
  that is client-side without a full reload — and what lets every action work with scripts off.
- **Zero trust is an overlay property.** In the cluster every port a workload has is mutual TLS, a
  caller is named by its certificate, and a network policy decides who may connect. The console is a
  platform workload: it holds a platform identity, reaches the control plane the way the gateway does,
  and is reached by the gateway the way the control plane is. Locally, it runs against the compose
  Keycloak and `sbt controlPlane/run` in the clear, as the CLI does.

The name is deliberate. The local console shows services on your machine; this is *the installation's
console*, and the limitations page's "no console for a deployed installation" is the sentence this
feature removes.

What this phase is *not* is a second product. Signup, billing and provisioning belong to `ankka-cloud`;
observability of a running service — traces, sessions, entity state — belongs to the local console
today and to a later feature in the cluster; people are still added to the realm in Keycloak's own
administration console, since the platform holds no administrative credential for it and this feature
does not give it one.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Sign in through the identity provider, with nothing a script can steal (Priority: P1)

A person opens the console's address. They are not signed in, so they are sent to the installation's
Keycloak, sign in there — with whatever Keycloak asks of them, a password, a second factor, a
federated login — and come back to the console signed in, on the page they first asked for. From
then on every page and every action is theirs. Their browser holds one cookie it cannot read from a
script; no access token, refresh token or identity token ever reaches it. When they sign out, the
console forgets them *and* ends their Keycloak session, so the next visit asks them to sign in again.
When they leave the console idle past the realm's session timeout, the next page sends them to sign
in, and brings them back to where they were.

**Why this priority**: Without a sign-in that is safe to expose on the internet there is no console
to put behind it, and a sign-in that is *unsafe* is worse than the CLI it replaces. Everything else
in the feature is a page behind this one.

**Independent Test**: With a browser driven by a test against a real Keycloak and the real control
plane: open a deep link, complete Keycloak's form, land on the deep link signed in; inspect every
cookie and every byte of HTML, script and storage the browser holds and find no token; sign out and
find the Keycloak session gone; let a session expire and find the next request redirected to sign in
and then back.

**Acceptance Scenarios**:

1. **Given** a person with no session, **When** they open any console page, **Then** they are sent to
   the identity provider's sign-in, and after signing in they arrive at the page they asked for,
   signed in.
2. **Given** a signed-in person, **When** the browser's cookies, local storage, session storage and
   every response body are inspected, **Then** no access token, refresh token or identity token
   appears in any of them, and the session cookie cannot be read by script, is sent only over HTTPS
   in a cluster, and is not sent on cross-site requests that change state.
3. **Given** a signed-in person, **When** they sign out, **Then** the console's session is gone, the
   identity provider's session for them is ended, and opening the console again asks them to sign in.
4. **Given** a person whose identity provider session has ended — by timeout, by signing out
   elsewhere, or by an administrator — **When** they next load a page, **Then** they are sent to sign
   in rather than shown an error, and after signing in they return to that page.
5. **Given** a sign-in that comes back to the console with a state the console did not issue, or from
   an identity provider the console does not trust, **When** the callback arrives, **Then** it is
   refused and no session is created.
6. **Given** a return-to address supplied by a link, **When** it names another site, **Then** the
   console ignores it and lands the person on its own front page.
7. **Given** the console runs as more than one instance, **When** a person's requests reach different
   instances, **Then** their session works on every one of them, and restarting an instance signs
   nobody out.
8. **Given** the identity provider is unreachable, **When** a signed-in person loads a page whose
   session needs renewing, **Then** they are told the identity provider cannot be reached and asked to
   retry, and their session is not discarded.

---

### User Story 2 - Organizations and projects, the way the CLI sees them (Priority: P1)

Signed in, a person sees the organizations they belong to, with their role in each, and — for a
platform administrator — every organization in the installation. They create an organization and
become its owner, rename one they own, and delete one that is empty. Inside an organization they see
its projects, create one, rename it and delete an empty one. Every refusal the control plane gives is
shown as what it is: an id that was used before and cannot come back, a name only an owner may change,
an organization that is not empty, an installation where organizations are created for them — with
the sign-up address the installation supplies, when it supplies one.

**Why this priority**: Organizations and projects are the tenancy every other page hangs off; with
this story alone the console already replaces the first commands a new user runs.

**Independent Test**: Against a real control plane: create an organization and a project in it from
the console and read both back with the CLI; rename and delete both; attempt each refusal and read the
message shown. Against a control plane restricted to platform administrators, attempt to create an
organization as a member and see the sign-up address.

**Acceptance Scenarios**:

1. **Given** a signed-in member of two organizations, **When** they open the console, **Then** they
   see both, each with its name, id and their role, and nothing about any other organization.
2. **Given** a platform administrator, **When** they open the console, **Then** they see every
   organization in the installation, marked as seen by administration rather than membership.
3. **Given** a signed-in person, **When** they create an organization with an id and a name, **Then**
   it exists with them as its first owner, and they are taken to it — not to a listing that may not
   show it yet.
4. **Given** an organization the person owns, **When** they rename it, **Then** the new name shows
   everywhere it appears; **When** a member who is not an owner tries, **Then** the rename is not
   offered, and if attempted anyway is refused with the control plane's reason.
5. **Given** an organization with no projects, **When** an owner deletes it, **Then** it is gone from
   their listing; **When** it has projects, **Then** the deletion is refused with the reason.
6. **Given** an id that belonged to a deleted organization or project, **When** anyone creates one
   with that id, **Then** the conflict is shown, naming that the id cannot be reused.
7. **Given** an organization, **When** a member creates, renames and deletes projects in it, **Then**
   each takes effect and shows, and a project with services cannot be deleted.
8. **Given** an installation where only the platform administrator creates organizations, **When** a
   member tries to create one, **Then** the refusal is shown with the installation's sign-up address
   if it has one.

---

### User Story 3 - Services: status, descriptor and the operations the CLI has (Priority: P1)

Inside a project, a person sees its services with their lifecycle state, ready and desired instances,
image and hostname. They open one and see everything `ankka services get` shows — state, detail,
generation, database, hostname, whether it is paused or suspended — and its history of applies and
restarts, each attributed. They apply a descriptor by pasting or uploading `service.json`, and the
console shows the control plane's verdict: accepted, or refused with each problem named. They pause,
resume, restart, expose and unexpose a service, read its logs, and delete it.

**Why this priority**: A service is what the platform is for. With stories 1–3 a developer can go from
nothing to a deployed, exposed service and see why it is or is not `Ready`, without installing the CLI.

**Independent Test**: Against a real control plane in a real cluster: apply the shopping cart's
descriptor from the console, watch it reach `Ready`, expose it, open its hostname, read its logs and
history, pause and resume it, and delete it — each step checked with the CLI. Apply an invalid
descriptor and read every problem the control plane names.

**Acceptance Scenarios**:

1. **Given** a project with services, **When** a member opens it, **Then** each service shows its
   lifecycle state, ready of desired instances, image, generation and hostname when exposed.
2. **Given** a service, **When** a member opens it, **Then** they see every field the API's service
   status carries, in words a person can read, and its history with who did what and when.
3. **Given** a descriptor, **When** a member applies it to a new or existing name, **Then** the
   service is created or updated and its generation increments; **When** the descriptor is refused,
   **Then** every problem the control plane names is shown beside the descriptor.
4. **Given** a service, **When** a member pauses, resumes, restarts, exposes or unexposes it, **Then**
   the operation takes effect and the page reflects the new state without a full reload, and the
   state shown is the control plane's, not the console's guess.
5. **Given** an exposed service, **When** the member reads its page, **Then** its hostname is a link
   that opens it.
6. **Given** a service with instances, **When** a member asks for its logs, **Then** they can choose
   an instance, the previous container, a number of lines and a window in seconds, as the CLI can.
7. **Given** a service, **When** a member deletes it, **Then** it is gone, and applying a descriptor
   to the same name later creates it again with its generation continuing.
8. **Given** a service whose organization is disabled, **When** a member views it, **Then** it reads
   `Suspended`, and every operation is refused with the reason, not hidden.

---

### User Story 4 - Members, deploy tokens and the platform administrator's operations (Priority: P2)

An owner opens an organization's members: who is in it, with what role, and which invitations are
waiting. They invite someone by email, change a member's role, remove a member, and withdraw an
invitation. They create a deploy token, see its secret exactly once, and revoke one. A platform
administrator disables and enables an organization, sets and clears its quota, and repairs an
organization whose owners have all left by adding one.

**Why this priority**: Owners do these from the CLI today and they are the operations most naturally
done from a page — a members list is a table. They come after stories 2 and 3 only because a console
that cannot show a service yet is not a console.

**Independent Test**: Against a real control plane: as an owner, invite a second user, sign in as
them and see the organization appear; change their role and see the change; create a token, see the
secret once, and use it with the CLI; revoke it and see the CLI refused. As an administrator, disable
the organization and see its services `Suspended`.

**Acceptance Scenarios**:

1. **Given** an owner, **When** they open an organization's members, **Then** they see every member's
   subject, name and email where known, their role, and every pending invitation.
2. **Given** an owner, **When** they invite an email, **Then** the invitation shows as pending, and a
   person who signs in with that verified email finds the organization on their front page.
3. **Given** an owner, **When** they change a member's role or remove them, **Then** it takes effect
   on that member's next request; **When** they try to remove or demote the last owner, **Then** the
   refusal is shown.
4. **Given** a member who is not an owner, **When** they open members, **Then** they see the list and
   none of the controls, and a machine's membership (`token:<id>`) is shown as one.
5. **Given** an owner, **When** they create a deploy token, **Then** the secret is shown once with a
   warning that it will not be shown again, and the listing thereafter shows the token's id, name and
   creation without the secret.
6. **Given** an owner, **When** they revoke a token, **Then** the next request made with it is refused.
7. **Given** a platform administrator, **When** they disable an organization, **Then** it is marked
   disabled, every service in it reads `Suspended`, and enabling it brings back what was running.
8. **Given** a platform administrator, **When** they set or clear an organization's quota, **Then** the
   organization's page shows the quota in force.

---

### User Story 5 - A platform workload, deployed with the platform (Priority: P2)

An operator applies the installation's overlay as they do today and the console comes up with it, in
its own namespace, at `https://console.<base domain>`, with a certificate from the installation's
wildcard. It presents a platform identity to the control plane over mutual TLS and is reached by the
gateway over TLS; its network admits the gateway and nothing else; it holds no credential for the
Kubernetes API, no database and no state of its own, so two instances serve interchangeably and one
can be replaced without anyone noticing. Its image is built, published and cached with the platform's
other images. A developer runs it on their own machine against the compose Keycloak and a control
plane from a checkout, in the clear, with the same realm file.

**Why this priority**: A console that only runs on a laptop is a demo. It is P2 rather than P1 because
the pages can be built and proven against a local control plane first, and this story is what makes
them the installation's.

**Independent Test**: The local deploy script brings the console up on kind and the address it prints
answers, through the gateway, with a sign-in page; the end-to-end cluster suite deploys it into k3s and
drives a sign-in and a project creation through the gateway; from another namespace, a connection to
its port is refused at the network; its ServiceAccount can do nothing.

**Acceptance Scenarios**:

1. **Given** the local overlay applied to a kind cluster, **When** the deploy script finishes,
   **Then** it prints the console's address, and opening it in a browser that trusts the local
   authority shows the sign-in.
2. **Given** a deployed console, **When** it calls the control plane, **Then** the call is over mutual
   TLS, presenting a certificate the installation's service authority issued for the console's own
   platform identity, verified against the control plane's name.
3. **Given** a deployed console, **When** the gateway reaches it, **Then** the connection is TLS with
   the console's certificate verified, and a connection to the console's port from a pod in any other
   namespace is refused at the network.
4. **Given** the console's ServiceAccount, **When** its token is used against the Kubernetes API,
   **Then** every request is refused; the console reaches the control plane and the identity provider
   and nothing else.
5. **Given** a console running as two instances, **When** one is deleted, **Then** every signed-in
   person keeps their session and the deploy reports no failed request.
6. **Given** a release, **When** its images are published, **Then** the console's is among them, at
   the same registry and tag, public, and cached beside the others.
7. **Given** a checkout, **When** a developer starts the compose services, a control plane and the
   console with the documented commands, **Then** they sign in as the development user and see the
   control plane's organizations, over plain HTTP, with no certificate to configure.
8. **Given** the example cloud overlay, **When** an operator sets its placeholders, **Then** the
   console's image, hostname and client secret are among them, and the development secret is deleted
   rather than overridden, as the identity provider's is.

---

### User Story 6 - Fast, and working with scripts off (Priority: P3)

Every page arrives from the server already rendered, so the first paint is the page and not a spinner.
After that, moving between pages does not reload the document: the console fetches what the next page
needs and swaps it in, and a page the person is likely to open next is prepared before they click.
Every creation, change and deletion is an ordinary form the server answers, so a person with scripts
disabled, or on a page whose script failed to load, can still do everything — it is just slower.

**Why this priority**: "Very responsive" is the reason for choosing server rendering over a static
bundle, and progressive enhancement is what stops the choice from being a liability: a form that only
works with the script loaded is a form that fails silently on a bad connection.

**Independent Test**: Measure the time from request to a rendered organizations page on the local kind
cluster and on a browser with scripts disabled; walk stories 2–4 with scripts disabled and find every
operation works.

**Acceptance Scenarios**:

1. **Given** a signed-in person, **When** they request any page, **Then** the response is the rendered
   page, and the browser shows it before any script has run.
2. **Given** a rendered page with scripts running, **When** the person follows a link within the
   console, **Then** the document is not reloaded and the next page shows within the budget in
   *Success Criteria*.
3. **Given** a browser with scripts disabled, **When** the person performs every operation in stories
   2–4, **Then** each succeeds and shows its result.
4. **Given** an operation in flight, **When** the person submits it again, **Then** it is not
   performed twice.

---

### User Story 7 - The console is documented, and the limitation removed (Priority: P3)

A person reads how to sign in to the console and what it can do; an operator reads what the console
needs from the installation — its realm client, its secret, its hostname — and how to add the client
to an installation that predates it; a contributor reads how to run and test it. The limitations page
no longer says there is no console for a deployed installation, and says what the console still does
not show.

**Why this priority**: The documentation's most common reader is a model that retrieved one page, and
a page that says "the CLI is the only client" beside an installation with a console misleads it.

**Independent Test**: `just docs` passes; the limitations page no longer carries the quoted sentence;
the identity page lists the console's client beside the CLI's; the local and cloud installation pages
name the console's address and placeholders.

**Acceptance Scenarios**:

1. **Given** the limitations page, **When** a reader looks under observability, **Then** the sentence
   quoted in *Context* is gone, and what the console does not show is stated.
2. **Given** the identity page, **When** an operator reads about the realm, **Then** the console's
   client is listed with what it is, and how to add it to an installation whose realm was imported
   before this feature.
3. **Given** the installation pages, **When** an operator plans a cluster, **Then** the console's
   hostname, secret and image are among what they must set.
4. **Given** the documentation tree, **When** it is built, **Then** every new page is in the
   navigation and in a skill, and every descriptor block in it is valid.

---

### Edge Cases

- **The cookie has a size limit, and a token pair does not fit in it comfortably.** A browser stores
  about four kilobytes per cookie and refuses a larger one silently; a realm's access token with the
  control plane's claims and a refresh token together approach that. What the cookie carries must be
  small and must never grow with what the identity provider decides to put in a token.
- **An access token is five minutes; a session is thirty idle.** A page requested at minute six must
  work without a visible sign-in; a page requested after the realm's idle timeout must not show an
  error before it sends the person to sign in. Renewal happens on the server and is invisible.
- **Refresh token rotation.** If the realm is configured to revoke a refresh token on use, two
  concurrent requests on two instances race to refresh and one loses its session. The console must
  work with the realm as shipped and must state what it needs from a realm configured otherwise.
- **A platform administrator's identity answer lists no organizations.** The identity route returns
  membership, and an administrator holds none; the listing route is what shows them every
  organization. A console that reads the front page from the identity answer shows an administrator
  nothing.
- **A listing lags a write.** The control plane's listings are projections, refreshed on an interval;
  an organization created a moment ago may not be in the list yet. After a creation the console goes
  to the thing it created, read by id, rather than to a listing that may not show it.
- **What you cannot see does not exist.** An organization the person was just removed from answers
  `404` on the next page. That is a sign-in-sized event for the person, not a crash for the console:
  it shows what happened and goes back to the front page.
- **A refusal is a value.** `403` and `409` carry the control plane's reason; the console shows that
  reason, verbatim, beside the thing refused, and never a generic failure.
- **A deploy token's secret is shown once.** The page that shows it must not be one a browser refresh
  re-requests (a `POST` answered with a redirect loses it; a `POST` answered with a page re-submits on
  refresh). The secret is shown on the response to the creation and on nothing after.
- **The browser's back button after sign-out.** A cached page must not show what the person could
  see; every signed-in response says it may not be stored.
- **A return-to address is attacker-controlled.** Only a path within the console is honoured.
- **The identity provider names external addresses in its discovery document**, and inside the
  cluster the external address of the local installation resolves to loopback. The browser must be
  sent to the external authorization endpoint; the console's own calls to the token endpoint must go
  to the in-cluster address, as the control plane's key fetch does — two addresses, not one.
- **The identity token's issuer must be exactly the realm's external issuer, port included**, the
  same string the control plane derives; a mismatch is a sign-in that silently never completes.
- **The session-sealing secret changes.** Every session is invalid at once; every person is sent to
  sign in and nothing else breaks. Rotating it is a documented way to sign everyone out.
- **A certificate that does not exist yet.** The console's pod starts before cert-manager has issued
  its certificate and must not be `Ready` until it can present one, exactly as every other workload.
- **Logs are large and slow.** A request for logs streams or bounds them; it never buffers a whole
  container's output on the console's server.
- **Two people change one thing.** The control plane's generation is the arbiter; the console shows
  the state it read back, not the state it sent.
- **A descriptor the browser thinks is JSON and the control plane does not.** Validation is the
  control plane's; the console may check that the text parses before sending it, and nothing more,
  so the two can never disagree.

## Requirements *(mandatory)*

### Functional Requirements

**Sign-in and session**

- **FR-001**: The console MUST sign a person in with the OAuth 2.0 authorization code flow against the
  installation's realm, as a confidential client with a client secret and with PKCE, and MUST verify
  the returned identity token's issuer, audience, nonce, signature and expiry before creating a session.
- **FR-002**: The console MUST hold tokens on its server only. No access, refresh or identity token
  may appear in a cookie the browser can read from script, in any response body, in local or session
  storage, or in a URL.
- **FR-003**: The session cookie MUST be marked so that script cannot read it, MUST be sent only over
  HTTPS in a cluster, MUST not be sent on cross-site requests that change state, MUST be integrity
  protected and encrypted with a secret the installation supplies, and MUST be valid on every instance
  of the console.
- **FR-004**: The console MUST keep no session state of its own that a second instance cannot see: a
  session is what the cookie carries plus what the identity provider holds, and an instance may cache
  only what it can re-derive from those.
- **FR-005**: The console MUST renew an expired access token on the server, invisibly, and MUST send a
  person whose session the identity provider no longer honours to sign in, returning them afterwards
  to the page they asked for.
- **FR-006**: Signing out MUST discard the console's session, revoke what the console holds for it at
  the identity provider, and end the identity provider's session for the person.
- **FR-007**: The console MUST refuse a sign-in callback whose state it did not issue, and MUST honour
  a return-to address only when it is a path within the console.
- **FR-008**: The console MUST reach the identity provider's authorization endpoint through the
  browser at the realm's external address and its token, key and logout endpoints from the server at
  an address the installation configures, defaulting to the external one.
- **FR-009**: Every response to a signed-in person MUST say it may not be stored by any cache.
- **FR-010**: Every request that changes state MUST be a `POST` from the console's own origin, and the
  console MUST refuse one whose origin, where the browser sends it, is another site.

**What the console shows and does**

- **FR-011**: The console MUST call the control plane's HTTP API for everything it shows and changes,
  with the signed-in person's access token as the bearer, and MUST NOT hold a credential of its own
  for the control plane, the Kubernetes API or any database.
- **FR-012**: The console MUST show the person their identity as the control plane sees it —
  subject, name, email, whether they are a platform administrator — and, on its front page, the
  organizations they belong to with their role in each, or for an administrator every organization.
- **FR-013**: The console MUST let a person create an organization, and an owner rename and delete
  one, and MUST show the control plane's reason when any is refused, including an installation's
  sign-up address when creation is restricted and one is supplied.
- **FR-014**: The console MUST let a member list, create, rename and delete an organization's
  projects, and set and clear a project's registry credential, never showing a password it has been
  given after the form that took it.
- **FR-015**: The console MUST list a project's services with lifecycle state, ready and desired
  instances, image, generation and hostname, and show a service with every field the API's status
  carries and its history with attribution.
- **FR-016**: The console MUST let a member apply a descriptor by pasted or uploaded text, and MUST
  show every problem the control plane names when it is refused.
- **FR-017**: The console MUST let a member pause, resume, restart, expose, unexpose and delete a
  service, and read its logs with the instance, previous-container, line and window choices the CLI
  offers.
- **FR-018**: The console MUST let an owner list members and pending invitations, invite by email,
  change a role, remove a member and withdraw an invitation, and MUST show a refusal to remove or
  demote the last owner.
- **FR-019**: The console MUST let an owner list, create and revoke deploy tokens, showing a new
  token's secret exactly once, on the response to its creation and never again.
- **FR-020**: The console MUST let a platform administrator disable and enable an organization, set
  and clear its quota, and add an owner to an organization that has none.
- **FR-021**: After creating something, the console MUST take the person to it, read by id, and MUST
  NOT depend on a listing showing it.
- **FR-022**: The console MUST show a `404` for a thing the person was a member of as a loss of
  access, not an error, and MUST show every `403` and `409` with the control plane's reason verbatim.
- **FR-023**: The console MUST show a state as the control plane reported it after an operation,
  never as the console predicted it.

**Rendering**

- **FR-024**: Every page MUST be rendered on the server and be complete before any script runs.
- **FR-025**: With scripts running, navigation within the console MUST NOT reload the document.
- **FR-026**: Every operation MUST be an HTML form the server answers, so that every operation works
  with scripts disabled.
- **FR-027**: A submitted operation MUST NOT be performed twice by a second submission while the
  first is in flight.

**Deployment**

- **FR-028**: The console MUST be a container image, `ankka-console`, built from the repository,
  built and loaded by the local deploy script, published by the release with the platform's other
  images, and named with a placeholder tag in the example cloud overlay.
- **FR-029**: The console MUST be deployed by the same kustomize component set as the control plane,
  in its own namespace, with its own ServiceAccount holding no grant, a Deployment of at least two
  instances, a Service, and an `HTTPRoute` at `console.<base domain>` on the installation's gateway.
- **FR-030**: In a cluster the console MUST serve TLS with a certificate the service authority issues
  for it, MUST be reached by the gateway with that certificate verified, and MUST call the control
  plane over mutual TLS presenting a certificate carrying the platform identity `ankka://platform/console`,
  re-read when cert-manager renews it.
- **FR-031**: The console's network MUST admit the gateway's proxy and nothing else on its serving
  port, and the control plane's network MUST admit the console.
- **FR-032**: The console MUST NOT be `Ready` until it can present its certificate and has bound its
  port, and MUST serve through a rolling replacement without a refused request.
- **FR-033**: The realm file MUST carry the console's confidential client with the authorization code
  flow, PKCE required, the control plane's client scope as a default, and redirect and post-logout
  addresses for the installation's console hostname and for local development; the local overlay
  MUST substitute the base domain into them as it does into every other hostname.
- **FR-034**: The console's client secret and session-sealing secret MUST be Secrets the installation
  supplies: development values in the local overlay, deleted rather than overridden in the example
  cloud overlay.
- **FR-035**: The documentation MUST say how an installation whose realm was imported before this
  feature adds the client, since a realm import creates and never updates.
- **FR-036**: The console MUST run on a developer's machine against the compose Keycloak and a control
  plane from a checkout with the documented commands, over plain HTTP, and the same realm file MUST
  serve both.

**Delivery**

- **FR-037**: The console's source MUST live in this repository, in a directory of its own, with its
  own dependency lock, type check, unit tests and browser tests, runnable with one command each.
- **FR-038**: Continuous integration MUST build, type-check and test the console when its directory or
  the manifests it depends on change, and the release MUST build its image from the tag.
- **FR-039**: The end-to-end cluster suite MUST deploy the console into its cluster and prove a
  sign-in, an organization creation and a service listing through the gateway.
- **FR-040**: The limitations page MUST no longer state that there is no console for a deployed
  installation, and MUST state what the console does not show; the identity, local install and cloud
  install pages MUST describe the console's client, secrets, hostname and image.

### Key Entities

- **Session**: What the console knows about a signed-in browser: a sealed cookie carrying enough to
  obtain a fresh access token from the identity provider and nothing more, and a server-side cache of
  the current access token keyed by that session, discardable at any time. Ends by sign-out, by the
  identity provider's timeout, or by the sealing secret changing.
- **Sign-in attempt**: The state, nonce, code verifier and return-to path issued when a person is sent
  to sign in, held in a short-lived sealed cookie until the callback consumes it.
- **Console identity**: The platform identity `ankka://platform/console`, carried by a certificate the
  service authority issues, presented to the control plane and to the gateway.
- **Console client**: The realm's confidential client for the console, with its redirect and
  post-logout addresses, secret, and the control plane's scope.
- **Page**: A server-rendered view over the control plane's answer to one or more requests made as
  the signed-in person; carries no state of its own beyond what the URL names.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A person with no CLI installed signs in and reaches a rendered organizations page in
  under 30 seconds of their own time on the local kind installation, Keycloak's form included.
- **SC-002**: Every operation the CLI offers on an organization, project, service, member or deploy
  token — every route under those resources in the control plane's API reference except reading the
  identity discovery — is reachable from the console, checked by a test that walks the reference's
  route table against the console's.
- **SC-003**: A test that inspects every cookie, every response body and the browser's storage after a
  sign-in finds zero tokens, and the session cookie is under 2 kilobytes.
- **SC-004**: On the local kind cluster, a signed-in page renders in under 500 milliseconds at the
  median, measured server-side from request to response, and a client-side navigation shows the next
  page in under 300 milliseconds at the median.
- **SC-005**: Every operation in stories 2–4 completes with scripts disabled, checked by the browser
  suite run twice, once with scripts and once without.
- **SC-006**: A rolling replacement of the console's two instances refuses zero requests from a
  signed-in session making one request a second throughout.
- **SC-007**: The end-to-end cluster suite passes with the console deployed, and a connection to the
  console's port from another namespace is refused at the network.
- **SC-008**: `just docs` passes with the limitations sentence removed, and the console's image is
  among those the release publishes and caches.

## Assumptions

- **Server rendering with client-side navigation, on Node.** The console is a TypeScript application
  built with a framework that renders each route on the server, hydrates it in the browser for
  navigation without reloads, expresses every operation as a form the server answers, and runs as a
  plain Node server in a container — the shape React Router's framework mode (the successor of Remix)
  provides. A static bundle alone was rejected because a browser cannot hold a bearer token safely and a
  first paint that waits for scripts is not responsive; a framework that ships its own hosting
  assumptions was rejected because the console must run as an ordinary container behind the platform's
  gateway. The repository's TypeScript SDK already fixes Node 22 as the floor and 24 as the documented
  line; the console follows the documented line and is not a library, so it may require 24.
- **The identity provider's session is the session store.** The console keeps no store of its own: the
  sealed cookie carries the refresh token, an instance holds the current access token in memory for
  its lifetime and re-derives it from the refresh token when it has none. A refresh token the realm
  issues is under a kilobyte, so the sealed cookie fits comfortably; an access token, which grows with
  every claim the realm maps, never enters the cookie. This assumes the realm's refresh tokens are
  reusable until they expire, which is Keycloak's default and how the shipped realm is configured; a
  realm that revokes a refresh token on use needs a shared store, and the documentation says so.
- **A confidential client with PKCE.** A public client with PKCE alone would need no secret to manage,
  and PKCE already defeats a stolen authorization code; the secret is kept because the console is a
  server that can hold one, and a token endpoint that authenticates its client is the stronger of the
  two shapes. The secret's handling is the identity provider's own admin secret's: development values
  in the repository, deleted and supplied out of band anywhere else.
- **The realm is one file, and an import is one-shot.** Adding the console's client to the file serves
  every new installation and the compose Keycloak; an installation whose realm was imported before this
  feature adds the client by hand or by the same administrative command the local deploy script
  already uses for its smoke-test client.
- **Hostname `console.<base domain>`.** One label, so it can never collide with an exposed service's
  `<service>-<project>` hostname, exactly as `api` and `auth` cannot.
- **The control plane accepts the console's certificate as it accepts the gateway's.** Its routes are
  guarded by the bearer token, not by the caller's name; the certificate is what lets the console
  connect at all and what names it in the control plane's traces.
- **No Kubernetes grant.** The console needs nothing from the API server. It reads certificates from a
  mounted Secret and reaches two addresses: the control plane's Service and the identity provider's.
- **The API is enough.** No route is added to the control plane for the console. Where the console
  wants something the API does not offer, that is a control plane change proposed separately, and the
  CLI gets it too.
- **Plain HTML and CSS of the console's own.** No component library or design system is adopted; the
  console's pages are few and its look is its own to decide during planning.
- **The local console is untouched.** `ankka local console` keeps its name, its scope and its page in
  the documentation; the two are described side by side.

## Out of Scope

- Signup, billing and provisioning — the `ankka-cloud` product.
- Managing users in the realm: creating people, resetting passwords, second factors. Keycloak's
  administration console does that, and the platform holds no credential for it.
- Observability of deployed services in the cluster — traces, sessions, entity state — which the
  local console shows on a developer's machine and no feature yet shows in a cluster.
- Calling a deployed service's own endpoints from the console.
- A design system, theming, or a visual refresh of the local console.
- Native or mobile applications; the console is a web page that works at a phone's width.
- Changes to the control plane's API or its authorization rules.
