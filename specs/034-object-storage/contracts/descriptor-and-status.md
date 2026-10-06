# Contract: the descriptor, and what a member reads

What a member writes and what the control plane, the CLI and the console say back. Decisions are
in [research.md](../research.md) (R5, R10 to R14, R18).

## The descriptor

```json
{
  "name": "reports",
  "service": {
    "image": "registry.example.com/shop/reports:1.0.0",
    "provisionObjectStorage": true,
    "exposeObjectStorage": true
  }
}
```

| Field | Type | Default | Meaning |
|---|---|---|---|
| `provisionObjectStorage` | boolean | `false` | the platform makes this service a bucket and a storage credential |
| `exposeObjectStorage` | boolean | `false` | the bucket is reachable from the internet, for signed URLs |

Any hosting may set them.

### What a descriptor says about object storage

| `provisionObjectStorage` | gives an `ANKKA_S3_` variable | the service has |
|---|---|---|
| `false` | no | no object storage, and nothing is reported |
| `true` | no | a bucket the platform made |
| `false` | yes | an object store of its own (`supplied`) |
| `true` | yes | refused |

The check is by name: a variable taken from a project secret counts.

### Refusals, at apply

| The descriptor | Message |
|---|---|
| sets `provisionObjectStorage` and gives `ANKKA_S3_X` | `provisionObjectStorage cannot be combined with env var 'ANKKA_S3_X', which supplies an object store of the service's own` |
| sets `exposeObjectStorage` without `provisionObjectStorage` | `exposeObjectStorage needs provisionObjectStorage: only a bucket the platform made can be reached from outside the cluster` |
| asks for a bucket whose name `<project>.<service>` is over 63 characters | `bucket name '<name>' is N characters, over the 63 character limit for a bucket's name; a shorter service name or project id is the only fix` |
| takes a variable from a Secret named `<anything>-storage` | `env var '<name>': secret '<secret>' is issued by the platform and cannot be read by a service` |

The first two and the last are `ServiceSpec.problems`, so the CLI's local check and the control
plane say the same words. The third needs the project and is the control plane's, in
`ServiceEndpoint`'s apply and again in `ServiceProjection.project`. All are a 400,
`invalid descriptor: …`, with every problem named at once.

### Project secrets

`ankka secrets set <name>` refuses a name ending `-storage`, in the words it refuses `-db` and
`-secret-key` with. A project secret that already has such a name is listed and can have entries
removed; it cannot be set again.

## The declaration

```scala
// modules/core/…/core/PlatformVariables.scala
val ObjectStoragePrefix: String = "ANKKA_S3_"
def objectStorage(name: String): Boolean = name.startsWith(ObjectStoragePrefix)
```

It is in no withheld list. `platformOnly`, `runtimeOnly`, `shared` and `withheldFromModule` answer
`false` for every `ANKKA_S3_` name.

## The names (`crd`, `Buckets`)

| | |
|---|---|
| `Buckets.name("shop", "reports")` | `shop.reports` |
| `Buckets.secret("reports")` | `reports-storage` |
| `Buckets.publicEndpoint("example.com", 443)` | `https://storage.example.com` |
| `Buckets.publicEndpoint("127.0.0.1.sslip.io", 8443)` | `https://storage.127.0.0.1.sslip.io:8443` |
| `Buckets.publicAddress("shop", "reports", "example.com", 443)` | `https://storage.example.com/shop.reports` |

## The status a member reads (`ServiceStatus`)

Three optional fields, each omitted when absent.

| Field | Values |
|---|---|
| `objectStorage` | `waiting for object storage`, `provisioned`, `recovered existing bucket`, `supplied`, `object storage provisioning failed` |
| `bucket` | the bucket's name, when the descriptor asks for one |
| `bucketAddress` | the bucket's address on the internet, when the descriptor asks for that too |

`GET /services/{projectId}` and `GET /services/{projectId}/{name}` return the same values for
these three.

When the store is waiting or failed for a reason, `detail` carries it:

| Situation | `objectStorage` | `detail` contains |
|---|---|---|
| the installation has no object store | `object storage provisioning failed` | `object storage: the installation has no object store` |
| the store cannot be reached | `waiting for object storage` | `object storage: ` and the reason |

## `ankka services get`

After `database`, each line only when its field is present:

```text
database        provisioned
object storage  recovered existing bucket
bucket          shop.reports
bucket address  https://storage.example.com/shop.reports
```

`--output json` prints the three fields as the wire has them.

## The console's service page

One fact, `Object storage`, after `Database`:

| The service | Shown |
|---|---|
| has a bucket | the bucket's name; its address beneath when it has one |
| has an object store of its own | `Its own` |
| has neither | `None` |
| is waiting or failed | the phrase |

The text beside the delete control says the bucket is kept, as it says of the database.

## What is recorded

`ServiceObserved.objectStorage` and `Service.objectStorage` hold the phase and default to `None`.
`ServiceApplied` holds the descriptor with its two fields, omitted at `false`. Nothing else is
journaled: no name, no address, no key.
