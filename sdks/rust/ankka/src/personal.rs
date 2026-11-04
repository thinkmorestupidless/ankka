//! Personal fields (feature 042): a value about one data subject, never held in the clear.
//!
//! ```ignore
//! #[derive(Serialize, Deserialize)]
//! struct PlayerRegistered { player_id: String, email: Personal<String> }
//!
//! let subject = DataSubject::new("player/8c1f")?;
//! PlayerRegistered { player_id: "8c1f".into(), email: Personal::present(subject, "ada@example.com".into()) }
//! ```
//!
//! A module never seals or opens a field itself and never sees a key. While one of the crate's
//! codecs encodes or decodes a record — an event, a state, a row, a command and its reply — the
//! record's manifest is in scope, and a `Personal` field crosses the `keyring_seal` and
//! `keyring_open` imports: the runtime seals the field's own JSON under the data subject's key and
//! that manifest, and opens it again. Read back after the subject is erased, the field is
//! [`Personal::Erased`]; decoding never fails for an erased subject.
//!
//! Outside such a scope — an endpoint's request body or reply, a call to another service — the
//! field is its edge form, `{"subject": s, "value": v}`, or `{"subject": s}` once erased.
//!
//! A field's textual form names the subject and never the value: `Personal(player/8c1f)`.

use std::cell::{Cell, RefCell};
use std::fmt;

use prost::Message;
use serde::de::{DeserializeOwned, Error as _};
use serde::ser::{Error as _, SerializeStruct};
use serde::{Deserialize, Deserializer, Serialize, Serializer};
use serde_json::Value;

use crate::abi::imports::{call_keyring_open, call_keyring_seal};
use crate::effects::{CommandError, ErrorCode};
use crate::proto;

/// The rule a data subject keeps, in the words every SDK refuses with.
pub const SUBJECT_RULE: &str =
    "a data subject is 1 to 253 characters from letters, digits, '.', '_', '-', '/' and ':'";

/// The key of the `details` a personal field's refusal carries, and its three values.
pub const ERROR_KEY: &str = "personal";
/// No keyring answered.
pub const UNAVAILABLE: &str = "unavailable";
/// A write for an erased data subject.
pub const ERASED: &str = "erased";
/// An envelope whose authentication failed.
pub const CORRUPT: &str = "corrupt";

/// The envelope's version: a reader refuses one it does not know.
pub const ENVELOPE_VERSION: u64 = 1;

/// Who a personal field is about: 1 to 253 characters from letters, digits, `.`, `_`, `-`, `/` and
/// `:` ([`SUBJECT_RULE`]).
#[derive(Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct DataSubject(String);

/// A text that is not a data subject, and why.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InvalidSubject(pub String);

impl fmt::Display for InvalidSubject {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for InvalidSubject {}

impl From<InvalidSubject> for CommandError {
    fn from(e: InvalidSubject) -> CommandError {
        CommandError::new(ErrorCode::BadRequest, e.0)
    }
}

fn subject_char(c: char) -> bool {
    c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-' | '/' | ':')
}

impl DataSubject {
    /// `value` as a data subject, or why it cannot be one.
    pub fn new(value: impl Into<String>) -> Result<DataSubject, InvalidSubject> {
        let value = value.into();
        if (1..=253).contains(&value.len()) && value.chars().all(subject_char) {
            Ok(DataSubject(value))
        } else {
            let shown: String = value
                .chars()
                .map(|c| if c.is_control() { '?' } else { c })
                .collect();
            Err(InvalidSubject(format!(
                "'{shown}' is not a data subject: {SUBJECT_RULE}"
            )))
        }
    }

    /// The subject's text.
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for DataSubject {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl fmt::Debug for DataSubject {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "DataSubject({})", self.0)
    }
}

impl TryFrom<&str> for DataSubject {
    type Error = InvalidSubject;
    fn try_from(value: &str) -> Result<DataSubject, InvalidSubject> {
        DataSubject::new(value)
    }
}

impl TryFrom<String> for DataSubject {
    type Error = InvalidSubject;
    fn try_from(value: String) -> Result<DataSubject, InvalidSubject> {
        DataSubject::new(value)
    }
}

impl Serialize for DataSubject {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&self.0)
    }
}

