# Release signing (item R-06)

The release build is signed from CI secrets only. The
keystore is never committed, never stored in the repo, and
never printed in a log. `app/build.gradle.kts` reads four
environment variables; `.github/workflows/release-signed.yml`
supplies them from GitHub Actions secrets.

## One-time setup (owner)

1. Create the keystore on your own machine and back it up
   somewhere safe (losing it means losing the app identity):

   ```
   keytool -genkeypair -v -keystore termbridge-release.jks \
     -alias termbridge -keyalg RSA -keysize 4096 \
     -validity 10000
   ```

2. In the GitHub repo: Settings → Secrets and variables →
   Actions → New repository secret, four secrets:

   | Secret | Value |
   |---|---|
   | `TB_KEYSTORE_B64` | `base64 -w0 termbridge-release.jks` output |
   | `TB_KEYSTORE_PASSWORD` | the keystore password |
   | `TB_KEY_ALIAS` | `termbridge` (the alias chosen above) |
   | `TB_KEY_PASSWORD` | the key password |

3. Keep an offline copy of the .jks + passwords with your
   other signing keys.

## Producing a release

- Push a tag like `v0.2.0`, or run the "Signed Release"
  workflow manually from the Actions tab.
- The workflow fails fast if `TB_KEYSTORE_B64` is missing.
- It assembles the R8-minified release APK, verifies the
  signature with `apksigner`, checks 16 KB page alignment
  with `zipalign -P 16`, uploads the APK as an artifact,
  and (on tag runs) attaches it to the GitHub release.

## Notes

- The debug builds from `android-ci.yml` are unaffected and
  keep using the auto-generated debug key.
- R8 keep rules live in `app/proguard-rules.pro`. The
  load-bearing one: Bouncy Castle is loaded by reflection
  (`SshCryptoProvider`), so the crypto/transport stack is
  kept whole; everything else minifies normally.
- A signed APK still needs the on-device smoke pass in
  `docs/DEVICE_TEST_PLAN.md` before it is announced —
  minified builds can fail at runtime in ways compilation
  cannot see.
