# Signing key for the test build

`satmap-test.jks` signs the APK published at the `android-test-build` release, so each new build installs over the
one before (Android only updates an app signed with the same key). It is a **public test key**: anyone can read it
from this repository, so anyone could sign an APK that installs over SatMap. Only install SatMap from this
repository's releases.

To sign with a private key instead, add four repository secrets (Settings → Secrets and variables → Actions):
`ANDROID_KEYSTORE_BASE64` (the .jks file, base64), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`. The Android workflow then uses them. Switching keys means uninstalling the test build once
(Android refuses an update signed with a different key), which removes its offline maps.

Store and key password: `satmap-test`; alias: `satmap`.
