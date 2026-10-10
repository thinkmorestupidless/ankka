# Install on GKE

> Run ankka on Google Kubernetes Engine with buckets in Google Cloud Storage — Workload Identity, the ankka-gcp cloud provider, the object store settings, and what a service gets, keeps, rotates and moves there.

Source: https://docs.ankka.cloud/platform/install-gke/
A GKE installation is a cloud installation, as [Install on a cloud cluster](install-cloud.md) describes,
whose cloud provider is Google's and whose buckets are made in Google Cloud Storage. Everything there
holds; this page adds what is Google's.

## What runs where

- **The cluster** is GKE with Workload Identity Federation for GKE enabled, so a Kubernetes
  ServiceAccount can act as a Google service account with no key file.
- **The platform's components** are the cloud overlay's, as on any cloud. The operator holds no Google
  credential, no Google client and no Google role, and is bound to no Google identity: everything it needs
  made in Google Cloud it asks the cloud provider for.
- **The cloud provider**, `ankka-gcp`, runs in `ankka-cloud-provider` under a Google service account bound
  to its Kubernetes ServiceAccount. Its roles are owner-equivalent on the installation's Google Cloud
  project; `ankka-gcp`'s README lists them and installs it. It makes each bucket, each service's cloud
  identity and each storage credential, and writes a credential into a Secret it cannot read back.

## Settings

The installation's `ankka-platform` ConfigMap names its cloud, as [The cloud provider](cloud-provider.md)
describes, and its object store:

```yaml title="platform-configmap.yaml"
data:
  cloudProvider: gcp
  cloudAccount: acme-production      # the Google Cloud project
  cloudLocation: europe-west2        # where buckets are made unless a project names its own
  cloudKmsKey: ""                    # a Cloud KMS key to encrypt buckets with, or Google's own encryption
  cloudRotationGrace: 1h             # how long a replaced storage credential goes on working
  objectStoreBackend: gcs            # new buckets are made in Google Cloud Storage
  objectStorePrefix: acme            # starts every bucket's name, which every Google customer shares
  objectStoreSoftDeleteDays: "7"     # 7 to 90 days a deleted object can be recovered
```

| Key | Variable | Meaning |
|---|---|---|
| `objectStoreBackend` | `ANKKA_OBJECT_STORE_BACKEND` | where new buckets are made: `garage` or `gcs` |
| `objectStorePrefix` | `ANKKA_OBJECT_STORE_PREFIX` | the installation's prefix for a bucket's name; required with `gcs` |
| `objectStoreSoftDeleteDays` | `ANKKA_OBJECT_STORE_SOFT_DELETE_DAYS` | how long a new bucket keeps a deleted object, 7 to 90 days |

`gcs` with no cloud provider named, or with no prefix, stops the operator and the control plane at start,
naming the setting. An installation that also runs Garage keeps both: the buckets already there stay there
until a member moves them.

## What a service gets

A descriptor that asks for a bucket is unchanged from Garage, and so are the variables and the client
that reads them. The bucket is named from the prefix, the project, the service and a digest of the two,
and `ankka services get` shows its name and location. A bucket in Google Cloud Storage is reachable from
the internet by anyone holding a valid signature, whether or not its descriptor asks for
`exposeObjectStorage`; exposure decides only the public address the service is told and the CORS rule
that admits a browser's origins. See [Object storage](object-storage.md#on-google-cloud-storage).

## Keeping documents

Every bucket keeps versions, with the installation's soft-delete window, and has no retention policy: the
platform refuses no deletion on account of an object's age. A version can be read back after an overwrite
or a deletion, and deleting every version of an object removes it once the window has passed. Versions
are stored and paid for; `objectStorageVersionAgeDays` on a descriptor deletes a noncurrent version after
that many days. A bucket keeps the window and the wrapping key it was made with until a member runs
`ankka services storage reapply-settings`.

## Issuing a storage credential again

```bash
ankka services storage reissue reports -p shop
```

The cloud provider writes a new key into the service's Secret, the service's instances are replaced onto
it, and the old key goes on working for the rotation grace after the provider reports the new one in
place; after it, Google Cloud Storage refuses it, whatever the rollout did.

## A service with no key

A service written with Google's own client can decline the storage credential
(`objectStorageCredential: false`). Its ServiceAccount is bound to its cloud identity, so the client's
default credentials reach its bucket and no other; no key is issued for it, and it cannot sign URLs with
one.

## Moving from Garage

An installation that kept its buckets in Garage and now keeps new ones in Google Cloud Storage runs both
stores, Garage's settings beside `objectStoreBackend: gcs`, and moves each service when a member asks:

```bash
ankka services storage move reports -p shop --write-pause-bound 10m
```

A mover the operator runs copies the bucket while the service goes on writing, pauses its writes for no
longer than the bound to copy what changed and check every object, and gives the service its new bucket.
`ankka services get` shows the pause, since when and how long it may last. A move that fails, or whose
pause reaches its bound, gives the service its writes back in Garage and may be asked for again. The
bucket in Garage is kept as it was.

## Locations

A bucket is made in the installation's `cloudLocation`, or in its project's when the project names one:

```bash
ankka projects location set europe-west6 -p shop
```

A bucket's location is fixed when it is made: naming another one later applies to buckets made after it.
