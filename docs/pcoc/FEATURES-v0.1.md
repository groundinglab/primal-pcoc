# PCOC features v0.1 — Account doors

Product SPEC for the Account → PCOC doors. Cut points stay in [CUT-POINTS.md](CUT-POINTS.md). Phase order stays in [DEV-PLAN.md](DEV-PLAN.md).

Terms, unchanged: **leg**, **leg result**, **STAGES 18**. A leg is 1,000,000 steps. The tip interrupts the drive. Live profile only. TEST is paused.

This SPEC is the 2026-10-05 Account doors lock. For these doors it supersedes the earlier phone→gate POST (`https://admission.pcoc.app/v1/sign-in`) and the bare-hash QR. The admission gate, the website, Cloudflare, and R2 are not changed here. A conformant HTTPS site is a later Public Site/Infra change.

The signature is BIP-340 over the 32 hash bytes (`CryptoUtils.sign` / `secp256k1.signSchnorr`). Docs still owes that one-liner on pcoc-prototype issue 87. This sentence is the phone cross-reference only.

## 1. Menu

Entry is Account → PCOC (the drawer tile immediately before Settings). Four doors:

1. **Enroll your PCOC** — working.
2. **Verify any PCOC** — copy only. No execute.
3. **Create a PCOC** — copy only. No execute.
4. **Request service admission** — working.

The centre stays Wallet.

## 2. Enroll your PCOC

File picker only. Not a share sheet and not a deep link.

Read `hike::yielded_public_key` only. Not `object_public_key`. The reader returns three results: **PublicKey**, **NoPublicKey**, **Undecodable**. Do not collapse Undecodable into NoPublicKey. Do not add a fourth reader result. A returned PublicKey is not an “is a hike” pass. Do not hand-roll a CBOR layout or a second shape check on the phone.

- **PublicKey** whose 32 bytes match the signed-in account’s 32-byte key (the bytes, or the store’s hex spelling — not npub text compared with hex text) → may store the object bytes for admit.
- **PublicKey** mismatch → withhold. Not stored. Not an admission denial. Nothing submitted.
- **NoPublicKey** → stop. Not stored. Not a withhold. Not a denial. Nothing submitted.
- **Undecodable** → stop. Not the NoPublicKey path. Not a withhold. Not a denial. Nothing submitted.

Clear asks for confirmation, then deletes the stored object bytes only. It does not touch the account or the nsec.

No enrolled object, or the wrong login (npub-only or internal signer), means Request service admission does not submit. An external signer has no producer nsec here, so it does not submit either.

How Android calls `hike::yielded_public_key` is a thin JNI into that function (`libhike_yielded_public_key.so`). This tree does not ship that native artifact. Until it is linked, enroll stops without storing and without inventing a key. That stop is not NoPublicKey and not Undecodable.

## 3. Request service admission

The QR is one HTTPS URL. Query `h` is 64 hex: the highest stored header hash the site put there (from the index), Bitcoin display order. It is not the gossip tip. A bare 64-hex QR is not this door.

Explicit Sign and submit. The phone signs only those 32 bytes with BIP-340 via `CryptoUtils.sign`. Not the hex text, not a height, not the URL string, and not Nostr `signOrThrow`.

POST body only `{object_b64, signature}` to the **exact** scanned URL. No redirect to another host. Never send the nsec. The phone never POSTs the admission gate. Optional `sid` is phone↔site only and is not sent by this phone. No Option B fields (`id`, `challenge_id`, `/v1/challenge`, `/v1/admit`).

The phone shows accept or deny only from that response’s `decision`. Timeout, an empty body, or garbage is a failed request, not a denial. No polling and no Requested status channel. The phone does not show `verifier_ran`, `result`, or measurements.

## 4. Shells

Verify any PCOC and Create a PCOC are copy only.

- Verify does not run a verifier and does not show verify success, a verifier result, or measurements.
- Create does not plant, gossip, call Bari, grow a seed list, or run produce. It does not create an object and does not show an admission result.

