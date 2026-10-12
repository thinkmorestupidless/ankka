# Contract: the control plane, the CLI and what a member reads

What a member sends and what the control plane, the CLI and the console say back. Decisions are
in [research.md](../research.md) (R2, R3, R4, R7, R11, R17).

## Routes

| Route | Who | Body | Answers |
|---|---|---|---|
| `PUT /services/{projectId}/{name}/hostnames/{hostname}` | a member with write access to the project; a platform administrator | none | 204; 404 unknown service; 409 with a refusal |
| `DELETE /services/{projectId}/{name}/hostnames/{hostname}` | the same | none | 204, also when not held; 404 unknown service |

A `DELETE` by a principal carrying the `platform-admin` role records `hostname taken away`, with
the administrative attribution `Authorization.administrator` builds, whether or not they are also
a member; by anyone else, `hostname removed`. Both are the history's.

Hostnames in the path are normalised (lowercase, no trailing dot) before anything else.

## Refusals, in order (FR-002a)

| # | Refusal | Message |
|---|---|---|
| 1 | not a name alone | `a custom hostname is a name alone: '<h>' has a scheme, a path or a port` |
| 2 | a wildcard | `a custom hostname cannot be a wildcard` |
| 3 | not a DNS name | `'<h>' is not a hostname: <label '<l>' is over 63 characters / has a character outside a-z, 0-9 and '-' / starts or ends with '-' / is empty>` or `'<h>' is 260 characters, over the 253 character limit` |
| 4 | one label | `'<h>' is not a name on the internet: a custom hostname has at least two labels` |
| 5 | under the base domain | `a custom hostname cannot be under the base domain '<base>'` |
| 6 | no issuer | `the installation names no authority for custom hostnames (ANKKA_HOSTNAME_ISSUER)` |
| 7 | not exposed | `service '<n>' is not exposed` |
| 8 | at the cap | `service '<n>' holds 5 custom hostnames, the most a service can hold` |
| 9 | held by another | `'<h>' is held by service '<other>' in project '<p>'` |
| 10 | no proof record | `'<h>' does not carry the proof record of project '<p>': create TXT _ankka.<h> with the value "ankka-project=<p>"` |
| 11 | could not look up | `could not look up _ankka.<h>: <detail>; the proof record was not checked` |

1 to 5 are `CustomHostnames.problems`, pure, in `controlplane-api`. 6 is the config. 7 and 8 are
the entity's (`add-hostname` answers them). 9 is the holder scan. 10 and 11 are the lookup, last,
so no DNS is read for a name that would be refused anyway. A hostname this service already holds
is a 204 and no event.

## What a member reads (`GET /services/{projectId}/{name}`, the listing)

```json
{
  "name": "cart",
  "exposed": true,
  "hostname": "https://cart-checkout.example.test",
  "proofRecord": { "name": "_ankka.<hostname>", "kind": "TXT", "value": "ankka-project=checkout" },
  "customHostnames": [
    { "hostname": "app.example.com", "state": "serving",
      "record": { "name": "app.example.com", "kind": "CNAME", "value": "cart-checkout.example.test" } },
    { "hostname": "example.com", "state": "pending",
      "reason": "waiting for the certificate: Waiting for HTTP-01 challenge propagation: failed to perform self check GET request 'http://example.com/.well-known/acme-challenge/…': … no such host",
      "note": "an apex cannot be a CNAME; this installation has published no address" }
  ]
}
```

- `proofRecord` is present whenever the service is read, exposed or not, with `<hostname>`
  literally, since it is the same for every hostname the project brings and a member may prepare
  DNS before exposing.
- `state` is `pending`, `serving` or `rejected`; `reason` is the operator's word (contract
  [operator.md](operator.md)); `record` is the record to create, `note` what cannot be said as a
  record. On a local platform the note adds `the installation answers on port <port>`.
- A hostname just added and not yet observed is `pending` with no reason.
- Unexposed: `customHostnames` keeps every hostname with `state: pending`, `reason: the service is
  not exposed`.

## History (`GET /services/{projectId}/{name}/history`)

Kinds `hostname added`, `hostname removed`, `hostname taken away`, each with the actor and the
time, and the hostname in the entry's detail.

## CLI

```
ankka services hostnames add <service> <hostname> -p <project>
ankka services hostnames remove <service> <hostname> -p <project>
ankka services get <service> -p <project>
ankka services list -p <project>
```

`add` prints, on success, the record to create (or the apex note) and reminds the proof record
stays; on a refusal, the message and exit 1 (the refusal for 10 is the whole record to
create). `remove` prints `removed` and exit 0 also when nothing was held. `get` prints after
`hostname`:

```
proof record     TXT _ankka.<hostname> "ankka-project=checkout"
custom hostnames
  app.example.com   serving
                    create CNAME app.example.com → cart-checkout.example.test
  example.com       pending: waiting for the certificate: … no such host
                    an apex cannot be a CNAME; this installation has published no address
```

`list` shows the derived hostname and then each custom hostname in the `HOSTNAME` column,
comma-separated, a `!` after one that is not serving. `--format json` prints the wire.

MCP tools: `add_hostname(project, service, hostname)` and `remove_hostname(...)`, descriptions
naming the proof record.

## Console

The service page gains **Hostnames** under the address: the derived hostname, the proof record,
each custom hostname with its state and reason and its record, a remove control per hostname, an
add form (one input), and the control plane's refusal shown verbatim. The project page's services
table shows custom hostnames after the derived one. `fake-control-plane.ts` applies refusals 1 to
5, 7, 8 and 9 as the API does, and 10 for any hostname whose first label is `unproved`.

## Routes reference

`docs/reference/control-plane-api.md` gains the two routes in the generated table
(`ControlPlaneRoutesReferenceSuite`) and hand-written sections for each, the `ServiceStatus` fields
`customHostnames` and `proofRecord`, and the three history kinds. `docs/reference/cli.md` gains the
commands (`CliReferenceSuite`).
