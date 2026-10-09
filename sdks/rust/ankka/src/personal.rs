//! Personal fields (protocol 1.15): a value of one data subject, kept encrypted under that
//! subject's key.
//!
//! A field of type [`Personal<T>`] is written as the personal envelope every SDK writes,
//! `{"subject", "project", "data"[, "lookup"]}`, where `data` is AES-256-GCM over the value's JSON
//! with the subject and project as associated data. When the data subject is erased its key is
//! destroyed, and every copy of the field reads as [`Personal::Erased`] from then on.
//!
//! The keys come from the runtime, through the `subject_key` import, which a module imports only
//! if it has a personal field. The runtime holds the keyring's channel and its cache, so a module
//! keeps no key of its own beyond the call that needed it.

use std::cell::{Cell, RefCell};

use aes_gcm::aead::{Aead, KeyInit, Payload as AeadPayload};
use aes_gcm::{Aes256Gcm, Nonce};
use prost::Message;
use serde::de::{DeserializeOwned, Error as _};
use serde::ser::{Error as _, SerializeMap};
use serde::{Deserialize, Deserializer, Serialize, Serializer};

use crate::abi::imports;
use crate::codec::base64;
use crate::effects::{CommandError, ErrorCode};
use crate::proto;

/// A data subject that is not one: empty, longer than 253 characters, or holding a character other
/// than letters, digits, `.`, `_`, `-`, `/` and `:`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DataSubjectError(pub String);

impl std::fmt::Display for DataSubjectError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for DataSubjectError {}

/// Whether `subject` is a data subject, and why not.
pub fn check_subject(subject: &str) -> Result<(), DataSubjectError> {
    if subject.is_empty() {
        return Err(DataSubjectError("a data subject is required".into()));
    }
    if subject.chars().count() > 253 {
        return Err(DataSubjectError(format!(
            "a data subject is at most 253 characters, not {}",
            subject.chars().count()
        )));
    }
    if !subject
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-' | '/' | ':'))
    {
        return Err(DataSubjectError(format!(
            "a data subject is letters, digits, '.', '_', '-', '/' and ':' only: '{subject}'"
        )));
    }
    Ok(())
}

/// A value of one data subject.
#[derive(Clone)]
pub enum Personal<T> {
    /// The value, readable while the subject's key exists.
    Present {
        /// The data subject the value belongs to.
        subject: String,
        /// The value.
        value: T,
        /// Whether a view's declared query may match the field by equality.
        lookup: bool,
        /// The project the value belongs to, when read from another project's envelope.
        project: Option<String>,
        /// Read from a store, or written once: re-written for an erased subject, it is written
        /// erased rather than refused.
        stored: Cell<bool>,
    },
    /// The subject is erased: the value is gone.
    Erased {
        /// The data subject that was erased.
        subject: String,
        /// The project, when known.
        project: Option<String>,
    },
}

impl<T> Personal<T> {
    /// A personal value of `subject`.
    pub fn present(subject: impl Into<String>, value: T) -> Result<Personal<T>, DataSubjectError> {
        let subject = subject.into();
        check_subject(&subject)?;
        Ok(Personal::Present {
            subject,
            value,
            lookup: false,
            project: None,
            stored: Cell::new(false),
        })
    }

    /// A personal value of `subject` that a view's declared query may match by equality.
    pub fn lookup(subject: impl Into<String>, value: T) -> Result<Personal<T>, DataSubjectError> {
        let mut made = Personal::present(subject, value)?;
        if let Personal::Present { lookup, .. } = &mut made {
            *lookup = true;
        }
        Ok(made)
    }

    /// The same value marked for lookup, for a view's row: the journal carries no token, so a value
    /// read from an event is marked again where the row a declared query matches is written.
    pub fn for_lookup(self) -> Personal<T> {
        match self {
            Personal::Present {
                subject,
                value,
                project,
                stored,
                ..
            } => Personal::Present {
                subject,
                value,
                lookup: true,
                project,
                stored,
            },
            erased => erased,
        }
    }

    /// The data subject.
    pub fn subject(&self) -> &str {
        match self {
            Personal::Present { subject, .. } | Personal::Erased { subject, .. } => subject,
        }
    }

    /// The value, or `None` when the subject is erased — including a value an instance has held
    /// since before the erasure. A module hears of no erasure by itself, so a stored value asks the
    /// runtime, whose cache answers at once, whether its subject is still there.
    pub fn as_ref(&self) -> Option<&T> {
        match self {
            Personal::Present {
                value,
                subject,
                project,
                stored,
                ..
            } => {
                if stored.get() && matches!(key(project.as_deref(), subject, false), Key::Erased(_))
                {
                    None
                } else {
                    Some(value)
                }
            }
            Personal::Erased { .. } => None,
        }
    }

