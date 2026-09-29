//! Codecs: what the bytes in a payload mean.
//!
//! Built to `protocol/ENCODING.md` (copied into this crate under `protocol/`), which describes what
//! the Scala SDK's default codecs produce, so that a journal written by a service in one language
//! is read by the same service in another. `tests/encoding_fixtures.rs` proves every fixture both
//! ways.
//!
//! The default for a domain type is JSON through serde ([`Json`]): a struct is an object with every
//! field written, an enum declared `#[serde(tag = "type")]` is an object with `"type":"<Variant>"`,
//! `None` is `null`. A payload that is a primitive of its own uses the text or binary encodings
//! ([`Text`], [`binary`]). [`Auto`] chooses among them by type, and is what a handler's input and
//! reply use when nothing else is said.

pub mod base64;
pub mod binary;
mod check;
pub mod float;
pub mod json;
pub mod manifest;
pub mod text;
pub mod time;

use std::fmt;

use serde::{Deserialize, Deserializer, Serialize, Serializer};

use crate::proto::Payload;

pub use binary::{BytesCodec, DoneCodec, OptionCodec, UnitCodec};
pub use json::Json;
pub use manifest::{Auto, Form, Primitive, codec_for_manifest, manifest_for};
pub use text::{Text, TextValue};

/// The three content types a payload may have.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ContentType {
    /// `application/json`: records and sum types.
    Json,
    /// `text/plain`: a string, a number, a boolean or a duration in milliseconds.
    Text,
    /// `application/octet-stream`: `done`, `unit`, raw bytes and a top-level option.
    Binary,
}

impl ContentType {
    /// The content type as a payload carries it.
    pub fn as_str(&self) -> &'static str {
        match self {
            ContentType::Json => "application/json",
            ContentType::Text => "text/plain",
            ContentType::Binary => "application/octet-stream",
        }
    }
}

impl fmt::Display for ContentType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

/// A value or a document the encoding does not cover.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EncodingError(pub String);

impl fmt::Display for EncodingError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for EncodingError {}

impl From<serde_json::Error> for EncodingError {
    fn from(e: serde_json::Error) -> EncodingError {
        EncodingError(e.to_string())
    }
}

/// Encodes and decodes one type under one manifest.
pub trait Codec<T> {
    /// The manifest the journal stores beside the bytes.
    fn manifest(&self) -> &str;

    /// The payload's content type.
    fn content_type(&self) -> ContentType;

    /// The value's bytes.
    fn encode(&self, value: &T) -> Result<Vec<u8>, EncodingError>;

    /// The value these bytes are.
    fn decode(&self, data: &[u8]) -> Result<T, EncodingError>;

    /// The value as a whole payload.
    fn payload(&self, value: &T) -> Result<Payload, EncodingError> {
        Ok(Payload {
            content_type: self.content_type().as_str().to_string(),
            manifest: self.manifest().to_string(),
            data: self.encode(value)?,
        })
    }
}

/// A value as a payload under its default codec ([`Auto`]).
pub fn encode_payload<T: Serialize + 'static>(value: &T) -> Result<Payload, EncodingError> {
    Auto::<T>::new().to_payload(value)
}

/// A payload read as `T` under its default codec ([`Auto`]).
pub fn decode_payload<T: serde::de::DeserializeOwned + 'static>(
    payload: &Payload,
) -> Result<T, EncodingError> {
    Auto::<T>::new().decode_value(&payload.data)
}

/// The reply that says only that a command was handled. Zero bytes on the wire, manifest `done`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default, Serialize, Deserialize)]
pub struct Done;

/// Bytes that are a payload of their own (manifest `bytes`), or base64 text inside JSON.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Default)]
pub struct Bytes(pub Vec<u8>);

impl Serialize for Bytes {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&base64::encode(&self.0))
    }
}

impl<'de> Deserialize<'de> for Bytes {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Bytes, D::Error> {
        let text = String::deserialize(deserializer)?;
        base64::decode(&text)
            .map(Bytes)
            .map_err(serde::de::Error::custom)
    }
}
