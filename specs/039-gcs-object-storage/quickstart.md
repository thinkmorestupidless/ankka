# Quickstart: validating Object Storage on Google Cloud Storage

The runs that prove the feature, cheapest first. Each names what it shows and what a pass cannot
be mistaken for. Contracts are in [contracts/](contracts/); the data is in
[data-model.md](data-model.md).

## 0. Before anything: 044, and the spikes

- 044 is merged (`CloudResource`, the scripted provider, the settings; research R1a). Without it slices 5 to 7 have
  nothing to render against.
- **S1** — `sbt 'operator/testOnly *GarageStoreSuite'`: three new cases against `dxflrs/garage:v2.3.0`
  prove `UpdateBucket.corsRules` is honoured (a preflight from a named origin answers the
  headers, an unnamed one does not), `AllowBucketKey` with `write: false` refuses a `PutObject`
  and serves a `GetObject`, and `UpdateKey.expiration` in the past makes the key `expired` and
  refused. If any fails, R5 and R6 change before anything is built on them.
- **S2** — `ANKKA_GCS_*=… sbt -Dankka.gcs.tests=on 'controlPlane/testOnly *GcsCompatibilitySuite'`
  by hand against the prepared bucket (contracts/installation.md).

## 1. Pure, offline (`sbt -Dankka.cluster.tests=off test`, about a minute per module)

| Suite | Shows |
|---|---|
| `PlatformVariablesSuite`, `PlatformDeclarationSuite` | the three names are declared once and `PlatformOnly` |
| `SettingsSuite` | `gcs` without a provider or a prefix fails at startup naming it; `garage` defaults when the admin URL is set; the mover image and GCS endpoint defaults |
| `ObjectStorageDescriptorSuite`, `DescriptorSuite` | the three fields, their refusals, defaults omitted on the wire; `objectStorageCredential: false` survives a round trip (the jsoniter trap) |
| `ServiceProjectionSuite` | the six spec fields; the name-length rule only on `garage`; the bound filled to `10m` |
| `ServiceEntitySuite`, `ProjectEntitySuite` | the three events and the location event; history kinds; re-issue and a second move refused during a move; a failed move can be requested again |
| `EventCompatibilitySuite` | every new field defaults; a 034-era journal decodes |
| `StatusIngestSuite` | store, bucket, address and the move read from the status; `detail` prefixed |
| `ObjectStorageSuite` | `decide` per store, every row of the table; `status` per store |
| `StorageMoveSuite` | every transition of the state machine, the bound's expiry, the deadline's remainder, `Failed` → `Requested` on a new generation |
| `StorageCredentialSuite` | `reissue` against the `World` double: the generation-named key, the patch, expiry on lower generations, idempotence; `readOnly`; restore |
| `CloudRequestsSuite` | the three requests' parameters from spec, settings and project; the credential request absent until both are `Ready` or when declined; `settings-generation` rewrites `softDeleteDays` and `kmsKey` and never `location` |
| `ObjectStorageRenderingSuite` | the variables per store; `envFrom` per store and absent when declined; the credential annotation present only with a platform bucket; the annotations on the ServiceAccount; the Job per phase; `RemoveHttpRoute` on `gcs` |
| `RenderingGoldenSuite` | `golden/object-storage-gcs.txt` |
| `RenderingUnchangedSuite` | a service on Garage renders exactly what it rendered — **no repin** |
| `CrdSchemaSuite` | every new field on both resources, inside `objectStorage` and `move` |
| `ReservedSecretNamesSuite` | `<service>-cloud-storage` cannot be read by a descriptor |
| `MoverSuite` (`storage-mover`) | contracts/mover.md's eight cases against two Garage containers (S4) |
| CLI suites | the five commands and the eight output lines |
| console unit tests | the fact, the three actions, the fake's answers |

## 2. The store, offline (`sbt 'operator/testOnly *GarageStoreSuite'`)

The S1 cases above, run as part of the suite from now on.

## 3. k3s with the scripted provider in its Garage-backed mode (`caffeinate -i sbt 'controlPlane/testOnly *ObjectStorageGcsClusterFeatures'`)

