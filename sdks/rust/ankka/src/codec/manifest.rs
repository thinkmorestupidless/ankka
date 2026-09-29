//! Which encoding a type takes, and under which manifest, when nothing else is said.
//!
//! The table is `ENCODING.md`'s: `String`, the integers, the floats, `bool` and [`Duration`] are
//! text; [`Done`], `()` and [`Bytes`] are binary; `Option<T>` on its own is binary around `T`'s
//! form; anything else is JSON under its simple name. The choice is made from the type's name,
//! compared against the library's own types rather than spelled out, so a service type that
//! happens to share a name (`my::Duration`) is JSON as it should be.

use std::any::type_name;
use std::borrow::Cow;
use std::marker::PhantomData;

use serde::Serialize;
use serde::de::DeserializeOwned;
use serde_json::Value;

use super::text::TextKind;
use super::time::Duration;
use super::{Bytes, Codec, ContentType, Done, EncodingError, base64, json};

/// A payload form that is not JSON.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Primitive {
    /// A text form.
    Text(TextKind),
    /// `done`: zero bytes.
    Done,
    /// `unit`: zero bytes.
    Unit,
    /// `bytes`: the bytes themselves.
    Bytes,
    /// `option[<inner>]`: zero bytes, or `0x01` then the inner form's bytes.
    Option(Box<Form>),
}

/// How a type's values cross as payloads.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Form {
    /// A primitive form, with the manifest it fixes.
    Primitive(Primitive),
    /// JSON, under this manifest.
    Json(String),
}

impl Form {
    /// The manifest a payload of this form carries.
    pub fn manifest(&self) -> String {
        match self {
            Form::Json(manifest) => manifest.clone(),
            Form::Primitive(Primitive::Text(kind)) => kind.manifest().to_string(),
            Form::Primitive(Primitive::Done) => "done".into(),
            Form::Primitive(Primitive::Unit) => "unit".into(),
            Form::Primitive(Primitive::Bytes) => "bytes".into(),
            Form::Primitive(Primitive::Option(inner)) => format!("option[{}]", inner.manifest()),
        }
    }

    /// The content type a payload of this form carries.
    pub fn content_type(&self) -> ContentType {
        match self {
            Form::Json(_) => ContentType::Json,
            Form::Primitive(Primitive::Text(_)) => ContentType::Text,
            Form::Primitive(_) => ContentType::Binary,
        }
    }

    /// The bytes of a value in its serde form. JSON is written from the value tree, so a JSON form
    /// used this way loses nothing but key order — which is why [`Auto`] writes a JSON type from
    /// the type itself, and comes here only for primitives.
    pub fn encode_value(&self, value: &Value) -> Result<Vec<u8>, EncodingError> {
        match self {
            Form::Json(_) => json::to_vec(value),
            Form::Primitive(Primitive::Text(kind)) => kind.render(value).map(String::into_bytes),
            Form::Primitive(Primitive::Done | Primitive::Unit) => Ok(Vec::new()),
            Form::Primitive(Primitive::Bytes) => {
                let text = value
                    .as_str()
                    .ok_or_else(|| EncodingError(format!("{value} is not bytes")))?;
                base64::decode(text).map_err(EncodingError)
            }
            Form::Primitive(Primitive::Option(inner)) => {
                if value.is_null() {
                    return Ok(Vec::new());
                }
                let mut out = vec![1u8];
                out.extend(inner.encode_value(value)?);
                Ok(out)
            }
        }
    }

    /// A value's serde form from its bytes: what `encode_value` was given.
    pub fn decode_value(&self, data: &[u8]) -> Result<Value, EncodingError> {
        match self {
            Form::Json(_) => json::from_slice(data),
            Form::Primitive(Primitive::Text(kind)) => {
                let text = std::str::from_utf8(data).map_err(|e| EncodingError(e.to_string()))?;
                kind.parse(text)
            }
            Form::Primitive(Primitive::Done | Primitive::Unit) => Ok(Value::Null),
            Form::Primitive(Primitive::Bytes) => Ok(Value::String(base64::encode(data))),
            Form::Primitive(Primitive::Option(inner)) => match data.split_first() {
                None => Ok(Value::Null),
                Some((1, rest)) => inner.decode_value(rest),
                Some((marker, _)) => Err(EncodingError(format!(
                    "{}: expected a 0x01 marker, got {marker}",
                    self.manifest()
                ))),
            },
        }
    }
}

