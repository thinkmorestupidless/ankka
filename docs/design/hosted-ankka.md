# Design treatment: hosted ankka — signup, subscriptions, and access to an installation

**Status**: treatment, to be fed to `speckit-specify`; the platform-side changes are one small
feature in this repository, the rest is a repository of its own.
**Date**: 2026-09-24
**Platform slice**: feature 011 (`specs/011-hosted-ankka/`) — the creation policy, create for an
owner, the published wire library and the spoke proof. Everything else here belongs to `ankka-cloud`.

## The problem

The platform authenticates its operators and bounds tenancy (`authenticated-control-plane.md`), but
nothing turns a stranger into a customer. Today a person becomes a user of an installation when an
administrator creates them in Keycloak's console, and once admitted they may create organizations
and deploy at no cost. That is the right shape for a company installing ankka for itself. It is not
a product anyone can sign up for.

The ask: a website where a person signs up, pays for a subscription through Stripe, and then has an
organization on a running installation they can deploy into and operate with the CLI. Some clusters
will run the control plane and operator alone; others will also run the website and take signups.

Four questions were put, and this treatment answers each: is it a new repository; how is it
deployed; how are users managed; and is there one paid-for cluster or several, and how are several
managed.

## What this feature is not

- Not a browser console for operating services. The CLI stays the client for projects and services;
  the website's authenticated area is an *account* area: organizations, plan, billing, and where to
  point the CLI.
- Not billing inside the platform. The control plane learns nothing about money, plans or Stripe.
  It gains one installation setting and one small extension to a route, both useful without a
  website, and a self-hosted installation keeps working exactly as today.
- Not usage metering. Subscriptions are flat, per organization, per month. Metering agent tokens
  or instance-hours is a follow-on that needs the platform to report them first.
- Not a user directory of its own. Users, passwords, verification, MFA and social login stay
  Keycloak's, as the identity treatment decided; the website only widens who may register.

## The shape, in one paragraph

A new repository, `ankka-cloud`, holds the hosted product: a website that is itself an ankka
application, deployed beside the control plane as a platform component and not through the
operator. It is an OIDC client of the platform's Keycloak, whose realm has self-registration on, so
Keycloak remains the only place a user is created. After a Stripe Checkout succeeds, the website —
acting as a `platform-admin` machine client of the control plane — creates the customer's
organization on an installation with the customer as its first owner. When the subscription lapses
it disables the organization, and when payment recovers it enables it: the platform's existing
suspension is the enforcement lever, and the only new rule the platform needs is "in this
installation, organizations are created by the platform administrator". One website and one
identity realm serve any number of installations; an organization lives on exactly one of them.

## Decisions, with the alternatives they beat

### D1. A repository of its own: `ankka-cloud`

**Chosen**: a separate repository holding the website, its Kubernetes component, its release and
its own documentation. It depends on the published `ankka-*` artifacts like any application.

The platform and the product have different lifecycles: the platform releases by tag, four times
so far, and each release is a bundle of libraries, images, a template, a plugin and a formula; the
website will change with pricing pages and copy. They have different trust: Stripe keys, price
ids and commercial logic have no place in a public repository whose readers install the platform
for themselves. And they have different readers: everything in `docs/` is for a developer building
on ankka, and none of them needs to know a subscription exists. `ankka-deployments` made the same
cut for the same reasons (its D1, "a repository of its own, split by lifecycle").

There is a second benefit. The template proves the published artifacts work for a *service*; the
website proves they work for a real application with a database, webhooks, timers and a workflow,
built outside this repository against Maven Central. The `dependencyOverrides` trap was found by
exactly that kind of consumer.

**Rejected**: a `website` module in this repository beside `controlplane`. It would share the
release cadence, put Stripe in the platform's build, and be the one module no self-hoster wants.

### D2. The website is an ankka application, deployed as a platform component

**Chosen**: one Scala process on the ankka runtime — entities, a consumer, a workflow, timers and
an HTTP endpoint that serves both the pages and the Stripe webhook — with its own CNPG `Cluster`
in its own namespace, deployed by kustomize the way the control plane is.

