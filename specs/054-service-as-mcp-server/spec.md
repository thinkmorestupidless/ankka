# Feature Specification: A Service as an MCP Server — Tools Any Assistant Can Call, With Client Keys Scoped to a Caller

**Feature Branch**: `054-service-as-mcp-server`

**Created**: 2026-10-19

**Status**: Implemented

**Input**: User description: "A service as an MCP server. A service declares which of its
commands, queries and endpoint routes are tools, and the platform serves them to any MCP client
over Streamable HTTP, with keys scoped to a caller, so that a customer's assistant (Claude, a
coding agent, a product's own assistant) can act on a service without the product hand-rolling
JSON-RPC, keys and an OAuth server. The need comes from ankka-for-plumbers spec 007 (an
Organisation's assistant books and reads jobs through MCP with a scoped key), which replaces
DumaFlow's hand-rolled MCP surface. This is the server side; feature 029 already made agents MCP
clients, and `ankka mcp` already serves the CLI's tools to coding agents."

## Context

ankka speaks the Model Context Protocol in two places today, and in neither is a service the
server. An agent lists MCP servers and the platform connects to each when the service starts,
over Streamable HTTP, offering their tools to the model as `mcp__<server>__<tool>`
(`modules/agent/mcp`, feature 029; `docs/build/mcp-servers.md`). And `ankka mcp` is a server over
standard input and output, built into the CLI, that gives a coding agent the control plane's verbs,
the services on this machine and this version's documentation (`cli/mcp`,
`docs/get-started/coding-agents.md`). A service's own surface to the outside is its HTTP endpoint,
whose routes turn requests into calls to components, and its gRPC endpoint, for Scala services.

A product built on ankka has customers, and its customers have assistants. A plumbing business
wants to say "book Mrs Patel for Thursday morning" to Claude and have the job appear in its
schedule; a developer wants their coding agent to read the service it is building through the same
tools the service's users have. The protocol those assistants speak is MCP over HTTP, and what
they need from the service is a list of tools with typed arguments, a way to call each, and a
credential that says who is calling. Today a product builds every part of that itself. DumaFlow
did: a JSON-RPC handler, a table of keys, an OAuth server, and the glue between them, none of it
the product's business and all of it security-sensitive code written once per product. A service
can also only be *given* tools by an agent of another ankka service through code: an agent that
wants a sibling's commands as tools wraps them by hand, though the sibling already knows what they
are.

Everything the platform already does for an HTTP endpoint is what an MCP server needs, with two
things it does not have. It has no component that speaks the protocol, and it has no credential a
service can hand to a caller of its own. The decisions below add exactly those two and change
nothing else.

- **An MCP endpoint is a component, beside the HTTP and gRPC endpoints.** A service declares an
  **MCP endpoint**: a path, a name and version to answer the protocol's handshake with, an ACL it
  must state as every endpoint must, and its **tools**. A tool has a name, a description the model
  reads, typed arguments whose schema is derived from the argument type exactly as an agent's own
  tool's is (`SchemaType`), and a handler, which is ordinary endpoint code: it runs on a virtual
  thread, calls entities, views, workflows and agents through the component client and other
  services through the service client, and answers a value that becomes the tool's result. Where
  an HTTP route is `POST /carts/{cartId}/items`, a tool is `add_item(cartId, item)`. The same
  component with a different front: the way a gRPC endpoint uses `Acl`, `Caller`, `Principal` and
  `EndpointClients` unchanged (`.claude/rules/grpc.md`), the MCP endpoint does too.
- **A tool can also be a reference: an existing command, query or route, named in one line.** Most
  tools a product wants are its handlers as they stand: `get_cart` is the cart's `get-cart` query,
  `add_item` its `add-item` command. An MCP endpoint declares such a tool by naming the component's
  handler, or the HTTP endpoint's route, and writing its description beside it; the arguments are
  the handler's input type with the entity's or workflow's id added under a name the declaration
  gives (`cartId`), or the route's path names and body, and the result is the handler's reply or
  the route's response. Nothing runs between the client and the handler but the platform: the
  reference is resolved at startup, so a handler or route that does not exist fails the start
  naming it, and a renamed wire name is caught before any client sees it. A handler written for an
  MCP endpoint and a reference are offered to a client identically, and a service may mix them. The
  description is required either way: a model reads it, and a wire name is not a description.
- **The protocol is served on the service's HTTP port, at the endpoint's path.** Streamable HTTP
  is HTTP: one path answering `POST` with the protocol's messages and `GET` for a stream. The
  endpoint is mounted on the service's existing HTTP server, so a service has no third port and no
  third protocol (`docs/reference/limitations.md`, "Two protocols"), and everything that is true of
  a route is true of the endpoint without a line of new platform code: exposure and the derived
  hostname, custom hostnames, the gateway as the caller from the internet, mutual TLS and the
  certificate as the caller inside the cluster, grants across projects, rollouts, readiness,
  tracing. A paused service's MCP endpoint answers nothing, as its routes do. The default path is
  `/mcp`; a service with two MCP endpoints gives them two paths, and an endpoint whose path a route
  also claims is refused at startup, as two routes to one path are.
- **The server is stateless, so any instance answers any request.** Streamable HTTP lets a server
  decline to issue a session id, and this one does: each request stands alone, carries its caller's
  credential, and is answered by whichever instance the Service routed it to. There is no session
  affinity to build and nothing to lose when an instance stops. Tool results are answered as one
  JSON response; the stream `GET` opens is accepted and closed, since the server has nothing to
  push. A tool call runs its handler as a request runs a route, and takes as long.
- **Tools only, as the client side chose.** Feature 029 took tools and left resources and prompts;
  so does this. A tool's error — the handler throwing, or answering a refusal — is the protocol's
  tool error, carrying the message, and the client's model reads it; a protocol error (an unknown
  tool, bad arguments against the schema) is the protocol's error response. The arguments the
  client sends are checked against the schema before the handler sees them, and the handler reads
  typed values, never JSON.
