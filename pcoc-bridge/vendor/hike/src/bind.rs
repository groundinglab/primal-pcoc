use crate::consts::*;
use crate::sha::sha256;

pub fn terrain_sample(terrain: &[u8], previous_checkpoint: &[u8; 32]) -> Vec<u8> {
    terrain_sample_sized(terrain, previous_checkpoint, Sizes::production())
}

pub fn terrain_sample_sized(terrain: &[u8], previous_checkpoint: &[u8; 32], sizes: Sizes) -> Vec<u8> {
    let offset =
        (u64::from_le_bytes(previous_checkpoint[..8].try_into().unwrap()) % sizes.offset_modulus())
            as usize;
    terrain[offset..offset + sizes.sample_bytes].to_vec()
}

pub fn checkpoint_preimage(
    previous: &[u8; 32],
    waypoint: &[u8; 32],
    sample: &[u8],
    leg_result_le: &[u8; 8],
) -> Vec<u8> {
    let mut pre = Vec::with_capacity(64 + sample.len() + 8);
    pre.extend_from_slice(previous);
    pre.extend_from_slice(waypoint);
    pre.extend_from_slice(sample);
    pre.extend_from_slice(leg_result_le);
    pre
}

pub fn checkpoint(
    previous: &[u8; 32],
    waypoint: &[u8; 32],
    sample: &[u8],
    leg_result: u64,
) -> [u8; 32] {
    assert_eq!(sample.len(), SAMPLE_BYTES);
    let limb = leg_result.to_le_bytes();
    sha256(&checkpoint_preimage(previous, waypoint, sample, &limb))
}

pub fn checkpoint_sized(
    previous: &[u8; 32],
    waypoint: &[u8; 32],
    sample: &[u8],
    leg_result: u64,
    sizes: Sizes,
) -> [u8; 32] {
    assert_eq!(sample.len(), sizes.sample_bytes);
    let limb = leg_result.to_le_bytes();
    sha256(&checkpoint_preimage(previous, waypoint, sample, &limb))
}

pub fn fold_checkpoints(checkpoints: &[[u8; 32]]) -> [u8; 32] {
    let mut fold = [0u8; 32];
    for checkpoint in checkpoints {
        let mut pre = [0u8; 64];
        pre[..32].copy_from_slice(&fold);
        pre[32..].copy_from_slice(checkpoint);
        fold = sha256(&pre);
    }
    fold
}

pub fn waypoint_preimage(
    beacon: &[u8; 32],
    slot_sample: &[u8],
    opening_waypoint: &[u8; 32],
    fold: &[u8; 32],
) -> Vec<u8> {
    let mut pre = Vec::with_capacity(96 + slot_sample.len());
    pre.extend_from_slice(beacon);
    pre.extend_from_slice(slot_sample);
    pre.extend_from_slice(opening_waypoint);
    pre.extend_from_slice(fold);
    pre
}

pub fn successor_waypoint(
    terrain: &[u8],
    beacon: &[u8; 32],
    opening_waypoint: &[u8; 32],
    checkpoints: &[[u8; 32]],
) -> [u8; 32] {
    successor_waypoint_sized(
        terrain,
        beacon,
        opening_waypoint,
        checkpoints,
        Sizes::production(),
    )
}

