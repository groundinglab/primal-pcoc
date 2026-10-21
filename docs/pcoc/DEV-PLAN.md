# PCOC development plan — primal-pcoc

Short sequence for integrating PCOC into this fork. Tonight’s product SPEC is [FEATURES-v0.1.md](FEATURES-v0.1.md). The file and symbol map is [CUT-POINTS.md](CUT-POINTS.md). This plan adds no product code.

PCOC stays in [groundinglab/pcoc](https://github.com/groundinglab/pcoc). This repo integrates a dependency. It does not re-home the protocol, **STAGES 18**, or a signature scheme. A leg is 2,000,000 steps (SPEC v2.1 `STEPS_PER_LEG`). The 1,000,000 figure is withdrawn. Every step reads a full 16 KiB slot of a 256 MiB terrain. STAGES is 18. The tip interrupts the drive. A leg ending is not the end of observation.

## Gates

**Account doors (2026-10-05 lock)** are specified in [FEATURES-v0.1.md](FEATURES-v0.1.md). Entry is Account → PCOC, the drawer tile immediately before Settings. Enroll your PCOC, Verify any PCOC, and Request service admission work. Create a PCOC runs the reference live produce on the phone. Verify any is the section 14 measurement record, as [FEATURES-v0.1.md](FEATURES-v0.1.md) locks. It does not apply section 15 and it does not show valid or invalid. The phone POSTs `{object_b64, signature}` to the exact scanned HTTPS URL. It does not POST the admission gate, and the QR is not a bare hash. That supersedes, for these doors, the earlier settings-row phone→gate wire. The centre stays Wallet. A live object is still produced elsewhere. Create does not plant, gossip, call Bari, or run produce.

**Later** (in-app produce, screen-off tip observe, Option B admit) stay behind their own asks. Verify any is the Java verifier in [FEATURES-v0.1.md](FEATURES-v0.1.md). In-app produce still waits on the Bari GO and on a published produce artifact. A leg and a leg result in sample objects belong to those later phases. Live profile only. TEST stays paused: no test-profile flag in the app.

The phone door signs the 32 hash bytes with `CryptoUtils.sign`, as locked in [FEATURES-v0.1.md](FEATURES-v0.1.md). The construction is SPEC v2.1 in groundinglab/pcoc. The earlier “POST the gate from the phone” sentence is superseded for these doors.

## Ordered phases

Smallest diff first. Each phase is one reviewable change. A phase that needs an answer in the ask list stays unstarted.

### 0. Planning docs (this change)

Add `docs/pcoc/CUT-POINTS.md`, `docs/pcoc/DEV-PLAN.md`, `docs/pcoc/FEATURES-v0.1.md`, and a README pointer. Leave `LICENSE` and `NOTICE.md` untouched. No Kotlin, Gradle, or resource edits.

### 1. Tonight — producer nsec login

Use the existing path in [FEATURES-v0.1.md](FEATURES-v0.1.md): bech32 nsec → `LoginHandler` / `CredentialsStore.saveNsec` → `ActiveAccountStore.activeUserId()` is the hex pubkey of that nsec. Login can succeed before the object is compared. No new key store. No `InternalSigner`.

### 2. Enroll your PCOC

On Account → PCOC: file picker only. Store the picked file bytes for the signed-in account. No local key check. Do not call `hike::yielded_public_key` or `object_public_key`. Do not match the file to the account. Do not add a JNI reader. Clear deletes stored object bytes only. The admission site decides.

Build gate: `./gradlew :app:assembleAospDebug` (see the baseline notes in [CUT-POINTS.md](CUT-POINTS.md)).

### 3. Request service admission

On Account → PCOC: scan one HTTPS URL. Query `h` is 64 hex (highest stored header hash, Bitcoin display order), not a bare hash and not the gossip tip. Explicit Sign and submit. BIP-340-sign those 32 bytes with `CryptoUtils.sign` and the producer nsec. POST `{object_b64, signature}` to the exact scanned URL. Do not POST `https://admission.pcoc.app/v1/sign-in`. Do not follow a redirect to another host. Show accept or deny only from `decision`, and whether the check ran when `verifier_ran` is present. Timeout, empty, or garbage is a failed request, not a denial. No poll. No Option B. The centre stays Wallet.

### 4. Later — Groundinglab badge

Optional. Not required for the tonight demo.

Touch `R.string.app_name` and `SettingsHomeScreen.VersionListItem` only if the ask list confirms that pair. Leave `applicationId` (`net.primal.android`), `Badges`, and the premium drawer badge alone.

### 5. Later — centre control

Blocked. The centre stays `PrimalTopLevelDestination.Wallet` and `WalletDashboardContent`. Do not retarget `PrimalNavigationBar`, `MainScreen.onTabChanged`, or `onActiveDestinationClick`.

Produce-on-centre and admit-on-centre are Ask Christian only. This phase does not add a produce state or an admit state to the centre item.

### 6. Later — in-app produce, once a real artifact exists

Depends on a published Android-callable API from `groundinglab/pcoc` whose symbols are written down (ask list). This phase adds that dependency only. It does not place the call on Wallet, and it does not retarget the centre.

Finishing produce associates the object. Do not add a register function. Do not add a JNI or UniFFI layer in this repo to invent the call. `STAGES 18` remains inside pcoc. The UI shows the sample object’s leg and leg result. A leg is 2,000,000 steps (SPEC v2.1 `STEPS_PER_LEG`). The 1,000,000 figure is withdrawn.

If the artifact is not published, or the call site is still unnamed, this phase does not start. A stub that returns a made-up object is out of scope.

### 7. Later — local verify

The Account door **Verify any PCOC** runs the Java port of section 14 and shows the measurement record: Profile, Shape, Checkpoints, Waypoints, Leg rate, and Six leg. It does not apply section 15 and it does not show valid or invalid. VivoBook bare metal is the timing host. See [FEATURES-v0.1.md](FEATURES-v0.1.md).

The door reads the live index. It does not keep a header archive and it does not GET a tip. The Option B admit gate does not own a second verifier and does not call verify.

`NostrNotary.verifySignature` and `Nip05VerificationService` stay on their current screens. They are not the reference verifier. The tip is a public sequencer tick. The app does not keep a header archive.

### 8. Later — screen-off tip observe

Blocked on the tip client (ask list). Not tonight. `PrimalNwcService` is a pattern only: `start` → `startForegroundService` → `onStartCommand` → `startForeground`, with `foregroundServiceType="specialUse"`. That lifecycle is NWC’s. It is not the hike.

Do not invent a service whose start or stop follows produce, a leg, or a leg result. Do not assign start or stop to a leg. A leg is 2,000,000 steps (SPEC v2.1 `STEPS_PER_LEG`). The 1,000,000 figure is withdrawn. STAGES is 18. The tip interrupts the drive. A leg ending is not the end of observation.

No header client is added in this phase. The app does not keep a header archive. Screen-off work on an `aosp` build does not use `PrimalFirebaseMessagingService`. `MempoolApiService` stays the wallet deposit client. `libs.bitcoinj.core` being on the classpath is not an observe path.

### 9. Later — Option B admit, after a Christian GO

Not this door. `/v1/challenge` and `/v1/admit` stay lab-only until that GO. Request service admission POSTs `{object_b64, signature}` to the scanned HTTPS URL, as locked in [FEATURES-v0.1.md](FEATURES-v0.1.md). The phone does not POST the gate.

The lab surface is `DeveloperToolsScreen` (`developer_tools`), or it stays unnamed. The centre stays Wallet. This phase does not switch the centre to admit. Admit-on-centre remains Ask Christian only.

Option B withhold matches `ActiveAccountStore.activeUserId()` to the object’s associated pubkey and does not call the reference verifier. This phase starts only after the Option B wire is specified. It does not define bytes, and it does not define a signature. Nostr event signing stays on `NostrNotary.signNostrEvent` and `signOrThrow`. Request service admission uses `CryptoUtils.sign` on the 32 bytes of query `h`. That call is not an Option B payload and not a Nostr event.

## Ask Christian

Account door UI home is closed in [FEATURES-v0.1.md](FEATURES-v0.1.md): Account → PCOC, the tile immediately before Settings. That supersedes the earlier Groundinglab settings row for these doors. Enroll stores the picked file and does no local key check. Do not retarget Wallet.

Later, still stop rather than invent:

1. **Option B wire and signature.** What are Option B’s bytes, and is there a signature at all? Phase 9 waits. Tonight’s sign-in body stays the two fields in the SPEC.
2. **Produce artifact.** The Android-callable surface is the JNI bridge in `pcoc-bridge/`, linked to the vendored hike crate.
3. **Produce association record.** When in-app produce finishes, which field is the object id and which field is the associated pubkey? Phase 9 waits on this. Naming it does not move admit onto the centre. Tonight’s enroll stores the picked file. It does not use `hike::yielded_public_key` or `object_public_key`.
4. **Tip client.** Which client reads the public tip? Phase 8 waits. Do not bind start or stop to a leg. The app does not keep a header archive. The same answer blocks phase 7. mempool.space transaction lookup is the wallet path.
5. **Centre versus Wallet, including produce-on-centre and admit-on-centre.** Wallet stays the centre item (`WalletDashboardContent`). Do not retarget it. Phases 5 and 6 wait. Tonight’s UI is the settings ask in the SPEC, not this one.
6. **Badge scope.** Launcher label (`app_name`), settings version row (`VersionListItem`), or both? Does `applicationId` stay `net.primal.android`? Phase 4 waits. Not a tonight blocker.
7. **Sample object shape for later UI.** Where do sample objects live, and which fields are the leg and the leg result? The app does not copy STAGES 18. A live CBOR file is enough for phases 1–3.
8. **Issue #87 parity.** The earlier cut-point locks came from the task brief because issue #87 returned 404 here. Confirm they still match for the later phases. Live profile only. TEST paused.

## Working agreement

- One phase per pull request, after phase 0.
- Baseline build for every code phase: `./gradlew :app:assembleAospDebug`.
- Preserve the MIT `LICENSE` and `NOTICE.md`.
- Keep upstream Primal behavior working on the paths a phase does not touch. Wallet, Nostr signing, and NWC stay in place until a phase says otherwise.
- If an answer in the ask list is still open, stop. A smaller diff that avoids the open question is the next step. A guessed wire is not.
