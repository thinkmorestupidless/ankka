---
title: Error codes
description: The eight error codes a component can refuse with, the HTTP status each becomes, how a refusal travels from a handler to a caller, and how it differs from a failure.
kind: reference
languages: [scala, python]
related: [concepts/effects.md, build/http-endpoints.md, build/component-client.md, reference/control-plane-api.md]
---

# Error codes

A handler that refuses a request returns an error effect carrying a message and an error code. The code
classifies the refusal so that everything downstream can act on it without reading the message: a
calling component receives a typed error, and an HTTP endpoint answers with the matching status without
knowing the rule that was broken.

## The codes

| Scala `ErrorCode` | Python `ErrorCode` | HTTP status | Use it when | Retryable |
|---|---|---|---|---|
| `BadRequest` | `BAD_REQUEST` | `400` | The request is invalid whatever the state: a quantity of zero, a missing field. The default. | no |
| `Unauthorized` | `UNAUTHORIZED` | `401` | The caller is not authenticated. | no |
| `Forbidden` | `FORBIDDEN` | `403` | The caller is authenticated and not allowed. | no |
| `NotFound` | `NOT_FOUND` | `404` | The thing the request is about does not exist. | no |
| `Conflict` | `CONFLICT` | `409` | The request is valid but conflicts with the current state: a cart already checked out. | no |
| `Timeout` | `TIMEOUT` | `504` | The call did not complete in time. The runtime uses it for a call that exceeded its deadline. | yes |
| `Unavailable` | `UNAVAILABLE` | `503` | The target cannot serve right now. The runtime uses it when a component is briefly unreachable, for example while a process-hosted service restarts. | yes |
| `Internal` | `INTERNAL` | `500` | Something is wrong that the caller cannot fix. | no |

A retryable code means a caller could reasonably send the same request again unchanged. In Scala,
`ErrorCode.retryable` answers that.

## Refusing a request

A refusal is a value the handler returns, not an exception it throws. Nothing is persisted for a refused
command, and the runtime does not retry it.

**Scala**

```scala
def addItem(item: LineItem): Effect[Done] =
  if currentState.checkedOut then effects.error("cart is already checked out", ErrorCode.Conflict)
  else if item.quantity <= 0 then
    effects.error(s"quantity must be greater than zero, was ${item.quantity}")
  else effects.persist(ItemAdded(item)).thenReply(_ => Done)
```

**Python**

```python
@command("add-item")
def add_item(self, item: LineItem) -> EventSourcedEffect[ShoppingCart, ShoppingCartEvent, Done]:
    if self.state.checkedOut:
        return self.effects.error("cart is already checked out", ErrorCode.CONFLICT)
    return self.effects.persist(ItemAdded(item)).then_reply(lambda _: DONE)
```

Without a code, an error is `BadRequest`.

## Receiving a refusal

The component client raises the refusal in the caller, with its code intact:

- In Scala, `invoke` throws `com.thinkmorestupidless.ankka.core.CommandError`, which has `message` and
  `code`. `invokeAsync` fails its `Future` with the same exception.
- In Python, `invoke` raises `ankka.client.CommandError`, whose `error` has `message` and `code`.

An HTTP endpoint that lets the error propagate answers with the status in the table and a JSON body:

```json
{"status":409,"error":"cart is already checked out"}
```

An endpoint can also answer with a status of its own: throw `HttpProblem(status, message)` in Scala, with
helpers such as `HttpProblem.notFound`, or raise `ankka.endpoint.HttpProblem(status, message)` in Python.

## Refusals and failures

A refusal is a modelled outcome: the handler decided, on purpose, to say no. A failure is anything else:
a handler that threw, a payload that could not be decoded, a process that did not answer.

| | Refusal | Failure |
|---|---|---|
| Produced by | an error effect | an exception, a crash, a timeout |
| Persists | nothing | nothing |
| Reaches an HTTP caller as | the code's status and the handler's message | `500` with `internal error`, or `400` for an argument that could not be read |
| Shown on a trace as | a refusal | a fault |

Consumers and timed actions differ: a timed action's failure is retried with backoff, and a consumer
that keeps failing does not advance past the message.

## In the sidecar protocol

The protocol carries a refusal as an `Error` message with a `code` from the `ErrorCode` enum in
`payload.proto`, which has the same eight values: `INTERNAL = 0`, `BAD_REQUEST = 1`, `UNAUTHORIZED = 2`,
`FORBIDDEN = 3`, `NOT_FOUND = 4`, `CONFLICT = 5`, `TIMEOUT = 6`, `UNAVAILABLE = 7`. A fault in the
process is a separate `Failure` message, never a refusal. See [Sidecar protocol](sidecar-protocol.md).