pub fn successor_waypoint_sized(
    terrain: &[u8],
    beacon: &[u8; 32],
    opening_waypoint: &[u8; 32],
    checkpoints: &[[u8; 32]],
    sizes: Sizes,
) -> [u8; 32] {
    let fold = fold_checkpoints(checkpoints);
    let slot_index =
        (u64::from_le_bytes(beacon[..8].try_into().unwrap()) % sizes.n_slots() as u64) as usize;
    let start = slot_index * sizes.slot_bytes;
    let slot_sample = &terrain[start..start + sizes.slot_bytes];
    sha256(&waypoint_preimage(
        beacon,
        slot_sample,
        opening_waypoint,
        &fold,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sha::sha256;

    #[test]
    fn checkpoint_preimage_is_1096_bytes() {
        let previous = [0xA1; 32];
        let waypoint = [0xB2; 32];
        let mut sample = vec![0u8; SAMPLE_BYTES];
        sample[0] = 0xC3;
        sample[SAMPLE_BYTES - 1] = 0xC4;
        let limb = [9, 8, 7, 6, 5, 4, 3, 2];
        let pre = checkpoint_preimage(&previous, &waypoint, &sample, &limb);
        assert_eq!(pre.len(), 1096);
        assert_eq!(pre.len(), CHECKPOINT_PREIMAGE_LEN);
        assert_eq!(&pre[..32], &previous);
        assert_eq!(&pre[32..64], &waypoint);
        assert_eq!(pre[64], 0xC3);
        assert_eq!(pre[64 + SAMPLE_BYTES - 1], 0xC4);
        assert_eq!(&pre[pre.len() - 8..], &limb);
    }

    #[test]
    fn waypoint_preimage_is_16480_bytes() {
        let beacon = [0x11; 32];
        let mut slot = vec![0u8; SLOT_BYTES];
        slot[0] = 0x22;
        slot[SLOT_BYTES - 1] = 0x33;
        let opening = [0x44; 32];
        let fold = [0x55; 32];
        let pre = waypoint_preimage(&beacon, &slot, &opening, &fold);
        assert_eq!(pre.len(), 16_480);
        assert_eq!(&pre[..32], &beacon);
        assert_eq!(pre[32], 0x22);
        assert_eq!(pre[32 + SLOT_BYTES - 1], 0x33);
        assert_eq!(&pre[32 + SLOT_BYTES..32 + SLOT_BYTES + 32], &opening);
        assert_eq!(&pre[pre.len() - 32..], &fold);
    }

    /// Section 11. The sample window is `sample_bytes` long. The small profile uses 32, not 1 KB.
    #[test]
    fn p11_3_small_sample_is_profile_sample_bytes() {
        let sizes = Sizes::conformance_small();
        let terrain = vec![0x5Au8; sizes.terrain_bytes];
        let mut previous = [0u8; 32];
        previous[..8].copy_from_slice(&10u64.to_le_bytes());
        let sample = terrain_sample_sized(&terrain, &previous, sizes);
        let offset = (10 % sizes.offset_modulus()) as usize;
        assert_eq!(sample.len(), 32);
        assert_eq!(sample.len(), sizes.sample_bytes);
        assert_eq!(sample, terrain[offset..offset + sizes.sample_bytes]);
    }

    /// Section 11. Production `SAMPLE_BYTES` is 1024. Offset 0 needs only that window.
    #[test]
    fn p11_3_production_checkpoint_sample_is_1024() {
        assert_eq!(SAMPLE_BYTES, 1024);
        assert_eq!(Sizes::production().sample_bytes, 1024);
        assert_eq!(
            Sizes::production().offset_modulus(),
            (TERRAIN_BYTES - SAMPLE_BYTES + 1) as u64
        );
        let mut terrain = vec![0u8; SAMPLE_BYTES];
        terrain[0] = 0x10;
        terrain[SAMPLE_BYTES - 1] = 0x20;
        let previous = [0u8; 32];
        let sample = terrain_sample(&terrain, &previous);
        assert_eq!(sample.len(), 1024);
        assert_eq!(sample, terrain);
        let mut shifted = [0u8; 32];
        shifted[..8].copy_from_slice(&7u64.to_le_bytes());
        let offset = (7 % OFFSET_MODULUS) as usize;
        assert_eq!(offset, 7);
        let mut wide = vec![0u8; offset + SAMPLE_BYTES];
        wide[offset] = 0x31;
        wide[offset + SAMPLE_BYTES - 1] = 0x32;
        let window = terrain_sample(&wide, &shifted);
        assert_eq!(window.len(), 1024);
        assert_eq!(window, wide[offset..offset + SAMPLE_BYTES]);
    }

    /// Section 12. `W_{g+1} = SHA-256(I_g ‖ slot_sample(I_g) ‖ W_g ‖ fold)`.
    #[test]
    fn a1_3_waypoint_rehash_on_a_small_slot() {
        let sizes = Sizes::conformance_small();
        let terrain = vec![0x3Cu8; sizes.terrain_bytes];
        let beacon = [0x11u8; 32];
        let opening = [0x22u8; 32];
        let checkpoint = [0x33u8; 32];
        let got = successor_waypoint_sized(&terrain, &beacon, &opening, &[checkpoint], sizes);
        let fold = fold_checkpoints(&[checkpoint]);
        let slot_index =
            (u64::from_le_bytes(beacon[..8].try_into().unwrap()) % sizes.n_slots() as u64) as usize;
        let start = slot_index * sizes.slot_bytes;
        let slot = &terrain[start..start + sizes.slot_bytes];
        assert_eq!(slot.len(), 64);
        let mut pre = Vec::new();
        pre.extend_from_slice(&beacon);
        pre.extend_from_slice(slot);
        pre.extend_from_slice(&opening);
        pre.extend_from_slice(&fold);
        assert_eq!(got, sha256(&pre));
    }

    #[test]
    fn fold_starts_at_zero_and_hashes_each_checkpoint() {
        let checkpoint = [0xAB; 32];
        let mut pre = [0u8; 64];
        pre[32..].copy_from_slice(&checkpoint);
        assert_eq!(fold_checkpoints(&[checkpoint]), sha256(&pre));
        assert_eq!(fold_checkpoints(&[]), [0u8; 32]);
    }
}
