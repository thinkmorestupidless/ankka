---
name: ankka-platform
description: Install, configure and operate the ankka platform itself — a local kind installation with deploy-local.sh, a production kustomize overlay on a real cluster (load balancer, DNS-01 wildcard certificate, base domain, registry images), the control plane and operator, Keycloak identity (users, platform administrators, machine accounts, the issuer), organizations, projects, members and roles, per-service CloudNativePG databases, and the gateway, routes, ports and TLS. Use when the task is about running or administering the platform rather than a service on it — kustomize overlays, Keycloak, CNPG, Envoy Gateway, cert-manager, organizations, invitations, or the control plane's HTTP API.
---

# The ankka platform

The platform is a control plane (an ankka application holding organizations, projects and services as
event sourced entities), an in-cluster operator that reconciles each service's `AnkkaService` resource
into a namespace, Deployment and database, a Keycloak realm for identity, CloudNativePG for databases, and
an Envoy Gateway with one wildcard certificate for routes. It is installed with kustomize: components
shared by every installation and one overlay per cluster carrying only what differs.

## Rules

1. **Install with the overlay, not by hand.** Locally: `kind create cluster --name ankka --config
   kustomization/kind.yaml` then `./kustomization/deploy-local.sh`, which installs CloudNativePG,
   cert-manager and Envoy Gateway (server-side apply; the CRDs are too large for client-side), builds
   and loads the images, applies the overlay, restarts the operator and control plane so a re-run takes
   effect, and exports the local CA. The script refuses any context that is not the `kind-*` cluster it
   targets. A production overlay is applied by hand after the three CRD-bearing controllers, in order.
   `kubectl apply -k` must be the whole deploy: anything a script generates is something a second
   deployer (Flux, a colleague) will not have.
2. **A production overlay changes exactly four things.** A `LoadBalancer` instead of kind's node ports; an
   ACME issuer over DNS-01 (a wildcard cannot be had from HTTP-01) instead of the local self-signed root;
   the real base domain on 443; and Keycloak's development admin secret *deleted* so the identity
   provider refuses to start until a real Secret exists out of band. Images need `DOCKER_REPOSITORY` and
   an `images:` block; a wrong registry fails minutes later as `ImagePullBackOff`, an absent one at once.
3. **Keycloak authenticates; the control plane authorizes.** Every route but discovery and health is
   behind a token verified offline against the realm's keys. Only the token's `sub` is ever a key; email
   and name are display. Membership is claimed on first verified login from an invitation by email, so
   the control plane holds no Keycloak admin credential. The realm import is one-shot: it creates a
   missing realm and never updates one, so a change to the realm on an existing installation is a console
   job. A user needs a first and last name or a password grant fails with "Account is not fully set up".
4. **The issuer must equal what Keycloak writes into `iss`, port included.** In a cluster it is derived
   from `ANKKA_BASE_DOMAIN` and `ANKKA_HTTPS_PORT` (`https://auth.<base>[:port]/realms/ankka`); Keycloak
   learns the port only from `X-Forwarded-Port`, which the identity provider's route sets. Locally
   `ANKKA_AUTH_ISSUER` names the compose Keycloak. A mismatch is a 401 on every request.
5. **Tenancy ids are tombstoned; service names are not.** A deleted organization or project keeps its id
   forever (`exists` false, `known` true); a service name is a deployment target and may be reused.
   Cross-entity checks live in the endpoint, never in an entity handler. Every command carries an
   attribution, and every change is recorded with who made it.
6. **One database per service, in the project's namespace.** The operator creates a CNPG `Database` and
   `DatabaseRole` per service and generates the password itself (CNPG does not) only when the credential
   secret is absent, so a reconcile never rotates a password under a running service. A `Database` can
   briefly fail with `role does not exist` and a secret can briefly be `forbidden` while CNPG catches up:
   both are in-progress, not failures. Cross-namespace references get no status at all, which is why
   capacity is per project. The platform never destroys data on deletion.
