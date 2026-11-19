# OpenAPI documents

> Write, check and serve an OpenAPI 3.1 document of a service's HTTP routes, derived from the routes themselves, and hold the service's tests to it, in Scala, Java or Kotlin.

Source: https://docs.ankka.cloud/build/openapi/
A service's HTTP routes describe themselves. Its OpenAPI 3.1 document is read from the routes it
registers, so the document says what the code does: each route's method and path, the type of each
path parameter, the shape of the request body and of each answer, and who may call it. A shape is
the one the service's codec writes, so a field the codec omits is not required in the document, and
a sum type is one of its cases by `"type"`, as on the wire.

`ankka openapi` writes the document from the service's own build. `withOpenApi` serves it from the
running service, and a test kit held to its document fails a test whose answer the document does
not allow.

## What is derived

Nothing has to be added to a service for most of its document:

| From | Gives |
|---|---|
| the route's method and template | the operation and its whole path, the endpoint's prefix included |
| a path parameter's type | its type: `string`, `integer`, `number`, `boolean` or a `uuid` string |
| the body's type | the request body's shape |
| the reply's type | the `200` answer's shape; `Done` and no reply are an answer with no body |
| a query parameter the route binds | its name and type, optional unless it is required |
| the endpoint's ACL | open, a bearer token, or `x-ankka-admission: not-open` for any other rule |

A type the route cannot see is a gap. A route that answers bytes it builds itself, or reads its body
as text it parses, has no shape for that part. The document still holds the route, marked
`x-ankka-incomplete` with what it lacks (`request body`, `answer 200`).

## Describing what the types do not say

A description fills the gaps and adds what no type carries: a summary, the refusals a route makes,
the query parameters it reads for itself, and tags. It travels with the routes it wraps, and says
nothing to the codec or to a caller: a route answers exactly as it did before.

This route reads a text note, answers JSON it writes itself, reads two query parameters through the
request and refuses an empty cart:

**Scala**

```scala
described(
  _.summary("A cart's receipt")
    .body[String]
    .answers[Receipt](200)
    .query[String]("currency")
    .query[Int]("copies")
    .refuses(404, "the cart is empty")
) {
  postBody("/{cartId}/receipt")((cartId: String, note: String) => receipt(cartId, note))
}
```

**Java**

```java
@Post("/carts/{cartId}/receipt")
@Summary("A cart's receipt")
@BodyShape(String.class)
@Answers(shape = Receipt.class)
@ReadsQuery(name = "currency")
@ReadsQuery(name = "copies", type = Integer.class)
@Refuses(status = 404, reason = "the cart is empty")
public Response receipt(String cartId, @Body String note) {
  return receiptOf(cartId, note);
}
```

**Kotlin**

```kotlin
described({
    summary("A cart's receipt")
    body<String>()
    answers<Receipt>()
    query<String>("currency")
    query<Int>("copies")
    refuses(404, "the cart is empty")
}) {
    post<String>("/carts/{cartId}/receipt") { note -> receipt(path("cartId"), note, request) }
}
```

In Java the same words are annotations on the handler method: `@Summary`, `@BodyShape`, `@Answers`,
`@Refuses`, `@RefusalShape`, `@ReadsQuery` and `@Tag`. `@Refuses`, `@RefusalShape`, `@ReadsQuery`
and `@Tag` on the endpoint class apply to every route in it. In Kotlin, `described({ … }) { … }` wraps
the routes of a `routes { }` block, and a route written in that form has no answer type of its own, so it
stays incomplete until its scope says `answers`.

A description that contradicts the types is refused, not merged. A description that gives `200` a
shape when the reply's type gives another, or a `@ReadsQuery` for a parameter the handler already
binds, is a problem the command reports, naming the route.

## Writing the document

`ankka openapi` runs the service's own build with the document mode on, and writes `openapi.json`
beside it:

```bash
ankka openapi                       # the service in the current directory
ankka openapi services/cart --out api/cart.json
ankka openapi --out -               # print it; everything the build says goes to standard error
ankka openapi --strict              # refuse a document with an incomplete route
ankka openapi --tag cart --prefix /carts --api-version 1.4.0
```

The build is the service's: `sbt run` in an sbt build, `./mvnw -q exec:java` in a Maven one,
`./gradlew --no-daemon -q run` in a Gradle one, or the `run` command its `local.json` names. In a
project of several services, give the service's directory, and the build runs where
`ankka local run` runs it. Nothing else starts: no database, broker or companion. Docker is not
consulted.

The service's `main` reaches `start`, which writes the document before it needs anything from the
environment and ends the JVM: no port is bound, no cluster formed and no database connected. Every
endpoint is built as `start` builds it, so an endpoint that cannot be built fails the command and
names the endpoint.

A build without the CLI writes the same document with the properties the command passes in
`JAVA_TOOL_OPTIONS`:

| Property | Value |
|---|---|
| `ankka.document` | `openapi` |
| `ankka.document.out` | the file to write |
| `ankka.document.strict` | `true` to refuse an incomplete route |
| `ankka.document.tags`, `ankka.document.prefixes` | comma-separated; only the routes with one of these tags, or under one of these prefixes |
| `ankka.document.version` | the document's `info.version`; `0.0.0` when absent |