## 5. Gate and site, unchanged by this package

These stay on the gate and the site. This phone package does not edit them.

- The gate still checks the signature against the highest stored hash or the height-minus-one file when it exists.
- A missing yielded key at the gate is a gate denial (the gate, not the phone withhold).
- Only a match runs `hike::verify` and section 15. An undecodable shape reaches the verifier with no signature check, as the door already defines.
- The site must not add a Schnorr pre-check against QR-only `h` that can disagree with the gate’s window.
- Today’s live `pcoc.app/sign-in` bare-hash QR, where the page POSTs the gate, is not what this phone implements.

## 6. Feature table

| Feature | Status | Touch points | Pass / fail |
| --- | --- | --- | --- |
| Entry | This package | Account drawer tile immediately before Settings. Route `pcoc`. | Pass: Account → PCOC opens the four doors. The centre stays Wallet. |
| Enroll your PCOC | This package | File picker (`OpenDocument`). `hike::yielded_public_key` via the compile-gated JNI reader. `EnrolledPcocStore` keeps object bytes per account hex. | Pass: PublicKey match stores the bytes and is not an “is a hike” pass. Mismatch withholds, does not store, and is not a denial. NoPublicKey and Undecodable are separate stops. Clear deletes object bytes only. Fail: `object_public_key`, a hand-rolled hike check, or collapsing Undecodable into NoPublicKey. |
| Verify any PCOC | Copy shell | `VerifyPcocScreen`. | Pass: copy only. Fail: a verify success, a local verifier result, or measurements. |
| Create a PCOC | Copy shell | `CreatePcocScreen`. | Pass: copy only. Fail: plant, gossip, Bari, a seed list, produce, a created object, or an admission result. |
| Request service admission | This package | Camera QR. `AdmissionQr`, `AdmissionSigner` (`CryptoUtils.sign`), `AdmissionSiteClient`. | Pass: one HTTPS URL, `h` is 64 hex, BIP-340 over those 32 bytes, POST `{object_b64, signature}` to that URL, accept/deny only from `decision`. Fail: bare-hash QR, phone→gate POST, redirect to another host, nsec in the body, Option B, polling, or timeout/empty/garbage shown as deny. |
| Wrong login | This package | `Credential.admissionPrivateKeyOrNull` is `CredentialType.PrivateKey` only. | Pass: npub-only, internal signer, and external signer do not submit. |

## 7. Acceptance

- [ ] Account → PCOC opens Enroll your PCOC, Verify any PCOC, Create a PCOC, and Request service admission.
- [ ] Enroll uses the file picker only and reads `hike::yielded_public_key` only.
- [ ] PublicKey match may store object bytes. That store is not an “is a hike” pass.
- [ ] PublicKey mismatch withholds: not stored, not an admission denial, nothing submitted.
- [ ] NoPublicKey and Undecodable are separate stops: not stored, not a withhold, not a denial, nothing submitted.
- [ ] Clear confirms, then deletes stored object bytes only.
- [ ] No enroll, or npub-only / internal signer (and any login without the producer nsec): Request service admission does not submit.
- [ ] The QR is one HTTPS URL whose query `h` is 64 hex. A bare hash is not signed and not submitted.
- [ ] Sign and submit is explicit. The signed message is the 32 bytes of `h` via `CryptoUtils.sign`, not `signOrThrow`.
- [ ] The POST body is only `{object_b64, signature}` to the exact scanned URL. The phone does not POST the admission gate and does not follow a redirect onto another host.
- [ ] The phone shows accept or deny only from `decision`. Timeout, empty, or garbage is a failed request, not a denial.
- [ ] Verify any and Create are copy only. Create does not plant, gossip, call Bari, grow a seed list, or run produce.
- [ ] The centre tab is still Wallet.
- [ ] This package does not change the admission gate, the website, Cloudflare, or R2.