The state is event-sourced by nature: Stripe delivers webhooks at least once, each carrying an
event id, and the answer to "what is this customer's standing" is a fold over those events. A
`Subscription` entity keyed by organization id folds them; a Stripe event id seen twice is a no-op
in the fold, which is the idempotency Stripe asks for. Provisioning an organization is a workflow
— create the organization, add the owner, record the outcome — with retries when the installation
is momentarily unreachable and compensation when a step fails permanently. A grace period is a
timer. Every one of those is something the platform already does well, and the website is the
first application outside the platform to need all of them at once.

It is deployed like the control plane and not *through* the platform because it must hold the
apex hostname (`ankka.cloud`, `www.ankka.cloud`) and the platform derives every hosted service's
hostname (there are no custom hostnames), and because the process that suspends organizations
must not itself be a suspendable organization's service.

**Rejected**: a JavaScript frontend with an API behind it. It adds a second runtime, a second image
and a second test harness for an account area of a handful of pages. The pages are server-rendered
by the endpoint; a static marketing site can front it later without changing the shape.

### D3. One identity realm, self-registration on, trusted by every installation

**Chosen**: the realm in the hub cluster becomes the identity provider for the product. It differs
from a self-hosted installation's realm in exactly these ways: `registrationAllowed` on,
`verifyEmail` on (which needs an SMTP provider, a new out-of-band secret), a confidential client
`ankka-web` with the authorization code flow and PKCE for the website's own login, a service
account client `ankka-cloud` holding the `platform-admin` realm role, and GitHub and Google as
brokered identity providers. Every other installation in the product sets `ANKKA_AUTH_ISSUER` and
`ANKKA_AUTH_JWKS_URL` to that realm and runs no Keycloak of its own — both settings exist today and
an explicit issuer already wins over the derived one.

Keycloak stays the only place a user is created, so the website holds no Keycloak credential and
the "the control plane holds no credential able to act on another system" invariant is joined by
"and neither does the website, on Keycloak". Registration, verification, password reset and MFA
are Keycloak's screens, themed. A user registered once can `ankka login` against any installation
in the product, because every installation trusts the same issuer and the same `ankka-cli` client.

The realm file for this shape is canonical in `ankka-cloud`, a full `KeycloakRealmImport`, the way
the platform's own realm is canonical here: one file, applied by kustomize and mounted by
compose. The hub cluster deletes the platform's realm import with a Flux patch and takes this one
instead (D8). Since a realm import is one-shot, changing an installed realm remains a console job.

**Rejected**: the website creating users through Keycloak's admin API. It puts registration in
two places and hands the website a credential that can reset any user's password.

**Rejected**: a Keycloak per installation with identity brokering back to the hub. A user would
exist several times, every membership claim would depend on which copy answered, and the brokering
configuration is more Keycloak than the whole platform carries today.

### D4. The organization is the unit of subscription, and suspension is the enforcement

**Chosen**: one subscription per organization. After payment the website creates the organization
with the customer as first owner; on non-payment it disables the organization; on recovery it
enables it. Nothing else in the platform changes its behaviour.

Disabling an organization already means what a lapsed subscription should mean: every service in
its projects is suspended (scaled to zero, its database kept), and changes are refused until it is
re-enabled, at which point what the members had chosen is restored. That was built for a platform
administrator shutting down a tenant, and it is exactly the lever a billing system needs. Owners
can invite members without the website's involvement, and members' commands are authorized by the
control plane as today — the website never sits in the request path.

Two platform changes make this hold. First, an installation setting — `ANKKA_ORGANIZATION_CREATION`
= `open` (the default, and what every existing installation does) or `platform-admin` — so that in
a hosted installation a registered user cannot `ankka organizations create` past the checkout. The
CLI's refusal names the website. Second, `POST /organizations/{id}` accepts an `owner` subject when
the caller is a platform administrator, so the customer and not the website's service account is
the first owner; without it the website would create, add, and remove itself in three commands,
which is a window in which a retry leaves a mess.

**Rejected**: the control plane learning about plans and entitlement. It is the platform learning
about money; a self-hosted installation would carry billing concepts it has no use for, and the
place where a plan's meaning changes would be the platform's release train.