7. **One gateway, one wildcard, one label.** `*.<base>` covers `cart-checkout.<base>` and not
   `cart.checkout.<base>`, so an exposed hostname is `<service>-<project>` and the control plane answers at
   `api.<base>`. A cert-manager `ClusterIssuer` resolves its secret in cert-manager's namespace, so the
   local CA uses a namespaced `Issuer` beside its secret, while the DNS-01 webhook needs the opposite. A
   route can be `Accepted` and still not serve (`RefNotPermitted`, `NotAllowedByListeners`); the
   service's status folds both into `route rejected: <reason>`. A redirect route names its port or the
   `Location` keeps the request's.
8. **The operator is not an ankka application and cannot reach the control plane.** It depends only on
   the CRD and a Kubernetes client; the resource is the only thing either side knows about the other.
   Owner references give cascade deletion; watches give sub-second change notification; the control
   plane holds no credential able to create a workload. Server-side apply needs `patch`, not `create`,
   in RBAC, and never re-applies an object read back with `managedFields`.
9. **Readiness is cluster membership plus every extension's opinion.** A pod is `Ready` only once it has
   joined the service's cluster and bound its HTTP port; the probe is on the management port by its
   *name*. Rolling updates surge one pod that joins the existing cluster before an old one stops, and a
   `preStop` sleep keeps the old pod serving while endpoints propagate. Never `Recreate`.
10. **Platform administrators are a realm role, not a member.** They may operate any organization; the
    CLI's tenancy administration (create, invite, disable, delete) stays on the command line and is
    absent from `ankka mcp` on purpose.

## Operating checklist

- After any change to a manifest or image locally, re-run `deploy-local.sh`; it restarts the pods
  because a `:latest` tag applies as no change.
- Read a deployed pod's `env` before blaming its image; read the operator's log before blaming the
  control plane; read `services history` before blaming either.
- A scaled Deployment comes back within one resync; pausing is `services pause`.
- Two services sharing a database delete each other's timers. Never do it.

## Mistakes to check for

- Applying a production overlay through `deploy-local.sh`, or a local script step that the overlay lacks.
- A `ClusterIssuer` for the local CA, or a namespaced `Issuer` for the DNS-01 webhook.
- A realm change made by editing the import and re-applying it.
- An operator that talks to the control plane, or a control plane that creates a Deployment.
- A `HorizontalPodAutoscaler`, or a `spec.selector` carrying ankka's generation.

## Reference files

Open the one a task needs; each is one topic and stands alone.

### Concepts

- `references/concepts/control-plane.md` — How the control plane records what you asked for, how the operator makes the cluster match it, and how the status you read is kept honest with generations and confirmation.
- `references/concepts/tenancy-and-access.md` — How organizations, projects and services divide an installation, who may operate each of them, and how the identity provider and the control plane share the work of authentication and authorization.

### Run the platform

- `references/platform/install-local.md` — Run the whole ankka platform on your own machine in a kind cluster — operator, control plane, identity provider, databases, gateway and TLS — with one script, and point the CLI at it.
- `references/platform/install-cloud.md` — Install ankka on a real Kubernetes cluster with a production kustomize overlay — a load balancer, a public wildcard certificate over DNS-01, a real base domain, images from a registry and an identity provider with no default credentials.
- `references/platform/organizations.md` — Create organizations and projects, invite members by email, manage roles, and disable or delete an organization, with the rules the control plane enforces on each.
- `references/platform/identity.md` — How people and machines authenticate to the ankka control plane through the installation's Keycloak — logging in with the CLI, adding users, platform administrators, machine accounts and the realm.
- `references/platform/databases.md` — How the platform provisions a Postgres database for every service with CloudNativePG, why each service must have its own, how data survives deletion, and how to bring your own database instead.
- `references/platform/networking.md` — How traffic reaches ankka services — the installation's single gateway and wildcard certificate, per-service routes, in-cluster addresses, the ports an instance uses, readiness, and what is not isolated.

### Reference

- `references/reference/cli.md` — Every `ankka` command and option, how the CLI resolves its settings and credentials, its output formats and its exit codes.
- `references/reference/lifecycle-states.md` — What each of a deployed service's eight lifecycle states means, what usually causes it, what to do about it, and what an unconfirmed status is.
- `references/reference/control-plane-api.md` — Every route the control plane serves, with its parameters, request body, response and who may call it, plus how requests are authenticated and how errors are reported.
- `references/reference/error-codes.md` — The eight error codes a component can refuse with, the HTTP status each becomes, how a refusal travels from a handler to a caller, and how it differs from a failure.
