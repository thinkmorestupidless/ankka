# Contract: the Secret Manager REST subset the store speaks and the fake serves

Base: `ankka.secrets.secret-manager.endpoint` (`https://secretmanager.googleapis.com` as shipped).
Every request carries `Authorization: Bearer <token>` from `AccessTokens` (the GKE metadata server,
or the fixed test token) and `Content-Type: application/json`. `<account>` is `ANKKA_CLOUD_ACCOUNT`.

| Operation | Request | Used by | Success | Errors the store maps |
|---|---|---|---|---|
| create secret | `POST /v1/projects/<account>/secrets?secretId=<id>` body `{replication, annotations, customerManagedEncryption?}` | store `put` (on 404 from addVersion), control plane writer | `200` Secret | `409 ALREADY_EXISTS` → continue; `403` → `Internal` (grant) |
| add version | `POST /v1/projects/<account>/secrets/<id>:addVersion` body `{"payload": {"data": "<base64>"}}` | store `put`, writer `setEntries` | `200` SecretVersion (`name` ends `/versions/<n>`) | `404` → create then retry once; `403` → `Internal`; `429/503` → `Unavailable` |
| access latest | `GET /v1/projects/<account>/secrets/<id>/versions/latest:access` | store `get`, move check | `200` `{name, payload: {data}}` | `404` → none; `403` → `Internal`; `429/503` → `Unavailable` |
| delete secret | `DELETE /v1/projects/<account>/secrets/<id>` | store `delete` | `200` | `404` → success; `403` → `Internal` |
| list versions | `GET /v1/projects/<account>/secrets/<id>/versions?filter=state:ENABLED&pageSize=100` | store prune, writer `removeEntry`, `latestSkipped` | `200` `{versions: [{name, state}]}` | `403` → warn (prune) / `Internal` (writer) |
| destroy version | `POST /v1/…/versions/<n>:destroy` | store prune | `200` | logged at warn, never fails `put` |
| disable version | `POST /v1/…/versions/<n>:disable` | writer `removeEntry` | `200` | `403` → `Unavailable` to the member (the route's existing mapping) |
| token | `GET http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/token`, header `Metadata-Flavor: Google` | `AccessTokens.metadata` | `{access_token, expires_in}` | any failure → `Unavailable` |

Google's error body is `{"error": {"code": 403, "message": "…", "status": "PERMISSION_DENIED"}}`;
the store reads `status` and `message` and puts both in its `CommandError` message, never a value.

Timeouts: one `ankka.secrets.timeout` (10s) bounds a store call end to end, including a create and
retry; the token call has 5s and is cached until 60s before expiry.

## The fake

Serves the same paths on loopback, reads the identity from the token (`fake:<project>/<service>`,
`fake:controlplane`, `fake:provider`; anything else is `401 UNAUTHENTICATED`), applies the grant rule
of research R3 before any state change, and answers Google's shapes. Extra, for tests only:
`unreachable(true)` closes the listener; `failNext(503)` fails the next call; `calls` lists
`(identity, method, id)`; `snapshot` returns ids → enabled version count; `versionsOf(id)`.

## Secret ids

See `data-model.md` "Derived id". The fake refuses an id that does not match
`[A-Za-z0-9_-]{1,255}` with `400 INVALID_ARGUMENT`, as Google does.