- **The ACL decides who may call, and it already knows three kinds of caller.** The endpoint's ACL
  is any ACL an HTTP endpoint may state. `allowCallers` admits workloads by certificate, so an
  agent of a sibling service that lists this endpoint as `McpServer.service(...)` (feature 029) is
  admitted by name with no credential at all: the client half and the server half meet, and a
  service's agent gets a sibling's tools by listing it. `Oidc.authenticate()` admits a person's
  token from one of the service's listed issuers, as an authenticated route does. And a new
  authenticator, below, admits a client key. A tool's handler reads `principal` as a route's does,
  and `tools/list` shows a caller only the tools its principal may call, so a model is never
  offered what it will be refused.
- **A client key is a credential the service issues to one caller, for named tools.** A product
  knows who its customers are and the platform does not, so the product's own code decides when a
  key is due: an owner of a plumbing Organisation presses "connect my assistant" in the product's
  interface, the product's endpoint, having authenticated the owner its own way, asks the platform
  for a **client key** scoped to that Organisation and to the tools an assistant may use, and
  shows the secret once. The platform holds the key as a digest in the service's own database,
  with its subject, its tools, its attributes, a label, an expiry and the time it was last used;
  the service lists and revokes its keys through the same client; a revoked key is refused on its
  next request on every instance. A key's **attributes** are a few named string values the service
  attaches at issue — who the key is for, what role they hold, which plan they are on — which the
  handler reads as the principal's claims, exactly as it reads a token's; the platform stores and
  shows them and never interprets them. A request carrying a key is a request whose principal is the key's subject, so a
  tool handler scopes every call to that subject exactly as it would for a person's token, and a
  key that names tools is shown and allowed those tools and no other. Keys are the platform's
  because every product would otherwise write the same digest table, the same constant-time
  compare and the same revocation index, and get one of them wrong. The platform still issues no
  token for a service's users: a key is not a statement of who a person is, it is a capability
  the service granted, and the service's identity provider is still where people are.
- **A client that only speaks OAuth is sent to the service's issuer by default, and the platform
  brokers the sign-in when the service asks it to.** The protocol's authorization flow has a server
  tell a client where to get a token: a `401` naming the resource's metadata, and that metadata
  naming the authorization servers. An MCP endpoint that admits tokens publishes that metadata
  naming the service's own issuers, and a client that does OAuth goes to the product's identity
  provider and comes back with a token the endpoint already verifies; the platform runs nothing and
  registers no client, and whether the issuer admits a client it has never seen is the product's
  setting. Many issuers do not, and the assistants people actually use register themselves, so a
  service may opt into **brokered sign-in**: the platform then runs, for that service's MCP
  endpoints and at that service's own hostnames, the authorization server the metadata names. It
  registers a client that presents itself, sends the person to the service's issuer to sign in and
  takes the issuer's word for who they are, and mints a token for this service alone, signed with
  keys held in the service's database, which the endpoint verifies as it verifies any token. The
  sign-in broker signs nobody in itself and holds no password: the issuer is still where people are, and
  the token's subject is the subject the issuer gave. The platform still issues no token for a
  service's users except through a sign-in broker that service turned on, which is the one departure this
  feature makes from the line feature 022 drew. Client keys are for callers that carry a header — a
  coding agent, a script, a product's own assistant — and tokens for callers that sign in; a
  product that wants only one of the two states only that in its ACL.
- **Python, TypeScript and Rust services declare an MCP endpoint, and the platform speaks the
  protocol for them.** Behind the sidecar the HTTP server is the platform's, and the process is
  asked to run a route's handler; an MCP endpoint is the same: the sidecar answers the protocol,
  checks arguments against the schema the process declared, verifies the credential, and asks the
  process to run the tool with typed arguments, under a minor bump of the protocol version. The
  process never sees a key's digest or a token's signature, as it never sees a token today. Access
  keys are issued and revoked through the process's client, as it calls components.
- **Everything that shows a service shows its tools.** `services get`, the console and the local
  console list an MCP endpoint's path and tools beside the service's routes; `ankka mcp`'s
  `describe_local_service` names them, so a coding agent that is building the service can read
  what the service offers and call it. A tool call is a span under the endpoint and the tool, as a
  request is under its route, with the principal's subject and never a key's secret or a token.

What this feature is not: it is not MCP resources, prompts, sampling or elicitation, only tools; it
is not a server with sessions or server-sent notifications, which a stateless server has no need
of; it is not an identity provider — the sign-in broker holds no account and no password, and a service
that does not opt in has the platform issue no token and register no client; it is not a key for
the control plane, which has deploy
tokens; it is not a transport other than Streamable HTTP, nor the protocol on the gRPC port; it is
not one door for a whole project — an MCP endpoint that lists every sibling service's tools as
they come and go and forwards each call to its owner on behalf of the customer, which is spec 055
and needs a trust relation this feature does not build; and it is not a change to `ankka mcp`,
which keeps serving the CLI's tools over standard input and output.

## Clarifications

### Session 2026-10-19

- Q: How are tools declared: handlers only on a new MCP endpoint component, both handlers and
  one-line declarations that make an existing command, query or route a tool, or references only?
  → A: Both. A tool is a handler, or a reference to a command, query or route with a description
  written beside it and the entity's id taken from a named argument.
- Q: Who answers an OAuth client's sign-in: the service's own issuer only, the platform as the
  authorization server, or both? → A: Both. By default the resource metadata names the service's
  own issuers and the platform runs nothing; a service may opt into brokered sign-in, where the
  platform registers the client, sends the person to the service's issuer to sign in, and mints the
  token the MCP endpoint verifies.
- Q: What does a handler read from a client key: the subject only, the subject plus attributes
  the service attached at issue, or the subject plus roles? → A: Subject plus attributes: a small
  set of named string values the service attaches when it issues the key, read by the handler as
  the principal's claims, opaque to the platform and shown in the listing.
