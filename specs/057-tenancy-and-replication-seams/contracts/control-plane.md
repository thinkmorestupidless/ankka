# Contract: the control plane changes

Additive to `docs/reference/control-plane-api.md`. Every field here is optional on the wire so an
older client ignores it and a newer client reads an older control plane.

## Deploy tokens

### `POST /organizations/{id}/tokens`

Owners of the organization, or a platform administrator (recorded as administrative). Body gains:

```json
{ "label": "vendor-agent", "expiresIn": 7776000, "project": "acme-vendor" }
```

- `project` optional. When present it must name a project of this organization, else `404 no such
  project 'acme-vendor'` (what a stranger is told).
- The reply `DeployTokenCreated` gains `"project"`, echoed.

### `GET /organizations/{id}/tokens`

Each `DeployTokenSummary` gains `"project": "<id>"` (absent for an organization-wide token) and
`"administrative": true` when the platform, not an owner, created it.

### What a project-scoped token may do

| Route | Organization-wide token | Project token, its project | Project token, another project |
|---|---|---|---|
| services, topics, secrets, expose, status, history of a project | yes | yes | `404 no such project` |
| `GET /organizations/{id}/projects` | every project | its project only | n/a |
| `POST /projects/{id}/grants`, `DELETE .../grants/{grantId}` | `403` | **yes** (new) | `404 no such project` |
| `GET /projects/{id}/grants`, `.../received` | yes | yes | `404` |
| `accept`, `decline`, `relinquish` a grant | `403` | `403` | `403` |
| members, tokens, organization settings | `403` | `403` | `403` |

A grant a project token makes to another organization is pending, as any such grant is. Every
change is attributed to `token:<id>`.

## A platform administrator for an organization

`POST /organizations/{id}/tokens` and `POST /organizations/{id}/grants/{grantId}/accept` admit a
caller with the `platform-admin` role who is not a member; the act is recorded with
`administrative: true` on the actor. The docs' "owners only" lines for both become "owners, or a
platform administrator, whose act is marked administrative".

### Grant views

`GrantAct` (`made`, `accepted`, `declined`, `withdrawn`, `revoked`, `relinquished` on a grant) and
`GrantChangeRecord` (the received listing) gain `"administrative": false|true`.

## `GET /installation`

Any authenticated caller. The reply gains three optional objects beside `backups`:

```json
{
  "platformVersion": "0.13.0",
  "backups": { "...": "as today" },
  "broker": { "nodes": 3, "defaultCopies": 3, "minInSync": 2 },
  "retention": { "longest": null, "unbounded": true },
  "erasure": null
}
```

- `broker.nodes` absent when the control plane was not told (`ANKKA_BROKER_NODES` unset): "not
  stated". `defaultCopies` and `minInSync` are the topic defaults the installation applies.
- `retention.longest` the bound as a duration (`"30d"`) when the installation sets one;
  `unbounded: true` when it does not, and `longest` absent then.
- `erasure` absent in this release ("not stated"); its shape, reserved: `{ "enabled": true,
  "keyringInstances": 2 }`.

## CLI

- `ankka tokens create --label <l> [--expires-in <d>] [--project <id>]`; the list gains a `PROJECT`
  column (`-` for organization-wide) and marks an administrative creator.
- `ankka installation` prints `broker` and `retention` blocks after `secrets`, each value or `not
  stated`; JSON output is the wire reply.

## Configuration

| Variable | Set by | Default |
|---|---|---|
| `ANKKA_BROKER_NODES` | the kustomize `controlplane` component's Deployment (`1`, the one-node broker the `broker` component ships), overridden by `broker-three-nodes/controlplane-defaults.yaml` (`3`) | unset: not stated |

The one-node value sits in the control plane's own Deployment, not in the `broker` component as
first planned: that component is listed before `controlplane` in an installation's kustomization,
so it cannot patch a Deployment that is not yet there. `broker-three-nodes` patches the Deployment
beside the topic defaults it already sets (accepted 2026-10-22).
