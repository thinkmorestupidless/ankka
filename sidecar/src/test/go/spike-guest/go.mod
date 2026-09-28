// The spike's Go guest: the same exports as the Rust one, built with TinyGo to answer whether a
// small Go module can speak the protocol's messages without reflection. See
// ankka-deployments design/wasm-hosting.md, "What the spike must answer", item 5. Not a PDK and not published.
module ankka-spike-guest

go 1.26

require google.golang.org/protobuf v1.36.11
