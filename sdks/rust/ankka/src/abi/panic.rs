//! A module is built with `panic = "abort"`: a panic is a trap, and the runtime answers the call
//! with a fault. Before the trap, this hook sends the panic's message and location to the runtime's
//! log, so the fault names itself rather than reading as `unreachable` executed.

use std::sync::Once;

use super::imports::{Level, log};

static INSTALLED: Once = Once::new();

/// Installs the hook, once. `service!` does this before the first export runs.
pub fn install() {
    INSTALLED.call_once(|| {
        std::panic::set_hook(Box::new(|info| {
            log(Level::Error, &format!("the module {info}"));
        }));
    });
}
