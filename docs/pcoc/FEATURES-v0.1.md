# PCOC features v0.1 — tonight MVP

Product SPEC for a Techie and QA pass. Cut points stay in [CUT-POINTS.md](CUT-POINTS.md). Phase order stays in [DEV-PLAN.md](DEV-PLAN.md).

Terms, unchanged: **leg**, **leg result**, **STAGES 18**. A leg is 1,000,000 steps. The tip interrupts the drive. Live profile only. TEST is paused.

Tonight is **import + live sign-in**. It is not produce on the phone, and it is not a local verify screen.

## 1. Purpose

A PCOC-backed Nostr account completes live Groundinglab sign-in on this phone. The producer nsec is already a normal Primal login. A live PCOC CBOR produced elsewhere (pepebook / VivoBook produce) is imported and associated to that account. Bari is the plant helper. Bari does not produce the hike CBOR. The app scans the website tip-hash QR, signs that hash with the producer nsec, posts one sign-in request to the admission gate, and shows the gate’s accept or deny. Demo success is that decision on device for an object whose `public_key` is the active account.

## 2. Golden path (tonight)

1. **Login.** Enter the producer **bech32 nsec** on the existing Primal login screen. Login can succeed before any object is imported. **Pass:** `LoginViewModel` accepts it as `CredentialType.PrivateKey`, `LoginHandler.login` stores it with `CredentialsStore.saveNsec`, and `ActiveAccountStore.activeUserId()` is the hex pubkey of that nsec. **Fail:** npub-only login, external signer, or the `InternalSigner` from `getOrCreateInternalSignerCredentials` is treated as the producer key. The compare to the object `public_key` is step 3, not a login fail.

2. **Import.** Choose the live CBOR with the Android file picker only. **Pass:** the chosen file bytes are the object that will be sent as `object_b64`. **Fail:** any path other than the file picker (share sheet, deep link). **Fail:** a separate register/create step.

3. **Associate.** Bind that file to the active account. The sign-in read is `hike::yielded_public_key` in `groundinglab/pcoc` `reference/hike/src/object.rs`. It returns the 32-byte `public_key` when the first CBOR item has that field. `object_public_key` is the shape-true reader and is not this read. **Pass:** the hex of those 32 bytes equals `ActiveAccountStore.activeUserId()`. Hex encoding is not a CBOR decoder. Language is associate / import. **Fail:** the hex values differ. The app does not sign and does not post. **Fail:** a hand-rolled CBOR layout in this repo, or `object_public_key` used as the sign-in read. **Fail:** the bind is a register call. How Android calls `hike::yielded_public_key` is a dependency binding. The reader name is locked.

4. **Scan the tip-hash QR.** The website QR encodes the highest stored tip `header.hash`: 64 hex characters, Bitcoin display order. Height may be shown beside the QR. **Pass:** the scan yields that 64-hex hash. **Fail:** height is treated as the signed message or as a request field.

5. **Sign.** BIP-340 over the display-order hash **bytes**, with the producer nsec, so the signature verifies under the object’s `public_key`. **Pass:** signature is 128 hex characters. **Fail:** the app signs a Nostr event (`NostrNotary.signNostrEvent` / `NostrUnsignedEvent.signOrThrow`) instead of those hash bytes. **Fail:** the signed message is anything other than that 32-byte hash.

