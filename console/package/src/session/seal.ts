/**
 * Authenticated encryption for values a browser carries but must not read or alter: AES-256-GCM
 * with a random 96-bit nonce, the cookie's name as additional data (so a value sealed for one cookie
 * cannot be replayed as another), base64url on the wire. The key is derived from the installation's
 * secret with HKDF-SHA256, so the secret itself may be any length and is never used directly.
 */
import { webcrypto } from "node:crypto";

const subtle = webcrypto.subtle;
const encoder = new TextEncoder();
const decoder = new TextDecoder();

export type SealKey = webcrypto.CryptoKey;

export async function deriveKey(secret: string, info: string): Promise<SealKey> {
  if (secret.length === 0) throw new Error("the session secret must not be empty");
  const material = await subtle.importKey("raw", encoder.encode(secret), "HKDF", false, ["deriveKey"]);
  return subtle.deriveKey(
    { name: "HKDF", hash: "SHA-256", salt: new Uint8Array(0), info: encoder.encode(info) },
    material,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}

export async function seal(value: unknown, key: SealKey, aad: string): Promise<string> {
  const iv = webcrypto.getRandomValues(new Uint8Array(12));
  const plain = encoder.encode(JSON.stringify(value));
  const cipher = new Uint8Array(
    await subtle.encrypt({ name: "AES-GCM", iv, additionalData: encoder.encode(aad) }, key, plain),
  );
  const out = new Uint8Array(iv.length + cipher.length);
  out.set(iv, 0);
  out.set(cipher, iv.length);
  return Buffer.from(out).toString("base64url");
}

/** The sealed value, or `null` for anything tampered with, truncated, or sealed under another key or name. */
export async function unseal<T = unknown>(text: string, key: SealKey, aad: string): Promise<T | null> {
  try {
    const bytes = Buffer.from(text, "base64url");
    if (bytes.length < 12 + 16) return null;
    const plain = await subtle.decrypt(
      { name: "AES-GCM", iv: bytes.subarray(0, 12), additionalData: encoder.encode(aad) },
      key,
      bytes.subarray(12),
    );
    return JSON.parse(decoder.decode(plain)) as T;
  } catch {
    return null;
  }
}
