#!/usr/bin/env bash
# ui-check.sh — compile the Compose UI layer locally.
#
# tests/local/local-check.sh deliberately excludes every file
# that imports Compose / Lifecycle / Navigation (they need the
# Compose compiler plugin). That left all UI code CI-proven
# only. This companion script closes that gap: it fetches the
# pinned Compose/AndroidX AARs, generates R.java with aapt2
# link, and type-checks every UI source with kotlinc + the
# Compose compiler plugin bundled in the Kotlin 2.0.21
# distribution.
#
# It type-checks only — nothing here runs, and it does not
# replace the Gradle build (resource linking, DEX, packaging
# stay CI's job). Run local-check.sh first: this script reuses
# its compiled main classes and dependency cache.
#
# Usage: bash tests/local/ui-check.sh [--skip-deps]
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CACHE="$HOME/.cache/termbridge-local-check"
DEPS="$CACHE/deps"
UIDEPS="$CACHE/ui-deps"
OUT="$CACHE/ui-out"
KOTLINC_BIN="${KOTLINC_BIN:-$HOME/workspace/tools/kotlinc/kotlinc/bin/kotlinc}"
JAVA_HOME_17="${JAVA_HOME_17:-$HOME/workspace/tools/jdk-17.0.20.1+1}"
AAPT2_BIN="${AAPT2_BIN:-$HOME/workspace/tools/android-sdk/build-tools/35.0.0/aapt2}"
ANDROID_JAR="$HOME/workspace/tools/android-sdk/platforms/android-35/android.jar"
GOOGLE="https://maven.google.com"
SKIP_DEPS=0
[ "${1:-}" = "--skip-deps" ] && SKIP_DEPS=1

fail() { echo "UI-CHECK FAILED: $1" >&2; exit 1; }
mkdir -p "$UIDEPS" "$OUT"
export PATH="$JAVA_HOME_17/bin:$PATH"

[ -d "$CACHE/out" ] || fail "no harness cache — run tests/local/local-check.sh first"

fetch_aar() { # group-path artifact version
    local path="$1" name="$2" ver="$3"
    local aar="$UIDEPS/$name-$ver.aar"
    local jar="$UIDEPS/$name-$ver-classes.jar"
    if [ ! -f "$jar" ]; then
        curl -fsSL -o "$aar" "$GOOGLE/$path/$name/$ver/$name-$ver.aar" \
            || fail "download failed: $name-$ver"
        (cd "$UIDEPS" && unzip -o -q "$aar" classes.jar -d "$name-$ver-x" \
            && cp "$name-$ver-x/classes.jar" "$jar") || fail "extract failed: $name-$ver"
    fi
}
fetch_jar() { # group-path artifact version
    local path="$1" name="$2" ver="$3"
    local jar="$UIDEPS/$name-$ver.jar"
    if [ ! -f "$jar" ]; then
        curl -fsSL -o "$jar" "$GOOGLE/$path/$name/$ver/$name-$ver.jar" \
            || fail "download failed: $name-$ver"
    fi
}

if [ "$SKIP_DEPS" -eq 0 ]; then
    echo "== fetching Compose / AndroidX artifacts (BOM 2024.09.00 pins)"
    CU="androidx/compose/ui"
    fetch_aar "$CU" ui-android 1.7.2
    fetch_aar "$CU" ui-graphics-android 1.7.2
    fetch_aar "$CU" ui-text-android 1.7.2
    fetch_aar "$CU" ui-unit-android 1.7.2
    fetch_aar "$CU" ui-geometry-android 1.7.2
    fetch_aar "$CU" ui-util-android 1.7.2
    fetch_aar "$CU" ui-tooling-preview-android 1.7.2
    CF="androidx/compose/foundation"
    fetch_aar "$CF" foundation-android 1.7.2
    fetch_aar "$CF" foundation-layout-android 1.7.2
    CA="androidx/compose/animation"
    fetch_aar "$CA" animation-android 1.7.2
    fetch_aar "$CA" animation-core-android 1.7.2
    fetch_aar "androidx/compose/material3" material3-android 1.3.0
    CM="androidx/compose/material"
    fetch_aar "$CM" material-icons-core-android 1.7.2
    fetch_aar "$CM" material-icons-extended-android 1.7.2
    fetch_aar "$CM" material-ripple-android 1.7.2
    CR="androidx/compose/runtime"
    fetch_aar "$CR" runtime-android 1.7.2
    fetch_aar "$CR" runtime-saveable-android 1.7.2
    fetch_aar "androidx/activity" activity-compose 1.9.3
    NV="androidx/navigation"
    fetch_aar "$NV" navigation-compose 2.8.0
    fetch_aar "$NV" navigation-common 2.8.0
    fetch_aar "$NV" navigation-runtime 2.8.0
    LC="androidx/lifecycle"
    fetch_aar "$LC" lifecycle-viewmodel-compose-android 2.8.7
    fetch_aar "$LC" lifecycle-runtime-compose-android 2.8.7
    fetch_aar "$LC" lifecycle-viewmodel-savedstate 2.8.7
    fetch_aar "androidx/customview" customview-poolingcontainer 1.0.0
    fetch_jar "androidx/collection" collection-jvm 1.4.0
    fetch_jar "androidx/annotation" annotation-jvm 1.8.2
    # The compose plugin bundled in the standalone kotlinc
    # distribution predates the Kotlin 2.0 registrar API; the
    # embeddable plugin for exactly 2.0.21 comes from Maven
    # Central instead.
    if [ ! -f "$UIDEPS/compose-plugin-embeddable-2.0.21.jar" ]; then
        curl -fsSL -o "$UIDEPS/compose-plugin-embeddable-2.0.21.jar" \
            "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compose-compiler-plugin-embeddable/2.0.21/kotlin-compose-compiler-plugin-embeddable-2.0.21.jar" \
            || fail "download failed: compose plugin embeddable"
    fi
    # The compose plugin registers through the compiler's
    # embeddable bridge; the standalone kotlinc CLI jar lacks
    # it (AbstractMethodError on the registrar). Gradle runs
    # the embeddable compiler — so does this script.
    if [ ! -f "$UIDEPS/kotlin-compiler-embeddable-2.0.21.jar" ]; then
        curl -fsSL -o "$UIDEPS/kotlin-compiler-embeddable-2.0.21.jar" \
            "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.0.21/kotlin-compiler-embeddable-2.0.21.jar" \
            || fail "download failed: kotlin compiler embeddable"
        curl -fsSL -o "$UIDEPS/trove4j.jar" \
            "https://repo1.maven.org/maven2/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar" \
            || fail "download failed: trove4j"
        curl -fsSL -o "$UIDEPS/annotations-13.0.jar" \
            "https://repo1.maven.org/maven2/org/jetbrains/annotations/13.0/annotations-13.0.jar" \
            || fail "download failed: jetbrains annotations"
    fi
