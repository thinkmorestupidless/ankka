//! The `ankka1_` exports, emitted once per service by [`service!`](crate::service): each takes its
//! request, hands it to the [`Service`] the service's `build` function made, and gives back the
//! reply.
//!
//! The service is built on the first call, once per instance: the runtime may run several
//! instances of a module, and each has its own memory and its own copy.

use std::cell::OnceCell;

use super::{memory, panic};
use crate::service::Service;

/// The exports that carry a request, by the name they are exported under.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Export {
    /// `ankka1_discover`: `SidecarInfo` in, `WasmSpec` out.
    Discover,
    /// `ankka1_handle`: `HandleRequest` in, `HandleReply` out.
    Handle,
    /// `ankka1_fold`: `FoldRequest` in, `FoldReply` out.
    Fold,
    /// `ankka1_run_step`: `StepRequest` in, `StepReply` out.
    RunStep,
    /// `ankka1_close`: `Passivate` in, nothing out.
    Close,
    /// `ankka1_view`: `ViewRequest` in, `ViewEffect` out.
    View,
    /// `ankka1_consumer`: `ConsumerRequest` in, `ConsumerEffect` out.
    Consumer,
    /// `ankka1_timed_action`: `TimedActionRequest` in, `TimedActionEffect` out.
    TimedAction,
    /// `ankka1_plan`: `PlanRequest` in, `PlanReply` out.
    Plan,
    /// `ankka1_invoke_tool`: `ToolRequest` in, `ToolResult` out.
    InvokeTool,
    /// `ankka1_check_guardrail`: `GuardrailRequest` in, `GuardrailResult` out.
    CheckGuardrail,
    /// `ankka1_http`: `HttpRequest` in, `HttpReply` out.
    Http,
}

thread_local! {
    static SERVICE: OnceCell<Service> = const { OnceCell::new() };
}

/// Runs one export: takes the request at `ptr`, answers through the service `build` makes (made on
/// the first call), and gives the reply back packed.
///
/// # Safety
///
/// `ptr` and `len` must describe a request the runtime wrote into a buffer from `ankka1_alloc`, as
/// the ABI says; the exports `service!` emits are the only callers.
pub unsafe fn run(export: Export, ptr: i32, len: i32, build: fn() -> Service) -> i64 {
    // SAFETY: the runtime allocated the request through ankka1_alloc(len) and wrote it in full.
    let request = unsafe { memory::take(ptr, len) };
    let reply = SERVICE.with(|cell| {
        let service = cell.get_or_init(|| {
            panic::install();
            build()
        });
        service.call(export, &request)
    });
    memory::give(reply)
}

/// Emits every `ankka1_` export for the service `build` makes, and installs the panic hook. Once, at
/// the service crate's root:
///
/// ```ignore
/// fn build() -> ankka::Service {
///     ankka::Service::new("ankka-rust").register(ShoppingCart)
/// }
/// ankka::service!(build);
/// ```
///
/// The exports exist only in a WebAssembly build; natively the macro only checks that `build` is a
/// `fn() -> Service`, so a service's unit tests build without a module.
#[macro_export]
macro_rules! service {
    ($build:path) => {
        #[cfg(not(target_arch = "wasm32"))]
        const _: fn() -> $crate::Service = $build;

        #[cfg(target_arch = "wasm32")]
        const _: () = {
            use $crate::abi::exports::{Export, run};

            #[unsafe(no_mangle)]
            pub extern "C" fn ankka1_alloc(len: i32) -> i32 {
                $crate::abi::memory::alloc(len)
            }

            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn ankka1_free(ptr: i32, len: i32) {
                // SAFETY: the runtime hands back a buffer this module gave it, once.
                unsafe { $crate::abi::memory::free(ptr, len) }
            }

            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn ankka1_discover(ptr: i32, len: i32) -> i64 {
                // SAFETY: the runtime calls every export as the ABI says.
                unsafe { run(Export::Discover, ptr, len, $build) }
            }

            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn ankka1_handle(ptr: i32, len: i32) -> i64 {
                // SAFETY: as above.
                unsafe { run(Export::Handle, ptr, len, $build) }
            }

            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn ankka1_fold(ptr: i32, len: i32) -> i64 {
                // SAFETY: as above.
                unsafe { run(Export::Fold, ptr, len, $build) }
            }

            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn ankka1_run_step(ptr: i32, len: i32) -> i64 {
                // SAFETY: as above.
                unsafe { run(Export::RunStep, ptr, len, $build) }
            }

            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn ankka1_close(ptr: i32, len: i32) {
                // SAFETY: as above; the reply is empty.
                unsafe { run(Export::Close, ptr, len, $build) };
            }

            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn ankka1_view(ptr: i32, len: i32) -> i64 {
                // SAFETY: as above.
                unsafe { run(Export::View, ptr, len, $build) }
            }

            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn ankka1_consumer(ptr: i32, len: i32) -> i64 {
                // SAFETY: as above.
                unsafe { run(Export::Consumer, ptr, len, $build) }
            }

            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn ankka1_timed_action(ptr: i32, len: i32) -> i64 {
                // SAFETY: as above.
                unsafe { run(Export::TimedAction, ptr, len, $build) }
            }

            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn ankka1_plan(ptr: i32, len: i32) -> i64 {
                // SAFETY: as above.
                unsafe { run(Export::Plan, ptr, len, $build) }
            }

            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn ankka1_invoke_tool(ptr: i32, len: i32) -> i64 {
                // SAFETY: as above.
                unsafe { run(Export::InvokeTool, ptr, len, $build) }
            }

            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn ankka1_check_guardrail(ptr: i32, len: i32) -> i64 {
                // SAFETY: as above.
                unsafe { run(Export::CheckGuardrail, ptr, len, $build) }
            }

            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn ankka1_http(ptr: i32, len: i32) -> i64 {
                // SAFETY: as above.
                unsafe { run(Export::Http, ptr, len, $build) }
            }
        };
    };
}
