#!/usr/bin/env bash
# TermBridge instrumented-test runner with the SSH fixture.
#
# Why this wrapper exists (CI run #17 lesson): the
# android-emulator-runner executes the workflow's `script`
# block in a way that did NOT carry an exported
# FIXTURE_PASSWORD into the fixture setup script — the
# fixture step failed with "FIXTURE_PASSWORD is required"
# even though the workflow lines generated and exported it.
# Running everything inside ONE bash process removes that
# dependency entirely: the password is generated, exported,
# and consumed here, never relying on the runner to preserve
# environment between script lines.
#
# What it does, in order (fails fast on any step):
#   1. Generates a throwaway fixture password (never
#      echoed, never logged, never committed).
#   2. Provisions the dedicated fixture sshd
#      (tests/fixture/setup-sshd-fixture.sh).
#   3. Runs the instrumented tests with the fixture
#      coordinates as instrumentation arguments.
#
# The password lives only in this process on the ephemeral
# CI runner. No `set -x`: nothing here may echo it.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$(dirname "$SCRIPT_DIR")")"
cd "$REPO_ROOT"

FIXTURE_PASSWORD="$(openssl rand -base64 24)"
export FIXTURE_PASSWORD
bash tests/fixture/setup-sshd-fixture.sh

# --- Diagnostic probe (PropertyFetcher investigation, run #24) ---
# Purpose: distinguish an emulator/adbd-side stall from a Gradle-side
# failure. Run #24 showed Gradle's device-property query failing 3x
# (ShellCommandUnresponsive) ~5.5 min after boot, while the runner's
# single-property polls succeeded. This probe runs the SAME kind of
# query Gradle does — a full `getprop` (large output) plus the exact
# SDK property behind the "Unknown API Level" skip — BEFORE Gradle
# starts, with timing. NON-FATAL by design: every step is guarded so
# a stalled probe is recorded, never aborts the run (the script runs
# under `set -euo pipefail`, so plain failures here would kill it).
# If the probe stalls but Gradle later fails the same way, the stall
# is emulator-side; if the probe answers promptly and Gradle still
# fails, the problem is Gradle/ddmlib-side. Probe output contains
# only device properties — no secrets.
ADB_BIN="adb"
if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
  ADB_BIN="$ANDROID_HOME/platform-tools/adb"
fi
echo "PROBE: adb=$ADB_BIN device=emulator-5554 (PropertyFetcher diagnosis)"
PROBE_T0="$(date +%s)"
if timeout 120 "$ADB_BIN" -s emulator-5554 shell getprop > /tmp/termbridge-probe-getprop.txt 2>/tmp/termbridge-probe-getprop.err; then
  PROBE_RC=0
else
  PROBE_RC=$?
fi
PROBE_T1="$(date +%s)"
echo "PROBE: full getprop rc=$PROBE_RC elapsed=$((PROBE_T1 - PROBE_T0))s lines=$(wc -l < /tmp/termbridge-probe-getprop.txt 2>/dev/null || echo '?')"
sed -n '1,20p' /tmp/termbridge-probe-getprop.txt 2>/dev/null || true
echo "PROBE: sdk=$(timeout 60 "$ADB_BIN" -s emulator-5554 shell getprop ro.build.version.sdk 2>/dev/null || echo 'TIMEOUT_OR_ERROR')"
# --- End diagnostic probe ---

./gradlew :app:connectedDebugAndroidTest --stacktrace \
  -Pandroid.testInstrumentationRunnerArguments.fixture_host=10.0.2.2 \
  -Pandroid.testInstrumentationRunnerArguments.fixture_port=2222 \
  -Pandroid.testInstrumentationRunnerArguments.fixture_user=termbridge-test \
  -Pandroid.testInstrumentationRunnerArguments.fixture_password="$FIXTURE_PASSWORD"
