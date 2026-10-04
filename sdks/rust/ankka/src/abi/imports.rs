//! The `ankka1` import module: what a module may ask the runtime for. Each import takes an encoded
//! request and answers encoded bytes the runtime allocated in this module's memory, which
//! [`call`] takes ownership of and returns.
//!
//! In a module these are the runtime's functions. Natively — in a unit test — there is no runtime,
//! so the calls go to a [`NativeHost`] the test installs with [`with_native_host`]; with none, a
//! component call is refused as unavailable, a variable reads as unset, and a log line goes to
//! standard error.

/// The imports that take and answer bytes.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Import {
    /// `invoke`: `InvokeRequest` in, `InvokeReply` out.
    Invoke,
    /// `send`: `InvokeRequest` in, nothing out; the call is dispatched and not waited for.
    Send,
    /// `invoke_stream`: `InvokeRequest` in, `StreamTokens` out.
    InvokeStream,
    /// `query`: `QueryRequest` in, `QueryReply` out.
    Query,
    /// `schedule`: `ScheduleRequest` in, `Empty` out.
    Schedule,
    /// `cancel`: `CancelRequest` in, `Empty` out.
    Cancel,
    /// `config`: `ConfigRequest` in, `ConfigReply` out.
    Config,
    /// `get_secret`: `GetSecretRequest` in, `GetSecretReply` out. Called through [`call_secret`].
    GetSecret,
    /// `put_secret`: `PutSecretRequest` in, `PutSecretReply` out. Called through [`call_secret`].
    PutSecret,
    /// `delete_secret`: `DeleteSecretRequest` in, `DeleteSecretReply` out. Called through
    /// [`call_secret`].
    DeleteSecret,
}

/// The level of a log line sent through `ankka1::log`, as the runtime reads it: 0 trace, 1 debug,
/// 2 info, 3 warn, 4 error.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum Level {
    /// Detail nobody reads unless they ask for it.
    Trace = 0,
    /// Detail for whoever is debugging.
    Debug = 1,
    /// What happened.
    Info = 2,
    /// Something is wrong, and it was handled.
    Warn = 3,
    /// Something is wrong, and it was not.
    Error = 4,
}

/// Calls an import with an encoded request, answering the encoded reply. The secret store's
/// imports go through [`call_secret`] instead.
pub fn call(import: Import, request: &[u8]) -> Vec<u8> {
    host::call(import, request)
}

/// Calls one of the secret store's imports. Apart from [`call`] on purpose: a module imports a
/// function only if something it links calls it, and a module that never uses the store must not
/// import it, or it would need a runtime that offers one (protocol 1.4).
pub fn call_secret(import: Import, request: &[u8]) -> Vec<u8> {
    host::call_secret(import, request)
}

/// Sends a line to the runtime's log, under the module's logger.
pub fn log(level: Level, text: &str) {
    host::log(level, text)
}

#[cfg(target_arch = "wasm32")]
mod host {
    use super::{Import, Level};
    use crate::abi::memory;

    #[link(wasm_import_module = "ankka1")]
    unsafe extern "C" {
        fn invoke(ptr: u32, len: u32) -> u64;
        fn send(ptr: u32, len: u32) -> u64;
        fn invoke_stream(ptr: u32, len: u32) -> u64;
        fn query(ptr: u32, len: u32) -> u64;
        fn schedule(ptr: u32, len: u32) -> u64;
        fn cancel(ptr: u32, len: u32) -> u64;
        fn config(ptr: u32, len: u32) -> u64;
        #[link_name = "log"]
        fn log_import(level: i32, ptr: u32, len: u32);
    }

    // A block of their own, reached only from `call_secret`, so the linker drops them from a
    // module that never keeps a secret.
    #[link(wasm_import_module = "ankka1")]
    unsafe extern "C" {
        fn get_secret(ptr: u32, len: u32) -> u64;
        fn put_secret(ptr: u32, len: u32) -> u64;
        fn delete_secret(ptr: u32, len: u32) -> u64;
    }

    pub(super) fn call(import: Import, request: &[u8]) -> Vec<u8> {
        let (ptr, len) = (request.as_ptr() as usize as u32, request.len() as u32);
        // SAFETY: the request outlives the call; the runtime reads it and writes its reply into a
        // buffer it allocates through ankka1_alloc, which is ours from here.
        let packed = unsafe {
            match import {
                Import::Invoke => invoke(ptr, len),
                Import::Send => send(ptr, len),
                Import::InvokeStream => invoke_stream(ptr, len),
                Import::Query => query(ptr, len),
                Import::Schedule => schedule(ptr, len),
                Import::Cancel => cancel(ptr, len),
                Import::Config => config(ptr, len),
                Import::GetSecret | Import::PutSecret | Import::DeleteSecret => {
                    panic!("the secret store's imports are called through call_secret")
                }
            }
        };
        let (rptr, rlen) = memory::unpack(packed);
        // SAFETY: the runtime allocated the reply through ankka1_alloc(rlen) and wrote it in full.
        unsafe { memory::take(rptr as i32, rlen as i32) }
    }

    pub(super) fn call_secret(import: Import, request: &[u8]) -> Vec<u8> {
        let (ptr, len) = (request.as_ptr() as usize as u32, request.len() as u32);
        // SAFETY: as for `call`.
        let packed = unsafe {
            match import {
                Import::GetSecret => get_secret(ptr, len),
                Import::PutSecret => put_secret(ptr, len),
                Import::DeleteSecret => delete_secret(ptr, len),
                other => panic!("{other:?} is not one of the secret store's imports"),
            }
        };
        let (rptr, rlen) = memory::unpack(packed);
        // SAFETY: the runtime allocated the reply through ankka1_alloc(rlen) and wrote it in full.
        unsafe { memory::take(rptr as i32, rlen as i32) }
    }

