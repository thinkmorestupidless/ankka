//! The memory convention of `protocol/WASM-ABI.md`: bytes cross as a pointer and a length into the
//! module's linear memory.
//!
//! A request arrives in a buffer the host allocated through [`alloc`] and the module now owns
//! ([`take`]). A reply leaves as one `u64` packing `ptr << 32 | len` ([`give`]); the host reads it
//! and hands it back through [`free`]. Zero is an empty reply. Pointers are 32-bit because a module's
//! memory is; natively these functions are never called by a host, and exist so the crate builds.

/// Packs a pointer and a length into the ABI's `u64`.
pub fn pack(ptr: u32, len: u32) -> u64 {
    (u64::from(ptr) << 32) | u64::from(len)
}

/// Unpacks the ABI's `u64` into a pointer and a length.
pub fn unpack(packed: u64) -> (u32, u32) {
    ((packed >> 32) as u32, (packed & 0xffff_ffff) as u32)
}

/// `ankka1_alloc`: a buffer of `len` bytes the host writes a request into. The module owns it once
/// the export it was written for is called.
pub fn alloc(len: i32) -> i32 {
    let mut buffer = Vec::<u8>::with_capacity(len.max(0) as usize);
    let ptr = buffer.as_mut_ptr();
    std::mem::forget(buffer);
    ptr as usize as i32
}

/// `ankka1_free`: gives back a buffer the host has finished reading.
///
/// # Safety
///
/// `ptr` and `len` must describe a buffer this module handed out through [`give`] or [`alloc`], not
/// yet freed.
pub unsafe fn free(ptr: i32, len: i32) {
    let len = len.max(0) as usize;
    // SAFETY: the caller hands back exactly what `give` or `alloc` produced, whose capacity is `len`.
    unsafe {
        drop(Vec::from_raw_parts(
            ptr as u32 as usize as *mut u8,
            len,
            len,
        ))
    }
}

/// Takes ownership of the bytes at `ptr`: a request the host wrote into a buffer from [`alloc`], or
/// a reply a host import allocated the same way.
///
/// # Safety
///
/// `ptr` and `len` must describe a buffer from [`alloc`] with capacity `len`, written in full, and
/// not taken before.
pub unsafe fn take(ptr: i32, len: i32) -> Vec<u8> {
    let len = len.max(0) as usize;
    if len == 0 {
        return Vec::new();
    }
    // SAFETY: the caller guarantees the buffer came from `alloc(len)` and is taken once.
    unsafe { Vec::from_raw_parts(ptr as u32 as usize as *mut u8, len, len) }
}

/// Hands bytes to the host as the ABI's packed `u64`; the host frees them. Empty bytes are zero.
pub fn give(bytes: Vec<u8>) -> i64 {
    if bytes.is_empty() {
        return 0;
    }
    let boxed = bytes.into_boxed_slice();
    let len = boxed.len() as u32;
    let ptr = Box::into_raw(boxed) as *mut u8 as usize as u32;
    pack(ptr, len) as i64
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn packing_round_trips() {
        assert_eq!(unpack(pack(0x1234, 0x56)), (0x1234, 0x56));
        assert_eq!(unpack(pack(u32::MAX, u32::MAX)), (u32::MAX, u32::MAX));
        assert_eq!(give(Vec::new()), 0);
    }
}
