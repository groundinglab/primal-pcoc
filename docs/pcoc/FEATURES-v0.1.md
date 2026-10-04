# PCOC features v0.1 — tonight MVP

Product SPEC for a Techie and QA pass. Cut points stay in [CUT-POINTS.md](CUT-POINTS.md). Phase order stays in [DEV-PLAN.md](DEV-PLAN.md).

Terms, unchanged: **leg**, **leg result**, **STAGES 18**. A leg is 1,000,000 steps. The tip interrupts the drive. Live profile only. TEST is paused.

Tonight is **import + live sign-in**. It is not produce on the phone, and it is not a local verify screen.

## 1. Purpose

A PCOC-backed Nostr account completes live Groundinglab sign-in on this phone. The producer nsec is already a normal Primal login. A live PCOC CBOR produced elsewhere (pepebook / VivoBook produce) is imported and associated to that account. Bari is the plant helper. Bari does not produce the hike CBOR. The app scans the website tip-hash QR, signs that hash with the producer nsec, posts one sign-in request to the admission gate, and shows the gate’s accept or deny. Demo success is that decision on device for an object whose `public_key` is the active account.

## 2. Golden path (tonight)

Import and Sign-in are a named Groundinglab row on `SettingsHomeScreen` (`PrimalSettingsSection`), always visible. The centre stays Wallet. They are not `DeveloperToolsScreen` and not the three-tap version unlock.

1. **Login.** Enter the producer **bech32 nsec** on the existing Primal login screen. Login can succeed before any object is imported. **Pass:** `LoginViewModel` accepts it as `CredentialType.PrivateKey`, `LoginHandler.login` stores it with `CredentialsStore.saveNsec`, and `ActiveAccountStore.activeUserId()` is the hex pubkey of that nsec. **Fail:** npub-only login, external signer, or the `InternalSigner` from `getOrCreateInternalSignerCredentials` is treated as the producer key. The compare to the object `public_key` is step 3, not a login fail.

2. **Import.** Choose the live CBOR with the Android file picker only. **Pass:** the chosen file is held for associate. **Fail:** any path other than the file picker (share sheet, deep link). **Fail:** a separate register/create step. `hike::yielded_public_key` is the associate pubkey read, and it is the stop when it returns no 32-byte key: that file does not proceed to sign or post. A 32-byte return is the key for that compare. It is not a hike-shape check, and it is not proof the file is a hike object. On pcoc main that reader can return a 32-byte key for a CBOR map that is not a hike when `public_key` is 32 bytes. A non-hike file does not fail import solely because of `hike::yielded_public_key`. Shape stays at the admission door. Do not hand-roll a second shape verifier on the phone.

3. **Associate.** Bind that file to the active account. The sign-in read is `hike::yielded_public_key` in `groundinglab/pcoc` `reference/hike/src/object.rs`. It returns the 32-byte `public_key` when the first CBOR item has that field. That return is the compare input. It is not a hike-shape gate. `object_public_key` is the shape-true reader and is not this read. **Pass:** the hex of those 32 bytes equals `ActiveAccountStore.activeUserId()`. Hex encoding is not a CBOR decoder. Language is associate / import. **Pass on a mismatch test:** associate ran, the hex values differ, and the UI shows an explicit withhold (associated pubkey mismatch / cannot sign-in), distinct from a run that never attempted associate or sign. **Fail:** the hex values differ and the UI does not show that withhold. **Fail:** a run that never reached associate is scored as a pass for the mismatch. **Fail:** no 32-byte key, a hand-rolled CBOR layout, `object_public_key` used as the sign-in read, or a register call. How Android calls `hike::yielded_public_key` is a dependency binding. The reader name is locked. The public_key checks are not waiting on a decoder name.

4. **Scan the tip-hash QR.** The website QR encodes the highest stored tip `header.hash`: 64 hex characters, Bitcoin display order. Height may be shown beside the QR. **Pass:** the scan yields that 64-hex hash. **Fail:** height is treated as the signed message or as a request field.

5. **Sign.** BIP-340 over the display-order hash **bytes**, with the producer nsec. **Pass:** BIP-340 verify of that signature succeeds under the object’s `public_key`, which is the hex of the 32 bytes from `hike::yielded_public_key`. **Fail:** that verify is false. A signature that is only 128 hex characters is not a pass. **Fail:** the app signs a Nostr event (`NostrNotary.signNostrEvent` / `NostrUnsignedEvent.signOrThrow`) instead of those hash bytes. **Fail:** the signed message is anything other than that 32-byte hash.

