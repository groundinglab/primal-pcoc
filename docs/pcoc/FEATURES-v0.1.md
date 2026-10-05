# PCOC features v0.1 — Account doors

Product SPEC for the Account → PCOC doors. Cut points stay in [CUT-POINTS.md](CUT-POINTS.md). Phase order stays in [DEV-PLAN.md](DEV-PLAN.md).

Terms, unchanged: **leg**, **leg result**, **STAGES 18**. A leg is 1,000,000 steps. The tip interrupts the drive. Live profile only. TEST is paused.

This SPEC is the 2026-10-05 Account doors lock. For these doors it supersedes the earlier phone→gate POST (`https://admission.pcoc.app/v1/sign-in`) and the bare-hash QR. The admission gate, the website, Cloudflare, and R2 are not changed here. A conformant HTTPS site is a later Public Site/Infra change.

The signature is BIP-340 over the 32 hash bytes (`CryptoUtils.sign` / `secp256k1.signSchnorr`). Docs still owes that one-liner on pcoc-prototype issue 87. This sentence is the phone cross-reference only.

## 1. Menu

Entry is Account → PCOC (the drawer tile immediately before Settings). Four doors:

1. **Enroll your PCOC** — working.
2. **Verify any PCOC** — full verify. Section 14, then section 15. The door shows valid or invalid only after that finishes.
3. **Create a PCOC** — copy only. No execute.
4. **Request service admission** — working.

The centre stays Wallet.

## 2. Enroll your PCOC

File picker only. Not a share sheet and not a deep link.

Enroll stores the picked file. The phone reads those bytes and keeps them as the enrolled object for the signed-in account. It does no local key check. It does not read a public key out of the file. It does not match the file to the account. There is no JNI reader and no native library gate. Storing the file is not a hike verdict.

Clear asks for confirmation, then deletes the stored object bytes only. It does not touch the account or the nsec. Enroll does not store an nsec or a mnemonic.

No enrolled object, or a login without the producer nsec (npub-only, internal signer, or external signer), means Request service admission does not submit. The admission site decides accept or deny.

## 3. Request service admission

The phone page says: scan the QR code on the service you want to get into. It does not show hash, header, or hex text. Signing asks **Confirm sign in** with OK or Cancel. There is no PIN. With no enrolled object the page does not open the scanner. It says to create a PCOC in this app, or copy one from the desktop and use Enroll your PCOC.

The QR shape is unchanged: one HTTPS URL whose query `h` is 64 hex, the highest stored header hash the site put there, Bitcoin display order. A bare 64-hex QR is not this door.

Explicit confirm, then the phone signs only those 32 bytes with BIP-340 via `CryptoUtils.sign`. Not the hex text, not a height, not the URL string, and not Nostr `signOrThrow`.

POST body only `{object_b64, signature}` to the **exact** scanned URL. No redirect to another host. Never send the nsec. The phone never POSTs the admission gate. Optional `sid` is phone↔site only and is not sent by this phone. No Option B fields (`id`, `challenge_id`, `/v1/challenge`, `/v1/admit`).

The phone shows accept or deny only from that response’s `decision`. Timeout, an empty body, or garbage is a failed request, not a denial. No polling and no Requested status channel. The phone does not show `verifier_ran`, `result`, or measurements.

## 4. Verify any PCOC

Full verify. The door runs section 14, then section 15, as the Java port of `reference/hike`. The work runs off the main thread. The screen shows a spinner and plain stage text: fetching block headers, building terrain, checking legs n of 6. Cancel stops the run. It shows **valid** when section 15 admits and **invalid** when it does not. A fetch that cannot reach the index shows “Couldn't reach the index — try again” and is neither word. Allocation failure or a run that does not finish shows neither word, with a plain message. Measurements stay hidden. Phone wall time is product. **VivoBook bare metal** is the timing acceptance host. Phone time is not that clock.

The live index is `https://index.pcoc.app`. The host constant is `index.pcoc.app`. Every index GET sends `User-Agent: pcoc-hike`. There is no phone index setting and no chain-rpc setting. Path-bind is `GET https://index.pcoc.app/index/{block_height}` with `network` equal to `main`. This phone does not GET Bari, and it does not use a test plane, a `test-*` host, or synth.

If terrain allocation fails, or the six-thread pool does not finish, the door shows neither word. It does not shrink the 268435456-byte terrain. `largeHeap` does not change that size. Draw count 6 and pool size 6 stay separate. The draw finishes on the calling thread before the pool is built. Fewer than 6 legs does not draw (`CouldNotDraw`) and the door shows invalid.

This door does not POST the admission gate.

## 5. Create shell

Create a PCOC is copy only.

- Create does not plant, gossip, call Bari, grow a seed list, or run produce. It does not create an object and does not show an admission result.