**Rejected**: a `subscriber` realm role the website grants after payment, checked at organization
creation. Roles are per user; subscriptions are per organization. A person who owns a paid
organization and is a member of a lapsed one is one token with one role set, and the check has
nothing to compare against.

### D5. Stripe hosted surfaces, webhooks in, and no card data anywhere near the website

**Chosen**: Stripe Checkout for the purchase, Stripe's Customer Portal for card changes, plan
changes and cancellation, and webhooks for everything the website needs to know. The website
stores the Stripe customer id, subscription id, price id and subscription status, and nothing a
PCI questionnaire would ask about. One Stripe customer per organization, with the creating user's
verified email as the initial billing email, and no trial: Checkout charges at once, and a
trial is a Stripe setting that can be added later without a change here. The customer id is on the organization's
`Subscription` entity, so ownership can change hands on the platform without touching Stripe.

Status maps to one platform action, folded from `customer.subscription.*` and
`invoice.payment_*` events:

| Stripe status | Organization | Notes |
|---|---|---|
| `trialing`, `active` | enabled | on first `active`, the provisioning workflow runs |
| `past_due` | enabled | Stripe's dunning emails and retry schedule are the warning |
| `unpaid`, `canceled`, `paused` | disabled | services suspended, database kept, refused changes |
| (deleted customer) | disabled | never deleted by the website in v1 |

Nothing is deleted automatically in v1: an organization stays disabled with its data until a
person decides otherwise, because the platform's own rule is that nothing it does destroys a
database, and a billing system should not be the first exception.

Plans: v1 has one priced plan. A free tier, if wanted, is a Stripe price of zero — the model does
not change, and neither does the platform, because whether an organization exists is still the
website's decision. Plan *limits* (projects, services, instances per organization) need a quota
the control plane enforces and is a platform follow-on; until it exists plans can differ in price
and support, not in capacity.

`stripe-java` is the one library the website adds, for signature verification and the Checkout and
Portal session calls. Locally, `stripe listen` forwards test-mode webhooks to the running process,
and `stripe trigger` drives the integration suite.

### D6. Membership stays the control plane's; the website reads it, never copies it

**Chosen**: the website asks the installation's control plane who the members of an organization
are when it needs to decide whether the signed-in user may see or change its billing (owners may;
members see the plan and nothing more). It holds no membership state of its own and no listing of
who belongs where beyond "this subject created this subscription".

Members join as today: an owner invites by email from the CLI, the invitee registers at Keycloak
(the website's sign-up link is Keycloak's registration page) and their next `ankka login` claims
the invitation. The website is not involved and does not need to be. Seat-based pricing would
need the website to count members and is a follow-on; the flat plan is why it is not needed now.

### D7. One product, many installations: hub and spokes

**Chosen**: one website, one Stripe account, one identity realm — the *hub* — and any number of
installations, each a control plane and operator on its own cluster with its own base domain
under the zone (`api.ankka.cloud`, `api.caladan.ankka.cloud`, following `ankka-deployments` D9).
The hub cluster hosts an installation too — arrakis is identity, website and the first
installation, so one cluster is the whole product on day one; a *spoke* hosts only an installation.

The website carries a registry of installations — name, region, control plane URL — as
configuration, not state: adding a spoke is a deploy of the spoke and a line in the hub's config.
An organization lives on exactly one installation, chosen at checkout as a region, because its
entity lives in that control plane's journal and moving a journal between clusters is not a thing
the platform does. The account area shows each organization with its installation's control plane
URL and the `ankka config set url` line for it, which is how the CLI is pointed at the right one.

The website's service account is `platform-admin` on every installation, since they share the
realm and the role is a realm role. That is one credential able to disable any organization in the
product, which is the website's job, but it is also broader than one installation needs; scoping
it per installation (an audience per installation, checked by each control plane) is listed as a
follow-on, and the treatment accepts the breadth for a product with one operator.

**Rejected**: one paid-for cluster only. It is the same design with a registry of length one, and
deciding it now would mean revisiting identity later, which is the expensive part. The registry
costs a configuration list and a region choice at checkout.

**Rejected**: an organization spanning installations, or a global organization catalogue in the
website. Both make the website a second source of truth for tenancy, which is what D4 and D6 avoid.

