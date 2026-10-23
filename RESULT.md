# Android v2 produce and verify

The sections below are the history of the ref-rust 2.0 pin. The bridge now uses the pcoc Rust SDK (tag `sdk-rust-v2.3.1`, set in `pcoc-bridge/bridge/Cargo.toml`).

## PR

https://github.com/groundinglab/primal-pcoc/pull/24

Ready for review. Not merged. Not deployed.

## Commit

`1eb73ed` Produce and verify a PCOC on the phone with the v2 reference

Branch `android/v2-produce-verify`, rebased on `origin/main` (`1665b5a`) before the work.

The configured SSH signing key (`~/.ssh/id_ed25519_groundinglab.pub`) is not on this machine, so this commit is unsigned.

## Reference pin

Vendored hike crate from [groundinglab/pcoc](https://github.com/groundinglab/pcoc) tag [`ref-rust-v2.0.4`](https://github.com/groundinglab/pcoc/tree/ref-rust-v2.0.4/reference/rust/hike). That pin is not SPEC v2.1. `pcoc-bridge/vendor/hike` is still the pre-v2.1 hike. Re-vendor it from `sdk/rust` after SPEC v2.1 merges. Until then it is not the step or the fill.

Sibling checkout: `/home/hodl315/pcoc-build/worktrees/pcoc-main-android-ref` (detached at that commit). Tag `ref-v2.0.0` is `6e62e4c` / `ed88a47`, which is behind main. Main is the pin because it includes the verify block-hash display-order fix. `pcoc-bridge/vendor/hike/tests/` was not copied. The library sources are otherwise the reference.

The vendored crate names `tip.pcoc.app` only so it can refuse that retired host. Product copy does not dial it. Live produce posts to `https://bari.groundinglab.io`. Verify reads `https://index.groundinglab.io`.

## What landed

- Create runs the reference live produce in-process through JNI (`libpcoc_bridge.so`). Stop writes no object. The object is written only when the run returns, then saved for the signed-in account.
- Verify uses the same crate's section 14 measurement. The v1.35 Java verify path is deleted.
- Sign-in was already the locked Gate §15.1 door and was left as it is.

## Tests

`./gradlew test ktlintCheck assembleDebug` — BUILD SUCCESSFUL (1166 tasks; the retry after a daemon exit completed the remaining work).

PCOC unit tests on `googleDebug`: 89 completed, 0 failed.

Rust bridge tests (`cargo test -p pcoc-bridge`): 8 passed, including a scripted conformance-small produce and a junk-byte verify. Those tests do not dial live hosts.

## Debug APKs

Not committed. Built locally.

| APK | sha256 |
| --- | --- |
| `app/build/outputs/apk/google/debug/app-google-debug.apk` | `14e156067339f1dfdca48854bab4caa72605ee883c40d0a59acab8fdc8cfe6f9` |
| `app/build/outputs/apk/aosp/debug/app-aosp-debug.apk` | `0f934d39fb27e5f8206251234aec83655bfd1b6421958ceb271f5a4b6efcdff0` |

Both contain `lib/arm64-v8a/libpcoc_bridge.so` and `lib/x86_64/libpcoc_bridge.so`.

`app/src/google/google-services.json` is a local copy of the committed AOSP placeholder so the Google flavor can configure. It is gitignored.

## Unfinished

A full live hike on a handset was not run. That is about 18 Bitcoin blocks, and this job does not touch live hosts beyond the code that will dial them when someone starts a hike. 32-bit ABIs are not in the bridge library.
