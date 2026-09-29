//! Text payloads (`text/plain`): a primitive that is a payload of its own crosses as its text, not
//! as JSON — `hello, world` rather than `"hello, world"`, `42`, `1.5`, `true`, and a duration as
//! its whole milliseconds.

use std::marker::PhantomData;

use serde_json::Value;

use super::time::Duration;
use super::{Codec, ContentType, EncodingError, float};

/// The primitives with a text form, by manifest.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum TextKind {
    /// `string`: the text itself.
    String,
    /// `int`: a 32-bit integer.
    Int,
    /// `long`: a 64-bit integer.
    Long,
    /// `short`: a 16-bit integer.
    Short,
    /// `byte`: an 8-bit integer.
    Byte,
    /// `double`: a 64-bit float, laid out as Java writes it.
    Double,
    /// `float`: a 32-bit float, likewise.
    Float,
    /// `boolean`: `true` or `false`.
    Boolean,
    /// `duration-millis`: a duration as whole milliseconds.
    DurationMillis,
}

impl TextKind {
    /// Every text kind, for looking one up by manifest.
    pub const ALL: [TextKind; 9] = [
        TextKind::String,
        TextKind::Int,
        TextKind::Long,
        TextKind::Short,
        TextKind::Byte,
        TextKind::Double,
        TextKind::Float,
        TextKind::Boolean,
        TextKind::DurationMillis,
    ];

    /// The manifest a payload of this kind carries.
    pub fn manifest(&self) -> &'static str {
        match self {
            TextKind::String => "string",
            TextKind::Int => "int",
            TextKind::Long => "long",
            TextKind::Short => "short",
            TextKind::Byte => "byte",
            TextKind::Double => "double",
            TextKind::Float => "float",
            TextKind::Boolean => "boolean",
            TextKind::DurationMillis => "duration-millis",
        }
    }

    fn range(&self) -> Option<(i64, i64)> {
        match self {
            TextKind::Int => Some((i64::from(i32::MIN), i64::from(i32::MAX))),
            TextKind::Long => Some((i64::MIN, i64::MAX)),
            TextKind::Short => Some((i64::from(i16::MIN), i64::from(i16::MAX))),
            TextKind::Byte => Some((i64::from(i8::MIN), i64::from(i8::MAX))),
            _ => None,
        }
    }

    /// The text of a value in its serde form (a duration's is its ISO-8601 string).
    pub fn render(&self, value: &Value) -> Result<String, EncodingError> {
        let wrong = || EncodingError(format!("{value} is not a {}", self.manifest()));
        match self {
            TextKind::String => value.as_str().map(str::to_string).ok_or_else(wrong),
            TextKind::Boolean => value.as_bool().map(|b| b.to_string()).ok_or_else(wrong),
            TextKind::Double => float::java_double(value.as_f64().ok_or_else(wrong)?),
            // A float widened to a double for serde: narrowing it back is exact.
            TextKind::Float => float::java_float(value.as_f64().ok_or_else(wrong)? as f32),
            TextKind::DurationMillis => {
                let text = value.as_str().ok_or_else(wrong)?;
                let duration = Duration::parse(text).map_err(|e| EncodingError(e.0))?;
                Ok(duration.to_millis().to_string())
            }
            _ => value.as_i64().map(|n| n.to_string()).ok_or_else(wrong),
        }
    }

    /// The serde form of this text: what `render` would have been given.
    pub fn parse(&self, text: &str) -> Result<Value, EncodingError> {
        let wrong = || EncodingError(format!("not a {}: {text:?}", self.manifest()));
        match self {
            TextKind::String => Ok(Value::String(text.to_string())),
            TextKind::Boolean => match text {
                "true" => Ok(Value::Bool(true)),
                "false" => Ok(Value::Bool(false)),
                _ => Err(wrong()),
            },
            TextKind::Double | TextKind::Float => {
                let n: f64 = text.trim().parse().map_err(|_| wrong())?;
                serde_json::Number::from_f64(n)
                    .map(Value::Number)
                    .ok_or_else(wrong)
            }
            TextKind::DurationMillis => {
                let millis: i64 = text.trim().parse().map_err(|_| wrong())?;
                Ok(Value::String(Duration::of_millis(millis).to_string()))
            }
            _ => {
                let n: i64 = text.trim().parse().map_err(|_| wrong())?;
                let (low, high) = self.range().expect("an integer kind");
                if n < low || n > high {
                    return Err(EncodingError(format!(
                        "{n} does not fit a {}",
                        self.manifest()
                    )));
                }
                Ok(Value::from(n))
            }
        }
    }
}