### D8. Three cluster shapes, one set of components, composed per cluster by Flux

| Shape | Runs | Identity | Organization creation |
|---|---|---|---|
| self-hosted | control plane, operator, Keycloak | its own realm, registration off | open (as today) |
| hub | control plane, operator, Keycloak, **website** | the product realm, registration on | platform-admin |
| spoke | control plane, operator | trusts the hub's realm | platform-admin |

Nothing in this repository knows which shape a cluster has. The components stay as they are; the
website's component lives in `ankka-cloud`; and a cluster's directory in `ankka-deployments`
composes them: the hub's has a second Flux `GitRepository` (`ankka-cloud`, at a pinned tag) and a
second `Kustomization` that `dependsOn` the platform's, plus the patch that swaps the realm import
(D3); a spoke's has the platform alone with the two auth variables set on the control plane
Deployment; a self-hosted installation applies `overlays/<name>` and never sees any of it. "Flux is
the seam, and the cluster pulls" (`ankka-deployments` D3) already describes this; the website is
the first second source that seam carries. Secrets a shape needs — Stripe's API key and webhook
signing secret, the SMTP credential, the `ankka-cloud` client secret — are written by Terraform
from variables in the hub's environment root, the same way that repository's D4 writes what the
overlay deletes.

Locally, `ankka-cloud`'s `just up` targets the kind cluster that `deploy-local.sh` built, applies
its own component with the hub realm, and expects `stripe listen` to be running; nothing in this
repository's local deploy changes.

### D9. What the platform publishes for it

The website is a client of the control plane's HTTP API and needs its wire types and descriptor
rules. `controlplane-api` was written for exactly this — it depends on `core` only so a client
carries no actor system — but is `publish / skip`. It becomes the seventh published artifact,
`ankka-controlplane-api`. The website's HTTP client is its own; the CLI's is a candidate to extract
into that module later, and this treatment does not depend on it.

### D10. Customers' images must be pullable, and today that means public

A customer deploys by naming an image in a descriptor, and an installation pulls it. The operator
renders no `imagePullSecrets`, so in v1 an image must be pullable anonymously: a public repository
on Docker Hub, GitHub's registry or the like. That is acceptable for a first paying customer and
not for many. **A per-project pull credential** — provisioned by the operator like `ANKKA_DB_*`,
declared in the descriptor or on the project — is the first platform feature this product needs
after the two in D4, and it is a feature of this repository, not of `ankka-cloud`. The
documentation for the product says "public images" plainly until then.

## User journeys, in priority order

1. **A stranger becomes a customer.** A person opens the website, registers (Keycloak's page,
   verified email), signs in to the account area, names an organization, picks a region, pays
   through Checkout, and within a minute sees the organization with its control plane URL. They
   install the CLI, set the URL, `ankka login` with the same account, and `ankka services apply`
   works. That is the whole product, end to end.
2. **Payment fails and recovers.** Stripe retries, the subscription goes `unpaid`, the organization
   is disabled and its services stop; the customer fixes the card in the Portal, the subscription
   is `active`, the organization is enabled and its services return as they were.
3. **An owner brings a team.** An owner invites a colleague from the CLI; the colleague registers
   on the website and logs in with the CLI; the invitation is claimed; the colleague deploys.
   The website was never involved.
4. **A second installation.** A spoke is deployed with the two auth variables; it appears in the
   registry; a new customer chooses it at checkout; everything in journey 1 holds against it.
5. **The operator looks at the books.** From the website's own journal: who subscribed, when
   payment failed, when an organization was disabled and why — the same "the journal is the audit
   trail" the control plane makes.

## Functional requirements (for the spec to sharpen)

Platform, in this repository:

- `ANKKA_ORGANIZATION_CREATION` (`open` | `platform-admin`, default `open`) on the control plane;
  when `platform-admin`, `POST /organizations/{id}` from a caller without the realm role answers
  403 with a message the CLI prints, and every other route is unchanged.
- `POST /organizations/{id}` accepts an optional `owner` subject and display fields from a
  platform administrator; the created event records that subject as the first owner and the
  administrator as the actor.
