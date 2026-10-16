use crate::consts::*;
use crate::sha::sha256;

/// Next leg state after one step. `s_folded` is the integer folded into the seal.
#[inline(always)]
pub fn seal(s_folded: u64) -> u64 {
    let mut pre = [0u8; 20];
    pre[..12].copy_from_slice(SEAL_DOMAIN);
    pre[12..].copy_from_slice(&s_folded.to_le_bytes());
    debug_assert_eq!(pre.len(), 20);
    let digest = sha256(&pre);
    u64::from_le_bytes(digest[..8].try_into().unwrap())
}

/// Production step. Slot count and words-per-slot are literals here so LLVM
/// can turn `state % N_SLOTS` into a mask and vectorise the 2048-word fold.
/// The sized path below keeps the generic profile for conformance.
#[inline(always)]
pub fn step(terrain: &[u8], state: u64) -> u64 {
    debug_assert_eq!(terrain.len(), TERRAIN_BYTES);
    // N_SLOTS is 16384 = 2^14 (see consts.rs). A mask matches `%` for every u64.
    const _: () = assert!(N_SLOTS.is_power_of_two());
    const N_SLOTS_MASK: u64 = (N_SLOTS as u64) - 1;
    let i = (state & N_SLOTS_MASK) as usize;
    let start = i * SLOT_BYTES;
    // i < N_SLOTS and len == TERRAIN_BYTES, so the slot is in range. One slice
    // check covers the whole fold; the per-word indexing below is then exact.
    let slot = &terrain[start..start + SLOT_BYTES];
    let mut folded = state;
    // Const trip count WORDS_PER_SLOT (2048): LLVM vectorises under release.
    for w in 0..WORDS_PER_SLOT {
        let off = w * 8;
        let word = u64::from_le_bytes(slot[off..off + 8].try_into().unwrap());
        folded ^= word ^ (w as u64);
    }
    seal(folded)
}

pub fn step_sized(terrain: &[u8], state: u64, sizes: Sizes) -> u64 {
    let n_slots = sizes.n_slots() as u64;
    let i = (state % n_slots) as usize;
    let start = i * sizes.slot_bytes;
    let slot = &terrain[start..start + sizes.slot_bytes];
    let mut folded = state;
    for w in 0..sizes.words_per_slot() {
        let word = u64::from_le_bytes(slot[w * 8..w * 8 + 8].try_into().unwrap());
        folded ^= word ^ (w as u64);
    }
    seal(folded)
}

pub fn leg_result(terrain: &[u8], previous_checkpoint: &[u8; 32]) -> u64 {
    let mut state = u64::from_le_bytes(previous_checkpoint[..8].try_into().unwrap());
    for _ in 0..STEPS_PER_LEG {
        state = step(terrain, state);
    }
    state
}

pub fn leg_result_sized(terrain: &[u8], previous_checkpoint: &[u8; 32], sizes: Sizes) -> u64 {
    let mut state = u64::from_le_bytes(previous_checkpoint[..8].try_into().unwrap());
    for _ in 0..sizes.steps_per_leg {
        state = step_sized(terrain, state, sizes);
    }
    state
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Section 11.1. The seal preimage is `SEAL_DOMAIN ‖ le64(S_folded)`, 20 bytes.
    #[test]
    fn p11_1_seal_preimage_is_domain_then_le64() {
        let folded = 0x0102u64;
        let mut pre = Vec::new();
        pre.extend_from_slice(SEAL_DOMAIN);
        pre.extend_from_slice(&folded.to_le_bytes());
        assert_eq!(pre.len(), 20);
        assert_eq!(&pre[..12], b"pcoc-walk-v1");
        let digest = crate::sha::sha256(&pre);
        assert_eq!(
            seal(folded),
            u64::from_le_bytes(digest[..8].try_into().unwrap())
        );
    }

    #[test]
    fn seal_zero_matches_spec() {
        // SHA-256(SEAL_DOMAIN ‖ le64(0)) begins 4816bdaf23240476.
        assert_eq!(
            seal(0).to_le_bytes(),
            [0x48, 0x16, 0xbd, 0xaf, 0x23, 0x24, 0x04, 0x76]
        );
        assert_eq!(seal(0), 0x76042423afbd1648);
    }

    /// Section 11. A leg is exactly `steps_per_leg` steps. The small profile uses 32, not 2_000_000.
    #[test]
    fn p11_2_small_leg_runs_steps_per_leg() {
        let sizes = Sizes::conformance_small();
        let terrain = vec![0x7u8; sizes.terrain_bytes];
        let previous = [0x10u8; 32];
        let mut state = u64::from_le_bytes(previous[..8].try_into().unwrap());
        let start = state;
        for _ in 0..sizes.steps_per_leg {
            state = step_sized(&terrain, state, sizes);
        }
        assert_eq!(sizes.steps_per_leg, 32);
        assert_ne!(state, start);
        assert_eq!(leg_result_sized(&terrain, &previous, sizes), state);
    }

    /// Production step matches the sized path with production sizes (mask ≡ %).
    #[test]
    fn production_step_matches_sized() {
        let sizes = Sizes::production();
        let mut terrain = vec![0u8; sizes.terrain_bytes];
        for (i, b) in terrain.iter_mut().enumerate() {
            *b = (i as u8).wrapping_mul(17).wrapping_add(3);
        }
        let mut state = 0x0123_4567_89ab_cdefu64;
        for _ in 0..64 {
            let a = step(&terrain, state);
            let b = step_sized(&terrain, state, sizes);
            assert_eq!(a, b);
            state = a;
        }
    }
}
