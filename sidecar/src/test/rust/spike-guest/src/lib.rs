//! The spike guest. Exports the `ankka1_` functions the treatment sketches, over the protocol's
//! own messages, with a cart small enough to read and real enough to cost something to encode.
//!
//! Bytes cross the boundary as (pointer, length) in the guest's linear memory. A function that
//! returns bytes packs pointer and length into one u64 (pointer high, length low), as Extism
//! does, because Rust's C ABI returns an aggregate through memory rather than as two values.

use prost::Message;
use serde::{Deserialize, Serialize};

pub mod ankka {
    pub mod protocol {
        pub mod v1 {
            include!(concat!(env!("OUT_DIR"), "/ankka.protocol.v1.rs"));
        }
    }
    pub mod spike {
        include!(concat!(env!("OUT_DIR"), "/ankka.spike.rs"));
    }
}

use ankka::protocol::v1::{
    component, event_sourced_out, outcome, Component, Error, ErrorCode, EventSourcedDetail,
    Handler, Kind, Outcome, Payload, SdkInfo, SidecarInfo, Spec,
};
use ankka::spike::{FoldRequest, HandleReply, HandleRequest};

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
    unsafe { drop(Vec::from_raw_parts(ptr as *mut u8, len as usize, len as usize)) }
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
        sdk: Some(SdkInfo { name: "ankka-spike-guest".into(), version: "0.0.0".into() }),
        components: vec![Component {
            kind: Kind::EventSourcedEntity as i32,
            id: "cart".into(),
            handlers: vec![
                Handler { name: "add-item".into(), read_only: false, streaming: false },
                Handler { name: "get-cart".into(), read_only: true, streaming: false },
            ],
            detail: Some(component::Detail::EventSourced(EventSourcedDetail {
                snapshot_every: 100,
            })),
        }],
        endpoints: vec![],
    };
    give(spec.encode_to_vec())
}

#[no_mangle]
pub extern "C" fn ankka1_handle(ptr: u32, len: u32) -> u64 {
    let request = HandleRequest::decode(take(ptr, len).as_slice()).expect("HandleRequest");
    let command = request.command.expect("a command");
    let cart = decode_cart(request.state.as_ref());
    let (events, cart, outcome) = match command.name.as_str() {
        "add-item" => {
            let item: Item =
                serde_json::from_slice(&command.payload.expect("payload").data).expect("an Item");
            let event = Event::ItemAdded { item };
            let cart = fold(cart, &event);
            let reply = outcome::Reply { payload: Some(done()), metadata: None };
            (vec![json("Event", &event)], cart, outcome::Outcome::Reply(reply))
        }
        "get-cart" => {
            let reply = outcome::Reply { payload: Some(json("Cart", &cart)), metadata: None };
            (vec![], cart, outcome::Outcome::Reply(reply))
        }
        other => {
            let error = Error { message: format!("no handler {other}"), code: ErrorCode::NotFound as i32 };
            (vec![], cart, outcome::Outcome::Error(error))
        }
    };
    let reply = HandleReply {
        reply: Some(event_sourced_out::Reply {
            command_id: command.id,
            events,
            retention: None,
            outcome: Some(Outcome { outcome: Some(outcome) }),
            snapshot: None,
        }),
        state: Some(json("Cart", &cart)),
    };
    give(reply.encode_to_vec())
}

#[no_mangle]
pub extern "C" fn ankka1_fold(ptr: u32, len: u32) -> u64 {
    let request = FoldRequest::decode(take(ptr, len).as_slice()).expect("FoldRequest");
    let cart = decode_cart(request.state.as_ref());
    let event: Event =
        serde_json::from_slice(&request.event.expect("event").data).expect("an Event");
    give(json("Cart", &fold(cart, &event)).encode_to_vec())
}

// ---- a host call, for the blocking measurement -------------------------------------------------

#[link(wasm_import_module = "ankka1")]
extern "C" {
    /// The host's `Client.invoke`: bytes in, packed bytes out, allocated through `ankka1_alloc`.
    fn invoke(ptr: u32, len: u32) -> u64;
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