    /// Whether the subject is erased.
    pub fn is_erased(&self) -> bool {
        self.as_ref().is_none()
    }
}

impl<T: PartialEq> PartialEq for Personal<T> {
    fn eq(&self, other: &Self) -> bool {
        self.subject() == other.subject() && self.as_ref() == other.as_ref()
    }
}

// Never the value: a personal field must not reach a log line by being printed.
impl<T> std::fmt::Debug for Personal<T> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Personal::Present { subject, .. } => write!(f, "Present({subject}, <personal>)"),
            Personal::Erased { subject, .. } => write!(f, "Erased({subject})"),
        }
    }
}

// ── Keys ─────────────────────────────────────────────────────────────────────

/// What the runtime answers for a subject's key.
#[derive(Debug, Clone)]
enum Key {
    Available(String, Vec<u8>),
    Erased(String),
    Refused(String, String),
    Unavailable(String),
}

fn key(project: Option<&str>, subject: &str, create: bool) -> Key {
    let request = proto::KeyFetch {
        subject: subject.to_string(),
        project: project.unwrap_or_default().to_string(),
        create,
    }
    .encode_to_vec();
    let reply = proto::SubjectKeyReply::decode(imports::call_subject_key(&request).as_slice())
        .unwrap_or_default();
    match reply.result {
        Some(proto::subject_key_reply::Result::Key(k)) => Key::Available(k.project, k.key),
        Some(proto::subject_key_reply::Result::Refused(r)) if r.unavailable => {
            Key::Unavailable(r.reason)
        }
        Some(proto::subject_key_reply::Result::Refused(r))
            if r.reason == "erased" || r.reason == "unknown" =>
        {
            Key::Erased(r.project)
        }
        Some(proto::subject_key_reply::Result::Refused(r)) => Key::Refused(r.project, r.reason),
        None => Key::Unavailable("the runtime answered nothing".into()),
    }
}

fn lookup_token(plaintext: &[u8]) -> Result<String, CommandError> {
    let request = proto::LookupTokenRequest {
        plaintext: plaintext.to_vec(),
    }
    .encode_to_vec();
    let reply = proto::LookupTokenReply::decode(imports::call_lookup_token(&request).as_slice())
        .unwrap_or_default();
    match reply.result {
        Some(proto::lookup_token_reply::Result::Token(token)) => Ok(token),
        Some(proto::lookup_token_reply::Result::Error(e)) => {
            Err(CommandError::new(ErrorCode::Unavailable, e.message))
        }
        None => Err(CommandError::new(
            ErrorCode::Unavailable,
            "the runtime made no lookup token",
        )),
    }
}

/// The lookup token of `value`: what a view's declared query compares a personal field marked for
/// lookup with. Made by the runtime with the project's lookup key, which a module never holds; a
/// token shows only that two values are equal.
pub fn lookup_token_of<V: Serialize>(value: &V) -> Result<String, CommandError> {
    let plaintext = serde_json::to_vec(value)
        .map_err(|e| CommandError::new(ErrorCode::BadRequest, e.to_string()))?;
    lookup_token(&plaintext)
}

thread_local! {
    static LOOKUP_ALLOWED: Cell<bool> = const { Cell::new(false) };
    static REFUSAL: RefCell<Option<CommandError>> = const { RefCell::new(None) };
}

/// Runs `f` with lookup tokens written: around a view's row write, the one place one belongs.
pub fn allowing_lookup<A>(f: impl FnOnce() -> A) -> A {
    let before = LOOKUP_ALLOWED.with(|l| l.replace(true));
    struct Restore(bool);
    impl Drop for Restore {
        fn drop(&mut self) {
            LOOKUP_ALLOWED.with(|l| l.set(self.0));
        }
    }
    let _restore = Restore(before);
    f()
}

/// Why the last write of a personal field on this thread was refused, taken: what a host answers a
/// command with in place of a fault.
pub fn take_refusal() -> Option<CommandError> {
    REFUSAL.with(|r| r.borrow_mut().take())
}

fn refuse<E: serde::ser::Error>(code: ErrorCode, message: String) -> E {
    REFUSAL.with(|r| *r.borrow_mut() = Some(CommandError::new(code, message.clone())));
    E::custom(message)
}

fn aad(subject: &str, project: &str) -> Vec<u8> {
    format!("{subject}\u{0}{project}").into_bytes()
}