6. **Submit.** One JSON POST, fields fixed in [Wire lock](#4-wire-lock). **Pass:** a POST was sent and the body is only `object_b64` and `signature`. **Fail:** no request was sent. That is fail-as-not-submitted. Do not pass this step when no POST was sent. **Fail:** the QR hash, an `id`, a `challenge_id`, or any Option B field is included.

7. **Show the decision.** **Pass:** the screen shows `accept` or `deny` taken from `decision`. **Fail:** timeout, an empty body, or a body with no `decision`. **Fail:** the phone runs a second verifier, or invents a website↔phone session sync. The website `sessionStorage` session on the sign-in page can follow later.

## 3. Feature table

| Feature | Status | Touch points | Inputs / outputs | Pass / fail | Ask / blocked |
| --- | --- | --- | --- | --- | --- |
| Login / nsec | Tonight | `LoginViewModel` (`isValidNostrPrivateKey` → `CredentialType.PrivateKey`); `LoginHandler.login` → `CredentialsStore.saveNsec`; `AuthRepository.loginWithNsec` → `ActiveAccountStore.setActiveUserId`. See [CUT-POINTS.md](CUT-POINTS.md) keys. | In: bech32 nsec. Out: hex `activeUserId` of that nsec. | Pass: stored `PrivateKey` and login succeeds. Fail: `PublicKey`, `ExternalSigner`, or `InternalSigner`. A mismatch with the object is not a login fail. | None for the login path. It already exists. |
| Import PCOC (file) | Tonight | No CBOR import symbol in this tree. Existing pickers are photos (`ActivityResultContracts.PickVisualMedia`) and export (`CreateDocument` on `WalletSettingsScreen`). Those are not this import. After the pick, `hike::yielded_public_key` is the associate pubkey read and the stop when it returns no 32-byte key. | In: file from the Android file picker. Out: file bytes held for associate and for `object_b64`, or a stopped import when that reader returns no 32-byte key. | Pass: the picker file is held for associate. A 32-byte return from `hike::yielded_public_key` is the associate pubkey. It is not an “is a hike” pass. Fail: share sheet, deep link, or a register step. When the reader returns no 32-byte key, stop and do not sign or post. That stop is the missing key. A non-hike file does not fail import solely because of `hike::yielded_public_key`. Shape stays at the admission door. Do not hand-roll a second shape verifier on the phone. | UI home is locked: named Groundinglab row on `SettingsHomeScreen` (`PrimalSettingsSection`), always visible. Not `DeveloperToolsScreen`. Centre stays Wallet. |
| Associate to active pubkey | Tonight | Sign-in read: `hike::yielded_public_key` (`reference/hike/src/object.rs`). Returns the 32-byte `public_key` when the first CBOR item has that field. `object_public_key` is not this read. Compare `ActiveAccountStore.activeUserId()` to the hex of those 32 bytes. `CredentialsStore.findOrThrow` loads the nsec for the later sign. | In: imported file + active hex pubkey. Out: association, or an explicit withhold. | Pass: hex values match. Mismatch pass: associate ran and the UI shows an explicit withhold (associated pubkey mismatch / cannot sign-in), not “never attempted.” Fail: silent mismatch, a run that never reached associate scored as a withhold pass, no 32-byte key, `object_public_key` as the sign-in read, a hand-rolled CBOR layout, or a register/create call. | Reader name is locked. Not blocked on a decoder Ask. UI home is the Groundinglab settings row (section 6). |
| Scan tip-hash QR | Tonight | Camera scan exists as `ScanCodeScreen` / `ScanCodeContract` (`ScanMode.Anything`, `ScanMode.RemoteLogin`) via `navigateToScanCode`. It does not decode a tip hash. | In: website QR. Out: 64-hex `header.hash`, Bitcoin display order. | Pass: that hash only. Fail: height captured as an output. | Same Groundinglab settings row. Do not retarget Wallet. Not Developer Tools. |
| Sign tip hash (BIP-340) | Tonight | `CryptoUtils.sign` (`secp256k1.signSchnorr`) on the 32-byte tip-hash. Nostr event signing stays `NostrNotary` / `signOrThrow` and is not this signature. | In: 32 display-order hash bytes + producer nsec. Out: signature checked under the hex of `hike::yielded_public_key`. | Pass: BIP-340 verify succeeds under that `public_key`. Fail: verify is false. 128 hex characters alone is not pass. Fail: a Nostr event signature, or a signature by `InternalSigner`. | Signed message is the hash bytes from the lock. |
| Submit sign-in | Tonight | No admission-gate client in this tree. Do not use `SocketModule`, `MempoolApiService`, or `NetworkingModule`’s `https://primal.net` Retrofit as the gate. | In: standard base64 of the CBOR, and the signature that passed BIP-340 verify. Out: HTTP response from the URL in the wire lock, or no request. | Pass: a POST was sent and the body is only `object_b64` and `signature`. Height and the QR hash are absent from that body. Fail: Option B paths, or those checks marked pass when no POST was sent. If submit did not happen, height/hash absence is N/A or fail-as-not-submitted. | None on the wire. The two fields match the live READMEs named in section 4. |
| Show decision | Tonight | No decision screen in this tree. | In: gate body. Out: `accept` or `deny` from `decision`, or a failed call. | Pass: the screen shows `accept` or `deny` from `decision`. Fail: timeout, empty body, or a body with no `decision`. Fail: a local verify screen, or a new phone↔website sync. | Same Groundinglab settings row (section 6). |

## 4. Wire lock

Source of truth: the Sign-in sections of `groundinglab/pcoc` `services/admission-gate/README.md` and `apps/www/sign-in/README.md` on `main`. Techie confirmed those READMEs are readable. The POST fields below match those sections. This SPEC adds no request fields.

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

The sign-in read is locked: `hike::yielded_public_key` in `groundinglab/pcoc` `reference/hike/src/object.rs`. It returns the 32-byte `public_key` when the first CBOR item has that field. `object_public_key` is not the sign-in read. Section 7 scores `public_key` with that reader. Those boxes are not blocked on a decoder Ask. This repo does not hand-roll a CBOR layout. How Android calls that Rust function is an implementation binding. It does not leave the reader unnamed.

1. **Where Import and Sign-in live.** Closed (2026-10-04). A named Groundinglab row on `SettingsHomeScreen` (`PrimalSettingsSection`), always visible. The centre stays Wallet. Not `DeveloperToolsScreen`. Not the three-tap version unlock (`SettingsHomeViewModel.handleVersionTapped`).

No open tonight Ask remains on UI home. Badge choice is not a tonight blocker.

## 7. Acceptance for tonight demo

This checklist is writable now. UI home is locked (section 6): the always-visible Groundinglab row on `SettingsHomeScreen`. Bari GO is not required. `public_key` lines use `hike::yielded_public_key` and are not blocked on a decoder Ask.

- [ ] Import and Sign-in open from a named Groundinglab row on `SettingsHomeScreen` (`PrimalSettingsSection`). The row is always visible. It is not `DeveloperToolsScreen` and not the three-tap version unlock. The centre tab stays Wallet.

- [ ] Log in with the producer bech32 nsec succeeds. `ActiveAccountStore.activeUserId()` is the hex pubkey of that nsec. Login success does not require a match to the object.
- [ ] npub-only login and internal-signer login were attempted in this run. **Pass** only when those attempts were made, they are not treated as the producer login, and they do not post. If either attempt was not made, this box is fail or N/A. A run that never tried them does not pass.
- [ ] Import uses the Android file picker only. The file was produced elsewhere (pepebook / VivoBook). Bari does not produce that file. `hike::yielded_public_key` is the associate pubkey compare and the stop when it returns no 32-byte key: do not sign or post. A 32-byte return is not an “is a hike” pass. A non-hike file does not fail import solely because of that reader. Shape stays at the admission door. Do not hand-roll a second shape verifier on the phone.
- [ ] Association binds that file to the active account. There is no register step. The sign-in read is `hike::yielded_public_key`, not `object_public_key`.
- [ ] Mismatch withhold, scored only after associate ran: the hex of the 32 yielded bytes differs from `activeUserId()`, and the UI shows an explicit withhold (associated pubkey mismatch / cannot sign-in), distinct from never attempted. A run that never reached associate or sign does not pass this box.
- [ ] Scan the sign-in page QR. The value used for signing is the 64-hex `header.hash` in Bitcoin display order.
- [ ] Sign pass is BIP-340 verify under the object `public_key` (hex of the `hike::yielded_public_key` bytes). **Fail** when that verify is false. 128 hex characters alone is not a pass.
- [ ] Height and the QR hash are absent from the POST body only if a POST was sent. If submit did not happen, mark N/A or fail-as-not-submitted. Do not pass this box because there was no request.
- [ ] A POST was sent to `https://admission.pcoc.app/v1/sign-in`, and the body is only `object_b64` (standard base64 of the file) and `signature`. If no request was sent, this box is fail-as-not-submitted. Do not pass it because there was no request.
- [ ] **Pass** only when the screen shows `accept` or `deny` from `decision`. Timeout, an empty body, or a body with no `decision` is a **fail**.
- [ ] The centre tab is still Wallet.
- [ ] No produce, no local verify screen, no Option B call, no TEST profile switch.