fi

echo "== generating R.java (aapt2 link)"
GEN="$OUT/gen"
rm -rf "$GEN" && mkdir -p "$GEN"
# Compile the resources HERE, fresh: the harness's compiled.zip
# goes stale the moment a string is added, and a stale R class
# then reports phantom unresolved references.
RESZIP="$OUT/compiled.zip"
"$AAPT2_BIN" compile --dir "$ROOT/app/src/main/res" -o "$RESZIP" \
    || fail "aapt2 compile failed"
# The app's manifest carries no package attribute (the Gradle
# namespace supplies it), and aapt2 link requires one: feed it a
# temporary copy with the namespace injected.
sed 's|<manifest |<manifest package="com.pishi.termbridge" |' \
    "$ROOT/app/src/main/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$AAPT2_BIN" link -o "$OUT/linked.apk" --java "$GEN" \
    -I "$ANDROID_JAR" \
    --manifest "$OUT/AndroidManifest.xml" \
    "$RESZIP" || fail "aapt2 link failed"
RJAVA="$(find "$GEN" -name "R.java" | head -n 1)"
[ -n "$RJAVA" ] || fail "aapt2 link produced no R.java"

echo "== type-checking ALL app sources as one module (kotlinc + Compose plugin)"
# One module, like the Gradle build: compiling the UI against
# separately-built main classes produces false smart-cast
# errors (a public property from "another module" cannot be
# smart-cast). KSP/Room generated classes are only referenced
# reflectively by Room at runtime, so a source-only compile is
# sound.
CP="$ANDROID_JAR"
for j in "$DEPS"/*.jar "$UIDEPS"/*-classes.jar "$UIDEPS"/collection-jvm-*.jar "$UIDEPS"/annotation-jvm-*.jar; do
    [ -f "$j" ] && CP="$CP:$j"
done
find "$ROOT/app/src/main/java/com/pishi/termbridge" -name "*.kt" > "$OUT/ui.lst"
echo "  app files: $(wc -l < "$OUT/ui.lst" | tr -d ' ')"
STDLIB="$HOME/workspace/tools/kotlinc/kotlinc/lib/kotlin-stdlib.jar"
COROUTINES="$DEPS/kotlinx-coroutines-core-jvm-1.8.1.jar"
java -cp "$UIDEPS/kotlin-compiler-embeddable-2.0.21.jar:$UIDEPS/trove4j.jar:$UIDEPS/annotations-13.0.jar:$STDLIB:$COROUTINES" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -kotlin-home "$HOME/workspace/tools/kotlinc/kotlinc" \
    -Xdisable-default-scripting-plugin \
    -jvm-target 17 \
    -Xplugin="$UIDEPS/compose-plugin-embeddable-2.0.21.jar" \
    -opt-in=androidx.compose.material3.ExperimentalMaterial3Api \
    -opt-in=androidx.compose.foundation.ExperimentalFoundationApi \
    -cp "$CP" -d "$OUT/classes" \
    @"$OUT/ui.lst" "$RJAVA" \
    || fail "UI compilation failed"
echo ""
echo "UI-CHECK PASSED: Compose UI type-checked locally."
echo "Still requires CI: Gradle build/packaging, emulator fixture tests, device behaviour."