- Q: Is "client key" the right name for the credential, against "tool key", "assistant key" or
  "service token"? → A: Yes, "client key". "Access key" is already the refused synonym of a
  storage credential, "token" is a signed statement from an issuer, and a key alone is a message's
  key on a topic. Refused for this: API key, access key, MCP key, assistant key, tool key.
- Q: How long does a client key live by default: required with no default, deploy tokens' 90 days
  with a cap of a year, or required with a cap and never forever? → A: Required, no default. The
  service gives a duration or an instant, or says explicitly that the key never expires; a key
  issued with none of the three is refused.
- Q: Should this feature include one MCP server for a project, whose advertised tools change as
  sibling services register and which forwards a customer's call to the service that owns the
  tool? → A: No; the principle is wanted and goes to spec 055, shaped so this feature does not
  preclude it. Client keys stay the issuing service's own; a product that wants one door today
  writes a front service whose tools forward to its siblings in its own code. In ankka the
  "registration" is already the platform's knowledge of each service's report, so 055 derives the
  list rather than taking announcements; a call is forwarded as one in-cluster hop, never handed
  off to a second connection, since MCP cannot express that and no client would follow it; and
  the new thing 055 must build is a call made on behalf of a principal the front door verified,
  which the owning service admits from a named workload.

### Decided while implementing, 2026-10-19

- A tool call is a span of its own under the request's span (component `mcp`, handler
  `<path> <tool>`), carrying the principal's subject and a client key's id as `client_key`, never
  the key.
- The topology reports each tool as a handler of type `tool` on the MCP endpoint's node; the
  console and `services topology` mark it "(tool)", and the local console lists tools without
  offering to call them.
- The conformance reference issues keys at `POST /mcp-keys`, lists them at `GET /mcp-keys` and
  revokes them at `DELETE /mcp-keys/{id}`; the case that a key is listed without the key itself was
  added to hold the listing in every SDK.
- A runtime at protocol 1.19 is refused by an SDK that declares an MCP endpoint, after every older
  reason, so an older runtime is still told the oldest thing it lacks.
- Brokered sign-in's deviations from the plan are listed in `.claude/rules/mcp.md`: an instance
  re-reads the broker's keys on an unknown key id, the token rate limit is per node, and the
  issuer client does not read the issuer's CA.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A service offers its tools to any assistant (Priority: P1)

A developer adds an MCP endpoint to the shopping cart service at `/mcp` with the tools `get_cart`,
`add_item` and `checkout`, each with a description and typed arguments, each handler calling the
cart entity through the component client. They run the service locally, point Claude Code at
`http://localhost:9000/mcp`, and ask it what is in cart `c1`. Claude lists the three tools, calls
`get_cart` with `{"cartId": "c1"}`, and reads the cart. Asked to add a book, it calls `add_item`;
asked to add a quantity of zero, it is told the tool's error in words the handler chose. A request
for a tool that does not exist, or with an argument the schema does not admit, is a protocol error
that names what was wrong. The derived hostname, exposure and a custom hostname serve the endpoint
as they serve a route.

**Why this priority**: This is the feature. A product that cannot offer tools over the protocol
builds its own server, and everything else here is a consequence of the endpoint existing.

**Independent Test**: In the test kit, start a service declaring an MCP endpoint and drive it with
the platform's own MCP client (the one agents use): assert `initialize` names the endpoint,
`tools/list` returns the three tools with the schemas derived from their argument types, a
`tools/call` of `get_cart` reaches the entity and answers its state as the tool's result, a
handler's refusal is a tool error carrying its message, and an unknown tool and a bad argument
are protocol errors. In the k3s suite, deploy the sample, expose it and call `tools/list` through
the gateway at the derived hostname.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/mcp-endpoints/tools.feature`: a service's MCP endpoint lists the tools it declares, with each tool's description and arguments
- added `features/mcp-endpoints/tools.feature`: a tool call runs the tool's handler with typed arguments and answers its result
- added `features/mcp-endpoints/tools.feature`: a tool's handler that refuses answers the caller a tool error carrying the reason
- added `features/mcp-endpoints/tools.feature`: a call to a tool the MCP endpoint does not have is a protocol error naming the tool
- added `features/mcp-endpoints/tools.feature`: a tool call whose arguments do not match the tool's schema is refused before the handler runs
- added `features/mcp-endpoints/tools.feature`: a stream an MCP client opens on an MCP endpoint is closed at once, since the MCP endpoint pushes nothing
- added `features/mcp-endpoints/tools.feature`: an MCP endpoint's path cannot also be a route's path
- added `features/mcp-endpoints/tools.feature`: a tool the MCP endpoint cannot declare is refused at startup, naming the MCP endpoint and the tool
- added `features/mcp-endpoints/tools.feature`: a request to an MCP endpoint is answered by whichever instance receives it
- added `features/mcp-endpoints/tools.feature`: an MCP endpoint is served at every hostname the service answers at
- added `features/mcp-endpoints/tools.feature`: a paused service's MCP endpoint answers nothing

---

### User Story 2 - A product hands its customer's assistant a client key (Priority: P1)

A plumbing product's owner, signed in to the product, asks to connect their assistant. The
product's endpoint, which knows the owner belongs to Organisation `acme`, asks the platform for an
client key with subject `acme`, scoped to `list_jobs`, `book_job` and `reschedule_job`, labelled
"Alice's assistant", with the attributes `person=alice` and `role=owner`, expiring in a year. The
product shows the secret once. The owner gives it to their assistant as a header. The assistant
lists tools and sees three, not the office's `close_books`; it calls `book_job` and the handler
reads `principal.subject` as `acme` and the claim `person` as `alice`, so the job is booked in
acme's schedule and nobody else's, and recorded as booked by Alice. The product's listing shows the key, when it was
last used and when it expires. The owner's assistant is replaced; the product revokes the key, and
the assistant's next request is refused on every instance within a second.

**Why this priority**: Without a credential of its own a product keeps its OAuth server and its key
table, which is the half of DumaFlow this feature exists to remove. It is P1 beside the endpoint
because a tool surface with no way to scope a caller is one a product cannot turn on.

**Independent Test**: In the test kit, from a route's handler issue a key through the client with a
subject and two of three tools; call `tools/list` with it and assert two tools; call an admitted
tool and assert the handler's principal is the subject; call the third and assert the refusal
names the tool; list the keys and assert the label, expiry and last use; revoke and assert the next
call is refused. Restart the service and assert the key still works, then that an expired key is
refused naming expiry.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/mcp-endpoints/client-keys.feature`: a service issues a client key for a subject and is shown it once
- added `features/mcp-endpoints/client-keys.feature`: a request carrying a client key runs the tool as the client key's subject
- added `features/mcp-endpoints/client-keys.feature`: a handler reads a client key's attributes as the principal's claims
- added `features/mcp-endpoints/client-keys.feature`: a client key's attributes beyond what the platform keeps are refused at issue
- added `features/mcp-endpoints/client-keys.feature`: a client key scoped to tools is shown those tools and no other
- added `features/mcp-endpoints/client-keys.feature`: a tool call outside a client key's scope is refused, naming the tool
- added `features/mcp-endpoints/client-keys.feature`: a client key scoped to no tool may call every tool of the MCP endpoint
- added `features/mcp-endpoints/client-keys.feature`: a client key that cannot be issued is refused, naming what is wrong
- added `features/mcp-endpoints/client-keys.feature`: a service lists its client keys with each one's label, expiry and last use, and never the client key itself
- added `features/mcp-endpoints/client-keys.feature`: a revoked client key is refused on its next request on every instance
- added `features/mcp-endpoints/client-keys.feature`: a client key issued as never expiring is admitted after any time
- added `features/mcp-endpoints/client-keys.feature`: an expired client key is refused, saying it expired
- added `features/mcp-endpoints/client-keys.feature`: a client key survives the service restarting
- added `features/mcp-endpoints/client-keys.feature`: a client key of one service opens nothing on another
- added `features/mcp-endpoints/client-keys.feature`: a client key appears in no log, trace or listing