```bash
sbt -Dankka.document=openapi -Dankka.document.out=openapi.json run
```

The command exits `0` when it wrote or printed the document, `1` when the document could not be
built or a strict one has an incomplete route, and `2` when the directory is not one service it can
run: a language that has no document yet, a directory holding several services or none, a service
with no build of its own, or one whose language or run command it cannot tell.

## Checking a kept document

Keep the document in the repository and check it in the build that changes the routes:

```bash
ankka openapi --check openapi.json
```

When the routes still give the same document, the command prints `openapi.json is current` and exits
`0`. When they do not, it prints one line per route added, removed or changed and per schema changed,
writes nothing and exits `1`. A client generated from the kept document is then never older than the
service without a red build to say so.

`ankka openapi --local <service>` prints the document of a service running on this machine, from its
local console address. A service behind the sidecar answers that it has no document, and the command
exits `1`.

## A client from the document

Any OpenAPI 3.1 generator reads the document. With `openapi-typescript`, for instance, the cart's
document gives TypeScript types for every path, body and answer:

```bash
npx openapi-typescript openapi.json -o cart.d.ts
```

The types say what the codec does. A collection field is optional, because a missing one reads as
empty, and a field with a default is optional, because the codec omits it when it equals the default.
A client written against them therefore handles an absent `items`, as a client of the wire must.

## Serving the document

`withOpenApi` serves the document at `GET /_ankka/openapi.json`, to the callers the given ACL admits.
Without it, the path is answered as any route the service does not have.

**Scala**

```scala
val served = HttpServer
  .at("127.0.0.1", 0)(clients => ShoppingCartEndpoint(clients.componentClient))
  // GET /_ankka/openapi.json, answered to the callers this ACL admits.
  .withOpenApi(Acl.AllowAll)
```

**Java**

```java
public static ServiceBuilder service() {
  return Ankka.service("greetings")
      .withEndpoint(Greetings.class)
      // GET /_ankka/openapi.json, answered to the callers this ACL admits.
      .withOpenApi(Acl.allowAll());
}
```

**Kotlin**

```kotlin
fun greetings(): ServiceBuilder =
    Ankka.service("greetings")
        .withEndpoint(GreetingsEndpoint::class.java)
        // GET /_ankka/openapi.json, answered to the callers this ACL admits.
        .withOpenApi(Acl.allowAll())
```

The served document is the one the command writes when it is given the same name and version. Its
title is `ankka.service.name` and its version `ankka.document.version`, each read from a JVM property
or from the service's configuration. The command passes both as properties to the build it runs, so a
service that serves a document with a version sets `ankka.document.version` in its `application.conf`
to the `--api-version` its kept document is written with. Without it, the served version is `0.0.0`.

A route whose operation id would be another's (`/a-b` and `/a_b` are both `get_a_b`) is a problem, as
any other is: the command names both routes and writes nothing, and a server serving the document
refuses to start.

## Holding tests to the document

A test kit held to its document checks every answer a test causes against the document the service
gives: the status must be one the route names, the body must match the shape of that answer,
and a query parameter a handler reads must be one the route names. Anything outside it is a violation, and the kit fails, listing each, when it closes. A route that is
not in the document is not checked.

**Scala**

```scala
// Port 0 so concurrent test runs cannot collide. Held to its OpenAPI document: an answer the
// document does not allow fails the test that caused it.
server = HeldToDocument.server(
  HttpServer.at("127.0.0.1", 0)(clients => ShoppingCartEndpoint(clients.componentClient))
)
testKit = AnkkaTestKit.start(Seq(ShoppingCartEntity.descriptor), Seq(server))
baseUrl = s"http://127.0.0.1:\${server.boundPort.getOrElse(fail("server did not bind"))}"
```

**Java**

```java
/** Two calls of a test, each answer checked against the service's document. */
public static void held() throws Exception {
  // close() fails, listing every answer the document does not allow.
  try (var kit = AnkkaTestKit.start(service(), Settings.defaults().heldToDocument())) {
    get(kit, "/greetings/ada");
    get(kit, "/greetings/nobody"); // 404, which the route's @Refuses names
  }
}
```

**Kotlin**

```kotlin
/** Two calls of a test, each answer checked against the service's document. */
fun heldToItsDocument() {
    // Closing fails, listing every answer the document does not allow.
    ankkaTestKit(greetings(), Settings.defaults().heldToDocument()) {
        get("/greetings/ada")
        get("/greetings/nobody") // 404, which the route's description names
    }
}
```

A refusal the code makes and the description does not name is the most common violation: a `404`
thrown from a handler is not in the document until `refuses(404, …)` (or `@Refuses`) says so.

## What is left out

- Socket routes, MCP tools and gRPC methods are not in the document; it describes request and
  server-sent event routes only.
- A shape describes an answer. It does not validate a request: a body the codec cannot read is
  refused as it always was, and nothing checks a request against the document first.
- A Python, TypeScript, Go, Rust or web-hosted service has no document yet. The command refuses
  each by name.
- The document is what `main` registers when it runs under the document mode. A `main` that
  registers an endpoint only when some environment variable is set gives a document that depends
  on that variable, so run the command in the environment whose routes the document should hold.

See [Limitations](../reference/limitations.md) for the full list.
