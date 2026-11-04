#!/usr/bin/env bash
# TermBridge local compile + test harness ("local-first, push last").
#
# What it does (without Gradle, which cannot run in this sandbox):
#   1. Downloads the exact pinned dependency jars/AARs from Maven Central
#      (curl works through the sandbox proxy; plain-Java HTTPS does not).
#   2. Compiles every main source file that does NOT depend on Compose
#      (domain, data, transport, terminal, security + app class) with
#      kotlinc 2.0.21 against android.jar (API 35) + the dependency jars.
#   3. Compiles and RUNS every JVM unit test with JUnitCore.
#   4. Compiles the device-independent instrumented tests (SshFixture,
#      SshIntegrationTest, KeystoreAndRoomTest, CryptoProviderTest,
#      RfbIntegrationTest) — compile-only, because they need an
#      emulator to RUN.
#   5. Compiles the XML resources with aapt2 and syntax-checks the
#      fixture shell scripts.
#
# What it CANNOT prove (still needs GitHub Actions CI):
#   - Compose UI compilation (needs the Compose compiler plugin; the
#     plain kotlinc invocation here would fail spuriously on @Composable
#     code, so UI files are excluded by design, not by accident).
#   - The real Gradle build (resource linking, DEX, packaging).
#   - Anything that runs on a device/emulator (fixture tests, Keystore
#     behaviour, SAF, process death).
#
# Rule: no push until this script exits 0. CI then only has to prove
# what this script cannot — it must never again be the tool that finds
# a compile error or a unit-test failure.
#
# Usage:
#   bash tests/local/local-check.sh [--skip-deps] [--skip-run]
# Environment overrides:
#   TERMCHECK_CACHE   deps + outputs dir (default ~/.cache/termbridge-local-check)
#   KOTLINC_BIN       kotlinc binary (default ~/workspace/tools/kotlinc/kotlinc/bin/kotlinc)
#   JAVA_BIN          java binary (default $JAVA_HOME/bin/java, else `java`)
#   ANDROID_JAR       android.jar (default ~/workspace/tools/android-sdk/platforms/android-35/android.jar)
#   AAPT2_BIN         aapt2 binary (default ~/workspace/tools/android-sdk/build-tools/35.0.0/aapt2)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CACHE="${TERMCHECK_CACHE:-$HOME/.cache/termbridge-local-check}"
DEPS="$CACHE/deps"
OUT="$CACHE/out"
MVN="https://repo1.maven.org/maven2"
# androidx artifacts come from Google Maven (the canonical source Gradle
# uses via google()); Maven Central does not serve them through this proxy.
GOOGLE="https://maven.google.com"

KOTLINC_BIN="${KOTLINC_BIN:-$HOME/workspace/tools/kotlinc/kotlinc/bin/kotlinc}"
if [ ! -x "$KOTLINC_BIN" ]; then KOTLINC_BIN="$(command -v kotlinc)"; fi
JAVA_BIN="${JAVA_BIN:-${JAVA_HOME:-$HOME/workspace/tools/jdk-17.0.20.1+1}/bin/java}"
if [ ! -x "$JAVA_BIN" ]; then JAVA_BIN="$(command -v java)"; fi
# kotlinc's launcher resolves `java` from PATH, not from JAVA_BIN.
export PATH="$(dirname "$JAVA_BIN"):$PATH"
ANDROID_JAR="${ANDROID_JAR:-$HOME/workspace/tools/android-sdk/platforms/android-35/android.jar}"
AAPT2_BIN="${AAPT2_BIN:-$HOME/workspace/tools/android-sdk/build-tools/35.0.0/aapt2}"

SKIP_DEPS=0; SKIP_RUN=0
for arg in "$@"; do
  case "$arg" in
    --skip-deps) SKIP_DEPS=1 ;;
    --skip-run)  SKIP_RUN=1 ;;
    *) echo "unknown arg: $arg" >&2; exit 2 ;;
  esac
