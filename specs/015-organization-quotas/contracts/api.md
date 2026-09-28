# Contract: control plane routes

## `PUT /organizations/{organizationId}/quota`

Platform administrators only (`403` otherwise; `404` for an organization that does not exist).
Allowed on a disabled organization. Body:

```json
{ "projects": 2, "services": 3, "instances": 4 }
```

Each limit is optional; one left out or `null` is unlimited; `0` is a limit. Refused `400` when a
limit is negative or when no limit is named. Replaces the whole quota. Also brings the
organization's usage up to date with what exists, for an organization created before quotas
existed. Answers `204`.

## `DELETE /organizations/{organizationId}/quota`

Platform administrators only. Clears the quota; the organization is unlimited again. Answers
`204`, also when there was no quota.

## Changed: `GET /organizations` and `GET /organizations/{organizationId}`

Every summary carries the quota, when one is set, and the usage:

```json
{
  "id": "acme", "name": "Acme Corp", "projects": 2, "disabled": false, "role": "owner",
  "quota": { "projects": 2, "services": 3, "instances": 4 },
  "usage": { "projects": 2, "services": 1, "instances": 2 }
}
```

`quota` is absent when none is set. `usage` is absent when nothing is held (every count zero) and
otherwise carries all three numbers. `projects` (top level) is the
listing's count; `usage.projects` is the organization's own record — the two differ only for an
organization created before quotas existed and not yet given one.

## Changed refusals

| Route | When | Status | Message |
|---|---|---|---|
| `POST /projects/{projectId}` | project count at quota | `409` | `organization 'acme' has reached its quota of 2 projects (2 in use)` |
| `PUT /services/{projectId}/{name}` | new service, service count at quota | `409` | `organization 'acme' has reached its quota of 3 services (3 in use)` |
| `PUT /services/{projectId}/{name}` | instances would exceed | `409` | `applying 'checkout/cart' with 4 instances would take organization 'acme' to 6 instances, over its quota of 4 (3 in use)` |

A refused create creates nothing; a refused apply changes nothing (a new service does not come to
exist; an existing one keeps its descriptor and generation). A re-apply at the same or a lower
instance count is never refused for quota.

## Wire library (`ankka-controlplane-api`)

`Quota`, `Quota.problems`, `Usage`; `OrganizationDetail.quota/usage`,
`OrganizationSummary.quota/usage`; `Wire.quotaCodec`. Additive; every existing shape decodes.