impl<T: Serialize> Serialize for Personal<T> {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        let erased = |serializer: S, subject: &str, project: &str| {
            let mut map = serializer.serialize_map(Some(2))?;
            map.serialize_entry("subject", subject)?;
            map.serialize_entry("project", project)?;
            map.end()
        };
        match self {
            Personal::Erased { subject, project } => {
                let project = match project {
                    Some(p) => p.clone(),
                    None => match key(None, subject, false) {
                        Key::Available(p, _) | Key::Erased(p) | Key::Refused(p, _) => p,
                        Key::Unavailable(reason) => {
                            return Err(refuse(
                                ErrorCode::Unavailable,
                                format!("no keyring is available: {reason}"),
                            ));
                        }
                    },
                };
                erased(serializer, subject, &project)
            }
            Personal::Present {
                subject,
                value,
                lookup,
                project,
                stored,
            } => match key(project.as_deref(), subject, project.is_none()) {
                Key::Available(project, k) => {
                    let plaintext = serde_json::to_vec(value).map_err(S::Error::custom)?;
                    let mut nonce = [0u8; 12];
                    imports::random(&mut nonce);
                    let cipher = Aes256Gcm::new_from_slice(&k).map_err(S::Error::custom)?;
                    let sealed = cipher
                        .encrypt(
                            Nonce::from_slice(&nonce),
                            AeadPayload {
                                msg: &plaintext,
                                aad: &aad(subject, &project),
                            },
                        )
                        .map_err(S::Error::custom)?;
                    let mut data = Vec::with_capacity(1 + 12 + sealed.len());
                    data.push(1u8);
                    data.extend_from_slice(&nonce);
                    data.extend_from_slice(&sealed);
                    let token = if *lookup && LOOKUP_ALLOWED.with(|l| l.get()) {
                        Some(lookup_token(&plaintext).map_err(|e| {
                            refuse::<S::Error>(ErrorCode::Unavailable, e.message.clone())
                        })?)
                    } else {
                        None
                    };
                    let mut map = serializer.serialize_map(None)?;
                    map.serialize_entry("subject", subject)?;
                    map.serialize_entry("project", &project)?;
                    map.serialize_entry("data", &base64::encode(&data))?;
                    if let Some(token) = token {
                        map.serialize_entry("lookup", &token)?;
                    }
                    stored.set(true);
                    map.end()
                }
                // Carried, not new: a state holding what was stored before the erasure.
                Key::Erased(project) if stored.get() => erased(serializer, subject, &project),
                Key::Erased(project) => Err(refuse(
                    ErrorCode::BadRequest,
                    format!(
                        "data subject {subject} is erased in project {project}: no personal field can be written for it"
                    ),
                )),
                Key::Refused(project, reason) => Err(refuse(
                    ErrorCode::Forbidden,
                    format!(
                        "the keyring refused data subject {subject} of project {project}: {reason}"
                    ),
                )),
                Key::Unavailable(reason) => Err(refuse(
                    ErrorCode::Unavailable,
                    format!("no keyring is available: {reason}"),
                )),
            },
        }
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Envelope {
    subject: String,
    project: String,
    data: Option<String>,
    lookup: Option<String>,
}

impl<'de, T: DeserializeOwned> Deserialize<'de> for Personal<T> {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let envelope = Envelope::deserialize(deserializer)?;
        if check_subject(&envelope.subject).is_err() {
            return Err(D::Error::custom(
                "a personal envelope needs a valid subject",
            ));
        }
        if envelope.project.is_empty() {
            return Err(D::Error::custom("a personal envelope needs its project"));
        }
        let Envelope {
            subject,
            project,
            data,
            lookup,
        } = envelope;
        let Some(data) = data else {
            return Ok(Personal::Erased {
                subject,
                project: Some(project),
            });
        };
        let k = match key(Some(&project), &subject, false) {
            Key::Available(_, k) => k,
            Key::Erased(_) | Key::Refused(_, _) => {
                return Ok(Personal::Erased {
                    subject,
                    project: Some(project),
                });
            }
            Key::Unavailable(reason) => {
                return Err(D::Error::custom(format!(
                    "no keyring is available: {reason}"
                )));
            }
        };
        let corrupt = || {
            D::Error::custom(format!(
                "personal envelope corrupt: it does not open as {subject} of {project}"
            ))
        };
        let stored = base64::decode(&data)
            .map_err(|_| D::Error::custom("personal envelope corrupt: data is not base64"))?;
        if stored.len() < 1 + 12 + 16 || stored[0] != 1 {
            return Err(corrupt());
        }
        let cipher = Aes256Gcm::new_from_slice(&k).map_err(|_| corrupt())?;
        let plaintext = cipher
            .decrypt(
                Nonce::from_slice(&stored[1..13]),
                AeadPayload {
                    msg: &stored[13..],
                    aad: &aad(&subject, &project),
                },
            )
            .map_err(|_| corrupt())?;
        let value: T = serde_json::from_slice(&plaintext).map_err(D::Error::custom)?;
        Ok(Personal::Present {
            subject,
            value,
            lookup: lookup.is_some(),
            project: Some(project),
            stored: Cell::new(true),
        })
    }
}