/// The last path segment of a type's name, without its type arguments: `Cart` for
/// `my_service::domain::Cart`, `Vec` for `alloc::vec::Vec<my_service::Item>`.
pub(crate) fn simple_name(name: &str) -> String {
    let base = name.split('<').next().unwrap_or(name);
    base.rsplit("::").next().unwrap_or(base).to_string()
}

/// The form a type of this name takes.
fn form_of(name: &str) -> Form {
    let text = |kind| Form::Primitive(Primitive::Text(kind));
    let is = |other: &str| name == other;
    if is(type_name::<String>()) {
        text(TextKind::String)
    } else if is(type_name::<i32>()) {
        text(TextKind::Int)
    } else if is(type_name::<i64>()) {
        text(TextKind::Long)
    } else if is(type_name::<i16>()) {
        text(TextKind::Short)
    } else if is(type_name::<i8>()) {
        text(TextKind::Byte)
    } else if is(type_name::<f64>()) {
        text(TextKind::Double)
    } else if is(type_name::<f32>()) {
        text(TextKind::Float)
    } else if is(type_name::<bool>()) {
        text(TextKind::Boolean)
    } else if is(type_name::<Duration>()) {
        text(TextKind::DurationMillis)
    } else if is(type_name::<Done>()) {
        Form::Primitive(Primitive::Done)
    } else if is(type_name::<()>()) {
        Form::Primitive(Primitive::Unit)
    } else if is(type_name::<Bytes>()) {
        Form::Primitive(Primitive::Bytes)
    } else if let Some(inner) = name
        .strip_prefix("core::option::Option<")
        .and_then(|rest| rest.strip_suffix('>'))
    {
        Form::Primitive(Primitive::Option(Box::new(form_of(inner))))
    } else {
        Form::Json(simple_name(name))
    }
}

/// The manifest a type's values carry by default.
pub fn manifest_for<T: ?Sized>() -> String {
    form_of(type_name::<T>()).manifest()
}

/// The primitive form a manifest names, including `option[...]` of one; `None` for a manifest
/// that names a domain type, which only its own codec can read.
pub fn codec_for_manifest(manifest: &str) -> Option<Form> {
    let found = match manifest {
        "done" => Some(Primitive::Done),
        "unit" => Some(Primitive::Unit),
        "bytes" => Some(Primitive::Bytes),
        other => TextKind::ALL
            .iter()
            .find(|k| k.manifest() == other)
            .map(|k| Primitive::Text(*k)),
    };
    if let Some(primitive) = found {
        return Some(Form::Primitive(primitive));
    }
    let inner = manifest.strip_prefix("option[")?.strip_suffix(']')?;
    codec_for_manifest(inner).map(|form| Form::Primitive(Primitive::Option(Box::new(form))))
}

/// The default codec for any serde type: the form its type takes, under that form's manifest
/// unless named otherwise.
pub struct Auto<T> {
    form: Form,
    manifest: Cow<'static, str>,
    marker: PhantomData<fn() -> T>,
}

impl<T> Auto<T> {
    /// The codec the type takes by default.
    pub fn new() -> Auto<T> {
        let form = form_of(type_name::<T>());
        let manifest = Cow::Owned(form.manifest());
        Auto {
            form,
            manifest,
            marker: PhantomData,
        }
    }

    /// The same encoding under a manifest of the service's choosing.
    pub fn named(manifest: impl Into<Cow<'static, str>>) -> Auto<T> {
        Auto {
            manifest: manifest.into(),
            ..Auto::new()
        }
    }

    /// The form this codec writes.
    pub fn form(&self) -> &Form {
        &self.form
    }

    /// Whether this is an option around a JSON type, written from the type rather than a value
    /// tree so its fields keep their declared order.
    fn optional_json(&self) -> bool {
        matches!(&self.form, Form::Primitive(Primitive::Option(inner)) if matches!(**inner, Form::Json(_)))
    }
}

impl<T> Default for Auto<T> {
    fn default() -> Auto<T> {
        Auto::new()
    }
}

impl<T: Serialize> Auto<T> {
    /// The value's bytes. Needs only `Serialize`, so a reply can be written without being readable.
    pub fn encode_value(&self, value: &T) -> Result<Vec<u8>, EncodingError> {
        if matches!(self.form, Form::Json(_)) {
            return json::to_vec(value);
        }
        if self.optional_json() {
            let written = json::to_vec(value)?;
            if written == b"null" {
                return Ok(Vec::new());
            }
            let mut out = vec![1u8];
            out.extend(written);
            return Ok(out);
        }
        self.form.encode_value(&serde_json::to_value(value)?)
    }

