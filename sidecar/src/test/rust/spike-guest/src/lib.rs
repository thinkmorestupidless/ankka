//! The spike guest. Exports the `ankka1_` functions of `protocol/WASM-ABI.md`, over the protocol's
//! own messages and the envelopes in `wasm.proto`, with a cart small enough to read and real enough
//! to cost something to encode. It is stateless: the host hands it the state on every call.
//!
//! Two handlers exist for the host's tests: `panic` panics (a trap, under `panic = "abort"`), and
//! `config` answers what the `ankka1::config` import returns for the name it is sent.
//!
//! The cart is stateless unless `ANKKA_CONFORMANCE_SHAPE` reads `stateful` through `config`; then
//! it keeps each entity's cart between calls until `ankka1_close`, and every reply's metadata says
//! whether the host handed it the state (`state-present`), which is how the host's suite sees the
//! shape at work. The remaining exports answer fixed replies, so every export the host calls is
//! answered by a module.
//!
//! Bytes cross the boundary as (pointer, length) in the guest's linear memory. A function that
//! returns bytes packs pointer and length into one u64 (pointer high, length low), as Extism
//! does, because Rust's C ABI returns an aggregate through memory rather than as two values.

use prost::Message;
use serde::{Deserialize, Serialize};
use std::cell::{Cell, RefCell};
use std::collections::HashMap;

pub mod ankka {
    pub mod protocol {
        pub mod v1 {
            include!(concat!(env!("OUT_DIR"), "/ankka.protocol.v1.rs"));
        }
    }
}

use ankka::protocol::v1::{
    component, consumer_effect, event_sourced_out, guardrail_result, handle_reply, handle_request,
    http_reply, metadata, outcome, plan_reply, step_outcome, timed_action_effect, tool_result,
    view_effect, workflow_out, AgentPlan, Component, ConfigReply, ConfigRequest, ConsumerEffect,
    Empty, Error, ErrorCode, EventSourcedDetail, FoldReply, FoldRequest, GuardrailResult,
    HandleReply, HandleRequest, Handler, HttpReply, HttpResponse, Kind, Metadata, Outcome,
    Passivate, Payload, PlanReply, SdkInfo, SidecarInfo, Spec, StepOutcome, StepReply, StepRequest,
    TimedActionEffect, ToolResult, ViewEffect, WasmSpec,
};

// ---- the shape --------------------------------------------------------------------------------

thread_local! {
    /// Read once per instance, through the config import.
    static STATEFUL: Cell<Option<bool>> = const { Cell::new(None) };
    /// A stateful cart's state, by entity id, until the host closes it.
    static HELD: RefCell<HashMap<String, Cart>> = RefCell::new(HashMap::new());
}

fn stateful() -> bool {
    STATEFUL.with(|s| match s.get() {
        Some(known) => known,
        None => {
            let known = config("ANKKA_CONFORMANCE_SHAPE").as_deref() == Some("stateful");
            s.set(Some(known));
            known
        }
    })
}

/// The cart to act on: the one handed over if there is one, else the one kept (stateful only).
fn cart_for(entity_id: &str, state: Option<&Payload>) -> Cart {
    match state {
        Some(_) => decode_cart(state),
        None if stateful() => HELD
            .with(|h| h.borrow().get(entity_id).cloned())
            .unwrap_or_default(),
        None => Cart::default(),
    }
}

fn keep(entity_id: &str, cart: &Cart) {
    if stateful() {
        HELD.with(|h| h.borrow_mut().insert(entity_id.to_string(), cart.clone()));
    }
}

fn state_present(present: bool) -> Option<Metadata> {
    Some(Metadata {
        entries: vec![metadata::Entry {
            key: "state-present".into(),
            value: present.to_string(),
        }],
    })
}

// ---- the domain: the cart every sample uses, in the stored spelling ----------------------------

#[derive(Serialize, Deserialize, Clone, Default)]
struct Cart {
    items: Vec<Item>,
}

