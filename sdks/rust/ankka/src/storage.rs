//! The service's buckets: objects kept under names, whole, beside the service's journal and never
//! in it. An entity keeps an object's *name*; the bucket keeps the bytes.
//!
//! It is offered where the secret store is — endpoints, workflow steps, consumers, timed actions
//! and agents — and not to an entity, a view or a workflow's command handler:
//! [`Context::storage`](crate::Context::storage) answers `None` there.
//!
//! ```ignore
//! let storage = ctx.storage().expect("an endpoint has the service's buckets");
//! storage.bucket("photos").put("listings/42/front.jpg", &bytes, "image/jpeg")?;
//! let front = storage.bucket("photos").url("listings/42/front.jpg")?;
//! ```
//!
//! Each call is one `storage` import, answered by the runtime's own client, and blocks the handler
//! until the runtime answers. A module has no socket, so every object goes through the runtime
//! whole: a body, in or out, is at most [`MAX_BODY_BYTES`], and a larger one is
//! [`StorageError::TooLarge`]. A module that never reaches a bucket does not import the function,
//! and so runs on a runtime that predates it (protocol 1.20).

use std::fmt;

use prost::Message;

use crate::abi::imports::call_storage;
use crate::codec::time::{Duration, Instant};
use crate::effects::{CommandError, ErrorCode, HttpProblem};
use crate::proto;
use crate::proto::storage_reply::Reply;

/// The largest body a `put` sends or a `get` answers through the `storage` import, in bytes.
pub const MAX_BODY_BYTES: usize = 4_000_000;
/// The longest object name, in bytes as UTF-8.
pub const MAX_NAME_BYTES: usize = 1024;
/// The longest bucket name, in characters.
pub const MAX_BUCKET_NAME: usize = 32;
/// The longest a signed URL may be good for: seven days.
pub const MAX_SIGNED_URL_SECONDS: i64 = 604_800;
/// What a signed URL is good for when nothing else is said: fifteen minutes.
pub const DEFAULT_SIGNED_URL_SECONDS: i64 = 900;

/// The rule a bucket's name keeps, in the descriptor's words.
pub const BUCKET_NAME_RULE: &str = "lower-case letters, digits and '-', 1 to 32 characters";

/// What the runtime says, and this crate says before it, when a body is above the bound.
const TOO_LARGE: &str = "through the storage import is at most";

// ── the rules ────────────────────────────────────────────────────────────────

/// What is wrong with an object's `name`, if anything: the runtime's own rule, in its words.
pub fn name_problem(name: &str) -> Option<String> {
    problem("an object's name", name, false)
}

/// What is wrong with a listing's `prefix`, if anything. Empty is everything; a prefix may end in
/// `/`.
pub fn prefix_problem(prefix: &str) -> Option<String> {
    if prefix.is_empty() {
        None
    } else {
        problem("a prefix", prefix, true)
    }
}

/// What is wrong with a bucket's name, if anything, in the descriptor's words.
pub fn bucket_name_problem(name: &str) -> Option<String> {
    let valid = (1..=MAX_BUCKET_NAME).contains(&name.chars().count())
        && name
            .chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-');
    (!valid).then(|| format!("'{}' is not a bucket name: {BUCKET_NAME_RULE}", shown(name)))
}

/// What is wrong with a signed URL's span of `seconds`, if anything.
pub fn span_problem(seconds: i64) -> Option<String> {
    if seconds <= 0 {
        Some(format!(
            "a signed URL's span is positive; this one is {seconds} seconds"
        ))
    } else if seconds > MAX_SIGNED_URL_SECONDS {
        Some(format!(
            "a signed URL is made for at most 7 days ({MAX_SIGNED_URL_SECONDS} seconds); this one asks for {seconds}"
        ))
    } else {
        None
    }
}

fn is_control(c: char) -> bool {
    c < ' ' || c == '\u{7f}'
}

fn problem(subject: &str, text: &str, trailing_slash: bool) -> Option<String> {
    let bytes = text.len();
    let body = if trailing_slash {
        text.strip_suffix('/').unwrap_or(text)
    } else {
        text
    };
    let mut segments = body.split('/');
    let found = if text.is_empty() {
        format!("{subject} must not be empty")
    } else if bytes > MAX_NAME_BYTES {
        format!("{subject} is at most {MAX_NAME_BYTES} bytes as UTF-8; this one is {bytes}")
    } else if text.starts_with('/') {
        format!("{subject} must not begin with '/'")
    } else if text.chars().any(is_control) {
        format!("{subject} must not hold a control character")
    } else if segments.clone().any(str::is_empty) {
        format!("{subject} must not have an empty segment")
    } else if segments.any(|s| s == "." || s == "..") {
        format!("{subject} must not have a segment '.' or '..'")
    } else {
        return None;
    };
    Some(format!("{found} (\"{}\")", shown(text)))
}

