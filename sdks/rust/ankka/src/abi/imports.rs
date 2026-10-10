//! The `ankka1` import module: what a module may ask the runtime for. Each import takes an encoded
//! request and answers encoded bytes the runtime allocated in this module's memory, which
//! [`call`] takes ownership of and returns.
//!
//! In a module these are the runtime's functions. Natively — in a unit test — there is no runtime,
//! so the calls go to a [`NativeHost`] the test installs with [`with_native_host`]; with none, a
//! component call is refused as unavailable, a variable reads as unset, and a log line goes to
//! standard error.
//!
//! Three imports are not requests answered with bytes the same way, and each is a function of its
//! own here, linked only by a module that calls it: [`call_request`], a call to another service;
//! [`now`], the runtime's clock; and [`random`], bytes from the runtime's secure source. A module
//! that calls none of them imports none of them, and runs on a runtime from before protocol 1.10.

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
    /// `schedule_recurring`: `ScheduleRecurringRequest` in, `ScheduleRecurringReply` out. Called
    /// through [`call_schedule_recurring`].
    ScheduleRecurring,
    /// `config`: `ConfigRequest` in, `ConfigReply` out.
    Config,
    /// `get_secret`: `GetSecretRequest` in, `GetSecretReply` out. Called through [`call_secret`].
    GetSecret,
    /// `put_secret`: `PutSecretRequest` in, `PutSecretReply` out. Called through [`call_secret`].
    PutSecret,
    /// `delete_secret`: `DeleteSecretRequest` in, `DeleteSecretReply` out. Called through
    /// [`call_secret`].
    DeleteSecret,
    /// `request`: `ServiceRequest` in, `ServiceReply` out. Called through [`call_request`].
    Request,
    /// `await_end`: `AwaitEndRequest` in, `InvokeReply` out (protocol 1.15). Called through
    /// [`call_await`].
    AwaitEnd,
}

/// The most bytes the runtime fills in one call of its `random` import. [`random`] fills a longer
/// buffer in parts.
pub const MAX_RANDOM_BYTES: usize = 65536;

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

/// Calls another service: an encoded `ServiceRequest` in, an encoded `ServiceReply` out. Apart from
/// [`call`] for the reason [`call_secret`] is: a module that never calls another service must not
/// import the function, or it would need a runtime that offers it (protocol 1.10).
///
/// The runtime serves it only to a handler that may wait: a workflow's step, a consumer, a timed
/// action, an agent's handler, tool, guardrail or task rule, and an endpoint's route. Called from
/// an entity's or a workflow's command, from an event being applied or from a view, it does not
/// return: the runtime ends the call there and answers its caller with a fault.
pub fn call_request(request: &[u8]) -> Vec<u8> {
    host::call_request(request)
}

/// The runtime's clock, as milliseconds since the Unix epoch, when it is asked. Natively, the
/// machine's clock, or the time a test fixed.
pub fn now() -> i64 {
    host::now()
}

/// Fills `buf` with random bytes from the runtime's secure source, which nothing seeds. Natively,
/// from the system's source, or with the bytes a test fixed.
pub fn random(buf: &mut [u8]) {
    for part in buf.chunks_mut(MAX_RANDOM_BYTES) {
        host::random(part);
    }
}

/// Calls `schedule_recurring`. Apart from [`call`] for the reason [`call_secret`] is: a module that
/// never sets a recurring timer must not import it, or it would need a runtime that offers one
/// (protocol 1.12).
pub fn call_schedule_recurring(request: &[u8]) -> Vec<u8> {
    host::call_schedule_recurring(request)
}

