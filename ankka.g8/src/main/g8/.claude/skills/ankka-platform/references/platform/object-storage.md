# Object storage

> How the platform gives a service a bucket of its own, the variables any S3 client needs to reach it, why buckets are never deleted, how a bucket is made reachable for signed URLs, and how to bring your own store.

Source: https://docs.ankka.cloud/platform/object-storage/
A deployed service can ask the platform for a bucket. The platform makes the service one bucket in the
installation's object store and one storage credential that reaches that bucket and no other, and gives
the service's program the variables that say where the bucket is and how to sign for it. The service keeps
and reads its objects with whatever S3 client its language has: the platform offers no storage API of its
own, and the SDKs wrap nothing.

```json title="service.json"
{
  "name": "reports",
  "service": {
    "image": "registry.example.com/acme/reports:1.0.0",
    "provisionObjectStorage": true
  }
}
```

A service that does not ask is given nothing: no bucket, no credential and no variable.

## What is provisioned

The installation's object store is [Garage](https://garagehq.deuxfleurs.fr/), an S3-compatible store,
running in the namespace `garage-system`. Per service that asks:

- **A bucket**, named `<project>.<service>`. A project id and a service name cannot contain a dot, so no two
  services share a name, and a bucket's name is at most 63 characters: a descriptor whose project and
  service name would make a longer one is refused when it is applied, naming the limit.
- **An access key**, allowed to read, write and own that bucket and nothing else. It is refused by every
  other bucket, in the same project or another.
- **A storage credential Secret**, `<service>-storage`, in the project's namespace, holding the key.

The service's program is given five variables:

| Variable | Value |
|---|---|
| `ANKKA_S3_ENDPOINT` | the store's S3 address inside the cluster, `http://garage.garage-system.svc.cluster.local:3900` |
| `ANKKA_S3_REGION` | the region to sign for, `garage` |
| `ANKKA_S3_BUCKET` | the bucket's name |
| `ANKKA_S3_ACCESS_KEY` | the access key's id, from the storage credential Secret |
| `ANKKA_S3_SECRET_KEY` | its secret, from the storage credential Secret |

Which program receives them depends on the service's hosting. An embedded service's one container has
them. A process-hosted or web-hosted service's own program has them and the platform's program beside it —
the sidecar or the proxy — has none, because nothing of the platform's opens a bucket. A module asks its
`config` import for them and is told each one.

A service with a bucket does not start until its credential exists: an instance is created only once the
Secret it reads is there.

## Configuring an S3 client

Any S3 client works, configured three ways:

- **Path-style addressing.** The store has one address and the bucket is in the path,
  `<endpoint>/<bucket>/<object>`. A client that puts the bucket in the hostname reaches nothing.
- **The region the platform gives**, `ANKKA_S3_REGION`. A signature is made for a region, and the store
  refuses one made for any other; most clients default to `us-east-1`, and are refused with
  `AuthorizationHeaderMalformed`.
- **Checksums only when an operation needs one.** Recent releases of the AWS SDKs send a trailing
  checksum with every upload by default, which the store refuses as an invalid payload signature. Set the
  client to calculate request checksums, and validate response checksums, only when required.

With the AWS SDK for Java:

```scala
private def s3(key: IssuedKey, region: String = "garage"): S3Client =
  S3Client
    .builder()
    .endpointOverride(s3Endpoint)
    .region(Region.of(region))
    .forcePathStyle(true)
    // The SDK's default since 2.30 sends a trailing checksum in an aws-chunked body, which the
    // store refuses as an invalid payload signature. A checksum only where an operation needs one
    // is what every S3-compatible store accepts.
    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
    .credentialsProvider(
      StaticCredentialsProvider.create(
        AwsBasicCredentials.create(key.accessKeyId, key.secretAccessKey)
      )
    )
    .build()
```

In a service, `s3Endpoint` is `ANKKA_S3_ENDPOINT`, the region is `ANKKA_S3_REGION`, and the key is
`ANKKA_S3_ACCESS_KEY` and `ANKKA_S3_SECRET_KEY`. From a shell, with curl 8 or later, `curl --aws-sigv4
"aws:amz:\$ANKKA_S3_REGION:s3" --user "\$ANKKA_S3_ACCESS_KEY:\$ANKKA_S3_SECRET_KEY"
"\$ANKKA_S3_ENDPOINT/\$ANKKA_S3_BUCKET/<object>"` reads an object. An older curl signs without sending the request's
payload hash, which the store requires: add it as the `x-amz-content-sha256` header.