done

fail() { echo "LOCAL-CHECK FAILED: $*" >&2; exit 1; }
note() { echo "== $*"; }

[ -f "$ANDROID_JAR" ] || fail "android.jar not found at $ANDROID_JAR (set ANDROID_JAR)"
[ -x "$KOTLINC_BIN" ] || fail "kotlinc not found (set KOTLINC_BIN)"
mkdir -p "$DEPS" "$OUT/main" "$OUT/test" "$OUT/androidTest"

# --- dependency fetch -------------------------------------------------------
# fetch_jar <base> <group-path> <artifact> <version> [sha256]
fetch_jar() {
  local base="$1"
  local path="$2"
  local artifact="$3"
  local version="$4"
  local want_sha="${5:-}"
  local file="$DEPS/$artifact-$version.jar"
  if [ "$SKIP_DEPS" = 1 ] && [ -s "$file" ]; then echo "cached $artifact-$version"; return 0; fi
  if [ ! -s "$file" ]; then
    note "downloading $artifact-$version"
    curl -fsSL -L --retry 2 -o "$file" "$base/$path/$artifact/$version/$artifact-$version.jar" \
      || fail "download failed: $artifact-$version"
  fi
  unzip -t -qq "$file" >/dev/null 2>&1 || fail "not a valid jar: $file"
  if [ -n "$want_sha" ] && command -v sha256sum >/dev/null 2>&1; then
    local got_sha; got_sha="$(sha256sum "$file" | cut -d' ' -f1)"
    [ "$got_sha" = "$want_sha" ] || fail "SHA-256 mismatch for $artifact-$version (got $got_sha)"
  fi
}

# fetch_aar <base> <group-path> <artifact> <version> — extracts classes.jar
fetch_aar() {
  local base="$1"
  local path="$2"
  local artifact="$3"
  local version="$4"
  local aar="$DEPS/$artifact-$version.aar"
  local jar="$DEPS/$artifact-$version-classes.jar"
  if [ "$SKIP_DEPS" = 1 ] && [ -s "$jar" ]; then echo "cached $artifact-$version"; return 0; fi
  if [ ! -s "$aar" ]; then
    note "downloading $artifact-$version (aar)"
    curl -fsSL -L --retry 2 -o "$aar" "$base/$path/$artifact/$version/$artifact-$version.aar" \
      || fail "download failed: $artifact-$version"
  fi
  if [ ! -s "$jar" ]; then
    (cd "$DEPS" && unzip -o -q "$aar" classes.jar && mv classes.jar "$jar") \
      || fail "no classes.jar inside $artifact-$version.aar"
  fi
}