impl<'de> Deserialize<'de> for DataSubject {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<DataSubject, D::Error> {
        let text = String::deserialize(deserializer)?;
        DataSubject::new(text).map_err(D::Error::custom)
    }
}

/// A personal field: present with its value, or erased. Constructed only with a subject;
/// [`Personal::Erased`] is what a field reads back as once its subject is erased.
#[derive(Clone, PartialEq, Eq)]
pub enum Personal<T> {
    /// The value, about `subject`.
    Present {
        /// Who the value is about.
        subject: DataSubject,
        /// The value.
        value: T,
    },
    /// The value is gone: `subject` was erased.
    Erased {
        /// Who the value was about.
        subject: DataSubject,
    },
}

impl<T> Personal<T> {
    /// `value`, about `subject`.
    pub fn present(subject: DataSubject, value: T) -> Personal<T> {
        Personal::Present { subject, value }
    }

    /// Who the field is about.
    pub fn subject(&self) -> &DataSubject {
        match self {
            Personal::Present { subject, .. } | Personal::Erased { subject } => subject,
        }
    }

    /// The value, unless erased.
    pub fn value(&self) -> Option<&T> {
        match self {
            Personal::Present { value, .. } => Some(value),
            Personal::Erased { .. } => None,
        }
    }

    /// Whether the data subject was erased.
    pub fn is_erased(&self) -> bool {
        matches!(self, Personal::Erased { .. })
    }

    /// The value, or `default` once erased.
    pub fn or_else(self, default: T) -> T {
        match self {
            Personal::Present { value, .. } => value,
            Personal::Erased { .. } => default,
        }
    }
}

impl<T> fmt::Debug for Personal<T> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "Personal({})", self.subject())
    }
}

impl<T> fmt::Display for Personal<T> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "Personal({})", self.subject())
    }
}

// ── the scope ────────────────────────────────────────────────────────────────

thread_local! {
    /// The manifest of the record being encoded or decoded on this thread; `None` is the edge.
    static SCOPE: RefCell<Option<String>> = const { RefCell::new(None) };
    /// Set while the encoding's check walks a value: a field is not sealed for a walk.
    static CHECKING: Cell<bool> = const { Cell::new(false) };
    /// Why the last field written or read in a scope was refused, as the caller is to see it.
    static REFUSAL: RefCell<Option<CommandError>> = const { RefCell::new(None) };
}

struct Restore<F: FnMut()>(F);
impl<F: FnMut()> Drop for Restore<F> {
    fn drop(&mut self) {
        (self.0)()
    }
}

/// Runs `f` with `manifest` in scope: every personal field encoded or decoded in it is sealed or
/// opened by the runtime under that manifest. The crate's codecs do this for every record.
pub fn within<T>(manifest: &str, f: impl FnOnce() -> T) -> T {
    let mut before = SCOPE.with(|s| s.replace(Some(manifest.to_string())));
    let _restore = Restore(|| SCOPE.with(|s| *s.borrow_mut() = before.take()));
    REFUSAL.with(|r| r.borrow_mut().take());
    f()
}

/// Runs `f` with no manifest in scope, as at the edge: every personal field is its edge form.
pub fn outside<T>(f: impl FnOnce() -> T) -> T {
    let mut before = SCOPE.with(|s| s.replace(None));
    let _restore = Restore(|| SCOPE.with(|s| *s.borrow_mut() = before.take()));
    f()
}

/// The manifest in scope on this thread, if any.
pub fn current_manifest() -> Option<String> {
    SCOPE.with(|s| s.borrow().clone())
}

/// Runs the encoding's check with no field sealed.
pub(crate) fn checking<T>(f: impl FnOnce() -> T) -> T {
    let before = CHECKING.with(|c| c.replace(true));
    let _restore = Restore(|| CHECKING.with(|c| c.set(before)));
    f()
}

/// Why the last personal field in a scope on this thread was refused, taken: the refusal with its
/// code and `details`, which an encoding error's text alone would lose.
pub fn take_refusal() -> Option<CommandError> {
    REFUSAL.with(|r| r.borrow_mut().take())
}

/// `message` as the caller sees it: the personal refusal behind it when there is one, else `code`.
pub(crate) fn refusal_or(code: ErrorCode, message: String) -> CommandError {
    take_refusal().unwrap_or_else(|| CommandError::new(code, message))
}