## Buckets and objects are never deleted by the platform

Deleting a service leaves its bucket, its objects and its storage credential exactly as they were. A
descriptor applied again under the same name, in the same project, is given the same bucket and the same
credential, and `services get` reports `recovered existing bucket`. The platform has no operation that
deletes a bucket or an object.

The service itself owns its bucket, so it can delete objects, and can delete the bucket once it is empty.
A bucket deleted that way is made again, empty, on the platform's next pass, and the service's credential
reaches it.

## A credential is made once, and replaced only when a member asks

The credential is issued once, when the bucket is first made. Applying the descriptor again, restarting the
service or upgrading the platform leaves it as it is. The platform's operator writes the Secret and cannot
read it back; the control plane never holds it. The operator does hold the object store's administrator
token, as it administers each project's database, and whoever holds that token can reach every bucket.

A member can have the credential issued again — for a credential that has leaked, or a rotation policy:

```bash
ankka services storage reissue reports -p shop
```

A new credential is written where the service's instances read it, and the instances are replaced by a
rolling update once it is there. The old credential goes on working for the rotation grace, an hour as
shipped, so an instance not yet replaced keeps working; after it, the store refuses it. The history
records `storage-credential-reissued`.

A credential rotated by hand in the store is not seen by the platform: the Secret keeps the old one, and a
service using it is refused. If the store loses a key, the operator issues a new one into the same Secret
the next time it starts, and a running service reads it when it is restarted.

## A bucket reachable from a browser

A bucket is reached only from inside the installation until its descriptor asks otherwise:

```json title="service.json"
{
  "name": "reports",
  "service": {
    "image": "registry.example.com/acme/reports:1.0.0",
    "provisionObjectStorage": true,
    "exposeObjectStorage": true,
    "objectStorageOrigins": ["https://app.example.com"]
  }
}
```

The bucket is then reachable at the store's one hostname, `storage.<base domain>`, with the bucket in the
path: `https://storage.<base domain>/<project>.<service>`. The service's program is given a sixth
variable, `ANKKA_S3_PUBLIC_ENDPOINT`, the store's address on the internet, and `services get` shows the
bucket's address.

A service gives a browser a **signed URL**: an address for one object, made with the service's credential,
that lets whoever holds it read or upload that object until it expires, without the credential.

- **Sign against `ANKKA_S3_PUBLIC_ENDPOINT`**, never `ANKKA_S3_ENDPOINT`. A signature covers the address it
  was made for, so a URL signed for the address inside the cluster is refused from a browser. Read and
  write the service's own objects through `ANKKA_S3_ENDPOINT`.
- **Nothing is read without a signature.** A request with none is refused by the store.
- **An upload from a page needs its origin named.** A browser sending a file from a page to the store's
  hostname asks first, and is refused unless the bucket admits the page's origin. The descriptor names the
  origins in `objectStorageOrigins` — each `https://host` or `https://host:port`, or `*` — and the platform
  sets the bucket's CORS rule from them; the service sets none. Name the page's origin, which is in general
  not the service's own hostname: a page served by another service, or by a site of its own, uploads from
  there. No origins is no rule, and a bucket that is not reachable has none whatever the list says.
- **Turning it off revokes every URL.** Applying the descriptor without `exposeObjectStorage` removes the
  bucket's route, and a URL signed before stops working, whatever its expiry. Only the bucket that asked is
  reachable: every other bucket's path answers nothing at the store's hostname.

Only a bucket the platform made can be made reachable: `exposeObjectStorage` without
`provisionObjectStorage` is refused.

## Bring your own object store

A descriptor that declares any `ANKKA_S3_` variable in its `env` has an object store of its own, and the
platform makes it no bucket and no credential. `services get` reports `supplied`. The variables reach the
service's program as declared, by the same rule as any other variable:

```json title="service.json"
{
  "name": "exports",
  "service": {
    "image": "registry.example.com/acme/exports:2.0.0",
    "env": [
      { "name": "ANKKA_S3_ENDPOINT", "value": "https://s3.eu-west-2.amazonaws.com" },
      { "name": "ANKKA_S3_REGION", "value": "eu-west-2" },
      { "name": "ANKKA_S3_BUCKET", "value": "acme-exports" },
      { "name": "ANKKA_S3_ACCESS_KEY", "secretKeyRef": { "name": "exports-store", "key": "id" } },
      { "name": "ANKKA_S3_SECRET_KEY", "secretKeyRef": { "name": "exports-store", "key": "secret" } }
    ]
  }
}
```

