# PCOC features v0.1 — Account doors

Product SPEC for the Account → PCOC doors. Cut points stay in [CUT-POINTS.md](CUT-POINTS.md). Phase order stays in [DEV-PLAN.md](DEV-PLAN.md).

Terms, unchanged: **leg**, **leg result**, **STAGES 18**. A leg is 1,000,000 steps. The tip interrupts the drive. Live profile only. TEST is paused.

This SPEC is the 2026-10-05 Account doors lock. For these doors it supersedes the earlier phone→gate POST (`https://admission.pcoc.app/v1/sign-in`) and the bare-hash QR. The admission gate, the website, Cloudflare, and R2 are not changed here. A conformant HTTPS site is a later Public Site/Infra change.

The signature is BIP-340 over the 32 hash bytes (`CryptoUtils.sign` / `secp256k1.signSchnorr`). Docs still owes that one-liner on pcoc-prototype issue 87. This sentence is the phone cross-reference only.

## 1. Menu

Entry is Account → PCOC (the drawer tile immediately before Settings). Four doors:

1. **Enroll your PCOC** — working.
2. **Verify any PCOC** — full verify. The door writes a measurement record. It lists Profile, Shape, Checkpoints, Waypoints, Leg rate, and Six leg. It does not show valid or invalid.
3. **Create a PCOC** — copy only. No execute.
4. **Request service admission** — working.

The centre stays Wallet.

## 2. Enroll your PCOC

File picker only. Not a share sheet and not a deep link.

Enroll stores the picked file. The phone reads those bytes and keeps them as the enrolled object for the signed-in account. It does no local key check. It does not read a public key out of the file. It does not match the file to the account. There is no JNI reader and no native library gate. Storing the file is not a hike verdict.

When a file is saved, the stored state is plain text: “Saved. This PCOC will be checked by the service when you sign in.” The screen also shows the file name and the date saved. It shows no key and no hex.

Clear asks for confirmation, then deletes the stored object bytes only. It does not touch the account or the nsec. Enroll does not store an nsec or a mnemonic.

No enrolled object, or a login without the producer nsec (npub-only, internal signer, or external signer), means Request service admission does not submit. The admission site decides accept or deny.

## 3. Request service admission

The phone page says: scan the QR code on the service you want to get into. It does not show hash, header, or hex text. Signing asks **Confirm sign in** with OK or Cancel. There is no PIN. With no enrolled object the page does not open the scanner. It says to create a PCOC in this app, or copy one from the desktop and use Enroll your PCOC.

The QR shape is unchanged: one HTTPS URL whose query `h` is 64 hex, the highest stored header hash the site put there, Bitcoin display order. A bare 64-hex QR is not this door.

Explicit confirm, then the phone signs only those 32 bytes with BIP-340 via `CryptoUtils.sign`. Not the hex text, not a height, not the URL string, and not Nostr `signOrThrow`.

POST body only `{object_b64, signature}` to the **exact** scanned URL. No redirect to another host. Never send the nsec. The phone never POSTs the admission gate. Optional `sid` is phone↔site only and is not sent by this phone. No Option B fields (`id`, `challenge_id`, `/v1/challenge`, `/v1/admit`).

The phone is fire-and-forget after that POST. A 2xx response is delivery, including `decision: provisional` and a deny body that is not `bad_signature`. It does not show welcome, refused, or progress. It does not show `decision`, `verifier_ran`, `result`, or measurements. It does not poll. It does not follow provisional, accepted, or checking, and it does not revoke. There is no gate state machine on the phone.

When the POST is delivered, the phone says it was delivered and that the signature was made as the correct signatory. It tells you to look at the website page for welcome, refused, or progress. Those words stay on the website page.

A response whose error is `bad_signature` is a clear error. A timeout, a non-2xx response, a redirect, or any other delivery failure is a clear error. Neither error is a refusal. A policy deny in a 2xx body is not a phone refusal. The phone does not call it one.

## 4. Verify any PCOC

