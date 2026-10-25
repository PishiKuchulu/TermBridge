#!/usr/bin/env bash
# TermBridge SSH fixture: a throwaway OpenSSH server for the
# instrumented integration tests (SshIntegrationTest).
#
# What it does:
#   - installs openssh-server (Ubuntu/Debian, needs sudo)
#   - creates a test-only user (default: termbridge-test)
#   - runs a DEDICATED sshd instance on port 2222 with its own
#     config + host keys under /tmp/termbridge-sshd-fixture
#     (the system sshd is never touched or restarted)
#   - accepts password auth, AcceptEnv TERM_BRIDGE_* (so the
#     env accepted/rejected test has a real policy to hit)
#
# What it NEVER does:
#   - echo, log, or store the password (it arrives only via
#     the FIXTURE_PASSWORD environment variable)
#   - touch a real server, a real user, or real credentials
#   - open anything except the fixture port on loopback-reachable
#     interfaces (the emulator reaches it as 10.0.2.2)
#
# Usage (CI):
#   FIXTURE_PASSWORD="$(openssl rand -base64 24)" \
#     tests/fixture/setup-sshd-fixture.sh
#   ./gradlew :app:connectedDebugAndroidTest \
#     -Pandroid.testInstrumentationRunnerArguments.fixture_password="$FIXTURE_PASSWORD"
#
# Usage (local Linux with an emulator):
#   FIXTURE_PASSWORD='test-only-password' tests/fixture/setup-sshd-fixture.sh
#   # then run the instrumented tests with fixture_password set
#   # to the same value (see SshFixture.kt for all arguments).
#
# The script FAILS (non-zero exit) if sshd is not listening
# afterwards, so a CI job can never silently run the fixture
# tests against nothing — without a fixture the tests SKIP via
# Assume, and a failed provisioning must fail the job instead.
set -euo pipefail

FIXTURE_USER="${FIXTURE_USER:-termbridge-test}"
FIXTURE_PORT="${FIXTURE_PORT:-2222}"
FIXTURE_DIR="${FIXTURE_DIR:-/tmp/termbridge-sshd-fixture}"

if [ -z "${FIXTURE_PASSWORD:-}" ]; then
    echo "FIXTURE_PASSWORD is required (never commit a value)." >&2
    exit 1
fi

if ! command -v sshd >/dev/null 2>&1; then
    echo "Installing openssh-server..."
    sudo apt-get update -qq
    sudo apt-get install -y -qq openssh-server
fi

if ! id "$FIXTURE_USER" >/dev/null 2>&1; then
    sudo useradd -m -s /bin/bash "$FIXTURE_USER"
fi
# Password via stdin only — never on a command line, never echoed.
printf '%s:%s\n' "$FIXTURE_USER" "$FIXTURE_PASSWORD" | sudo chpasswd

sudo mkdir -p "$FIXTURE_DIR" /run/sshd
sudo chmod 755 "$FIXTURE_DIR"

if [ ! -f "$FIXTURE_DIR/ssh_host_ed25519_key" ]; then
    sudo ssh-keygen -t ed25519 -f "$FIXTURE_DIR/ssh_host_ed25519_key" -N "" -q
fi

sudo tee "$FIXTURE_DIR/sshd_config" >/dev/null <<EOF
Port $FIXTURE_PORT
ListenAddress 0.0.0.0
PidFile $FIXTURE_DIR/sshd.pid
HostKey $FIXTURE_DIR/ssh_host_ed25519_key
PasswordAuthentication yes
PubkeyAuthentication no
ChallengeResponseAuthentication no
UsePAM no
StrictModes no
PrintMotd no
AcceptEnv TERM_BRIDGE_*
Subsystem sftp /usr/lib/openssh/sftp-server
EOF

# Restart the dedicated instance if one is already running.
if [ -f "$FIXTURE_DIR/sshd.pid" ] && sudo kill -0 "$(cat "$FIXTURE_DIR/sshd.pid")" 2>/dev/null; then
    sudo kill "$(cat "$FIXTURE_DIR/sshd.pid")"
    sleep 1
fi
sudo /usr/sbin/sshd -f "$FIXTURE_DIR/sshd_config" -E "$FIXTURE_DIR/sshd.log"

# Verify: sshd must actually be listening, or fail the job.
for _ in 1 2 3 4 5 6 7 8 9 10; do
    if (command -v ss >/dev/null 2>&1 && ss -ltn | grep -q ":$FIXTURE_PORT ") || \
       (command -v nc >/dev/null 2>&1 && nc -z 127.0.0.1 "$FIXTURE_PORT"); then
        echo "Fixture sshd listening on port $FIXTURE_PORT."
        sudo ssh-keygen -lf "$FIXTURE_DIR/ssh_host_ed25519_key.pub"
        echo "Fixture user: $FIXTURE_USER (password supplied via FIXTURE_PASSWORD only)."
        exit 0
    fi
    sleep 1
done
echo "Fixture sshd failed to listen on port $FIXTURE_PORT." >&2
sudo cat "$FIXTURE_DIR/sshd.log" 2>/dev/null || true
exit 1