The check is by name, so a value taken from a project secret counts. A descriptor that both asks for a
bucket and declares an `ANKKA_S3_` variable is refused, naming the variable.

No descriptor may take a variable from a Secret named `<service>-storage` or `<service>-cloud-storage`, its
own service's included, and no project secret may be given such a name: a storage credential reaches its
own service and no other.

## On Google Cloud Storage

An installation may keep its buckets in Google Cloud Storage instead of Garage, made in the installation's
cloud account by its [cloud provider](cloud-provider.md); [Installing on GKE](install-gke.md) sets one up.
The descriptor is the same on either store, and so are the variables and the client: a service written
against Garage runs unchanged.

```json title="service.json"
{
  "name": "reports",
  "service": {
    "image": "registry.example.com/acme/reports:1.0.0",
    "provisionObjectStorage": true
  }
}
```

**Which store a bucket is in.** The installation names the store new buckets are made in
(`ANKKA_OBJECT_STORE_BACKEND`, `garage` or `gcs`). A bucket stays in the store it was made in: an
installation that turns to Google Cloud Storage keeps every bucket it already has in Garage until a member
moves it, and `ankka services get` says which store a service's bucket is in, its name and its location.

**The variables.** `ANKKA_S3_ENDPOINT`, `ANKKA_S3_REGION` and `ANKKA_S3_BUCKET` are what the cloud provider
answered for the bucket: Google Cloud Storage's XML API, the region `auto`, and the bucket's name. The
storage credential is in `<service>-cloud-storage`, written once by the provider; it is an HMAC key of the
service's own cloud identity, granted on its bucket and on nothing else. Configure the client as for
Garage:

```scala
private lazy val s3: S3Client =
  S3Client
    .builder()
    .endpointOverride(URI.create(endpoint)) // ANKKA_S3_ENDPOINT
    .region(Region.of(region))              // ANKKA_S3_REGION
    .forcePathStyle(true)
    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
    .credentialsProvider(credentials) // ANKKA_S3_ACCESS_KEY, ANKKA_S3_SECRET_KEY
    .build()
```

**The name.** A bucket's name in Google Cloud Storage is shared with every other customer of Google's,
so it is the installation's prefix, the project, the service and a digest of the two:
`acme-shop-reports-ae7cc739`. The cloud provider names it and the status reports it; read it from
`ANKKA_S3_BUCKET`, never derive it. A name another Google customer holds is a bucket the provider refuses.

**Until the provider has answered.** The service's instances start only once the provider has answered:
until then the service is `UpdateInProgress`, saying what it waits for. A bucket the provider refuses
starts no instance at all, since a service that asked for a bucket cannot be relied on to run correctly
without one: the service is `Failed`, with the provider's reason in its status. Instances already running
when a refusal comes are left as they are.

### Reachable from the internet, and a browser's origin

A bucket in Google Cloud Storage is reachable from the internet by anyone holding a valid signature,
whether or not its descriptor asks for `exposeObjectStorage`: Google's address answers every signed
request. Exposure decides only two things: the service is given `ANKKA_S3_PUBLIC_ENDPOINT`, Google's
address, and `ankka services get` shows the bucket's address on the internet; and the bucket's CORS rule
admits the descriptor's `objectStorageOrigins`. A request without a signature is refused by every bucket.
The cloud provider sets the CORS rule from the descriptor, as the operator does on Garage, and the service
sets none: a rule a service sets through S3 is refused by Google Cloud Storage.

### Versions are kept, and every version can be deleted

Every bucket in Google Cloud Storage keeps versions. An object overwritten or deleted stays readable as a
noncurrent version, and a deleted object can be recovered for the installation's soft-delete window
(`ANKKA_OBJECT_STORE_SOFT_DELETE_DAYS`, 7 days as shipped), which `ankka services get` shows. No bucket has
a retention policy: the platform refuses no deletion on account of an object's age, and keeping a
document for as long as a rule requires is the service's own to do. Deleting every version of an object
removes it once the soft-delete window has passed:

```scala
test("deleting every version of an object leaves none listed") {
  val key = fresh("passport.pdf")
  put(key, "first")
  put(key, "second")
  for (id, _) <- versions(key) do
    s3.deleteObject(
      DeleteObjectRequest.builder().bucket(bucket.get).key(key).versionId(id).build()
    ): Unit
  assertEquals(versions(key), Vector.empty)
}
```

