# The module image for a wasm-hosted service: the module and one command that copies it where the
# platform's runtime loads it from. The platform runs this once per pod, as an init container with
# /ankka/module mounted; the runtime image is the platform's own. Build the module first, from
# sdks/rust: cargo build -p shopping-cart --release --target wasm32-unknown-unknown
FROM busybox:1.37
COPY target/wasm32-unknown-unknown/release/shopping_cart.wasm /service.wasm
CMD ["cp", "/service.wasm", "/ankka/module/service.wasm"]