/// Calls `await_end`: waits for a workflow's end. Apart from [`call`] for the reason
/// [`call_secret`] is: a module that never waits must not import it, or it would need a runtime
/// that offers one (protocol 1.15).
pub fn call_await(request: &[u8]) -> Vec<u8> {
    host::call_await(request)
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

    // Each a block of its own, reached only from the one function that calls it, so the linker
    // drops it from a module that never does.
    #[link(wasm_import_module = "ankka1")]
    unsafe extern "C" {
        fn request(ptr: u32, len: u32) -> u64;
    }

    #[link(wasm_import_module = "ankka1")]
    unsafe extern "C" {
        #[link_name = "now"]
        fn now_import() -> i64;
    }

    #[link(wasm_import_module = "ankka1")]
    unsafe extern "C" {
        #[link_name = "random"]
        fn random_import(ptr: u32, len: u32);
    }

    // Reached only from `call_schedule_recurring`, so a module that sets no recurring timer does
    // not import it.
    #[link(wasm_import_module = "ankka1")]
    unsafe extern "C" {
        fn schedule_recurring(ptr: u32, len: u32) -> u64;
    }

    // Reached only from `call_await`, so a module that never waits for a workflow does not import it.
    #[link(wasm_import_module = "ankka1")]
    unsafe extern "C" {
        fn await_end(ptr: u32, len: u32) -> u64;
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
                Import::Request => panic!("request is called through call_request"),
                Import::ScheduleRecurring => {
                    panic!("schedule_recurring is called through call_schedule_recurring")
                }
                Import::AwaitEnd => panic!("await_end is called through call_await"),
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

    pub(super) fn call_request(asked: &[u8]) -> Vec<u8> {
        let (ptr, len) = (asked.as_ptr() as usize as u32, asked.len() as u32);
        // SAFETY: as for `call`.
        let packed = unsafe { request(ptr, len) };
        let (rptr, rlen) = memory::unpack(packed);
        // SAFETY: the runtime allocated the reply through ankka1_alloc(rlen) and wrote it in full.
        unsafe { memory::take(rptr as i32, rlen as i32) }
    }

    pub(super) fn call_await(request: &[u8]) -> Vec<u8> {
        let (ptr, len) = (request.as_ptr() as usize as u32, request.len() as u32);
        // SAFETY: as for `call`.
        let packed = unsafe { await_end(ptr, len) };
        let (rptr, rlen) = memory::unpack(packed);
        // SAFETY: the runtime allocated the reply through ankka1_alloc(rlen) and wrote it in full.
        unsafe { memory::take(rptr as i32, rlen as i32) }
    }

    pub(super) fn call_schedule_recurring(request: &[u8]) -> Vec<u8> {
        let (ptr, len) = (request.as_ptr() as usize as u32, request.len() as u32);
        // SAFETY: as for `call`.
        let packed = unsafe { schedule_recurring(ptr, len) };
        let (rptr, rlen) = memory::unpack(packed);
        // SAFETY: the runtime allocated the reply through ankka1_alloc(rlen) and wrote it in full.
        unsafe { memory::take(rptr as i32, rlen as i32) }
    }

    pub(super) fn now() -> i64 {
        // SAFETY: the import takes nothing and touches no memory.
        unsafe { now_import() }
    }

    pub(super) fn random(buf: &mut [u8]) {
        // SAFETY: the buffer is ours for the length of the call, and the runtime writes exactly
        // `len` bytes at `ptr`.
        unsafe { random_import(buf.as_mut_ptr() as usize as u32, buf.len() as u32) }
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
pub use native::{
    NativeHost, with_native_clock, with_native_host, with_native_random, with_native_services,
};

#[cfg(not(target_arch = "wasm32"))]
mod native {
    use std::cell::{Cell, RefCell};
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

    /// What answers a call to another service in a test: the request in, the reply out.
    type Services = Rc<dyn Fn(proto::ServiceRequest) -> proto::ServiceReply>;

    thread_local! {
        static SERVICES: RefCell<Option<Services>> = const { RefCell::new(None) };
        static CLOCK: Cell<Option<i64>> = const { Cell::new(None) };
        /// The bytes a test fixed, and how many of them have been handed out.
        static RANDOM: RefCell<Option<(Vec<u8>, usize)>> = const { RefCell::new(None) };
    }

    /// Puts a thread-local back as it was when the scope that changed it ends, however it ends.
    struct Restore<F: FnMut()>(F);
    impl<F: FnMut()> Drop for Restore<F> {
        fn drop(&mut self) {
            (self.0)()
        }
    }

    /// Runs `f` with `services` answering every call to another service made on this thread. It is
    /// apart from the [`NativeHost`], so it works beside whichever host a testkit installs.
    pub fn with_native_services<T>(
        services: impl Fn(proto::ServiceRequest) -> proto::ServiceReply + 'static,
        f: impl FnOnce() -> T,
    ) -> T {
        let mut previous = SERVICES.with(|s| s.replace(Some(Rc::new(services))));
        let _restore = Restore(|| SERVICES.with(|s| *s.borrow_mut() = previous.take()));
        f()
    }

    /// Runs `f` with [`now`](super::now) answering `millis` on this thread.
    pub fn with_native_clock<T>(millis: i64, f: impl FnOnce() -> T) -> T {
        let previous = CLOCK.with(|c| c.replace(Some(millis)));
        let _restore = Restore(|| CLOCK.with(|c| c.set(previous)));
        f()
    }

    /// Runs `f` with [`random`](super::random) handing out `bytes` on this thread, in order and
    /// from the start again when they run out.
    pub fn with_native_random<T>(bytes: &[u8], f: impl FnOnce() -> T) -> T {
        assert!(
            !bytes.is_empty(),
            "a fixed source of random bytes needs at least one byte"
        );
        let mut previous = RANDOM.with(|r| r.replace(Some((bytes.to_vec(), 0))));
        let _restore = Restore(|| RANDOM.with(|r| *r.borrow_mut() = previous.take()));
        f()
    }

    pub(super) fn call_request(request: &[u8]) -> Vec<u8> {
        if let Some(services) = SERVICES.with(|s| s.borrow().clone()) {
            let request = proto::ServiceRequest::decode(request).expect("a ServiceRequest");
            return services(request).encode_to_vec();
        }
        if let Some(host) = installed() {
            return host.call(Import::Request, request);
        }
        proto::ServiceReply {
            result: Some(proto::service_reply::Result::Error(proto::Error {
                message: NO_RUNTIME.into(),
                code: proto::ErrorCode::Unavailable as i32,
                ..Default::default()
            })),
        }
        .encode_to_vec()
    }

    pub(super) fn now() -> i64 {
        CLOCK.with(|c| c.get()).unwrap_or_else(|| {
            let since = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("the machine's clock is after 1970");
            since.as_millis() as i64
        })
    }

    pub(super) fn random(buf: &mut [u8]) {
        let fixed = RANDOM.with(|r| {
            let mut source = r.borrow_mut();
            let Some((bytes, position)) = source.as_mut() else {
                return false;
            };
            for byte in buf.iter_mut() {
                *byte = bytes[*position % bytes.len()];
                *position += 1;
            }
            true
        });
        if !fixed {
            getrandom::fill(buf).expect("the system's source of random bytes answers");
        }
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
            ..Default::default()
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
            ..Default::default()
        };
        match import {
            Import::Config => proto::ConfigReply { value: None }.encode_to_vec(),
            // Sent and not waited for: with no runtime, there is nobody to send it to, and nobody
            // is told.
            Import::Send => Vec::new(),
            Import::Invoke | Import::AwaitEnd => proto::InvokeReply {
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
            Import::Schedule | Import::Cancel => {
                panic!("{NO_RUNTIME} (a timer was {import:?}d)")
            }
            Import::ScheduleRecurring => {
                panic!("{NO_RUNTIME} (a recurring timer was scheduled)")
            }
            Import::GetSecret | Import::PutSecret | Import::DeleteSecret => {
                call_secret(import, request)
            }
            Import::Request => call_request(request),
        }
    }

    pub(super) fn call_schedule_recurring(request: &[u8]) -> Vec<u8> {
        call(Import::ScheduleRecurring, request)
    }

    pub(super) fn call_await(request: &[u8]) -> Vec<u8> {
        call(Import::AwaitEnd, request)
    }

    pub(super) fn log(level: Level, text: &str) {
        match installed() {
            Some(host) => host.log(level, text),
            None => eprintln!("[ankka {level:?}] {text}"),
        }
    }
}
