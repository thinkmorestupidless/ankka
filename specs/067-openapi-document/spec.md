# Feature Specification: An OpenAPI Document of a Service's HTTP Endpoints — Derived From Its Routes, Completed by Declarations

**Feature Branch**: `067-openapi`

**Created**: 2026-11-18

**Status**: Draft

**Input**: User description: "OpenAPI/Swagger specs aren't available from ankka right now, let's get
that added. A service's HTTP endpoints are described as an OpenAPI document generated from the code,
as the old eitheror platform's build step did for its Akka endpoints (reflection over the endpoint
classes and records, with annotations for what the types did not say). The need comes from
ankka-modules: a typed frontend client for the casino and prediction-market services is generated
from each service's document, and most of those services' routes take a raw body and answer
hand-built JSON, so what the route's types say is not enough. The document is written to a file by
a build command and is also served by a deployed service; Scala, Java and Kotlin first; a route
that is not fully described is listed and a strict switch fails the build."

## Context

A service's HTTP endpoint is the one part of it that programs outside ankka are written against,
and ankka has no machine-readable account of it. The server knows every route: routes are declared
in an endpoint's constructor and collected as values, which is how the startup log, the topology,
grants and the control plane's generated route reference read them (`ServedRoute`: a method, a
path, whether it streams). What none of those hold is the shape of what goes in and what comes
out. A route's body and answer types are known where the route is declared, as the instances that
read the body and write the response, and are gone once it is recorded: a recorded route is a
method, a path template and a function from bytes to bytes.

So a team that wants a client for a service writes one by hand from the endpoint's source, and
keeps it in step by hand. ankka-modules is about to do that across some twenty services for a
frontend SDK, and it is the case this feature is shaped by, because it shows both halves of the
problem. A few of its routes are typed (`postBody` of a `ReservationRequest`, answering a record):
everything a client needs is in the declaration. Most are not. Of roughly 230 routes with a body,
215 take the body as text and answer bytes they built themselves, because those services reproduce
an existing system's wire format exactly and a derived codec would not. Their query parameters are
read inside the handler (`query.optional[Int]("page")`), and their refusals are thrown. A
generator that only reads types would list their paths and say nothing else.

The old eitheror platform solved this once for its Akka services, as a build step: reflection over
the endpoint classes and the records they named gave the paths and the schemas, and annotations
beside each method (a summary, a response per status, a schema where the type was opaque) gave the
rest. This feature is that, for ankka's route model. The decisions:

- **The document is OpenAPI 3.1, one per service, as JSON.** 3.1's schemas are JSON Schema, which
  is what the platform already writes for an agent's tools and an MCP endpoint's tool arguments.
  Its content is ordered by path and method and carries nothing that varies between runs, so two
  runs over the same code give the same bytes and the file can be committed and compared.
- **What a route's declaration already says is derived, with no change to the service.** Every
  request route and every server-sent-events route of every HTTP endpoint appears: its method, its
  whole path (the endpoint's prefix and the route's template), each path parameter with the type
  its handler names, the body's content type and, when the body type is one the platform decodes,
  its schema, and the answer's status, content type and, when the answer type is one the platform
  encodes, its schema. A schema derived from a type describes the JSON the platform's codec
  actually writes for that type, including the cases where that differs from the obvious (a
  fieldless enumeration, an absent optional field). A type used by several routes is one named
  schema, referred to.
- **What the declaration does not say, the route declares.** A route may carry a **route
  description**: a summary, the schema of a body or an answer whose type is opaque (text, bytes),
  answers by status beyond the one the handler's type gives (a refusal and its code), the query
  parameters the handler reads with their types and whether each is required, and labels. A
  described schema is given either as a type, derived as above, or as JSON Schema. A description
  adds to what is derived and never contradicts it: describing a body the route's type already
  describes, differently, is refused when the document is built, naming the route.
- **Who may call a route is said as far as the platform can know it.** A route open to any caller
  is marked as needing no credential. A route whose ACL admits tokens from named issuers is marked
  as needing a bearer token, with the issuers. Any other ACL (a predicate, callers named by their
  certificate) is beyond what a document can state, and such a route is marked as not open without
  saying how it is admitted.
- **A route that is not fully described is still listed, and a strict switch refuses it.** A route
  is **incomplete** when it takes a body with no schema, or has an answer with a body and no
  schema. An incomplete route appears with everything that was derived and is marked incomplete.
  With the strict switch the build of the document fails and names every incomplete route, so a
  repository adopts descriptions at its own pace and then holds the line in CI.