#[derive(Serialize, Deserialize, Clone)]
struct Item {
    #[serde(rename = "productId")]
    product_id: String,
    name: String,
    quantity: i32,
}

/// serde's internally tagged enum writes exactly the encoding's `"type"` discriminator.
#[derive(Serialize, Deserialize, Clone)]
#[serde(tag = "type")]
enum Event {
    ItemAdded { item: Item },
}

fn fold(mut cart: Cart, event: &Event) -> Cart {
    match event {
        Event::ItemAdded { item } => cart.items.push(item.clone()),
    }
    cart
}

// ---- memory ------------------------------------------------------------------------------------

#[no_mangle]
pub extern "C" fn ankka1_alloc(len: u32) -> u32 {
    let mut buf = Vec::<u8>::with_capacity(len as usize);
    let ptr = buf.as_mut_ptr();
    std::mem::forget(buf);
    ptr as u32
}

#[no_mangle]
pub extern "C" fn ankka1_free(ptr: u32, len: u32) {
    unsafe {
        drop(Vec::from_raw_parts(
            ptr as *mut u8,
            len as usize,
            len as usize,
        ))
    }
}

fn take(ptr: u32, len: u32) -> Vec<u8> {
    unsafe { Vec::from_raw_parts(ptr as *mut u8, len as usize, len as usize) }
}

fn give(bytes: Vec<u8>) -> u64 {
    let boxed = bytes.into_boxed_slice();
    let len = boxed.len() as u64;
    let ptr = Box::into_raw(boxed) as *mut u8 as u64;
    (ptr << 32) | len
}

fn json(manifest: &str, value: &impl Serialize) -> Payload {
    Payload {
        content_type: "application/json".into(),
        manifest: manifest.into(),
        data: serde_json::to_vec(value).expect("serializes"),
    }
}

fn decode_cart(state: Option<&Payload>) -> Cart {
    match state {
        Some(p) if !p.data.is_empty() => serde_json::from_slice(&p.data).expect("a cart"),
        _ => Cart::default(),
    }
}

fn text(value: &str) -> Payload {
    Payload {
        content_type: "text/plain".into(),
        manifest: "string".into(),
        data: value.as_bytes().to_vec(),
    }
}

fn done() -> Payload {
    Payload {
        content_type: "application/octet-stream".into(),
        manifest: "done".into(),
        data: vec![],
    }
}

// ---- the exports -------------------------------------------------------------------------------

#[no_mangle]
pub extern "C" fn ankka1_discover(ptr: u32, len: u32) -> u64 {
    let info = SidecarInfo::decode(take(ptr, len).as_slice()).expect("SidecarInfo");
    let spec = Spec {
        protocol_version: info.protocol_version,
        sdk: Some(SdkInfo {
            name: "ankka-spike-guest".into(),
            version: "0.0.0".into(),
        }),
        components: vec![Component {
            kind: Kind::EventSourcedEntity as i32,
            id: "cart".into(),
            handlers: vec![
                Handler {
                    name: "add-item".into(),
                    read_only: false,
                    streaming: false,
                },
                Handler {
                    name: "get-cart".into(),
                    read_only: true,
                    streaming: false,
                },
                Handler {
                    name: "panic".into(),
                    read_only: true,
                    streaming: false,
                },
                Handler {
                    name: "config".into(),
                    read_only: true,
                    streaming: false,
                },
            ],
            detail: Some(component::Detail::EventSourced(EventSourcedDetail {
                snapshot_every: 100,
            })),
        }],
        endpoints: vec![],
    };
    let shapes = if stateful() {
        vec!["cart".to_string()]
    } else {
        vec![]
    };
    let wasm = WasmSpec {
        spec: Some(spec),
        stateful: shapes,
        abi_version: "1".into(),
    };
    give(wasm.encode_to_vec())
}

