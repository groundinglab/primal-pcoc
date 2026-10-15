//! Canonical path body.
//!
//! Byte layout and `validate_for` are copied from `services/bari/src/proof.rs`
//! and `services/bari/src/merkle.rs`. This module does not define a second grammar.

use crate::sha::sha256;

pub const PROOF_BODY_VERSION: u8 = 0x01;
const MAX_SIBLINGS: usize = 32;
const FIXED_BODY_BYTES: usize = 146;
const LEAF_DOMAIN: u8 = 0x1A;
const PARENT_DOMAIN: u8 = 0x1B;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PathError {
    UnsupportedVersion(u8),
    Truncated,
    WrongLength { actual: usize, expected: usize },
    EmptyTree,
    LeafIndexOutOfRange { index: u32, tree_size: u32 },
    TooManySiblings(usize),
    NonCanonicalSiblingCount { actual: usize, expected: usize },
    WrongLeaf,
    WrongSelfSibling,
    WrongRoot,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct MerklePath {
    leaf: [u8; 32],
    leaf_index: u32,
    tree_size: u32,
    siblings: Vec<[u8; 32]>,
}

/// Locators §14.6 binds. `tree_size` stays on the Merkle path and is not a locator.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PathLocators {
    pub root: [u8; 32],
    pub txid: [u8; 32],
    pub vout: u32,
    pub block_hash: [u8; 32],
    pub block_height: u32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CanonicalPathBody {
    path: MerklePath,
    root: [u8; 32],
    txid: [u8; 32],
    vout: u32,
    block_hash: [u8; 32],
    block_height: u32,
}

fn leaf_node(leaf: &[u8; 32]) -> [u8; 32] {
    let mut pre = [0u8; 33];
    pre[0] = LEAF_DOMAIN;
    pre[1..].copy_from_slice(leaf);
    sha256(&pre)
}

fn parent_node(left: &[u8; 32], right: &[u8; 32]) -> [u8; 32] {
    let mut pre = [0u8; 65];
    pre[0] = PARENT_DOMAIN;
    pre[1..33].copy_from_slice(left);
    pre[33..].copy_from_slice(right);
    sha256(&pre)
}

fn canonical_sibling_count(mut width: u32) -> Result<usize, PathError> {
    if width == 0 {
        return Err(PathError::EmptyTree);
    }
    let mut count = 0usize;
    while width > 1 {
        width = width / 2 + width % 2;
        count += 1;
    }
    Ok(count)
}

impl MerklePath {
    fn resolve(&self) -> Result<[u8; 32], PathError> {
        if self.tree_size == 0 {
            return Err(PathError::EmptyTree);
        }
        if self.leaf_index >= self.tree_size {
            return Err(PathError::LeafIndexOutOfRange {
                index: self.leaf_index,
                tree_size: self.tree_size,
            });
        }
        let expected = canonical_sibling_count(self.tree_size)?;
        if self.siblings.len() != expected {
            return Err(PathError::NonCanonicalSiblingCount {
                actual: self.siblings.len(),
                expected,
            });
        }

        let mut node = leaf_node(&self.leaf);
        let mut index = self.leaf_index;
        let mut width = self.tree_size;
        for sibling in &self.siblings {
            node = if width % 2 == 1 && index == width - 1 {
                if sibling != &node {
                    return Err(PathError::WrongSelfSibling);
                }
                parent_node(&node, &node)
            } else if index % 2 == 0 {
                parent_node(&node, sibling)
            } else {
                parent_node(sibling, &node)
            };
            index /= 2;
            width = width / 2 + width % 2;
        }
        Ok(node)
    }

    fn verify(&self, expected_root: &[u8; 32]) -> Result<(), PathError> {
        if &self.resolve()? == expected_root {
            Ok(())
        } else {
            Err(PathError::WrongRoot)
        }
    }
}

impl CanonicalPathBody {
    fn encode(&self) -> Result<Vec<u8>, PathError> {
        self.validate_for(&self.path.leaf)?;
        let mut bytes = Vec::with_capacity(FIXED_BODY_BYTES + self.path.siblings.len() * 32);
        bytes.push(PROOF_BODY_VERSION);
        bytes.extend_from_slice(&self.path.leaf);
        bytes.extend_from_slice(&self.path.leaf_index.to_be_bytes());
        bytes.extend_from_slice(&self.path.tree_size.to_be_bytes());
        bytes.push(self.path.siblings.len() as u8);
        for sibling in &self.path.siblings {
            bytes.extend_from_slice(sibling);
        }
        bytes.extend_from_slice(&self.root);
        bytes.extend_from_slice(&self.txid);
        bytes.extend_from_slice(&self.vout.to_be_bytes());
        bytes.extend_from_slice(&self.block_hash);
        bytes.extend_from_slice(&self.block_height.to_be_bytes());
        debug_assert_eq!(
            bytes.len(),
            FIXED_BODY_BYTES + self.path.siblings.len() * 32
        );
        Ok(bytes)
    }

    fn decode(bytes: &[u8]) -> Result<Self, PathError> {
        if bytes.len() < FIXED_BODY_BYTES {
            return Err(PathError::Truncated);
        }
        if bytes[0] != PROOF_BODY_VERSION {
            return Err(PathError::UnsupportedVersion(bytes[0]));
        }
        let sibling_count = bytes[41] as usize;
        if sibling_count > MAX_SIBLINGS {
            return Err(PathError::TooManySiblings(sibling_count));
        }
        let expected_len = FIXED_BODY_BYTES + sibling_count * 32;
        if bytes.len() != expected_len {
            return Err(PathError::WrongLength {
                actual: bytes.len(),
                expected: expected_len,
            });
        }

        let leaf = array32(&bytes[1..33]);
        let leaf_index = be_u32(&bytes[33..37]);
        let tree_size = be_u32(&bytes[37..41]);
        let siblings_start = 42;
        let siblings_end = siblings_start + sibling_count * 32;
        let siblings = bytes[siblings_start..siblings_end]
            .chunks_exact(32)
            .map(array32)
            .collect();
        let mut offset = siblings_end;
        let root = array32(&bytes[offset..offset + 32]);
        offset += 32;
        let txid = array32(&bytes[offset..offset + 32]);
        offset += 32;
        let vout = be_u32(&bytes[offset..offset + 4]);
        offset += 4;
        let block_hash = array32(&bytes[offset..offset + 32]);
        offset += 32;
        let block_height = be_u32(&bytes[offset..offset + 4]);

        let body = Self {
            path: MerklePath {
                leaf,
                leaf_index,
                tree_size,
                siblings,
            },
            root,
            txid,
            vout,
            block_hash,
            block_height,
        };
        body.validate_for(&leaf)?;
        Ok(body)
    }

    fn validate_for(&self, requested_leaf: &[u8; 32]) -> Result<(), PathError> {
        if &self.path.leaf != requested_leaf {
            return Err(PathError::WrongLeaf);
        }
        if self.path.tree_size == 0 {
            return Err(PathError::EmptyTree);
        }
        if self.path.leaf_index >= self.path.tree_size {
            return Err(PathError::LeafIndexOutOfRange {
                index: self.path.leaf_index,
                tree_size: self.path.tree_size,
            });
        }
        if self.path.siblings.len() > MAX_SIBLINGS {
            return Err(PathError::TooManySiblings(self.path.siblings.len()));
        }
        let expected = canonical_sibling_count(self.path.tree_size)?;
        if self.path.siblings.len() != expected {
            return Err(PathError::NonCanonicalSiblingCount {
                actual: self.path.siblings.len(),
                expected,
            });
        }
        self.path.verify(&self.root)?;
        Ok(())
    }

    pub(crate) fn locators(&self) -> PathLocators {
        PathLocators {
            root: self.root,
            txid: self.txid,
            vout: self.vout,
            block_hash: self.block_hash,
            block_height: self.block_height,
        }
    }
}

pub fn path_locators(bytes: &[u8]) -> Result<PathLocators, PathError> {
    Ok(CanonicalPathBody::decode(bytes)?.locators())
}

/// Decode the wire bytes and accept them only for `waypoint`.
pub fn validate_path(bytes: &[u8], waypoint: &[u8; 32]) -> Result<(), PathError> {
    let body = CanonicalPathBody::decode(bytes)?;
    body.validate_for(waypoint)?;
    Ok(())
}

fn array32(bytes: &[u8]) -> [u8; 32] {
    let mut out = [0u8; 32];
    out.copy_from_slice(bytes);
    out
}

fn be_u32(bytes: &[u8]) -> u32 {
    u32::from_be_bytes(
        bytes
            .try_into()
            .expect("caller supplied a fixed 4-byte slice"),
    )
}

/// One-leaf body whose leaf is `waypoint` and whose block height is `block_height`.
///
/// Fixtures only. Produce stores a live Bari body after `validate_for` succeeds.
/// `block_hash` is 32 bytes of `0x55`.
pub fn encode_path_in_block(waypoint: &[u8; 32], block_height: u32) -> Vec<u8> {
    encode_path_for_block(waypoint, &[0x55; 32], block_height)
}

/// One-leaf fixture. `block_hash` is natural order, the order the path body stores.
/// The verify record reverses those bytes (SPEC §9).
pub fn encode_path_for_block(
    waypoint: &[u8; 32],
    block_hash: &[u8; 32],
    block_height: u32,
) -> Vec<u8> {
    let body = CanonicalPathBody {
        path: MerklePath {
            leaf: *waypoint,
            leaf_index: 0,
            tree_size: 1,
            siblings: Vec::new(),
        },
        root: leaf_node(waypoint),
        txid: [0x44; 32],
        vout: 2,
        block_hash: *block_hash,
        block_height,
    };
    body.encode().expect("single leaf")
}

/// One-leaf body whose leaf is `waypoint`. Fixtures only; produce stores a live Bari body.
pub fn encode_single_leaf(waypoint: &[u8; 32]) -> Vec<u8> {
    encode_path_in_block(waypoint, 123_456)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn single_leaf_round_trip_and_rejects_other_waypoint() {
        let leaf = [0x22; 32];
        let bytes = encode_single_leaf(&leaf);
        assert_eq!(bytes.len(), FIXED_BODY_BYTES);
        validate_path(&bytes, &leaf).unwrap();
        assert_eq!(
            validate_path(&bytes, &[0x99; 32]),
            Err(PathError::WrongLeaf)
        );
        let mut bad = bytes.clone();
        bad[0] = 0x02;
        assert_eq!(
            validate_path(&bad, &leaf),
            Err(PathError::UnsupportedVersion(0x02))
        );
    }

    #[test]
    fn bari_one_leaf_wire_kat() {
        // Independent literal from services/bari/src/proof.rs.
        let leaf = [0x11; 32];
        let body = CanonicalPathBody {
            path: MerklePath {
                leaf,
                leaf_index: 0,
                tree_size: 1,
                siblings: Vec::new(),
            },
            root: leaf_node(&leaf),
            txid: [0x44; 32],
            vout: 2,
            block_hash: [0x55; 32],
            block_height: 123_456,
        };
        let bytes = body.encode().unwrap();
        assert_eq!(
            hex_encode(&bytes),
            "011111111111111111111111111111111111111111111111111111111111111111000000000000000100b7959d170da4982cf2361b9b75c52b70c1b42596e6b1f40665068cfc82ede39544444444444444444444444444444444444444444444444444444444444444440000000255555555555555555555555555555555555555555555555555555555555555550001e240"
        );
    }

    #[test]
    fn three_leaf_odd_path_matches_bari_root() {
        let a = [0x11; 32];
        let b = [0x22; 32];
        let c = [0x33; 32];
        let bytes = encode_sorted_tree(&[a, b, c], &b);
        let root_at = 42 + canonical_sibling_count(3).unwrap() * 32;
        assert_eq!(
            hex_encode(&bytes[root_at..root_at + 32]),
            "9c9a3f601444d1adc90e749e6a7972ea850c3029d2e8504ddfdaf111e4ecac90"
        );
        validate_path(&bytes, &b).unwrap();
        assert_eq!(validate_path(&bytes, &a), Err(PathError::WrongLeaf));
        let mut wrong_root = bytes.clone();
        let root_at = 42 + canonical_sibling_count(3).unwrap() * 32;
        wrong_root[root_at] ^= 1;
        assert_eq!(validate_path(&wrong_root, &b), Err(PathError::WrongRoot));
    }

    fn encode_sorted_tree(leaves: &[[u8; 32]], leaf: &[u8; 32]) -> Vec<u8> {
        let mut levels = vec![leaves.iter().map(leaf_node).collect::<Vec<_>>()];
        while levels.last().unwrap().len() > 1 {
            let current = levels.last().unwrap();
            let mut next = Vec::new();
            for pair in current.chunks(2) {
                let right = pair.get(1).unwrap_or(&pair[0]);
                next.push(parent_node(&pair[0], right));
            }
            levels.push(next);
        }
        let leaf_index = leaves.iter().position(|item| item == leaf).unwrap() as u32;
        let mut index = leaf_index as usize;
        let mut width = leaves.len();
        let mut siblings = Vec::new();
        for level in &levels[..levels.len() - 1] {
            let sibling_index = if width % 2 == 1 && index == width - 1 {
                index
            } else {
                index ^ 1
            };
            siblings.push(level[sibling_index]);
            index /= 2;
            width = width.div_ceil(2);
        }
        let body = CanonicalPathBody {
            path: MerklePath {
                leaf: *leaf,
                leaf_index,
                tree_size: leaves.len() as u32,
                siblings,
            },
            root: levels.last().unwrap()[0],
            txid: [0x44; 32],
            vout: 2,
            block_hash: [0x55; 32],
            block_height: 123_456,
        };
        body.encode().unwrap()
    }

    fn hex_encode(bytes: &[u8]) -> String {
        const HEX: &[u8; 16] = b"0123456789abcdef";
        let mut out = String::with_capacity(bytes.len() * 2);
        for byte in bytes {
            out.push(HEX[(byte >> 4) as usize] as char);
            out.push(HEX[(byte & 0xf) as usize] as char);
        }
        out
    }
}