## 6. Gate and site, unchanged by this package

These stay on the gate and the site. This phone package does not edit them.

- The gate still checks the signature against the highest stored hash or the height-minus-one file when it exists.
- A missing public key at the gate is a gate denial. This phone does not make that check. The admission site decides.
- Only a match runs `hike::verify` and section 15. An undecodable shape reaches the verifier with no signature check, as the door already defines.
- The site must not add a Schnorr pre-check against QR-only `h` that can disagree with the gate’s window.
- Today’s live `pcoc.app/sign-in` bare-hash QR, where the page POSTs the gate, is not what this phone implements.

## 7. Feature table

| Feature | Status | Touch points | Pass / fail |
| --- | --- | --- | --- |
| Entry | This package | Account drawer tile immediately before Settings. Route `pcoc`. | Pass: Account → PCOC opens the four doors. The centre stays Wallet. |
| Enroll your PCOC | This package | File picker (`OpenDocument`). `EnrolledPcocStore` keeps the picked object bytes per account hex. | Pass: the picked file is stored for the signed-in account, with no local key check. Clear deletes object bytes only. The admission site decides. Fail: a local public-key read, a match against the account, a JNI or `.so` gate, or storing an nsec or mnemonic. |
| Verify any PCOC | This package | `VerifyPcocScreen`. Java section 14 then section 15, off the main thread. Live index `https://index.pcoc.app` with `User-Agent: pcoc-hike`. Spinner, stage text, Cancel. | Pass: valid or invalid only after the run finishes. Index fetch failure is a plain retry message, not a verdict. Measurements hidden. VivoBook bare metal is the timing host. Phone wall time is not that clock. Fewer than 6 legs shows invalid. Allocation failure or an unfinished pool of 6 shows neither word. Fail: a shorter procedure, a measurement on screen, a gate POST, Bari, synth, or a `test-*` host. |
| Create a PCOC | Copy shell | `CreatePcocScreen`. | Pass: copy only. Fail: plant, gossip, Bari, a seed list, produce, a created object, or an admission result. |
| Request service admission | This package | Camera QR after an enrolled object. Confirm sign in, OK or Cancel, no PIN. `AdmissionQr`, `AdmissionSigner` (`CryptoUtils.sign`), `AdmissionSiteClient`. | Pass: the page says to scan the service QR and does not show hash or header text. No enrolled object shows guidance instead of the scanner. The wire is still one HTTPS URL, `h` is 64 hex, BIP-340 over those 32 bytes, POST `{object_b64, signature}` to that URL, accept/deny only from `decision`. Fail: bare-hash QR, phone→gate POST, redirect to another host, nsec in the body, Option B, polling, or timeout/empty/garbage shown as deny. |
| Wrong login | This package | `Credential.admissionPrivateKeyOrNull` is `CredentialType.PrivateKey` only. | Pass: npub-only, internal signer, and external signer do not submit. |

## 8. Acceptance

- [ ] Account → PCOC opens Enroll your PCOC, Verify any PCOC, Create a PCOC, and Request service admission.
- [ ] Enroll uses the file picker only and stores the picked file for the signed-in account. It does no local key check.
- [ ] Clear confirms, then deletes stored object bytes only.
- [ ] The admission site decides accept or deny. The phone does not.
- [ ] No enroll, or npub-only / internal signer (and any login without the producer nsec): Request service admission does not submit.
- [ ] The admission page says to scan the QR code on the service. It does not show hash, header, or hex text. Sign in asks Confirm sign in, OK or Cancel, no PIN.
- [ ] With no enrolled object, Request service admission does not open the scanner. It tells you to create a PCOC here or enroll one from the desktop.
- [ ] The QR is one HTTPS URL whose query `h` is 64 hex. A bare hash is not signed and not submitted.
- [ ] Sign and submit is explicit. The signed message is the 32 bytes of `h` via `CryptoUtils.sign`, not `signOrThrow`.
- [ ] The POST body is only `{object_b64, signature}` to the exact scanned URL. The phone does not POST the admission gate and does not follow a redirect onto another host.
- [ ] The phone shows accept or deny only from `decision`. Timeout, empty, or garbage is a failed request, not a denial.
- [ ] Verify any runs section 14 then section 15 off the main thread, with a spinner, stage text, and Cancel. It shows valid or invalid only after the run finishes. A failed index fetch is neither word. Measurements stay hidden. VivoBook bare metal is the timing acceptance host. Phone wall time is not that clock.
- [ ] Create is copy only. Create does not plant, gossip, call Bari, grow a seed list, or run produce.
- [ ] The centre tab is still Wallet.
- [ ] This package does not change the admission gate, the website, Cloudflare, or R2.
