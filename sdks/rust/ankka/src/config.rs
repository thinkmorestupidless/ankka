//! The service's configuration: the variables its descriptor sets. A module has no environment of
//! its own; the runtime answers for it, and withholds every variable the platform reserves (the
//! database's credentials, the cluster's settings), so a module can read only what its descriptor
//! declared.

use prost::Message;

use crate::abi::imports::{Import, call};
use crate::proto;

/// The value of the descriptor variable `name`: `None` when it is unset, or reserved by the
/// platform.
pub fn config(name: &str) -> Option<String> {
    let request = proto::ConfigRequest {
        name: name.to_string(),
    }
    .encode_to_vec();
    let reply = call(Import::Config, &request);
    proto::ConfigReply::decode(reply.as_slice())
        .unwrap_or_else(|e| {
            panic!("the runtime's answer to config({name:?}) is not a ConfigReply: {e}")
        })
        .value
}

#[cfg(all(test, not(target_arch = "wasm32")))]
mod tests {
    use super::*;
    use crate::abi::imports::{NativeHost, with_native_host};

    struct Env;

    impl NativeHost for Env {
        fn call(&self, import: Import, request: &[u8]) -> Vec<u8> {
            assert_eq!(import, Import::Config);
            let name = proto::ConfigRequest::decode(request).unwrap().name;
            let value = (name == "GREETING").then(|| "hello".to_string());
            proto::ConfigReply { value }.encode_to_vec()
        }
    }

    #[test]
    fn a_variable_is_read_from_whatever_answers_for_the_runtime() {
        with_native_host(Env, || {
            assert_eq!(config("GREETING").as_deref(), Some("hello"));
            assert_eq!(config("MISSING"), None);
        });
        assert_eq!(config("GREETING"), None, "no runtime: nothing is set");
    }
}
