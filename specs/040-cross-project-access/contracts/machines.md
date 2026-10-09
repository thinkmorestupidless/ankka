# Contract: registered machines and their tokens

Held by `features/cross-project/machines.feature` and the machine half of `machine-topics.feature`.

## Registration

```text
POST   /organizations/{id}/machines                    {"name": "affiliate-network"}
                                                       → 200 {"clientId": "machine:eitheror/affiliate-network",
                                                              "clientSecret": "<64 hex, shown once>",
                                                              "tokenUrl": "https://api.<base>/oauth/token",
                                                              "brokerBootstrap": "broker.<base>:9094" | absent}
GET    /organizations/{id}/machines                    → [MachineSummary]   name, registered by, when, byte rates; no secret
DELETE /organizations/{id}/machines/{name}             → 204; the token route refuses the client id from now on
PUT    /organizations/{id}/machines/{name}/byte-rates  {"produceBytesPerSecond": 1048576, "consumeBytesPerSecond": 4194304, "requestPercentage": 50}
```

Who: an owner registers, deletes and sets byte rates; a member lists; a deploy token does none.
A name already registered and not deleted is 409. A name deleted earlier may be registered again:
a new machine, a new secret, no grant.

```bash
ankka organizations machines register eitheror affiliate-network      # prints the client id and secret once
ankka organizations machines list eitheror
ankka organizations machines delete eitheror affiliate-network
ankka organizations machines byte-rates eitheror affiliate-network --produce 1MiB --consume 4MiB --request-percentage 50
```

## The token route (OAuth 2.0 client credentials)

```text
POST /oauth/token
Content-Type: application/x-www-form-urlencoded
grant_type=client_credentials&client_id=machine%3Aeitheror%2Faffiliate-network&client_secret=…
   (or the client id and secret as HTTP Basic)

200 {"access_token": "<JWT>", "token_type": "Bearer", "expires_in": 900}
400 {"error": "unsupported_grant_type"} | {"error": "invalid_request"}
401 {"error": "invalid_client"}            unknown id, wrong secret, deleted machine — all alike
429 Retry-After: <seconds>                 more than ANKKA_MACHINE_TOKEN_RATE requests a minute for one client id, per node
```

`GET /.well-known/jwks.json` and `GET /.well-known/openid-configuration` (issuer, jwks_uri,
token_endpoint) are served by the control plane behind the gateway and on its `keys` port 7629
inside the cluster (server TLS only). `POST /platform/machine-keys/rotate` (platform administrator)
adds a signing key; a recurring timed action does the same every 30 days and drops keys older
than the previous one an hour after a rotation.

The token's claims are in `data-model.md`. It carries no grant.

## At a route

A request through the gateway with `Authorization: Bearer <machine token>` reaches the handler as
`Caller.Machine(organization, name)`; with no token, an expired one, or any other issuer's, as
`Caller.Gateway`. At a route whose ACL is `Acl.Authenticate` the same token also yields a
`Principal` with subject `machine:<org>/<name>` and claims `kind: machine`, `organization`. A
machine's token over a service-to-service connection is ignored: the certificate is the caller.

Scala: `Callers.granted` in an ACL; `caller` in a handler matches `Caller.Machine(org, name)`.
Python: `Callers.granted`; `MachineCaller(organization, name)`. TypeScript: `Callers.granted`;
`{ kind: "machine", organization, name }`. Rust: `CallerMatcher::Granted`; `Caller::Machine {
organization, name }`.

## Platform variables (rendered by the operator, refused in a descriptor)

```text
ANKKA_MACHINE_ISSUER=https://api.<base>                      the token's iss
ANKKA_MACHINE_JWKS_URL=https://ankka-controlplane.ankka-controlplane.svc:7629/.well-known/jwks.json
ANKKA_MACHINE_JWKS_CA=/var/run/secrets/ankka/service/ca.crt   the service authority, already mounted
```

Unset (a local run, a service outside a cluster): no token is verified, every bearer leaves the
caller as it was.
