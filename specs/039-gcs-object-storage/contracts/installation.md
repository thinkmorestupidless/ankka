# Contract: the installation, and the workflows

What an installation sets, what a GKE installation needs, and how the suites that touch Google
run. Decisions are in [research.md](../research.md) (R2, R12, R13, R14).

## The `ankka-platform` ConfigMap

Beside 044's `ANKKA_CLOUD_PROVIDER`, `ANKKA_CLOUD_ACCOUNT`, `ANKKA_CLOUD_LOCATION` and
`ANKKA_CLOUD_KMS_KEY`:

| Key | Local overlay | Cloud overlay | Read by |
|---|---|---|---|
| `ANKKA_OBJECT_STORE_BACKEND` | `garage` | `SET` (`garage` or `gcs`) | operator, control plane |
| `ANKKA_OBJECT_STORE_PREFIX` | — | `SET` when `gcs` | operator, control plane (shown on the installation's settings) |
| `ANKKA_OBJECT_STORE_SOFT_DELETE_DAYS` | — | `7` | operator, control plane |

The `garage` component's `operator-patch.yaml` is unchanged: an installation that runs Garage
sets the five `ANKKA_OBJECT_STORE_*` the component sets today, with or without `gcs` beside them.
`RemoteOverlaySuite` asserts the three keys exist in the cloud overlay with `SET` and the backend
is `garage` in the local one.

The operator's Deployment gains `ANKKA_STORAGE_MOVER_IMAGE` in `components/operator/operator.yaml`
(the local overlay's unqualified name; the cloud overlay's `images:` block names the registry's,
as the sidecar's does) and the `batch/jobs` rule in its ClusterRole.

## A GKE installation (`docs/platform/install-gke.md`)

The page stands alone and says, in this order:

1. **What runs where.** The cluster is GKE with Workload Identity Federation for GKE enabled.
   ankka's components are the cloud overlay's. `ankka-gcp` runs in `ankka-cloud-provider` under a
   Google service account bound to its Kubernetes ServiceAccount; its roles are owner-equivalent
   on the account and the README of `ankka-gcp` lists them. The operator holds none of them and
   is bound to no Google identity.
2. **Settings.** The four `ANKKA_CLOUD_*` keys and the three above, with an example.
3. **What a service gets.** The same five variables; the bucket's name as the status shows it;
   `storage.googleapis.com` reachable by any valid signature, so exposure decides the public
   endpoint and the CORS rule, not reachability (FR-018, in those words).
4. **Keeping documents.** Versioning and soft delete on every bucket; no retention policy and
   why; how to delete every version through the XML API, with the snippet from
   `GcsCompatibilitySuite`; the cost of versions and `objectStorageVersionAgeDays`.
5. **Re-issuing a credential.** The command, the rotation grace, the rollout.
6. **Declining a credential.** `objectStorageCredential: false`, Google's client with default
   credentials, no signed URLs.
7. **Moving from Garage.** Both stores configured, the command, the bound, what the status shows,
   what the Garage bucket keeps, what to do when a move fails.
8. **Locations.** The installation's default, a project's own, fixed at creation.

## Garage, unchanged

`docs/platform/object-storage.md` keeps its Garage sections and gains "On Google Cloud Storage",
which links to the GKE page and states the differences once (research R14). The limitation that
in-cluster traffic to Garage is plain HTTP stays; the new limitations (Garage keeps one version;
an object over 5 GiB does not move) are added to `docs/reference/limitations.md`.

## Workflows

### `.github/workflows/gcs.yml` (new)

```yaml
on:
  schedule: [{ cron: "30 3 * * *" }]
  workflow_dispatch:
jobs:
  gcs:
    runs-on: ubuntu-latest
    env:
      CI: "true"
      ANKKA_GCS_ENDPOINT: https://storage.googleapis.com
      ANKKA_GCS_REGION: auto
      ANKKA_GCS_BUCKET: ${{ secrets.ANKKA_GCS_BUCKET }}
      ANKKA_GCS_ACCESS_KEY: ${{ secrets.ANKKA_GCS_ACCESS_KEY }}
      ANKKA_GCS_SECRET_KEY: ${{ secrets.ANKKA_GCS_SECRET_KEY }}
      ANKKA_GCS_ORIGIN: https://play.example
    steps: [checkout, java, sbt -Dankka.gcs.tests=on 'controlPlane/testOnly *GcsCompatibilitySuite']
```

The bucket is prepared once by hand in the test project: versioning on, soft delete 7 days,
public access prevention, uniform access, a CORS rule admitting `ANKKA_GCS_ORIGIN`, and an HMAC
key of a service account granted `roles/storage.objectUser` on it. The suite `assume`-skips without
the variables and, under `CI` with `ankka.gcs.tests=on`, fails instead. The workflow and the
secret names are listed in `ci.yml`'s `unchecked` filter.

### `cluster.yml`, unchanged

`ObjectStorageGcsClusterFeatures` reads `ankka.cluster.tests`, so `cluster-suites.py` lists it;
its runner is `sbt 'controlPlane/testOnly *ObjectStorageGcsClusterFeatures'`, which builds the
sample image and the mover's. `GcsCompatibilitySuite` does **not** read `ankka.cluster.tests`.

### `ci.yml`

- `scala-build` and `scala` filters gain `storage-mover/**`; the `rest` matrix builds it by
  subtraction.
- `unchecked` gains `.github/workflows/gcs.yml`.
- `build.sbt`'s forwarded `-D` list gains `ankka.gcs.tests`.

## Release notes

- A GCS installation needs `ankka-gcp` and the three settings; the GKE page is the install
  guide.
- The operator's ClusterRole gains `batch/jobs`.
- A Garage key made before this release is generation 0 and is replaced only on a re-issue.
