# Quickstart: proving the feature

Each step is one scenario group of `features/openapi-documents/`, run by hand. Docker is not
needed for any of them except step 5's running sample.

`ankka openapi` runs a service that is a build of its own, as `ankka init` makes one, or one of a
project's `services/`. This repository's samples are projects of its root build, which `ankka
openapi` refuses ("has no build of its own"), so these steps run the root build's run task with the
properties the command would add (found during implementation, T034). `doc` below is

```bash
doc() { out=$1; shift; JAVA_TOOL_OPTIONS="-Dankka.document=openapi -Dankka.document.out=$out $*" sbt -batch shoppingCart/run; }
```

## 1. A document from a service as it stands (US1)

```bash
sbt cli/stage shoppingCart/compile
doc /tmp/cart.json
cli/target/universal/stage/bin/ankka openapi samples/shopping-cart   # exit 2: no build of its own
cli/target/universal/stage/bin/ankka init probe --language java --dir /tmp/q
cli/target/universal/stage/bin/ankka openapi /tmp/q/probe --out /tmp/probe.json
```

The template's project is written with `wrote /tmp/probe.json: 6 routes, …`.

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
doc /tmp/cart-cart.json -Dankka.document.tags=cart          # only the routes tagged cart
doc /tmp/cart-nobody.json -Dankka.document.tags=nobody       # exits non-zero: no route carries the tag
```

## 3. A document that cannot drift (US3)

```bash
doc /tmp/cart-strict.json -Dankka.document.strict=true       # exits non-zero while a route is incomplete
sbt 'shoppingCart/testOnly *OpenApiDocumentSuite'            # the kept openapi.json is current
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
JAVA_TOOL_OPTIONS="-Dankka.document=openapi -Dankka.document.out=/tmp/cart-java.json" sbt -batch shoppingCartJava/run
JAVA_TOOL_OPTIONS="-Dankka.document=openapi -Dankka.document.out=/tmp/cart-kotlin.json" sdks/kotlin/gradlew-this-commit.sh -q :samples:shopping-cart:run
sbt 'shoppingCartJava/testOnly *OpenApiJava*'                # languages.feature's Scala and Java rows
```

The three documents hold the same operations and schemas for the routes the samples share; each is
kept beside its sample (`openapi.json`).

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