    pub(super) fn log(level: Level, text: &str) {
        // SAFETY: the runtime reads the text during the call and keeps nothing.
        unsafe {
            log_import(
                level as i32,
                text.as_ptr() as usize as u32,
                text.len() as u32,
            )
        }
    }
}

#[cfg(not(target_arch = "wasm32"))]
use native as host;
#[cfg(not(target_arch = "wasm32"))]
pub use native::{NativeHost, with_native_host};

#[cfg(not(target_arch = "wasm32"))]
mod native {
    use std::cell::RefCell;
    use std::rc::Rc;

    use prost::Message;

    use super::{Import, Level};
    use crate::proto;

    /// What answers the imports when there is no runtime: a test's stand-in.
    pub trait NativeHost {
        /// Answers an import's encoded request with its encoded reply.
        fn call(&self, import: Import, request: &[u8]) -> Vec<u8>;

        /// Receives a log line; standard error by default.
        fn log(&self, level: Level, text: &str) {
            eprintln!("[ankka {level:?}] {text}");
        }
    }

    thread_local! {
        static HOST: RefCell<Option<Rc<dyn NativeHost>>> = const { RefCell::new(None) };
    }

    /// Runs `f` with `host` answering the imports on this thread, then puts back whatever answered
    /// them before.
    pub fn with_native_host<T>(host: impl NativeHost + 'static, f: impl FnOnce() -> T) -> T {
        let previous = HOST.with(|h| h.replace(Some(Rc::new(host))));
        struct Restore(Option<Rc<dyn NativeHost>>);
        impl Drop for Restore {
            fn drop(&mut self) {
                let previous = self.0.take();
                HOST.with(|h| *h.borrow_mut() = previous);
            }
        }
        let _restore = Restore(previous);
        f()
    }

    fn installed() -> Option<Rc<dyn NativeHost>> {
        HOST.with(|h| h.borrow().clone())
    }

    thread_local! {
        /// The secret store a unit test talks to when no host is installed: a map, with the
        /// runtime's rules.
        static SECRETS: RefCell<std::collections::HashMap<String, String>> = RefCell::new(Default::default());
    }

    pub(super) fn call_secret(import: Import, request: &[u8]) -> Vec<u8> {
        if let Some(host) = installed() {
            return host.call(import, request);
        }
        let refused = |message: String| proto::Error {
            message,
            code: proto::ErrorCode::BadRequest as i32,
        };
        match import {
            Import::PutSecret => {
                let r = proto::PutSecretRequest::decode(request).expect("a PutSecretRequest");
                let error = crate::secrets::name_problem(&r.name)
                    .or_else(|| crate::secrets::value_problem(&r.value))
                    .map(refused);
                if error.is_none() {
                    SECRETS.with(|s| s.borrow_mut().insert(r.name, r.value));
                }
                proto::PutSecretReply { error }.encode_to_vec()
            }
            Import::GetSecret => {
                let r = proto::GetSecretRequest::decode(request).expect("a GetSecretRequest");
                let result = match crate::secrets::name_problem(&r.name) {
                    Some(problem) => proto::get_secret_reply::Result::Error(refused(problem)),
                    None => match SECRETS.with(|s| s.borrow().get(&r.name).cloned()) {
                        Some(value) => proto::get_secret_reply::Result::Value(value),
                        None => proto::get_secret_reply::Result::Absent(proto::Empty {}),
                    },
                };
                proto::GetSecretReply {
                    result: Some(result),
                }
                .encode_to_vec()
            }
            Import::DeleteSecret => {
                let r = proto::DeleteSecretRequest::decode(request).expect("a DeleteSecretRequest");
                let error = crate::secrets::name_problem(&r.name).map(refused);
                if error.is_none() {
                    SECRETS.with(|s| s.borrow_mut().remove(&r.name));
                }
                proto::DeleteSecretReply { error }.encode_to_vec()
            }
            other => panic!("{other:?} is not one of the secret store's imports"),
        }
    }

    const NO_RUNTIME: &str = "there is no ankka runtime outside a module; a test answers component calls with a NativeHost";

    pub(super) fn call(import: Import, request: &[u8]) -> Vec<u8> {
        if let Some(host) = installed() {
            return host.call(import, request);
        }
        let unavailable = proto::Error {
            message: NO_RUNTIME.into(),
            code: proto::ErrorCode::Unavailable as i32,
        };
        match import {
            Import::Config => proto::ConfigReply { value: None }.encode_to_vec(),
            // Sent and not waited for: with no runtime, there is nobody to send it to, and nobody
            // is told.
            Import::Send => Vec::new(),
            Import::Invoke => proto::InvokeReply {
                result: Some(proto::invoke_reply::Result::Error(unavailable)),
            }
            .encode_to_vec(),
            Import::Query => proto::QueryReply {
                result: Some(proto::query_reply::Result::Error(unavailable)),
            }
            .encode_to_vec(),
            Import::InvokeStream => {
                let failed = proto::StreamToken {
                    token: Some(proto::stream_token::Token::Failed(unavailable)),
                };
                proto::StreamTokens {
                    tokens: vec![failed],
                }
                .encode_to_vec()
            }
            Import::Schedule | Import::Cancel => panic!("{NO_RUNTIME} (a timer was {import:?}d)"),
            Import::GetSecret | Import::PutSecret | Import::DeleteSecret => {
                call_secret(import, request)
            }
        }
    }

    pub(super) fn log(level: Level, text: &str) {
        match installed() {
            Some(host) => host.log(level, text),
            None => eprintln!("[ankka {level:?}] {text}"),
        }
    }
}
