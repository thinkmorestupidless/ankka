# Data Model: Custom Hostnames

What is recorded, where, and in what shape. Decisions are in [research.md](research.md).

## The custom hostname

A string, normalised by `CustomHostnames.normalise`: lowercase, a trailing dot removed. Valid when
`CustomHostnames.problems(hostname, baseDomain)` is empty:

| Rule | Refusal |
|---|---|
| contains `://`, `/`, `:` or whitespace | `a custom hostname is a name alone` |
| contains `*` | `a custom hostname cannot be a wildcard` |
| over 253 characters; a label empty or over 63; a character outside `[a-z0-9-]`; a label starting or ending with `-` | `'<h>' is not a hostname: <which>` |
| fewer than two labels | `'<h>' is not a name on the internet: a custom hostname has at least two labels` |
| equals the base domain, or ends with `.<base domain>` | `a custom hostname cannot be under the base domain` |

`MaxPerService = 5`. `Hostnames.isApex(h)` is "exactly two labels" (R17).

Identity: one hostname is held by at most one service of the installation (R4); the holder is
found by scanning `ServiceRows`.

## The proof record (R3)

| | |
|---|---|
| name | `_ankka.<hostname>` |
| type | `TXT` |
| value | `ankka-project=<project id>` |
| read | once, when the hostname is added, by `ProofLookup.txt(name): Either[LookupFailure, Vector[String]]` over JNDI; `LookupFailure` is `NoRecord` or `Unreachable(detail)` |

Not stored anywhere: it is a function of the project id, and `services get` states it.

## The record to create (R11, R17)

