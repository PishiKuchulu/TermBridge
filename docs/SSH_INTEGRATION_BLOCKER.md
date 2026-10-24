# TermBridge — SSH Integration-Test Blocker (Prompt 05)

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