fn refused(error: CommandError) -> String {
    let message = error.message.clone();
    REFUSAL.with(|r| *r.borrow_mut() = Some(error));
    message
}

fn personal_error(code: ErrorCode, detail: &str, message: String) -> CommandError {
    let mut error = CommandError::new(code, message);
    error
        .details
        .insert(ERROR_KEY.to_string(), detail.to_string());
    error
}

/// A refusal of the host's as the SDK's table says: CONFLICT is the erased refusal, UNAVAILABLE no
/// keyring, INTERNAL with `personal` = `corrupt` an envelope that failed authentication.
fn from_host(error: &proto::Error, subject: &str) -> CommandError {
    let found = CommandError::from_proto(error);
    match found.code {
        ErrorCode::Conflict => personal_error(
            ErrorCode::Conflict,
            ERASED,
            format!("the data subject '{subject}' is erased; nothing was written"),
        ),
        ErrorCode::Unavailable => {
            let message = if found.message.is_empty() {
                "the keyring is unavailable".to_string()
            } else {
                found.message
            };
            personal_error(ErrorCode::Unavailable, UNAVAILABLE, message)
        }
        _ if found.details.get(ERROR_KEY).map(String::as_str) == Some(CORRUPT) => personal_error(
            found.code,
            CORRUPT,
            format!("the personal envelope of '{subject}' failed authentication"),
        ),
        _ => found,
    }
}

// ── the envelope ─────────────────────────────────────────────────────────────

/// The envelope as the runtime writes it, keys in its order.
#[derive(Serialize, Deserialize)]
struct Envelope {
    #[serde(rename = "$personal")]
    version: u64,
    project: String,
    subject: String,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    c: Option<String>,
}

/// The edge form: `{"subject": s, "value": v}`, or `{"subject": s}` once erased.
fn serialize_edge<T: Serialize, S: Serializer>(
    field: &Personal<T>,
    serializer: S,
) -> Result<S::Ok, S::Error> {
    match field {
        Personal::Present { subject, value } => {
            let mut s = serializer.serialize_struct("Personal", 2)?;
            s.serialize_field("subject", subject)?;
            s.serialize_field("value", value)?;
            s.end()
        }
        Personal::Erased { subject } => {
            let mut s = serializer.serialize_struct("Personal", 1)?;
            s.serialize_field("subject", subject)?;
            s.end()
        }
    }
}

/// The request `keyring_seal` is asked for a present field: its subject, the manifest in scope and
/// the field's own JSON.
pub fn seal_request<T: Serialize>(
    subject: &DataSubject,
    manifest: &str,
    value: &T,
) -> Result<proto::KeyringSealRequest, String> {
    let json = crate::codec::json::to_vec(value).map_err(|e| e.0)?;
    Ok(proto::KeyringSealRequest {
        subject: subject.as_str().to_string(),
        manifest: manifest.to_string(),
        value: json,
        erased: false,
    })
}

fn seal<T: Serialize>(
    subject: &DataSubject,
    manifest: &str,
    value: &T,
) -> Result<Envelope, String> {
    let request = seal_request(subject, manifest, value)?;
    sealed(subject, call_keyring_seal(&request.encode_to_vec()))
}

/// The envelope `keyring_seal` answered, or its refusal.
fn sealed(subject: &DataSubject, answered: Vec<u8>) -> Result<Envelope, String> {
    let reply = proto::KeyringSealReply::decode(answered.as_slice())
        .map_err(|e| format!("the runtime's answer to keyring_seal does not decode: {e}"))?;
    match reply.reply {
        Some(proto::keyring_seal_reply::Reply::Envelope(bytes)) => {
            let envelope: Envelope = serde_json::from_slice(&bytes)
                .map_err(|e| format!("the runtime sealed a field this crate cannot read: {e}"))?;
            Ok(envelope)
        }
        Some(proto::keyring_seal_reply::Reply::Error(error)) => {
            Err(refused(from_host(&error, subject.as_str())))
        }
        None => Err(refused(personal_error(
            ErrorCode::Unavailable,
            UNAVAILABLE,
            "the keyring is unavailable: keyring_seal answered nothing".into(),
        ))),
    }
}

