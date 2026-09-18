# Contract: RBAC and Installation

**Satisfies**: FR-024, FR-027, FR-029, FR-035 to FR-038

## The operator's new permissions

Added to the operator's `ClusterRole` in `kustomization/components/operator/operator.yaml`:

| API group | Resource | Verbs | Note |
|---|---|---|---|
| `postgresql.cnpg.io` | `clusters` | get, list, watch, create, patch | **no `delete`** |
| `postgresql.cnpg.io` | `databases` | get, list, watch, create, patch | **no `delete`** |
| `postgresql.cnpg.io` | `databaseroles` | get, list, watch, create, patch | **no `delete`** |
| `""` | `secrets` | get, create, patch | **no `list`**, **no `delete`** |
| `""` | `configmaps` | get, create, patch | the schema `ConfigMap` |

### The withheld `delete` verb is the point

FR-024 and FR-027 say no platform operation may destroy a database. Withholding the verb makes that
a thing the API server refuses rather than a promise the code keeps — the same technique feature 001
used to make "the operator cannot rewrite desired state" structural by withholding
`nakkaservices: update`. Combined with `databaseReclaimPolicy: retain`, a database survives even a
hand-deleted custom resource.

### `secrets` is a real regression, stated plainly

Feature 001 recorded, in `CLAUDE.md`, that the operator has **no verb on `secrets`** and that this
made "a status detail can never contain a secret value" structural rather than a discipline. This
feature breaks that: the operator must create credential secrets because CNPG does not generate
passwords (research R3).

What is and is not still true:

- **Still structural**: no `list`, so the operator cannot enumerate secrets across the cluster.
- **Now a discipline, not a guarantee**: "cannot read a secret" — it has `get`, which it needs to
  check whether a credential secret already exists before generating a password.
- **Unchanged in practice**: `detail` is built only from CNPG `status.message` values and rendering
  problems; no code path reads secret data into a status.

`CLAUDE.md` must be corrected rather than left overstating the guarantee. An overstated security
property is worse than an accurate weaker one.

## The control plane's permissions — unchanged

It gains nothing. It never touches a CNPG resource: its own database is a static kustomize resource
that exists before any service, and per-service provisioning is entirely the operator's. This is
what keeps the control plane ignorant of CNPG despite running on a CNPG-managed database.

## Installation

A new kustomize component, installed like any other:

```
kustomization/components/cnpg/kustomization.yaml
```

referencing the released manifest:

```yaml
resources:
  - https://raw.githubusercontent.com/cloudnative-pg/cloudnative-pg/release-1.30/releases/cnpg-1.30.0.yaml
```

| Obligation | Why |
|---|---|
| Idempotent — applying twice is a no-op | FR-035 |
| Applied before anything referencing a CNPG kind | the CRDs must exist first, exactly as `nakkaservice.yaml` must |
| Pinned to an exact version, not `latest` | a CRD schema that changes under a running operator is not a surprise anyone wants |
| `deploy-local.sh` waits for `cnpg-controller-manager` | measured ~25s; applying a `Cluster` before its webhook is up fails admission |

**Note on the remote resource**: unlike every other component, this one is fetched over the
network, so `kubectl apply -k` needs connectivity and the version is pinned by URL. An offline or
air-gapped install would need the manifest vendored — out of scope, but the reason the URL carries
an explicit version rather than a branch.

## Absence must be diagnosable (FR-029)

If the CNPG CRDs are not installed, applying a service must report that specific cause — the
operator's attempt to create a `Cluster` fails with a "no matches for kind" error, which must reach
the service's `detail` rather than leaving it in `Waiting` forever. This is the database equivalent
of feature 001's "no operator has reported on this service", and it exists for the same reason: the
most likely misconfiguration should name itself.