fn shown(text: &str) -> String {
    let visible: Vec<char> = text
        .chars()
        .map(|c| if is_control(c) { '?' } else { c })
        .collect();
    if visible.len() > 60 {
        visible[..60].iter().chain(std::iter::once(&'…')).collect()
    } else {
        visible.into_iter().collect()
    }
}

/// `text` percent-encoded as an S3 path is: every byte but a letter, a digit, `-`, `_`, `.`, `~`
/// and, unless `slash`, `/`. Used by the testkits, which a module does not build.
#[cfg_attr(target_arch = "wasm32", allow(dead_code))]
pub(crate) fn percent_encode(text: &str, slash: bool) -> String {
    let mut out = String::with_capacity(text.len());
    for b in text.bytes() {
        let c = char::from(b);
        if c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.' | '~') || (c == '/' && !slash) {
            out.push(c);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// The refusals, in the runtime's words, said once so the unit test's store says what the runtime
/// does. Only `too_large` is the client's; the rest are the unit test store's.
#[cfg_attr(target_arch = "wasm32", allow(dead_code))]
pub(crate) mod refusals {
    use super::{BucketExposure, MAX_BODY_BYTES, TOO_LARGE};

    pub(crate) fn too_large(what: &str, length: u64) -> String {
        format!("{what} {TOO_LARGE} {MAX_BODY_BYTES} bytes; this one is {length}")
    }

    pub(crate) fn unknown<'a>(name: &str, known: impl Iterator<Item = &'a String>) -> String {
        let mut names: Vec<&String> = known.collect();
        names.sort();
        let named = if names.is_empty() {
            "names none".to_string()
        } else {
            let quoted: Vec<String> = names.iter().map(|n| format!("'{n}'")).collect();
            format!("names {}", quoted.join(", "))
        };
        format!("this service has no bucket named '{name}'; its descriptor {named}")
    }

    pub(crate) fn not_exposed() -> String {
        "the service's bucket is not reachable from the internet, so it signs no URL: the \
         descriptor sets exposeObjectStorage, or names a signed bucket"
            .to_string()
    }

    pub(crate) fn not_signed(bucket: &str, exposure: BucketExposure) -> String {
        format!(
            "bucket '{bucket}' is {}; a signed URL is made for a signed bucket",
            exposure.word()
        )
    }

    pub(crate) fn not_public(bucket: Option<&str>, exposure: BucketExposure) -> String {
        let label = bucket.map_or("the service's unnamed bucket".to_string(), |b| {
            format!("bucket '{b}'")
        });
        format!(
            "{label} is {}; an address with no signature is a public bucket's",
            exposure.word()
        )
    }
}

// ── what the client answers ──────────────────────────────────────────────────

/// How a bucket is reached from outside the installation, as the descriptor says it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum BucketExposure {
    /// Inside the installation only.
    Private,
    /// From the internet, through URLs the service signs.
    Signed,
    /// Every object readable from the internet without a signature.
    Public,
}

impl BucketExposure {
    /// The word the descriptor uses.
    pub fn word(&self) -> &'static str {
        match self {
            BucketExposure::Private => "private",
            BucketExposure::Signed => "signed",
            BucketExposure::Public => "public",
        }
    }
}

/// What a signed URL lets its holder do with one object.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum SignedAccess {
    /// Read it.
    Read,
    /// Keep a body under its name.
    Keep,
}

/// An object read whole from a bucket.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoredObject {
    /// The content type it was kept with.
    pub content_type: String,
    /// Its bytes.
    pub body: Vec<u8>,
    /// Its length in bytes.
    pub length: u64,
    /// When it was kept.
    pub last_kept: Instant,
}

/// What a bucket keeps under a name, without the bytes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ObjectInfo {
    /// The object's name.
    pub name: String,
    /// The content type it was kept with.
    pub content_type: String,
    /// Its length in bytes.
    pub length: u64,
    /// When it was kept.
    pub last_kept: Instant,
}

/// One object in a listing.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ObjectEntry {
    /// The object's name.
    pub name: String,
    /// Its length in bytes.
    pub length: u64,
    /// When it was kept.
    pub last_kept: Instant,
}

/// Why a storage call was refused or failed, with the runtime's words.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StorageError {
    /// A body above [`MAX_BODY_BYTES`]: a `put` refused before the call, or a `get` of a larger
    /// object, refused by the runtime before any of it was read. The message names the bound.
    TooLarge(String),
    /// A name, a prefix or a span that breaks its rule, or a signed URL or an address the bucket's
    /// exposure does not allow.
    BadRequest(String),
    /// The store cannot be reached, the service has no such bucket, or no bucket at all.
    Unavailable(String),
    /// An answer that cannot be read, or a fault in the runtime.
    Internal(String),
}