- **The document is written by a command, without starting the service.** The command builds the
  service's endpoints as the service's own start would, reads their routes, and writes the file.
  It needs no database, broker, cluster or network. It can be limited to routes with a given label
  or under a given path prefix, so a service whose endpoints serve several audiences can produce a
  document for one of them (the routes a player's browser calls, without the operators').
- **A deployed service serves the same document only to callers it names.** A service that states
  who may read its document answers it at one path under the platform's own prefix; a service that
  states nothing does not serve it, and the path answers as any unknown path does. The served
  document is the one the command writes for that build, whole, whatever the reader may call.
- **A description is checked against what the service does, in its tests.** The testkit can hold a
  service to its document: an answer whose status or body the route's description does not allow,
  a body the described schema refuses that the handler accepted, and a query parameter the handler
  read that the route did not declare each fail the test, naming the route. Without this a
  description is a comment, and a document that is wrong is worse than none.
- **Scala, Java and Kotlin.** The three share one route model in one JVM. A Java or Kotlin
  endpoint is described from its handler methods' types as a Scala one is from its routes', and
  declares the rest in the form its SDK uses for everything else. A service in Python, TypeScript,
  Go or Rust has no document yet, and the command says so instead of writing an empty one.

Not in a document: socket routes, an MCP endpoint's tools (they already carry schemas, in MCP's own
listing), gRPC methods (their `.proto` is their description), and the platform's own routes under
its prefix. None of these has an OpenAPI form that would help a client, and each is named in the
documentation as left out.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A document from a service as it stands (Priority: P1)

A developer with an ankka service whose routes take and answer typed values runs one command in
the service's build and gets an OpenAPI document of every route, with schemas, having changed
nothing in the service. They feed it to a client generator and call the service through the
generated client.

**Why this priority**: It is the whole feature for a service written the way the documentation
teaches, and everything else builds on the document it produces.

**Independent Test**: Run the command on the shopping cart sample; validate the file as OpenAPI
3.1; generate a client from it and add an item to a cart and read the cart back through that
client against the running sample.

**Acceptance Scenarios**:

- added `features/openapi-documents/deriving.feature`: the document holds every route of the service's HTTP endpoints
- added `features/openapi-documents/deriving.feature`: a path parameter is in the document under its name, with the type its route gives it
- added `features/openapi-documents/deriving.feature`: a typed body has its type's shape, and a body the service accepts fits it
- added `features/openapi-documents/deriving.feature`: a typed answer has its type's shape, and the answer the service gives fits it
- added `features/openapi-documents/deriving.feature`: a route that answers nothing has an answer with no body, and is complete
- added `features/openapi-documents/deriving.feature`: a route answered as a stream of events is in the document as one
- added `features/openapi-documents/deriving.feature`: a type two routes use is one named shape that both refer to
- added `features/openapi-documents/deriving.feature`: writing the document twice from the same code gives the same document
- added `features/openapi-documents/deriving.feature`: what has no place in an OpenAPI document is left out of it
- added `features/openapi-documents/deriving.feature`: the document is written with no database, no broker and no network
- added `features/openapi-documents/deriving.feature`: a route says who may call it as far as its ACL tells
- added `features/openapi-documents/deriving.feature`: an endpoint that cannot be built without the running service fails the writing and is named

---

### User Story 2 - Describing what the types do not say (Priority: P2)

A developer whose route takes its body as text and answers bytes it built itself, reads query
parameters in its handler and refuses with its own codes, declares those beside the route. The
document then describes the route as completely as a typed one.

**Why this priority**: It is the case the feature was asked for. Without it the services that most
need a generated client get a list of paths.

**Independent Test**: Take one route with a raw body, a raw answer, two query parameters and a
refusal; describe it; confirm the document holds all of them and that the route is no longer
marked incomplete.

**Acceptance Scenarios**:

- added `features/openapi-documents/describing.feature`: a text body described with a shape has that shape in the document
- added `features/openapi-documents/describing.feature`: an answer of bytes described with a shape has that shape in the document
- added `features/openapi-documents/describing.feature`: a described refusal stands beside the answer the route's type gives
- added `features/openapi-documents/describing.feature`: a described query parameter is in the document with its type and whether it is required
- added `features/openapi-documents/describing.feature`: a shape described by a type is the named shape a typed route would have
- added `features/openapi-documents/describing.feature`: a shape described as JSON Schema is in the document as it was given
- added `features/openapi-documents/describing.feature`: a description that contradicts the route fails the writing and names the route
- added `features/openapi-documents/describing.feature`: describing a route changes nothing of how it answers
- added `features/openapi-documents/describing.feature`: an endpoint's route tags and refusals are carried by each of its routes
- added `features/openapi-documents/describing.feature`: a document written for one route tag holds only the routes that carry it
- added `features/openapi-documents/describing.feature`: a document written for one path prefix holds only the routes under it
- added `features/openapi-documents/describing.feature`: a selection that matches no route fails the writing

---

### User Story 3 - A document that cannot drift from the service (Priority: P3)

A team that generates clients from the document makes it part of their build: the strict switch
fails while any route is incomplete, and their service's tests fail when the service answers
something its description does not allow.

**Why this priority**: A description nobody checks goes stale with the first change, and a client
generated from a stale document fails at run time in someone else's code.

**Independent Test**: Run the strict command on a service with one undescribed raw route and see
it fail naming that route; describe it wrongly and see a test that calls it fail naming the route
and what differed; describe it rightly and see both pass.

**Acceptance Scenarios**:

- added `features/openapi-documents/completeness.feature`: an incomplete route is in the document, marked with what it lacks
- added `features/openapi-documents/completeness.feature`: requiring completeness fails the writing and names every incomplete route
- added `features/openapi-documents/completeness.feature`: requiring completeness of a service whose routes are all complete writes the document
- added `features/openapi-documents/completeness.feature`: a body or an answer that is a stream of bytes does not make a route incomplete
- added `features/openapi-documents/completeness.feature`: a kept document that no longer matches the code is found, and nothing is written
- added `features/openapi-documents/held-to-document.feature`: an answer the description does not name fails the test
- added `features/openapi-documents/held-to-document.feature`: an answer body its shape refuses fails the test
- added `features/openapi-documents/held-to-document.feature`: a query parameter the route did not declare fails the test when the handler reads it
- added `features/openapi-documents/held-to-document.feature`: a service that answers as its document says passes
- added `features/openapi-documents/held-to-document.feature`: a test that does not hold the service to its document is unaffected
- added `features/openapi-documents/held-to-document.feature`: an incomplete route is held only to what it states

---

### User Story 4 - A deployed service serves its document to whom it names (Priority: P4)

A team whose partners or consoles read the document from the running service states who may read
it. Those callers fetch it from the service; nobody else learns that it is there.

**Why this priority**: The file is enough to generate a client. Serving matters for readers who
have the service and not its build, and it is a disclosure, so it is opt-in.

**Independent Test**: Deploy a service that names a reader; fetch the document as that reader and
compare it with the file; fetch it as another caller and as no one.

**Acceptance Scenarios**:

- added `features/openapi-documents/serving.feature`: a caller the service names is given the document, the same as the one written from its code
- added `features/openapi-documents/serving.feature`: a caller the service does not name is refused as that ACL refuses
- added `features/openapi-documents/serving.feature`: a service that states nothing about its document answers as for a path it does not have
- added `features/openapi-documents/serving.feature`: the served document is whole, whatever the reader may call
- added `features/openapi-documents/serving.feature`: a local service's document is given to its developer whether or not the service serves it

---

### User Story 5 - Java and Kotlin services (Priority: P5)

A developer with a Java or Kotlin service gets the same document from the same command, and
describes a route in their own language's form.

**Why this priority**: The three languages share the route model, so the document costs little
more; the services that need it first are Scala.

**Independent Test**: Run the command on the Java and the Kotlin shopping cart; compare each
document with the Scala one's, route by route.

**Acceptance Scenarios**:

- added `features/openapi-documents/languages.feature`: the same service has the same document in every language that has one
- added `features/openapi-documents/languages.feature`: a route description says the same in every language that has one
- added `features/openapi-documents/languages.feature`: a service in a language with no document yet is told so

---

### Edge Cases

- Two different types with the same short name: each is a schema of its own, under names that tell
  them apart, the same names on every run.
- A type that refers to itself (a tree): its schema refers to itself by name.
- A type the platform encodes with a codec written by hand: its schema cannot be derived, so a
  route using it is incomplete until the route, or the type, states one.
- A route answering a value with a status chosen by the handler (a created resource, a redirect):
  the handler's type gives the body and not the status, so the status is described or the route's
  answer is listed under the default.
