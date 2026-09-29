//! The module side of `protocol/WASM-ABI.md`: the memory convention, the `ankka1` imports, the
//! `ankka1_` exports `service!` emits, and the panic hook that makes a trap name itself.
//!
//! A service never calls these directly: the client, `config` and the exports are built on them.

pub mod exports;
pub mod imports;
pub mod memory;
pub mod panic;