impl StorageError {
    /// What went wrong.
    pub fn message(&self) -> &str {
        match self {
            StorageError::TooLarge(m)
            | StorageError::BadRequest(m)
            | StorageError::Unavailable(m)
            | StorageError::Internal(m) => m,
        }
    }

    /// The protocol's code for it: a body too large is a bad request.
    pub fn code(&self) -> ErrorCode {
        match self {
            StorageError::TooLarge(_) | StorageError::BadRequest(_) => ErrorCode::BadRequest,
            StorageError::Unavailable(_) => ErrorCode::Unavailable,
            StorageError::Internal(_) => ErrorCode::Internal,
        }
    }

    fn from_proto(error: proto::Error) -> StorageError {
        match ErrorCode::from_proto(error.code) {
            ErrorCode::BadRequest if error.message.contains(TOO_LARGE) => {
                StorageError::TooLarge(error.message)
            }
            ErrorCode::BadRequest => StorageError::BadRequest(error.message),
            ErrorCode::Unavailable => StorageError::Unavailable(error.message),
            _ => StorageError::Internal(error.message),
        }
    }
}

impl fmt::Display for StorageError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:?}: {}", self.code(), self.message())
    }
}

impl std::error::Error for StorageError {}

impl From<StorageError> for CommandError {
    fn from(error: StorageError) -> CommandError {
        CommandError::new(error.code(), error.message())
    }
}

impl From<StorageError> for HttpProblem {
    fn from(error: StorageError) -> HttpProblem {
        CommandError::from(error).into()
    }
}

// ── the client ───────────────────────────────────────────────────────────────

/// The service's buckets, from a handler's [`Context`](crate::Context).
#[derive(Debug, Clone, Default)]
pub struct Storage {
    _private: (),
}

impl Storage {
    pub(crate) fn new() -> Storage {
        Storage { _private: () }
    }

    /// The service's unnamed bucket, the one `provisionObjectStorage` asks for. Every call is
    /// refused `Unavailable` when the service has none.
    pub fn default(&self) -> Bucket {
        Bucket { name: None }
    }

    /// A bucket the descriptor's `buckets` list names. Every call is refused `Unavailable`, naming
    /// the ones it does name, otherwise.
    pub fn bucket(&self, name: &str) -> Bucket {
        Bucket {
            name: Some(name.to_string()),
        }
    }
}

/// One bucket. Its objects are named with `/` between segments, so a listing by prefix reads like
/// a directory; it is not one. There is no directory to make or remove, and no rename, copy,
/// append or partial write: a put replaces the whole object or leaves the old one.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Bucket {
    name: Option<String>,
}

fn ask(request: proto::StorageRequest) -> Result<Reply, StorageError> {
    let answered = call_storage(&request.encode_to_vec());
    let reply = proto::StorageReply::decode(answered.as_slice()).map_err(|e| {
        StorageError::Internal(format!(
            "the runtime's answer to a storage call does not decode: {e}"
        ))
    })?;
    match reply.reply {
        Some(Reply::Error(error)) => Err(StorageError::from_proto(error)),
        Some(other) => Ok(other),
        None => Err(StorageError::Internal(
            "the runtime answered a storage call with nothing".to_string(),
        )),
    }
}

fn unexpected<T>(op: &str, reply: &Reply) -> Result<T, StorageError> {
    let case = match reply {
        Reply::Object(_) => "an object",
        Reply::Info(_) => "an object's details",
        Reply::Entries(_) => "a listing",
        Reply::Url(_) => "a URL",
        Reply::Done(_) => "nothing",
        Reply::Error(_) => "a refusal",
    };
    Err(StorageError::Internal(format!(
        "the runtime answered a {op} with {case}"
    )))
}

fn instant(text: &str) -> Result<Instant, StorageError> {
    Instant::parse(text).map_err(|e| {
        StorageError::Internal(format!(
            "the runtime answered a time that does not read: '{text}': {e}"
        ))
    })
}

impl Bucket {
    /// The descriptor's name for it; `None` for the unnamed bucket.
    pub fn name(&self) -> Option<&str> {
        self.name.as_deref()
    }

    fn request(&self, op: proto::storage_request::Op, name: &str) -> proto::StorageRequest {
        proto::StorageRequest {
            bucket: self.name.clone(),
            op: op as i32,
            name: name.to_string(),
            ..Default::default()
        }
    }

