// Generates prost types from the platform's own protocol files (read in place: a PDK would copy
// them in) and the spike's envelope beside them.
fn main() {
    let protocol = "../../../../../protocol/src/main/protobuf";
    let files = [
        format!("{protocol}/ankka/protocol/v1/payload.proto"),
        format!("{protocol}/ankka/protocol/v1/event_sourced.proto"),
        format!("{protocol}/ankka/protocol/v1/discovery.proto"),
        "proto/spike.proto".to_string(),
    ];
    let fds = protox::compile(files, [protocol, "proto"]).expect("protocol compiles");
    prost_build::Config::new().compile_fds(fds).expect("prost generates");
    println!("cargo:rerun-if-changed=proto/spike.proto");
    println!("cargo:rerun-if-changed={protocol}");
}