---

### User Story 3 - Who may call is the ACL's decision, in three kinds (Priority: P2)

An MCP endpoint states its ACL as every endpoint must. One product admits client keys only. A
second admits tokens from its issuer as well: a client that speaks the protocol's authorization
flow receives a `401` naming the resource metadata, reads the service's issuer from it, signs in
there, and comes back with a token the endpoint verifies; the handler reads the token's subject.
A third, in the same project as a support service, admits that service by name: the support
service's agent lists it as an MCP server and is offered its tools with no credential, because its
certificate is the credential. An endpoint that states no ACL does not start.

A fourth product's issuer does not register clients it has never seen, and its customers connect
from an assistant that can only sign in. The product opts its MCP endpoint into brokered sign-in.
The assistant reads the resource metadata, finds the platform's sign-in broker at the product's own
hostname, registers itself, and is sent to the product's issuer, where the customer signs in as
they always do. The sign-in broker mints a token for this service and the assistant comes back with it;
the handler reads the customer's subject as the issuer gave it. A token the sign-in broker minted opens
nothing on another service, and a service that has not opted in has no sign-in broker.

**Why this priority**: The client key alone serves the P1 product. Tokens and certificates make
the same endpoint serve a signed-in person's assistant and a sibling service's agent without the
product choosing one audience, and they cost nothing new: both authenticators exist. Brokered
sign-in is the part that costs something, and it is what lets the assistants people have, which
register themselves and carry no header, reach a product whose issuer will not register them.

**Independent Test**: In the test kit, with the module's test issuer, declare three endpoints with
the three ACLs: assert a key is refused at the token-only endpoint with a `401` whose challenge
names the metadata, and that the metadata names the issuer; assert a token from the issuer is
admitted and the principal is its subject; assert an agent of a second service in the kit that
lists the third endpoint as a service is offered its tools and calls one, and that a key at that
endpoint is refused. Assert an endpoint without an ACL fails the start naming it. For brokered
sign-in, opt a fourth endpoint in and drive the flow with the platform's own MCP client against the
module's test issuer: assert the metadata names the sign-in broker at the service's hostname, that a client
registers and is sent to the test issuer, that the token the sign-in broker mints is admitted with the test
issuer's subject as the principal, that the same token is refused at another service's endpoint,
and that a service not opted in answers nothing where the sign-in broker would be.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/mcp-endpoints/brokered-sign-in.feature`: a service that opts into brokered sign-in names the platform's sign-in broker in its resource metadata
- added `features/mcp-endpoints/brokered-sign-in.feature`: an MCP client registers itself with the sign-in broker and is sent to the service's issuer to sign in
- added `features/mcp-endpoints/brokered-sign-in.feature`: a token the sign-in broker mints runs the tool as the subject the service's issuer gave
- added `features/mcp-endpoints/brokered-sign-in.feature`: a token the sign-in broker minted for one service opens nothing on another
- added `features/mcp-endpoints/brokered-sign-in.feature`: a person the service's issuer does not sign in gets no token from the sign-in broker
- added `features/mcp-endpoints/brokered-sign-in.feature`: a service that has not opted into brokered sign-in has no sign-in broker
- added `features/mcp-endpoints/brokered-sign-in.feature`: the sign-in broker holds no password and the service's issuer is never asked for one
- added `features/mcp-endpoints/access.feature`: an MCP endpoint must state its ACL
- added `features/mcp-endpoints/access.feature`: a request with no credential at an MCP endpoint that admits tokens is told where to get one
- added `features/mcp-endpoints/access.feature`: an MCP endpoint that admits tokens publishes the issuers a client may sign in with
- added `features/mcp-endpoints/access.feature`: an MCP endpoint that admits no token publishes no issuer
- added `features/mcp-endpoints/access.feature`: a request carrying a token from one of the service's issuers runs the tool as the token's subject
- added `features/mcp-endpoints/access.feature`: an MCP endpoint that admits named workloads admits them by certificate and asks for no credential
- added `features/mcp-endpoints/access.feature`: a credential an MCP endpoint's ACL does not admit is refused
- added `features/mcp-endpoints/access.feature`: a tool's own ACL replaces the MCP endpoint's for that tool
- changed `features/agents/mcp-servers.feature`: the platform connects to an MCP server that is a service as the agent's service
- added `features/agents/mcp-servers.feature`: an agent is offered the tools of a sibling service's MCP endpoint by listing the service

---

### User Story 4 - A Python, TypeScript or Rust service is an MCP server too (Priority: P2)

A developer writing the plumbing service in Python declares an MCP endpoint class with three tools,
each a function of typed arguments, and an ACL admitting client keys. The platform's container
answers the protocol, verifies the key, checks the arguments and asks the process to run the tool;
the process reads the principal's subject and calls the entity. The process issues and revokes
keys through its client. The TypeScript and Rust services do the same in their idiom, and the
conformance suite proves the three against the Scala reference.

**Why this priority**: Every ankka capability reaches every language or the limitations page says
why. It is P2 because the Scala endpoint proves the design and the protocol change rides on it.

**Independent Test**: The conformance suite gains an MCP endpoint in each language's example
service; the Scala reference is driven first, then each SDK's, with the same requests and the
same expected answers, including a key issued from a tool and used on the next request.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/mcp-endpoints/languages.feature`: a Python, TypeScript or Rust service declares an MCP endpoint and the platform answers the protocol for it
- added `features/mcp-endpoints/languages.feature`: a service's own code is asked to run a tool with typed arguments and the principal, and never sees the credential
- added `features/mcp-endpoints/languages.feature`: a service's own code issues, lists and revokes client keys through its client
- added `features/polyglot/conformance.feature`: an SDK's MCP endpoint answers as the Scala reference does

