//! A pass over a value before it is written, refusing what the encoding has no form for rather than
//! letting serde_json write something else in its place: a NaN or an infinity (which it would write
//! as `null`), and an enum without `#[serde(tag = "type")]` (which it would write as
//! `{"ItemAdded":{…}}` or `"Ready"`, where the encoding stores `{"type":"ItemAdded",…}`).
//!
//! An internally tagged enum reaches a serializer as a struct or a map carrying its `type` field, so
//! any enum variant that arrives here as a variant is one that is not tagged.

use serde::Serialize;
use serde::ser::{self, Impossible};
use std::fmt;

/// What the pass found.
#[derive(Debug)]
pub struct Refusal(pub String);

impl fmt::Display for Refusal {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for Refusal {}

impl ser::Error for Refusal {
    fn custom<T: fmt::Display>(msg: T) -> Refusal {
        Refusal(msg.to_string())
    }
}

/// Walks `value`, answering the first thing the encoding cannot carry.
pub fn check<T: Serialize + ?Sized>(value: &T) -> Result<(), Refusal> {
    value.serialize(Guard)
}

fn untagged(name: &str, variant: &str) -> Refusal {
    Refusal(format!(
        "{name}::{variant} is an enum variant without a discriminator; declare {name} with \
         #[serde(tag = \"type\")] so it is written as {{\"type\":\"{variant}\",…}}, and give it \
         struct or unit variants (a tuple variant has no field names to write)"
    ))
}

fn finite(value: f64) -> Result<(), Refusal> {
    if value.is_finite() {
        Ok(())
    } else {
        Err(Refusal(format!("{value} is not a JSON number")))
    }
}

struct Guard;

impl ser::Serializer for Guard {
    type Ok = ();
    type Error = Refusal;
    type SerializeSeq = Guard;
    type SerializeTuple = Guard;
    type SerializeTupleStruct = Guard;
    type SerializeTupleVariant = Impossible<(), Refusal>;
    type SerializeMap = Guard;
    type SerializeStruct = Guard;
    type SerializeStructVariant = Impossible<(), Refusal>;

