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
#   - accepts password auth for the main user and PUBLIC-KEY auth
#     for a second user (so the private-key code path is exercised
#     against a real server, review #2 F-12)
#   - offers ed25519, RSA and ECDSA host keys, and logs at DEBUG1 so
#     a pre-authentication failure leaves evidence in sshd.log
#   - AcceptEnv TERM_BRIDGE_* (so the env accepted/rejected test has
#     a real policy to hit)
#
# What it NEVER does:
#   - echo, log, or store the password (it arrives only via
#     the FIXTURE_PASSWORD environment variable)
#   - touch a real server, a real user, or real credentials
#   - listen on anything but 127.0.0.1 (the emulator reaches the
#     host's loopback as 10.0.2.2, so loopback is sufficient)
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
FIXTURE_KEY_USER="${FIXTURE_KEY_USER:-termbridge-key}"
FIXTURE_VNC_PORT="${FIXTURE_VNC_PORT:-5901}"
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

if ! id "$FIXTURE_KEY_USER" >/dev/null 2>&1; then
    sudo useradd -m -s /bin/bash "$FIXTURE_KEY_USER"
fi
# The key user has no password. NOT `passwd -l`: with UsePAM no, sshd
# refuses LOCKED accounts even for public-key login ("account is
# locked"). A `*` password field means "no password login" while still
# permitting key auth; the Match block below also disables passwords.
sudo usermod -p '*' "$FIXTURE_KEY_USER"

sudo mkdir -p "$FIXTURE_DIR" /run/sshd
sudo chmod 755 "$FIXTURE_DIR"

if [ ! -f "$FIXTURE_DIR/ssh_host_ed25519_key" ]; then
    sudo ssh-keygen -t ed25519 -f "$FIXTURE_DIR/ssh_host_ed25519_key" -N "" -q
fi

if [ ! -f "$FIXTURE_DIR/ssh_host_rsa_key" ]; then
    sudo ssh-keygen -t rsa -b 3072 -f "$FIXTURE_DIR/ssh_host_rsa_key" -N "" -q
fi
if [ ! -f "$FIXTURE_DIR/ssh_host_ecdsa_key" ]; then
    sudo ssh-keygen -t ecdsa -b 256 -f "$FIXTURE_DIR/ssh_host_ecdsa_key" -N "" -q
fi

# Client key for the public-key user: Ed25519 in OpenSSH format (the
# format sshj 0.40.0 reads for Ed25519), generated per run, never
# committed. Not RSA: OpenSSH 8.8+ refuses ssh-rsa (SHA-1) user-auth
# signatures, which would fail for reasons unrelated to the app.
sudo rm -f "$FIXTURE_DIR/client_key" "$FIXTURE_DIR/client_key.pub"
sudo ssh-keygen -t ed25519 -f "$FIXTURE_DIR/client_key" -N "" -q
sudo cp "$FIXTURE_DIR/client_key.pub" "$FIXTURE_DIR/authorized_keys_$FIXTURE_KEY_USER"
sudo chmod 644 "$FIXTURE_DIR/authorized_keys_$FIXTURE_KEY_USER"
sudo chmod 644 "$FIXTURE_DIR/client_key"

sudo tee "$FIXTURE_DIR/sshd_config" >/dev/null <<EOF
Port $FIXTURE_PORT
ListenAddress 127.0.0.1
LogLevel DEBUG1
PidFile $FIXTURE_DIR/sshd.pid
HostKey $FIXTURE_DIR/ssh_host_ed25519_key
HostKey $FIXTURE_DIR/ssh_host_rsa_key
HostKey $FIXTURE_DIR/ssh_host_ecdsa_key
PasswordAuthentication yes
PubkeyAuthentication yes
AuthorizedKeysFile $FIXTURE_DIR/authorized_keys_%u
ChallengeResponseAuthentication no
UsePAM no
StrictModes no
PrintMotd no
AcceptEnv TERM_BRIDGE_*
Subsystem sftp /usr/lib/openssh/sftp-server
# Match blocks must come last (Subsystem is not allowed inside one).
Match User $FIXTURE_KEY_USER
    PasswordAuthentication no
Match User $FIXTURE_USER
    PubkeyAuthentication no
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
        for k in ed25519 rsa ecdsa; do
            sudo ssh-keygen -lf "$FIXTURE_DIR/ssh_host_${k}_key.pub"
        done
        echo "Fixture user: $FIXTURE_USER (password supplied via FIXTURE_PASSWORD only)."
        exit 0
    fi
    sleep 1
done
echo "Fixture sshd failed to listen on port $FIXTURE_PORT." >&2
sudo cat "$FIXTURE_DIR/sshd.log" 2>/dev/null || true
exit 1

# ---- Optional VNC server for the RFB integration test (best effort) ----
# A virtual display plus x11vnc on loopback with a per-run password. If
# the packages cannot be installed the VNC test is skipped (Assume), so
# this never fails the SSH fixture.
rm -f /tmp/termbridge-vnc-pass
if sudo apt-get install -y -qq xvfb x11vnc >/dev/null 2>&1; then
    VNC_PASS="$(openssl rand -hex 6)"
    sudo pkill Xvfb >/dev/null 2>&1 || true
    (Xvfb :99 -screen 0 800x600x24 >/dev/null 2>&1 &)
    sleep 2
    x11vnc -storepasswd "$VNC_PASS" /tmp/termbridge-vncauth >/dev/null 2>&1
    x11vnc -display :99 -rfbport "$FIXTURE_VNC_PORT" -rfbauth /tmp/termbridge-vncauth \
        -localhost -forever -shared -bg -o /tmp/termbridge-x11vnc.log >/dev/null 2>&1 || true
    sleep 1
    if (exec 3<>/dev/tcp/127.0.0.1/"$FIXTURE_VNC_PORT") 2>/dev/null; then
        printf '%s' "$VNC_PASS" > /tmp/termbridge-vnc-pass
        echo "VNC fixture ready on 127.0.0.1:$FIXTURE_VNC_PORT"
    else
        echo "VNC fixture could not start; the RFB test will be skipped."
    fi
else
    echo "VNC packages unavailable; the RFB test will be skipped."
fi
