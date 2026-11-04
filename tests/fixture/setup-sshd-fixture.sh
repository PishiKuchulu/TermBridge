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
#   - AllowTcpForwarding yes, plus the item T-20 extras: a second
#     (bastion) sshd for jump chains, a python3 TCP echo service as
#     the forwarding target, and a python3 no-auth SOCKS5 proxy
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
# Item T-20 fixture extras: a bastion sshd for the jump-chain
# test, a TCP echo service as the forwarding target, and a
# no-auth SOCKS5 proxy for the proxied-connection test.
FIXTURE_BASTION_PORT="${FIXTURE_BASTION_PORT:-2223}"
FIXTURE_ECHO_PORT="${FIXTURE_ECHO_PORT:-18081}"
FIXTURE_SOCKS_PORT="${FIXTURE_SOCKS_PORT:-10809}"
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
# The directory must belong to the invoking user, not root:
# later steps launch the echo/SOCKS services WITHOUT sudo and
# write their $svc.log / $svc.pid files into this directory.
# With a root-owned directory those unprivileged redirects
# fail with Permission denied — the exact failure that killed
# CI run #64's instrumented job (echo.log / echo.pid).
# sshd itself runs as root, so it reads its keys, configs and
# authorized_keys files here regardless of who owns the dir.
sudo chown "$(id -un)" "$FIXTURE_DIR"

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
# committed.
sudo rm -f "$FIXTURE_DIR/client_key" "$FIXTURE_DIR/client_key.pub"
sudo ssh-keygen -t ed25519 -f "$FIXTURE_DIR/client_key" -N "" -q
sudo cp "$FIXTURE_DIR/client_key.pub" "$FIXTURE_DIR/authorized_keys_$FIXTURE_KEY_USER"
sudo chmod 644 "$FIXTURE_DIR/authorized_keys_$FIXTURE_KEY_USER"
sudo chmod 644 "$FIXTURE_DIR/client_key"

# Item R-10: a second client key, RSA-3072. OpenSSH 8.8+ refuses
# ssh-rsa (SHA-1) user-auth signatures, but sshj signs RSA keys
# with rsa-sha2-256/512, which modern OpenSSH accepts — this key
# exists to prove exactly that path end to end.
sudo rm -f "$FIXTURE_DIR/client_key_rsa" "$FIXTURE_DIR/client_key_rsa.pub"
sudo ssh-keygen -t rsa -b 3072 -f "$FIXTURE_DIR/client_key_rsa" -N "" -q
# NOTE: the append must run under sudo (tee -a), not as
# `sudo cat ... >> file` — a redirect is performed by the
# calling shell, so with a root-owned authorized_keys file
# (created by the sudo cp above) the unprivileged redirect
# fails with Permission denied. That exact failure killed
# CI run #63's instrumented job at this line.
cat "$FIXTURE_DIR/client_key_rsa.pub" | sudo tee -a "$FIXTURE_DIR/authorized_keys_$FIXTURE_KEY_USER" >/dev/null
sudo chmod 644 "$FIXTURE_DIR/client_key_rsa"

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
# Item T-20: forwarding is exercised against this fixture;
# state the permission instead of relying on sshd's default.
AllowTcpForwarding yes
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

# ---- Item T-20 extras: bastion sshd, echo service, SOCKS5 ----
# Bastion: a second sshd on its own port, same users. Item R-10:
# it presents its OWN host keys (different from the main sshd's)
# so the changed-host-key test can re-point a local relay from
# the main sshd to this one and watch the app report a CHANGED
# key for what is, to the app, the same endpoint.
if [ ! -f "$FIXTURE_DIR/ssh_host_ed25519_key_bastion" ]; then
    sudo ssh-keygen -t ed25519 -f "$FIXTURE_DIR/ssh_host_ed25519_key_bastion" -N "" -q
fi
sudo tee "$FIXTURE_DIR/sshd_config_bastion" >/dev/null <<EOF
Port $FIXTURE_BASTION_PORT
ListenAddress 127.0.0.1
LogLevel DEBUG1
PidFile $FIXTURE_DIR/sshd-bastion.pid
HostKey $FIXTURE_DIR/ssh_host_ed25519_key_bastion
PasswordAuthentication yes
PubkeyAuthentication yes
AuthorizedKeysFile $FIXTURE_DIR/authorized_keys_%u
ChallengeResponseAuthentication no
UsePAM no
StrictModes no
PrintMotd no
AllowTcpForwarding yes
Subsystem sftp /usr/lib/openssh/sftp-server
Match User $FIXTURE_KEY_USER
    PasswordAuthentication no
Match User $FIXTURE_USER
    PubkeyAuthentication no
EOF
if [ -f "$FIXTURE_DIR/sshd-bastion.pid" ] && sudo kill -0 "$(cat "$FIXTURE_DIR/sshd-bastion.pid")" 2>/dev/null; then
    sudo kill "$(cat "$FIXTURE_DIR/sshd-bastion.pid")"
    sleep 1