if [ "$SKIP_DEPS" = 0 ]; then
  note "stage 0: dependencies"
  fetch_jar "$MVN" org/jetbrains/kotlinx kotlinx-coroutines-core-jvm 1.8.1
  fetch_jar "$MVN" org/jetbrains/kotlinx kotlinx-serialization-json-jvm 1.7.3
  fetch_jar "$MVN" org/jetbrains/kotlinx kotlinx-serialization-core-jvm 1.7.3
  fetch_jar "$MVN" org/jetbrains/kotlinx kotlinx-coroutines-test-jvm 1.8.1
  fetch_jar "$MVN" com/hierynomus sshj 0.40.0 a6a5533f6580e0418dfcacbb5396680186698585c325fa950c788e749f24d1e2
  fetch_jar "$MVN" com/hierynomus asn-one 0.6.0
  fetch_jar "$MVN" org/bouncycastle bcprov-jdk18on 1.80
  fetch_jar "$MVN" org/bouncycastle bcpkix-jdk18on 1.80
  fetch_jar "$MVN" net/i2p/crypto eddsa 0.3.0
  fetch_jar "$MVN" org/slf4j slf4j-api 1.7.36
  fetch_jar "$MVN" junit junit 4.13.2
  fetch_jar "$MVN" org/hamcrest hamcrest-core 1.3
  fetch_jar "$GOOGLE" androidx/annotation annotation-jvm 1.8.2
  fetch_jar "$GOOGLE" androidx/room room-common 2.6.1
  fetch_aar "$GOOGLE" androidx/room room-runtime 2.6.1
  fetch_aar "$GOOGLE" androidx/room room-ktx 2.6.1
  fetch_aar "$GOOGLE" androidx/sqlite sqlite-android 2.5.0
  fetch_aar "$GOOGLE" androidx/test core 1.6.1
  fetch_aar "$GOOGLE" androidx/test monitor 1.7.2
  fetch_aar "$GOOGLE" androidx/test/ext junit 1.2.1
  fetch_aar "$GOOGLE" androidx/test runner 1.6.1
  fetch_aar "$GOOGLE" androidx/core core 1.15.0
  # App-lock groundwork (2026-11-04): ui/BiometricHelper.kt is not a
  # Compose file, so it is inside this harness's compile scope, but the
  # biometric dependency (added with the app-lock work) was never added
  # here. Versions mirror the tree's catalog / resolved graph:
  # biometric 1.1.0 (catalog), fragment 1.2.5 (biometric's POM),
  # activity 1.9.3 + lifecycle 2.8.7 + core 1.15.0 (catalog pins win),
  # savedstate 1.2.1 (activity's POM).
  fetch_aar "$GOOGLE" androidx/biometric biometric 1.1.0
  fetch_aar "$GOOGLE" androidx/fragment fragment 1.2.5
  fetch_aar "$GOOGLE" androidx/activity activity 1.9.3
  # Lifecycle 2.8.x is Kotlin-multiplatform: the plain artifacts are
  # metadata-only, the Android/JVM classes live in the -android / -jvm
  # variants (verified against Google Maven, 2026-11-04).
  fetch_aar "$GOOGLE" androidx/lifecycle lifecycle-runtime-android 2.8.7
  fetch_aar "$GOOGLE" androidx/lifecycle lifecycle-viewmodel-android 2.8.7
  fetch_jar "$GOOGLE" androidx/lifecycle lifecycle-common-jvm 2.8.7
  fetch_aar "$GOOGLE" androidx/savedstate savedstate 1.2.1
fi

join_by() { local IFS="$1"; shift; echo "$*"; }
MAIN_CP="$(join_by : "$ANDROID_JAR" \
  "$DEPS/kotlinx-coroutines-core-jvm-1.8.1.jar" \
  "$DEPS/kotlinx-serialization-json-jvm-1.7.3.jar" \
  "$DEPS/kotlinx-serialization-core-jvm-1.7.3.jar" \
  "$DEPS/sshj-0.40.0.jar" \
  "$DEPS/asn-one-0.6.0.jar" \
  "$DEPS/bcprov-jdk18on-1.80.jar" \
  "$DEPS/bcpkix-jdk18on-1.80.jar" \
  "$DEPS/eddsa-0.3.0.jar" \
  "$DEPS/slf4j-api-1.7.36.jar" \
  "$DEPS/annotation-jvm-1.8.2.jar" \
  "$DEPS/room-runtime-2.6.1-classes.jar" \
  "$DEPS/room-common-2.6.1.jar" \
  "$DEPS/room-ktx-2.6.1-classes.jar" \
  "$DEPS/sqlite-android-2.5.0-classes.jar" \
  "$DEPS/core-1.15.0-classes.jar" \
  "$DEPS/biometric-1.1.0-classes.jar" \
  "$DEPS/fragment-1.2.5-classes.jar" \
  "$DEPS/activity-1.9.3-classes.jar" \
  "$DEPS/lifecycle-runtime-android-2.8.7-classes.jar" \
  "$DEPS/lifecycle-viewmodel-android-2.8.7-classes.jar" \
  "$DEPS/lifecycle-common-jvm-2.8.7.jar" \
  "$DEPS/savedstate-1.2.1-classes.jar")"
