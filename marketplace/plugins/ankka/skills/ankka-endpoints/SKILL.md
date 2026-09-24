---
name: ankka-endpoints
description: Write, change or test an ankka HTTP endpoint in Scala or Python — routes and path templates, typed path parameters and bodies, response encoding, error codes and HttpProblem, query parameters and headers from the request, the ACL (DenyAll, AllowAll, AllowIf, Authenticate), server-sent events, and calling entities, workflows, agents and views from a handler. Use when the task names an endpoint, a route, a REST API, an ACL, authentication of callers, a 4xx status, SSE, HttpServer, or EndpointClients.
---

# ankka HTTP endpoints

An endpoint is the only way into a service from outside. It declares routes under a prefix, turns each
request into component calls and their replies into responses, and holds no state. Every endpoint states
an ACL. Exposing a service changes who can *reach* an endpoint, never who is *allowed* to call it.

## Rules

1. **Endpoints are thin.** Domain validation lives in the entity, whose `effects.error(message, code)`
   becomes the HTTP status with no code in the endpoint: `Conflict` → 409, `NotFound` → 404,
   `BadRequest` → 400, `Unauthorized` → 401, `Forbidden` → 403, `Timeout` → 504, `Unavailable` → 503.
   Let a `CommandError` propagate. Only checks that merely catch mistakes ("does this customer exist?")
   belong here.
2. **`acl` is abstract in Scala; decide it.** `Acl.DenyAll`, `Acl.AllowAll` (state it deliberately; on an
   exposed service this is the internet), `Acl.AllowIf(ctx => Boolean)`, or `Acl.Authenticate(ctx =>
   AuthDecision)` returning `Allow(principal)`, `Unauthenticated(challenge)` (401), `Forbidden(reason)`
   (403) or `Unavailable(reason)` (503). ankka ships no identity-provider check for your services and no
   "same service" principal: a header the client sets is not security. In Python `acl` defaults to
   `ALLOW_ALL`, so set it on every endpoint. Different audiences get different endpoints, because each
   endpoint has one ACL.
3. **Annotate every handler parameter's type.** `get("/{cartId}") { (cartId: String) => ... }`; the
   annotation selects the overload and the parser. Path parameters: `String`, `Int`, `Long`, `Boolean`,
   `UUID`; up to two, in template order, then the body for `postBody`/`putBody`/`patchBody`. A value that
   does not parse is a 400 before the handler runs.
4. **Literal segments outrank parameters**, whatever the declaration order, so `/users/me` never depends
   on being declared before `/users/{id}`. Two endpoints may not share a prefix. Routes are checked at
   startup: a handler whose arity does not match its template fails the service, not the first request.
5. **The return type is the response.** `Done`/`Unit` → 204; `String` → 200 `text/plain` (raw, not a
   JSON string; and a `String` body is read raw); numbers and booleans as text; any type with a
   `JsonValueCodec` → `application/json`. Declare body codecs as private givens with `Codecs.make`.
6. **Query parameters and headers come from `request`, on the handler's thread.** `query.required[A]`
   (absent is a 400 naming it; no silent default), `optional[A]`, `all[A]`, `flag`. `request` also has
   the principal the ACL established. It is a thread-local of the handler's virtual thread: work handed
   to another thread cannot see it, so read what you need first. For a streaming route, read parameters
   while *building* the `Source`, never inside it.
7. **Register with a lambda over `EndpointClients`.** `HttpServer.of(clients =>
   ShoppingCartEndpoint(clients.componentClient))`; `clients.viewClient` for views. Do not shorten it to
   `ShoppingCartEndpoint(_.componentClient)`: the placeholder binds to the inner application.
   `HttpServer.of` binds `0.0.0.0:9000` from configuration; tests use `HttpServer.at("127.0.0.1", 0)` so
   they never collide with a running service. On the platform the port comes from the descriptor and
   `ANKKA_HTTP_PORT` is refused.
8. **Calls block, and that is free.** `invoke` parks a virtual thread; `invokeAsync` fans out. An agent
   call needs an explicit longer await than the 10-second ask timeout. A refusal is a `CommandError`, not
   a default value.
9. **SSE frames are JSON strings.** `sse(template)` answers `GET` and `sseBody` answers `POST` as
   `text/event-stream`; each `data` field is one chunk JSON-encoded, because raw text loses a leading
   space and splits on a newline. Only agents stream.
10. **In Python the process never binds a port.** The sidecar serves the declared routes, applies the ACL
    and forwards. Pass `self.request.metadata` with `with_metadata` so the handler's calls appear under the
    request's trace. `Acl.AUTHENTICATED` answers 503 for now.

## Before writing an endpoint

- Who calls it, and how will they be identified? That is the ACL, and it must be decided before the
  service is exposed.
- Which component answers each route? By id → an entity or workflow; by attributes → a view through the
  view client; a conversation → an agent by session id.
- What can the entity refuse, and does each refusal map to the status the client expects? Change the
  `ErrorCode` in the entity, not the endpoint.
- Is any input structural (path, body) or per-call (query, header)? Structural inputs are typed
  arguments; per-call inputs are read from the request.

## Testing

Run the service with `AnkkaTestKit`, register the server with `HttpServer.at("127.0.0.1", 0)`, and call
the bound port with an HTTP client. Assert on statuses and bodies, including the status a refusal
produces and the SSE encoding of a streaming route. A route's arity mismatch shows up at start, so a
test that starts the service covers every route's shape.

## Mistakes to check for

- `try`/`catch` and status mapping in a handler; a `CommandError` swallowed into a default.
- An unannotated handler parameter, or a handler reading `request` inside a `Source` or a `Future`.
- `Acl.AllowAll` chosen by default rather than on purpose; an authentication check on a plain header.
- A test binding port 9000.
- A `POST` whose `String` body was expected as a JSON string, or a client calling `.json()` on a
  `text/plain` reply.

## Reference files

Open the one a task needs; each is one topic and stands alone.

### Concepts

- `references/concepts/tenancy-and-access.md` — How organizations, projects and services divide an installation, who may operate each of them, and how the identity provider and the control plane share the work of authentication and authorization.

### Build

- `references/build/views.md` — Build a queryable projection of an entity's or a topic's changes, keep one row per source id, and query the rows with SQL in Scala or by key in Python.
- `references/build/streaming.md` — Stream an agent's reply token by token to a caller and over HTTP as server-sent events, and know what streaming changes about guardrails and sessions.
- `references/build/http-endpoints.md` — Expose a service over HTTP — routes, typed path parameters and bodies, responses, errors, query parameters and headers, access control and server-sent events — in Scala or Python.
- `references/build/component-client.md` — Call entities, workflows and agents through the component client — blocking or asynchronous, with typed refusals and timeouts — and query views through the view client.
- `references/build/testing.md` — Test ankka components at two levels in Scala and Python — unit test kits that run a component with nothing else, and integration test kits that run the whole service against a real database — with scripted models for agents.

### Run and deploy

- `references/deploy/expose.md` — Make a deployed service reachable from outside the cluster at its platform-derived HTTPS hostname, understand why the hostname has the shape it does, and remove the route again.

### Reference

- `references/reference/error-codes.md` — The eight error codes a component can refuse with, the HTTP status each becomes, how a refusal travels from a handler to a caller, and how it differs from a failure.
