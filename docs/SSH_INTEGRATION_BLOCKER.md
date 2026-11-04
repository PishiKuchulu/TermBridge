# TermBridge — SSH Integration-Test Blocker (Prompt 05)

## Update, 2026-10-25 (remediation phase 3) — PARTIALLY UNBLOCKED

Status: a fixture now EXISTS and tier-1 tests are implemented
against it. The original prompt-05 block below is preserved as
history; it no longer describes the whole suite.

- Fixture: `tests/fixture/setup-sshd-fixture.sh` provisions a
  dedicated OpenSSH instance (own config + host keys, port
  2222, password auth, `AcceptEnv TERM_BRIDGE_*`) without
  touching the system sshd. Credentials travel only as
  instrumentation arguments, never committed.
- Harness: `SshFixture` (androidTest) reads fixture
  coordinates from instrumentation arguments and
  Assume-skips every test when no fixture is configured —
  a skip, never a fake pass.
- Implemented (12 tests, real server, no mocks):
  handshake + password auth, wrong-credentials failure,
  unknown-key trust flow (reject / trust / match-no-dialog),
  disconnect + cancel, shell streaming, SFTP round-trip,
  SFTP zero-byte + Unicode + cancellation, button execute,
  button insert-never-executes (proven by file absence),
  env accepted/rejected reporting, working-directory pwd,
  SFTP download landing in a local folder.
- Still blocked (1 test, @Ignore'd with its reason):
  changed host key (needs fixture-side key rotation on the
  SAME endpoint — item R-10).
- Item T-20 update (2026-11-04): the five forwarding cases
  are IMPLEMENTED. The fixture now also provisions a bastion
  sshd (port 2223), a python3 TCP echo service (18081) and a
  python3 no-auth SOCKS5 proxy (10809), and both sshd configs
  state AllowTcpForwarding yes; the wrapper passes the three
  ports as instrumentation arguments and the tests skip only
  when they are absent. The cases: local forward + per-forward
  stop, remote forward + cancel, jump chain + target-reject,
  SOCKS (proven by dialling "localhost", which only the
  host-side proxy can reach), and partial failure (a remote
  forward onto the echo port is refused; the app's
  close-on-any-failure rule then frees the started forward).
  These tests are compile-proven locally; their first live
  run is the CI instrumented job of the push carrying them.
- Correction to an old premise: SftpManager has NO
  working-directory concept — paths are absolute only. The
  old test #12 premise ("SFTP starts in the working
  directory") is therefore not implemented as written; the
  replacement test proves absolute-path transfer plus local
  landing, which is what the code actually does.
- CI wiring is pending (remediation phase 7): the
  instrumented job must provision the fixture before the
  emulator run. Until that job is green, tier 1 is
  implemented-but-not-CI-verified.

## Original block (prompt 05, preserved)

Status: BLOCKED — no controlled SSH test server is available in
this sandbox or in CI at the time of writing.

## What is missing

A controlled SSH fixture: an OpenSSH server in a container or a
dedicated test VM, with a test user, a known password and/or key,
a known host key, and a way to rotate that host key (for the
CHANGED test). Without it, no test can honestly be called an
integration or end-to-end test.

## What was done instead (and labelled honestly)

- JVM unit tests for the parts that do not need a server:
  fingerprint computation, host-key policy, error mapping.
- An ignored instrumented test class
  (`SshIntegrationTest`, `@Ignore`) listing exactly what the
  fixture tests must prove, with no mock pretending to be a server.
- The transport code itself is written against the real sshj
  library, not a mock transport — so the first fixture run exercises
  the real handshake, not a fake.

## What unblocks it

1. A test-server fixture definition (container image + setup
   script) committed to the project, with test-only credentials
   that are never real user credentials.
2. A CI job (or a documented local run) that starts the fixture
   and runs `SshIntegrationTest` with `@Ignore` removed.
3. A host-key rotation step in the fixture, for the CHANGED test.

Until then, prompt 05's acceptance criterion "a real SSH
handshake/authentication succeeds against a controlled endpoint"
is NOT marked passed. It is marked: implemented, unit-tested,
integration-blocked. The first real-server run — by the user on a
real device against a real server, or by CI once the fixture
exists — is the proof that closes this item, and it must be
reported with the server type and result, not assumed.

## Update, 2026-11-04 — item R-10

The changed-host-key case no longer needs a fixture
restart hook: the bastion sshd now presents its OWN host
keys, and `changedHostKeyFlow` dials a test-local loopback
relay that is re-pointed from the main sshd to the bastion
mid-test — the dialed endpoint is constant, the presented
key changes. An RSA-3072 client-key case
(`rsaSha2ClientKeyAuthenticates`) was added alongside.
All 21 SshIntegrationTest cases are implemented; none is
@Ignore'd. First live run: the CI run of the G0–G7 push.