OLD_IFS="$IFS"; IFS=':'
for j in $MAIN_CP; do
  [ -s "$j" ] || fail "missing classpath jar: $j (run without --skip-deps)"
done
IFS="$OLD_IFS"

# --- stage 1: main sources (non-Compose) ------------------------------------
note "stage 1: compiling main sources (non-Compose)"
find "$ROOT/app/src/main" -name "*.kt" > "$OUT/all-main.lst"
# Exclude every file that imports Compose / Activity-Compose / Navigation /
# Lifecycle: those need the Compose compiler plugin and the Gradle build.
grep -L -E '^import (androidx\.compose|androidx\.activity|androidx\.navigation|androidx\.lifecycle)' \
  $(cat "$OUT/all-main.lst") > "$OUT/main.lst" || true
MAIN_COUNT="$(wc -l < "$OUT/main.lst" | tr -d ' ')"
EXCLUDED_COUNT="$(( $(wc -l < "$OUT/all-main.lst" | tr -d ' ') - MAIN_COUNT ))"
echo "  main files: $MAIN_COUNT compiled, $EXCLUDED_COUNT Compose/UI excluded (CI proves those)"
"$KOTLINC_BIN" -jvm-target 17 -cp "$MAIN_CP" -d "$OUT/main" @"$OUT/main.lst" \
  || fail "main compilation failed (this is exactly the error class CI runs #1-#11 and #20 found)"
echo "  main compilation OK ($MAIN_COUNT files)"

# --- stage 2: unit tests: compile + run --------------------------------------
note "stage 2: compiling unit tests"
# Compile ALL test sources: *Test.kt files plus helpers like FakeDaos.kt
# and FakeSecretCrypto.kt that the tests reference. Only *Test classes run.
find "$ROOT/app/src/test" -name "*.kt" > "$OUT/unit-all.lst"
find "$ROOT/app/src/test" -name "*Test.kt" > "$OUT/unit.lst"
UNIT_COUNT="$(wc -l < "$OUT/unit.lst" | tr -d ' ')"
# kotlinc puts its stdlib on the compile classpath automatically, but the
# JUnitCore run needs it explicitly on the runtime classpath.
KOTLIN_STDLIB="$(cd "$(dirname "$KOTLINC_BIN")/../lib" && pwd)/kotlin-stdlib.jar"
[ -f "$KOTLIN_STDLIB" ] || fail "kotlin-stdlib.jar not found next to kotlinc (set KOTLINC_BIN)"
TEST_CP="$OUT/main:$MAIN_CP:$KOTLIN_STDLIB:$DEPS/junit-4.13.2.jar:$DEPS/hamcrest-core-1.3.jar:$DEPS/kotlinx-coroutines-test-jvm-1.8.1.jar"
if [ -d "$ROOT/app/src/test/resources" ]; then TEST_CP="$TEST_CP:$ROOT/app/src/test/resources"; fi
# -Xfriend-paths mirrors Gradle's test compilation: tests may access
# internal declarations of main (e.g. SftpManager.renamePaths).
"$KOTLINC_BIN" -jvm-target 17 -Xfriend-paths="$OUT/main" -cp "$TEST_CP" -d "$OUT/test" @"$OUT/unit-all.lst" \
  || fail "unit-test compilation failed"
echo "  unit-test compilation OK ($UNIT_COUNT files)"