    fn serialize_bool(self, _: bool) -> Result<(), Refusal> {
        Ok(())
    }
    fn serialize_i8(self, _: i8) -> Result<(), Refusal> {
        Ok(())
    }
    fn serialize_i16(self, _: i16) -> Result<(), Refusal> {
        Ok(())
    }
    fn serialize_i32(self, _: i32) -> Result<(), Refusal> {
        Ok(())
    }
    fn serialize_i64(self, _: i64) -> Result<(), Refusal> {
        Ok(())
    }
    fn serialize_i128(self, _: i128) -> Result<(), Refusal> {
        Ok(())
    }
    fn serialize_u8(self, _: u8) -> Result<(), Refusal> {
        Ok(())
    }
    fn serialize_u16(self, _: u16) -> Result<(), Refusal> {
        Ok(())
    }
    fn serialize_u32(self, _: u32) -> Result<(), Refusal> {
        Ok(())
    }
    fn serialize_u64(self, _: u64) -> Result<(), Refusal> {
        Ok(())
    }
    fn serialize_u128(self, _: u128) -> Result<(), Refusal> {
        Ok(())
    }
    fn serialize_f32(self, v: f32) -> Result<(), Refusal> {
        finite(f64::from(v))
    }
    fn serialize_f64(self, v: f64) -> Result<(), Refusal> {
        finite(v)
    }
    fn serialize_char(self, _: char) -> Result<(), Refusal> {
        Ok(())
    }
    fn serialize_str(self, _: &str) -> Result<(), Refusal> {
        Ok(())
    }
    fn serialize_bytes(self, _: &[u8]) -> Result<(), Refusal> {
        Ok(())
    }
    fn serialize_none(self) -> Result<(), Refusal> {
        Ok(())
    }
    fn serialize_some<T: Serialize + ?Sized>(self, value: &T) -> Result<(), Refusal> {
        value.serialize(Guard)
    }
    fn serialize_unit(self) -> Result<(), Refusal> {
        Ok(())
    }
    fn serialize_unit_struct(self, _: &'static str) -> Result<(), Refusal> {
        Ok(())
    }
    fn serialize_unit_variant(
        self,
        name: &'static str,
        _: u32,
        variant: &'static str,
    ) -> Result<(), Refusal> {
        Err(untagged(name, variant))
    }
    fn serialize_newtype_struct<T: Serialize + ?Sized>(
        self,
        _: &'static str,
        value: &T,
    ) -> Result<(), Refusal> {
        value.serialize(Guard)
    }
    fn serialize_newtype_variant<T: Serialize + ?Sized>(
        self,
        name: &'static str,
        _: u32,
        variant: &'static str,
        _: &T,
    ) -> Result<(), Refusal> {
        Err(untagged(name, variant))
    }
    fn serialize_seq(self, _: Option<usize>) -> Result<Guard, Refusal> {
        Ok(Guard)
    }
    fn serialize_tuple(self, _: usize) -> Result<Guard, Refusal> {
        Ok(Guard)
    }
    fn serialize_tuple_struct(self, _: &'static str, _: usize) -> Result<Guard, Refusal> {
        Ok(Guard)
    }
    fn serialize_tuple_variant(
        self,
        name: &'static str,
        _: u32,
        variant: &'static str,
        _: usize,
    ) -> Result<Self::SerializeTupleVariant, Refusal> {
        Err(untagged(name, variant))
    }
    fn serialize_map(self, _: Option<usize>) -> Result<Guard, Refusal> {
        Ok(Guard)
    }
    fn serialize_struct(self, _: &'static str, _: usize) -> Result<Guard, Refusal> {
        Ok(Guard)
    }
    fn serialize_struct_variant(
        self,
        name: &'static str,
        _: u32,
        variant: &'static str,
        _: usize,
    ) -> Result<Self::SerializeStructVariant, Refusal> {
        Err(untagged(name, variant))
    }
}

impl ser::SerializeSeq for Guard {
    type Ok = ();
    type Error = Refusal;
    fn serialize_element<T: Serialize + ?Sized>(&mut self, value: &T) -> Result<(), Refusal> {
        value.serialize(Guard)
    }
    fn end(self) -> Result<(), Refusal> {
        Ok(())
    }
}

impl ser::SerializeTuple for Guard {
    type Ok = ();
    type Error = Refusal;
    fn serialize_element<T: Serialize + ?Sized>(&mut self, value: &T) -> Result<(), Refusal> {
        value.serialize(Guard)
    }
    fn end(self) -> Result<(), Refusal> {
        Ok(())
    }
}

impl ser::SerializeTupleStruct for Guard {
    type Ok = ();
    type Error = Refusal;
    fn serialize_field<T: Serialize + ?Sized>(&mut self, value: &T) -> Result<(), Refusal> {
        value.serialize(Guard)
    }
    fn end(self) -> Result<(), Refusal> {
        Ok(())
    }
}

impl ser::SerializeMap for Guard {
    type Ok = ();
    type Error = Refusal;
    fn serialize_key<T: Serialize + ?Sized>(&mut self, key: &T) -> Result<(), Refusal> {
        key.serialize(Guard)
    }
    fn serialize_value<T: Serialize + ?Sized>(&mut self, value: &T) -> Result<(), Refusal> {
        value.serialize(Guard)
    }
    fn end(self) -> Result<(), Refusal> {
        Ok(())
    }
}

impl ser::SerializeStruct for Guard {
    type Ok = ();
    type Error = Refusal;
    fn serialize_field<T: Serialize + ?Sized>(
        &mut self,
        _: &'static str,
        value: &T,
    ) -> Result<(), Refusal> {
        value.serialize(Guard)
    }
    fn end(self) -> Result<(), Refusal> {
        Ok(())
    }
}