    /// Keeps `body` under `name` with `content_type`, replacing what was there. A body above
    /// [`MAX_BODY_BYTES`] is [`StorageError::TooLarge`], and nothing is sent.
    pub fn put(&self, name: &str, body: &[u8], content_type: &str) -> Result<(), StorageError> {
        if body.len() > MAX_BODY_BYTES {
            return Err(StorageError::TooLarge(refusals::too_large(
                "an object kept",
                body.len() as u64,
            )));
        }
        let mut request = self.request(proto::storage_request::Op::Put, name);
        request.content_type = content_type.to_string();
        request.body = Some(body.to_vec());
        match ask(request)? {
            Reply::Done(_) => Ok(()),
            other => unexpected("put", &other),
        }
    }

    /// The object kept under `name`, read whole, or `None` when there is none. An object above
    /// [`MAX_BODY_BYTES`] is [`StorageError::TooLarge`]: the runtime refuses it before reading any
    /// of it.
    pub fn get(&self, name: &str) -> Result<Option<StoredObject>, StorageError> {
        match ask(self.request(proto::storage_request::Op::Get, name))? {
            Reply::Done(_) => Ok(None),
            Reply::Object(o) if o.body.len() > MAX_BODY_BYTES => Err(StorageError::TooLarge(
                refusals::too_large("an object read", o.body.len() as u64),
            )),
            Reply::Object(o) => Ok(Some(StoredObject {
                last_kept: instant(&o.last_kept)?,
                content_type: o.content_type,
                length: o.length,
                body: o.body,
            })),
            other => unexpected("get", &other),
        }
    }

    /// Removes what is kept under `name`. Removing nothing is not an error.
    pub fn delete(&self, name: &str) -> Result<(), StorageError> {
        match ask(self.request(proto::storage_request::Op::Delete, name))? {
            Reply::Done(_) => Ok(()),
            other => unexpected("delete", &other),
        }
    }

    /// Every object whose name begins with `prefix`, in name order. The runtime answers a page at
    /// a time; this asks for every page and joins them.
    pub fn list(&self, prefix: &str) -> Result<Vec<ObjectEntry>, StorageError> {
        let mut found = Vec::new();
        let mut continuation: Option<String> = None;
        loop {
            let mut request = self.request(proto::storage_request::Op::List, prefix);
            request.continuation = continuation.take();
            let page = match ask(request)? {
                Reply::Entries(page) => page,
                other => return unexpected("listing", &other),
            };
            for e in page.entries {
                found.push(ObjectEntry {
                    last_kept: instant(&e.last_kept)?,
                    name: e.name,
                    length: e.length,
                });
            }
            match page.next {
                Some(next) if !next.is_empty() => continuation = Some(next),
                _ => return Ok(found),
            }
        }
    }

    /// What is kept under `name`, without its bytes, or `None` when there is nothing.
    pub fn stat(&self, name: &str) -> Result<Option<ObjectInfo>, StorageError> {
        match ask(self.request(proto::storage_request::Op::Stat, name))? {
            Reply::Done(_) => Ok(None),
            Reply::Info(i) => Ok(Some(ObjectInfo {
                last_kept: instant(&i.last_kept)?,
                name: i.name,
                content_type: i.content_type,
                length: i.length,
            })),
            other => unexpected("stat", &other),
        }
    }

    /// A URL a browser may use, until `span` has passed, to read ([`SignedAccess::Read`]) or keep
    /// ([`SignedAccess::Keep`]) the object under `name`, made by computation alone: a signed
    /// bucket's, or the unnamed bucket's when the service sets `exposeObjectStorage`; at most seven
    /// days, in whole seconds.
    pub fn signed_url(
        &self,
        name: &str,
        access: SignedAccess,
        span: Duration,
    ) -> Result<String, StorageError> {
        let seconds = span.to_millis() / 1000;
        if let Some(problem) = span_problem(seconds) {
            return Err(StorageError::BadRequest(problem));
        }
        let mut request = self.request(proto::storage_request::Op::SignedUrl, name);
        request.access = match access {
            SignedAccess::Read => proto::storage_request::Access::Read,
            SignedAccess::Keep => proto::storage_request::Access::Keep,
        } as i32;
        // At most seven days, so it fits.
        request.expires_seconds = seconds as u32;
        match ask(request)? {
            Reply::Url(url) => Ok(url),
            other => unexpected("signed URL", &other),
        }
    }

    /// The address a browser reads the object under `name` at, with no signature: a public
    /// bucket's only. Computed, never fetched, so it is answered for a name nothing is kept under
    /// yet.
    pub fn url(&self, name: &str) -> Result<String, StorageError> {
        match ask(self.request(proto::storage_request::Op::Url, name))? {
            Reply::Url(url) => Ok(url),
            other => unexpected("public address", &other),
        }
    }
}
