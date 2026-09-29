//! JSON payloads (`application/json`): serde's rendering, held to the encoding's rules.
//!
//! serde already writes what the encoding asks for — every field, `None` as `null`, an `i64` beyond
//! 2⁵³ exactly, an enum under `#[serde(tag = "type")]` as `{"type":"<Variant>",…}` — except in
//! two places this module fixes: a double is laid out as jsoniter writes it (`1.0E10`, not
//! `10000000000.0`), and what has no JSON form (NaN, an untagged enum) is refused rather than
//! written as something else. Reading is serde's: unknown fields are ignored, and an absent
//! `Option` reads as `None`.

use std::borrow::Cow;
use std::io;
use std::marker::PhantomData;

use serde::Serialize;
use serde::de::DeserializeOwned;

use super::{Codec, ContentType, EncodingError, check, float};

/// Writes floating-point numbers as the platform's codecs do; everything else as serde_json does.
struct JsoniterFormatter;

impl serde_json::ser::Formatter for JsoniterFormatter {
    fn write_f64<W: io::Write + ?Sized>(&mut self, writer: &mut W, value: f64) -> io::Result<()> {
        let text = float::java_double(value).map_err(|e| io::Error::other(e.0))?;
        writer.write_all(text.as_bytes())
    }

    fn write_f32<W: io::Write + ?Sized>(&mut self, writer: &mut W, value: f32) -> io::Result<()> {
        let text = float::java_float(value).map_err(|e| io::Error::other(e.0))?;
        writer.write_all(text.as_bytes())
    }
}

/// `value` as the encoding's compact JSON.
pub fn to_vec<T: Serialize + ?Sized>(value: &T) -> Result<Vec<u8>, EncodingError> {
    check::check(value).map_err(|r| EncodingError(r.0))?;
    let mut out = Vec::new();
    let mut serializer = serde_json::Serializer::with_formatter(&mut out, JsoniterFormatter);
    value.serialize(&mut serializer)?;
    Ok(out)
}

/// `value` as the encoding's compact JSON text.
pub fn to_string<T: Serialize + ?Sized>(value: &T) -> Result<String, EncodingError> {
    String::from_utf8(to_vec(value)?).map_err(|e| EncodingError(e.to_string()))
}

/// The value this JSON document is.
pub fn from_slice<T: DeserializeOwned>(data: &[u8]) -> Result<T, EncodingError> {
    Ok(serde_json::from_slice(data)?)
}

/// The JSON codec for a serde type, under a manifest: the type's simple name unless named.
pub struct Json<T> {
    manifest: Cow<'static, str>,
    marker: PhantomData<fn() -> T>,
}

impl<T> Json<T> {
    /// The codec under the type's simple name (`Cart` for `my_service::domain::Cart`).
    pub fn new() -> Json<T> {
        Json {
            manifest: Cow::Owned(super::manifest::simple_name(std::any::type_name::<T>())),
            marker: PhantomData,
        }
    }

    /// The codec under a manifest of the service's choosing — the one another language's service
    /// uses for the same type, if they share a journal.
    pub fn named(manifest: impl Into<Cow<'static, str>>) -> Json<T> {
        Json {
            manifest: manifest.into(),
            marker: PhantomData,
        }
    }
}

impl<T> Default for Json<T> {
    fn default() -> Json<T> {
        Json::new()
    }
}

impl<T: Serialize + DeserializeOwned> Codec<T> for Json<T> {
    fn manifest(&self) -> &str {
        &self.manifest
    }

    fn content_type(&self) -> ContentType {
        ContentType::Json
    }

    fn encode(&self, value: &T) -> Result<Vec<u8>, EncodingError> {
        to_vec(value)
    }

    fn decode(&self, data: &[u8]) -> Result<T, EncodingError> {
        from_slice(data)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde::Deserialize;

    #[derive(Serialize, Deserialize, Debug, PartialEq)]
    #[serde(tag = "type")]
    enum Tagged {
        Added { name: String },
        Cleared,
    }

    #[derive(Serialize)]
    enum Untagged {
        Added { name: String },
    }

    #[derive(Serialize)]
    struct Numbers {
        ten_billion: f64,
        nothing: Option<f64>,
    }

    #[test]
    fn a_tagged_enum_is_written_with_its_discriminator() {
        let added = Tagged::Added { name: "x".into() };
        assert_eq!(to_string(&added).unwrap(), r#"{"type":"Added","name":"x"}"#);
        assert_eq!(
            to_string(&Tagged::Cleared).unwrap(),
            r#"{"type":"Cleared"}"#
        );
        assert_eq!(
            from_slice::<Tagged>(br#"{"name":"x","type":"Added"}"#).unwrap(),
            added
        );
    }

    #[test]
    fn an_untagged_enum_is_refused_naming_the_attribute() {
        let error = to_string(&Untagged::Added { name: "x".into() }).unwrap_err();
        assert!(error.0.contains("#[serde(tag = \"type\")]"), "{error}");
        assert!(error.0.contains("Untagged::Added"), "{error}");
    }

    #[test]
    fn doubles_are_written_as_jsoniter_writes_them_and_nan_is_refused() {
        let n = Numbers {
            ten_billion: 1e10,
            nothing: None,
        };
        assert_eq!(
            to_string(&n).unwrap(),
            r#"{"ten_billion":1.0E10,"nothing":null}"#
        );
        assert!(
            to_string(&Numbers {
                ten_billion: f64::NAN,
                nothing: None
            })
            .is_err()
        );
    }

    #[test]
    fn the_manifest_defaults_to_the_simple_name() {
        assert_eq!(Json::<Tagged>::new().manifest(), "Tagged");
        assert_eq!(
            Json::<Tagged>::named("tagged-event").manifest(),
            "tagged-event"
        );
    }
}
