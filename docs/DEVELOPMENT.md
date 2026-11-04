# TermBridge — Development Guide (Prompt 02)

## Toolchain (pinned, not "latest")

| Piece | Version | Why this one |
|---|---|---|
| Gradle wrapper | 8.10.2 | Proven stack from the user's other Android projects; wrapper jar is genuine (see below). |
| Android Gradle Plugin | 8.7.3 | Pairs with Gradle 8.10.2 and Kotlin 2.0.21; AGP 9 / Gradle 9 would force a breaking migration and are NOT adopted opportunistically. |
| Kotlin | 2.0.21 | AGP 8.7.3's paired Kotlin version. |
| JDK to run Gradle | 17 (Temurin) | AGP 8.7.3 requires JDK 17; app bytecode target is also 17. |
| compileSdk / targetSdk | 35 | Current target for the foundation. |
| minSdk | 26 | Android 8.0+: modern Keystore behaviour, no legacy API workarounds for the foundation. Locked in prompt 02; lowering it later is a deliberate decision with a compatibility cost, not a one-line edit. |
| Compose BOM | 2024.09.00 | Compose 1.7 line compatible with Kotlin 2.0.21. |
| Navigation Compose | 2.8.0 | Used from prompt 03's shell onward. |
| Coroutines | 1.8.1 | Transport lifecycle (prompts 05+) runs on coroutines. |

Application ID (working, pre-release): `com.pishi.termbridge`
(namespace matches). It can still be changed before the first public
release; after release it must not change. Debug builds use the
`.debug` suffix so debug and release can coexist on one device.

## Build and test commands

```sh
# Unit tests + debug APK (the CI job runs exactly this):
./gradlew :app:testDebugUnitTest :app:assembleDebug --stacktrace

# Lint (run before claiming a phase is clean, from prompt 03 onward):
./gradlew :app:lintDebug

# Instrumented tests need an emulator/device. CI runs them on an
# emulator (API 29) against a real sshd fixture via
# tests/fixture/run-instrumented-with-fixture.sh; the same command
# works locally with a device attached:
./gradlew :app:connectedDebugAndroidTest
```

## Build route — read this before claiming a build

The authoritative build route is **GitHub Actions**
(`.github/workflows/android-ci.yml`: JDK 17, Android SDK via
setup-android, wrapper 8.10.2; two jobs — build: unit tests +
assembleDebug and, on main, publishing the APK to the rolling
`debug-latest` release; instrumented: emulator API 29 with the sshd
fixture). The local
sandbox is NOT a build route, for two independent, previously recorded
reasons:

1. Plain-Java HTTPS is broken by the sandbox egress proxy: the Gradle
   wrapper's distribution download fails with
   `javax.net.ssl.SSLException: Unsupported or unrecognized SSL message`
   (reproduced in prompt 02). curl/git work; Gradle's Java HTTPS does not.
2. Even with a local Gradle distribution, the Gradle daemon protocol
   is incompatible with this sandbox: `Could not receive a message
   from the daemon` (reproduced in prompt 02 with Gradle 8.10.2 +
   JDK 17, `--no-daemon` included).

Consequence: any build/test claim in this project must cite a CI run
(or a capable local machine) — never a sandbox Gradle invocation.
Prompt 02's local verification is therefore static only (XML/YAML
well-formedness, secret/dependency scan, wrapper-jar checksum), and
the build + unit-test + launch claims are all `NOT RUN` until CI or a
device run provides evidence.

## Wrapper authenticity

`gradle/wrapper/gradle-wrapper.jar` was extracted from the genuine
Gradle 8.10.2 distribution's `gradle-wrapper-main` jar (the download
URL for the standalone wrapper jar returned an S3 NoSuchKey error in
this sandbox). Its SHA-256 is:

```
2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046
```

…which matches the checksum Gradle publishes for the 8.10.2 wrapper
jar. This matters because `gradle/actions/setup-gradle` rejects
hand-assembled wrapper jars. `gradlew`/`gradlew.bat` are the stock
scripts from the Gradle 8.10.2 tag. `distributionSha256Sum` is
deliberately NOT set in `gradle-wrapper.properties`: that field is
the distribution ZIP's checksum, not the wrapper jar's, and setting
the wrong value would break wrapper validation.

## Conventions (prompt 02)

- Kotlin style: standard Kotlin/Compose conventions; no custom lint
  config yet — prompt 03 may add one only with a reason.
- Coroutines: all network/disk work off the main thread, structured
  concurrency, cancellation propagated (enforced from prompt 05).
- Error handling: transport errors are typed results/states, never
  silent fallbacks and never exceptions swallowed into a success UI.
- Dependency injection: NONE yet. Manual construction is sufficient
  for a single-module foundation; a DI framework is added only if a
  later prompt shows the wiring cost justifies it.
- Module boundaries: one app module with the logical layers from
  docs/ARCHITECTURE.md (`ui`, `domain`, `data`, `security`,
  `transport`, `diagnostics` as packages when their prompts land).
  A Gradle module is created only on evidence, never for appearance.
- Dependencies: every dependency added after prompt 02 must record
  purpose, licence, maintenance evidence, and Android/ABI/size impact
  in its prompt report. SSH (05), Room (04), and native RDP/VNC (12/13)
  are deliberately absent from this foundation.

## Security defaults already in the foundation

- `allowBackup = false` (encrypted blobs must not be silently
  restorable without their Keystore key — prompt 04 owns the final
  backup rule and may revisit this only with an explicit decision).
- `usesCleartextTraffic = false` + fail-closed network security config
  (system trust anchors only).
- Permissions: INTERNET + ACCESS_NETWORK_STATE in the app manifest, plus USE_BIOMETRIC and USE_FINGERPRINT contributed to the merged manifest by the AndroidX biometric library for the optional app lock (verified against the biometric 1.1.0 library manifest, 2026-11-04).
- `.gitignore` blocks keystores, `.p12/.pfx/.pem/.key`, `key.properties`,
  `local.properties`, build output, and any governance/prompt-pack
  directory copied into the tree by mistake.
- No release signing is configured. Prompt 18 owns it, via CI secrets
  only — never a committed keystore or password.
