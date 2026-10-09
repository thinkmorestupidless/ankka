# Contract: what 042 asks of 040, 041, 039 and 044

Held by no feature of 042's; by `features/cross-project/*.feature` and
`features/databases/*.feature` once those land. Each is a seam 042 builds and a later feature fills.

## 040 — grants rendered to the keyring and to the control plane

`GrantReader` (runtime): `grants(principal: Principal): Set[Grant]`, `Grant(project: String,
target: Target, attributes: Set[String], state: String)`, `Target.Topic(name) | Erasure`. 042
reads `attributes` for `decrypt` and `target` for `Erasure`, and only `state == "accepted"` opens
anything. 040 provides `GrantReader.fromFile(path)` over the rendered grants volume (its FR-010,
FR-032), re-read by modification time, and mounts the volume on the keyring's Deployment and the
control plane's. The principals 042 passes: `service:<project>/<name>` from a certificate,
`machine:<organization>/<name>` from a verified machine token (`Caller.Machine`, 040 FR-015).

Until then: `GrantReader.none` everywhere but the test kit, so a consumer in another project reads
every personal field as erased, a service asking for an erasure in any project is refused and the
refusal recorded, and `/decrypt` answers 403.

## 040 — the machine issuer

`/decrypt` verifies a bearer token with `auth-oidc` over the issuer named by the keyring's
`ANKKA_AUTH_` set; 040's control plane issuer (FR-014, a JWKS the control plane publishes) is that
issuer, and the keyring component's manifest names it once 040 decides its URL.

## 041 — restores

A switched service applies the keyring's log before it reports ready with no code of 041's
(`ErasureRuntime`, R16); the observe document carries `erasures.appliedUpTo` and `finishedAt`, which
041's restore status reads to say when the service finished. The keyring's database is backed up to
a bucket of its own (041 FR-003), and after its restore `KeyringReplay` runs before any answer. A
restored control plane calls `ErasureLogBucket.reconcile()` before release and reports the entries
gained (041 FR-031; the method exists and runs at every start today).

The k3s proof of both restore cases is a task blocked on 041 (`ErasureClusterFeatures`, the two
restore scenarios, `@ranElsewhere` until then and run offline by template copy in the meantime).

## 039 — finality on GCS

`ObjectErasure.erase` reads the bucket's store and soft-delete window from the service's status
(039 FR-021, FR-013) to compute `finalAt`; until 039 the store is Garage and `finalAt` is now.
`objects.feature`'s "every version" and "soft-delete window" rows are tasks blocked on 039.

## 044 — the wrapping key

`RootKeySource.fromWrappingKey` takes the `wrapping-key` request's key for the keyring's identity
(044 FR-005); until then `RootKeySource.secretStore` is the only source and the keyring's manifest
names no cloud identity.
