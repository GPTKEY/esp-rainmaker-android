# 01 — Android Assisted Claim diagnostic APK

Date: 2026-08-02

## Background

The ESP32-S3 firmware reaches all of the following states:

- BLE discovery and GATT connection succeed.
- Security 2 establishes a secured session.
- The device reports `Assisted Claiming Started`.
- The device reports `RainMaker Claim Started`.

The phone UI then remains on the certificate-acquisition screen, while the device does not receive the Claim Init response required to begin CSR generation.

## Goal

Create an installable diagnostic APK without requiring a local Android Studio installation. The APK must make the exact Assisted Claim stage visible and must stop waiting forever when the Claim cloud request never completes.

## Non-goals

- Do not change the RainMaker BLE protocol.
- Do not change Security 2, username, PoP, salt, or verifier handling.
- Do not bypass certificate validation.
- Do not print access tokens, PoP values, private keys, CSRs, certificates, or full Claim responses.
- Do not introduce release signing in this diagnostic work package.

## Changes

### Cloud build

`.github/workflows/01_android_diagnostic_apk.yml` builds `:app:assembleDebug` on GitHub Actions with:

- Ubuntu runner
- JDK 17
- Android SDK 35
- Security 2
- `wifiprov` username
- Global RainMaker region

The workflow uploads the APK, SHA-256 checksum, and build metadata as the artifact:

`esp-rainmaker-diagnostic-debug-apk`

### Diagnostic patch

`scripts/patch_claim_diagnostics.py` applies a checked, deterministic source patch during the cloud build. It fails if any expected source anchor is missing or duplicated.

The diagnostic APK adds:

- duplicate Claim Start suppression;
- explicit Claim stage markers prefixed with `[CLAIM-DIAG]`;
- Claim base URL and non-sensitive payload metadata logging;
- null/empty Claim response detection;
- exception class and message reporting;
- a 30-second timeout for the cloud `initiateClaim` request;
- an on-screen stage/error message instead of an indefinite spinner.

## Build output

Open the repository's **Actions** page, select **Android Diagnostic APK**, open the latest successful run, and download:

`esp-rainmaker-diagnostic-debug-apk`

The ZIP contains:

- the debug APK;
- `SHA256SUMS.txt`;
- `build-info.txt`.

## Installation note

The diagnostic APK uses the same package name as the official ESP RainMaker application but is signed with a different debug key. Android will not install it over the store version. Uninstall the official application first, or use a separate test phone.

## Test procedure

1. Install the diagnostic APK.
2. Sign in using the same RainMaker account.
3. Close other BLE scanner/debug applications.
4. Reset the ESP32-S3 and scan the newly printed QR code once.
5. Wait for the Claim screen to complete or report an explicit stage.
6. Capture logcat lines containing `CLAIM-DIAG`, `ClaimingActivity`, and `ApiManager`.
7. Capture the ESP32 serial output from `Secured session established!` onward.

## Expected diagnostic outcomes

- `cloud-initiate-timeout`: the App did not receive any callback from the Claim request within 30 seconds.
- `cloud-network-failed`: DNS, TLS, socket, or connectivity failure.
- `cloud-response-failed`: the Claim service returned an application/HTTP error.
- `cloud-response-null` or `cloud-response-empty`: the request callback succeeded but returned unusable data.
- `initiateClaim succeeded`: the problem is later, during Claim Init/CSR/certificate transfer.

## Rollback

Delete the workflow and patch script, or close the diagnostic branch. The upstream `ClaimingActivity.java` stored in the repository is not directly modified by this work package.