#[no_mangle]
pub extern "C" fn ankka1_handle(ptr: u32, len: u32) -> u64 {
    let request = HandleRequest::decode(take(ptr, len).as_slice()).expect("HandleRequest");
    let command = match request.command.expect("a command") {
        handle_request::Command::EventSourced(c) => c,
        _ => panic!("the cart is an event sourced entity"),
    };
    let present = request.state.is_some();
    let cart = cart_for(&request.entity_id, request.state.as_ref());
    let (events, cart, outcome) = match command.name.as_str() {
        "add-item" => {
            let item: Item =
                serde_json::from_slice(&command.payload.expect("payload").data).expect("an Item");
            let event = Event::ItemAdded { item };
            let cart = fold(cart, &event);
            let reply = outcome::Reply {
                payload: Some(done()),
                metadata: state_present(present),
            };
            (
                vec![json("Event", &event)],
                cart,
                outcome::Outcome::Reply(reply),
            )
        }
        "get-cart" => {
            let reply = outcome::Reply {
                payload: Some(json("Cart", &cart)),
                metadata: state_present(present),
            };
            (vec![], cart, outcome::Outcome::Reply(reply))
        }
        "panic" => panic!("the panic handler panicked, as asked"),
        "config" => {
            let name = String::from_utf8(command.payload.expect("payload").data).expect("a name");
            match config(&name) {
                Some(value) => {
                    let reply = outcome::Reply {
                        payload: Some(text(&value)),
                        metadata: None,
                    };
                    (vec![], cart, outcome::Outcome::Reply(reply))
                }
                None => {
                    let message = format!("no variable {name}");
                    let error = Error {
                        message,
                        code: ErrorCode::NotFound as i32,
                    };
                    (vec![], cart, outcome::Outcome::Error(error))
                }
            }
        }
        other => {
            let error = Error {
                message: format!("no handler {other}"),
                code: ErrorCode::NotFound as i32,
            };
            (vec![], cart, outcome::Outcome::Error(error))
        }
    };
    keep(&request.entity_id, &cart);
    let reply = HandleReply {
        reply: Some(handle_reply::Reply::EventSourced(
            event_sourced_out::Reply {
                command_id: command.id,
                events,
                retention: None,
                outcome: Some(Outcome {
                    outcome: Some(outcome),
                }),
                snapshot: None,
            },
        )),
        state: Some(json("Cart", &cart)),
        failure: None,
    };
    give(reply.encode_to_vec())
}

#[no_mangle]
pub extern "C" fn ankka1_fold(ptr: u32, len: u32) -> u64 {
    let request = FoldRequest::decode(take(ptr, len).as_slice()).expect("FoldRequest");
    let cart = cart_for(&request.entity_id, request.state.as_ref());
    let event: Event =
        serde_json::from_slice(&request.event.expect("event").data).expect("an Event");
    let cart = fold(cart, &event);
    keep(&request.entity_id, &cart);
    let reply = FoldReply {
        state: Some(json("Cart", &cart)),
        failure: None,
    };
    give(reply.encode_to_vec())
}

#[no_mangle]
pub extern "C" fn ankka1_close(ptr: u32, len: u32) {
    let passivate = Passivate::decode(take(ptr, len).as_slice()).expect("Passivate");
    HELD.with(|h| h.borrow_mut().remove(&passivate.entity_id));
}

// ---- the other exports: fixed answers, so the host's dispatch of each is exercised -------------

#[no_mangle]
pub extern "C" fn ankka1_run_step(ptr: u32, len: u32) -> u64 {
    let request = StepRequest::decode(take(ptr, len).as_slice()).expect("StepRequest");
    let id = request.run_step.map(|r| r.id).unwrap_or_default();
    let end = StepOutcome {
        outcome: Some(step_outcome::Outcome::End(step_outcome::End {})),
    };
    let reply = StepReply {
        reply: Some(workflow_out::StepReply {
            command_id: id,
            new_state: None,
            next: Some(end),
        }),
        state: request.state,
        failure: None,
    };
    give(reply.encode_to_vec())
}

