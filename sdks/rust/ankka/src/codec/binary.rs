//! Binary payloads (`application/octet-stream`): `done` and `unit` as zero bytes, raw bytes as
//! themselves, and a top-level option as nothing for none or `0x01` then the value's bytes.

use std::marker::PhantomData;

use super::{Bytes, Codec, ContentType, Done, EncodingError};

/// `done`: the reply that says only that a command was handled.
#[derive(Debug, Clone, Copy, Default)]
pub struct DoneCodec;

impl Codec<Done> for DoneCodec {
    fn manifest(&self) -> &str {
        "done"
    }
    fn content_type(&self) -> ContentType {
        ContentType::Binary
    }
    fn encode(&self, _: &Done) -> Result<Vec<u8>, EncodingError> {
        Ok(Vec::new())
    }
    fn decode(&self, _: &[u8]) -> Result<Done, EncodingError> {
        Ok(Done)
    }
}

/// `unit`: a handler's input when it takes none.
#[derive(Debug, Clone, Copy, Default)]
pub struct UnitCodec;

impl Codec<()> for UnitCodec {
    fn manifest(&self) -> &str {
        "unit"
    }
    fn content_type(&self) -> ContentType {
        ContentType::Binary
    }
    fn encode(&self, _: &()) -> Result<Vec<u8>, EncodingError> {
        Ok(Vec::new())
    }
    fn decode(&self, _: &[u8]) -> Result<(), EncodingError> {
        Ok(())
    }
}

/// `bytes`: bytes as they are.
#[derive(Debug, Clone, Copy, Default)]
pub struct BytesCodec;

impl Codec<Bytes> for BytesCodec {
    fn manifest(&self) -> &str {
        "bytes"
    }
    fn content_type(&self) -> ContentType {
        ContentType::Binary
    }
    fn encode(&self, value: &Bytes) -> Result<Vec<u8>, EncodingError> {
        Ok(value.0.clone())
    }
    fn decode(&self, data: &[u8]) -> Result<Bytes, EncodingError> {
        Ok(Bytes(data.to_vec()))
    }
}

/// `option[<inner>]`: zero bytes for none, `0x01` then the inner codec's bytes otherwise.
pub struct OptionCodec<C, T> {
    inner: C,
    manifest: String,
    marker: PhantomData<fn() -> T>,
}

impl<C: Codec<T>, T> OptionCodec<C, T> {
    /// An option of what `inner` encodes.
    pub fn new(inner: C) -> OptionCodec<C, T> {
        let manifest = format!("option[{}]", inner.manifest());
        OptionCodec {
            inner,
            manifest,
            marker: PhantomData,
        }
    }
}

impl<C: Codec<T>, T> Codec<Option<T>> for OptionCodec<C, T> {
    fn manifest(&self) -> &str {
        &self.manifest
    }
    fn content_type(&self) -> ContentType {
        ContentType::Binary
    }
    fn encode(&self, value: &Option<T>) -> Result<Vec<u8>, EncodingError> {
        match value {
            None => Ok(Vec::new()),
            Some(v) => {
                let mut out = vec![1u8];
                out.extend(self.inner.encode(v)?);
                Ok(out)
            }
        }
    }
    fn decode(&self, data: &[u8]) -> Result<Option<T>, EncodingError> {
        match data.split_first() {
            None => Ok(None),
            Some((1, rest)) => self.inner.decode(rest).map(Some),
            Some((marker, _)) => Err(EncodingError(format!(
                "{}: expected a 0x01 marker, got {marker}",
                self.manifest
            ))),
        }
    }
}
