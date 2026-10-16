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

/// Mask path. Only valid when `n_slots` is a power of two; callers select it
/// with `is_power_of_two()`. With production constants inlined, LLVM turns the
/// mask into a shift and vectorises the fold.
#[inline(always)]
fn step_masked(terrain: &[u8], state: u64, n_slots: usize, slot_bytes: usize) -> u64 {
    debug_assert!(n_slots.is_power_of_two());
    let i = (state & (n_slots as u64 - 1)) as usize;
    let start = i * slot_bytes;
    let slot = &terrain[start..start + slot_bytes];
    let mut folded = state;
    for w in 0..slot_bytes / 8 {
        let off = w * 8;
        let word = u64::from_le_bytes(slot[off..off + 8].try_into().unwrap());
        folded ^= word ^ (w as u64);
    }
    seal(folded)
}

/// Picks the mask path for power-of-two slot counts, else the generic `%`.
#[inline(always)]
#[cfg_attr(not(test), allow(dead_code))]
pub(crate) fn step_select(terrain: &[u8], state: u64, sizes: Sizes) -> u64 {
    let n = sizes.n_slots();
    if n.is_power_of_two() {
        step_masked(terrain, state, n, sizes.slot_bytes)
    } else {
        step_sized(terrain, state, sizes)
    }
}

/// Production step (section 6 sizes, constants visible to LLVM).
#[inline(always)]
pub fn step(terrain: &[u8], state: u64) -> u64 {
    if N_SLOTS.is_power_of_two() {
        step_masked(terrain, state, N_SLOTS, SLOT_BYTES)
    } else {
        step_sized(terrain, state, Sizes::production())
    }
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

    /// Plain `%` reference for one step at any slot count.
    fn step_reference(terrain: &[u8], state: u64, slot_bytes: usize) -> u64 {
        let n = (terrain.len() / slot_bytes) as u64;
        let i = (state % n) as usize;
        let slot = &terrain[i * slot_bytes..(i + 1) * slot_bytes];
        let mut folded = state;
        for w in 0..slot_bytes / 8 {
            let word = u64::from_le_bytes(slot[w * 8..w * 8 + 8].try_into().unwrap());
            folded ^= word ^ (w as u64);
        }
        seal(folded)
    }

    /// Non-power-of-two slot counts take the `step_sized` fallback in
    /// `step_select` and match `%`; N=4 and 8 take the mask path.
    #[test]
    fn step_sized_non_pow2_matches_modulo_reference() {
        for n in [3usize, 5, 6, 4, 8] {
            let sizes = Sizes { sample_bytes: 32, slot_bytes: 64, terrain_bytes: 64 * n, steps_per_leg: 8 };
            assert_eq!(sizes.n_slots(), n);
            let terrain: Vec<u8> = (0..sizes.terrain_bytes).map(|i| (i as u8).wrapping_mul(31).wrapping_add(7)).collect();
            let mut state = 0xdead_beef_0bad_f00du64;
            for _ in 0..200 {
                let a = step_select(&terrain, state, sizes);
                assert_eq!(a, step_reference(&terrain, state, 64), "n={n}");
                assert_eq!(a, step_sized(&terrain, state, sizes), "n={n}");
                state = a;
            }
        }
    }
}