Versions are stored and paid for. `objectStorageVersionAgeDays` deletes a version that is no longer current
after that many days; Garage, which keeps one version, accepts it and does nothing.

A bucket is encrypted, with the installation's wrapping key when it names one (`ANKKA_CLOUD_KMS_KEY`). The
window and the key are the installation's when the bucket is made, and stay as they were when the
installation changes them, until a member applies them again:

```bash
ankka services storage reapply-settings reports -p shop
```

### Where a bucket is made

A bucket is made in its project's location when the project names one, and in the installation's
(`ANKKA_CLOUD_LOCATION`) otherwise. A bucket's location is fixed when it is made.

```bash
ankka projects location set europe-west6 -p shop
ankka projects location clear -p shop
```

### A service with no storage credential

A service written with Google's own client can reach its bucket as its cloud identity, with no key at
all: its Kubernetes ServiceAccount is bound to that identity, so the client's default credentials reach
its bucket and no other. Its descriptor declines the credential:

```json title="service.json"
{
  "name": "reports",
  "service": {
    "image": "registry.example.com/acme/reports:1.0.0",
    "provisionObjectStorage": true,
    "objectStorageCredential": false
  }
}
```

Such a service is given `ANKKA_S3_ENDPOINT`, `ANKKA_S3_REGION` and `ANKKA_S3_BUCKET` and no key, and none is
issued for it. It cannot sign URLs with an HMAC key. An installation whose store is Garage refuses the
descriptor, since a bucket there is reached only with a storage credential.

### Moving a bucket from Garage

A member moves one service's bucket from Garage to Google Cloud Storage:

```bash
ankka services storage move reports -p shop --write-pause-bound 30m
```

A mover the operator runs inside the installation, holding the service's two storage credentials and no
other, copies every object into a bucket made for the service in Google Cloud Storage while the service
goes on writing. Then, for a write pause no longer than its bound (10 minutes as shipped, from one minute
to a day), the service's key in Garage reads and does not write, what changed since the copy began is
copied, and every object is checked on both sides. Once they match, the service is given the variables of
its new bucket and its instances are replaced. `ankka services get` says where the move is: the pause,
since when and how long it may last.

A move that fails, or whose write pause reaches its bound, gives the service its writes back in Garage,
and the status says why; it may be asked for again, and a second run copies only what is missing. The
bucket in Garage is left as it was: the platform deletes nothing. An object larger than 5 GiB does not
move.

## The store in an installation

The object store is the `garage` component of the installation's overlay. On its own it is one node with
one volume, so a bucket's durability is that volume's, which is what a local platform runs.

An installation in a cluster lists `garage-replicated` after it: three nodes, one per machine, each
object kept on all three, so losing a machine and its volume loses nothing. A small Deployment,
`garage-layout`, connects the nodes and gives each its role, and goes on doing so every fifteen
seconds: the node that replaces a lost one, which comes up with a new identity, is given the lost
node's role and Garage copies the objects back to it. Until it has a role it refuses what it is sent,
so for those seconds about one request in three is refused.

`garage-copy` copies every bucket, the services' and the platform's backups alike, to a secondary store
outside the cluster every hour, with `rclone sync`, so an object deleted from Garage is deleted from the
copy at the next run. The secondary's address and credential are the Secret `garage-secondary` in
`garage-system`, with `ENDPOINT`, `ACCESS_KEY_ID`, `SECRET_ACCESS_KEY` and `REGION`, which the
installation creates, beside `garage-copy-key`, the key the copy reads Garage with (`ACCESS_KEY_ID`,
`GK` and 24 hex digits, and `SECRET_ACCESS_KEY`, 64), which the copy imports into Garage itself. Each
copy writes what it did to the ConfigMap `garage-copy-status`, and
`ankka status` reports when the last one completed, when one last failed and why, and how many objects
it deleted. Without a completed copy, the installation's backups share its cluster's failure domain,
and the status says so. An installation that requires a copy, with `ANKKA_BACKUP_COPY_REQUIRED=on`, does
not call a project backed up until its latest base backup has been copied.

The platform's own buckets, `platform.backups.<project>` and `platform.backups-controlplane`, hold the
databases' backups; no service can be given a credential for them. Inside the cluster the store speaks plain HTTP, behind a network
policy that admits only the installation's workloads, the gateway and the operator; a request from a
browser is encrypted as far as the gateway. A request is signed, so a secret key never crosses the
network, but an object's contents do.

An installation without the component has no object store. A service that asks for a bucket there is
reported `object storage provisioning failed`, with the reason, and none of its instances starts.