(The wire field is `record`; prose says "the record to create", the glossary's words.)

`DnsRecord(name, kind, value)`, derived on the way out of the control plane:

| hostname | kind | value |
|---|---|---|
| three labels or more | `CNAME` | the derived hostname `<service>-<project>.<base domain>` |
| an apex, address known | `A` | `ANKKA_GATEWAY_ADDRESS` |
| an apex, address unknown | — | `record: None`, `note: "an apex cannot be a CNAME; this installation has published no address"` |

On a local platform the note carries the port: `the installation answers on port 8443`.

## The service entity (R7)

```
Service
  customHostnames: Vector[String] = Vector.empty      // desired, in the order added
  hostnameReports: Vector[HostnameReport] = Vector.empty   // observed, from the resource

HostnameReport(hostname: String, state: String, reason: Option[String])   // state ∈ pending | serving | rejected
```

Events (all with `actor: Option[Actor] = None`, `at: Option[Instant] = None`; none bumps the
generation):

| Event | Fields | Fold |
|---|---|---|
| `CustomHostnameAdded` | `hostname` | append; `remember("hostname added")` |
| `CustomHostnameRemoved` | `hostname`, `byAdministrator: Boolean = false` | remove; `remember("hostname removed")` or `("hostname taken away")` |
| `ServiceObserved` | `+ hostnames: Vector[HostnameReport] = Vector.empty` | replaces `hostnameReports` |
| `ServiceDeleted` | — | `customHostnames = Vector.empty`, as `exposed = false` |
| `ServiceUnexposed` | — | untouched: the hostnames stay recorded |

Handlers (wire names): `add-hostname` (refuses: not exists → 404; not exposed; already held by
this service → reply unchanged; at the cap), `remove-hostname` (not held → reply unchanged),
`take-hostname-away` (the same, `byAdministrator = true`). `desiredState` carries
`customHostnames`.

## The view

`ServiceRows`' row is `ServiceStatus`; it gains `customHostnames` (the wire field) so the holder
scan and the listing read it. Folded from the two events and from `ServiceObserved` (the states).

## The resource (R1, R6)

```
AnkkaServiceSpec
  + customHostnames: List[String] = Nil         // projected whole; rendered only when exposed

AnkkaServiceStatus
  + hostnames: List[HostnameStatus] = Nil
HostnameStatus(hostname: String, state: String, reason: Option[String] = None)
```

`sameReport` compares `hostnames` too. Declared in `ankkaservice.yaml` (`spec.customHostnames:
array of string`; `status.hostnames: array of {hostname, state, reason}`); `CrdSchemaSuite` reads
into the block.

## The objects the operator renders (per exposed service)

| Object | Name | Namespace | Owner | When |
|---|---|---|---|---|
| `ListenerSet` | `<service>-hostnames` | the project's | the resource | `customHostnames` non-empty; else `RemoveListenerSet` |
| `Certificate` | `<hostname>` | the project's | the resource | one per hostname; `secretName: <hostname>`; label `ankka.thinkmorestupidless.com/hostname-certificate: "true"` (a marker: a label value is at most 63 characters, a hostname 253); `PruneHostnameCertificates` removes marked, owned ones the spec no longer names, and cert-manager's `--enable-certificate-owner-ref` takes each Secret with its Certificate |
| `HTTPRoute` | `<service>` (unchanged) | the project's | the resource | `hostnames: derived :: custom`; `parentRefs`: the Gateway, and the set when non-empty |

The `ListenerSet`:

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: ListenerSet
metadata: { name: cart-hostnames, namespace: ankka-checkout, ownerReferences: [the AnkkaService] }
spec:
  parentRef: { group: gateway.networking.k8s.io, kind: Gateway, name: ankka, namespace: ankka-gateway }
  listeners:
    - name: app.example.com
      hostname: app.example.com
      port: 443
      protocol: HTTPS
      tls:
        mode: Terminate
        certificateRefs: [{ kind: Secret, name: app.example.com }]
      allowedRoutes: { namespaces: { from: Same } }
```

## The status rules (R6)

Inputs per hostname, read by `Fabric8Executor.observeHostnames(namespace, service, hostnames)`:

```
CertificateView(ready: Boolean, issuingFailure: Option[String])
ChallengeView(state: String, reason: Option[String])          // newest Challenge with spec.dnsName == hostname
ListenerView(accepted: (Boolean, String), programmed: (Boolean, String), resolvedRefs: (Boolean, String), conflicted: (Boolean, String))
RouteParentView(accepted: (Boolean, String))                  // the route's status.parents entry for the set
```

`HostnameRules.status(issuer: Option[String], views): HostnameStatus`, first rule that applies:

| Condition | state | reason |
|---|---|---|
| no issuer | `rejected` | `the operator names no authority for custom hostnames (ANKKA_HOSTNAME_ISSUER)` |
| no base domain, or no port | `rejected` | the setting or the reason |
| under the base domain, or a wildcard | `rejected` | `a custom hostname cannot be under the base domain …`; nothing is rendered for it (spike S1: a set's `api.<base>` outranks the Gateway's wildcard) |
| listener `Conflicted` | `rejected` | `<conflict reason>` (e.g. `HostnameConflict`) |
| certificate not ready, a challenge with a reason | `pending` | `waiting for the certificate: <reason>` |
| certificate not ready, no challenge reason | `pending` | `the certificate is being issued` |
| listener not `Accepted`, or the route not `Accepted` on the set | `rejected` | the reason (checked after the certificate: a listener without its certificate yet may say so) |
| listener not `Programmed` or not `ResolvedRefs` | `pending` | `the gateway is attaching the hostname` |
| certificate ready with an issuing failure | `serving` | `renewal refused: <message>` |
| otherwise | `serving` | — |

## The wire (R11)

```
ServiceStatus
  + customHostnames: Vector[CustomHostname] = Vector.empty
  + proofRecord: Option[DnsRecord] = None          // present whenever the service is read

CustomHostname(hostname, state: String = "pending", reason: Option[String] = None,
               record: Option[DnsRecord] = None, note: Option[String] = None)
DnsRecord(name: String, kind: String, value: String)
```

The shared codec omits a field at its default, so a service without hostnames carries neither.
The console's zod mirrors follow, and `ControlPlaneFixturesSuite` writes the fixtures again.

## The proxy (R12)

`Sender.Internet(stated: Option[String])` is unchanged in shape; for a request from the gateway
`stated` is now the request's authority (`Host`, port parsed) rather than `None`. `Headers.inbound`
is unchanged in shape: `stated.orElse(settings.publicAuthority)`.

## Settings

| Variable | Who | Meaning |
|---|---|---|
| `ANKKA_HOSTNAME_ISSUER` | operator, control plane | the `ClusterIssuer` for custom hostnames; absent → refused / `rejected` |
| `ANKKA_DNS_RESOLVER` | control plane | `host:port` for the proof lookup; absent → the system resolver |
| `ANKKA_GATEWAY_ADDRESS` | control plane | the address an apex record points at; absent → the apex note |

None is a service's variable, so `PlatformVariables` is unchanged; `PlatformDeclarationSuite`'s
rule (no hand-kept list of `ANKKA_` literals) holds, as each is one `val`.