    /// The value as a whole payload.
    pub fn to_payload(&self, value: &T) -> Result<crate::proto::Payload, EncodingError> {
        Ok(crate::proto::Payload {
            content_type: self.form.content_type().as_str().to_string(),
            manifest: self.manifest.to_string(),
            data: self.encode_value(value)?,
        })
    }
}

impl<T: DeserializeOwned> Auto<T> {
    /// The value these bytes are. Needs only `DeserializeOwned`.
    pub fn decode_value(&self, data: &[u8]) -> Result<T, EncodingError> {
        if matches!(self.form, Form::Json(_)) {
            return json::from_slice(data);
        }
        if self.optional_json() {
            return match data.split_first() {
                None => json::from_slice(b"null"),
                Some((1, rest)) => json::from_slice(rest),
                Some((marker, _)) => Err(EncodingError(format!(
                    "{}: expected a 0x01 marker, got {marker}",
                    self.manifest
                ))),
            };
        }
        Ok(serde_json::from_value(self.form.decode_value(data)?)?)
    }
}

impl<T: Serialize + DeserializeOwned> Codec<T> for Auto<T> {
    fn manifest(&self) -> &str {
        &self.manifest
    }

    fn content_type(&self) -> ContentType {
        self.form.content_type()
    }

    fn encode(&self, value: &T) -> Result<Vec<u8>, EncodingError> {
        self.encode_value(value)
    }

    fn decode(&self, data: &[u8]) -> Result<T, EncodingError> {
        self.decode_value(data)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde::Deserialize;

    #[derive(Serialize, Deserialize, Debug, PartialEq)]
    struct Cart {
        items: Vec<String>,
        owner: String,
    }

    #[test]
    fn a_type_takes_the_manifest_the_encoding_gives_it() {
        assert_eq!(manifest_for::<String>(), "string");
        assert_eq!(manifest_for::<i32>(), "int");
        assert_eq!(manifest_for::<i64>(), "long");
        assert_eq!(manifest_for::<f64>(), "double");
        assert_eq!(manifest_for::<bool>(), "boolean");
        assert_eq!(manifest_for::<Duration>(), "duration-millis");
        assert_eq!(manifest_for::<Done>(), "done");
        assert_eq!(manifest_for::<()>(), "unit");
        assert_eq!(manifest_for::<Bytes>(), "bytes");
        assert_eq!(manifest_for::<Option<i32>>(), "option[int]");
        assert_eq!(manifest_for::<Cart>(), "Cart");
        assert_eq!(manifest_for::<Option<Cart>>(), "option[Cart]");
    }

    #[test]
    fn a_manifest_names_its_primitive() {
        let int = Form::Primitive(Primitive::Text(TextKind::Int));
        let expected = Form::Primitive(Primitive::Option(Box::new(int)));
        assert_eq!(codec_for_manifest("option[int]"), Some(expected));
        assert_eq!(codec_for_manifest("shopping-cart"), None);
    }

    #[test]
    fn primitives_cross_as_text_or_bytes() {
        let auto = Auto::<String>::new();
        assert_eq!(
            auto.encode(&"hello, world".to_string()).unwrap(),
            b"hello, world"
        );
        assert_eq!(
            Auto::<i64>::new().decode(b"9007199254740993").unwrap(),
            9_007_199_254_740_993
        );
        assert_eq!(Auto::<f64>::new().encode(&1e10).unwrap(), b"1.0E10");
        assert_eq!(
            Auto::<Duration>::new()
                .encode(&Duration::of_millis(1500))
                .unwrap(),
            b"1500"
        );
        assert_eq!(
            Auto::<Option<i32>>::new().encode(&Some(42)).unwrap(),
            b"\x0142"
        );
        assert_eq!(Auto::<Option<i32>>::new().decode(b"").unwrap(), None);
        assert!(Auto::<i32>::new().decode(b"9007199254740993").is_err());
    }

    #[test]
    fn an_optional_record_keeps_its_field_order() {
        let cart = Cart {
            items: vec![],
            owner: "a".into(),
        };
        let auto = Auto::<Option<Cart>>::new();
        let bytes = auto.encode(&Some(cart)).unwrap();
        assert_eq!(bytes, b"\x01{\"items\":[],\"owner\":\"a\"}");
        assert_eq!(auto.encode(&None).unwrap(), b"");
        assert_eq!(auto.decode(&bytes).unwrap().unwrap().owner, "a");
    }
}