if [ "$SKIP_RUN" = 0 ]; then
  note "stage 2b: running unit tests (JUnitCore)"
  # Derive runnable classes from the compiled output, not from source
  # file names: one *Test.kt file may hold several test classes
  # (Phase2Test.kt holds QuickConnectTest, SubnetPlannerTest and
  # ConnectionLogCodecTest, and no Phase2Test class at all), and a
  # file-name guess makes JUnitCore abort the entire run at parse time.
  TEST_CLASSES="$(cd "$OUT/test" && find . -name "*Test.class" ! -name '*$*' \
    | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort | tr '\n' ' ')"
  # -Djava.net.preferIPv4Stack=true is REQUIRED in this sandbox: without
  # it, Java loopback sockets are intercepted (a Java client reads
  # "muse: Other " instead of the peer's bytes; Python loopback is
  # unaffected). Without the flag, RfbConnectionTest fails locally
  # while passing in CI — a sandbox artifact, not a product defect.
  # shellcheck disable=SC2086
  "$JAVA_BIN" -Djava.net.preferIPv4Stack=true -cp "$TEST_CP:$OUT/test" org.junit.runner.JUnitCore $TEST_CLASSES 2>&1 | tee "$OUT/unit-tests.log" \
    || fail "unit tests failed (see $OUT/unit-tests.log)"
  grep -E "^(OK|Tests run)" "$OUT/unit-tests.log" | tail -2
fi

# --- stage 3: instrumented tests, compile-only -------------------------------
note "stage 3: compiling device-independent instrumented tests (compile-only)"
ANDROIDTEST_CP="$OUT/main:$MAIN_CP:$DEPS/junit-4.13.2.jar:$DEPS/hamcrest-core-1.3.jar:$DEPS/kotlinx-coroutines-test-jvm-1.8.1.jar:$DEPS/core-1.6.1-classes.jar:$DEPS/monitor-1.7.2-classes.jar:$DEPS/junit-1.2.1-classes.jar:$DEPS/runner-1.6.1-classes.jar:$DEPS/core-1.15.0-classes.jar"
printf '%s\n' \
  "$ROOT/app/src/androidTest/java/com/pishi/termbridge/SshFixture.kt" \
  "$ROOT/app/src/androidTest/java/com/pishi/termbridge/SshIntegrationTest.kt" \
  "$ROOT/app/src/androidTest/java/com/pishi/termbridge/KeystoreAndRoomTest.kt" \
  "$ROOT/app/src/androidTest/java/com/pishi/termbridge/CryptoProviderTest.kt" \
  "$ROOT/app/src/androidTest/java/com/pishi/termbridge/RfbIntegrationTest.kt" \
  > "$OUT/androidtest.lst"
"$KOTLINC_BIN" -jvm-target 17 -Xfriend-paths="$OUT/main" -cp "$ANDROIDTEST_CP" -d "$OUT/androidTest" @"$OUT/androidtest.lst" \
  || fail "instrumented-test compilation failed (this is the run #20 error class)"
echo "  instrumented-test compilation OK (5 files; MainActivitySmokeTest excluded — needs Compose test + UI, CI proves it)"

# --- stage 4: resources + fixture scripts ------------------------------------
if [ -x "$AAPT2_BIN" ]; then
  note "stage 4: aapt2 compiling resources"
  mkdir -p "$OUT/res"
  "$AAPT2_BIN" compile --dir "$ROOT/app/src/main/res" -o "$OUT/res/compiled.zip" \
    || fail "aapt2 resource compilation failed"
  echo "  resources OK"
else
  echo "  (aapt2 not found at $AAPT2_BIN — resource stage skipped, CI covers it)"
fi
note "stage 4b: syntax-checking fixture scripts"
bash -n "$ROOT/tests/fixture/setup-sshd-fixture.sh" || fail "setup-sshd-fixture.sh has a syntax error"
bash -n "$ROOT/tests/fixture/run-instrumented-with-fixture.sh" || fail "run-instrumented-with-fixture.sh has a syntax error"
echo "  fixture scripts OK"

echo
echo "LOCAL-CHECK PASSED: main=$MAIN_COUNT files, unit tests=$UNIT_COUNT files compiled (+ run unless --skip-run), instrumented=5 files compiled."
echo "Still requires CI: Compose UI compile, Gradle build/packaging, emulator fixture tests, device behaviour."
