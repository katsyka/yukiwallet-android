# Yuki Wallet for Android

The Android app for [Yuki Wallet](https://wallet.yukichat.lol), a non-custodial wallet for [YukiChain](https://github.com/katsyka/yukichain).

The wallet itself is a web app. This project wraps it in a native shell and adds the features a browser can't provide.

## Features

- **Non-custodial.** Keys are derived from a 12- or 24-word BIP39 phrase (SLIP-10 Ed25519). They never leave the device, and are stored encrypted with AES-GCM under a PBKDF2-derived key.
- **Biometric unlock.** Uses `BiometricPrompt` with an Android Keystore key that is invalidated when a new fingerprint is enrolled. Falls back to weak biometrics on devices that report strong biometrics as unavailable.
- **Notifications.** Alerts for incoming transfers and expiring `.yuki` names. A foreground service listens to the chain's event stream, with a `JobService` fallback every 15 minutes.
- **YUKI Connect.** `wallet.yukichat.lol/connect` deep links open straight into the app (App Links verified).
- **Self-update.** Checks a version manifest and installs new releases in place.

## Requirements

- Android 8.0+ (biometrics require Android 9+)
- Android SDK; Gradle via the included wrapper

## Building

Signing uses `keystore.properties` and a `.jks` keystore, neither of which is included in this repository. Create your own:

```properties
# keystore.properties
storeFile=../your-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Then:

```sh
./gradlew assembleRelease
```

`build_apk.sh "release notes"` builds, signs and publishes the APK together with its update manifest.

## Structure

| Path | Contents |
| --- | --- |
| `app/src/main/java/lol/yukichat/wallet/` | Activity, JavaScript bridge (`YukiNative`), biometrics (`Bio`), notification service and job |
| `app/src/main/AndroidManifest.xml` | Permissions, App Links intent filter |

Package name: `lol.yukichat.wallet`
