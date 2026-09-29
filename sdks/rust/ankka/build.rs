// Generates the protocol's messages from the crate's own copy of it (`protocol/`, refreshed by
// `scripts/proto.sh`), so the published crate builds without the repository around it.
use std::path::PathBuf;

fn main() {
    let root = PathBuf::from("protocol/src/main/protobuf");
    let v1 = root.join("ankka/protocol/v1");
    let mut files: Vec<PathBuf> = std::fs::read_dir(&v1)
        .expect("the protocol copy is present: run scripts/proto.sh")
        .map(|entry| entry.expect("a directory entry").path())
        .filter(|path| path.extension().is_some_and(|e| e == "proto"))
        .collect();
    files.sort();
    let descriptors = protox::compile(&files, [&root]).expect("the protocol compiles");
    prost_build::Config::new()
        .compile_fds(descriptors)
        .expect("prost generates the protocol");
    println!("cargo:rerun-if-changed=protocol");
}
