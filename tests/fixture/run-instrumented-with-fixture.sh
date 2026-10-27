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
#   3. Runs a non-fatal pre-Gradle device probe (PropertyFetcher
#      diagnosis) and then the instrumented tests with the fixture
#      coordinates as instrumentation arguments. The Gradle run is
#      retried (up to 3 attempts total) ONLY on flaky first-contact
#      signatures — see below.
#   4. If Gradle fails, re-probes device state (post-failure probe)
#      and exits with Gradle's original code, so the job still
#      fails honestly.
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

# Gradle runs with errexit suspended so its exit code can be captured
# for the post-failure probe below; a failing Gradle must NOT abort
# the script before diagnostics run.
#
# --- Retry on flaky first-contact signatures (runs #30, #31) ---
# Run #30 proved the stall is transient and Gradle-side: the adb CLI
# full getprop answered in 2s (331 lines, sdk=29) before Gradle, and
# again in 3s (332 lines) after Gradle failed — yet Gradle's own
# property fetch threw ShellCommandUnresponsiveException 3x at its
# FIRST device contact (each ~2.5s apart, right after the
# EmulatorConsole failure). A local bytecode read of ddmlib's
# PropertyFetcher shows why the CLI succeeds where Gradle fails:
# the fetch runs plain `getprop` with a 2-SECOND no-output window,
# so any initial-output stall longer than 2s fails it even when the
# full output would arrive just after. And the fetch is
# intermittent, not deterministic: runs #26 and #29 got past it on
# the same class of setup.
# Run #31 proved the NEXT gate is the same kind of race: attempt 1
# hit the property-fetch signature and retried as designed, and
# attempt 2 got PAST property fetch to APK installation — then the
# installer saw API level=1 ("Cannot install split APKs with API
# level < 21") while the post-failure probe again showed the real
# device at sdk=29. That is the known ddmlib fail-open: the
# installer's version check reads DEFAULT(1) when properties are
# not yet populated at its first call. Attempt 2 was fast (1m 22s,
# 65 tasks up-to-date), so retries stay cheap. So: retry the
# connected task when — and ONLY when — the attempt log shows one
# of the two first-contact signatures (property fetch OR split-APK
# API-level-1 install). A run that actually executes tests
# (pass or fail) is never retried, so real failures still fail
# honestly. Each retry reuses the warm daemon and up-to-date
# tasks, so it reaches device selection in ~1-2 min.
set +e
GRADLE_RC=0
ATTEMPT=1
while [ "$ATTEMPT" -le 3 ]; do
  echo "GRADLE_ATTEMPT: $ATTEMPT of 3"
  ./gradlew :app:connectedDebugAndroidTest --stacktrace \
    -Pandroid.testInstrumentationRunnerArguments.fixture_host=10.0.2.2 \
    -Pandroid.testInstrumentationRunnerArguments.fixture_port=2222 \
    -Pandroid.testInstrumentationRunnerArguments.fixture_user=termbridge-test \
    -Pandroid.testInstrumentationRunnerArguments.fixture_password="$FIXTURE_PASSWORD" 2>&1 | tee "gradle-attempt-$ATTEMPT.log"
  GRADLE_RC=${PIPESTATUS[0]}
  if [ "$GRADLE_RC" -eq 0 ]; then
    break
  fi
  if [ "$ATTEMPT" -lt 3 ] && grep -q -e "Unknown API Level" -e "PropertyFetcher" -e "Cannot install split APKs" "gradle-attempt-$ATTEMPT.log"; then
    echo "GRADLE_ATTEMPT: $ATTEMPT failed on a flaky first-contact signature; retrying in 30s"
    sleep 30
    ATTEMPT=$((ATTEMPT + 1))
    continue
  fi
  break
done
set -e
# --- End retry ---

# --- Post-failure probe (PropertyFetcher investigation, run #25) ---
# Run #25 proved the PRE-Gradle probe answers (full getprop rc=0, 4s,
# 330 lines, sdk=29) while Gradle's own property query still fails.
# This probe answers the remaining question: is adbd STILL answering
# after Gradle fails, or does something degrade during the build?
# It runs ONLY when Gradle fails, and every command is guarded so
# diagnostics can never mask Gradle's result. The script then exits
# with Gradle's original code, so a failed run still fails honestly.
if [ "$GRADLE_RC" -ne 0 ]; then
  echo "POSTFAIL: gradle rc=$GRADLE_RC — re-probing device state"
  timeout 60 "$ADB_BIN" -s emulator-5554 devices -l 2>&1 | sed 's/^/POSTFAIL: devices: /' || true
  POSTFAIL_T0="$(date +%s)"
  if timeout 120 "$ADB_BIN" -s emulator-5554 shell getprop > /tmp/termbridge-postfail-getprop.txt 2>/tmp/termbridge-postfail-getprop.err; then
    POSTFAIL_RC=0
  else
    POSTFAIL_RC=$?
  fi
  POSTFAIL_T1="$(date +%s)"
  echo "POSTFAIL: full getprop rc=$POSTFAIL_RC elapsed=$((POSTFAIL_T1 - POSTFAIL_T0))s lines=$(wc -l < /tmp/termbridge-postfail-getprop.txt 2>/dev/null || echo '?')"
  echo "POSTFAIL: sdk=$(timeout 60 "$ADB_BIN" -s emulator-5554 shell getprop ro.build.version.sdk 2>/dev/null || echo 'TIMEOUT_OR_ERROR')"
  # --- Server-side fixture state (run #32 diagnosis) ---
  # Run #32's artifact proved the tests used the right endpoint
  # (10.0.2.2:2222) but could NOT name the connect failure: the app
  # deliberately drops the sshj cause chain (SshTransportException
  # has no cause parameter, by security design), so the XML, logcat,
  # and UTP logs all show only the fixed "Network error" message.
  # The fixture sshd log is the only record that can say whether
  # the guest's connections reached the server at all, and at which
  # step they failed. Capture it here, guarded like everything else
  # in this probe: diagnostics must never mask Gradle's result, and
  # nothing here echoes the fixture password (sshd never logs it).
  if [ -f /tmp/termbridge-sshd-fixture/sshd.pid ]; then
    POSTFAIL_SSHD_PID="$(cat /tmp/termbridge-sshd-fixture/sshd.pid 2>/dev/null || echo '?')"
    echo "POSTFAIL: fixture sshd pid file: $POSTFAIL_SSHD_PID"
    if sudo kill -0 "$POSTFAIL_SSHD_PID" 2>/dev/null; then
      echo "POSTFAIL: fixture sshd process is still running"
    else
      echo "POSTFAIL: fixture sshd process is NOT running"
    fi
  else
    echo "POSTFAIL: fixture sshd pid file is missing"
  fi
  if command -v ss >/dev/null 2>&1; then
    ss -ltn 2>/dev/null | grep ":2222 " | sed 's/^/POSTFAIL: listening: /' || echo "POSTFAIL: nothing listening on port 2222 (host ss view)"
  fi
  echo "POSTFAIL: fixture sshd.log follows (server side of every connection attempt)"
  sudo cat /tmp/termbridge-sshd-fixture/sshd.log 2>/dev/null | sed 's/^/POSTFAIL: sshd: /' || echo "POSTFAIL: sshd.log unavailable"
  echo "POSTFAIL: end of fixture sshd.log"
  # --- End server-side fixture state ---
fi
# --- End post-failure probe ---

exit "$GRADLE_RC"
