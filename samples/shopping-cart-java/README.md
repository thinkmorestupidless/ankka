# The shopping cart, in Java

The shopping cart sample written with the Java SDK, hosted by the same runtime in the same process
as the Scala cart: no sidecar and no protocol. Its entity's component id, its handlers' wire names
and its manifests are the Scala cart's, so the two read each other's journal.

- `ShoppingCart`: the event sourced entity, its handlers named by `@Command` and `@Query`.
- `Cart`, `LineItem`, `CartEvent`: records and a sealed interface, encoded as the Scala cart's
  case classes and enum are.
- `CartEndpoint`: the cart's routes, which call the entity by method reference.
- `CartRows`: a view with one row per cart.
- `CheckoutNotifier`: a consumer that publishes a notice to `cart-checkouts` when a cart is
  checked out; `CheckoutsSeen` reads the notices back from that topic, and `CheckoutsSeenEndpoint`
  serves them.
- `CartMcpEndpoint`: the cart's tools for a customer's assistant, at `/mcp`, behind the client
  keys `CartKeysEndpoint` issues.
- `Main`: the service, registered explicitly.

```bash
sbt shoppingCartJava/test   # the Scala cart's own features against this cart, and the
                            # cross-language bytes check
docker compose up -d && sbt shoppingCartJava/run   # HTTP on :9000
```

`ShoppingCartJavaFeatures` runs `../shopping-cart/features`, the Scala sample's living features,
unchanged, and every scenario runs.