/// An erased field, written by the runtime as it writes every envelope: under the project it holds,
/// which a module is never told.
fn erased_envelope(subject: &DataSubject, manifest: &str) -> Result<Envelope, String> {
    let request = proto::KeyringSealRequest {
        subject: subject.as_str().to_string(),
        manifest: manifest.to_string(),
        value: Vec::new(),
        erased: true,
    };
    sealed(subject, call_keyring_seal(&request.encode_to_vec()))
}

impl<T: Serialize> Serialize for Personal<T> {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        if CHECKING.with(Cell::get) {
            return serialize_edge(self, serializer);
        }
        let Some(manifest) = current_manifest() else {
            return serialize_edge(self, serializer);
        };
        let envelope = match self {
            Personal::Present { subject, value } => seal(subject, &manifest, value),
            Personal::Erased { subject } => erased_envelope(subject, &manifest),
        }
        .map_err(S::Error::custom)?;
        envelope.serialize(serializer)
    }
}

/// The request `keyring_open` is asked for an envelope, read under the manifest in scope.
pub fn open_request(envelope: &Value, manifest: &str) -> proto::KeyringOpenRequest {
    proto::KeyringOpenRequest {
        envelope: serde_json::to_vec(envelope).unwrap_or_default(),
        manifest: manifest.to_string(),
    }
}

fn open<T: DeserializeOwned>(
    document: &Value,
    envelope: Envelope,
    manifest: &str,
) -> Result<Personal<T>, String> {
    if envelope.version != ENVELOPE_VERSION {
        return Err(format!(
            "a personal envelope of version {} is not one this crate reads (it reads {ENVELOPE_VERSION})",
            envelope.version
        ));
    }
    let subject = DataSubject::new(envelope.subject.clone()).map_err(|e| e.0)?;
    if envelope.c.is_none() {
        return Ok(Personal::Erased { subject });
    }
    let request = open_request(document, manifest);
    let answered = call_keyring_open(&request.encode_to_vec());
    let reply = proto::KeyringOpenReply::decode(answered.as_slice())
        .map_err(|e| format!("the runtime's answer to keyring_open does not decode: {e}"))?;
    match reply.reply {
        Some(proto::keyring_open_reply::Reply::Value(bytes)) => {
            let value: T = serde_json::from_slice(&bytes).map_err(|e| {
                format!("the personal field of '{subject}' is not what it is read as: {e}")
            })?;
            Ok(Personal::Present { subject, value })
        }
        Some(proto::keyring_open_reply::Reply::Erased(_)) => Ok(Personal::Erased { subject }),
        Some(proto::keyring_open_reply::Reply::Error(error)) => {
            Err(refused(from_host(&error, subject.as_str())))
        }
        None => Err(refused(personal_error(
            ErrorCode::Unavailable,
            UNAVAILABLE,
            "the keyring is unavailable: keyring_open answered nothing".into(),
        ))),
    }
}

fn read_edge<T: DeserializeOwned>(
    mut object: serde_json::Map<String, Value>,
) -> Result<Personal<T>, String> {
    let subject = match object.remove("subject") {
        Some(Value::String(text)) => DataSubject::new(text).map_err(|e| e.0)?,
        Some(_) => return Err("a personal field's subject is a string".into()),
        None => return Err("a personal field names its data subject".into()),
    };
    match object.remove("value") {
        Some(value) => {
            let value = serde_json::from_value(value).map_err(|e| e.to_string())?;
            Ok(Personal::Present { subject, value })
        }
        None => Ok(Personal::Erased { subject }),
    }
}

impl<'de, T: DeserializeOwned> Deserialize<'de> for Personal<T> {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Personal<T>, D::Error> {
        let document = Value::deserialize(deserializer)?;
        let Value::Object(object) = &document else {
            return Err(D::Error::custom(
                "expected a personal field: {\"subject\", \"value\"}",
            ));
        };
        if object.contains_key("$personal") {
            let Some(manifest) = current_manifest() else {
                return Err(D::Error::custom(
                    "a personal envelope is read only inside a record, under its manifest",
                ));
            };
            let envelope: Envelope =
                serde_json::from_value(document.clone()).map_err(D::Error::custom)?;
            return open(&document, envelope, &manifest).map_err(D::Error::custom);
        }
        let Value::Object(object) = document else {
            unreachable!("matched as an object above")
        };
        read_edge(object).map_err(D::Error::custom)
    }
}
