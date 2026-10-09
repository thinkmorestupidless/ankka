//! A keyring in memory, for unit tests of components with personal fields.
//!
//! Outside a module the runtime is not there to fetch a subject's key, so a personal field cannot
//! be written or read: [`with_keyring`] answers the `subject_key` and `lookup_token` imports from a
//! map for the length of a closure, beside whichever host a kit installs. A key is made on a
//! subject's first write, never on a read; [`InMemoryKeyring::erase`] destroys one, and every copy
//! of the subject's fields reads as erased from then on.
//!
//! ```ignore
//! with_keyring(|keyring| {
//!     let mut kit = EventSourcedTestKit::<ItemEntity>::new("i1");
//!     kit.command("set-owner", owner);
//!     keyring.erase("user/u1");
//!     assert!(kit.command("get-item", ()).reply::<Item>().unwrap().owner.unwrap().is_erased());
//! });
//! ```

use std::cell::RefCell;
use std::collections::{HashMap, HashSet};
use std::rc::Rc;

use prost::Message;

use crate::abi::imports::{Import, NativeHost, with_native_keys};
use crate::proto;

/// The project a test's fields belong to when a fetch names none.
const PROJECT: &str = "local";

#[derive(Default)]
struct Keys {
    keys: HashMap<(String, String), Vec<u8>>,
    erased: HashSet<(String, String)>,
    lookup_key: Vec<u8>,
}

/// Subject keys in memory: one per subject, made on its first write.
#[derive(Clone)]
pub struct InMemoryKeyring(Rc<RefCell<Keys>>);

impl Default for InMemoryKeyring {
    fn default() -> InMemoryKeyring {
        InMemoryKeyring::new()
    }
}

impl InMemoryKeyring {
    /// A keyring holding no key.
    pub fn new() -> InMemoryKeyring {
        InMemoryKeyring(Rc::new(RefCell::new(Keys {
            lookup_key: random(32),
            ..Keys::default()
        })))
    }

    /// Destroys `subject`'s key, as an applied erasure does: its fields read as erased, and a new
    /// value for it is refused.
    pub fn erase(&self, subject: &str) {
        let mut keys = self.0.borrow_mut();
        let id = (PROJECT.to_string(), subject.to_string());
        keys.keys.remove(&id);
        keys.erased.insert(id);
    }

    /// Whether `subject` has a key.
    pub fn holds(&self, subject: &str) -> bool {
        self.0
            .borrow()
            .keys
            .contains_key(&(PROJECT.to_string(), subject.to_string()))
    }

    fn refused(subject: String, project: String, reason: &str) -> proto::SubjectKeyReply {
        proto::SubjectKeyReply {
            result: Some(proto::subject_key_reply::Result::Refused(
                proto::SubjectRefused {
                    subject,
                    project,
                    reason: reason.into(),
                    unavailable: false,
                },
            )),
        }
    }

    fn fetch(&self, fetch: proto::KeyFetch) -> proto::SubjectKeyReply {
        let project = if fetch.project.is_empty() {
            PROJECT.to_string()
        } else {
            fetch.project
        };
        let id = (project.clone(), fetch.subject.clone());
        let mut keys = self.0.borrow_mut();
        if keys.erased.contains(&id) {
            return InMemoryKeyring::refused(fetch.subject, project, "erased");
        }
        let key = match keys.keys.get(&id) {
            Some(key) => key.clone(),
            None if fetch.create => keys.keys.entry(id).or_insert_with(|| random(32)).clone(),
            None => return InMemoryKeyring::refused(fetch.subject, project, "unknown"),
        };
        proto::SubjectKeyReply {
            result: Some(proto::subject_key_reply::Result::Key(proto::SubjectKey {
                subject: fetch.subject,
                project,
                key,
                expires_millis: 300_000,
            })),
        }
    }
}

impl NativeHost for InMemoryKeyring {
    fn call(&self, import: Import, request: &[u8]) -> Vec<u8> {
        match import {
            Import::SubjectKey => {
                let fetch = proto::KeyFetch::decode(request)
                    .unwrap_or_else(|e| panic!("a key fetch that does not decode: {e}"));
                self.fetch(fetch).encode_to_vec()
            }
            Import::LookupToken => {
                let r = proto::LookupTokenRequest::decode(request)
                    .unwrap_or_else(|e| panic!("a lookup token request that does not decode: {e}"));
                let token = hmac_hex(&self.0.borrow().lookup_key, &r.plaintext);
                proto::LookupTokenReply {
                    result: Some(proto::lookup_token_reply::Result::Token(token)),
                }
                .encode_to_vec()
            }
            other => panic!("the in-memory keyring answers only keys, not {other:?}"),
        }
    }
}

/// Runs `f` with a fresh keyring answering every key fetch on this thread: what a unit test of a
/// component with personal fields needs. The keyring is handed to `f`, so it can erase a subject.
pub fn with_keyring<T>(f: impl FnOnce(&InMemoryKeyring) -> T) -> T {
    let keyring = InMemoryKeyring::new();
    with_native_keys(keyring.clone(), || f(&keyring))
}

fn random(len: usize) -> Vec<u8> {
    let mut bytes = vec![0u8; len];
    getrandom::fill(&mut bytes).expect("the system's source of random bytes answers");
    bytes
}

/// HMAC-SHA-256, hex: a lookup token as the runtime makes one.
fn hmac_hex(key: &[u8], data: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    let mut k = [0u8; 64];
    k[..key.len()].copy_from_slice(key);
    let mut inner = Sha256::new();
    inner.update(k.iter().map(|b| b ^ 0x36).collect::<Vec<u8>>());
    inner.update(data);
    let mut outer = Sha256::new();
    outer.update(k.iter().map(|b| b ^ 0x5c).collect::<Vec<u8>>());
    outer.update(inner.finalize());
    outer
        .finalize()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::personal::Personal;

    #[test]
    fn a_value_round_trips_and_reads_erased_once_its_subject_is_erased() {
        with_keyring(|keyring| {
            let written = serde_json::to_value(
                Personal::present("user/u1", "ada@example.com".to_string()).unwrap(),
            )
            .unwrap();
            assert!(!written.to_string().contains("ada@example.com"));
            assert!(keyring.holds("user/u1"));
            let read: Personal<String> = serde_json::from_value(written.clone()).unwrap();
            assert_eq!(read.as_ref().map(String::as_str), Some("ada@example.com"));
            keyring.erase("user/u1");
            let erased: Personal<String> = serde_json::from_value(written).unwrap();
            assert!(erased.is_erased());
        });
    }

    #[test]
    fn a_read_makes_no_key() {
        with_keyring(|keyring| {
            let reply = keyring.fetch(proto::KeyFetch {
                subject: "user/never".into(),
                project: String::new(),
                create: false,
            });
            assert!(matches!(
                reply.result,
                Some(proto::subject_key_reply::Result::Refused(_))
            ));
            assert!(!keyring.holds("user/never"));
        });
    }
}
