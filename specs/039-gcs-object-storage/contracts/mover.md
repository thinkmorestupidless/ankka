# Contract: the storage mover

A program in its own image, `ankka-storage-mover`, run by the operator as a Job per phase of a
move ([operator.md](operator.md), "The Job"). It depends on nothing of ankka. Decisions are in
[research.md](../research.md) R7.

## Environment

| Variable | Meaning |
|---|---|
| `MOVER_SOURCE_ENDPOINT`, `MOVER_SOURCE_REGION`, `MOVER_SOURCE_BUCKET`, `MOVER_SOURCE_ACCESS_KEY`, `MOVER_SOURCE_SECRET_KEY` | the bucket in Garage, with the service's credential |
| `MOVER_TARGET_ENDPOINT`, `MOVER_TARGET_REGION`, `MOVER_TARGET_BUCKET`, `MOVER_TARGET_ACCESS_KEY`, `MOVER_TARGET_SECRET_KEY` | the bucket in Google Cloud Storage, with the service's credential there |
| `MOVER_CONCURRENCY` | objects in flight at once; `8` as shipped |

Both clients: path-style, `WHEN_REQUIRED` checksums both ways, the region as given — the
settings the docs give every service. A missing variable is exit 2 naming it.

## Modes

### `copy`

1. List the source (paginated, `ListObjectsV2`), streaming; count every object.
2. For each object, `HEAD` the target. Copy when the target is absent, or its size differs, or
   its `x-amz-meta-sha256` (written by this program) differs from the source's content hash —
   computed by streaming the source body through SHA-256 **before** the upload when the
   object is small enough to buffer (≤ 64 MiB), and otherwise by a first pass that hashes and
   a second that uploads (two reads of the source, one of the target).
3. `PutObject` to the target with the source's `Content-Type`, `Cache-Control`,
   `Content-Disposition`, `Content-Encoding` and every `x-amz-meta-*`, plus
   `x-amz-meta-sha256: <hex>`. One request; an object over 5 GiB is a failure naming it.
4. After each upload, `HEAD` the target and compare size; the hash is verified in `verify`.
5. Never deletes, overwrites only what step 2 chose, and never touches the source.

Interrupted part way, a second `copy` run copies what steps 1–2 find missing or different and
nothing twice: "a move that stopped part way is finished by running it again".

### `verify`

1. `copy` first (objects written since the previous run are the delta: "objects written during
   the bulk copy are copied in the write pause").
2. List both sides; the sets of keys must be equal, or fail naming the first key missing on
   either side.
3. For every key, read **both** bodies and hash both; fail naming the first that differs. ETags
   are never compared.
4. Report.

## Result

One JSON object, as the last line of stdout and in `/dev/termination-log`:

```json
{"mode":"verify","counted":10000,"copied":17,"verified":10000,"failed":null,"reason":null,"seconds":412}
```

| Field | Meaning |
|---|---|
| `counted` | objects listed on the source |
| `copied` | objects uploaded this run |
| `verified` | objects whose hashes matched (verify only) |
| `failed` | the key the run stopped at, or `null` |
| `reason` | `differs`, `missing on target`, `missing on source`, `over 5 GiB`, `source unreachable`, `target unreachable`, `refused by source`, `refused by target`, or the exception's message |

Exit codes: `0` done; `1` verification failed or an object could not be moved (`failed` and
`reason` say which); `2` could not run (configuration, unreachable store, refused credential).
The operator reads the termination message into the move's status; the reason reaches
`services get` word for word.

## What it never does

Delete an object or a version on either side; set a bucket's settings; compare ETags; hold any
credential but the two it is given; speak to the Kubernetes API.

## Tests

`MoverSuite` runs two Garage containers (the pinned image), seeds the source with objects of
several sizes, content types and metadata, and proves: a copy round-trips every object with its
metadata and `x-amz-meta-sha256`; a second copy uploads nothing; an object changed on the source
after the first copy is copied by the second; an object altered on the target makes `verify` fail
naming it; an object added to the source during a copy (the suite writes it mid-run) is on the
target after `verify`; a source credential that cannot write still copies (read-only key); exit 2
with the variable's name when one is missing. S4 (two endpoints, two credentials, one JVM) is this
suite.

## Image

Built by `sbt storageMover/docker:publishLocal` as the sidecar is, `eclipse-temurin` base, one
entrypoint, `BuildInfo.imageTag`. The operator names it through `ANKKA_STORAGE_MOVER_IMAGE`; the
k3s suite loads the commit-tagged image as it loads the sample's, never `:latest`.