- A route that streams its body in or its answer out: listed with the content type and no schema,
  and not incomplete, since bytes have none.
- An endpoint whose construction does work that needs a running service: the command fails naming
  the endpoint and the cause, and writes no file.
- The same method and path declared by two endpoints: already refused when the service starts;
  the command refuses the same way.
- A path with a parameter that takes the rest of the path: written in the form OpenAPI has for a
  single segment, and marked as taking the rest.
- A label or prefix that selects no route: the command fails saying so, instead of writing an
  empty document.
- A route admitted by any one of several ACLs, one of them tokens: marked as needing a bearer
  token as one of its alternatives.
- The served document asked for with a prefix or label: not offered; the served document is whole.

## Requirements *(mandatory)*

### Functional Requirements

**The document**

- **FR-001**: The platform MUST produce, for a service, one OpenAPI 3.1 document in JSON
  describing its HTTP endpoints.
- **FR-002**: The document MUST hold every request route and every server-sent-events route of
  every HTTP endpoint of the service, each with its method and its whole path.
- **FR-003**: The document MUST NOT hold socket routes, an MCP endpoint's routes or tools, gRPC
  methods, or routes under the platform's own prefix.
- **FR-004**: The document's content MUST be the same bytes for the same code on every run and on
  every machine.
- **FR-005**: The document MUST name the service and carry the service's own version when the
  build states one.

**What is derived**

- **FR-006**: Each path parameter MUST appear by its name in the path, required, with the type its
  handler declares.
- **FR-007**: A route with a body MUST have a request body with the content type the service
  reads, and, when the body's type is one whose JSON the platform decodes, that type's schema.
- **FR-008**: A route's answer MUST appear with the status and content type the platform sends for
  the handler's answer type, and, when that type is one whose JSON the platform encodes, its
  schema; an answer of nothing is the no-content status; a server-sent-events route's answer is an
  event stream.
- **FR-009**: A schema derived from a type MUST accept every value the platform's codec writes for
  that type and refuse a value the codec would refuse to read for a missing required field or a
  field of the wrong JSON type.
- **FR-010**: A type used in more than one place MUST be one named schema, referred to; names MUST
  be stable between runs and distinct for distinct types.
- **FR-011**: A route open to any caller MUST be marked as needing no credential; a route whose
  ACL admits tokens MUST be marked as needing a bearer token and name the issuers; a route with
  any other ACL MUST be marked as not open, without a credential scheme.

**What is declared**

- **FR-012**: A route MUST be able to carry a description giving any of: a summary; a schema for
  its body; answers by status, each with or without a schema; its query parameters, each with a
  name, a type and whether it is required; labels.
- **FR-013**: A described schema MUST be expressible as a type, derived as in FR-009 and FR-010,
  or as JSON Schema, written into the document as given.
- **FR-014**: A description MUST add to what is derived: a described answer for a status the
  handler's type does not give stands beside the derived one.
- **FR-015**: A description that contradicts what is derived (a different schema for a typed body
  or a typed answer, a path parameter the path does not have, the same query parameter twice) MUST
  be refused when the document is built, naming the route and the contradiction, and no document
  written.
- **FR-016**: Describing a route MUST NOT change what the route does for any request.
- **FR-017**: An endpoint MUST be able to give labels and described refusals once for all its
  routes, each route adding its own.

**Completeness**

- **FR-018**: A route MUST be counted incomplete when it takes a body that has no schema, or has
  an answer with a body and no schema; a body or answer that is a stream of bytes is not counted.
- **FR-019**: An incomplete route MUST appear in the document with everything derived for it and a
  mark that it is incomplete, saying what it lacks.
- **FR-020**: With the strict switch the command MUST fail, write no file, and name every
  incomplete route and what each lacks.

**The command**

- **FR-021**: A command MUST write the document to a file, or to standard output, without starting
  the service and with no database, broker, cluster or network available.
- **FR-022**: The command MUST be able to limit the document to routes with a given label, and to
  routes under a given path prefix; the document then holds only those routes and the schemas they
  use.
- **FR-023**: A selection that matches no route MUST fail the command, saying so.
- **FR-024**: The command MUST be able to check a committed document against the code and fail,
  showing what differs, when they disagree, writing nothing.
- **FR-025**: A failure of the command MUST leave no partly written file and MUST end with a
  status other than success.