6. **Submit.** One JSON POST, fields fixed in [Wire lock](#4-wire-lock). **Pass:** the body is only `object_b64` and `signature`. **Fail:** the QR hash, an `id`, a `challenge_id`, or any Option B field is included.

7. **Show the decision.** **Pass:** the screen shows the gate `decision`: `accept` or `deny`. **Fail:** the phone runs a second verifier, or invents a website↔phone session sync. The website `sessionStorage` session on the sign-in page can follow later.

## 3. Feature table

| Feature | Status | Touch points | Inputs / outputs | Pass / fail | Ask / blocked |
| --- | --- | --- | --- | --- | --- |
| Login / nsec | Tonight | `LoginViewModel` (`isValidNostrPrivateKey` → `CredentialType.PrivateKey`); `LoginHandler.login` → `CredentialsStore.saveNsec`; `AuthRepository.loginWithNsec` → `ActiveAccountStore.setActiveUserId`. See [CUT-POINTS.md](CUT-POINTS.md) keys. | In: bech32 nsec. Out: hex `activeUserId` of that nsec. | Pass: stored `PrivateKey` and login succeeds. Fail: `PublicKey`, `ExternalSigner`, or `InternalSigner`. A mismatch with the object is not a login fail. | None for the login path. It already exists. |
| Import PCOC (file) | Tonight | No CBOR import symbol in this tree. Existing pickers are photos (`ActivityResultContracts.PickVisualMedia`) and export (`CreateDocument` on `WalletSettingsScreen`). Those are not this import. | In: live CBOR file from the Android file picker. Out: file bytes held for associate and for `object_b64`. | Pass: one file chosen in the picker. Fail: share sheet or deep link. | UI home is an Ask (section 6). |
| Associate to active pubkey | Tonight | Sign-in read: `hike::yielded_public_key` (`reference/hike/src/object.rs`). Returns the 32-byte `public_key` when the first CBOR item has that field. `object_public_key` is not this read. Compare `ActiveAccountStore.activeUserId()` to the hex of those 32 bytes. `CredentialsStore.findOrThrow` loads the nsec for the later sign. | In: imported file + active hex pubkey. Out: association for that account, or a withheld sign-in. | Pass: hex values match. Fail: mismatch, `object_public_key` used as the sign-in read, a hand-rolled CBOR layout, or a register/create call. | Reader name is locked. Android binding of `hike::yielded_public_key` is still open (section 6). |
| Scan tip-hash QR | Tonight | Camera scan exists as `ScanCodeScreen` / `ScanCodeContract` (`ScanMode.Anything`, `ScanMode.RemoteLogin`) via `navigateToScanCode`. It does not decode a tip hash. | In: website QR. Out: 64-hex `header.hash`, Bitcoin display order. | Pass: that hash only. Fail: height captured as an output. | UI home is the same Ask. Do not retarget Wallet. |
| Sign tip hash (BIP-340) | Tonight | `CryptoUtils.sign` (`secp256k1.signSchnorr`) on the 32-byte tip-hash. Nostr event signing stays `NostrNotary` / `signOrThrow` and is not this signature. | In: 32 display-order hash bytes + producer nsec. Out: 128 hex signature. | Pass: BIP-340 under the object `public_key`. Fail: a Nostr event signature, or a signature by `InternalSigner`. | Signed message is the hash bytes from the lock. |
| Submit sign-in | Tonight | No admission-gate client in this tree. Do not use `SocketModule`, `MempoolApiService`, or `NetworkingModule`’s `https://primal.net` Retrofit as the gate. | In: standard base64 of the CBOR, and the 128-hex signature. Out: HTTP response from the URL in the wire lock. | Pass: the JSON object in the wire lock and no other fields. Fail: Option B paths. | None on the wire. It is locked. |
| Show decision | Tonight | No decision screen in this tree. | In: gate `decision`. Out: `accept` or `deny` on screen. | Pass: the shown word is the gate value. Fail: a local verify screen, or a new phone↔website sync. | UI home is the same Ask. |

## 4. Wire lock

Named source of truth: `groundinglab/pcoc` `services/admission-gate/README.md` (Sign-in) and `apps/www/sign-in/README.md`.

Those files were not readable from this environment (repository 404). The facts in this section are the 2026-10-04 lock and nothing else. This SPEC adds no request fields and no response fields.

Website QR encodes the highest stored tip **header.hash** (64 hex, Bitcoin display order). Height may show beside the QR. Height is not sent to the gate. The phone signs those display-order hash **bytes** with BIP-340 under the object’s `public_key` (producer nsec).

The QR hash is not a request field. The body has no `id`, no `challenge_id`, and no Option B fields.

```http
POST https://admission.pcoc.app/v1/sign-in
Content-Type: application/json
```

```json
{"object_b64":"<standard base64 of CBOR>","signature":"<128 hex>"}
```

Door, from the admission-gate Sign-in README. These cases are not one always-sequential Schnorr-then-verify path. The phone still shows only `decision` (`accept` or `deny`).

- A signature that matches neither the highest stored `header.hash` nor the height-minus-one file → deny, and stop before the verifier.
- The object decodes but yields no 32-byte `public_key` → deny, with no signature check and no verifier.
- Only a match runs `hike::verify` and section 15.
- An object that will not decode is the shape case that reaches the verifier with no signature check.

Same-phone MVP: this app may call that URL itself and show `decision`. A later website `sessionStorage` session on the sign-in page is out of this SPEC. This SPEC does not define a website↔phone sync protocol.

**Option B is out of tonight.** `/v1/challenge` and `/v1/admit` stay lab-only until a Christian GO. They are not this request.

## 5. Non-goals

- In-app produce.
- Local verify UI, and any phone-owned reference verifier. `hike::verify` runs only in the door cases in the wire lock.
- Screen-off tip observe. `PrimalNwcService` stays the NWC service.
- Option B `/v1/challenge` and `/v1/admit`.
- Import by share sheet or deep link.
- Retargeting the centre `PrimalTopLevelDestination.Wallet`.
- TEST profile.
- Groundinglab badge (optional, not required for the demo).
- A leg / leg result browser. STAGES 18 stays in pcoc.

## 6. Open Asks

Only blockers for tonight:

1. **Where Import and Sign-in live.** The centre stays Wallet. Recommendation: `DeveloperToolsScreen` on route `developer_tools` (`SettingsHomeScreen` shows it after three version taps via `SettingsHomeViewModel.handleVersionTapped`, then `navigateToDeveloperTools`). Alternative: a named Groundinglab row on `SettingsHomeScreen` (`PrimalSettingsSection`). Christian picks. Until that pick, do not add the screen on the centre item.

2. **Android binding of the locked reader.** The sign-in read is `hike::yielded_public_key` in `groundinglab/pcoc` `reference/hike/src/object.rs`. It returns the 32-byte `public_key` when the first CBOR item has that field. `object_public_key` is not the sign-in read. This repo does not hand-roll a CBOR layout. Hex of those 32 bytes is what `activeUserId()` is compared to. How this Android app calls that Rust function is still open. The reader name is not.

Badge choice is not a tonight blocker.

## 7. Acceptance for tonight demo

Run on device once a live CBOR file exists. Bari GO is not required for this list.

- [ ] Log in with the producer bech32 nsec succeeds. `ActiveAccountStore.activeUserId()` is the hex pubkey of that nsec. Login success does not require a match to the object.
- [ ] An npub-only login or the internal signer is not the producer login and does not post.
- [ ] Import uses the Android file picker only. The imported bytes are a live CBOR produced elsewhere (pepebook / VivoBook). Bari does not produce that file.
- [ ] Association is that file bound to the active account. There is no register step. The sign-in read is `hike::yielded_public_key`, not `object_public_key`.
- [ ] Hex of the 32 bytes from `hike::yielded_public_key` is compared to `activeUserId()`. A mismatch does not sign and does not post.
- [ ] Scan the sign-in page QR. The value used for signing is the 64-hex `header.hash` in Bitcoin display order.
- [ ] Height shown beside the QR is not in the POST body. The hash is not in the POST body.
- [ ] POST `https://admission.pcoc.app/v1/sign-in` sends only `object_b64` (standard base64 of the file) and `signature` (128 hex BIP-340).
- [ ] The screen shows `accept` or `deny` from `decision`.
- [ ] The centre tab is still Wallet.
- [ ] No produce, no local verify screen, no Option B call, no TEST profile switch.
