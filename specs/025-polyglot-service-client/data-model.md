# Data Model: Polyglot Service Client

Nothing is stored. No table, no journal event, no snapshot field and no field of the
`AnkkaService` resource is added or changed. What the feature adds is three messages on the wire,
one error, one setting and one kind of span.

## On the wire (protocol 1.7)

Exact shapes are in [contracts/protocol.md](contracts/protocol.md).

### ServiceRequest

| Field | Type | Rule |
|---|---|---|
| `service` | text | a service name: what a descriptor may name a service |
| `project` | optional text | a project id; absent means the calling service's own |
| `method` | text | not empty; sent as given |
| `path` | text | starts with `/`; the query is part of it |
| `headers` | ordered pairs | any; the platform's own are removed before sending (R7) |
| `content_type` | optional text | becomes the request's `Content-Type` |
| `body` | optional bytes | at most 4,000,000 bytes |
| `metadata` | pairs | the calling handler's, forwarded unchanged; names the caller and the trace |

There is no host, no port, no address and no timeout: a request names a service and nothing
that says where it is.

### ServiceReply

Exactly one of:

| Case | Carries | Means |
|---|---|---|
| `response` | status, content type, body (at most 4,000,000 bytes), headers | the service answered |
| `failure` | a reason and a detail | no answer came from the service |
| `error` | a message and a code | the runtime refused to make the call |

### ServiceFailure.Reason

| Reason | Was anything sent | Scala error |
|---|---|---|
| `UNANSWERED` | perhaps | `ServiceUnanswered` |
| `UNRESOLVABLE` | no | `ServiceUnresolvable` |
| `IDENTITY_MISMATCH` | no | `ServiceIdentityMismatch` |

## The errors, in every language

| Error | Fields | Raised by |
|---|---|---|
| unresolvable | service, reason | the raw request and every helper |
| identity mismatch | service, detail | the raw request and every helper |
| unanswered | service, reason | the raw request and every helper |
| call failed | service, status, body | a typed helper only, for an answer outside 2xx |

`service` is `project/name` in Scala, where the client knows its own project; an SDK, which does
not, gives the name as the handler gave it and takes the detail from the runtime.

## The caller of a request

Derived from `metadata`, never stored:

| `ankka-caller` names | Outcome |
|---|---|
| a declared handler of an event sourced or key value entity | refused, naming the kind |
| a workflow's declared handler that is not a step | refused, naming "a step" |
| any other declared handler | the call is made as that handler |
| nothing, or a name the service did not declare | the call is made as the unknown caller |

## Configuration

| Key | Variable | Default | Read by |
|---|---|---|---|
| `ankka.service-client.timeout` | `ANKKA_SERVICE_CLIENT_TIMEOUT` | `30s` | `HttpServiceClients`, in a Scala service and in the sidecar |

`ANKKA_SERVICE_CLIENT_TIMEOUT` joins `PlatformVariables.RuntimeOnlyNames`: a descriptor may give
it, the operator puts it on the platform's container only, and a module is told it is not set.

## What a call leaves behind

Both are in memory, bounded, and existed before for an endpoint's calls.

- **A count**, in the service's topology: from the calling handler (or the unknown caller) to
  the service, by method; handled as ok, refused (4xx) or failed (5xx), or unanswered as timed
  out or undelivered. Unchanged.
- **A span**, new: in the calling handler's trace, under its span, named by the service's
  admitted name and the method, with the same outcome. A call made in no trace leaves no span.
  The names are those the count already interns, so the recorder's table does not grow.