/// A Rust type with a text form, and the manifest it travels under.
pub trait TextValue: Sized {
    /// The kind this type is.
    const KIND: TextKind;

    /// The value's text.
    fn render(&self) -> Result<String, EncodingError>;

    /// The value this text is.
    fn parse(text: &str) -> Result<Self, EncodingError>;
}

macro_rules! integer {
    ($t:ty, $kind:ident) => {
        impl TextValue for $t {
            const KIND: TextKind = TextKind::$kind;
            fn render(&self) -> Result<String, EncodingError> {
                Ok(self.to_string())
            }
            fn parse(text: &str) -> Result<$t, EncodingError> {
                text.trim().parse().map_err(|_| {
                    EncodingError(format!("not a {}: {text:?}", TextKind::$kind.manifest()))
                })
            }
        }
    };
}

integer!(i32, Int);
integer!(i64, Long);
integer!(i16, Short);
integer!(i8, Byte);

impl TextValue for String {
    const KIND: TextKind = TextKind::String;
    fn render(&self) -> Result<String, EncodingError> {
        Ok(self.clone())
    }
    fn parse(text: &str) -> Result<String, EncodingError> {
        Ok(text.to_string())
    }
}

impl TextValue for bool {
    const KIND: TextKind = TextKind::Boolean;
    fn render(&self) -> Result<String, EncodingError> {
        Ok(self.to_string())
    }
    fn parse(text: &str) -> Result<bool, EncodingError> {
        TextKind::Boolean
            .parse(text)
            .map(|v| v.as_bool().unwrap_or_default())
    }
}

impl TextValue for f64 {
    const KIND: TextKind = TextKind::Double;
    fn render(&self) -> Result<String, EncodingError> {
        float::java_double(*self)
    }
    fn parse(text: &str) -> Result<f64, EncodingError> {
        text.trim()
            .parse()
            .map_err(|_| EncodingError(format!("not a double: {text:?}")))
    }
}

impl TextValue for f32 {
    const KIND: TextKind = TextKind::Float;
    fn render(&self) -> Result<String, EncodingError> {
        float::java_float(*self)
    }
    fn parse(text: &str) -> Result<f32, EncodingError> {
        text.trim()
            .parse()
            .map_err(|_| EncodingError(format!("not a float: {text:?}")))
    }
}

impl TextValue for Duration {
    const KIND: TextKind = TextKind::DurationMillis;
    fn render(&self) -> Result<String, EncodingError> {
        Ok(self.to_millis().to_string())
    }
    fn parse(text: &str) -> Result<Duration, EncodingError> {
        let millis: i64 = text
            .trim()
            .parse()
            .map_err(|_| EncodingError(format!("not a duration in milliseconds: {text:?}")))?;
        Ok(Duration::of_millis(millis))
    }
}

/// The text codec for a primitive.
pub struct Text<T>(PhantomData<fn() -> T>);

impl<T> Text<T> {
    /// The codec, under the type's primitive manifest.
    pub fn new() -> Text<T> {
        Text(PhantomData)
    }
}

impl<T> Default for Text<T> {
    fn default() -> Text<T> {
        Text::new()
    }
}

impl<T: TextValue> Codec<T> for Text<T> {
    fn manifest(&self) -> &str {
        T::KIND.manifest()
    }

    fn content_type(&self) -> ContentType {
        ContentType::Text
    }

    fn encode(&self, value: &T) -> Result<Vec<u8>, EncodingError> {
        value.render().map(String::into_bytes)
    }

    fn decode(&self, data: &[u8]) -> Result<T, EncodingError> {
        let text = std::str::from_utf8(data).map_err(|e| EncodingError(e.to_string()))?;
        T::parse(text)
    }
}
