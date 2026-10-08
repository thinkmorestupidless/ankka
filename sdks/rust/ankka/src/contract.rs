//! A contract: what a topic carries, as a name and the fingerprint of the schema document a
//! component was built against. A project declares one on a topic; a component states one on each
//! topic it reads or publishes to; the runtime refuses, at start, a side whose name or fingerprint
//! is not the declared one. The name travels on the wire as every message's `ce-type`. Nothing
//! checks a message against the schema.
//!
//! The fingerprint is `sha256:` and the hex SHA-256 of the document under the JSON Canonicalization
//! Scheme (RFC 8785), so whitespace and key order in a saved file do not change it and a changed
//! field does. Every SDK fingerprints the same way, held to `protocol/fixtures/contracts/`.

use std::fmt;
use std::path::Path;

use sha2::{Digest, Sha256};

/// A name and the fingerprint of the schema document a component was built against.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Contract {
    /// The contract's name, `[a-z0-9][a-z0-9._-]{0,98}[a-z0-9]`.
    pub name: String,
    /// `sha256:` and 64 lowercase hex digits.
    pub fingerprint: String,
}

/// Why a contract could not be made: the name breaks the rule, or the document is not JSON.
#[derive(Debug)]
pub struct ContractError(String);

impl fmt::Display for ContractError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for ContractError {}

impl Contract {
    /// The schema document at `path`, fetched from the project's declaration and kept in the
    /// project, under `name`.
    pub fn from_file(path: impl AsRef<Path>, name: &str) -> Result<Contract, ContractError> {
        let path = path.as_ref();
        let bytes = std::fs::read(path)
            .map_err(|e| ContractError(format!("cannot read {}: {e}", path.display())))?;
        Contract::from_bytes(&bytes, name)
    }

    /// The schema document as bytes, under `name`.
    pub fn from_bytes(document: &[u8], name: &str) -> Result<Contract, ContractError> {
        if !valid_name(name) {
            return Err(ContractError(format!(
                "contract name '{name}' is not [a-z0-9][a-z0-9._-]{{0,98}}[a-z0-9]"
            )));
        }
        let fingerprint = fingerprint(document)?;
        Ok(Contract {
            name: name.to_string(),
            fingerprint,
        })
    }

    pub(crate) fn to_proto(&self) -> crate::proto::Contract {
        crate::proto::Contract {
            name: self.name.clone(),
            fingerprint: self.fingerprint.clone(),
        }
    }
}

/// `sha256:` and the hex SHA-256 of `document` under RFC 8785.
pub fn fingerprint(document: &[u8]) -> Result<String, ContractError> {
    let json: serde_json::Value = serde_json::from_slice(document)
        .map_err(|e| ContractError(format!("the document is not JSON: {e}")))?;
    let canonical = serde_jcs::to_string(&json)
        .map_err(|e| ContractError(format!("the document cannot be canonicalised: {e}")))?;
    let digest = Sha256::digest(canonical.as_bytes());
    let mut hex = String::with_capacity(7 + 64);
    hex.push_str("sha256:");
    for byte in digest {
        hex.push_str(&format!("{byte:02x}"));
    }
    Ok(hex)
}

/// `[a-z0-9][a-z0-9._-]{0,98}[a-z0-9]`.
pub fn valid_name(name: &str) -> bool {
    let bytes = name.as_bytes();
    let edge = |b: u8| b.is_ascii_lowercase() || b.is_ascii_digit();
    let inner = |b: u8| edge(b) || b == b'.' || b == b'_' || b == b'-';
    (2..=100).contains(&bytes.len())
        && edge(bytes[0])
        && edge(bytes[bytes.len() - 1])
        && bytes[1..bytes.len() - 1].iter().all(|&b| inner(b))
}
