//! Tip-bound Hashcash for `POST /waypoint`.
//!
//! Copied from `services/bari/src/hashcash.rs`. The hike does not define a second puzzle.

use crate::sha::sha256;

/// Algorithm tag mixed into the challenge (not on the wire).
pub const HASHCASH_TAG: &[u8] = b"bari-hc-v1";

/// Difficulty: 21 leading zero bits.
pub const HASHCASH_BITS_STRAW: u32 = 21;

/// HTTP header name (canonical).
pub const HASHCASH_NONCE_HEADER: &str = "x-bari-hashcash-nonce";

/// Derive the 32-byte challenge from the live tip hash (natural order).
pub fn challenge_from_tip(tip_hash: &[u8; 32]) -> [u8; 32] {
    let mut pre = Vec::with_capacity(HASHCASH_TAG.len() + 32);
    pre.extend_from_slice(HASHCASH_TAG);
    pre.extend_from_slice(tip_hash);
    sha256(&pre)
}

/// `SHA256(challenge ‖ waypoint ‖ nonce_le_u64)`.
pub fn hashcash_digest(challenge: &[u8; 32], waypoint: &[u8; 32], nonce: u64) -> [u8; 32] {
    let mut pre = Vec::with_capacity(32 + 32 + 8);
    pre.extend_from_slice(challenge);
    pre.extend_from_slice(waypoint);
    pre.extend_from_slice(&nonce.to_le_bytes());
    sha256(&pre)
}

/// Count leading zero bits in a SHA-256 digest (big-endian bit order within bytes).
pub fn leading_zero_bits(digest: &[u8; 32]) -> u32 {
    let mut bits = 0u32;
    for byte in digest {
        if *byte == 0 {
            bits += 8;
            continue;
        }
        bits += byte.leading_zeros();
        break;
    }
    bits
}

pub fn verify(challenge: &[u8; 32], waypoint: &[u8; 32], nonce: u64, bits: u32) -> bool {
    let digest = hashcash_digest(challenge, waypoint, nonce);
    leading_zero_bits(&digest) >= bits
}

/// Brute-force a nonce that satisfies `bits` leading zero bits.
///
/// The search is the one in `services/bari`: start at 0 and add 1. A wrapped
/// search is a produce failure rather than a process abort.
pub fn solve(challenge: &[u8; 32], waypoint: &[u8; 32], bits: u32) -> Result<u64, ()> {
    let mut nonce = 0u64;
    loop {
        if verify(challenge, waypoint, nonce, bits) {
            return Ok(nonce);
        }
        nonce = nonce.wrapping_add(1);
        if nonce == 0 {
            return Err(());
        }
    }
}

/// Production difficulty is [`HASHCASH_BITS_STRAW`].
///
/// Callers that need a lower count pass `bits` into [`solve`] and [`verify`].
/// An environment variable cannot lower this.
pub fn effective_bits() -> u32 {
    HASHCASH_BITS_STRAW
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn challenge_is_tag_then_natural_tip() {
        let tip = [0x11; 32];
        let mut pre = b"bari-hc-v1".to_vec();
        pre.extend_from_slice(&tip);
        assert_eq!(challenge_from_tip(&tip), sha256(&pre));
        assert_ne!(challenge_from_tip(&[1; 32]), challenge_from_tip(&[2; 32]));
    }

    #[test]
    fn committed_21_bit_stamp_verifies_without_mining() {
        let tip = [0x11; 32];
        let waypoint = [0x22; 32];
        let nonce = 651_896u64;
        let challenge = challenge_from_tip(&tip);
        assert!(verify(&challenge, &waypoint, nonce, HASHCASH_BITS_STRAW));
        assert!(leading_zero_bits(&hashcash_digest(&challenge, &waypoint, nonce)) >= 21);
        let previous_bits = std::env::var("BARI_HASHCASH_BITS").ok();
        let previous_gate = std::env::var("PCOC_HASHCASH_TEST").ok();
        std::env::set_var("PCOC_HASHCASH_TEST", "1");
        std::env::set_var("BARI_HASHCASH_BITS", "1");
        assert_eq!(effective_bits(), HASHCASH_BITS_STRAW);
        match previous_bits {
            Some(value) => std::env::set_var("BARI_HASHCASH_BITS", value),
            None => std::env::remove_var("BARI_HASHCASH_BITS"),
        }
        match previous_gate {
            Some(value) => std::env::set_var("PCOC_HASHCASH_TEST", value),
            None => std::env::remove_var("PCOC_HASHCASH_TEST"),
        }
    }

    #[test]
    fn leading_zero_bits_counts_prefix() {
        let mut d = [0xffu8; 32];
        assert_eq!(leading_zero_bits(&d), 0);
        d[0] = 0x00;
        d[1] = 0x0f;
        assert_eq!(leading_zero_bits(&d), 8 + 4);
        d = [0u8; 32];
        assert_eq!(leading_zero_bits(&d), 256);
    }

    #[test]
    fn solve_and_verify_roundtrip_low_bits() {
        let tip = [0x11; 32];
        let wp = [0x22; 32];
        let challenge = challenge_from_tip(&tip);
        let nonce = solve(&challenge, &wp, 8).unwrap();
        assert!(verify(&challenge, &wp, nonce, 8));
        assert!(leading_zero_bits(&hashcash_digest(&challenge, &wp, nonce)) >= 8);
    }
}
