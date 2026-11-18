# Quickstart: proving the feature

Each step is one scenario group of `features/openapi-documents/`, run by hand. Docker is not
needed for any of them except step 5's running sample.

## 1. A document from a service as it stands (US1)

```bash
sbt cli/stage shoppingCart/compile
cli/target/universal/stage/bin/ankka openapi samples/shopping-cart --out /tmp/cart.json
```

Expect `wrote /tmp/cart.json: <n> routes, 0 incomplete` for the typed routes, a file that
`swagger-parser` reads as OpenAPI 3.1, `ShoppingCart` and `LineItem` once each under
`components.schemas`, and no path under `/_ankka`, no socket and no MCP route. Run it again to a
second file: `cmp` reports nothing. Stop Docker and run it a third time: it still writes.

## 2. Describing what the types do not say (US2)

In the sample, the route added for this feature takes text and answers bytes. Remove its
`described` scope: the document marks it `x-ankka-incomplete`. Put it back: the mark is gone and
the route has its body, its `404` and its query parameters. Give its body a second, different
shape on a typed route: the command exits `1` naming the route, and `/tmp/cart.json` is untouched.

```bash
ankka openapi samples/shopping-cart --tag player --out -     # only the routes tagged player
ankka openapi samples/shopping-cart --tag nobody             # exit 1: no route carries the tag
```

## 3. A document that cannot drift (US3)

```bash
ankka openapi samples/shopping-cart --strict                 # exit 1 while a route is incomplete
ankka openapi samples/shopping-cart --check samples/shopping-cart/openapi.json
sbt 'shoppingCart/testOnly *ShoppingCartHttpSuite'
```

Change the described route to answer a status its description does not name: the suite fails with
`<route>: answered <status>, which its description does not name`. Read a query parameter the
route does not declare: it fails naming the parameter.

## 4. Served (US4)

`sbt 'http/testOnly *OpenApiServedSuite'`: with `withOpenApi(acl)` the document is answered to a
caller the ACL admits and equals `OpenApi.build`'s rendering; a caller it does not admit is
refused as that ACL refuses; without `withOpenApi` the path is answered exactly as on `main`.

## 5. Java and Kotlin (US5), and a generated client (SC-002)

```bash
ankka openapi samples/shopping-cart-java --out /tmp/cart-java.json
ankka openapi sdks/kotlin/samples/shopping-cart --out /tmp/cart-kotlin.json
```

The three documents hold the same operations and schemas for the routes the samples share.

```bash
npx openapi-typescript /tmp/cart.json -o /tmp/cart.d.ts
```

The TypeScript job compiles a typed `fetch` wrapper against `/tmp/cart.d.ts` and runs add-item and
get-cart against `sbt shoppingCart/run`.

## 6. A language with no document

```bash
ankka openapi sdks/typescript/examples/shopping-cart     # exit 2: a typescript service has no OpenAPI document yet
```

## Suites

```bash
sbt 'core/testOnly *ShapeSuite *ShapeAgreesSuite'
sbt 'http/testOnly *OpenApiSuite *OpenApiServedSuite'
sbt 'testkit/testOnly *OpenApiFeatures *HeldToDocumentSuite'
sbt 'cli/testOnly *OpenApiCommandSuite'
sbt 'testkitJava/testOnly *OpenApiJavaFeatures'
just kotlin test
just features
```