**Served**

- **FR-026**: A service MUST be able to state who may read its document, as an ACL.
- **FR-027**: A service that states one MUST answer its whole document at one path under the
  platform's prefix to the callers that ACL admits and refuse others as that ACL refuses.
- **FR-028**: A service that states none MUST answer that path as it answers a path that does not
  exist.
- **FR-029**: The served document MUST be the same as the one the command writes for the same
  build with no selection.
- **FR-030**: On a developer's machine the local tooling MUST give a running service's document
  whether or not the service serves it.

**Held to the service**

- **FR-031**: The testkit MUST be able to hold a service to its document for the length of a test:
  an answer with a status the route's description and derivation do not name, an answer body its
  schema refuses, and a query parameter read that the route did not declare each fail the test,
  naming the route and what differed.
- **FR-032**: Holding a service to its document MUST be something a test asks for; a test that
  does not ask is unaffected.
- **FR-033**: A route that is incomplete MUST be held only to what it does state.

**Languages**

- **FR-034**: Scala, Java and Kotlin services MUST each get a document from the command, and the
  same service written in each MUST get the same routes, parameters, answers and schemas.
- **FR-035**: Java and Kotlin MUST each have a way to describe a route that carries everything
  FR-012 lists.
- **FR-036**: For a service in any other language the command MUST fail, saying that language has
  no document yet.

**Compatibility**

- **FR-037**: A service that compiles against the previous version MUST compile and behave
  unchanged against this one, and get a document with no change to its code.

### Key Entities

- **OpenAPI document**: the description of one service's HTTP endpoints: its routes, their
  parameters, bodies and answers, the named schemas they refer to, and who may call each as far as
  the platform knows.
- **Route description**: what a route declares about itself beyond its handler's types: a summary,
  schemas for an opaque body or answer, answers by status, query parameters, labels.
- **Schema**: the JSON shape of a body or an answer, derived from a type or given as JSON Schema;
  a named one is shared by every route that uses its type.
- **Incomplete route**: a route with a body or an answer body that has no schema.
- **Label**: a word a route or an endpoint carries, by which the command selects routes for a
  document.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer gets a valid document for an existing typed service with one command and
  no change to the service's code.
- **SC-002**: A client generated from the shopping cart sample's document, by a generator the
  platform does not maintain, completes every call the sample's own tests make, with no
  hand-written request or response type.
- **SC-003**: Every request and server-sent-events route of a service is in its document: for each
  of the samples, the count of routes in the document equals the count the service lists at start,
  less the kinds the document leaves out.
- **SC-004**: For every typed route of the samples, every body their tests send and every answer
  the service gives is valid against the document's schema for it.
- **SC-005**: A route with a raw body, a raw answer, query parameters and a refusal is described
  completely in at most ten lines beside the route.
- **SC-006**: A change to a described route's answer that its description does not allow fails a
  test that holds the service to its document, in the same test run, naming the route.
- **SC-007**: The command writes the document for the largest sample in under ten seconds once the
  service is compiled, on a machine with nothing else running.
- **SC-008**: Two runs of the command over the same code give identical files, on a developer's
  machine and in CI.
- **SC-009**: A service that does not state who may read its document discloses nothing new: every
  request that was refused or unknown before this feature is answered the same after it.

## Assumptions

- The first consumer is ankka-modules, generating a TypeScript client per module from each
  service's document; this feature ends at the document and ships no client generator.
- A service's endpoints can be built without the service running: an endpoint's constructor
  declares routes and does no I/O, which the platform's own reference suite already relies on.
- OpenAPI 3.1 is the only version written. A reader that needs 3.0 or Swagger 2 converts.
- The document is JSON. A reader that wants YAML converts.
- Schemas are derived for the types the platform's shared codec handles. A type with a
  hand-written codec states its own schema or is described at the route.
- Python, TypeScript, Go and Rust services are a later feature, as is describing a WebAssembly
  module's routes; the description would travel in the sidecar's discovery, which this feature
  does not change.
- The deployed console and the local console do not render the document in this feature; the
  local tooling returns it as text.
- A refusal the platform itself gives before a handler runs (an unknown path, a refused caller, a
  body that is not JSON) is described once for the document and not per route.
- Headers a handler reads or sets are not described in this feature, beyond the bearer token.
- Request validation at run time is unchanged: a described schema does not make the server refuse
  a body the handler would accept. Only a test that asks holds the service to its document.
