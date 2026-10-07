# Quickstart: validating object storage

The runs that show the feature works, cheapest first. Docker is required from step 2. Contracts:
[descriptor-and-status](contracts/descriptor-and-status.md), [operator](contracts/operator.md),
[installation](contracts/installation.md). Data: [data-model.md](data-model.md).

Every command switches the k3s suites off unless it is one; a k3s run is minutes, and belongs
under `caffeinate -i` on a laptop.

## 1. Pure: names, rules, the plan, the credential, the rendering

```bash
sbt -Dankka.cluster.tests=off \
    'crd/testOnly *BucketsSuite *AnkkaServiceCodecSuite' \
    'core/testOnly *PlatformVariablesSuite' \
    'controlPlaneApi/testOnly *ObjectStorageDescriptorSuite *ProjectSecretsSuite *ControlPlaneFixturesSuite *DocumentationDescriptorsSuite' \
    'operator/testOnly *ObjectStorageSuite *StorageCredentialSuite *ObjectStorageRenderingSuite *RenderingGoldenSuite *RenderingUnchangedSuite *CrdSchemaSuite *SettingsSuite'
```

Expect: a name over 63 characters refused naming the limit; the two descriptor refusals in the
contract's words; every row of the plan's table; every row of the credential's table, with no
secret key in any action's description; the variables on the developer's container and on no
other, for each hosting; and `RenderingUnchangedSuite` green against fixtures whose only change
from the base is one `# RemoveHttpRoute` line each (`git diff --stat
operator/src/test/resources/unchanged` shows four files, four insertions).

To see a check fail once: remove `objectStorage` from `ankkaservice.yaml`'s status and run
`CrdSchemaSuite`; then remove one property from inside it.

## 2. The store's client against the real store

```bash
sbt -Dankka.cluster.tests=off 'operator/testOnly *GarageStoreSuite'
```

Expect: a container of `dxflrs/garage:v2.3.0` started with `--single-node`; a bucket named
`shop.reports` created, found and reported with its creation time; a key issued, allowed, and
used to put and get an object path-style; a second key refused by that bucket; and no request
carrying `showSecretKey`.

## 3. The control plane, offline

```bash
sbt -Dankka.cluster.tests=off \
    'controlPlane/testOnly *ServiceProjectionSuite *StatusIngestSuite *ServiceEntitySuite *EventCompatibilitySuite *ReservedSecretNamesSuite *PlatformDeclarationSuite *RemoteOverlaySuite *ControlPlaneHttpSuite' \
    'cli/testOnly *OutputSuite'
```

Expect: the two fields projected; the phase folded to its phrase in the entity and in the listing
alike; a pre-feature journal decoding with no object storage; `-storage` in both reserved lists
and held to `Buckets.secret`; both overlays rendering the store, with the development Secrets in
the local one only (needs `kubectl` on the PATH, or the suite skips — check it ran).

## 4. The console

```bash
just test-console
```

Expect: the fixtures test decoding a `ServiceStatus` with the three fields; the service page
showing a bucket's name, `Its own` and `None` in the three cases.

## 5. On k3s

```bash
caffeinate -i sbt 'controlPlane/testOnly *ObjectStorage*Features'
caffeinate -i sbt 'operator/testOnly *OperatorClusterSuite'
caffeinate -i sbt 'sidecar/testOnly *SidecarClusterSuite'
```

Expect one case per scenario of six feature files, the process's and the module's cases in
`SidecarClusterSuite`, and in `OperatorClusterSuite`:
ten applies leaving `<service>-storage` at one `resourceVersion`, and a token minted for the
operator's ServiceAccount refused a `get` of it (this one needs the prerequisite change merged).

To see only one file: `sbt 'controlPlane/testOnly *ObjectStorageReachableFeatures'`. sbt holds a
suite's report until it ends; `sbt 'set controlPlane / Test / logBuffered := false' …` reports
each scenario as it finishes.

## 6. Documentation

```bash
just docs-reference && just docs-sync && just docs && just features
```

Expect: the new page in the nav and a skill; its example included from the tested suite; no
finding against `features/object-storage/`.

## 7. By hand, on kind

```bash
kind create cluster --name ankka --config kustomization/kind.yaml   # if there is none
./kustomization/deploy-local.sh
ankka login
```

Apply a descriptor with both fields:

```bash
cat > /tmp/reports.json <<'EOF'
{ "name": "reports",
  "service": { "image": "sample-shopping-cart:latest",
               "provisionObjectStorage": true, "exposeObjectStorage": true } }
EOF
ankka services apply -f /tmp/reports.json -p shop
ankka services get reports -p shop
```

Expect `object storage  provisioned`, `bucket  shop.reports` and `bucket address
https://storage.127.0.0.1.sslip.io:8443/shop.reports`.

Keep and read an object from inside the pod, with what the pod was given:

```bash
kubectl -n ankka-shop exec deploy/reports -- sh -c '
  echo hello > /tmp/h &&
  curl -sf --aws-sigv4 "aws:amz:$ANKKA_S3_REGION:s3" --user "$ANKKA_S3_ACCESS_KEY:$ANKKA_S3_SECRET_KEY" \
       -T /tmp/h "$ANKKA_S3_ENDPOINT/$ANKKA_S3_BUCKET/hello.txt" &&
  curl -sf --aws-sigv4 "aws:amz:$ANKKA_S3_REGION:s3" --user "$ANKKA_S3_ACCESS_KEY:$ANKKA_S3_SECRET_KEY" \
       "$ANKKA_S3_ENDPOINT/$ANKKA_S3_BUCKET/hello.txt"'
```

Expect `hello`. Then, from the host, without a signature:

```bash
curl -s -o /dev/null -w '%{http_code}\n' --cacert ~/.ankka/local-ca.crt \
     https://storage.127.0.0.1.sslip.io:8443/shop.reports/hello.txt
```

Expect `403`, from the store. Apply the descriptor again without `exposeObjectStorage` and repeat:
expect `404`, from the Gateway. Then:

```bash
ankka services delete reports -p shop
ankka services apply -f /tmp/reports.json -p shop
ankka services get reports -p shop
```

Expect `object storage  recovered existing bucket`, and the object still readable from the pod.