fi
sudo /usr/sbin/sshd -f "$FIXTURE_DIR/sshd_config_bastion" -E "$FIXTURE_DIR/sshd-bastion.log"

# Echo + SOCKS services: plain python3, loopback only, per-run
# processes (pid files under FIXTURE_DIR; the CI runner reaps
# everything at job end regardless).
FIXTURE_SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
for svc in echo socks; do
    if [ "$svc" = echo ]; then
        svc_port="$FIXTURE_ECHO_PORT"; svc_script="echo_server.py"
    else
        svc_port="$FIXTURE_SOCKS_PORT"; svc_script="socks5_proxy.py"
    fi
    if [ -f "$FIXTURE_DIR/$svc.pid" ] && kill -0 "$(cat "$FIXTURE_DIR/$svc.pid")" 2>/dev/null; then
        kill "$(cat "$FIXTURE_DIR/$svc.pid")" || true
        sleep 1
    fi
    nohup python3 "$FIXTURE_SCRIPT_DIR/$svc_script" "$svc_port" \
        >"$FIXTURE_DIR/$svc.log" 2>&1 &
    echo $! > "$FIXTURE_DIR/$svc.pid"
done

# Verify: every service must actually be listening, or fail
# the job (a missing capability must never become a silent
# test skip in CI).
port_listening() {
    (command -v ss >/dev/null 2>&1 && ss -ltn | grep -q ":$1 ") || \
    (command -v nc >/dev/null 2>&1 && nc -z 127.0.0.1 "$1")
}
wait_for_port() {
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        if port_listening "$1"; then
            return 0
        fi
        sleep 1
    done
    return 1
}
if ! wait_for_port "$FIXTURE_PORT"; then
    echo "Fixture sshd failed to listen on port $FIXTURE_PORT." >&2
    sudo cat "$FIXTURE_DIR/sshd.log" 2>/dev/null || true
    exit 1
fi
echo "Fixture sshd listening on port $FIXTURE_PORT."
for extra in "$FIXTURE_BASTION_PORT:bastion sshd" \
             "$FIXTURE_ECHO_PORT:echo service" \
             "$FIXTURE_SOCKS_PORT:SOCKS5 proxy"; do
    extra_port="${extra%%:*}"
    extra_name="${extra#*:}"
    if ! wait_for_port "$extra_port"; then
        echo "Fixture $extra_name failed to listen on port $extra_port." >&2
        exit 1
    fi
    echo "Fixture $extra_name listening on port $extra_port."
done
for k in ed25519 rsa ecdsa; do
    sudo ssh-keygen -lf "$FIXTURE_DIR/ssh_host_${k}_key.pub"
done
echo "Fixture user: $FIXTURE_USER (password supplied via FIXTURE_PASSWORD only)."
exit 0

# ---- Optional VNC server for the RFB integration test (best effort) ----
# A virtual display plus x11vnc on loopback with a per-run password. If
# anything fails, the reason is written to /tmp/termbridge-vnc-status.txt
# (read by the CI triage step) and the VNC test is skipped (Assume), so
# this never fails the SSH fixture.
STATUS=/tmp/termbridge-vnc-status.txt
: > "$STATUS"
rm -f /tmp/termbridge-vnc-pass
{
    sudo apt-get update -qq
    sudo apt-get install -y -qq xvfb x11vnc
} >>"$STATUS" 2>&1 || echo "apt install failed" >>"$STATUS"
if command -v x11vnc >/dev/null 2>&1 && command -v Xvfb >/dev/null 2>&1; then
    VNC_PASS="$(openssl rand -hex 6)"
    sudo pkill Xvfb >/dev/null 2>&1 || true
    (Xvfb :99 -screen 0 800x600x24 >>"$STATUS" 2>&1 &)
    sleep 3
    x11vnc -storepasswd "$VNC_PASS" /tmp/termbridge-vncauth >>"$STATUS" 2>&1 || echo "storepasswd failed" >>"$STATUS"
    x11vnc -display :99 -rfbport "$FIXTURE_VNC_PORT" -rfbauth /tmp/termbridge-vncauth \
        -localhost -forever -shared -bg -o /tmp/termbridge-x11vnc.log >>"$STATUS" 2>&1 || echo "x11vnc start failed" >>"$STATUS"
    sleep 2
    if (exec 3<>/dev/tcp/127.0.0.1/"$FIXTURE_VNC_PORT") 2>/dev/null; then
        printf '%s' "$VNC_PASS" > /tmp/termbridge-vnc-pass
        echo "VNC fixture ready on 127.0.0.1:$FIXTURE_VNC_PORT" | tee -a "$STATUS"
    else
        echo "VNC port not reachable; the RFB test will be skipped." | tee -a "$STATUS"
        tail -n 20 /tmp/termbridge-x11vnc.log >>"$STATUS" 2>&1 || true
    fi
else
    echo "VNC packages missing; the RFB test will be skipped." | tee -a "$STATUS"
fi