Full verify. The door runs the section 14 measurement, as the Java port of `reference/hike`. It does not apply section 15. It does not show valid or invalid. The work runs off the main thread. Only one run is in flight. The screen shows a spinner and plain stage text: fetching block headers, building terrain, checking legs n of 6. Cancel interrupts that run and releases the terrain room before another run can allocate one.

When the object decodes, the screen says “A measurement record was written.” and lists six rows:

| Row | Words |
| --- | --- |
| Profile | production. This phone runs the live profile. It does not offer another profile. |
| Shape | match or mismatch |
| Checkpoints | match, mismatch, or not read |
| Waypoints | match, mismatch, or not read |
| Leg rate | not read. The line also says “The rate was not read. This is not a verdict.” |
| Six leg | match, mismatch, not read, or not defined |

Those rows use only those words. A mismatch is a measurement. It is not a denial and not an admission. Policy words stay on Request service admission.

A file that cannot be read, or bytes that do not decode, says “The object file could not be read.” That message is not a measurement and not a policy verdict. A fetch that cannot reach the index shows “Couldn't reach the index — try again” and is not a row. Allocation failure or a run that does not finish says “Verify did not finish.” and is not a row. Debug logs record elapsed time for headers, terrain, and legs. Phone wall time is product. **VivoBook bare metal** is the timing acceptance host. Phone time is not that clock.

Expected on an A34-class phone: header fetches stay short, and building the 268435456-byte terrain is a few seconds. The six legs are a separate stage. That expectation is not a VivoBook acceptance measurement.

The live index is `https://index.pcoc.app`. The host constant is `index.pcoc.app`. Every index GET sends `User-Agent: pcoc-hike`. There is no phone index setting and no chain-rpc setting. Path-bind is `GET https://index.pcoc.app/index/{block_height}` with `network` equal to `main`. This phone does not GET Bari, and it does not use a test plane, a `test-*` host, or synth.

If terrain allocation fails, or the six-thread pool does not finish, the door says verify did not finish. It does not shrink the 268435456-byte terrain. `largeHeap` does not change that size. Draw count 6 and pool size 6 stay separate. The draw finishes on the calling thread before the pool is built. Fewer than 6 legs does not draw (`CouldNotDraw`). The Six leg row says not defined.

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
| Enroll your PCOC | This package | File picker (`OpenDocument`). `EnrolledPcocStore` keeps the picked object bytes per account hex, plus the file name and the date saved. | Pass: the picked file is stored for the signed-in account, with no local key check. The stored state says it will be checked by the service when you sign in, and shows the file name and the date saved. No key and no hex. Clear deletes object bytes only. The admission site decides. Fail: a local public-key read, a match against the account, a JNI or `.so` gate, storing an nsec or mnemonic, or copy that reads like the phone already approved the file. |
| Verify any PCOC | This package | `VerifyPcocScreen`. Java section 14 measurement, off the main thread, one run at a time. Live index `https://index.pcoc.app` with `User-Agent: pcoc-hike`. Spinner, stage text, Cancel. Terrain is one `long[]` room, released when the run ends. | Pass: after the run finishes, the screen lists Profile, Shape, Checkpoints, Waypoints, Leg rate, and Six leg. Profile says production. The other rows say match, mismatch, not read, or not defined. Leg rate not read also says the rate was not read and that this is not a verdict. An unreadable or undecodable file says the object file could not be read, and that is not a verdict. Index fetch failure is a plain retry message. Cancel interrupts the run and releases the room. Debug timing for headers, terrain, and legs. VivoBook bare metal is the timing host. Phone wall time is not that clock. Fewer than 6 legs records Six leg as not defined. Allocation failure or an unfinished pool of 6 says verify did not finish. Fail: a valid or invalid headline, section 15 on this door, a shorter procedure, a second room kept after cancel, a gate POST, Bari, synth, a `test-*` host, or accept, deny, refuse, or revoke on this screen. |
| Create a PCOC | Copy shell | `CreatePcocScreen`. | Pass: copy only. Fail: plant, gossip, Bari, a seed list, produce, a created object, or an admission result. |
| Request service admission | This package | Camera QR after an enrolled object. Confirm sign in, OK or Cancel, no PIN. `AdmissionQr`, `AdmissionSigner` (`CryptoUtils.sign`), `AdmissionSiteClient`. | Pass: the page says to scan the service QR and does not show hash or header text. No enrolled object shows guidance instead of the scanner. The wire is still one HTTPS URL, `h` is 64 hex, BIP-340 over those 32 bytes, POST `{object_b64, signature}` to that URL. A 2xx response, including provisional or a deny that is not `bad_signature`, says delivered and points at the website page. `bad_signature` and a real delivery failure are clear errors, not a refusal. Fail: bare-hash QR, phone→gate POST, redirect to another host, nsec in the body, Option B, polling, provisional or checking on the phone, or a 2xx shown as a delivery failure. |
| Wrong login | This package | `Credential.admissionPrivateKeyOrNull` is `CredentialType.PrivateKey` only. | Pass: npub-only, internal signer, and external signer do not submit. |

