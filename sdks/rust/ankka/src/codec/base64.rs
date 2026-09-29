//! Standard base64 with padding: how bytes travel inside JSON, and how the fixtures carry their
//! bytes. Small enough to own rather than depend on.

const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

/// `bytes` as base64 text.
pub fn encode(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let n = (u32::from(chunk[0]) << 16)
            | (u32::from(*chunk.get(1).unwrap_or(&0)) << 8)
            | u32::from(*chunk.get(2).unwrap_or(&0));
        for i in 0..4 {
            if i <= chunk.len() {
                out.push(ALPHABET[(n >> (18 - 6 * i) & 0x3f) as usize] as char);
            } else {
                out.push('=');
            }
        }
    }
    out
}

/// The bytes base64 `text` is, or why it is not base64.
pub fn decode(text: &str) -> Result<Vec<u8>, String> {
    let text = text.trim_end_matches('=');
    let mut out = Vec::with_capacity(text.len() * 3 / 4);
    let mut buffer = 0u32;
    let mut bits = 0;
    for c in text.bytes() {
        let value = ALPHABET
            .iter()
            .position(|a| *a == c)
            .ok_or_else(|| format!("{:?} is not base64", c as char))? as u32;
        buffer = (buffer << 6) | value;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((buffer >> bits & 0xff) as u8);
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    #[test]
    fn round_trips() {
        for (bytes, text) in [
            (&b""[..], ""),
            (b"\x01\x02\x03", "AQID"),
            (b"\x0142", "ATQy"),
            (b"hello, world", "aGVsbG8sIHdvcmxk"),
            (b"ab", "YWI="),
            (b"a", "YQ=="),
        ] {
            assert_eq!(super::encode(bytes), text);
            assert_eq!(super::decode(text).unwrap(), bytes);
        }
        assert!(super::decode("!!").is_err());
    }
}