The installation: PKI, the `garage` component, the fake provider, the operator with
`ANKKA_OBJECT_STORE_BACKEND=gcs`, `ANKKA_OBJECT_STORE_PREFIX=t`, `ANKKA_CLOUD_PROVIDER=fake`,
`ANKKA_OBJECT_STORE_GCS_ENDPOINT` at Garage's S3 port, a rotation grace of 20 seconds and the
sample image. Each scenario of `features/object-storage-gcs/` runs whole, and the suite reports
by name the ones in `ranOutside`:

| Scenario (short) | Shown by |
|---|---|
| given a bucket; reads it back with the Garage client's settings; status names the bucket, store and location | the three `CloudResource`s, the fake's bucket, a put and a get from the pod, `services get` |
| a name another customer holds → `Failed`; cannot be made yet → still being made | the fake told to answer `Failed` / `Waiting` for one request |
| two services whose names join the same → different buckets | the fake's `BucketNames` digest |
| operator reads only the provider's status; neither reads a credential back | the operator's token refused a `get`; the fake's token refused a `get` |
| a credential issued again … refused once the grace has passed; an instance not yet replaced is refused | a second key in the Secret; a `get` with the first key after 20 s refused by Garage; a pod held back by a readiness gate |
| declines a credential → no key variables, none issued; refused on Garage | the pod's env; the fake's issue log; the apply refused on a `garage` run of the same steps |
| exposed → `ANKKA_S3_PUBLIC_ENDPOINT`; named origin uploads through a signed URL; unnamed cannot | the preflight against Garage's CORS from the fake's `corsOrigins` |
| the move: every scenario of `move.feature` | two Garage buckets; the copy Job; a write refused during the pause and a read served; the bound at 1 minute reached by holding the verify Job; the switch rolls the pods onto the new variables; the Garage bucket kept |
| location: the installation's, the project's | the request's `location` and the status |

`ranOutside` (reason: Google only): "Google Cloud Storage refuses" rows of isolation and keyless;
"the cloud provider reaches Google Cloud through its workload identity"; "a service's cloud
identity is granted on its own bucket and on nothing else"; retention's versioning, deletion
and retention-policy scenarios; "a request without a signed URL is refused by every bucket".
Of these, the retention and reachable ones name their test in `GcsCompatibilitySuite`.

Also: `ObjectStorageClusterFeatures` gains the steps for the two changed scenarios of
`features/object-storage/` (re-issue; origins without the service setting a rule), on Garage.

## 4. Google, nightly (`.github/workflows/gcs.yml`; by hand as in S2)

`GcsCompatibilitySuite`, each test named for the scenario it proves: an object overwritten is
readable as a noncurrent version; a deleted one too; deleting every version leaves none listed
(`?versions`, `DELETE ?generation=`); an unsigned GET is refused; a presigned PUT from the named
origin's preflight is allowed and from another is not; the AWS SDK's `PutBucketCors` is refused
(FR-016's reason); the checksum settings work. The docs' snippets are taken from this suite.
ankka-gcp's nightly covers the rest (044 SC-007); until it is green, the GCS path is not called
done.

## 5. Documentation (`just docs`, `just docs-sync`, `just features`)

Every page builds; the included snippets come from `GcsCompatibilitySuite` and `MoverSuite`; the
GKE page is in the nav and the rendered skill; the features check reads 46 specs and finds
nothing.

## 6. By hand, on a GKE installation with `ankka-gcp`

1. Set the seven settings; deploy the cloud overlay; `kubectl get cloudresources -A` shows
   nothing yet.
2. Apply the shopping cart with `provisionObjectStorage: true`: three `CloudResource`s, a Secret
   `cart-cloud-storage`, `services get` shows `object store gcs` and a bucket named with the prefix
   and digest; a put and a get from the pod.
3. `ankka services storage reissue`: a new key in place within a minute, the pods roll, the old
   key refused an hour later.
4. On an installation that had Garage: `ankka services storage move` on a service with objects;
   watch `services get` through `copying`, `write pause` (a write from the pod refused, a read
   served), `moved`; the pods read the objects from the new bucket; the Garage bucket unchanged.
5. `kubectl get pods -n ankka-operator -o yaml | grep -i google` finds nothing; the operator's
   ServiceAccount has no `iam.gke.io` annotation.
