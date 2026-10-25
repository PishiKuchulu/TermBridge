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
- Still blocked (6 tests, each @Ignore'd with its own reason):
  changed host key (needs fixture-side key rotation on the
  SAME endpoint), local forward (needs a fixture echo
  service), remote forward (needs a fixture-side service),
  jump chain (needs a second fixture sshd as bastion),
  SOCKS proxy (needs a fixture SOCKS proxy), partial tunnel
  failure (needs a fixture that rejects one forward).
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
