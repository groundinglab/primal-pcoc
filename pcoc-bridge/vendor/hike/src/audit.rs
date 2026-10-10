//! Unbiased distinct global-leg draws, section 14.6.
//! The pinned stream is the CLI demo source; it is not part of the hike.

use crate::sha::sha256;

pub trait Entropy {
    fn fill(&mut self, dest: &mut [u8]) -> Result<(), ()>;
}

pub struct OsEntropy;

impl Entropy for OsEntropy {
    fn fill(&mut self, dest: &mut [u8]) -> Result<(), ()> {
        getrandom::getrandom(dest).map_err(|_| ())
    }
}

/// `block(i) = SHA-256(seed ‖ le64(i))`, concatenated.
pub struct PinnedEntropy {
    seed: [u8; 32],
    counter: u64,
    block: [u8; 32],
    pos: usize,
}

impl PinnedEntropy {
    pub fn new(seed: [u8; 32]) -> Self {
        Self {
            seed,
            counter: 0,
            block: [0; 32],
            pos: 32,
        }
    }

    fn next_block(&mut self) {
        let mut pre = [0u8; 40];
        pre[..32].copy_from_slice(&self.seed);
        pre[32..].copy_from_slice(&self.counter.to_le_bytes());
        self.block = sha256(&pre);
        self.counter += 1;
        self.pos = 0;
    }
}

impl Entropy for PinnedEntropy {
    fn fill(&mut self, dest: &mut [u8]) -> Result<(), ()> {
        for byte in dest.iter_mut() {
            if self.pos == 32 {
                self.next_block();
            }
            *byte = self.block[self.pos];
            self.pos += 1;
        }
        Ok(())
    }
}

/// `2^64 mod n`. Zero when `n` is a power of two (and when `n == 1`).
fn modulus_residue(n: u64) -> u64 {
    let rem = u64::MAX % n;
    if rem == n - 1 {
        0
    } else {
        rem + 1
    }
}

fn draw_one(n: u64, src: &mut dyn Entropy) -> Result<u64, ()> {
    if n == 0 {
        return Err(());
    }
    let residue = modulus_residue(n);
    loop {
        let mut buf = [0u8; 8];
        src.fill(&mut buf)?;
        let u = u64::from_le_bytes(buf);
        if residue == 0 {
            return Ok(u % n);
        }
        // limit = 2^64 - (2^64 mod n). Accept u < limit.
        let limit = 0u64.wrapping_sub(residue);
        if u < limit {
            return Ok(u % n);
        }
    }
}

pub fn draw_distinct(n: u64, k: usize, src: &mut dyn Entropy) -> Result<Vec<u64>, ()> {
    if n == 0 || (k as u64) > n {
        return Err(());
    }
    let mut chosen = Vec::with_capacity(k);
    while chosen.len() < k {
        let q = draw_one(n, src)?;
        if !chosen.contains(&q) {
            chosen.push(q);
        }
    }
    Ok(chosen)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sha::sha256;

    struct Script {
        bytes: Vec<u8>,
        pos: usize,
    }

    impl Entropy for Script {
        fn fill(&mut self, dest: &mut [u8]) -> Result<(), ()> {
            if self.pos + dest.len() > self.bytes.len() {
                return Err(());
            }
            dest.copy_from_slice(&self.bytes[self.pos..self.pos + dest.len()]);
            self.pos += dest.len();
            Ok(())
        }
    }

    #[test]
    fn power_of_two_accepts_every_draw() {
        let mut raw = Vec::new();
        raw.extend_from_slice(&u64::MAX.to_le_bytes());
        let mut src = Script { bytes: raw, pos: 0 };
        assert_eq!(draw_one(8, &mut src).unwrap(), u64::MAX % 8);
    }

    #[test]
    fn rejection_consumes_bytes() {
        // n = 6 leaves a residue of 4, so u64::MAX is rejected and 0 is index 0.
        let mut raw = Vec::new();
        raw.extend_from_slice(&u64::MAX.to_le_bytes());
        raw.extend_from_slice(&0u64.to_le_bytes());
        let mut src = Script { bytes: raw, pos: 0 };
        assert_eq!(draw_one(6, &mut src).unwrap(), 0);
        assert_eq!(src.pos, 16);
    }

    #[test]
    fn distinct_redraws_a_duplicate() {
        let mut raw = Vec::new();
        raw.extend_from_slice(&0u64.to_le_bytes());
        raw.extend_from_slice(&0u64.to_le_bytes());
        raw.extend_from_slice(&1u64.to_le_bytes());
        let mut src = Script { bytes: raw, pos: 0 };
        assert_eq!(draw_distinct(6, 2, &mut src).unwrap(), vec![0, 1]);
    }

    #[test]
    fn pinned_stream_crosses_blocks() {
        let seed = [0x11u8; 32];
        let mut src = PinnedEntropy::new(seed);
        let mut got = [0u8; 40];
        src.fill(&mut got).unwrap();
        let mut pre = [0u8; 40];
        pre[..32].copy_from_slice(&seed);
        let block0 = sha256(&pre);
        pre[32..].copy_from_slice(&1u64.to_le_bytes());
        let block1 = sha256(&pre);
        assert_eq!(&got[..32], &block0);
        assert_eq!(&got[32..], &block1[..8]);
    }

    #[test]
    fn os_csprng_yields_bytes() {
        let mut buf = [0u8; 8];
        OsEntropy.fill(&mut buf).unwrap();
    }
}
