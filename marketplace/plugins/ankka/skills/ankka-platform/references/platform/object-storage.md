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
"aws:amz:$ANKKA_S3_REGION:s3" --user "$ANKKA_S3_ACCESS_KEY:$ANKKA_S3_SECRET_KEY"
"$ANKKA_S3_ENDPOINT/$ANKKA_S3_BUCKET/<object>"` reads an object. An older curl signs without sending the request's
payload hash, which the store requires: add it as the `x-amz-content-sha256` header.

## Buckets and objects are never deleted by the platform

Deleting a service leaves its bucket, its objects and its storage credential exactly as they were. A
descriptor applied again under the same name, in the same project, is given the same bucket and the same
credential, and `services get` reports `recovered existing bucket`. The platform has no operation that
deletes a bucket or an object.

The service itself owns its bucket, so it can delete objects, and can delete the bucket once it is empty.
A bucket deleted that way is made again, empty, on the platform's next pass, and the service's credential
reaches it.

## A credential is made once

The credential is issued once, when the bucket is first made, and never rotated or replaced. Applying the
descriptor again, restarting the service or upgrading the platform leaves it as it is. The platform's
operator writes the Secret and cannot read it back; the control plane never holds it. The operator does
hold the object store's administrator token, as it administers each project's database, and whoever holds
that token can reach every bucket.

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
    "exposeObjectStorage": true
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
- **An upload from a page needs a CORS rule on the bucket.** A browser sending a file from a page to the
  store's hostname asks first, and is refused unless the bucket allows the page's origin. The service sets
  its own bucket's CORS rule with its S3 client (`PutBucketCors`); the platform sets none.
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

No descriptor may take a variable from a Secret named `<service>-storage`, its own service's included, and
no project secret may be given such a name: a storage credential reaches its own service and no other.

## The store in an installation

The object store is the `garage` component of the installation's overlay. It is one node with one volume,
so a bucket's durability is that volume's; an installation that needs more runs more nodes, with a higher
replication factor, from its own overlay. Inside the cluster the store speaks plain HTTP, behind a network
policy that admits only the installation's workloads, the gateway and the operator; a request from a
browser is encrypted as far as the gateway. A request is signed, so a secret key never crosses the
network, but an object's contents do.

An installation without the component has no object store. A service that asks for a bucket there is
reported `object storage provisioning failed`, with the reason, and none of its instances starts.
