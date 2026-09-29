//! The protocol's messages, generated from the crate's copy of `protocol/` by `build.rs`: what
//! crosses the module's linear memory, in the ABI's own names.

// Comments are the protocol files' own, written for protobuf rather than rustdoc.
#[allow(missing_docs, clippy::all, rustdoc::all)]
pub mod ankka {
    pub mod protocol {
        pub mod v1 {
            include!(concat!(env!("OUT_DIR"), "/ankka.protocol.v1.rs"));
        }
    }
}

pub use ankka::protocol::v1::*;
