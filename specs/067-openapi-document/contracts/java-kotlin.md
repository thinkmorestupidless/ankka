# Contract: the Java and Kotlin surfaces

No Scala type appears on either (`SurfaceSuite`, `SurfaceTest`).

## What is derived, with no change to a service

| From | Gives |
|---|---|
| the verb annotation's template | method and whole path |
| a path parameter's Java type | its type |
| the `@Body` parameter's type | the body's shape |
| the method's generic return type | the answer's shape; `void` and `Done` are nothing |
| each `@QueryParam` parameter | a query parameter: its name, its type, optional when the parameter is an `Optional` |
| the class's `acl()` | who may call |

Shapes come from the same `Plan` the record codec writes by, through the bridge's `Shapes`, under
the same rules and names as Scala's. A Kotlin data class is read as the codec reads it.

## Annotations (`javasdk.annotations`), on a handler method or the endpoint class

| Annotation | Members | Repeatable | On a class |
|---|---|---|---|
| `@Summary` | `value` | no | no |
| `@BodyShape` | `value: Class<?>` or `schema: String` | no | no |
| `@Answers` | `status` (200), `shape: Class<?>` (`Void.class`: no body) or `schema: String` | yes | no |
| `@Refuses` | `status`, `reason`, `shape: Class<?>` (optional) | yes | yes |
| `@RefusalShape` | `value: Class<?>` | no | yes |
| `@ReadsQuery` | `name`, `type: Class<?>` (`String.class`), `required` (`false`) | yes | yes |
| `@Tag` | `value: String[]` | no | yes |

`@ReadsQuery` is for a parameter a handler reads through `Request`; one bound with `@QueryParam` is
already known, and naming it again is a contradiction.

```java
@Tag("player") @Refuses(status = 403, reason = "wrong brand")
public class PlayerEndpoint extends HttpEndpoint {
  @Get("/players/{playerId}/messages")
  @Summary("A player's messages")
  @Refuses(status = 404, reason = "not found")
  public Messages messages(String playerId, @QueryParam("page") Optional<Integer> page) { ... }
}
```

## `ServiceBuilder`

```java
ServiceBuilder withOpenApi(Acl acl);   // serve GET /_ankka/openapi.json to whom acl admits
```

## Kotlin's `routes { }`

```kotlin
override fun routes() = routes {
    described({ summary("A cart"); answers<ShoppingCart>(); refuses(404, "not found") }) {
        get("/carts/{cartId}") { cart(path("cartId")).call(ShoppingCart::getCart).invokeSuspending() }
    }
}
```

`RouteDescription` has `summary`, `body<T>()`, `body(schema)`, `answers<T>(status = 200)`,
`answers(status)`, `refuses(status, reason)`, `refusalShape<T>()`, `query<T>(name, required =
false)`, `tag(vararg)`. A route of this form has no answer type of its own, so it is incomplete
until its scope says `answers`. The annotated form derives as Java's does.

## Test kits

```java
AnkkaTestKit kit = AnkkaTestKit.start(Main.service(), Settings.defaults().heldToDocument());
kit.assertHeldToDocument();     // fails, listing each violation; close() calls it too
List<String> v = kit.documentViolations();
```

Kotlin: `ankkaTestKit(service(), Settings.defaults().heldToDocument()) { ... }`, the Java `Settings` as it is.

## Parity

`SurfaceParitySuite` gains rows under "HTTP endpoint" (`described` → `annotations.Summary`,
`description` → `annotations.Tag` on a class, `withOpenApi` → `ServiceBuilder.withOpenApi`) and
"Testing" (`HeldToDocument` → `Settings.heldToDocument`). None is pending.
