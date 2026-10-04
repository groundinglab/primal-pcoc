# PCOC development plan — primal-pcoc

Short sequence for integrating PCOC into this fork. Read [CUT-POINTS.md](CUT-POINTS.md) for the file and symbol map. This plan adds no product code.

PCOC stays in [groundinglab/pcoc](https://github.com/groundinglab/pcoc). This repo integrates a dependency. It does not re-home the protocol, **STAGES 18**, or a signature scheme.

## Gates

No phase below phase 0 starts until both are true:

1. Bari waypoint-anchoring **GO** (first-ship intent on pcoc issue #87 is parked until then).
2. Sample objects exist, including a **leg** and a **leg result** this UI can render.

Until those gates, the only change in this repo is planning docs. Assume the **live** profile. Leave TEST paused: no test-profile flag in the app.

Issue #87 was not readable here (HTTP 404). If the issue contradicts a lock in [CUT-POINTS.md](CUT-POINTS.md), the issue wins and this plan is updated before code.

## Ordered phases

Smallest diff first. Each phase is one reviewable change. A phase that needs an answer in the ask list stays unstarted.

### 0. Planning docs (this change)

Add `docs/pcoc/CUT-POINTS.md`, `docs/pcoc/DEV-PLAN.md`, and a README pointer. Leave `LICENSE` and `NOTICE.md` untouched. No Kotlin, Gradle, or resource edits.

### 1. Groundinglab badge

After the gates: a visible mark that this build is the Groundinglab fork.

Touch `R.string.app_name` and `SettingsHomeScreen.VersionListItem` only if the ask list confirms that pair. Leave `applicationId` (`net.primal.android`), `Badges`, and the premium drawer badge alone.

Build gate: `./gradlew :app:assembleAospDebug` (see the baseline notes in [CUT-POINTS.md](CUT-POINTS.md)).

### 2. Read-only key gate

A single helper that answers, for the active account:

- hex pubkey from `ActiveAccountStore.activeUserId()`
- whether `CredentialsStore.findOrThrow` yields `CredentialType.PrivateKey` with a non-null `nsec`

No new storage. No call to `getOrCreateInternalSignerCredentials`. No produce, no admit, no signature. Callers later use this helper to withhold admit on a foreign key, an external signer, or an npub-only login.

### 3. Centre control shell

Empty centre control, still with no protocol call.

The click target is the centre item of `PrimalNavigationBar` (`PrimalTopLevelDestination.Wallet` today), through `MainScreen.onTabChanged` / `onActiveDestinationClick`. Do this phase only after Christian confirms that slot (ask list). Until then, do not retarget Wallet.

Empty state: the control’s action is “start produce”, and the action shows a blocked state because the produce artifact is not linked yet. It must not navigate into a fake produce flow and must not call `CryptoUtils`, `NostrNotary`, or the wallet.

### 4. In-app produce, once a real artifact exists

Depends on a published Android-callable API from `groundinglab/pcoc` whose symbols are written down (ask list). This phase adds that dependency and calls it from the empty centre control.

Finishing produce associates the object. Do not add a register function. Do not add a JNI or UniFFI layer in this repo to invent the call. `STAGES 18` remains inside pcoc. The UI shows the sample object’s leg and leg result.

If the artifact is not published, this phase does not start. A stub that returns a made-up object is out of scope.

### 5. Local verify

A new `NavHost` destination, opened from the associated object, calls the reference verifier. The screen does not own a verifier. An admission gate does not own a second verifier.

The call uses the sample object and the current public tip, the sequencer tick. The tip is not an archive the app stores. No verify request on `SocketModule`, `NetworkingModule`, or `MempoolApiService`.

`NostrNotary.verifySignature` and `Nip05VerificationService` stay on their current screens. They are not the reference verifier.

### 6. Screen-off tip observe

A foreground service patterned on `PrimalNwcService.start` / `onStartCommand` / `startForeground`, `foregroundServiceType="specialUse"`, started when the leg begins and stopped when the leg result is in. For the open leg it keeps observing the public tip with the screen off on an `aosp` build, so it does not use `PrimalFirebaseMessagingService`. It does not store a header archive.

The tip client is whatever Christian names (ask list). `MempoolApiService` stays the wallet deposit client. `libs.bitcoinj.core` being on the classpath is not an observe path.

### 7. Admit, lab-only, after the wire exists

Last. Option B. Lab-only until a later GO that explicitly ships admit.

Surface: `DeveloperToolsScreen` (`developer_tools`), not the centre button, until that later GO. The gate shows admit only when `ActiveAccountStore.activeUserId()` matches the object’s associated pubkey, and withholds admit on a mismatch. That match is the whole gate. Phase 7 does not call the reference verifier.

This phase starts only after the admit wire is specified. It does not define bytes, and it does not define a signature. `NostrNotary.signNostrEvent` and `CryptoUtils.sign` remain Nostr event signing.

## Ask Christian

Stop on these. Do not invent an answer in code or in a follow-up design doc.

1. **Admit wire and signature.** What are Option B’s bytes, and is there a signature at all? No scheme will be proposed from this repo. Phase 7 waits.
2. **Produce artifact.** Which Android-callable coordinates and symbols does `groundinglab/pcoc` expose (Maven/AAR, package, function names)? This tree has no Rust bridge and will not grow one to fill the gap. Phase 4 waits.
3. **Association record.** When produce finishes, which field is the object id and which field is the associated pubkey? Foreign-key withhold compares that pubkey to `ActiveAccountStore.activeUserId()`. Phase 3’s admit label and phase 7 wait on this.
4. **Tip client.** Which client reads the public tip (the sequencer tick) for the open leg while the screen is off? Phase 6 waits. The app does not keep a header archive. mempool.space transaction lookup is the wallet path and is not the proposal.
5. **Centre control versus Wallet.** `PrimalTopLevelDestination` order is Feeds, Reads, Wallet, Alerts, Explore. Wallet is the centre item and currently opens `WalletDashboardContent`. Does PCOC replace that item, or is the centre control a new destination? Phase 3 waits.
6. **Badge scope.** Is the Groundinglab mark the launcher label (`app_name`), the settings version row (`VersionListItem`), or both? Does `applicationId` stay `net.primal.android`? Phase 1 waits.
7. **Sample object shape.** Where do the sample objects live, and which fields are the leg and the leg result the UI must show? Confirm the app does not copy STAGES 18.
8. **Issue #87 parity.** The locks in [CUT-POINTS.md](CUT-POINTS.md) came from the task brief because issue #87 returned 404 here. Confirm they match the parked issue, including live-profile only and TEST paused.

## Working agreement

- One phase per pull request, after phase 0.
- Baseline build for every code phase: `./gradlew :app:assembleAospDebug`.
- Preserve the MIT `LICENSE` and `NOTICE.md`.
- Keep upstream Primal behavior working on the paths a phase does not touch. Wallet, Nostr signing, and NWC stay in place until a phase says otherwise.
- If an answer in the ask list is still open, stop. A smaller diff that avoids the open question is the next step. A guessed wire is not.
