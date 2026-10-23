# The shopping cart, in Go

The Scala sample's cart written with the Go SDK: the same component id, wire names, manifests and
routes, so a Go cart and a Scala cart read each other's journal.

Run it beside the bundled sidecar and Postgres, from the repository root:

```bash
docker compose --profile polyglot up -d
cd sdks/go/examples/shopping-cart && go run ./cmd/cart
curl -X POST localhost:9000/carts/c1/items -H 'content-type: application/json' \
  -d '{"productId":"pen","name":"Pen","quantity":2}'
curl localhost:9000/carts/c1
```

The image is built by `scripts/image.sh`, which stages the SDK beside the example so the module's
`replace` resolves inside the build. `go test ./...` runs the unit tests; `ANKKA_SLOW=1 go test ./...`
also starts a sidecar and a database in containers.
