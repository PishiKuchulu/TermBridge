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

./gradlew :app:connectedDebugAndroidTest --stacktrace \
  -Pandroid.testInstrumentationRunnerArguments.fixture_host=10.0.2.2 \
  -Pandroid.testInstrumentationRunnerArguments.fixture_port=2222 \
  -Pandroid.testInstrumentationRunnerArguments.fixture_user=termbridge-test \
  -Pandroid.testInstrumentationRunnerArguments.fixture_password="$FIXTURE_PASSWORD"