fn ignoring(ptr: u32, len: u32, reply: impl Message) -> u64 {
    drop(take(ptr, len));
    give(reply.encode_to_vec())
}

#[no_mangle]
pub extern "C" fn ankka1_view(ptr: u32, len: u32) -> u64 {
    ignoring(
        ptr,
        len,
        ViewEffect {
            effect: Some(view_effect::Effect::Ignore(Empty {})),
        },
    )
}

#[no_mangle]
pub extern "C" fn ankka1_consumer(ptr: u32, len: u32) -> u64 {
    ignoring(
        ptr,
        len,
        ConsumerEffect {
            effect: Some(consumer_effect::Effect::Done(Empty {})),
        },
    )
}

#[no_mangle]
pub extern "C" fn ankka1_timed_action(ptr: u32, len: u32) -> u64 {
    let done = TimedActionEffect {
        effect: Some(timed_action_effect::Effect::Done(Empty {})),
    };
    ignoring(ptr, len, done)
}

#[no_mangle]
pub extern "C" fn ankka1_plan(ptr: u32, len: u32) -> u64 {
    let plan = AgentPlan {
        user: Some("hello".into()),
        ..Default::default()
    };
    ignoring(
        ptr,
        len,
        PlanReply {
            message: Some(plan_reply::Message::Plan(plan)),
        },
    )
}

#[no_mangle]
pub extern "C" fn ankka1_invoke_tool(ptr: u32, len: u32) -> u64 {
    ignoring(
        ptr,
        len,
        ToolResult {
            result: Some(tool_result::Result::Ok("tool ran".into())),
        },
    )
}

#[no_mangle]
pub extern "C" fn ankka1_check_guardrail(ptr: u32, len: u32) -> u64 {
    let pass = GuardrailResult {
        result: Some(guardrail_result::Result::Pass(Empty {})),
    };
    ignoring(ptr, len, pass)
}

#[no_mangle]
pub extern "C" fn ankka1_http(ptr: u32, len: u32) -> u64 {
    let response = HttpResponse {
        status: 200,
        content_type: "text/plain".into(),
        body: b"hello".to_vec(),
        headers: vec![],
    };
    ignoring(
        ptr,
        len,
        HttpReply {
            message: Some(http_reply::Message::Response(response)),
        },
    )
}

// ---- a host call, for the blocking measurement -------------------------------------------------

#[link(wasm_import_module = "ankka1")]
extern "C" {
    /// The host's `Client.invoke`: bytes in, packed bytes out, allocated through `ankka1_alloc`.
    fn invoke(ptr: u32, len: u32) -> u64;

    /// One of the descriptor's variables: `ConfigRequest` in, `ConfigReply` out.
    #[link_name = "config"]
    fn config_import(ptr: u32, len: u32) -> u64;
}

fn config(name: &str) -> Option<String> {
    let request = ConfigRequest { name: name.into() }.encode_to_vec();
    let packed = unsafe { config_import(request.as_ptr() as u32, request.len() as u32) };
    let (rptr, rlen) = ((packed >> 32) as u32, (packed & 0xffff_ffff) as u32);
    let reply = if rlen == 0 { vec![] } else { take(rptr, rlen) };
    ConfigReply::decode(reply.as_slice())
        .expect("ConfigReply")
        .value
}

/// Passes the request to the host and returns what the host answered, so the spike can measure a
/// host function that blocks on a `Future` from inside the guest.
#[no_mangle]
pub extern "C" fn ankka1_call_out(ptr: u32, len: u32) -> u64 {
    let request = take(ptr, len);
    let packed = unsafe { invoke(request.as_ptr() as u32, request.len() as u32) };
    let (rptr, rlen) = ((packed >> 32) as u32, (packed & 0xffff_ffff) as u32);
    give(take(rptr, rlen))
}
