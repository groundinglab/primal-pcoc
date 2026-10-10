use crate::consts::*;

pub fn mix64(x: u64) -> u64 {
    let mut y = x.wrapping_add(MIX_ADD);
    y = (y ^ (y >> 30)).wrapping_mul(MIX_MUL1);
    y = (y ^ (y >> 27)).wrapping_mul(MIX_MUL2);
    y ^ (y >> 31)
}

pub fn production_seed(public_key: &[u8; 32], seed_beacon: &[u8; 32]) -> [u8; 64] {
    let mut out = [0u8; 64];
    out[..32].copy_from_slice(public_key);
    out[32..].copy_from_slice(seed_beacon);
    out
}

pub fn fill_pin(production_seed: &[u8; 64]) -> u64 {
    let mut x = u64::from_le_bytes(*SEED_DOMAIN);
    for i in 0..8 {
        let lane = u64::from_le_bytes(production_seed[i * 8..i * 8 + 8].try_into().unwrap());
        x = mix64(x ^ lane ^ (i as u64).wrapping_mul(MIX_ADD));
    }
    x
}

pub(crate) fn bitrev_parent(i: usize) -> usize {
    let i = i as u32;
    let bits = i.ilog2().max(1);
    let rev = i.reverse_bits() >> (32 - bits);
    (rev % i) as usize
}

pub(crate) fn mul_parent(i: usize) -> usize {
    let h = (i as u64).wrapping_mul(MIX_ADD);
    ((h >> 32) % (i as u64)) as usize
}

/// dag2 terrain. `TERRAIN_BYTES` long. Slot 0 is counter words only.
pub fn fill_terrain(fill_pin: u64) -> Vec<u8> {
    fill_terrain_sized(fill_pin, Sizes::production())
}

/// dag2 terrain at `sizes`. Slot 0 is counter words only. The parent rules are section 10.
pub fn fill_terrain_sized(fill_pin: u64, sizes: Sizes) -> Vec<u8> {
    let words_per_slot = sizes.words_per_slot();
    let slot_bytes = sizes.slot_bytes;
    let mut terrain = vec![0u8; sizes.terrain_bytes];
    let mut words = vec![0u64; words_per_slot];
    for i in 0..sizes.n_slots() {
        for j in 0..words_per_slot {
            words[j] = mix64(fill_pin ^ ((i as u64) << 32) ^ (j as u64) ^ WORD_XOR);
        }
        if i > 0 {
            let parents = [i - 1, bitrev_parent(i), mul_parent(i)];
            for (p_idx, parent) in parents.into_iter().enumerate() {
                let k = (p_idx as u64) + 1;
                let k_term = k.wrapping_mul(PARENT_MUL);
                let base = parent * slot_bytes;
                for j in 0..words_per_slot {
                    let pw = u64::from_le_bytes(
                        terrain[base + j * 8..base + j * 8 + 8].try_into().unwrap(),
                    );
                    let rot = ((j as u32).wrapping_add(k as u32)) & 63;
                    let x = words[j] ^ pw.rotate_left(rot);
                    words[j] = mix64(x ^ k_term);
                }
            }
            let slot_mix = (i as u64).wrapping_mul(SLOT_MUL);
            for j in 0..words_per_slot {
                words[j] = mix64(words[j] ^ slot_mix);
            }
        }
        let base = i * slot_bytes;
        for (j, word) in words.iter().enumerate() {
            terrain[base + j * 8..base + j * 8 + 8].copy_from_slice(&word.to_le_bytes());
        }
    }
    terrain
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Section 10. The production seed is the key followed by the beacon. Nothing is hashed yet.
    #[test]
    fn p10_1_production_seed_is_key_then_beacon() {
        let key = [0x11u8; 32];
        let beacon = [0x22u8; 32];
        let seed = production_seed(&key, &beacon);
        assert_eq!(&seed[..32], &key);
        assert_eq!(&seed[32..], &beacon);
        assert_eq!(seed.len(), 64);
    }

    #[test]
    fn fill_pin_matches_spec_vector() {
        let mut seed = [0u8; 64];
        seed[..32].fill(0x11);
        seed[32..].fill(0x22);
        assert_eq!(fill_pin(&seed), 0x7df0aff879fb1171);
    }

    #[test]
    fn parent_indices_match_section_10() {
        assert_eq!(bitrev_parent(1), 0);
        assert_eq!(bitrev_parent(5), 2);
        assert_eq!(mul_parent(5), 4);
        assert_eq!(bitrev_parent(16383), 8191);
        assert_eq!(mul_parent(16383), 9976);
    }
}