## 8. Acceptance

- [ ] Account → PCOC opens Enroll your PCOC, Verify any PCOC, Create a PCOC, and Request service admission.
- [ ] Enroll uses the file picker only and stores the picked file for the signed-in account. It does no local key check. The stored state says: Saved. This PCOC will be checked by the service when you sign in. The file name and the date saved are shown. No key and no hex.
- [ ] Clear confirms, then deletes stored object bytes only.
- [ ] The website page decides welcome or refused. The phone does not.
- [ ] No enroll, or npub-only / internal signer (and any login without the producer nsec): Request service admission does not submit.
- [ ] The admission page says to scan the QR code on the service. It does not show hash, header, or hex text. Sign in asks Confirm sign in, OK or Cancel, no PIN.
- [ ] With no enrolled object, Request service admission does not open the scanner. It tells you to create a PCOC here or enroll one from the desktop.
- [ ] The QR is one HTTPS URL whose query `h` is 64 hex. A bare hash is not signed and not submitted.
- [ ] Sign and submit is explicit. The signed message is the 32 bytes of `h` via `CryptoUtils.sign`, not `signOrThrow`.
- [ ] The POST body is only `{object_b64, signature}` to the exact scanned URL. The phone does not POST the admission gate and does not follow a redirect onto another host.
- [ ] After a delivered POST, including HTTP 200 `decision: provisional` or a deny body that is not `bad_signature`, the phone says it was delivered and that the signature was made as the correct signatory. It tells you to look at the website page for welcome, refused, or progress. `bad_signature` and a real delivery failure (transport, non-2xx, redirect) are clear errors, not a refusal. The phone does not show `decision` or `verifier_ran`. It does not poll, and it does not run provisional, checking, or revoke.
- [ ] Verify any runs the section 14 measurement off the main thread, one run at a time, with a spinner, stage text, and Cancel. Cancel interrupts the run and releases the terrain room. It does not apply section 15. When the object decodes, it says a measurement record was written and lists Profile, Shape, Checkpoints, Waypoints, Leg rate, and Six leg. Profile says production. The other rows say match, mismatch, not read, or not defined. Leg rate not read also says the rate was not read and that this is not a verdict. An unreadable or undecodable file says the object file could not be read. A failed index fetch is a plain retry message. Allocation failure or a run that does not finish says verify did not finish. Fewer than 6 legs records Six leg as not defined. VivoBook bare metal is the timing acceptance host. Phone wall time is not that clock. On an A34-class phone the terrain build is expected to take a few seconds. That is not the VivoBook clock.
- [ ] Create is copy only. Create does not plant, gossip, call Bari, grow a seed list, or run produce.
- [ ] The centre tab is still Wallet.
- [ ] This package does not change the admission gate, the website, Cloudflare, or R2.
