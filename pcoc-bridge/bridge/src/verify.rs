//! Verify, in-process. The crate measures the object and returns the section 14
//! record. An undecodable value is not a record. A file that could not be
//! opened is the caller's concern.

use crate::INDEX_URL;
use serde_json::json;
use sha2::{Digest, Sha256};
use std::cell::Cell;
use std::time::Instant;

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct IndexReads {
    pub issued: u32,
    pub answered: u32,
    pub total_ms: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum VerifyOutcome {
    Record {
        /// Section 14 JSON, trailing newline included, as the crate wrote it.
        json: String,
        bytes: u64,
        sha256: String,
        reads: IndexReads,
    },
    Unreadable,
}

/// `threads` is the Rule A4.3 pool size. Zero means one.
/// Section 6 sizes. The phone verifier does not select conformance-small.
pub fn verify_object(bytes: &[u8], index_url: &str, threads: usize) -> VerifyOutcome {
    verify_with(
        bytes,
        index_url,
        threads,
        hike::Sizes::production(),
        "production",
    )
}

/// SPEC §16.1 sizes. Tests of a scripted object use this. The phone does not.
pub(crate) fn verify_object_sized(bytes: &[u8], index_url: &str, threads: usize) -> VerifyOutcome {
    verify_with(
        bytes,
        index_url,
        threads,
        hike::Sizes::conformance_small(),
        "conformance-small",
    )
}

fn verify_with(
    bytes: &[u8],
    index_url: &str,
    threads: usize,
    sizes: hike::Sizes,
    profile: &str,
) -> VerifyOutcome {
    let url = if index_url.is_empty() {
        INDEX_URL
    } else {
        index_url
    };
    let reads = Cell::new(IndexReads {
        issued: 0,
        answered: 0,
        total_ms: 0,
    });
    let note = |ok: bool, since: Instant| {
        let mut tally = reads.take();
        tally.issued += 1;
        if ok {
            tally.answered += 1;
            tally.total_ms += since.elapsed().as_millis() as u64;
        }
        reads.set(tally);
    };
    let agent = hike::http_agent();
    let measurements = hike::verify_with_bind_threads_sized(
        bytes,
        hike::AuditDraw::OsCsprng,
        |height| {
            let started = Instant::now();
            let read = hike::read_index_beacon(&agent, url, height).map_err(|_| ());
            note(read.is_ok(), started);
            read
        },
        |loc| {
            let started = Instant::now();
            match hike::index_document(&agent, url, u64::from(loc.block_height)) {
                Ok(document) => {
                    note(true, started);
                    match hike::proxy_matches(&document, loc) {
                        Ok(()) => hike::ChainRead::Match,
                        Err(()) => hike::ChainRead::Mismatch,
                    }
                }
                Err(_) => {
                    note(false, started);
                    hike::ChainRead::NotRead
                }
            }
        },
        threads.max(1),
        sizes,
    );
    if matches!(
        hike::yielded_public_key(bytes),
        hike::YieldedKey::Undecodable
    ) {
        return VerifyOutcome::Unreadable;
    }
    let json = hike::verify_record_json(profile, &measurements);
    if serde_json::from_str::<serde_json::Value>(json.trim_end()).is_err() {
        return VerifyOutcome::Unreadable;
    }
    let sha256 = hex_encode(&Sha256::digest(bytes));
    VerifyOutcome::Record {
        json,
        bytes: bytes.len() as u64,
        sha256,
        reads: reads.into_inner(),
    }
}

pub fn outcome_json(outcome: &VerifyOutcome, index_url: &str) -> String {
    match outcome {
        VerifyOutcome::Unreadable => json!({ "outcome": "unreadable" }).to_string(),
        VerifyOutcome::Record {
            json: record,
            bytes,
            sha256,
            reads,
        } => json!({
            "outcome": "record",
            "record": record,
            "bytes": bytes,
            "sha256": sha256,
            "issued": reads.issued,
            "answered": reads.answered,
            "total_ms": reads.total_ms,
            "index_url": index_url,
        })
        .to_string(),
    }
}

pub fn yielded_key(bytes: &[u8]) -> Option<[u8; 32]> {
    match hike::yielded_public_key(bytes) {
        hike::YieldedKey::PublicKey(key) => Some(key),
        hike::YieldedKey::NoPublicKey | hike::YieldedKey::Undecodable => None,
    }
}

fn hex_encode(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(HEX[(byte >> 4) as usize] as char);
        out.push(HEX[(byte & 0x0f) as usize] as char);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn junk_bytes_are_unreadable_and_do_not_need_the_index() {
        let outcome = verify_object(&[0xff, 0x00, 0x01], "http://127.0.0.1:9", 2);
        assert_eq!(outcome, VerifyOutcome::Unreadable);
    }

    #[test]
    fn a_minimal_public_key_map_yields_that_key() {
        let mut key = [0u8; 32];
        key[0] = 0x11;
        key[31] = 0x22;
        let bytes = public_key_map(&key);
        assert_eq!(yielded_key(&bytes), Some(key));
    }

    fn public_key_map(key: &[u8; 32]) -> Vec<u8> {
        let name = b"public_key";
        let mut out = Vec::with_capacity(2 + name.len() + 2 + key.len());
        out.push(0xa1);
        out.push(0x60 | name.len() as u8);
        out.extend_from_slice(name);
        out.push(0x58);
        out.push(32);
        out.extend_from_slice(key);
        out
    }
}