- `ankka-controlplane-api` is published.
- The k3s end-to-end suite gains one case: a control plane started with an explicit issuer and
  JWKS URL naming a realm on another host accepts that realm's tokens (the spoke shape).

Product, in `ankka-cloud`:

- Login through `ankka-web` (authorization code + PKCE); the website keeps a session, not a token
  copy, and never prints or stores a refresh token in a browser-readable place.
- Checkout creates a Stripe customer and subscription; `checkout.session.completed` starts the
  provisioning workflow; the organization exists on the chosen installation with the customer as
  owner before the account page says so.
- Every webhook is verified with the signing secret, folded by Stripe event id, and answered 200
  once folded; an unknown organization or a replayed event is a no-op, not an error.
- The status table in D5 is the whole enable/disable rule; each transition is one control plane
  call, retried with backoff, and recorded with the Stripe event that caused it.
- Billing pages are visible to an organization's owners, read-only to members, and answer 404 to
  anyone else — the same non-leak rule as the control plane's.
- The installation registry is configuration; the region is chosen at checkout and shown with the
  control plane URL afterwards.

## Success criteria

- Journey 1 completes in under ten minutes for a person who has never seen ankka, with no step
  performed by an operator, proven against a real kind hub with Stripe in test mode.
- No card number, CVC or expiry is ever received by the website's process — its HTTP log for a
  full checkout contains no such field, and the Stripe integration is SAQ-A.
- A lapsed subscription suspends every service in the organization within one Stripe retry cycle
  plus one minute, and recovery restores the exact set of services that were running, proven by
  the k3s suite driving the control plane the website drives.
- A self-hosted installation deployed from `overlays/local` after this feature behaves identically
  to before it, proven by the existing suites passing with the default setting.
- The website builds from Maven Central artifacts alone, with no checkout of this repository.

## Testing shape

- **Entity and workflow unit suites**, in `ankka-cloud`, with the testkits: every Stripe status
  transition, event replay and idempotency, the provisioning workflow's retries and compensation
  against a scripted control plane.
- **An `AnkkaTestKit` suite** with an in-process JWKS and a scripted control plane double,
  driving the account pages and the webhook route with `stripe-java`'s own signature helper.
- **One real-platform suite**: the website against the control plane from this repository's
  image in k3s, with `stripe trigger` producing the webhooks, proving the organization is created,
  disabled and enabled for real.
- **In this repository**: the two platform changes tested at the HTTP authorization matrix level,
  and the spoke case in the end-to-end suite.

## Things `speckit-clarify` should be asked to settle

All six were settled with the author on 2026-09-24, before specification:

- ~~The name and hostname of the product repository~~ — `thinkmorestupidless/ankka-cloud`, the
  website at the apex and `www` of `ankka.cloud`.
- ~~Whether the hub cluster hosts an installation of its own~~ — it does: arrakis runs identity,
  the website, and a control plane and operator at `api.ankka.cloud`, so one cluster is the whole
  product on day one and spokes come later.
- ~~Trial length and whether a card is required~~ — card required, no trial. Checkout charges
  immediately; a trial is a Stripe setting that can be turned on later without a change here.
- ~~Whether social login ships in v1~~ — GitHub and Google, brokered by the product realm beside
  email and password. Each needs an OAuth application registered out of band, whose client secret
  is one more Secret the hub's environment root writes.
- ~~How long a disabled organization is kept~~ — indefinitely. The website never deletes; an
  operator deletes by hand when they choose, and the platform keeps the databases regardless.
- ~~Whether the website's `platform-admin` breadth across installations is acceptable~~ — yes for
  v1; per-installation scoping stays a follow-on.

## Follow-ons this deliberately leaves out

- **Pull credentials per project** (D10), the first platform feature the product needs.
- **Quotas per organization** enforced by the control plane, which is what lets plans differ in
  capacity.
- **Usage metering** — agent tokens, instance-hours — reported by the platform and billed through
  Stripe's metered prices.
- **Seat-based pricing**, which needs member counts the website would read from the control plane.
- **Scoping the website's authority per installation**, an audience per installation.
- **A browser console** for services, which the identity treatment also deferred.
- **Moving an organization between installations**, which the platform cannot do for a journal.
