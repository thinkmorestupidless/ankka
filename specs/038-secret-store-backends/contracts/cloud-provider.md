# Contract: what this feature asks of the cloud provider (spec 044)

044 is not implemented. This file states exactly what 038's Group B renders and reads, in 044's
vocabulary as amended by this plan (044 FR-005, FR-010, FR-014). When 044's plan is written, this is
its input from 038.

## Requests the operator writes (owned by the `AnkkaService` / `AnkkaProject`)

### `secret-access` — one per service, when the backend is `secret-manager`

```yaml
spec:
  provider: gcp
  kind: secret-access
  subject: { project: spinvibe, service: payments }
  parameters:
    principal: <output of the service's identity request>
    own:  ["s_spinvibe_payments_"]     # prefixes: create unconditioned; add/access/list/destroy/get/delete under the prefix
    read: ["p_spinvibe_"]              # prefixes: access under the prefix
```

The provider conditions on `resource.name.startsWith("projects/<number>/secrets/<prefix>")`, with the
project **number**. It grants no `secrets.list`. Status `Ready` for the spec's generation is what the
operator waits for before `ApplyDeployment`; `Waiting` past the acknowledgement bound is reported as
044 FR-004 says; `Failed` is folded into `status.secretStore` with the provider's detail.

### `secret-sync` — one per project, when the backend is `secret-manager`

```yaml
spec:
  provider: gcp
  kind: secret-sync
  subject: { project: spinvibe }
  parameters:
    secretName: checkout                 # the project secret = the Kubernetes Secret's name
    entries: { STRIPE_KEY: p_spinvibe_checkout_STRIPE_KEY, WEBHOOK_KEY: p_spinvibe_checkout_WEBHOOK_KEY }
    generation: 7                        # AnkkaProject.spec.secretsGeneration
```

One request per project secret (named `<project>-sync-<secret>`). The provider keeps the Kubernetes
Secret's entries equal to each id's latest enabled version within one minute, reports
`syncedGeneration` in its status, and — the seeding clause — copies an entry the Kubernetes Secret
holds and the account has no version of **up**, as that id's first version, before syncing anything
down. The operator holds a service's rollout while any request of its project reports a
`syncedGeneration` behind the project's `secretsGeneration`, for the entries the service's
descriptor takes variables from.

### The control plane's own access

The control plane's manifest (`components/controlplane`) carries one `secret-access` for
`ankka://platform/controlplane`'s ServiceAccount: `own: []`, `read: []`, and a third list this
feature adds to the kind, `write: ["p_"]` — create unconditioned, add/list/disable under `p_`,
never access. (If 044 prefers, `write` can be modelled as `own` without access; the plan for 044
decides, and this file is the request.)

## What the operator reads back

| From | Into | Shown as |
|---|---|---|
| `secret-access` status phase/detail | `AnkkaServiceStatus.secretStore.{phase, detail}` | `services get` → `secretStore: Waiting — waiting on secret access` |
| `secret-sync` status `syncedGeneration` | the hold; `status.secretStore.detail` naming the entry | `waits on the entry STRIPE_KEY of checkout being synced` |
| a provider status for Data Access logging | installation status (044 FR-019's page) | `audit log: on/off/unknown` |

## What 038 does not ask

No request for the service's Workload Identity annotation (none is needed in the direct form); no
request to destroy or delete a secret (the service does both itself under its own grant); no request
for a KMS key (044's `ANKKA_CLOUD_KMS_KEY` is passed on create by the store).
