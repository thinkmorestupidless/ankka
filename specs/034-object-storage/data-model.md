# Data Model: Object Storage

What exists, who writes it, and what each state means. Decisions are in
[research.md](research.md); the wire and the rendered objects are in [contracts/](contracts/).

## In the object store

### Bucket

| | |
|---|---|
| Name | `<projectId>.<serviceName>` (`Buckets.name`), at most 63 characters |
| Made by | the operator, when a service that asks is first reconciled |
| Made again | when it is absent on a later pass, whatever removed it |
| Deleted by | never the platform. Its owner, the service, may delete it when it is empty |
| Outlives | the service's resource, the service's deletion, the project |

### Access key

| | |
|---|---|
| Name | the bucket's name |
| Id and secret | made by the store; the secret is returned once, to the operator, and written into one Secret |
| Permission | `read`, `write` and `owner` on its bucket, and on nothing else |
| Count per service | one that a Secret holds. A second exists only between an interrupted pass and the next |
| Deleted | by the operator, only when its secret is in no Secret (R6) |

## In the cluster

### Storage credential — Secret `<serviceName>-storage`

| | |
|---|---|
| Namespace | the project's |
| Type | `Opaque` |
| Entries | `ANKKA_S3_ACCESS_KEY`, `ANKKA_S3_SECRET_KEY` |
| Labels | the service's identity labels |
| Owner reference | none: it outlives the service's resource |
| Written | once, by `create`. Patched only when the store knows no key for the service |
| Read by | the kubelet, for the developer's container. Never the operator, never the control plane |

### `AnkkaService` — two fields on `spec`

| Field | Type | Default | Set by |
|---|---|---|---|
| `provisionObjectStorage` | boolean | `false` | the control plane, from the descriptor |
| `exposeObjectStorage` | boolean | `false` | the control plane, from the descriptor |

### `AnkkaService` — `status.objectStorage`

Absent when the service neither asks for a bucket nor gives an `ANKKA_S3_` variable.

| Field | Type | Meaning |
|---|---|---|
| `phase` | `Waiting`, `Provisioned`, `Recovered`, `Supplied`, `Failed` | below |
| `bucket` | string | the bucket's name; empty when `Supplied` |
| `publicAddress` | string, optional | `https://storage.<base>[:port]/<bucket>`, when the bucket is reachable from the internet |
| `recovered` | boolean | the bucket is older than this incarnation of the resource |
| `detail` | string, optional | why it is waiting or failed |

### Phases

```text
                         descriptor gives ANKKA_S3_*
   (absent) ──────────────────────────────────────────────▶ Supplied
      │
      │ descriptor asks
      ▼
   Failed  ◀── no object store in the installation, or the name is over the limit
      │
   Waiting ◀── the store cannot be reached (detail says why), or the bucket or its key is not there yet
      │
      ├──▶ Provisioned   the bucket was made for this incarnation
      └──▶ Recovered     the bucket was there before it
```

`Waiting` returns from `Provisioned` or `Recovered` when the store stops answering; the service's
instances are untouched by that.

### Bucket route — `HTTPRoute <serviceName>-storage`

Exists exactly while the descriptor asks for a bucket reachable from the internet.

| | |
|---|---|
| Namespace | the project's |
| Owner reference | the service's resource: deleting the service removes it |
| Hostname | `storage.<base>` |
| Match | `PathPrefix /<bucket>` |
| Backend | the store's Service, in the store's namespace |

### Reference grant — `ReferenceGrant <project namespace>`

| | |
|---|---|
| Namespace | the store's |
| Made | when a project first has a bucket reachable from the internet |
| Removed | never |
| Permits | `HTTPRoute`s of that one namespace to name the store's Service |

## In the control plane

### The descriptor (`ServiceSpec`)

| Field | Type | Default |
|---|---|---|
| `provisionObjectStorage` | boolean | `false` |
| `exposeObjectStorage` | boolean | `false` |

Journaled inside `ServiceApplied`, as the whole descriptor is. A field at its default is omitted,
so an earlier journal decodes unchanged.

### What is journaled about the store

| Record | Field | Value |
|---|---|---|
| `ServiceObserved` | `objectStorage: Option[String]`, default `None` | the phase |
| `Service` (snapshot) | `objectStorage: Option[String]`, default `None` | the phase |
| the listing's row | `objectStorage`, `bucket`, `bucketAddress` | the phrase and the two derived values |

No bucket name, address, key id or secret is journaled: the name and the address are derived when
a status is built, and the control plane never holds a key.

### What a member reads (`ServiceStatus`)

| Field | When present | Value |
|---|---|---|
| `objectStorage` | the operator reported a phase | `waiting for object storage`, `provisioned`, `recovered existing bucket`, `supplied`, `object storage provisioning failed` |
| `bucket` | the descriptor asks for a bucket | `<projectId>.<serviceName>` |
| `bucketAddress` | the descriptor also asks that it be reachable, and the installation has a base domain | `https://storage.<base>[:port]/<bucket>` |

A waiting or failed store's reason is in `detail`, prefixed `object storage:`.

## Rules, in one place

| Rule | Held by |
|---|---|
| a bucket's name is derived, never chosen | `Buckets` in `crd`; the resource carries two booleans |
| a name over 63 characters is refused at apply | `ServiceEndpoint`, `ServiceProjection`, and `ObjectStorage.decide` |
| asking and supplying cannot be combined | `ServiceSpec.problems` |
| only a bucket the platform made can be reachable | `ServiceSpec.problems` |
| no descriptor takes a variable from a `-storage` Secret; no project secret takes the name | `ServiceSpec.isPlatformSecret`, `ProjectSecrets.nameProblems`, held to the operator's names by `ReservedSecretNamesSuite` |
| a credential is written once | `StorageCredential.ensure` |
| a service that does not ask has no object changed | `RenderingUnchangedSuite`, repinned to gain one removal line and nothing else |
| a spec or status field is in the schema | `CrdSchemaSuite`, top level and inside `objectStorage` |
