// Generates prost types from the platform's own protocol files, read in place (a guest library
// copies them in), including the module mode's envelopes in wasm.proto.
fn main() {
    let protocol = "../../../../../protocol/src/main/protobuf";
    let files = [
        "payload",
        "discovery",
        "event_sourced",
        "key_value",
        "workflow",
        "client",
        "view",
        "consumer",
        "timed_action",
        "agent",
        "endpoint",
        "wasm",
    ]
    .map(|f| format!("{protocol}/ankka/protocol/v1/{f}.proto"));
    let fds = protox::compile(files, [protocol]).expect("protocol compiles");
    prost_build::Config::new()
        .compile_fds(fds)
        .expect("prost generates");
    println!("cargo:rerun-if-changed={protocol}");
}
