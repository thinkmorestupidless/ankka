# Contract: wire-type fixtures

`console/package/fixtures/control-plane/<TypeName>.json`, one per codec in `controlplane-api`'s
`Wire` object plus the error body, written by `ControlPlaneFixturesSuite` in `controlplane-api`'s
tests and read by the package's `client.fixtures.test.ts`.

```json
{
  "type": "ServiceStatus",
  "json": {
    "name": "cart",
    "projectId": "checkout",
    "lifecycle": "Ready",
    "generation": 3,
    "image": "sample-shopping-cart:latest",
    "readyInstances": 1,
    "desiredInstances": 1,
    "detail": "…",
    "confirmed": true,
    "database": "provisioned",
    "hostname": "https://cart-checkout.example.com",
    "exposed": true,
    "suspended": false,
    "paused": false,
    "hosting": "embedded",
    "protocol": null
  }
}
```

Rules:

- The sample fills every optional field with a value **and** a second file, `<TypeName>.minimal.json`,
  omits every one, so both edges of the schema are held.
- `json` is exactly what the platform's codec wrote (`writeToArray`), pretty-printed for review; the
  suite compares canonical forms, so formatting is free and a field is not.
- The Scala suite fails when a committed file differs from a regenerated one, and it fails when a
  `Wire` codec has no fixture (SC-010). It regenerates under `-Dankka.docs.update=true`, the same
  switch the reference-page suites use.
- The TypeScript test decodes each file with the schema named by `type`, asserts the decoded value
  round-trips to the same canonical JSON for the full sample, and asserts the minimal sample decodes
  with every optional absent.
- A field added on the Scala side without a schema change is caught by the round-trip (the key is
  dropped on decode); a field removed is caught by the schema's required keys.