---

### User Story 5 - What a service offers is visible, and each call is traced (Priority: P3)

A member runs `services topology` on the deployed plumbing service and sees `/mcp` with its three
tools beside the routes. The console's topology page shows the same. On a developer's machine the local console lists the
tools, and a coding agent's `describe_local_service` names them, so the agent calls the service's
own tools while building it. A tool call appears in the local console's traces under the endpoint
and the tool, with the principal's subject, and the call it made to the entity under that.

**Why this priority**: Nothing works differently with it; it is what makes the endpoint operable and
the coding-agent loop close. It is last because every story above can be proved without it.

**Independent Test**: In the test kit, assert the descriptor the service reports names the MCP
endpoint's path and tools; call a tool and assert the recorder shows a span for the endpoint and
tool with the subject, a child span for the entity call, and no secret anywhere. In the control
plane's tests, assert `services topology` lists the endpoint and its tools beside the routes from a
deployed service's report.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/mcp-endpoints/observing.feature`: a service's report names its MCP endpoints and their tools
- added `features/mcp-endpoints/observing.feature`: a tool call is traced under its MCP endpoint and tool with the principal's subject
- added `features/local-console/services.feature`: the local console lists a service's MCP endpoints and their tools
- added `features/documentation/mcp-endpoints.feature`: a coding agent is told a local service's tools
- added `features/documentation/mcp-endpoints.feature`: the documentation describes declaring an MCP endpoint, its ACL and its client keys in every language
- added `features/documentation/mcp-endpoints.feature`: the documentation says what an MCP endpoint does not do
- added `features/documentation/mcp-endpoints.feature`: the documentation says how ankka's MCP endpoint differs from Akka's

---

### User Story 6 - A command, query or route becomes a tool in one line (Priority: P2)

The plumbing service already has a `Job` entity with `book` and `reschedule` commands and a
`get` query, and a route `GET /jobs/today`. Its developer declares each as a tool of the MCP
endpoint with one line and a description: `book_job` is `Job.book` with the job's id as `jobId`,
`todays_jobs` is the route. An assistant lists them beside the hand-written tools and cannot tell
which is which; a call to `book_job` reaches the `Job` entity's command with the id and the
command's input as the model sent them, and the model reads the command's reply. A reference to a
handler the service does not have fails the start, naming it.

**Why this priority**: It is what makes the endpoint cheap for a product whose handlers already
exist, and it keeps the tool and the handler from drifting apart. It is P2 because every reference
can be written as a handler of a few lines until it lands.

**Independent Test**: In the test kit, declare one tool by reference to an entity command, one to
an entity query, one to a workflow command and one to a route; assert `tools/list` shows each with
its description and a schema that is the handler's input with the id argument added, or the
route's path names and body; assert a call reaches the handler with the id and input and answers
its reply; assert a reference to a wire name the component lacks, and one with no description, fail
the start naming the tool.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/mcp-endpoints/tools.feature`: a command of an entity declared as a tool is called with the entity's id taken from a named argument
- added `features/mcp-endpoints/tools.feature`: a query of an entity declared as a tool answers what the query answers
- added `features/mcp-endpoints/tools.feature`: a route declared as a tool takes the route's path names and body as its arguments
- added `features/mcp-endpoints/tools.feature`: a tool declared by reference is offered as a tool with a handler is
- added `features/mcp-endpoints/tools.feature`: a tool declared by reference to a handler or route the service does not have is refused at startup
- added `features/mcp-endpoints/tools.feature`: a tool declared by reference without a description is refused at startup

---

### Edge Cases

- A tool whose name is not lower-case letters, digits, underscores and hyphens, or two tools under
  one name, or a tool with no description: refused at startup, naming the endpoint and the tool,
  as a route with a bad path is. The name rule is the one the agent side already holds for tool
  names, so a tool of this endpoint can be offered by an agent as `mcp__<server>__<tool>`.
- An argument type whose schema cannot be derived: refused at startup, as an agent tool's is.
- A client sends a protocol version the server does not speak: the handshake answers the version
  it does, as the protocol prescribes, and a client that cannot proceed stops there.
- A client sends a session id: ignored; the server issued none and answers the request on its
  own. A client that cannot work without one is told so by the handshake's lack of an id.
- A `GET` for the server's stream: refused at once with the protocol's answer for a server that
  offers no stream, so a client that opens one does not hang and does not retry.
- A tool call whose handler runs longer than the request timeout: the request ends as a route's
  does and the handler's call completes or fails on its own; the client sees the timeout. A tool
  that does long work starts a workflow and answers its id.
- A client key presented at a route, not the MCP endpoint: admitted only if that route's ACL asks
  the key authenticator; a key is not a token and `Oidc.authenticate()` refuses it.
- A key issued with no tools: scoped to every tool of the endpoint, now and later; a key that
  names a tool the endpoint does not have is refused at issue, naming it.
- A key whose subject is blank, whose expiry is in the past, or whose expiry is not given at all:
  refused at issue. There is no default lifetime; a key that is to last says so, as a duration, an
  instant, or explicitly never.
- Two keys with one label: allowed; the label is for people, the id is the key's.
- A key presented after the service's issuers changed: unaffected; a key is the service's, not an
  issuer's.
- A key issued on one instance and used on another a moment later: admitted; the issue is written
  before the secret is shown, and verification reads the written state.
- Revocation under a rolling update: the revoke is an event on the key, so an instance of either
  version refuses it; an instance that has not yet seen the event admits it for at most the
  propagation second this feature promises.
- A secret guessed: digests are compared in constant time, and a refusal says nothing about
  whether the id exists. No lockout: the gateway does nothing but route, and rate limiting stays
  out of scope.
- A `tools/list` with a key scoped to tools of which one has since been removed from the code: the
  list shows what exists; a call to the removed one is a protocol error naming the tool.
- The service's database is restored from a backup: keys issued after the backup are gone, and
  their holders are refused as unknown; that is what a restore means.
- An erasure of the subject's personal data: a key's subject is an opaque id the service chose;
  if the service erases a subject's entities it revokes its keys too, through the client. The
  platform does not know what a subject is.
- A Python or TypeScript process declares a tool whose handler is missing when the sidecar asks:
  the call is a tool error naming the tool, and the service's report notes the handler.
- A module (Rust) tool that traps: a tool error, as a route handler's trap is a `500`.
- The service's issuer cannot be reached while the sign-in broker is sending a person to it: the sign-in
  fails as any sign-in at an unreachable issuer does, the client is told so, and no token is
  minted. The sign-in broker keeps nothing of the attempt.
- A client registers with the sign-in broker and never signs in: the registration is kept for a bounded
  time and forgotten; nothing was minted. The platform does not count or limit registrations
  beyond that, as it does not rate-limit anything else at the service.
- The sign-in broker's keys are rotated while a token it minted is still valid: the token stays valid
  until it expires, as the control plane's machine tokens do across their key rotation.
- A service opts out of brokered sign-in: the metadata stops naming the sign-in broker, no new token is
  minted, and tokens already minted are refused, since the keys that verify them are the sign-in broker's.
- A WebAssembly module's MCP endpoint admits keys and tokens as a process's does, since the
  platform's container holds both; what the module cannot do, as before, is be an agent that
  lists MCP servers.

## Requirements *(mandatory)*

### Functional Requirements

**The endpoint**

- **FR-001**: A service MUST be able to declare an MCP endpoint: a path on the service's HTTP port
  (default `/mcp`), a server name and version for the protocol's handshake, an ACL it MUST state,
  and its tools. A service MAY declare more than one, at distinct paths. A path that another MCP
  endpoint or a route also claims MUST be refused at startup, naming both.
- **FR-002**: A tool MUST have a name of lower-case letters, digits, underscores and hyphens,
  unique on its endpoint; a description; typed arguments whose JSON Schema is derived from the
  argument type as an agent's own tool's is; and a handler that receives the typed arguments and
  answers a value, which becomes the tool's result as text or structured content. A tool MAY state
  an ACL of its own, which replaces the endpoint's for that tool. Two tools under one name, a
  tool with no description, a name outside the rule or an argument type with no schema MUST be
  refused at startup, naming the endpoint and the tool.
- **FR-002a**: A tool MUST also be declarable by reference: to a command or query of an entity or
  workflow by its wire name, with the name under which the client passes the id, or to a route of
  one of the service's HTTP endpoints. Its arguments MUST be the handler's input type with the id
  added, or the route's path names and, when the route takes one, its body as one JSON argument;
  its result MUST be the handler's reply or the route's response; its description MUST be written
  in the declaration. A reference MUST be resolved at
  startup, and one naming a handler or route the service does not have, or lacking a description,
  MUST fail the start naming the endpoint and the tool. A client MUST see no difference between a
  tool declared by reference and one with a handler.
- **FR-003**: The endpoint MUST answer the protocol's Streamable HTTP transport at its path: a
  `POST` carrying `initialize`, `tools/list` and `tools/call` and the notifications a client sends,
  and a `GET` for a stream, which it MUST refuse with the protocol's answer for a server that
  offers no stream (`405`, naming the methods it allows), since it pushes nothing. It MUST issue no
  session id and MUST answer each request from whichever instance receives it, with no affinity. It
  MUST answer the protocol version it speaks when a client names another.
- **FR-004**: `tools/call` MUST check the arguments against the tool's argument types, which the
  schema describes, before the handler runs, and answer a protocol error naming the argument that
  was wrong when they do not match; MUST answer a
  protocol error naming the tool when the tool does not exist or the caller may not call it; MUST
  run the handler on a virtual thread with the component client and the service client, as a
  route's handler is run, and answer its value as the tool's result; and MUST answer a handler
  that throws or refuses as the protocol's tool error carrying the message, never as a transport
  error.
- **FR-005**: The endpoint MUST be served wherever the service's routes are: at the derived hostname
  and every custom hostname when exposed, over mutual TLS inside the cluster with the caller read
  from the certificate, through grants across projects, through a rollout, and not at all while
  the service is paused. No new port, protocol, gateway rule or resource field MUST be needed.

**Who may call**

- **FR-006**: The endpoint's ACL MUST be any ACL an HTTP endpoint may state. `allowCallers` MUST
  admit a workload by certificate with no credential; `Oidc.authenticate()` MUST admit a token from
  one of the service's listed issuers with the token's subject as the principal; and a new
  authenticator MUST admit a client key with the key's subject as the principal. An ACL MAY
  combine authenticators so one endpoint admits more than one kind. The principal MUST be
  readable by the tool's handler as a route's is.
- **FR-007**: `tools/list` MUST show a caller only the tools its principal may call under the
  endpoint's and each tool's ACL and, for a client key, the key's scope; `tools/call` on a tool
  the caller may not call MUST be refused naming the tool, and MUST NOT run the handler.
- **FR-008**: An endpoint whose ACL admits tokens MUST answer a request with no admitted credential
  with `401` and a challenge naming the resource's metadata, and MUST publish that metadata at the
  protocol's well-known path for the resource, naming the service's issuers as authorization
  servers. Unless the service opts into brokered sign-in (FR-008a), the platform MUST NOT act as an
  authorization server, issue a token or register a client. An endpoint that admits no tokens MUST
  answer `401` with a plain challenge and publish no issuer.
- **FR-008a**: A service MUST be able to opt an MCP endpoint into brokered sign-in. The platform
  MUST then run, at the service's own hostnames and under a path reserved to the platform beside
  the endpoint's, an authorization server the resource metadata names, one per opted-in endpoint:
  it MUST register a client that presents itself, with the protocol's code flow and proof key;
  MUST send the person to one of the service's listed issuers to sign in, as a client that issuer
  holds a registration for (named beside the issuer's other settings), and accept only a token
  that issuer verifiably signed; MUST mint a token for this endpoint alone, signed with keys held
  in the service's database and rotated by the platform, whose subject is the subject the issuer
  gave; and MUST verify such a token at the endpoint as it verifies any token. The sign-in broker
  MUST hold no account and no password, MUST never be asked for or be given a person's password,
  and MUST mint no token for an endpoint that has not opted in. A token the sign-in broker minted
  MUST be refused at every other service, and at every other endpoint of its own. An opted-in
  endpoint whose issuers hold no registration for the broker MUST fail the start naming the setting.

**Client keys**

- **FR-009**: A service MUST be able to issue a client key through its client, from any handler,
  for one of its MCP endpoints (named when the service has several, implied when it has one),
  giving a subject (required, non-blank), a label, an expiry (required, with no default: a duration,
  an instant in the future, or explicitly never), the tools of that endpoint it is scoped to (none
  meaning every tool of the endpoint) and attributes:
  up to 16 named string values, each name and value at most 256 characters, which the platform
  stores and never interprets. The platform MUST answer the key's id and its secret exactly once,
  and MUST keep the secret only as a digest. A key naming a tool the endpoint does not have, or
  exceeding the attribute limits, MUST be refused at issue, naming it.
- **FR-010**: A request carrying a client key MUST be verified against the stored digest in
  constant time; an unknown, revoked or expired key MUST be refused with a reason that does not
  say whether the id exists, except that an expired key MUST be told it expired. An admitted key
  MUST set the principal to the key's subject with the key's attributes as the principal's claims,
  readable by name as a token's claims are, and MUST confine `tools/list` and `tools/call` to the
  key's scope.
- **FR-011**: A service MUST be able to list its client keys (id, subject, attributes, label,
  scope, issued, expires, last used, revoked) and revoke one, through its client. A listing MUST never carry a
  secret. A revoked key MUST be refused on its next request on every instance within one second of
  the revocation. A key MUST survive the service restarting and a rolling update.
- **FR-012**: A key MUST be the service's own: held in the service's database, usable at the MCP
  endpoint it was issued for and nowhere else, and never a credential for the control plane or
  another service. The platform MUST record a key's last use without writing on every request more often
  than once a minute per key.
- **FR-013**: A key's secret MUST appear in no log, trace, report, listing, descriptor or
  notification; a trace of a tool call MUST carry the principal's subject and the key's id, not its
  secret.

**Languages**

- **FR-014**: A Python, TypeScript or Rust service MUST be able to declare an MCP endpoint with its
  tools, each tool's argument type and ACL, in that language's idiom; the sidecar MUST answer the
  protocol, verify credentials, check arguments and ask the process or module to run the tool with
  typed arguments and the principal, under a minor bump of the sidecar protocol version. The
  process MUST never receive a key's secret or digest or a token's signature. Issuing, listing and
  revoking keys MUST be available through the process's client.
- **FR-015**: The conformance suite MUST drive an MCP endpoint in each language's example service
  with the same requests as the Scala reference and expect the same answers.

**Visibility and documentation**

- **FR-016**: A service's report MUST name each MCP endpoint's path and tools where it names the
  routes, which is its topology: `services topology`, the console's topology page, the local
  console and `ankka mcp`'s `describe_local_service` MUST show them beside the routes.
- **FR-017**: A tool call MUST be traced as a span under the endpoint and the tool, carrying the
  principal's subject, with the calls the handler makes as its children, exactly as a request to a
  route is.
- **FR-018**: The documentation MUST gain a page on MCP endpoints in every language — declaring
  tools, stating the ACL, issuing and revoking client keys, pointing a client at the endpoint,
  what an OAuth client is told and what the platform does not do — reached from the endpoints
  skill; the limitations page MUST say what is out of scope (resources, prompts, sessions, an
  authorization server the service did not opt into, one door for a project, and that a change
  to a service's tools is seen by a client at its next listing and is not pushed); and the Akka
  divergences page MUST say how the MCP endpoint differs from Akka's.

### Key Entities

- **MCP endpoint**: a component of a service that serves the Model Context Protocol over HTTP at a
  path on the service's HTTP port, with a stated ACL and a set of tools. The third kind of endpoint,
  beside HTTP and gRPC.
- **tool** (of an MCP endpoint): one thing an MCP endpoint offers a client to call: a name, a
  description, typed arguments with a derived schema, and either a handler or a reference to a
  command, query or route of the service, and optionally an ACL of its own. The same word the agent
  side uses, from the other end: what an agent is offered, this endpoint offers.
- **client key**: a credential a service issues for one caller of its MCP endpoints: a subject the
  handler reads as the principal, attributes it reads as the principal's claims, a scope of tools,
  a label and an expiry; shown once, held as a digest in the service's database, listed and revoked
  by the service. Not a token: nobody signed it, and it says nothing about who a person is.
- **attributes** (of a client key): up to 16 named string values the service attaches when it
  issues the key, such as the person it is for or their role, which the handler reads as the
  principal's claims. The platform stores and shows them and never interprets them.
- **resource metadata**: what an MCP endpoint that admits tokens publishes at the protocol's
  well-known path: the authorization servers a client may sign in with. The service's own issuers,
  or the platform's sign-in broker when the service opted into brokered sign-in.
- **brokered sign-in**: what a service opts an MCP endpoint into so that the platform runs the
  authorization server for it: registering clients, sending the person to the service's issuer to
  sign in, and minting a token for this service that the endpoint verifies. It holds no account and
  no password; its keys live in the service's database.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A service that declares an MCP endpoint with tools and an ACL, and nothing else new,
  is listed and called by Claude Code and by the platform's own MCP client; one that also
  registers the platform's MCP runtime, as a service with agents registers the agent runtime, is
  called by an MCP client that sends a client key in a header, the protocol's inspector among
  them, and by a client that can only sign in
  through brokered sign-in against the test issuer; in neither is there code in the service that
  speaks the protocol or stores a credential.
- **SC-002**: A tool call that reads one entity through the endpoint takes no longer, at the
  median, than the same read through an HTTP route of the same service, within a factor of 1.5 in
  the test kit; the protocol adds a parse and a schema check and nothing else.
- **SC-003**: A revoked client key is refused by every instance of a three-instance service within
  one second of the revoke, measured in the k3s suite; a key never admits a tool outside its scope
  in any scenario.
- **SC-004**: Every scenario this spec names passes: the endpoint and key scenarios in the test
  kit, the hostname and paused scenarios in the k3s suite, the language scenarios in the
  conformance suite against each SDK's example, and the sibling-agent scenario with two services
  in one kit using the client of feature 029 unchanged.
- **SC-005**: No request to an MCP endpoint, admitted or refused, writes a secret to a log, a trace
  or a report, checked by the recorder across every scenario.
- **SC-006**: ankka-for-plumbers spec 007 is written against this feature with no JSON-RPC, key
  table or OAuth code of its own in its plan.

## Assumptions

- The MCP endpoint is a new component kind, declared in code and registered explicitly like every
  component. Its tools are handlers, or references to existing commands, queries and routes with
  the description written beside them and the id under a named argument (Clarifications,
  2026-10-19). Handlers only, with references deferred, was considered and not chosen: a product
  whose handlers exist would write the same forwarding code once per tool.
- The endpoint is served on the HTTP port at a path, not on a port of its own, so that exposure,
  hostnames, TLS, grants and tracing apply unchanged and the "two protocols" limitation stands.
- The server is stateless (no session id) because instances are interchangeable behind one
  Service and nothing in a tool-only server needs a session. A client that requires a session id
  is not supported; the protocol allows a server to issue none.
- Tools only, no resources or prompts, matching the client side (feature 029). Streaming results
  are not offered: a result is one JSON response.
- A client key is issued by the service's own code, which is the only thing that knows who its
  customers are; the platform offers no interface for a person to mint one. The platform holds it
  as a digest in the service's database as platform-owned entities (as agent instances and tasks
  are), so backup, recovery and the service's lifecycle apply as they do to every entity.
- By default the platform issues no token for a service's users and runs no authorization server:
  a client that only speaks the protocol's OAuth flow is pointed at the service's own issuers
  through the resource metadata. A service may opt into brokered sign-in (Clarifications,
  2026-10-19), and then the platform runs the authorization server for that service: it registers
  clients and mints tokens, but still holds no account and no password, since the sign-in itself
  happens at the service's issuer. Running the sign-in broker for every service unasked was not chosen,
  because a service whose issuer registers clients needs nothing and should carry nothing; leaving
  it out altogether was not chosen either, because the assistants people have register themselves
  and most issuers refuse them.
- Key verification on a request reads a per-node index fed by the keys' events, as the control
  plane's deploy tokens do, which is where the "within one second" promise comes from; last-used
  is written at most once a minute per key so a busy assistant does not write on every call.
- The name and version the handshake answers default to the service's name and image tag.
- The word *key* in the glossary is a message's key on a topic; this credential is always an
  *client key*, two words, and the glossary says so. *Tool* is the agent side's word and is
  shared, since it is the same thing seen from the other end.
- The protocol version spoken is the one the client side speaks, so the two halves are tested
  against each other; a later protocol revision is a change to both.
- Rate limiting, lockout and anything at the gateway remain out of scope, as feature 045 left
  them.
- Client keys and the sign-in broker are the issuing service's own, by design and not by omission
  (Clarifications, 2026-10-19). One door for a project — a front MCP endpoint whose tools are every
  sibling service's, derived from their reports as they are deployed and removed, each call
  forwarded to its owner on behalf of the customer the door verified — is spec 055. This feature
  keeps three things in its way clear: tool names leave room for a service prefix
  (`payments__list_transactions`, the shape feature 029 already uses); a service's report names its
  tools (FR-016), which is the registration 055 reads; and a tool declared by reference to a route
  (FR-002a) is the shape a hand-written forwarding tool takes today. What 055 must add, and this
  feature does not, is a call made on behalf of a principal: the owning service admitting a named
  workload's word for who the customer is.
- A tool list is read at a client's listing. The server is stateless and pushes no
  `list_changed` notification, so a tool a service gains after a client connected is seen when the
  client lists again, which every client does at its next connection.
- The library a service adds for an MCP endpoint brings the token verifier with it (planning,
  2026-10-19): brokered sign-in verifies the issuer's token with the verifier the platform already
  has, and an endpoint that admits tokens needs it anyway. A service with client keys alone
  carries it unused.
